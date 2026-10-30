/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.password

import android.os.Build
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * 密码字段自动识别 —— 完全在本地完成，不联网、不上传。
 *
 * 做法参考 HeliBoard / AOSP Keyboard / 问墨(Wenmo) / FlorisBoard：
 * 一旦判定当前输入框为密码类，立即进入隐私模式 —— 不学习、不记忆、
 * 不进入智能词库、不被 Recall 索引、不写入剪贴板历史。
 */
object PasswordFieldDetector {

    data class Result(val isPassword: Boolean, val reason: String)

    private val NONE = Result(false, "none")

    /** 已知密码管理类应用包名关键字 */
    private val passwordPackageHints = setOf(
        "keepass", "bitwarden", "1password", "lastpass", "kdbx", "authy", "dashlane"
    )

    fun detect(info: EditorInfo, packageName: String?): Result {
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val flags = type and InputType.TYPE_MASK_FLAGS

        // 1) 明确声明的密码变体（含「可见密码」）
        if (cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )
        ) {
            return Result(true, "inputType.passwordVariation")
        }

        // 2) 数字密码（如 PIN 锁屏、支付密码）
        if (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return Result(true, "inputType.numberPassword")
        }

        // 3) 应用显式声明「不要个性化学习」
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
            return Result(true, "imeOptions.noPersonalizedLearning")
        }

        // 4) Autofill 提示（浏览器 / WebView 常见）
        if (hasPasswordHint(info)) {
            return Result(true, "autofill.passwordHint")
        }

        // 5) 本地启发式：文本类型 + 无建议 + 无自动更正 + 单行 —— 典型的一次性验证码框
        if (cls == InputType.TYPE_CLASS_TEXT &&
            flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 &&
            flags and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0 &&
            flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
        ) {
            return Result(true, "heuristic.singleLineNoSuggestions")
        }

        // 6) 包名启发式
        val pkg = packageName?.lowercase()
        if (pkg != null && passwordPackageHints.any { pkg.contains(it) }) {
            return Result(true, "heuristic.passwordManagerPackage")
        }

        return NONE
    }

    /**
     * WebView / 浏览器 / Chromium 自定义键盘会把密码提示塞进 extras
     */
    private fun hasPasswordHint(info: EditorInfo): Boolean {
        val extras = info.extras ?: return false
        try {
            for (key in extras.keySet()) {
                val lower = key.lowercase()
                if (!lower.contains("hint") && !lower.contains("autofill")) continue
                val value = extras.get(key)
                if (value is CharSequence) {
                    val text = value.toString().lowercase()
                    if (text.contains("password") || text.contains("passwd") || text.contains("passcode")) {
                        return true
                    }
                } else if (value is Array<*> && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    for (item in value) {
                        val text = item?.toString()?.lowercase() ?: continue
                        if (text.contains(View.AUTOFILL_HINT_PASSWORD) ||
                            text.contains("newPassword".lowercase())
                        ) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }
}
