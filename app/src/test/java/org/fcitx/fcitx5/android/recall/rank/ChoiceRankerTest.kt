/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * ChoiceRanker 纯 JVM 单元测试（无需设备 / 模拟器 / Robolectric）。
 *
 * 覆盖需求书要求的 9 类场景：aq→安全/爱情 示例、时间衰减、冷启动、连续选择、
 * 误选恢复、清空历史、多拼音隔离；另加去抖、次数封顶、稳定顺序、手动置顶优先、
 * 防污染、栈深边界、评分公式、性能预算。
 *
 * 架构断言（最重要）：冠军(最近一次选择)永不被任何分数推翻 —— 这是
 * 「选择 >= 1 次即提高」的确定性保证，也是 v1.0.63~66 融合打分竞争失败的根因修正。
 */
package org.fcitx.fcitx5.android.recall.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoiceRankerTest {

    private var now = 1_000_000L

    private fun ranker(cfg: ChoiceConfig = ChoiceConfig()) = ChoiceRanker(cfg) { now }

    /** 生产用 AppPhraseImporter.keysForName；测试用固定映射。 */
    private val keys: (String) -> Collection<String> = { w ->
        when (w) {
            "安全", "爱情", "安庆", "安排", "安静", "按期", "暗器", "傲气" -> listOf("aq")
            "北京" -> listOf("bj")
            "重复" -> listOf("aq", "bj")   // 同一词在多个拼音下独立统计
            else -> emptyList()
        }
    }

    private val allAq: (String) -> Collection<String> = { listOf("aq") }

    private fun orderWords(key: String, r: ChoiceRanker) = r.buildOrder(key).map { it.word }

    // ---------------------------------------------------------------- 1. 核心示例

    @Test
    fun `aq 安全 爱情 完整排序变化`() {
        val r = ranker()
        // T0 冷启动：无历史，完全交还 libime，不写任何自定义词组
        assertTrue(r.buildOrder("aq").isEmpty())

        // T1 选一次「安全」：>=1 次即提高，无条件第 1
        r.onCommit("安全", keys); now += 1000
        assertEquals(
            listOf(BoostEntry("aq", 1, "安全")),
            r.buildOrder("aq")
        )

        // T2 之后选「爱情」：爱情第 1、安全第 2（确定性，不依赖 libime 词频）
        r.onCommit("爱情", keys); now += 1000
        assertEquals(
            listOf(BoostEntry("aq", 1, "爱情"), BoostEntry("aq", 2, "安全")),
            r.buildOrder("aq")
        )

        // T3 再选「安全」：一次即反超，无需攒分数；爱情退到第 2
        r.onCommit("安全", keys); now += 1000
        assertEquals(listOf("安全", "爱情"), orderWords("aq", r))
        assertEquals(1, r.buildOrder("aq").first().order)
    }

    @Test
    fun `选择一次即提高 且冠军不受高频旧词挑战`() {
        // 反例场景：安全被选 12 次，爱情只选 1 次。
        // 融合打分竞争会让爱情上不了第 1；本方案冠军由 last-commit-wins 硬锁定。
        val r = ranker()
        repeat(12) { r.onCommit("安全", allAq); now += 1000 }
        r.onCommit("爱情", allAq); now += 1000
        assertEquals("爱情", r.buildOrder("aq").first().word)
        assertEquals(1, r.buildOrder("aq").first().order)
    }

    // ---------------------------------------------------------------- 2. 时间衰减

    @Test
    fun `评分按半衰期指数衰减`() {
        val r = ranker(ChoiceConfig(halfLifeMs = 1000L))
        val rec = ChoiceRecord(n = 1, lastAt = 0L, prevWord = "")
        val s0 = r.score(rec, 0L)
        val s1 = r.score(rec, 1000L)
        val s2 = r.score(rec, 2000L)
        assertEquals(s0 * 0.5, s1, 1e-9)
        assertEquals(s0 * 0.25, s2, 1e-9)
        assertTrue(s0 > 0.0)
    }

    @Test
    fun `非冠军随时间衰减退役 冠军永不因分数退役`() {
        val r = ranker(ChoiceConfig(halfLifeMs = 1000L, minScore = 0.15))
        // 步进远小于半衰期，保证「刚选完」时次位仍存活
        r.onCommit("安全", keys); now += 10
        r.onCommit("爱情", keys); now += 10
        assertEquals(2, r.buildOrder("aq").size)

        now += 20_000L   // 20 个半衰期后，两个词的软层分都趋近 0
        val order = r.buildOrder("aq")
        assertEquals(1, order.size)              // 次位「安全」退役
        assertEquals("爱情", order[0].word)      // 冠军仍在
        assertEquals(1, order[0].order)
    }

    // ---------------------------------------------------------------- 3. 冷启动

    @Test
    fun `冷启动无历史且 beta 随样本平滑增长`() {
        val r = ranker(ChoiceConfig(warmupSamples = 3))
        assertTrue(r.buildOrder("aq").isEmpty())
        assertEquals(0.0, r.beta("aq"), 1e-9)
        r.onCommit("安全", keys); now += 1000
        assertEquals(1.0 / 3, r.beta("aq"), 1e-9)
        r.onCommit("爱情", keys); now += 1000
        assertEquals(2.0 / 3, r.beta("aq"), 1e-9)
        r.onCommit("安庆", keys); now += 1000
        assertEquals(1.0, r.beta("aq"), 1e-9)
    }

    @Test
    fun `beta 为 0 时次位严格保持最近优先的栈序`() {
        val r = ranker()
        r.importState(
            ChoiceState(
                stacks = mapOf("aq" to listOf("安全", "爱情", "安庆")),
                records = mapOf(
                    "aq=>安全" to ChoiceRecord(n = 1, lastAt = 100L),
                    "aq=>爱情" to ChoiceRecord(n = 1, lastAt = 100L),
                    "aq=>安庆" to ChoiceRecord(n = 1, lastAt = 100L),
                ),
                samples = emptyMap(),   // beta = 0 => 硬层栈序主导
                lastWord = "",
            )
        )
        val order = r.buildOrder("aq", now = 100L)
        assertEquals(listOf("安全", "爱情", "安庆"), order.map { it.word })
        assertEquals(listOf(1, 2, 3), order.map { it.order })
    }

    // ---------------------------------------------------------------- 4. 连续选择

    @Test
    fun `连续选择 streak 封顶且有界加成`() {
        val r = ranker(ChoiceConfig(streakCap = 3, streakW = 0.15))
        repeat(8) { r.onCommit("安全", allAq); now += 1000 }
        val rec = r.exportState().records["aq=>安全"]
        assertNotNull(rec)
        assertEquals(3, rec!!.streak)
        val s = r.score(rec, now)
        assertTrue("加成应有上界", s <= (1.0 + 0.15 * 3) * (1.0 + 0.5) + 1e-9)
    }

    // ---------------------------------------------------------------- 5. 误选恢复

    @Test
    fun `误选惩罚压低次位并令其退役 冠军不受影响`() {
        val r = ranker()
        r.onCommit("安庆", keys); now += 1000
        r.onCommit("爱情", keys); now += 1000
        assertEquals(listOf("爱情", "安庆"), orderWords("aq", r))

        r.onMisclick("aq", "安庆")
        assertEquals(0.8, r.exportState().records["aq=>安庆"]!!.penalty, 1e-9)
        assertEquals(listOf("爱情"), orderWords("aq", r))   // 安庆软层分归零 => 退役
        assertEquals(1, r.buildOrder("aq").size)
    }

    @Test
    fun `短时改选触发疑似误选惩罚`() {
        val r = ranker(ChoiceConfig(misclickMs = 1500L))
        r.onCommit("安庆", keys)
        val t = now
        r.onCommit("爱情", keys)
        r.onQuickSwitch("aq", "安庆", now = t + 100L)
        assertTrue(r.exportState().records["aq=>安庆"]!!.penalty > 0.0)
    }

    // ---------------------------------------------------------------- 6. 清空历史

    @Test
    fun `clearAll 与 clearKey`() {
        val r = ranker()
        r.onCommit("安全", keys); now += 1000
        r.onCommit("北京", keys); now += 1000
        r.onCommit("重复", keys); now += 1000
        assertTrue(r.recordCount() >= 2)
        assertTrue(r.hasHistory("aq")); assertTrue(r.hasHistory("bj"))

        r.clearKey("aq")
        assertFalse(r.hasHistory("aq"))
        assertTrue(r.hasHistory("bj"))           // 其它键不受影响

        r.clearAll()
        assertFalse(r.hasHistory("bj"))
        assertEquals(0, r.recordCount())
        assertTrue(r.buildOrder("aq").isEmpty())
    }

    // ---------------------------------------------------------------- 7. 多拼音隔离

    @Test
    fun `不同键独立统计 同一词在不同拼音下也独立`() {
        val r = ranker()
        r.onCommit("安全", keys)
        assertTrue(r.buildOrder("bj").isEmpty())            // aq 的学习不污染 bj
        r.onCommit("北京", keys); now += 1000
        assertEquals("安全", r.buildOrder("aq").first().word)
        assertEquals("北京", r.buildOrder("bj").first().word)

        // 同一词「重复」同时映射到 aq 与 bj：两边各自有独立记录
        r.onCommit("重复", keys); now += 1000
        val st = r.exportState()
        assertNotNull(st.records["aq=>重复"])
        assertNotNull(st.records["bj=>重复"])
        assertTrue(r.hasHistory("aq") && r.hasHistory("bj"))
        r.clearKey("bj")
        assertNull(r.exportState().records["bj=>重复"])
        assertNotNull(r.exportState().records["aq=>重复"])
    }

    // ---------------------------------------------------------------- 补充：去抖 / 封顶 / 稳定 / 置顶 / 污染 / 边界

    @Test
    fun `去抖 同一词短时重复上屏不累加次数`() {
        val r = ranker(ChoiceConfig(dedupMs = 800L))
        repeat(6) { r.onCommit("安全", keys) }   // now 不变，全部落在去抖窗口
        assertEquals(1, r.exportState().records["aq=>安全"]!!.n)
        assertEquals(1, r.sampleCount("aq"))
        // 硬层仍保持冠军语义
        assertEquals("安全", r.buildOrder("aq").first().word)
        now += 1000
        r.onCommit("安全", keys)
        assertEquals(2, r.exportState().records["aq=>安全"]!!.n)
    }

    @Test
    fun `次数 log 平滑封顶 防热门词垄断`() {
        val r = ranker(ChoiceConfig(nCap = 20))
        val low = r.score(ChoiceRecord(n = 1, lastAt = 0L), 0L)
        val high = r.score(ChoiceRecord(n = 1000, lastAt = 0L), 0L)
        assertTrue(high > low)
        assertTrue("次数项应有上界", high <= (1.0 + 0.15 * 3) * (1.0 + 0.5) + 1e-9)
        // ln(1+1000)/ln(1+20) 被 clamp 效果：远小于线性 1000 倍
        assertTrue(high < low * 100)
    }

    @Test
    fun `同分稳定顺序 lastAt 降序优先于原栈序`() {
        val r = ranker()
        r.importState(
            ChoiceState(
                stacks = mapOf("aq" to listOf("安全", "爱情", "安庆")),
                records = mapOf(
                    "aq=>安全" to ChoiceRecord(n = 1, lastAt = 100L),
                    "aq=>爱情" to ChoiceRecord(n = 1, lastAt = 300L),
                    "aq=>安庆" to ChoiceRecord(n = 1, lastAt = 200L),
                ),
                samples = mapOf("aq" to 3),   // beta = 1 => 软层主导
                lastWord = "",
            )
        )
        val tail = r.buildOrder("aq", now = 300L).drop(1).map { it.word }
        // 冠军「安全」固定；tail 里 lastAt 最大的「爱情」应排最前
        assertEquals("爱情", tail.first())
    }

    @Test
    fun `手动置顶优先 冠军已置顶时整体让位`() {
        val r = ranker()
        r.onCommit("安全", keys); now += 1000
        r.onCommit("爱情", keys); now += 1000
        val pinChampion: (String, String) -> Boolean = { _, w -> w == "爱情" }
        assertTrue(r.buildOrder("aq", pinChampion).isEmpty())

        val pinTail: (String, String) -> Boolean = { _, w -> w == "安全" }
        assertEquals(
            listOf(BoostEntry("aq", 1, "爱情")),
            r.buildOrder("aq", pinTail)
        )
    }

    @Test
    fun `防污染 敏感串 超长文本 单字母键 长串切片 隐私模式`() {
        val r = ranker()
        // 敏感串
        r.onCommit("安全", keys, isSensitive = { true })
        assertFalse(r.hasHistory("aq"))
        // 隐私模式
        r.onCommit("安全", keys, privacyMode = true)
        assertFalse(r.hasHistory("aq"))
        // 超长文本（粘贴）
        r.onCommit("安全".repeat(9), keys)
        assertFalse(r.hasHistory("aq"))
        // 单字母键（非法键，应被过滤）
        r.onCommit("安全", resolveKeys = { listOf("a") })
        assertFalse(r.hasHistory("aq"))
        // 长串不会被切成跨词 2 字窗口当主动选词
        r.onCommit("安全爱情安庆", keys)
        assertFalse(r.hasHistory("aq"))
        // 非 2 字词
        r.onCommit("啊", keys)
        assertFalse(r.hasHistory("aq"))
        // 正常 2 字词仍可学习
        r.onCommit("安全", keys)
        assertTrue(r.hasHistory("aq"))
    }

    @Test
    fun `栈深恒不超过 K 淘汰软层最弱者`() {
        val r = ranker(ChoiceConfig(topK = 3))
        listOf("安全", "爱情", "安庆", "安排", "安静", "按期", "暗器", "傲气")
            .forEach { r.onCommit(it, allAq); now += 1000 }
        assertTrue("stack=${r.stackSize("aq")}", r.stackSize("aq") <= 3)
        assertTrue(r.buildOrder("aq").size <= 3)
        assertEquals(1, r.buildOrder("aq").first().order)
    }

    @Test
    fun `importState 与 exportState 往返一致`() {
        val a = ranker()
        a.onCommit("安全", keys); now += 1000
        a.onCommit("爱情", keys); now += 1000
        val before = a.buildOrder("aq")
        val b = ranker()
        b.importState(a.exportState())
        assertEquals(before, b.buildOrder("aq"))
        assertEquals(a.recordCount(), b.recordCount())
    }

    @Test
    fun `性能 千次上屏加排序在实时预算内`() {
        val r = ranker()
        val words = listOf("安全", "爱情", "安庆", "安排", "安静", "按期", "暗器", "傲气")
        val t0 = System.nanoTime()
        repeat(1000) { i ->
            now += 1000
            r.onCommit(words[i % words.size], allAq)
            r.buildOrder("aq")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        assertTrue("1000 次耗时 %.2f ms".format(ms), ms < 1000.0)
    }

    // ---------------------------------------------------------------- v1.0.70 多字词 / 长键

    /**
     * 复现用户反馈「哔哩哔哩输入优先级顺序有问题，总是因为 OCR 而被排为顺序 2」：
     * 「哔哩哔哩」是 4 字词、首字母键 blbl 是 4 字母，v1.0.69 硬层只收
     * 「2 字词 + 2 字母键」，两道过滤把它彻底挡在门外 ⇒ 用户主动选它也不写 order=1，
     * 只能吃 OCR/应用名自动模块的 order=2/3 ⇒ 永远第 2 候选位。
     */
    @Test
    fun `复现 4字词应用名与长键应进入硬层并锁定 order=1`() {
        val r = ranker()
        val blbl: (String) -> Collection<String> = { listOf("blbl") }
        r.onCommit("哔哩哔哩", blbl)
        assertTrue("4 字词「哔哩哔哩」应进入硬层栈", r.hasHistory("blbl"))
        val order = r.buildOrder("blbl")
        assertTrue("应产出冠军条目", order.isNotEmpty())
        assertEquals(1, order.first().order)
        assertEquals("哔哩哔哩", order.first().word)
    }

    @Test
    fun `多字词长键同样满足 最近一次第1 前一次第2`() {
        val r = ranker()
        val blbl: (String) -> Collection<String> = { listOf("blbl") }
        r.onCommit("哔哩哔哩", blbl); now += 1000
        r.onCommit("哔哩啪啦", blbl)
        val o = r.buildOrder("blbl")
        assertEquals("哔哩啪啦", o[0].word)
        assertEquals(1, o[0].order)
        assertEquals("哔哩哔哩", o[1].word)
        assertEquals(2, o[1].order)
        assertEquals("blbl", o[0].key)
    }

    @Test
    fun `单字母键仍被排除 不污染 a 候选栏`() {
        val r = ranker()
        r.onCommit("安全", resolveKeys = { listOf("a") })
        assertFalse(r.hasHistory("a"))
        assertTrue(r.buildOrder("a").isEmpty())
    }

    @Test
    fun `长串上屏整段成词 绝不产生跨词窗口切片垃圾`() {
        val r = ranker()
        r.onCommit("安全爱情安庆", resolveKeys = { listOf("aqaqaq") })
        val st = r.exportState()
        val learned = st.records.keys.map { it.substringAfter("=>") }
        assertEquals("只学一个整段词，不做任何窗口切片", listOf("安全爱情安庆"), learned)
        assertFalse("不得出现「全爱」这类独立跨词切片", learned.contains("全爱"))
    }

    @Test
    fun `多段上屏仍只学独立 2 字词 保持 v1_0_69 兼容`() {
        val r = ranker()
        r.onCommit("安全 爱情", resolveKeys = keys)
        assertTrue(r.hasHistory("aq"))
        assertTrue(
            "多段上屏不产生长词",
            r.exportState().records.keys.none { it.substringAfter("=>").length > 2 }
        )
    }

    @Test
    fun `状态往返保留多字词与长键`() {
        val a = ranker()
        val blbl: (String) -> Collection<String> = { listOf("blbl") }
        a.onCommit("哔哩哔哩", blbl)
        val b = ranker()
        b.importState(a.exportState())
        assertEquals(a.buildOrder("blbl"), b.buildOrder("blbl"))
        assertTrue(b.hasHistory("blbl"))
        assertEquals("哔哩哔哩", b.buildOrder("blbl").first().word)
    }
}
