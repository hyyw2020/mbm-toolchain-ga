/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall —— Android 端「屏幕记忆 / 回溯」。
 *
 * 实现方式（不使用 MediaProjection，不与系统录屏争抢权限）：
 *  1. 主路径【AccessibilityService 节点树】：监听窗口内容与滚动事件，遍历
 *     AccessibilityNodeInfo 树抽取文字。开销极低、不阻塞界面，因此在光速滑动
 *     高速滚动的场景下也能逐屏（逐帧）捕获。
 *  2. 兜底路径【AccessibilityService 自带 takeScreenshot + 离线 OCR】：
 *     当内容位于 Canvas / 游戏 / 图片等拿不到节点文字的场景时启用。
 *     takeScreenshot 属于无障碍服务自身能力，不需要录屏权限。
 *
 * 隐私：密码输入框内容一律不采集、不索引（node.isPassword 直接跳过）；
 *      银行/支付类应用可在设置中排除；数据仅存本机 SQLCipher 加密库。
 */
package org.fcitx.fcitx5.android.recall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.recall.data.RecallPreferences
import org.fcitx.fcitx5.android.recall.data.RecallRepository
import org.fcitx.fcitx5.android.recall.data.RecallAuditLog
import org.fcitx.fcitx5.android.recall.ocr.OcrEngine
import org.fcitx.fcitx5.android.recall.PhraseAutoWriter
import org.fcitx.fcitx5.android.recall.RecallClipboardWriter
import org.fcitx.fcitx5.android.plugin.recall.R
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RecallService : AccessibilityService() {

    /** APM（系统高级保护模式）命中时置位：服务整体停用并常驻提示，不静默降级 */
    @Volatile
    private var blockedByApm = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var enabled = false

    @Volatile
    private var ocrEnabled = true

    @Volatile
    private var autoAddEnabled = true

    @Volatile
    private var autoClipboardEnabled = true

    @Volatile
    private var lastNodeCaptureAt = 0L

    @Volatile
    private var lastScreenshotAt = 0L

    // OCR 兜底是否正在执行（同一时刻只允许一张截图在 OCR 中）。
    // 注意：此处不能用 kotlinx Mutex——tryLock 在 captureNodeTree 协程里加锁，
    // 而 unlock 在 takeScreenshot 回调新起的协程里执行，跨协程 unlock 会抛
    // IllegalStateException 且锁永久卡死，导致 OCR 兜底路径第一张截图后失效。
    // 故用 AtomicBoolean 的 CAS 做跨线程安全标志。
    private val ocrInFlight = AtomicBoolean(false)
    /** 串行化节点树捕获，避免并发持有多个 rootInActiveWindow（同屏只有一个快照在内存） */
    private val captureMutex = Mutex()
    private var trimJob: Job? = null

    companion object {
        /** 节点树路径最小间隔：保证高速滚动时也能逐屏捕获，同时避免过快重复 */
        private const val NODE_CAPTURE_INTERVAL_MS = 120L
        /** 截图 + OCR 路径最小间隔 */
        private const val SCREENSHOT_INTERVAL_MS = 1_200L
        // MBM v1.0.31：移除 MAX_TEXT_LENGTH=4000 静默截断。
        // 捕获文本无字数上限：大文本由 RecallRepository 外置 blob 存储
        // （RecallBlobStore，DB 行只存元数据），不再有 take() 丢尾。

        @Volatile
        var running: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        // MBM v1.0.26：系统高级保护模式探测（P0-2 裁决：显式停用，不静默降级）
        if (AdvancedProtectionProbe.isAdvancedProtectionEnabled(this)) {
            blockedByApm = true
            enabled = false
            runCatching {
                RecallAuditLog.log(RecallAuditLog.ACTION_APM_BLOCK, "系统高级保护模式已开启，Recall 停用")
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, R.string.recall_disclosure_apm_blocked, Toast.LENGTH_LONG).show()
            }
            return
        }
        scope.launch {
            RecallPreferences.enabledFlow().collect { enabled = it }
        }
        scope.launch {
            RecallPreferences.ocrEnabledFlow().collect { ocrEnabled = it }
        }
        scope.launch {
            RecallPreferences.autoAddPhraseFlow().collect { autoAddEnabled = it }
        }
        scope.launch {
            RecallPreferences.autoClipboardFlow().collect { autoClipboardEnabled = it }
        }
        // 首次运行时读取一次，避免 flow 尚未发射
        scope.launch {
            enabled = RecallPreferences.isEnabled()
            ocrEnabled = RecallPreferences.isOcrEnabled()
            autoAddEnabled = RecallPreferences.isAutoAddPhrase()
            autoClipboardEnabled = RecallPreferences.isAutoClipboard()
        }
        // MBM v1.0.31：过期清理默认关闭（keepDays<=0 = 永久保留），
        // 旧代码硬编码 trim(…, 30) 已移除；用户设了保留天数才清理，且清理记审计日志。
        trimJob = scope.launch {
            RecallRepository.gcBlobs(this@RecallService)
            while (true) {
                delay(6 * 3600 * 1000L)
                val keepDays = RecallPreferences.keepDaysFlow().first()
                if (keepDays > 0) {
                    val removed = RecallRepository.trim(this@RecallService, keepDays)
                    if (removed > 0) {
                        RecallAuditLog.log(
                            RecallAuditLog.ACTION_TRIM,
                            "过期清理（保留 ${keepDays} 天）：删除 $removed 条"
                        )
                    }
                }
            }
        }
        Timber.i("MBM RecallService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!enabled || blockedByApm) return
        // MBM v1.0.26：统一隐私规则清单（金融应用等在此处即被丢弃）
        if (RecallPolicy.shouldDiscardEvent(e)) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> scheduleCapture(e)
        }
    }

    /**
     * ⚠️ 此回调在【主线程】执行（已踩坑：主线程遍历节点树把输入法进程卡到 ANR，
     * 弹「小企鹅输入法无响应」，见 docs/pitfalls.md #6）。
     *
     * 这里只允许做最廉价的节流判断，随后立即把全部工作
     * （rootInActiveWindow、walk 遍历、入库、OCR）丢进后台协程：
     * 无障碍节点树的每一次 getChild / 属性读取都是跨进程 binder IPC，
     * 大页面动辄上百次调用，绝不能在主线程执行。
     */
    private fun scheduleCapture(event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNodeCaptureAt < NODE_CAPTURE_INTERVAL_MS) return
        lastNodeCaptureAt = now

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // 不采集自己的界面

        scope.launch {
            runCatching { captureNodeTree(pkg) }
                .onFailure { Timber.w(it, "node capture failed") }
        }
    }

    /** 后台协程中执行：遍历节点树 + 入库 + 视情况触发 OCR 兜底 */
    private suspend fun captureNodeTree(pkg: String) {
        // rootInActiveWindow 返回的节点树必须回收，否则 AccessibilityNodeInfo（含 binder 引用）
        // 会堆积导致内存泄漏、进程被系统回收（已踩坑）。用 captureMutex 串行，保证同一时刻
        // 只有一个 root 在内存，且遍历完成立即回收。
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val appLabel = appLabelOf(pkg)
        try {
            // 捕获结果区分两种 null（v0.0.02 修复 OCR 兜底死代码）：
            //  - 隐私屏：text=null 且 privateWindow=true（密码/敏感数据/金融应用）
            //  - 无文字屏：text=null 且 privateWindow=false（Canvas/游戏/图片化内容 → 该走 OCR）
            // 旧版用 `text != null && text.isBlank()` 判 OCR 兜底，而上方的 takeIf{isNotBlank}
            // 早已把空文本变成 null，该条件永假 —— 「启用离线 OCR 兜底」开关从未生效。
            val (text, privateWindow) = captureMutex.withLock {
                val collected = mutableListOf<String>()
                val sawPassword = booleanArrayOf(false)
                val sawDataSensitive = booleanArrayOf(false)
                runCatching { walk(root, collected, sawPassword, sawDataSensitive) }
                    .onFailure { Timber.w(it, "walk node tree failed") }

                // 该界面命中隐私规则（密码框/敏感数据窗口/金融应用）→ 整屏按隐私处理。
                // ⚠️ 隐私时必须让下游拿到的 text 为 null 而不是 ""：
                // OCR 兜底/词组/剪贴板的判据都是 isNullOrBlank，用 "" 会把
                // “隐私丢弃”伪装成“本屏没文字”，导致 OCR 路径绕过整道隐私闸。
                if (RecallPolicy.shouldDiscardWindow(pkg, sawPassword[0], sawDataSensitive[0])) {
                    Timber.i("MBM Recall: discard window of $pkg by privacy policy")
                    null to true
                } else {
                    collected.joinToString("\n").takeIf { it.isNotBlank() } to false
                }
            }
            if (!text.isNullOrBlank()) {
                // MBM v1.0.31：无字数上限（大文本走 blob 外置）
                RecallRepository.record(this@RecallService, pkg, appLabel, text, "NODE")
            }
            // 兜底：非隐私屏且节点树取不到文字时启用 OCR（图片化内容 / 游戏 / Canvas）。
            // OCR 用 takeScreenshot，不依赖 root，故放在 root.recycle() 之前之外均可。
            // ⚠️ 仅 API 34+：3 参 takeScreenshot 在 API 34 才加入（2 参在 34 已删除）。
            if (!privateWindow && text.isNullOrBlank() && ocrEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                captureWithOcr(pkg, appLabel, SystemClock.elapsedRealtime())
            }
            // 捕获后自动写入自定义词组（无延迟：仅入队，真正落盘由 PhraseAutoWriter 去抖合并）
            if (autoAddEnabled) text?.let { PhraseAutoWriter.offer(it) }
            // 捕获后自动写入剪贴板（只写剪贴板库、不写系统剪贴板，避免覆盖用户复制内容造成刷屏）
            if (autoClipboardEnabled) text?.let { RecallClipboardWriter.offer(it) }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * 遍历节点树抽取文字；遇到密码输入框整体标记为隐私。
     * ⚠️ 递归返回后立即 `child.recycle()`，否则节点（含 binder 引用）累积泄漏。
     */
    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        sawPassword: BooleanArray,
        sawDataSensitive: BooleanArray
    ) {
        if (node.isPassword) {
            sawPassword[0] = true
            recycleChildren(node)
            return
        }
        // MBM v1.0.26：系统标记为「敏感数据」的节点 → 整屏按隐私处理
        if (RecallPolicy.isDataSensitive(node)) {
            sawDataSensitive[0] = true
            recycleChildren(node)
            return
        }
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) out += text

        val hint = node.hintText?.toString()
        if (!hint.isNullOrBlank() && hint.contains("密码", true)) {
            sawPassword[0] = true
            recycleChildren(node)
            return
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            walk(child, out, sawPassword, sawDataSensitive)
            // 子树遍历完成，该节点已不再需要，立即回收
            runCatching { child.recycle() }
        }
    }

    /**
     * 回收节点的**直接子节点**（不回收 node 自身——自身由调用方负责）。
     * recycle() 只释放单个节点持有的 binder 引用；早退分支（密码/敏感/提示语命中）
     * 不再遍历子树，若不显式回收，子孙节点会滞留系统
     * AccessibilityNodeInfo 池造成泄漏（v1.0.26 修复）。
     */
    private fun recycleChildren(node: AccessibilityNodeInfo) {
        val count = node.childCount
        for (i in 0 until count) {
            runCatching { node.getChild(i)?.recycle() }
        }
    }

    /**
     * 使用无障碍服务自带的截图能力（无需 MediaProjection），再做本地离线 OCR。
     *
     * ⚠️ API 档位（真 bug 修复）：`takeScreenshot(int displayId, Executor, Callback)`
     * 这个 3 参重载 **API 34（UPSIDE_DOWN_CAKE）才加入**；而 2 参的
     * `takeScreenshot(Executor, Callback)` 在 API 34 已被移除。本工程 compileSdk=37，
     * 无法静态引用已删除的 2 参重载。因此 OCR 兜底只在 **API 34+** 启用
     * （见调用方 guard `>= UPSIDE_DOWN_CAKE`）；Android 11/12/13 上直接走不到这里，
     * 不会出现 NoSuchMethodError。节点树主路径在那些版本仍正常工作。
     */
    private fun captureWithOcr(pkg: String, appLabel: String, now: Long) {
        if (now - lastScreenshotAt < SCREENSHOT_INTERVAL_MS) return
        lastScreenshotAt = now
        if (!ocrInFlight.compareAndSet(false, true)) return

        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                scope.launch {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )
                        if (bitmap == null) {
                            screenshot.hardwareBuffer.close()
                            return@launch
                        }
                        val lines = OcrEngine.recognize(this@RecallService, bitmap, true)
                        bitmap.recycle()
                        screenshot.hardwareBuffer.close()
                        val joined = lines.joinToString("\n") { it.text }
                        if (joined.isNotBlank()) {
                            // MBM v1.0.31：无字数上限（大文本走 blob 外置）
                            RecallRepository.record(
                                this@RecallService, pkg, appLabel, joined, "OCR"
                            )
                            if (autoAddEnabled) PhraseAutoWriter.offer(joined)
                            if (autoClipboardEnabled) RecallClipboardWriter.offer(joined)
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "ocr capture failed")
                    } finally {
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
            // N69：截图入口抛异常（截图不可用/权限异常等）时若不复位 ocrInFlight，
            // 标志将永远为 true，导致此后所有「无文字屏」都不再走 OCR 兜底（需重启服务才恢复）。
            Timber.w(t, "Recall screenshot request failed; OCR fallback will retry on next trigger")
            ocrInFlight.set(false)
        }
    }

    private fun appLabelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onInterrupt() {
        Timber.i("MBM RecallService interrupted")
    }

    override fun onDestroy() {
        running = false
        // 服务销毁前把队列里待写入的词组落盘，避免丢失
        runCatching { PhraseAutoWriter.flushNow() }
        // 修复：原 runBlocking { cancelAndJoin() } 在主线程同步等待协程取消，
        // 若恰逢 trim 协程持有锁会拖住主线程（同为 ANR 隐患）。cancel() 异步取消即可，
        // 协程会在下一个挂起点自然终止，服务销毁不依赖它完成。
        trimJob?.cancel()
        runCatching { OcrEngine.release() }
        runCatching { screenshotExecutor.shutdownNow() }
        scope.cancel()
        super.onDestroy()
    }

    // 便捷入口：供设置界面判断是否已启用无障碍服务
    object State {
        fun isServiceRunning(): Boolean = running
    }

    @Suppress("unused")
    private fun unusedContext(): Context = this
}
