/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.0.77：统一容量策略回归（UTF-16 计费 / 大文本写回 / 可配置边界 / 披露）。 */
class ClipboardCapacityPolicyTest {

    @Test
    fun parcelBytesBillsUtf16CodeUnits() {
        // Binder 按 UTF-16 码元计费，不能用 UTF-8 字节数（ASCII 会低估 2 倍）
        assertEquals(0L, ClipboardCapacityPolicy.parcelBytes(""))
        assertEquals(6L, ClipboardCapacityPolicy.parcelBytes("abc"))
        assertEquals(6L, ClipboardCapacityPolicy.parcelBytes("中文的"))  // 3 码元 × 2
        assertEquals(4L, ClipboardCapacityPolicy.parcelBytes("\uD83D\uDE00")) // surrogate pair
    }

    @Test
    fun shortTextUsesPlainClipboard() {
        assertEquals(
            ClipboardCapacityPolicy.CopyPlan.PLAIN,
            ClipboardCapacityPolicy.copyPlan("普通复制内容")
        )
        assertEquals(
            ClipboardCapacityPolicy.CopyPlan.PLAIN,
            ClipboardCapacityPolicy.copyPlan("x".repeat(ClipboardCapacityPolicy.PLAIN_CLIP_SAFE_CHARS))
        )
    }

    @Test
    fun overThresholdSwitchesToFileUri() {
        val text = "x".repeat(ClipboardCapacityPolicy.PLAIN_CLIP_SAFE_CHARS + 1)
        assertEquals(
            ClipboardCapacityPolicy.CopyPlan.FILE_URI,
            ClipboardCapacityPolicy.copyPlan(text)
        )
        // 中文按码元计费，不因 UTF-8 字节更多而误判
        val cjk = "中".repeat(ClipboardCapacityPolicy.PLAIN_CLIP_SAFE_CHARS + 1)
        assertEquals(
            ClipboardCapacityPolicy.CopyPlan.FILE_URI,
            ClipboardCapacityPolicy.copyPlan(cjk)
        )
    }

    @Test
    fun customSafeCharsIsRespected() {
        assertEquals(
            ClipboardCapacityPolicy.CopyPlan.PLAIN,
            ClipboardCapacityPolicy.copyPlan("12345", safeChars = 5)
        )
        assertEquals(
            ClipboardCapacityPolicy.CopyPlan.FILE_URI,
            ClipboardCapacityPolicy.copyPlan("123456", safeChars = 5)
        )
    }

    @Test
    fun resolveUiLoadCharsUsesDefaultForNonPositive() {
        val d = ClipboardCapacityPolicy.DEFAULT_UI_LOAD_CHARS
        assertEquals(d, ClipboardCapacityPolicy.resolveUiLoadChars(0))
        assertEquals(d, ClipboardCapacityPolicy.resolveUiLoadChars(-1))
        assertEquals(1, ClipboardCapacityPolicy.resolveUiLoadChars(0, default = 0))
    }

    @Test
    fun resolveUiLoadCharsHonorsUserConfiguredValue() {
        assertEquals(5_000_000, ClipboardCapacityPolicy.resolveUiLoadChars(5_000_000))
        assertEquals(1, ClipboardCapacityPolicy.resolveUiLoadChars(1))
    }

    @Test
    fun needsDisclosureDetectsTruncation() {
        assertTrue(ClipboardCapacityPolicy.needsDisclosure(100, 101L))
        assertFalse(ClipboardCapacityPolicy.needsDisclosure(100, 100L))
        assertFalse(ClipboardCapacityPolicy.needsDisclosure(200, 100L))
    }

    @Test
    fun editingDefaultIsLargerThanViewDefault() {
        // 编辑框可承载更多；用户显式配置时三者统一用配置值
        assertTrue(
            ClipboardCapacityPolicy.DEFAULT_EDIT_CHARS >=
                ClipboardCapacityPolicy.DEFAULT_UI_LOAD_CHARS
        )
        assertTrue(ClipboardCapacityPolicy.DEFAULT_CHIPS_PER_PAGE > 0)
    }
}
