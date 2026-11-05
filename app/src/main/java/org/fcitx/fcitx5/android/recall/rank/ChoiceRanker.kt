/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 候选词优先级优化 —— 纯算法层（零 Android 依赖，可直接跑 JVM 单测）。
 *
 * 设计要点（见 /workspace/candidate-priority-design.md）：
 *  1. 分层，而不是加权求和：
 *     - 硬层 HardOrder：某键「最近一次主动选择」无条件成为冠军(order=1)，
 *       其余历史词按「最近优先」组成 Top-K 有序栈占 order=2..K。
 *       冠军不受任何分数影响 —— 这是「选择 >= 1 次即提高」的确定性保证，
 *       也是对本仓库 v1.0.63~66「融合打分竞争 Top-1」实测失败(用户反馈
 *       「输好几遍才提高」)的架构级修正。
 *     - 软层 SoftScore：ln(1+n)·decay·streakBoost·ctxBoost - penalty，
 *       只用于「栈内次位重排 / 衰减退役 / 超容量淘汰」，永不推翻冠军。
 *  2. 冷启动平滑 beta：次位最终分 = (1-β)·硬层栈序分 + β·软层分，
 *     β = min(1, effectiveSamples/WARMUP)。首次选择 β 小 ⇒ 栈序(最近优先)主导，
 *     样本攒够 ⇒ 次数/衰减/上下文主导。冠军不受 β 影响，仍即时生效。
 *  3. 局部性：所有状态以精确键 key 分片；同词在不同 key 下独立统计。
 *  4. 防污染：去抖计数、连续选择加成封顶、次数 log 封顶、误选惩罚、
 *     粘贴/长文本/敏感串/非法键过滤、稳定排序、LRU 容量护栏。
 *  5. v1.0.70 覆盖扩展：单次上屏为「单一连续汉字段」时，整段视为用户主动选中的
 *     一个完整词（2 字词「安全」、4 字应用名「哔哩哔哩」、多字 OCR 捕获词皆然），
 *     键放宽到多字母首字母缩写（blbl / qmqz）。此前硬层只认「2 字词 + 2 字母键」，
 *     导致这些词永远拿不到 order=1，只能吃 OCR/应用名自动模块的 order=2/3，
 *     即用户反馈「哔哩哔哩总是因为 OCR 被排为顺序 2」。仍不做任何窗口切片，
 *     单字母键仍排除，粘贴/整句/敏感/隐私过滤全部保留。
 *
 * 本类不含 IO、不含 Android API、不含协程：调用方(UserChoiceStore)负责持久化、
 * 去抖落盘与 reload。所有时间通过可注入 clock 获取，便于时间衰减单测。
 */
package org.fcitx.fcitx5.android.recall.rank

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 可调参数（默认值由 RecallConfig 注入；单测可自行构造）。 */
data class ChoiceConfig(
    /** 每键硬插入位数：冠军=1，其余 2..K。K>=2 才能表达「爱情第1、安全第2」。 */
    val topK: Int = 3,
    /** 时间衰减半衰期 ms（默认 5 天，需求建议 3~7 天）。 */
    val halfLifeMs: Long = 5L * 24 * 3600 * 1000,
    /** 次数对数平滑封顶点，防热门词垄断。 */
    val nCap: Int = 20,
    /** 冷启动预热样本数：达到后 β=1，软层全力度介入。 */
    val warmupSamples: Int = 3,
    /** 连续选择加成上限。 */
    val streakCap: Int = 3,
    val streakW: Double = 0.15,
    /** 上下文共现加成系数（仅上一条上屏词命中时 +ctxW）。 */
    val ctxW: Double = 0.5,
    /** 一次疑似误选的惩罚扣减。 */
    val misclickPenalty: Double = 0.8,
    /** 软层退役下限：非冠军且 score 低于此值即从栈移除（衰减）。 */
    val minScore: Double = 0.15,
    /** 同一 key=>word 在此窗口内重复上屏不重复计数（防连点/长按）。 */
    val dedupMs: Long = 800L,
    /** 全局记录硬上限（LRU 淘汰最弱）。 */
    val recordCap: Int = 5000,
    /** 单次上屏最大长度，超过视为粘贴/整段，不学习。 */
    val maxCommitLen: Int = 16,
    /** 单次上屏最多处理的连续汉字段数。 */
    val maxRuns: Int = 8,
    /** 多段上屏（整句/粘贴）时，只学的「独立成段 2 字词」长度（v1.0.69 兼容通道）。 */
    val wordLen: Int = 2,
    /**
     * 单次上屏为「单一连续汉字段」时，允许把整段当成一个完整词学习的最大字数。
     * 覆盖「哔哩哔哩」等 4 字应用名 / 多字 OCR 捕获词；不做窗口切片，故不产生
     * 「安全爱情 -> 全爱」这类跨词垃圾（v1.0.68 修复 7 的初衷完整保留）。
     */
    val phraseLenMax: Int = 8,
    /** 键长度下限：2 排除单字母键（防 v1.0.65 的单字母 a 下 emoji 污染）。 */
    val keyLen: Int = 2,
    /** 键长度上限：容纳多字词首字母缩写键（blbl / qmqz ...，native exactMatchSearch 精确匹配）。 */
    val keyLenMax: Int = 12,
    /** 选词后在此窗口内改选别的词，视为疑似误选。 */
    val misclickMs: Long = 1500L,
    val enabled: Boolean = true,
)

/** 软层统计记录。key 为 "$key=>$word"。 */
data class ChoiceRecord(
    val n: Int = 0,
    val lastAt: Long = 0L,
    val prevWord: String = "",
    val streak: Int = 0,
    val penalty: Double = 0.0,
)

/** 输出到 fcitx5 自定义词组的硬插入条目：order=1 即第一候选。 */
data class BoostEntry(val key: String, val order: Int, val word: String)

/** 可持久化的完整状态快照（供 UserChoiceStore 存 SP / 未来存 Room）。 */
data class ChoiceState(
    val stacks: Map<String, List<String>>,
    val records: Map<String, ChoiceRecord>,
    val samples: Map<String, Int>,
    val lastWord: String,
)

class ChoiceRanker(
    private val config: ChoiceConfig = ChoiceConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** key -> 有序栈，index 0 = 冠军(order=1)。 */
    private val stacks = LinkedHashMap<String, MutableList<String>>()

    /** "$key=>$word" -> 软层统计。 */
    private val records = LinkedHashMap<String, ChoiceRecord>()

    /** key -> 有效(去抖后)选择样本数，用于 β。 */
    private val samples = HashMap<String, Int>()

    /** 上一条上屏词（bigram 上下文）。 */
    private var lastWord: String = ""

    private val hanziRun = Regex("[\\u4e00-\\u9fff]{2,}")

    /** 软层理论最大分，用于把 soft 归一化到与硬层栈序分同一量级。 */
    private val softMax: Double =
        (1.0 + config.streakW * config.streakCap) * (1.0 + config.ctxW)

    // ---------------------------------------------------------------- 公开 API

    /**
     * 记录一次「用户主动上屏」。返回受影响的键集合（调用方据此去抖落盘）。
     *
     * @param text        本次上屏文本
     * @param resolveKeys 词 -> 拼音/按键序列集合（生产用 AppPhraseImporter.keysForName）
     * @param isSensitive 敏感串判定（卡号/身份证/验证码）
     * @param privacyMode 隐私模式（密码框）下完全不学习
     */
    fun onCommit(
        text: String,
        resolveKeys: (String) -> Collection<String>,
        isSensitive: (String) -> Boolean = { false },
        privacyMode: Boolean = false,
    ): Set<String> {
        if (!config.enabled || privacyMode) return emptySet()
        if (isSensitive(text)) return emptySet()
        if (text.length > config.maxCommitLen) return emptySet()
        val runs = hanziRun.findAll(text).map { it.value }.toList()
        if (runs.isEmpty() || runs.size > config.maxRuns) return emptySet()
        // v1.0.70 词提取：
        //  - 单次上屏是「单一连续汉字段」且长度 <= phraseLenMax ⇒ 整段视为用户从候选栏
        //    主动选中的一个完整词：2 字词「安全」、4 字应用名「哔哩哔哩」、多字 OCR 词皆然。
        //    绝不切窗口，杜绝「安全爱情 -> 全爱」跨词垃圾（v1.0.68 修复 7 完整保留）。
        //  - 多段上屏（整句/粘贴）⇒ 退化为只学其中独立成段的 2 字词，保持 v1.0.69 兼容。
        //  - 超长仍由 maxCommitLen / maxRuns 拦截；敏感串 / 隐私模式在更前面已过滤。
        val words: List<String> = if (runs.size == 1 && runs[0].length in 2..config.phraseLenMax) {
            listOf(runs[0])
        } else {
            runs.filter { it.length == config.wordLen }.distinct()
        }
        if (words.isEmpty()) return emptySet()

        val now = clock()
        val dirty = LinkedHashSet<String>()
        val ctxAtStart = lastWord
        for (w in words) {
            for (key in resolveKeys(w)) {
                if (!validKey(key)) continue
                observe(key, w, now, ctxAtStart)
                dirty += key
            }
        }
        lastWord = words.last()
        trimRecords(now)
        return dirty
    }

    /**
     * 疑似误选：选中 word 后短时间内又删除/改写。施加软层惩罚，
     * 使其次位排序下沉并更快衰减退役；冠军位不受影响（用户刚选它仍应第一，
     * 若立刻改选别的词，硬层自然会覆盖）。
     */
    fun onMisclick(key: String, word: String) {
        val id = entryId(key, word)
        val r = records[id] ?: return
        records[id] = r.copy(penalty = r.penalty + config.misclickPenalty)
    }

    /**
     * 生成该键要写入 fcitx5 自定义词组的硬插入条目。
     * 空历史 => 空列表（冷启动：完全交还 libime 原生排序）。
     *
     * @param isPinned 手动置顶判定；冠军若已置顶则整个键让位（手动 > 自动）。
     */
    fun buildOrder(
        key: String,
        isPinned: (String, String) -> Boolean = { _, _ -> false },
        now: Long = clock(),
    ): List<BoostEntry> {
        val stack = stacks[key] ?: return emptyList()
        if (stack.isEmpty()) return emptyList()
        val champion = stack[0]
        // 手动置顶优先级高于自动学习：冠军已被置顶时本模块整体让位，
        // 避免同时写 order=1 的两行造成 native 插入位不确定。
        if (isPinned(key, champion)) return emptyList()

        val tail = orderedTail(key, stack.drop(1), now)
        val live = (listOf(champion) + tail).take(config.topK)
        return live
            .filterNot { isPinned(key, it) }
            .mapIndexed { i, w -> BoostEntry(key, i + 1, w) }
    }

    /** 疑似误选的便捷触发：同一键在短时间内改选了别的词。 */
    fun onQuickSwitch(key: String, previousWord: String, now: Long = clock()) {
        val r = records[entryId(key, previousWord)] ?: return
        if (now - r.lastAt in 0..config.misclickMs) onMisclick(key, previousWord)
    }

    fun clearKey(key: String) {
        stacks.remove(key)
        samples.remove(key)
        records.keys.removeAll { it.startsWith("$key=>") }
    }

    fun clearAll() {
        stacks.clear()
        records.clear()
        samples.clear()
        lastWord = ""
    }

    fun recordCount(): Int = records.size
    fun stackSize(key: String): Int = stacks[key]?.size ?: 0
    fun sampleCount(key: String): Int = samples[key] ?: 0
    fun hasHistory(key: String): Boolean = stacks[key]?.isNotEmpty() == true
    fun beta(key: String): Double =
        min(1.0, (samples[key] ?: 0).toDouble() / config.warmupSamples.coerceAtLeast(1))

    /** 软层评分（公开便于单测直接断言）。分数相同由调用方按稳定序处理。 */
    fun score(rec: ChoiceRecord?, now: Long, ctxWord: String = lastWord): Double {
        if (rec == null) return 0.0
        val dt = max(0L, now - rec.lastAt)
        val decay = 0.5.pow(dt.toDouble() / config.halfLifeMs.coerceAtLeast(1))
        // 次数对数平滑并 **封顶到 1.0**：n 超过 nCap 后不再增长，
        // 否则 ln(1+n)/ln(1+nCap) > 1 会让历史热门词无限累加、重新造成垄断。
        val freq = min(1.0, ln(1.0 + rec.n) / ln(1.0 + config.nCap.coerceAtLeast(1)))
        val streakB = 1.0 + config.streakW * min(rec.streak, config.streakCap)
        val ctxB = 1.0 + config.ctxW *
            (if (rec.prevWord.isNotEmpty() && rec.prevWord == ctxWord) 1.0 else 0.0)
        return max(0.0, freq * decay * streakB * ctxB - rec.penalty)
    }

    fun exportState(): ChoiceState = ChoiceState(
        stacks = stacks.mapValues { (_, v) -> v.toList() },
        records = records.toMap(),
        samples = samples.toMap(),
        lastWord = lastWord,
    )

    fun importState(state: ChoiceState) {
        clearAll()
        state.stacks.forEach { (k, v) -> if (validKey(k)) stacks[k] = v.toMutableList() }
        state.records.forEach { (k, v) -> records[k] = v }
        state.samples.forEach { (k, v) -> if (validKey(k)) samples[k] = v }
        lastWord = state.lastWord
    }

    // ---------------------------------------------------------------- 内部实现

    private fun validKey(key: String): Boolean =
        key.length in config.keyLen..config.keyLenMax && key.all { it in 'a'..'z' }

    private fun entryId(key: String, word: String) = "$key=>$word"

    /** 硬层 last-commit-wins + 软层次位重排。O(K)。 */
    private fun observe(key: String, word: String, now: Long, ctxAtStart: String) {
        val id = entryId(key, word)
        val prev = records[id]
        // 短时间重复上屏：只刷新时间，不累加次数（防连点刷爆 count）。
        // 用显式判空分支让 prev 智能转换为非空，避免多余 !!
        val rec: ChoiceRecord
        if (prev != null && (now - prev.lastAt) in 0 until config.dedupMs) {
            rec = prev.copy(lastAt = now)
        } else {
            rec = ChoiceRecord(
                n = (prev?.n ?: 0) + 1,
                lastAt = now,
                prevWord = ctxAtStart,
                streak = if (word == ctxAtStart) {
                    min((prev?.streak ?: 0) + 1, config.streakCap)
                } else 0,
                penalty = prev?.penalty ?: 0.0,
            )
            // 只有「非去抖」的有效选择才计入样本与次数（β 与 freq 用）
            samples[key] = (samples[key] ?: 0) + 1
        }
        records[id] = rec

        val stack = stacks.getOrPut(key) { mutableListOf() }
        // 注意：**不**在此处做「同键短时改选 => 旧冠军误选」的自动推断。
        // 用户核心示例是「aq 选安全」紧接着「aq 选爱情」，要求爱情第1、安全第2；
        // 把这种正常连续选词当作误选会踢掉「安全」，直接违反硬规则。
        // 误选的可靠信号是「上屏后立即按退格删除」，需由 IME 侧通过
        // onMisclick / onQuickSwitch 显式喂入（见设计文档「风险与优化建议」）。
        stack.remove(word)          // 若已在栈内，先移除
        stack.add(0, word)          // 无条件成为冠军（硬层，无分数比较）

        if (stack.size > 1) {
            val tail = orderedTail(key, stack.drop(1), now)
            stack.clear()
            stack.add(word)
            stack.addAll(tail.take(config.topK - 1))
        }
    }

    private data class TailItem(
        val idx: Int,
        val word: String,
        val soft: Double,
        val blended: Double,
        val lastAt: Long,
    )

    /**
     * 次位排序：过滤衰减退役项，再按「冷启动插值分」降序、稳定排序。
     * 同分 -> lastAt 降序 -> 原栈序（最近优先），保证结果可重复。
     */
    private fun orderedTail(key: String, candidates: List<String>, now: Long): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val b = beta(key)
        val items = candidates.mapIndexed { idx, w ->
            val r = records[entryId(key, w)]
            val soft = score(r, now)
            val hard = 1.0 - idx.toDouble() / config.topK.coerceAtLeast(1)
            TailItem(
                idx = idx,
                word = w,
                soft = soft,
                // 冷启动平滑：β 小 => 硬层栈序(最近优先)主导；β→1 => 软层主导
                blended = (1.0 - b) * hard + b * (soft / softMax),
                lastAt = r?.lastAt ?: 0L,
            )
        }
        return items
            .filter { it.soft >= config.minScore }   // 时间衰减退役（冠军不在此列）
            .sortedWith(
                compareByDescending<TailItem> { it.blended }
                    .thenByDescending { it.lastAt }
                    .thenBy { it.idx }
            )
            .map { it.word }
    }

    /** 容量护栏：超出上限淘汰最软记录，但保留仍在栈内的词（否则其 score 会归零）。 */
    private fun trimRecords(now: Long) {
        if (records.size <= config.recordCap) return
        val protectedIds = stacks.entries.flatMap { (k, words) -> words.map { entryId(k, it) } }.toSet()
        val weakest = records.entries
            .filterNot { it.key in protectedIds }
            .sortedBy { score(it.value, now) }
            .take(records.size - config.recordCap)
            .map { it.key }
        weakest.forEach { records.remove(it) }
    }
}
