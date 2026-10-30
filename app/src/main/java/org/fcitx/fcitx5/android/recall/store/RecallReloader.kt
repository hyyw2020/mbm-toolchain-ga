/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 生效层（冻结方案 C1/C2/C3）：逐词写入后的「批量 reload + ack 闭环」。
 *
 *  - 写入路径（[RecallWordStore]）只负责改内存词组数组并 saveCached；
 *  - 真正通知 fcitx 重新加载 customphrase 子配置在这里**去抖批量**完成，
 *    避免每写一个词就 reload 一次（reload 是进程内全量 re-parse，高频会卡顿）。
 *  - reload 通过 FcitxDaemon 同进程连接触发（与 IME 同一进程，零 IPC），
 *    实现即 `runIfReady { reloadPinyinCustomPhrase() }`。
 *  - ack：每次成功排期的 reload 后回调，供 UI / 日志确认「看到词 → 候选可用」链路闭合。
 */
package org.fcitx.fcitx5.android.recall.store

import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.core.reloadPinyinCustomPhrase
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.recall.RecallConfig
import timber.log.Timber

object RecallReloader {

    private val handler = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { doReload() }

    @Volatile
    private var lastReloadAt = 0L

    /** ack 回调：reload 已排期执行时通知上层（用于验收「<2s 候选可用」）。 */
    @Volatile
    var onAck: ((success: Boolean) -> Unit)? = null

    /** fcitx 未就绪时的最大重试次数：超过即放弃本次排期，避免永久空转轮询耗电。 */
    private const val MAX_RETRY = 10

    @Volatile
    private var retryCount = 0

    /** 去抖排期：多次写入合并为一次 reload。 */
    fun scheduleReload() {
        // 有新的写入 ⇒ 值得重新尝试，重置重试计数
        retryCount = 0
        handler.removeCallbacks(reloadTask)
        handler.postDelayed(reloadTask, RecallConfig.RELOAD_DEBOUNCE_MS)
    }

    private fun doReload() {
        val now = System.currentTimeMillis()
        val since = now - lastReloadAt
        if (since < RecallConfig.RELOAD_MIN_INTERVAL_MS) {
            // 距上次 reload 过近，补一个最小间隔后重试（不丢请求）
            handler.postDelayed(reloadTask, RecallConfig.RELOAD_MIN_INTERVAL_MS - since)
            return
        }
        lastReloadAt = now
        val conn = FcitxDaemon.getFirstConnectionOrNull()
        if (conn == null) {
            // fcitx 尚未就绪（输入法进程刚起 / 未连）：稍后重试，不丢词条。
            // 但有上限——旧实现无上限重试，输入法未启用时会永久每 300ms 空转一次。
            if (++retryCount > MAX_RETRY) {
                Timber.w("MBM Recall: reload abandoned, fcitx unavailable after $MAX_RETRY retries")
                retryCount = 0
                onAck?.invoke(false)
                return
            }
            handler.postDelayed(reloadTask, RecallConfig.RELOAD_MIN_INTERVAL_MS)
            return
        }
        retryCount = 0
        runCatching {
            conn.runIfReady { reloadPinyinCustomPhrase() }
        }.onSuccess {
            Timber.d("MBM Recall: reloadPinyinCustomPhrase scheduled")
            onAck?.invoke(true)
        }.onFailure {
            Timber.w(it, "MBM Recall: reload failed")
            onAck?.invoke(false)
        }
    }

    /** 立即触发一次 reload（服务销毁前确保最新词条生效）。 */
    fun flushNow() {
        handler.removeCallbacks(reloadTask)
        doReload()
    }
}
