/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.password

/**
 * 密码填写桥：把生成器产出的密码直接交给当前输入框。
 *
 * 密码只在进程内传递，不写剪贴板、不落学习记录；
 * 未获取到服务实例（键盘未启用）时返回 false，由调用方提示用户。
 */
object PasswordFillBridge {

    private const val TAG = "PasswordFillBridge"

    /** 由 [org.fcitx.fcitx5.android.input.FcitxInputMethodService] 注册 */
    @Volatile
    var commitConsumer: ((String) -> Boolean)? = null

    fun register(consumer: (String) -> Boolean) {
        commitConsumer = consumer
    }

    fun unregister() {
        commitConsumer = null
    }

    fun commit(value: String): Boolean {
        val consumer = commitConsumer ?: return false
        return runCatching { consumer.invoke(value) }.getOrDefault(false)
    }
}
