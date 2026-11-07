/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.data.emoji.CustomEmojiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.79：剪贴板图像表情条目策略回归。
 *
 * 用户实测的两种乱码形态（均来自宿主应用复制自定义表情时给出的私有编码文本）：
 *  - `\u0014¨\u0014Ľ🐸\u0014Ľ`
 *  - 剪贴板建议条上的 `🐸 -7NN7tŝ`
 * 修复目标：这类文本不得再作为可粘贴正文落库；同时普通文本不被误伤。
 */
class ClipboardMediaEntryPolicyTest {

    private fun token(file: String) = CustomEmojiProtocol.token("clipboard", file)

    // ------------------------------------------------------------ 私有编码识别

    @Test
    fun detectsUserReportedControlCharacterEncoding() {
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("\u0014¨\u0014Ľ🐸\u0014Ľ"))
    }

    @Test
    fun detectsEmojiMixedWithLatinExtendedA() {
        // 截图中「青蛙 + -7NN7tŝ」形态：无控制字符，靠 emoji 与 Latin Extended-A 混排识别
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("🐸 -7NN7tŝ"))
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("7NN7ĵš 😀"))
    }

    @Test
    fun detectsPrivateUseAreaObjectReplacementAndReplacementChar() {
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("hi\uE123there"))
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("hi\uFFFCthere"))
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("hi\uFFFDthere"))
    }

    @Test
    fun detectsControlCharactersAndUnpairedSurrogates() {
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("a\u0001b"))
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("a\u0085b"))
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("a\uD83Db"))
        assertTrue(ClipboardMediaEntryPolicy.isHostPrivateEncoding("a\uDC38b"))
    }

    @Test
    fun displayCorruptedIsStricterThanPrivateEncoding() {
        // 旧版已落库的乱码条目：两种用户实测形态都要拦
        assertTrue(ClipboardMediaEntryPolicy.isDisplayCorrupted("\u0014¨\u0014Ľ🐸\u0014Ľ"))
        assertTrue(ClipboardMediaEntryPolicy.isDisplayCorrupted("🐸 -7NN7tŝ"))
        assertTrue(ClipboardMediaEntryPolicy.isDisplayCorrupted("hi\uFFFDthere"))
        assertTrue(ClipboardMediaEntryPolicy.isDisplayCorrupted("hi\uE123there"))
        // 含控制字符的普通日志 / 终端文本不得被拦，仍可正常显示与粘贴
        assertFalse(ClipboardMediaEntryPolicy.isDisplayCorrupted("line1\u0001line2\n"))
        assertFalse(ClipboardMediaEntryPolicy.isDisplayCorrupted("你好，世界"))
        assertFalse(ClipboardMediaEntryPolicy.isDisplayCorrupted("🐸🐸"))
        assertFalse(ClipboardMediaEntryPolicy.isDisplayCorrupted("Ľubomír"))
        assertFalse(ClipboardMediaEntryPolicy.isDisplayCorrupted(""))
    }

    @Test
    fun doesNotFlagOrdinaryText() {
        assertFalse(ClipboardMediaEntryPolicy.isHostPrivateEncoding(""))
        assertFalse(ClipboardMediaEntryPolicy.isHostPrivateEncoding("你好，世界！"))
        assertFalse(ClipboardMediaEntryPolicy.isHostPrivateEncoding("Hello world\nsecond line\t"))
        // 纯 emoji 是合法 Unicode 文本，不是私有编码
        assertFalse(ClipboardMediaEntryPolicy.isHostPrivateEncoding("🐸🐸😀✌️"))
        // 捷克语 / 斯洛伐克语中的 Ľ、ŝ 是真实文字，不得误判
        assertFalse(ClipboardMediaEntryPolicy.isHostPrivateEncoding("Ľubomír ťažký"))
        assertFalse(ClipboardMediaEntryPolicy.isHostPrivateEncoding("¨ Ľ ŝ"))
        assertFalse(ClipboardMediaEntryPolicy.isHostPrivateEncoding("  abc  \n\n"))
    }

    // ------------------------------------------------------------ token 编解码

    @Test
    fun tokenRoundTripPreservesAllImages() {
        val tokens = listOf(token("clip_a.png"), token("clip_b.webp"), token("clip_c.gif"))
        val encoded = ClipboardMediaEntryPolicy.encodeTokens(tokens)
        assertEquals(tokens.joinToString("\n"), encoded)
        assertEquals(tokens, ClipboardMediaEntryPolicy.parseTokens(encoded!!))
    }

    @Test
    fun encodeTokensDeduplicatesAndFiltersInvalid() {
        val a = token("clip_a.png")
        val b = token("clip_b.png")
        val encoded = ClipboardMediaEntryPolicy.encodeTokens(listOf(a, b, a, "not a token", ""))
        assertEquals(listOf(a, b), ClipboardMediaEntryPolicy.parseTokens(encoded!!))
    }

    @Test
    fun encodeTokensReturnsNullWhenNoValidToken() {
        assertNull(ClipboardMediaEntryPolicy.encodeTokens(emptyList()))
        assertNull(ClipboardMediaEntryPolicy.encodeTokens(listOf("plain text", "")))
    }

    @Test
    fun parseTokensRejectsOrdinaryTextAndMixedLines() {
        assertNull(ClipboardMediaEntryPolicy.parseTokens("hello world"))
        assertNull(ClipboardMediaEntryPolicy.parseTokens("\u0014¨\u0014Ľ🐸\u0014Ľ"))
        // 一行 token 一行普通文本 → 整条不算图像条目，绝不误走上屏分支
        assertNull(ClipboardMediaEntryPolicy.parseTokens(token("clip_a.png") + "\nplain text"))
        assertNull(ClipboardMediaEntryPolicy.parseTokens(""))
    }

    @Test
    fun isStickerTokenIsStrict() {
        assertTrue(ClipboardMediaEntryPolicy.isStickerToken(token("clip_a.png")))
        assertFalse(ClipboardMediaEntryPolicy.isStickerToken(CustomEmojiProtocol.TOKEN_PREFIX))
        assertFalse(ClipboardMediaEntryPolicy.isStickerToken(CustomEmojiProtocol.TOKEN_PREFIX + "noSlash"))
        assertFalse(ClipboardMediaEntryPolicy.isStickerToken("clip_a.png"))
    }

    // ------------------------------------------------------------ 条目路由

    @Test
    fun routingIsDisabledWithoutCapturedImage() {
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("\u0014¨\u0014Ľ🐸\u0014Ľ", false))
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("hello", false))
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("", false))
    }

    @Test
    fun routingReplacesPrivateEncodingAndUriDerivedText() {
        assertTrue(ClipboardMediaEntryPolicy.shouldUseImageEntry("\u0014¨\u0014Ľ🐸\u0014Ľ", true))
        assertTrue(ClipboardMediaEntryPolicy.shouldUseImageEntry("", true))
        assertTrue(ClipboardMediaEntryPolicy.shouldUseImageEntry("content://media/12345", true))
        assertTrue(ClipboardMediaEntryPolicy.shouldUseImageEntry("/storage/emulated/0/DCIM/a.jpg", true))
        assertTrue(ClipboardMediaEntryPolicy.shouldUseImageEntry("clip_9f3a2b.png", true))
        // 本应用自己写回的图像条目：识别为图像条目（调用方据此跳过重复录入）
        assertTrue(ClipboardMediaEntryPolicy.shouldUseImageEntry(token("clip_a.png"), true))
    }

    @Test
    fun routingKeepsOrdinaryUserText() {
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("你好，世界", true))
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("Hello world", true))
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("internationalization", true))
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("a@b.com", true))
        assertFalse(ClipboardMediaEntryPolicy.shouldUseImageEntry("🐸表情", true))
    }
}
