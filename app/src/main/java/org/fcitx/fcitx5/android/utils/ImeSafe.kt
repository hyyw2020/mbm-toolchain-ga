/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.view.WindowManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * MBM 输入法进程稳定性基建（v1.0.19）。
 *
 * 根因：输入法(IME)进程内任何未捕获的异常都会令整个进程崩溃、键盘瞬间消失，
 * 用户看到的就是「点一下/长摁一下输入法里的某个按钮，整个输入法没了」。
 * 这类问题集中在两类入口：
 *  1) 协程顶层未捕获异常：从 IME 窗口发起的 `lifecycleScope.launch { ... }` /
 *     `ClipboardManager` 等无异常处理器的作用域，一旦块内抛异常会沿默认处理器上抛，
 *     直接令进程崩溃（同源 Bug：剪贴板删除按钮、长摁中英切换等）。
 *  2) IME 窗口内弹窗/对话框令牌非法：在部分 OEM（EMUI / Android 12+ / MagicOS）上，
 *     `PopupMenu.show()` / `service.showDialog()` 会抛 `WindowManager.BadTokenException`，
 *     若未捕获同样令进程崩溃。
 *
 * 这里集中提供防护原件，供各调用点复用，避免散落 try/catch 仍有遗漏。
 */

/**
 * 顶层协程异常处理器：吞掉并记录异常，阻止未捕获异常沿默认处理器上抛导致
 * 整个输入法进程崩溃。把它作为 `launch(imeExceptionHandler) { ... }` 的上下文传入。
 */
val imeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Timber.e(throwable, "MBM: 已抑制未捕获的协程异常，避免输入法进程崩溃")
}

/**
 * 主线程兜底协程作用域：当某个视图尚未挂到带 LifecycleOwner 的视图树
 * （理论上在 IME 主键盘内不会发生），用此作用域替代 `lifecycleScope` 拿到非空的
 * CoroutineScope，避免 `findViewTreeLifecycleOwner()?.lifecycleScope!!` 这种 `!!`
 * 空断言抛 NPE 令进程崩溃。使用 Main.immediate 以保持与原 lifecycleScope 一致的线程模型。
 */
val imeFallbackScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * 判定是否为 IME 窗口内弹窗/对话框常见的「令牌非法」类异常，供各调用点的
 * catch 块做安全降级（记录日志而非崩溃）。
 */
fun isBadToken(t: Throwable?): Boolean {
    var e = t
    var depth = 0
    while (e != null && depth < 6) {
        if (e is WindowManager.BadTokenException) return true
        val msg = e.message.orEmpty()
        if (e is IllegalArgumentException &&
            (msg.contains("WindowManager", ignoreCase = true) ||
                msg.contains("token", ignoreCase = true) ||
                msg.contains("not valid", ignoreCase = true))
        ) {
            return true
        }
        e = e.cause
        depth++
    }
    return false
}
