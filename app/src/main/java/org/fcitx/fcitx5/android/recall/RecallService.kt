/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 屏幕记忆 —— 同进程无障碍服务（冻结方案：内嵌输入法 APK，IME 同进程）。
 *
 * 数据流（全在同进程内完成，消灭 IPC）：
 *   拾取(RecallPicker 定频 + 事件驱动 + 静止退避 + 打字同步)
 *     → 节点树遍历（火力全开：无整屏丢弃规则，可见文字全量捕获）
 *     → 分词双通道(词典/新词发现 + NER) + 过滤(RecallSegmenter)
 *     → 逐词写入自定义词组 + 批量 reload + ack(RecallWordStore + RecallReloader)
 *     → 候选栏即时可用；旁路 OCR(RecallOcrController) 区域 diff + 打字区裁剪兜底。
 *
 * 同进程硬约束（架构前提）：
 *  - 全链路崩溃隔离：任何一步异常都被捕获，绝不拖垮 IME 主线程 / 不引发 ANR；
 *  - 线程隔离：节点树遍历 / OCR / 分词 / 写库全部在后台线程，主线程零拾取逻辑；
 *  - 有界队列：帧缓存 / OCR 在飞标志 / 候选批次均有界，满则丢旧或跳过。
 */
package org.fcitx.fcitx5.android.recall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.fcitx.fcitx5.android.utils.notificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.recall.data.RecallPreferences
import org.fcitx.fcitx5.android.recall.data.RecallRepository
import org.fcitx.fcitx5.android.recall.ocr.RecallOcrController
import org.fcitx.fcitx5.android.recall.picker.RecallFrameCache
import org.fcitx.fcitx5.android.recall.picker.RecallPicker
import org.fcitx.fcitx5.android.recall.segment.RecallCandidate
import org.fcitx.fcitx5.android.recall.segment.RecallSegmenter
import org.fcitx.fcitx5.android.recall.store.RecallReloader
import org.fcitx.fcitx5.android.recall.store.RecallWordStore
import timber.log.Timber
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecallService : AccessibilityService() {

    // MBM v1.0.39 裁决：移除「高级保护模式停采」「密码框/敏感窗口整屏丢弃」等
    // 应用内封锁点——它们只挡得住用户自己，挡不住真正拿到设备的人（虚假安全）。
    // 真正的安全=数据只存本机（SQLCipher 静态加密）+ 不联网，采集本身火力全开。

    @Volatile
    private var enabled = false

    @Volatile
    private var ocrEnabled = true

    @Volatile
    private var autoAddEnabled = true

    @Volatile
    private var autoClipboardEnabled = true

    /** 最近一次事件携带的包名（拾取层连续采样时用作窗口标识）。 */
    @Volatile
    private var lastPkg: String? = null

    /** 最近一次事件携带的窗口 id：与包名组合成帧缓存窗口键（P2-3：同 App 不同界面不再互相去重）。 */
    @Volatile
    private var lastWindowId = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenshotExecutor = newSingleThreadExecutor()

    private val frameCache = RecallFrameCache()
    private val segmenter = RecallSegmenter()

    /**
     * 分词 / 写词库专用单线程执行器：与拾取线程物理隔离（冻结方案硬约束）。
     * 队列有界（32）+ 满则丢最旧：新词发现（互信息/邻字熵）与词库合并是 CPU + IO 活，
     * 旧实现把它们放在拾取 HandlerThread 上同步执行，直接拖慢定频采样。
     */
    private val segmentExecutor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(32),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    /**
     * 包名 → 应用名缓存（有界 LRU）。appLabelOf 走 PackageManager Binder IPC，
     * 旧实现每次采样调用 2 次（captureNodeTree + ingest 各一次），高频下每秒数十次 IPC。
     */
    private val labelCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > 64
    }
    // P2-5 修复：by lazy 惰性初始化，消除 lateinit 在「instance 已赋值、picker 未就绪」
    // 时序窗口内的 UninitializedPropertyAccessException（曾被外层 runCatching 静默吞掉）。
    private val picker: RecallPicker by lazy { RecallPicker { captureTick() } }
    private val ocrController = RecallOcrController { text -> ingest(text, "OCR") }
    private val ocrInFlight = AtomicBoolean(false)

    companion object {
        @Volatile
        var instance: RecallService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        /** IME 打字时刻同步抓取：由 FcitxInputMethodService 在 commitText / 按键时调用。 */
        fun notifyTyping() = instance?.picker?.notifyTyping()

        /** MBM v1.0.58：保活常驻通知（前台服务）ID 与渠道。 */
        private const val KEEPALIVE_CHANNEL_ID = "mbm_recall_keepalive"
        private const val KEEPALIVE_NOTIF_ID = 0x5265 // "Re"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // MBM v1.0.58：立即提升为前台服务并挂常驻通知——这是「打开后一直打开、不被系统杀死」
        // 的核心保活手段。若延迟到首帧之后再 startForeground，系统可能在窗口期内已回收进程
        // （用户感知为约 40s 的「九离」/OCR 中断）。dataSync 类型对应 normal 权限、可长期常驻。
        ensureKeepAliveChannel()
        startForeground(KEEPALIVE_NOTIF_ID, buildKeepAliveNotification())
        // P2-5 修复：picker 惰性初始化（无 lateinit 窗口期），
        // instance 必须最后赋值——保证外部看到 instance 时全部内部状态已就绪。
        // 偏好开关（总 / OCR / 拾词 / 剪贴板）实时同步到内存字段。
        // 总开关额外驱动拾取线程启停：关闭即挂起（冻结方案：全局总开关，关闭即停止采集）。
        scope.launch {
            RecallPreferences.enabledFlow().collect { v ->
                enabled = v
                if (v) picker.start() else picker.stop()
            }
        }
        scope.launch { RecallPreferences.ocrEnabledFlow().collect { ocrEnabled = it } }
        scope.launch { RecallPreferences.autoAddPhraseFlow().collect { autoAddEnabled = it } }
        scope.launch { RecallPreferences.autoClipboardFlow().collect { autoClipboardEnabled = it } }
        // 其余开关初值即时同步（不等 flow 首帧），避免连接窗口期内字段仍是默认值
        scope.launch {
            ocrEnabled = RecallPreferences.isOcrEnabled()
            autoAddEnabled = RecallPreferences.isAutoAddPhrase()
            autoClipboardEnabled = RecallPreferences.isAutoClipboard()
        }

        // picker 的启停完全由上面的总开关 flow 驱动（默认关闭 ⇒ 服务连接后不采集），
        // 这里不再无条件 start()。
        instance = this
        Timber.i("MBM RecallService connected (in-process)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!enabled) return
        val pkg = e.packageName?.toString()
        if (!pkg.isNullOrEmpty()) lastPkg = pkg
        lastWindowId = e.windowId
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> picker.onAccessibilityEvent()
        }
    }

    /** 拾取层每次触发的采样：在 RecallPicker 的后台线程执行，主线程零逻辑。 */
    private fun captureTick() {
        // 隐私硬门控：总开关关闭时绝不采集。旧实现 captureTick 完全不看 enabled
        // （只有 onAccessibilityEvent 看），而定频 tick 不经过事件回调 ⇒ 关闭开关后
        // 仍在定频抓屏并写时间线；enabled 默认 false ⇒ 用户未授权即已开始采集。
        if (!enabled) return
        val pkg = lastPkg ?: return
        if (pkg == packageName) return // 不采集自己的界面
        runCatching { captureNodeTree(pkg) }
            .onFailure { Timber.w(it, "node capture failed") }
    }

    /** 节点树遍历 + 分词 + 写库 + OCR 兜底（全部后台线程）。 */
    private fun captureNodeTree(pkg: String) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            // P1-5 修复：删除 captureMutex —— collected 均为本方法局部变量，
            // walk 无共享状态，包整棵树遍历的锁是纯自我阻塞（拖慢 tick 数十毫秒）。
            val collected = mutableListOf<String>()
            runCatching { walk(root, collected) }
                .onFailure { Timber.w(it, "walk node tree failed") }
            val text = collected.joinToString("\n").takeIf { it.isNotBlank() }
            if (text != null) {
                ingest(text, "NODE")
            }
            // MBM v1.0.58：OCR 由「节点树取不到文字才兜底」改为「节点树有文字也持续跑」，
            // 满足「每 0.1 秒 OCR 一次屏幕」的硬要求。三重门控（区域哈希去重 + inFlight 互斥 +
            // OCR_MIN_INTERVAL_MS=100 限频）保证静止屏零开销（仅 24×24 哈希比对）、变化帧才
            // 触发全量 PaddleOCR 推理，因此即便常跑也不会无谓耗电/卡顿。
            if (ocrEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                captureWithOcr()
            }
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * 统一入口：一段可见文本 → 记录时间线 →（拾词开）分词写库 →（剪贴板开）写剪贴板库。
     * 帧缓存去重在此之上，避免静止屏对同帧重复 ingest。
     */
    private fun ingest(text: String, source: String) {
        if (!enabled) return // 与 captureTick 一致的隐私门控
        val pkg = lastPkg ?: return
        if (!frameCache.shouldCapture("$pkg/$lastWindowId", text, SystemClock.elapsedRealtime())) return
        // record 是 suspend（含 Room 写盘），在后台 IO 协程里异步落库，不让拾取线程阻塞；
        // 帧级去重已前置，DB 内去重仅作最终兜底，异步不影响正确性。
        val label = appLabelOf(pkg)
        scope.launch(Dispatchers.IO) {
            RecallRepository.record(this@RecallService, pkg, label, text, source)
        }
        // MBM v1.0.58：捕获文字是否注入输入法候选栏，由 RecallConfig.INJECT_INTO_IME 统一闸门。
        // 用户硬约束「所有训练文字数据不得出现在输入法之中」——屏幕/OCR 捕获的「训练文字」
        // 只进剪贴板与屏幕记忆库（RecallActivity 面板可回溯），默认【不】写入 fcitx 自定义词组，
        // 因此不再作为候选污染正常打字。已安装应用名（AppPhraseImporter）非训练文字，照常置顶候选。
        if (autoAddEnabled && RecallConfig.INJECT_INTO_IME) {
            // 分词 + 词库合并移出拾取线程：投递到专用单线程执行器（有界队列，满则丢最旧）。
            // 拾取线程只保留 O(1) 帧哈希去重与任务投递，采样节奏不再被分词拖慢。
            val snapshot = text
            runCatching {
                segmentExecutor.execute {
                    if (!enabled) return@execute
                    runCatching {
                        val candidates: List<RecallCandidate> = segmenter.segment(snapshot.lineSequence())
                        if (candidates.isNotEmpty()) RecallWordStore.commit(candidates)
                    }.onFailure { Timber.w(it, "MBM Recall: segment failed") }
                }
            }.onFailure { Timber.w(it, "MBM Recall: segment task rejected (queue bounded)") }
        }
        if (autoClipboardEnabled) RecallClipboardWriter.offer(text)
    }

    /** 遍历节点树抽取文字（火力全开：密码框/敏感标记节点也照常采集，不做整屏丢弃）。 */
    private fun walk(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) out += text
        val hint = node.hintText?.toString()
        if (!hint.isNullOrBlank()) out += hint
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            walk(child, out)
            child.recycleCompat()
        }
    }

    /**
     * 兼容回收（审查报告 P2-1）：自 API 33 起 AccessibilityNodeInfo 改由 GC 管理，
     * `recycle()` 已废弃且为空操作；本项目 minSdk=29，旧机型仍需显式回收。
     * 条件化后既消除 33+ 的无意义调用与编译警告，又不为旧机型引入 native 泄漏。
     */
    @Suppress("DEPRECATION") // 仅在 <33 分支调用，旧机型必需
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { recycle() }
    }

    /** 无障碍自有截图 + 离线 OCR（区域 diff / 打字区裁剪在控制器内）。 */
    private fun captureWithOcr() {
        if (!ocrInFlight.compareAndSet(false, true)) return
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                scope.launch {
                    // P0-1 修复：bitmap / hardwareBuffer 的释放在 finally 统一执行，
                    // wrapHardwareBuffer 失败、processBitmap 抛异常等任何路径都不再泄漏。
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )
                        if (bitmap == null) return@launch
                        ocrController.processBitmap(this@RecallService, bitmap, ocrEnabled)
                    } catch (t: Throwable) {
                        Timber.w(t, "ocr capture failed")
                    } finally {
                        runCatching { bitmap?.takeIf { !it.isRecycled }?.recycle() }
                        runCatching { screenshot.hardwareBuffer.close() }
                        ocrInFlight.set(false)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                Timber.w("Recall screenshot failed: %d", errorCode)
                ocrInFlight.set(false)
            }
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, screenshotExecutor, callback)
        } catch (t: Throwable) {
            Timber.w(t, "Recall screenshot request failed")
            ocrInFlight.set(false)
        }
    }

    /** 包名 → 应用名（带 LRU 缓存，避免高频采样下的重复 Binder IPC）。 */
    private fun appLabelOf(pkg: String): String {
        synchronized(labelCache) { labelCache[pkg]?.let { return it } }
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        synchronized(labelCache) { labelCache[pkg] = label }
        return label
    }

    override fun onInterrupt() = Unit

    /**
     * MBM v1.0.58：解绑时请求系统重新绑定。
     * 无障碍服务被系统临时解绑（而非用户手动关闭）时返回 true，系统会在可重连时再次
     * onServiceConnected，避免长时间停留在「已开启未连接」灰色态（用户体感为「九离」）。
     * 注意：不在此清空 instance——它由 onDestroy 统一清空，保证 isServiceRunning() 在重连间隙稳定。
     */
    override fun onUnbind(intent: Intent?): Boolean = true

    /** 常驻通知渠道（API≥26 必须显式创建，低优先级避免打扰）。 */
    private fun ensureKeepAliveChannel() {
        if (Build.VERSION.SDK_INT >= VERSION_CODES.O) {
            val ch = NotificationChannel(
                KEEPALIVE_CHANNEL_ID,
                getString(R.string.recall),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.recall_service_description) }
            notificationManager.createNotificationChannel(ch)
        }
    }

    /** 保活常驻通知：ongoing + 低优先级，仅用于提升进程优先级防止被回收。 */
    private fun buildKeepAliveNotification(): Notification =
        NotificationCompat.Builder(this, KEEPALIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(getString(R.string.recall))
            .setContentText("屏幕记忆常驻运行中 · 关闭请在系统无障碍设置中关闭本服务")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        runCatching { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) } // 退出前台、移除常驻通知（用户手动关闭或进程终止）
        instance = null
        runCatching { RecallReloader.flushNow() } // 销毁前确保最新词条 reload 生效
        runCatching { picker.dispose() } // 彻底释放拾取线程（服务销毁，不再重启）
        runCatching { segmentExecutor.shutdownNow() }
        runCatching { screenshotExecutor.shutdownNow() }
        scope.cancel()
        super.onDestroy()
    }
}
