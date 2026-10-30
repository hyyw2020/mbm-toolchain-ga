/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.password

import android.view.inputmethod.EditorInfo

/**
 * 隐私模式全局状态。
 *
 * 当 [PasswordFieldDetector] 判定当前输入框为密码类时进入隐私模式：
 *  - 输入法不学习、不记忆
 *  - 智能词库不统计
 *  - Recall 不捕获、不索引
 *  - 剪贴板不记录历史
 *  - 键盘顶部显示隐私状态提示
 */
object PrivacyController {

    @Volatile
    var inPrivacyMode: Boolean = false
        private set

    @Volatile
    var lastReason: String = "none"
        private set

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun addListener(l: (Boolean) -> Unit) {
        synchronized(listeners) { listeners += l }
    }

    fun removeListener(l: (Boolean) -> Unit) {
        synchronized(listeners) { listeners -= l }
    }

    fun update(info: EditorInfo, packageName: String?) {
        val result = PasswordFieldDetector.detect(info, packageName)
        val changed = result.isPassword != inPrivacyMode
        inPrivacyMode = result.isPassword
        lastReason = result.reason
        if (changed) {
            synchronized(listeners) { listeners.forEach { it.safeInvoke(inPrivacyMode) } }
        }
    }

    fun reset() {
        val changed = inPrivacyMode
        inPrivacyMode = false
        lastReason = "none"
        if (changed) {
            synchronized(listeners) { listeners.forEach { it.safeInvoke(false) } }
        }
    }

    private fun ((Boolean) -> Unit).safeInvoke(v: Boolean) = runCatching { invoke(v) }
}
