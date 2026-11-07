/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** v1.0.77：多 Item ClipData 合并策略回归（复制侧「一段不丢」）。 */
class ClipItemTextMergeTest {

    @Test
    fun emptyInputsReturnNull() {
        assertNull(ClipItemTextMerge.merge(emptyList()))
        assertNull(ClipItemTextMerge.merge(listOf(null, null)))
        assertNull(ClipItemTextMerge.merge(listOf("", "")))
        assertNull(ClipItemTextMerge.merge(listOf(null, "")))
    }

    @Test
    fun singleItemIsReturnedVerbatim() {
        val raw = "  第一行\n第二行  \n"
        val out = ClipItemTextMerge.merge(listOf(raw))
        assertEquals(raw, out)
        // 单 Item 快路径不得新建字符串、不得追加换行、不得 trim
        assertSame(raw, out)
    }

    @Test
    fun singleItemWithOnlyWhitespaceIsKept() {
        // 纯空白也是用户复制的内容，不能按 isBlank 过滤掉
        assertEquals("   ", ClipItemTextMerge.merge(listOf("   ")))
        assertEquals("\n\n", ClipItemTextMerge.merge(listOf("\n\n")))
    }

    @Test
    fun multipleItemsJoinedInOrderWithNewline() {
        assertEquals(
            "标题\n正文第一段\n正文第二段",
            ClipItemTextMerge.merge(listOf("标题", "正文第一段", "正文第二段"))
        )
    }

    @Test
    fun nullAndEmptyItemsAreSkippedWithoutLosingOrder() {
        assertEquals(
            "A\nB\nC",
            ClipItemTextMerge.merge(listOf("A", null, "", "B", null, "C"))
        )
    }

    @Test
    fun multiItemPreservesLeadingAndTrailingWhitespace() {
        assertEquals(
            "  A \n\n B  ",
            ClipItemTextMerge.merge(listOf("  A ", "\n B  "))
        )
    }

    @Test
    fun multiItemKeepsUnicodeAndSurrogatePairsIntact() {
        val parts = listOf("中文🐸\uD83D\uDE00", "第二段 — café", "第三段")
        val out = ClipItemTextMerge.merge(parts)!!
        assertEquals(parts.joinToString("\n"), out)
        assertEquals(parts.sumOf { it.length } + 2, out.length)
    }

    @Test
    fun trailingSingleItemAfterBlanksStillFastPath() {
        val raw = "only"
        assertSame(raw, ClipItemTextMerge.merge(listOf(null, "", raw)))
    }
}
