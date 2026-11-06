/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * UTS #51 / Unicode CLDR RGI_Emoji 序列结构判据的 JVM 单测。
 * 核心断言：合法复合序列不得因宿主字体缺字形而被判为非法（数据层与渲染层解耦）。
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSequenceTest {

    @Test
    fun singleCodePointEmojisAreValid() {
        listOf("😀", "👍", "🎉", "❤", "☺", "🫩", "🪾", "🫆").forEach {
            assertTrue("$it 应为合法单码点表情", EmojiSequence.isEmojiSequence(it))
        }
    }

    @Test
    fun zwjSequencesAreValidAndNotSplit() {
        val family = "👨‍👩‍👧‍👦"           // ZWJ 四人家庭
        val couple = "👩‍❤️‍💋‍👨"            // ZWJ + VS16 组合
        val rainbow = "🏳️‍🌈"                // VS16 + ZWJ
        listOf(family, couple, rainbow, "🧑‍💻", "🐻‍❄️").forEach {
            assertTrue("$it 应为合法 ZWJ 序列", EmojiSequence.isEmojiSequence(it))
            assertTrue("$it 应被识别为复合序列", EmojiSequence.isComposite(it))
        }
        // 整段 ZWJ 序列必须是一个 grapheme cluster，不能被从中间截断
        assertEquals(listOf(family), EmojiSequence.graphemeClusters(family))
        assertEquals(listOf(couple), EmojiSequence.graphemeClusters(couple))
    }

    @Test
    fun skinToneModifiersAreValid() {
        listOf("👍🏽", "🧑🏻‍💻", "🤝🏿").forEach {
            assertTrue("$it 应为合法肤色修饰序列", EmojiSequence.isEmojiSequence(it))
        }
        // 单独的修饰符不是完整表情
        assertFalse(EmojiSequence.isEmojiSequence("🏽"))
    }

    @Test
    fun variationSelectorsAreValid() {
        assertTrue(EmojiSequence.isEmojiSequence("☺️"))   // U+263A + VS16
        assertTrue(EmojiSequence.isEmojiSequence("❤️"))
    }

    @Test
    fun keycapSequencesAreValidInBothForms() {
        assertTrue(EmojiSequence.isEmojiSequence("1️⃣")) // 1 + VS16 + U+20E3
        assertTrue(EmojiSequence.isEmojiSequence("#️⃣"))
        assertTrue(EmojiSequence.isEmojiSequence("\u0032\u20E3")) // 2 + U+20E3（无 VS16）
        assertFalse("纯数字不是键帽表情", EmojiSequence.isEmojiSequence("1"))
        assertFalse("数字后无 U+20E3 不是键帽表情", EmojiSequence.isEmojiSequence("12"))
    }

    @Test
    fun regionalIndicatorFlagsAreValidOnlyInPairs() {
        assertTrue(EmojiSequence.isEmojiSequence("🇨🇳"))
        assertTrue(EmojiSequence.isEmojiSequence("🇺🇸"))
        assertFalse("单个 RI 不是完整旗帜", EmojiSequence.isEmojiSequence("🇨"))
        assertEquals(2, EmojiSequence.graphemeClusters("🇨🇳🇺🇸").size)
    }

    @Test
    fun tagSequencesAreValid() {
        // subdivision flag: U+1F3F4 + TAG 字符序列 + CANCEL TAG
        val england = "🏴󠁧󠁢󠁥󠁮󠁧󠁿"
        assertTrue(EmojiSequence.isEmojiSequence(england))
        assertEquals(1, EmojiSequence.graphemeClusters(england).size)
    }

    @Test
    fun nonEmojiTextIsRejected() {
        listOf("安全", "aq", "hello", "123", "😀 安全", "", " ", "，。", "T_T").forEach {
            assertFalse("$it 不应被判为纯表情", EmojiSequence.isEmojiSequence(it))
        }
    }

    @Test
    fun emojiOnlyCoversMixedSequences() {
        assertTrue(EmojiSequence.isEmojiOnly("😀😃"))
        assertTrue(EmojiSequence.isEmojiOnly("🇨🇳👨‍👩‍👧‍👦1️⃣"))
        assertFalse(EmojiSequence.isEmojiOnly("安全😀"))
        assertFalse(EmojiSequence.isEmojiOnly(""))
    }

    @Test
    fun supplementaryPlaneIsHandledByCodePointNotUtf16Char() {
        // U+1F600 在 UTF-16 里是代理对；逐 Char 遍历会看到 D83D/DE00 而漏判
        assertEquals(1, EmojiSequence.codePoints("😀").size)
        assertEquals(0x1F600, EmojiSequence.codePoints("😀")[0])
        assertTrue(EmojiSequence.hasEmojiBase("😀"))
        assertFalse(EmojiSequence.hasEmojiBase("安全"))
    }

    @Test
    fun graphemeClustersDoNotBreakSurrogatePairs() {
        val clusters = EmojiSequence.graphemeClusters("😀😃🎉")
        assertEquals(3, clusters.size)
        clusters.forEach { assertTrue(EmojiSequence.isEmojiSequence(it)) }
    }
}
