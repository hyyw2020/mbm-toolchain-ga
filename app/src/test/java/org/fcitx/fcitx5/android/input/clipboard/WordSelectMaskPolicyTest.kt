/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.74 回归单测：用户实测「反复点拆分选词，显示的都是小点点」。
 * 根因是全局偏好（默认 true）被当成「该条目是否敏感」用，普通文本整屏打码。
 */
class WordSelectMaskPolicyTest {

    @Test
    fun ordinaryEntryShowsOriginalTextWhenPrefIsDefaultOn() {
        // 用户实际场景：clipboardMaskSensitive 默认 true，但复制的是普通文本。
        assertFalse(WordSelectMaskPolicy.shouldMask(entrySensitive = false, maskPrefEnabled = true))
        val raw = "不要改变原有功能，查找并且修复一轮bug,然后发布"
        assertEquals(raw, WordSelectMaskPolicy.chipDisplayText(raw, mask = false))
        assertFalse(WordSelectMaskPolicy.chipDisplayText(raw, mask = false).contains('•'))
    }

    @Test
    fun sensitiveEntryIsStillMaskedWhenPrefOn() {
        // 隐私不回退：系统标记为敏感的条目（如密码）在偏好开启时仍打码。
        assertTrue(WordSelectMaskPolicy.shouldMask(entrySensitive = true, maskPrefEnabled = true))
        assertEquals("•••••", WordSelectMaskPolicy.chipDisplayText("12345", mask = true))
    }

    @Test
    fun turningPrefOffNeverMasks() {
        assertFalse(WordSelectMaskPolicy.shouldMask(entrySensitive = true, maskPrefEnabled = false))
        assertFalse(WordSelectMaskPolicy.shouldMask(entrySensitive = false, maskPrefEnabled = false))
    }

    @Test
    fun truthTableMatchesClipboardListSemantics() {
        // 与 ClipboardAdapter 的 `entry.sensitive && maskSensitive()` 完全一致。
        for (sensitive in listOf(false, true)) {
            for (pref in listOf(false, true)) {
                assertEquals(
                    "sensitive=$sensitive pref=$pref",
                    sensitive && pref,
                    WordSelectMaskPolicy.shouldMask(sensitive, pref)
                )
            }
        }
    }

    @Test
    fun unmaskedChipsPreserveChinesePunctuationEmojiAndNewline() {
        val raw = "安全 aq 🔐\n第二行 👨‍👩‍👧‍👦 🏳️‍🌈"
        assertEquals(raw, WordSelectMaskPolicy.chipDisplayText(raw, mask = false))
    }

    @Test
    fun maskedTextIsCappedAndLeaksNoOriginalCharacter() {
        val raw = "a".repeat(200)
        val masked = WordSelectMaskPolicy.chipDisplayText(raw, mask = true)
        assertEquals(24, masked.length)
        assertTrue(masked.all { it == '•' })
        assertFalse(masked.contains('a'))
    }
}
