/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 拾取层调度器（冻结方案 D1/D2/D3）。
 *
 * 线程模型（同进程隔离前提）：
 *  - 独立的 HandlerThread（THREAD_PRIORITY_BACKGROUND），不占用 IME 主线程；
 *  - 唯一的定频 tick 消费采样：事件 / 打字只置 dirty 标记，**绝不重排 tick**（P1-1 修复：
 *    旧实现每个 a11y 事件 removeCallbacks+post，视频 / 动画场景下事件频率击穿退避，
 *    回调持续重排导致采样堆积；现在任意事件风暴最多提前下一次 tick，一 tick 一采样）；
 *  - 活跃期按 PICKER_INTERVAL_MS 定频采样（名义 50ms，实际受 binder 排队为 20~50ms+，
 *    不承诺严格高频）；静止超过 PICKER_IDLE_BACKOFF_MS 退避为每秒轻探；
 *  - 打字同步：notifyTyping 置 dirty，下一 tick 立即采样，保证「打字即更新词库」；
 *  - 崩溃隔离：sampler（含 binder 节点遍历）任何异常都被捕获，不波及主流程 / 不引发 ANR。
 */
package org.fcitx.fcitx5.android.recall.picker

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import org.fcitx.fcitx5.android.recall.RecallConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class RecallPicker(private val sampler: () -> Unit) {

    private val thread = HandlerThread("RecallPicker", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val tick = Runnable { tick() }

    private val active = AtomicBoolean(false)
    @Volatile
    private var lastEventAt = 0L
    @Volatile
    private var lastTypingAt = -10_000L

    /** 事件 / 打字到达标记：只置位不重排 tick，由唯一的定频 tick 消费（P1-1 修复核心）。 */
    @Volatile
    private var dirty = false

    fun start() {
        if (!active.compareAndSet(false, true)) return
        lastEventAt = SystemClock.elapsedRealtime()
        handler.post(tick)
        Timber.d("MBM RecallPicker started")
    }

    /**
     * 暂停采样（**保留线程**，可再次 [start]）。
     * 修复：旧实现在此直接 quitSafely 终止 HandlerThread，导致「总开关关闭后再开启」
     * 时 start() 无效（线程已死，post 不执行），拾取永久失效。现在暂停只停回调，
     * 线程寿命与 [RecallService] 一致，由 [dispose] 在服务销毁时释放。
     */
    fun stop() {
        if (!active.compareAndSet(true, false)) return
        handler.removeCallbacks(tick)
        Timber.d("MBM RecallPicker paused (thread kept)")
    }

    /** 彻底释放线程（仅服务销毁时调用一次，之后不可再 start）。 */
    fun dispose() {
        stop()
        thread.quitSafely()
        Timber.d("MBM RecallPicker disposed")
    }

    /** 无障碍事件到达：刷新活跃时间并标记 dirty（不重排 tick，杜绝回调堆积）。 */
    fun onAccessibilityEvent() {
        lastEventAt = SystemClock.elapsedRealtime()
        dirty = true
    }

    /** IME 打字时刻同步抓取：标记 dirty，下一 tick 立即采样（tick 最长等待 PICKER_INTERVAL_MS）。 */
    fun notifyTyping() {
        lastTypingAt = SystemClock.elapsedRealtime()
        dirty = true
    }

    /** 是否处于活跃期（最近有事件 / 打字）：OCR 兜底等重操作以此为门控（P2-3）。 */
    fun hasRecentActivity(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now - lastTypingAt < 1000L || now - lastEventAt < RecallConfig.PICKER_IDLE_BACKOFF_MS
    }

    private fun tick() {
        if (!active.get()) return
        val now = SystemClock.elapsedRealtime()
        val activeWindow = hasRecentActivity()

        // 崩溃隔离：采样（含 binder 节点树遍历 / 分词 / 写库）任何异常都不外泄。
        // 有 dirty 标记或仍在活跃窗口内才采样：静止期纯轮询不做任何节点树遍历。
        if (dirty || activeWindow) {
            dirty = false
            runCatching { sampler() }.onFailure { Timber.w(it, "RecallPicker sample failed") }
        }

        // 间隔决策：活跃 → 名义定频；静止 → 退避为每秒轻探（≤2s 必有采样）
        val interval = if (activeWindow) RecallConfig.PICKER_INTERVAL_MS else 1000L
        handler.postDelayed(tick, interval)
    }
}
