/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 拾取层调度器（冻结方案 D1/D2/D3：10ms 定频拾取）。
 *
 * 同进程隔离前提下的线程模型：
 *  - 独立的 HandlerThread（THREAD_PRIORITY_BACKGROUND），不占用 IME 主线程；
 *  - 名义 10ms 定频轮询，但受 binder 排队影响实际为 20~50ms，**不承诺严格 100Hz**；
 *  - 事件驱动：无障碍事件到达即唤醒贴合节奏；
 *  - 静止退避：窗口静止超过 PICKER_IDLE_BACKOFF_MS 后降为每秒轻探（仍满足「静止 ≤2s 必有采样」）；
 *  - 打字同步：IME 打字时刻立即采样一次，确保刚打的文字即时入词库；
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

    fun start() {
        if (!active.compareAndSet(false, true)) return
        lastEventAt = SystemClock.elapsedRealtime()
        handler.post(tick)
        Timber.d("MBM RecallPicker started")
    }

    fun stop() {
        active.set(false)
        handler.removeCallbacks(tick)
        thread.quitSafely()
    }

    /** 无障碍事件到达：刷新活跃时间并贴合节奏（必要时唤醒轮询）。 */
    fun onAccessibilityEvent() {
        lastEventAt = SystemClock.elapsedRealtime()
        if (active.get()) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        } else {
            start()
        }
    }

    /** IME 打字时刻同步抓取：立即采样一次，最大化「看到词 → 候选可用」的即时性。 */
    fun notifyTyping() {
        lastTypingAt = SystemClock.elapsedRealtime()
        if (active.get()) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun tick() {
        if (!active.get()) return
        val now = SystemClock.elapsedRealtime()
        val idle = now - lastEventAt
        val sinceTyping = now - lastTypingAt

        // 崩溃隔离：采样（含 binder 节点树遍历 / 分词 / 写库）任何异常都不外泄
        runCatching { sampler() }.onFailure { Timber.w(it, "RecallPicker sample failed") }

        // 间隔决策：打字 / 活跃窗口 → 名义 10ms；静止 → 退避为每秒轻探（≤2s 必有采样）
        val interval = if (sinceTyping < 1000L || idle < RecallConfig.PICKER_IDLE_BACKOFF_MS) {
            RecallConfig.PICKER_INTERVAL_MS
        } else {
            1000L
        }
        handler.postDelayed(tick, interval)
    }
}
