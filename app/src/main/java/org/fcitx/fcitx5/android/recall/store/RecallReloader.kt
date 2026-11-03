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
import org.fcitx.fcitx5.android.recall.data.RecallAuditLog
import kotlinx.coroutines.launch
import timber.log.Timber

object RecallReloader {

    private val handler = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { doReload() }

    @Volatile
    private var lastReloadAt = 0L

    /** v1.0.62：上次 reload 是否失败（用于审计日志只在状态翻转时记一条）。 */
    @Volatile
    private var lastFailed = false

    /** ack 回调：reload 已排期执行时通知上层（用于验收「<2s 候选可用」）。 */
    @Volatile
    var onAck: ((success: Boolean) -> Unit)? = null

    /** fcitx 未就绪时的最大重试次数：超过即放弃本次排期，避免永久空转轮询耗电。 */
    private const val MAX_RETRY = 240

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
            // v1.0.62：10 次 × 250ms = 2.5s 就放弃是「捕获了却打不出」的直接原因之一 ——
            // 捕获常发生在键盘未弹起时（刚切到别的应用），2.5s 内引擎往往还没就绪，
            // 放弃后引擎永远不会 reload。放宽到 240 次（60s）：postDelayed 空转开销可忽略；
            // 用户开键盘后引擎就绪，随即补上 reload，词条必然生效。
            if (++retryCount > MAX_RETRY) {
                Timber.w("MBM Recall: reload abandoned, fcitx unavailable after $MAX_RETRY retries")
                retryCount = 0
                markFailed("引擎 60s 内未就绪，本次 reload 放弃（词条已落盘，下次捕获/保存会补生效）")
                onAck?.invoke(false)
                return
            }
            handler.postDelayed(reloadTask, RecallConfig.RELOAD_MIN_INTERVAL_MS)
            return
        }
        retryCount = 0
        runCatching {
            // v1.0.41：runIfReady 在 fcitx 尚未就绪时会**静默丢弃**本次 reload
            // （内部只判 isReady，既不排队也不重试），而这里仍走 onSuccess 分支，
            // 于是「词组已落盘、引擎却没重新加载」被误判成已生效 —— 表现就是
            // 新词要等下一次事件（重启输入法 / 下次捕获）才偶然生效。
            // 改用 runOnReady：未就绪时挂起等待，就绪后立即执行，reload 不会被丢。
            // 作用域取连接自身的 lifecycleScope，fcitx 停止时协程自动取消，不会泄漏。
            conn.lifecycleScope.launch {
                // 失败在协程内捕获（runOnReady 若在连接断开后执行会抛 IllegalStateException）
                runCatching { conn.runOnReady { reloadPinyinCustomPhrase() } }
                    .onSuccess {
                        Timber.d("MBM Recall: reloadPinyinCustomPhrase done")
                        // v1.0.62 可观测：失败恢复后补一条成功记录（审计面板可见链路闭合）
                        if (lastFailed) {
                            lastFailed = false
                            RecallAuditLog.log(
                                RecallAuditLog.ACTION_AUTO_PHRASE, "引擎 reload 恢复成功，词组候选已生效"
                            )
                        }
                    }
                    .onFailure {
                        Timber.w(it, "MBM Recall: reload failed")
                        markFailed("引擎 reload 失败：${it.message ?: it.javaClass.simpleName}")
                    }
            }
        }.onSuccess {
            Timber.d("MBM Recall: reloadPinyinCustomPhrase scheduled")
            onAck?.invoke(true)
        }.onFailure {
            Timber.w(it, "MBM Recall: reload failed")
            markFailed("引擎 reload 排期失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** v1.0.62：reload 失败落审计日志（仅状态翻转时记一条，避免刷屏）。 */
    private fun markFailed(reason: String) {
        if (lastFailed) return
        lastFailed = true
        RecallAuditLog.log(RecallAuditLog.ACTION_AUTO_PHRASE, reason)
    }

    /** 立即触发一次 reload（服务销毁前确保最新词条生效）。 */
    fun flushNow() {
        handler.removeCallbacks(reloadTask)
        doReload()
    }
}
