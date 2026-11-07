/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** v1.0.77：逐字保真策略回归（修复 insertEntry/updateText 的 trim 静默丢字）。 */
class ClipboardTextFidelityTest {

    @Test
    fun onlyAllWhitespaceIsDropped() {
        assertTrue(ClipboardTextFidelity.shouldDrop(""))
        assertTrue(ClipboardTextFidelity.shouldDrop("   "))
        assertTrue(ClipboardTextFidelity.shouldDrop("\n\t  \r\n"))
        assertFalse(ClipboardTextFidelity.shouldDrop(" a "))
        assertFalse(ClipboardTextFidelity.shouldDrop("\n内容\n"))
        // Kotlin String.trim() 用 Char.isWhitespace()，NBSP(U+00A0) 也属空白：
        // 纯 NBSP 条目按「全空白」丢弃，与平台语义及旧实现一致（非本次回归点）。
        assertTrue(ClipboardTextFidelity.shouldDrop("\u00A0"))
        // 但只要含任意非空白字符就必须保留，且首尾空白不许被裁剪
        assertFalse(ClipboardTextFidelity.shouldDrop("\u00A0x\u00A0"))
    }

    @Test
    fun storedTextIsNeverTrimmed() {
        val cases = listOf(
            "  abc  ",
            "\n\n第一行\n第二行\n\n",
            "\uFEFF带 BOM 的原文\uFEFF",
            "中文🐸\uD83D\uDE00 ",
            " ",
            ""
        )
        for (raw in cases) {
            assertSame(raw, ClipboardTextFidelity.stored(raw))
            assertEquals(raw, ClipboardTextFidelity.stored(raw))
        }
    }

    @Test
    fun hashUsesOriginalTextNotTrimmedText() {
        val raw = "  abc\n"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, ClipboardTextFidelity.sha256(raw))
        assertEquals(64, ClipboardTextFidelity.sha256(raw).length)
        // 旧实现会 trim 后算哈希，导致「 A 」与「A」哈希相同却只存裁剪值
        assertNotEquals(ClipboardTextFidelity.sha256("  A  "), ClipboardTextFidelity.sha256("A"))
    }

    @Test
    fun hashIsStableAndContentAddressed() {
        val a = ClipboardTextFidelity.sha256("同一段内容\n")
        val b = ClipboardTextFidelity.sha256("同一段内容\n")
        val c = ClipboardTextFidelity.sha256("同一段内容")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun fidelityInvariantMatchesBlobWriteSemantics() {
        // blob 流式写入按原始字节算 SHA-256；保真策略必须与之同口径
        val raw = "  大文本前缀🐸\n中间\n后缀  "
        val fromPolicy = ClipboardTextFidelity.sha256(ClipboardTextFidelity.stored(raw))
        val fromBytes = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(fromBytes, fromPolicy)
    }
}
