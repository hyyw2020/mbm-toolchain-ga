/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * ContextPredictor 纯 JVM 单测（v1.0.72）。
 *
 * 最重要的架构断言：预测**绝不覆盖用户硬层**——任一键已有用户冠军、或该词
 * 已被用户主动选过，整词让位。这保证 v1.0.66~71「选择 >= 1 次即第一」不被削弱。
 */
package org.fcitx.fcitx5.android.recall.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPredictorTest {

    /** 测试用键映射：显式给出，未登记的词给一个共享键 zz 便于测去重。 */
    private fun keys(vararg pairs: Pair<String, Set<String>>): (String) -> Set<String> {
        val m = pairs.toMap()
        return { w -> m[w] ?: setOf("zz") }
    }

    private val noHistory: (String) -> Boolean = { false }
    private val noUserWord: (String) -> Boolean = { false }
    private val noBigram: (String, Int) -> List<Pair<String, Int>> = { _, _ -> emptyList() }

    private fun predictor(cfg: PredictionConfig = PredictionConfig()) = ContextPredictor(cfg)

    // ---------------------------------------------------------------- 基础

    @Test
    fun emptyContext_returnsNoPrediction() {
        val r = predictor().predict(
            context = emptyList(),
            bigram = { _, _ -> listOf("天气" to 3) },
            keysFor = keys("天气" to setOf("tq", "tianqi")),
            hasUserHistory = noHistory,
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun noBigramData_returnsNoPrediction() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = noBigram,
            keysFor = keys(),
            hasUserHistory = noHistory,
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun bigramHit_writesOrderOneChampionOnEveryKey() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 3) },
            keysFor = keys("天气" to setOf("tq", "tianqi")),
            hasUserHistory = noHistory,
        )
        // 首字母键 + 全拼键各一行，都是第一候选位
        assertEquals(2, r.size)
        assertTrue(r.all { it.order == 1 })
        assertTrue(r.all { it.word == "天气" })
        assertEquals(setOf("tq", "tianqi"), r.map { it.key }.toSet())
    }

    @Test
    fun lowConfidence_isFiltered() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 1, "晚上" to 2) },
            keysFor = keys("天气" to setOf("tq"), "晚上" to setOf("ws")),
            hasUserHistory = noHistory,
        )
        assertEquals(listOf("晚上"), r.map { it.word }.distinct())
    }

    // ---------------------------------------------------------------- 让位

    @Test
    fun userHistoryOnAnyKey_makesWholeWordYield() {
        // 全拼键 tianqi 没有历史，但首字母键 tq 有 —— 必须整词让位，
        // 否则同一词在缩写下第 2、全拼下第 1，排序割裂。
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 5) },
            keysFor = keys("天气" to setOf("tq", "tianqi")),
            hasUserHistory = { it == "tq" },
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun userAlreadySelectedWord_yieldsAcrossAllKeys() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 5) },
            keysFor = keys("天气" to setOf("tq", "tianqi")),
            hasUserHistory = noHistory,
            hasUserWord = { it == "天气" },
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun pinnedEntry_isSkipped() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 5) },
            keysFor = keys("天气" to setOf("tq", "tianqi")),
            hasUserHistory = noHistory,
            isPinned = { k, w -> k == "tq" && w == "天气" },
        )
        // 置顶命中任一键 ⇒ 整词不写（避免只写一半键造成排序割裂）
        assertTrue(r.isEmpty())
    }

    // ---------------------------------------------------------------- 排序 / 去重

    @Test
    fun trigramHitOutranksPlainBigram() {
        val r = predictor().predict(
            context = listOf("今天", "下午"),
            bigram = { prev, _ ->
                if (prev == "今天") listOf("天气" to 5) else emptyList()
            },
            trigram = { _, _, _ -> listOf("晚上" to 5) },
            keysFor = keys("天气" to setOf("tq"), "晚上" to setOf("ws")),
            hasUserHistory = noHistory,
        )
        // trigram 命中额外 +1，应排在 bigram 词之前
        assertEquals(listOf("晚上", "天气"), r.map { it.word }.distinct())
    }

    @Test
    fun trigramOutranksBigram_evenWhenTrigramCountIsLower() {
        // v1.0.85 真 bug 回归：trigram 三词共现天然比 bigram 双词稀少、计数更低。
        // 设计文档与注释承诺「trigram 命中保证排在单步之前」，旧实现只在同词上 +1，
        // 当两词不同且 trigram 计数明显低于 bigram 时失效（bigram=9 vs trigram=2）。
        val r = predictor().predict(
            context = listOf("今天", "下午"),
            bigram = { prev, _ -> if (prev == "今天") listOf("天气" to 9) else emptyList() },
            trigram = { _, _, _ -> listOf("晚上" to 2) },
            keysFor = keys("天气" to setOf("tq"), "晚上" to setOf("ws")),
            hasUserHistory = noHistory,
        )
        assertEquals(listOf("晚上", "天气"), r.map { it.word }.distinct())
    }

    @Test
    fun trigramStillLosesToUserHistory() {
        // 分层只影响 trigram vs bigram 的相对优先，绝不能越过「用户硬层让位」铁律
        val r = predictor().predict(
            context = listOf("今天", "下午"),
            bigram = { _, _ -> emptyList() },
            trigram = { _, _, _ -> listOf("晚上" to 9) },
            keysFor = keys("晚上" to setOf("ws")),
            hasUserHistory = { it == "ws" },
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun onePredictionPerKey_higherConfidenceWins() {
        // 两个后继词共享同一个键，只允许置信更高者占该键
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 2, "体温" to 9) },
            keysFor = keys("天气" to setOf("tq"), "体温" to setOf("tq")),
            hasUserHistory = noHistory,
        )
        assertEquals(1, r.size)
        assertEquals("体温", r.first().word)
    }

    @Test
    fun stableOrdering_sameConfidenceUsesWordOrder() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 3, "晚上" to 3, "安全" to 3) },
            keysFor = keys(
                "天气" to setOf("tq"), "晚上" to setOf("ws"), "安全" to setOf("aq")
            ),
            hasUserHistory = noHistory,
        )
        // 置信相同按词字典序（码点升序）：天(U+5929) < 安(U+5B89) < 晚(U+665A)
        assertEquals(listOf("天气", "安全", "晚上"), r.map { it.word })
    }

    @Test
    fun noDuplicateKeyWordRows() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 3) },
            keysFor = keys("天气" to setOf("tq", "tq", "tianqi", "tianqi")),
            hasUserHistory = noHistory,
        )
        assertEquals(2, r.size)
        assertEquals(r.size, r.distinct().size)
    }

    // ---------------------------------------------------------------- 防污染

    @Test
    fun invalidWords_areFiltered() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ ->
                listOf(
                    "天" to 5,                       // 1 字
                    "a" to 5,                        // 非汉字
                    "天气abc" to 5,                  // 混排
                    "中华人民共和国万岁" to 5,        // 9 字，超上限
                    "天气" to 5,                     // 唯一合法
                )
            },
            keysFor = keys("天气" to setOf("tq")),
            hasUserHistory = noHistory,
        )
        assertEquals(listOf("天气"), r.map { it.word }.distinct())
    }

    @Test
    fun invalidKeys_areFiltered() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 5) },
            keysFor = keys(
                "天气" to setOf(
                    "a",                              // 单字母（emoji 污染防线）
                    "t1",                             // 含数字
                    "TQ",                             // 非小写
                    "abcdefghijklmnopqrstuvwxyz",     // 26 > 24
                    "tq",                             // 合法
                )
            ),
            hasUserHistory = noHistory,
        )
        assertEquals(listOf("tq"), r.map { it.key })
    }

    @Test
    fun wordWithNoValidKey_isSkipped() {
        val r = predictor().predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 5) },
            keysFor = keys("天气" to setOf("a", "T1")),
            hasUserHistory = noHistory,
        )
        assertTrue(r.isEmpty())
    }

    // ---------------------------------------------------------------- 容量护栏

    @Test
    fun maxWordsCap_isRespected() {
        val words = (1..8).map { "词${'a' + it}" to 5 }
        val r = predictor(PredictionConfig(maxWords = 2)).predict(
            context = listOf("今天"),
            bigram = { _, _ -> words },
            keysFor = { w -> setOf("k" + ('a' + (w.last() - 'a'))) },
            hasUserHistory = noHistory,
        )
        assertTrue(r.map { it.word }.distinct().size <= 2)
    }

    @Test
    fun maxEntriesCap_isRespected() {
        val r = predictor(PredictionConfig(maxEntries = 3)).predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 5, "晚上" to 4, "安全" to 3) },
            keysFor = keys(
                "天气" to setOf("tq", "tianqi"),
                "晚上" to setOf("ws", "wanshang"),
                "安全" to setOf("aq", "anquan"),
            ),
            hasUserHistory = noHistory,
        )
        assertTrue(r.size <= 3)
        assertEquals(r.size, r.distinct().size)
    }

    @Test
    fun predictionIsSnapshot_notCumulative() {
        val p = predictor()
        val kf = keys("天气" to setOf("tq"), "晚上" to setOf("ws"))
        val first = p.predict(
            context = listOf("今天"),
            bigram = { _, _ -> listOf("天气" to 5) },
            keysFor = kf,
            hasUserHistory = noHistory,
        )
        val second = p.predict(
            context = listOf("下午"),
            bigram = { _, _ -> listOf("晚上" to 5) },
            keysFor = kf,
            hasUserHistory = noHistory,
        )
        // 纯函数：不保留上次结果，调用方每次上屏全量替换托管行
        assertEquals(listOf("天气"), first.map { it.word })
        assertEquals(listOf("晚上"), second.map { it.word })
    }
}
