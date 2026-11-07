/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 *
 * 富媒体（图像表情）剪贴板条目策略 —— 纯数据层，零 Android 依赖，可在 JVM 单测中验证。
 *
 * v1.0.79 真 bug 背景：
 * 宿主应用复制自定义表情时，ClipData.Item 常同时给出 uri（真实位图）与 text
 * （应用私有的表情编码串）。旧实现 ClipboardEntry.fromClipData 无条件把 text
 * 当作可粘贴正文写入剪贴板历史，于是输入法底部剪贴板建议条（text.take(42)）
 * 显示的就是私有编码被 UTF-8 解码后的乱码，点击又是 commitText(text)，
 * 输出同样一串乱码 / 错误表情。v1.0.75~78 多轮修复都只在「把图片导入表情
 * 面板」这一侧，从未改变这条文本链路，因此用户侧现象始终是「未修复」。
 *
 * 本策略把两件事收敛为可回归的纯函数：
 *  1. 识别宿主私有编码文本（控制字符 / 私用区 / 对象替换符 / 未配对代理 /
 *     替换符 / emoji 与 Latin Extended-A 混排）与 URI 派生文本；
 *  2. 把一次复制导入的若干图像编码成一条可读、可上屏的历史条目文本
 *     （多行 sticker token），并能在粘贴侧无损解析回来。
 *
 * 重要边界：是否替换由调用方在「本次确实捕获到图像 URI」时才启用；纯文本
 * 复制路径完全不经过本策略，行为零变化。token 格式复用 CustomEmojiProtocol，
 * 避免两处协议漂移。
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.data.emoji.CustomEmojiProtocol

object ClipboardMediaEntryPolicy {

    private val TOKEN_PREFIX get() = CustomEmojiProtocol.TOKEN_PREFIX

    // ---------------------------------------------------------------- token 编解码

    /** 严格判定单个 sticker token（前缀 + packId/file 两段）。 */
    fun isStickerToken(text: String): Boolean =
        text.startsWith(TOKEN_PREFIX) &&
            text.length > TOKEN_PREFIX.length &&
            text.indexOf('/', TOKEN_PREFIX.length) > TOKEN_PREFIX.length

    /** 把若干 token 编码成一条历史条目文本；无合法 token 返回 null。 */
    fun encodeTokens(tokens: List<String>): String? {
        val valid = tokens.filter { isStickerToken(it) }.distinct()
        return if (valid.isEmpty()) null else valid.joinToString(separator = "\n")
    }

    /**
     * 解析历史条目文本为 token 列表。只有「每一行都是合法 token」才认定是图像
     * 条目；普通文本（哪怕以私用区字符开头）返回 null，绝不被误当图像上屏。
     */
    fun parseTokens(text: String): List<String>? {
        if (!text.startsWith(TOKEN_PREFIX)) return null
        val parts = text.split('\n')
        if (parts.isEmpty() || parts.any { !isStickerToken(it) }) return null
        return parts
    }

    // ---------------------------------------------------------------- 私有编码识别

    /**
     * 宿主应用私有表情编码的文本特征。逐码点判定，正确处理代理对：
     *  - C0/C1 控制字符（保留 \t \n \r）、DEL；
     *  - 私用区 U+E000..U+F8FF（应用自定义表情码点）；
     *  - U+FFFC OBJECT REPLACEMENT（含 ImageSpan 的 Spanned 的 toString 投影）；
     *  - U+FFFD REPLACEMENT（字节被错误解码后的替换符）；
     *  - 未配对的高低代理项；
     *  - emoji / 符号与 Latin Extended-A 带变音字母混排（用户实测
     *    `\u0014¨\u0014Ľ🐸\u0014Ľ` 与 `🐸 ... NN7tŝ` 两种形态）。
     *
     * 刻意**不**把「纯 emoji」或「纯 Latin Extended-A 文字」判为私有编码：
     * 前者是合法 Unicode 文本，后者可能是捷克语 / 斯洛伐克语等真实文字。
     */
    fun isHostPrivateEncoding(text: String): Boolean {
        if (text.isEmpty()) return false
        var extendedLatin = false
        var emojiOrSymbol = false
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val cp: Int
            when {
                ch.isHighSurrogate() -> {
                    if (i + 1 >= n || !text[i + 1].isLowSurrogate()) return true
                    cp = Character.toCodePoint(ch, text[i + 1])
                    i++
                }
                ch.isLowSurrogate() -> return true
                else -> cp = ch.code
            }
            when {
                cp in 0xE000..0xF8FF -> return true
                cp == 0xFFFC || cp == 0xFFFD -> return true
                cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D -> return true
                cp in 0x7F..0x9F -> return true
                cp in 0x0100..0x017F -> extendedLatin = true
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF -> emojiOrSymbol = true
            }
            i++
        }
        return extendedLatin && emojiOrSymbol
    }

    /**
     * 显示 / 上屏兜底用的**严格**判据：只认「几乎不可能是用户真实文本」的
     * 损坏串（私用区、对象替换符、替换符、未配对代理、emoji 与 Latin
     * Extended-A 混排），刻意**不含**纯 C0/C1 控制字符判据——避免误伤含
     * 控制字符的日志、终端输出等真实文本。
     *
     * 用途：v1.0.79 之前已落库的历史乱码条目不会随升级自动消失。这些条目
     * 没有关联的图像 token，无法还原成表情，但至少不能再显示成乱码、也不能
     * 被原样 commitText 输出。本判据不涉及删除或修改用户数据。
     */
    fun isDisplayCorrupted(text: String): Boolean {
        if (text.isEmpty()) return false
        var extendedLatin = false
        var emojiOrSymbol = false
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val cp: Int
            when {
                ch.isHighSurrogate() -> {
                    if (i + 1 >= n || !text[i + 1].isLowSurrogate()) return true
                    cp = Character.toCodePoint(ch, text[i + 1])
                    i++
                }
                ch.isLowSurrogate() -> return true
                else -> cp = ch.code
            }
            when {
                cp in 0xE000..0xF8FF -> return true
                cp == 0xFFFC || cp == 0xFFFD -> return true
                cp in 0x0100..0x017F -> extendedLatin = true
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF -> emojiOrSymbol = true
            }
            i++
        }
        return extendedLatin && emojiOrSymbol
    }

    // ---------------------------------------------------------------- 条目路由

    /**
     * 本次剪贴板事件是否应记为「图像条目」而非「文本条目」。
     *
     * @param rawText       ClipboardEntry.fromClipData 得到的待落库文本
     * @param imageCaptured 本次确实从 ClipData 成功导入了至少一张图像
     */
    fun shouldUseImageEntry(rawText: String, imageCaptured: Boolean): Boolean {
        if (!imageCaptured) return false
        if (rawText.isEmpty()) return true
        if (isHostPrivateEncoding(rawText)) return true
        // 本应用自己写回系统剪贴板 / 重新复制的图像条目，不重复记录、不回环
        if (parseTokens(rawText) != null) return true
        return looksLikeUriDerived(rawText)
    }

    /**
     * fromClipData 在 Item.text 为空时会从 URI / Intent 派生文本（路径、数字 id、
     * action 字符串）——这些同样不是用户想粘贴的正文。只在「长、纯 ASCII、
     * 无空白、含路径/扩展名/数字特征」时命中，避免误伤普通英文单词。
     */
    private fun looksLikeUriDerived(text: String): Boolean {
        if (text.contains("://")) return true
        if (text.startsWith("/")) return true
        if (text.length < 8) return false
        if (text.any { it.isWhitespace() || it.code > 0x7F }) return false
        if (!(text.contains('/') || text.contains('.') || text.any { it.isDigit() })) return false
        return text.all {
            it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == '/'
        }
    }
}
