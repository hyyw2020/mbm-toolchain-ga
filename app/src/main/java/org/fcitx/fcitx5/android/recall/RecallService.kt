/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 屏幕记忆 —— 同进程无障碍服务（冻结方案：内嵌输入法 APK，IME 同进程）。
 *
 * 数据流（全在同进程内完成，消灭 IPC）：
 *   拾取(RecallPicker 10ms 定频 + 事件驱动 + 静止退避 + 打字同步)
 *     → 节点树遍历 + 隐私判定(RecallPolicy)
 *     → 分词双通道(词典/新词发现 + NER) + 三层过滤(RecallSegmenter)
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.fcitx.fcitx5.android.recall.data.RecallAuditLog
import org.fcitx.fcitx5.android.recall.data.RecallPreferences
import org.fcitx.fcitx5.android.recall.data.RecallRepository
import org.fcitx.fcitx5.android.recall.ocr.RecallOcrController
import org.fcitx.fcitx5.android.recall.picker.RecallFrameCache
import org.fcitx.fcitx5.android.recall.picker.RecallPicker
import org.fcitx.fcitx5.android.recall.segment.RecallCandidate
import org.fcitx.fcitx5.android.recall.segment.RecallSegmenter
import org.fcitx.fcitx5.android.recall.store.RecallReloader
import org.fcitx.fcitx5.android.recall.store.RecallWordStore
import org.fcitx.fcitx5.android.R
import timber.log.Timber
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.atomic.AtomicBoolean

class RecallService : AccessibilityService() {

    /** 系统高级保护模式命中时置位：服务整体停用并常驻提示，不静默降级。 */
    @Volatile
    private var blockedByApm = false

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenshotExecutor = newSingleThreadExecutor()

    private lateinit var picker: RecallPicker
    private val frameCache = RecallFrameCache()
    private val segmenter = RecallSegmenter()
    private val ocrController = RecallOcrController { text -> ingest(text, "OCR") }
    private val captureMutex = ReentrantLock()
    private val ocrInFlight = AtomicBoolean(false)

    companion object {
        @Volatile
        var instance: RecallService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        /** IME 打字时刻同步抓取：由 FcitxInputMethodService 在 commitText / 按键时调用。 */
        fun notifyTyping() = instance?.picker?.notifyTyping()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
        // 偏好开关（总 / OCR / 拾词 / 剪贴板）实时同步到内存字段，并立即读一次初值
        scope.launch { RecallPreferences.enabledFlow().collect { enabled = it } }
        scope.launch { RecallPreferences.ocrEnabledFlow().collect { ocrEnabled = it } }
        scope.launch { RecallPreferences.autoAddPhraseFlow().collect { autoAddEnabled = it } }
        scope.launch { RecallPreferences.autoClipboardFlow().collect { autoClipboardEnabled = it } }
        scope.launch {
            enabled = RecallPreferences.isEnabled()
            ocrEnabled = RecallPreferences.isOcrEnabled()
            autoAddEnabled = RecallPreferences.isAutoAddPhrase()
            autoClipboardEnabled = RecallPreferences.isAutoClipboard()
        }

        picker = RecallPicker { captureTick() }
        picker.start()
        Timber.i("MBM RecallService connected (in-process)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!enabled || blockedByApm) return
        // E2：事件级无包名豁免，整窗丢弃仅在 window 级裁决
        if (RecallPolicy.shouldDiscardEvent(e)) return
        val pkg = e.packageName?.toString()
        if (!pkg.isNullOrEmpty()) lastPkg = pkg
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
        val pkg = lastPkg ?: return
        if (pkg == packageName) return // 不采集自己的界面
        runCatching { captureNodeTree(pkg) }
            .onFailure { Timber.w(it, "node capture failed") }
    }

    /** 节点树遍历 + 隐私判定 + 分词 + 写库 + OCR 兜底（全部后台线程）。 */
    private fun captureNodeTree(pkg: String) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val appLabel = appLabelOf(pkg)
        try {
            val (text, privateWindow) = captureMutex.withLock {
                val collected = mutableListOf<String>()
                val sawPassword = booleanArrayOf(false)
                val sawDataSensitive = booleanArrayOf(false)
                runCatching { walk(root, collected, sawPassword, sawDataSensitive) }
                    .onFailure { Timber.w(it, "walk node tree failed") }
                if (RecallPolicy.shouldDiscardWindow(pkg, sawPassword[0], sawDataSensitive[0])) {
                    null to true
                } else {
                    collected.joinToString("\n").takeIf { it.isNotBlank() } to false
                }
            }
            if (!text.isNullOrBlank()) {
                ingest(text, "NODE")
            }
            // OCR 兜底：非隐私屏且节点树取不到文字（Canvas / 游戏 / 图片）
            if (!privateWindow && text.isNullOrBlank() && ocrEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                captureWithOcr()
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * 统一入口：一段可见文本 → 记录时间线 →（拾词开）分词写库 →（剪贴板开）写剪贴板库。
     * 帧缓存去重在此之上，避免静止屏对同帧重复 ingest。
     */
    private fun ingest(text: String, source: String) {
        val pkg = lastPkg ?: return
        if (!frameCache.shouldCapture(pkg, text, SystemClock.elapsedRealtime())) return
        // record 是 suspend（含 Room 写盘），在后台 IO 协程里异步落库，不让拾取线程阻塞；
        // 帧级去重已前置，DB 内去重仅作最终兜底，异步不影响正确性。
        scope.launch(Dispatchers.IO) {
            RecallRepository.record(this@RecallService, pkg, appLabelOf(pkg), text, source)
        }
        if (autoAddEnabled) {
            val candidates: List<RecallCandidate> = segmenter.segment(text.lineSequence())
            if (candidates.isNotEmpty()) RecallWordStore.commit(candidates)
        }
        if (autoClipboardEnabled) RecallClipboardWriter.offer(text)
    }

    /** 遍历节点树抽取文字；命中密码 / 系统敏感标记则整棵子树标记隐私。 */
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
            runCatching { child.recycle() }
        }
    }

    private fun recycleChildren(node: AccessibilityNodeInfo) {
        val count = node.childCount
        for (i in 0 until count) {
            runCatching { node.getChild(i)?.recycle() }
        }
    }

    /** 无障碍自有截图 + 离线 OCR（区域 diff / 打字区裁剪在控制器内）。 */
    private fun captureWithOcr() {
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
                        ocrController.processBitmap(this@RecallService, bitmap, false, ocrEnabled)
                        bitmap.recycle()
                        screenshot.hardwareBuffer.close()
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
            Timber.w(t, "Recall screenshot request failed")
            ocrInFlight.set(false)
        }
    }

    private fun appLabelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        runCatching { RecallReloader.flushNow() } // 销毁前确保最新词条 reload 生效
        runCatching { picker.stop() }
        runCatching { screenshotExecutor.shutdownNow() }
        scope.cancel()
        super.onDestroy()
    }
}
