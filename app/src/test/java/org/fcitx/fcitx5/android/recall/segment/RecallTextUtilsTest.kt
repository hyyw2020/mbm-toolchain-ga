/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * RecallTextUtils.properNounTokens（通道 D 混合专名抽取）JVM 单测。
 * 依据函数 KDoc 声明的三条判据逐条固化：
 *   1) 至少一个汉字；
 *   2) 至少一个「专有名词信号」——ASCII 字母，或命名分隔符（·/•/・ 等）；
 *      （仅含数字混合的中文短句如「苹果12个」不视为专名，不抽取）
 *   3) 长度 ∈ [2, MAX_PROPERNOUN_LEN]。
 *
 * v1.0.82 真 bug：判据 2 原实现用 Char.isLetter()，而汉字是 Unicode 字母
 * （java.lang.Character.isLetter('苹') == true），于是对任何含汉字的 token
 * 恒为 true，判据 2 形同虚设；纯汉字短语 / 汉字+数字短句被通道 D 当成
 * 「混合中英文专名」越过 isAllHan 门控写进词库（RecallSegmenter 通道 D
 * emit(... allowMixed=true, forceLength=true)），污染用户词库与候选。
 */
package org.fcitx.fcitx5.android.recall.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallTextUtilsTest {

    @Test
    fun 汉字加数字短句不视为专名() {
        // KDoc 判据 2 明确举例：仅含数字混合的中文短句不抽取
        listOf("苹果12个", "第3章", "微信2", "价格199元", "2024年计划").forEach {
            assertTrue(
                "「$it」仅汉字+数字，不应被当成混合专名抽取",
                RecallTextUtils.properNounTokens(it).isEmpty()
            )
        }
    }

    @Test
    fun 整句纯汉字不应被通道D吞入() {
        // KDoc：整句汉字交给 hanRuns 处理，通道 D 只收「含 ASCII 字母 / 命名分隔符」的混合专名
        listOf("今天天气很好", "我们一起去公园", "王者荣耀真好玩").forEach {
            assertTrue(
                "纯汉字短语「$it」不应被通道 D 当专名抽取",
                RecallTextUtils.properNounTokens(it).isEmpty()
            )
        }
    }

    @Test
    fun 合法混合中英文专名仍保留() {
        // 对照组：真正的「汉字 + ASCII 字母」混合专名，收紧判据后仍须命中
        listOf("雨见cBeta", "微信WeChat", "抖音v30", "哔哩哔哩bilibili").forEach {
            assertTrue(
                "合法混合专名「$it」应被保留",
                RecallTextUtils.properNounTokens(it).contains(it)
            )
        }
    }

    @Test
    fun 命名分隔符专名仍保留() {
        // 对照组：含 PROPERNOUN_GLUE 命名分隔符的专名，收紧判据后仍须命中
        listOf("云·战双帕弥什", "崩坏·星穹铁道", "剑与远征·启程").forEach {
            assertTrue(
                "含命名分隔符的专名「$it」应被保留",
                RecallTextUtils.properNounTokens(it).contains(it)
            )
        }
    }

    @Test
    fun 纯汉字段仍由hanRuns正常抽取不丢词() {
        // 收紧通道 D 不等于丢词：整句汉字本就由 hanRuns 通道负责
        assertEquals(listOf("今天天气很好"), RecallTextUtils.hanRuns("今天天气很好"))
        assertTrue(RecallTextUtils.hanRuns("王者荣耀真好玩").contains("王者荣耀真好玩"))
    }

    @Test
    fun 汉字门控与长度边界() {
        assertTrue(RecallTextUtils.properNounTokens("").isEmpty())
        assertTrue("无汉字的纯英文不收", RecallTextUtils.properNounTokens("cBeta").isEmpty())
        assertTrue("无汉字的纯数字不收", RecallTextUtils.properNounTokens("2024").isEmpty())
        assertTrue("长度 < 2 不收", RecallTextUtils.properNounTokens("A").isEmpty())
    }
}
