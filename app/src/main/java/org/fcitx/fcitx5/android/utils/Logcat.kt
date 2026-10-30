/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R

class Logcat(var pid: Int? = Process.myPid()) : CoroutineScope by CoroutineScope(Dispatchers.IO) {

    private var process: java.lang.Process? = null
    private var emittingJob: Job? = null

    private val flow: MutableSharedFlow<String> = MutableSharedFlow()

    /**
     * Subscribe to this flow to receive log in app
     * Nothing would be emitted until [initLogFlow] was called
     */
    val logFlow: SharedFlow<String> by lazy { flow.asSharedFlow() }

    /**
     * 拼装 logcat 参数。
     * 修复（已踩坑，见 docs/pitfalls.md #7）：旧实现
     * `arrayOf("logcat", pid?.let { "--pid=$it" } ?: "", ...)` 会在 pid 为空时
     * 把一个【空字符串】塞进参数数组，logcat 收到非法空参数会直接报错退出，
     * 导致读到的日志为空。改为仅在实际存在 pid 时才追加 --pid 参数。
     */
    private fun buildArgs(vararg extra: String): Array<String> {
        val list = mutableListOf("logcat")
        pid?.let { list.add("--pid=$it") }
        list.addAll(extra)
        return list.toTypedArray()
    }

    private fun readLines(cmd: Array<String>): List<String> =
        Runtime.getRuntime().exec(cmd).inputStream.bufferedReader().readLines()

    /**
     * Get a snapshot of logcat（main + system + crash 三个缓冲区）。
     * 若带 --pid 过滤读到空输出（部分 ROM 对 --pid 的兼容问题），降级为
     * 不带 --pid 再读一次——非特权应用本就只能读到本 UID 的日志，安全性不变。
     */
    suspend fun snapshot(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            // 不修改共享 pid（避免干扰同时运行的实时日志流），仅用局部快照做降级
            val pidArg = pid
            val withPid = readLines(
                buildArgsPid(pidArg, "-b", "main", "-b", "system", "-b", "crash", "-d", "-v", "brief")
            )
            if (withPid.isNotEmpty()) withPid
            else readLines(
                buildArgsPid(null, "-b", "main", "-b", "system", "-b", "crash", "-d", "-v", "brief")
            )
        }
    }

    /** 与 [buildArgs] 一致，但 pid 由参数决定（用于 snapshot 降级，不动共享状态） */
    private fun buildArgsPid(pidArg: Int?, vararg extra: String): Array<String> {
        val list = mutableListOf("logcat")
        pidArg?.let { list.add("--pid=$it") }
        list.addAll(extra)
        return list.toTypedArray()
    }

    /**
     * Clear logcat
     */
    fun clearLog(): Job =
        launch {
            runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) }
        }

    /**
     * Create a process reading logcat, sending lines to [logFlow]
     */
    fun initLogFlow() =
        if (process != null)
            errorState(R.string.exception_logcat_created)
        else launch {
            runCatching {
                Runtime
                    .getRuntime()
                    .exec(buildArgs("-v", "brief"))
                    .also { process = it }
                    .inputStream
                    .bufferedReader()
                    .lineSequence()
                    .asFlow()
                    .flowOn(Dispatchers.IO)
                    .cancellable()
                    .collect { flow.emit(it) }
            }
        }.also { emittingJob = it }

    /**
     * Destroy the reading process
     */
    fun shutdownLogFlow() {
        process?.destroy()
        emittingJob?.cancel()
    }

    companion object {
        val default by lazy { Logcat() }
    }
}
