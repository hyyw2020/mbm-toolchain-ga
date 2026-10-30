/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 崩溃日志脱敏（MBM v1.0.26 / P2-7）。
 *
 * 背景：v1.0.25 的崩溃修复把整段 stackTrace 通过 Intent extra 丢给 LogActivity 展示，
 * 再把 `exitProcess` 前的内容写进日志。问题在于输入法崩溃栈里**必然夹带用户输入内容**：
 *
 *   at ...FcitxInputMethodService.commitText("用户刚打的半句话")
 *   at ...updateComposingText(FormattedText(strings=[nihao, 你好]))
 *
 * 日志一旦被用户导出分享（MBM 的 LogActivity 就带「导出」按钮），
 * 等于把输入内容明文外泄。输入法是特权组件，泄露面比普通应用更大。
 *
 * 规则（可审计、可测）：
 *  1. 命中「输入路径」方法名的栈帧，其中的字符串字面量一律掩码；
 *  2. 其余栈帧里长度 > 32 的字符串字面量也掩码（长字面量几乎不可能是诊断用的
 *     类名/标志位，更可能是用户文本、词库内容、URL/令牌）；
 *  3. 掩码保留长度信息（"<masked:7>"），不影响定位是哪个调用点；
 *  4. 只动字符串字面量与 FormattedText/strings=[...] 形状的参数，类名、方法名、
 *     文件名、行号全部原样保留 —— 脱敏不等于把日志变成废纸。
 */
package org.fcitx.fcitx5.android.utils

import java.util.regex.Pattern

object CrashLogSanitizer {

    /**
     * 输入路径上会携带用户内容的方法名（栈帧 `at pkg.Class.method(args)` 命中即整帧脱敏）。
     * 覆盖提交/预编辑/候选/词库/剪贴板/密码桥等所有会把用户文本带进参数的路径。
     */
    private val SENSITIVE_METHODS = setOf(
        "commitText",
        "commitContent",
        "setComposingText",
        "setComposingRegion",
        "updateComposingText",
        "updateSelection",
        "sendKey",
        "forwardKeyEvent",
        "onUpdateSelection",
        "recordUsage",
        "extractPhrases",
        "importAsPhrases",
        "importSelected",
        "transform",
        "transformClipboard",
        "fillText",
        "fill",
        "register",
        "loadOrCreate",
        "rawKey",
        "search",
        "byPackage"
    )

    /** 双引号字符串字面量（含内部转义），栈帧里参数形式 `("...")` 或 `("...")` 均可。 */
    private val STRING_LITERAL: Pattern =
        Pattern.compile("\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"")

    /** FormattedText 的 toString 形状：strings=[a, b, c] */
    private val FORMATTED_TEXT: Pattern =
        Pattern.compile("strings=\\[[^\\]]*\\]")

    /** 行首的栈帧行（含 at 前缀），便于按帧判断是否需要脱敏。 */
    private val FRAME_LINE: Pattern =
        Pattern.compile("^\\s*at\\s+[\\w.$]+\\.([\\w$]+)\\(.*$", Pattern.MULTILINE)

    private const val LONG_LITERAL_THRESHOLD = 32

    /**
     * @param stackTrace 原始异常栈文本
     * @return 脱敏后的文本；输入为空则原样返回
     */
    fun sanitize(stackTrace: String): String {
        if (stackTrace.isEmpty()) return stackTrace
        // 先按行找出「命中敏感方法」的行号集合，再逐行处理，避免跨行误伤
        val lines = stackTrace.split("\n")
        // Frame 行可能跨行（参数换行），这里用「最近的 at 行」向后传播到下一个 at 行为止
        val sensitiveUntil = IntArray(lines.size) { -1 }
        var current = -1
        lines.forEachIndexed { i, line ->
            val m = FRAME_LINE.matcher(line)
            if (m.find()) {
                val method = m.group(1) ?: ""
                current = if (method in SENSITIVE_METHODS) i else -1
            }
            sensitiveUntil[i] = current
        }
        return lines.mapIndexed { i, line ->
            val frameSensitive = sensitiveUntil[i] in 0..i
            sanitizeLine(line, frameSensitive)
        }.joinToString("\n")
    }

    private fun sanitizeLine(line: String, frameSensitive: Boolean): String {
        var out = FORMATTED_TEXT.matcher(line).replaceAll("strings=[<masked>]")
        out = STRING_LITERAL.matcher(out).replaceAll { match ->
            val literal = match.group()
            val content = literal.substring(1, literal.length - 1)
            val shouldMask = frameSensitive || content.length > LONG_LITERAL_THRESHOLD
            if (shouldMask) "\"<masked:${content.length}>\"" else literal
        }
        return out
    }
}
