/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 上下文「下一词」预测 —— 纯算法层（零 Android 依赖，可直接跑 JVM 单测）。
 *
 * v1.0.72 补的是「预判」而非「排序」：ChoiceRanker 只回答「用户选过什么，下次
 * 怎么排」，本类回答「用户还没选、但根据刚上屏的词，下一个大概率想打什么」。
 *
 * 数据底座复用 SmartDictManager 的本地 bigram/trigram（运行时从用户上屏学习，
 * 纯本机、不预置任何训练数据、不联网）。本类通过回调注入数据源，便于单测：
 *  - bigram(prev, limit)  -> [(后继词, 共现计数)]
 *  - trigram(a, b, limit) -> [(第三词, 共现计数)]
 *  - keysFor(word)        -> 该词可输入的拼音键集合（首字母 + 全拼）
 *  - hasUserHistory(key)  -> 该键下用户是否已有硬层冠军（有则让位，绝不覆盖）
 *  - hasUserWord(word)    -> 该词是否已被用户主动选过（跨键让位）
 *
 * 设计约束（与 v1.0.66~71 全部防御一致）：
 *  1. 只输出 order=1 的「预测冠军」，且**仅在用户从未主动选过该键时**；
 *     一旦用户选过，硬层 last-commit-wins 接管，预测立即让位。
 *     这不违反「选择 >= 1 次即第一」，反而补上了「没选过但能猜到」的空白。
 *  2. 置信门槛：共现计数 >= minConfidence，过滤一次性噪声。
 *  3. 每键最多 1 个预测词，防止同键多行 order=1 造成 native 插入位不确定。
 *  4. 只接受 2..8 字纯汉字段；键 2..24 位纯小写字母；不做窗口切片、不拼接，
 *     杜绝 v1.0.63「安全爱情 -> 全爱」式跨词垃圾与 v1.0.65 单字母 emoji 污染。
 *  5. 全局条数上限 + 稳定排序（置信降序 -> 词字典序），结果可重复、可回归。
 *  6. 预测是「最近一次上屏的下一步」快照，由调用方每次上屏全量替换托管行，
 *     因此不会长期堆积、不会永久占据首位。
 *
 * 本类不含 IO、不含 Android API、不含协程；时间无关，无需注入时钟。
 */
package org.fcitx.fcitx5.android.recall.rank

/** 可注入参数（默认值由 RecallConfig 提供；单测可自行构造）。 */
data class PredictionConfig(
    /** 单次最多输出的预测词条数（一个词可挂多个键，行数上限另见 maxEntries）。 */
    val maxWords: Int = 12,
    /** 单次最多写进词组的行数上限（键膨胀护栏）。 */
    val maxEntries: Int = 48,
    /** bigram/trigram 共现计数门槛。 */
    val minConfidence: Int = 2,
    /** 每个上下文种子取多少个后继。 */
    val perSeedLimit: Int = 3,
    val wordLenMin: Int = 2,
    val wordLenMax: Int = 8,
    val keyLenMin: Int = 2,
    /** 全拼键（如 anquan / bilibilli）比首字母缩写长，故上限放宽到 24。 */
    val keyLenMax: Int = 24,
)

class ContextPredictor(
    private val config: PredictionConfig = PredictionConfig(),
) {

    /**
     * 生成预测冠军条目。
     *
     * @param context 最近上屏词，末位为最新（通常取 2 个）
     * @param bigram  (前词, limit) -> [(后继词, 共现计数)]
     * @param trigram ((前二词 a, b, limit) -> [(第三词, 共现计数)])，可为 null
     * @param keysFor 词 -> 可输入拼音键集合（生产用 PhraseKeys.forWord）
     * @param hasUserHistory 键 -> 用户是否已有硬层冠军
     * @param hasUserWord 词 -> 用户是否已主动选过（跨键让位）
     * @param isPinned (键, 词) -> 是否被手动置顶
     */
    fun predict(
        context: List<String>,
        bigram: (String, Int) -> List<Pair<String, Int>>,
        trigram: ((String, String, Int) -> List<Pair<String, Int>>)? = null,
        keysFor: (String) -> Set<String>,
        hasUserHistory: (String) -> Boolean,
        hasUserWord: (String) -> Boolean = { false },
        isPinned: (String, String) -> Boolean = { _, _ -> false },
    ): List<BoostEntry> {
        if (context.isEmpty()) return emptyList()

        // 1) 收集候选：(词, 置信度)。trigram 命中给额外加成，因为它有更长上下文，
        //    比单步 bigram 更能代表「刚打完前两个词」的真实意图。
        val scored = LinkedHashMap<String, Int>()
        // v1.0.85 真 bug：trigram 命中词单独记一层。原实现只在「同一个词」的
        // 分数上 +1（maxOf(...)+1），当 trigram 词与 bigram 词不同、且 trigram
        // 三词共现计数天然低于 bigram 双词计数时，+1 无法让 trigram 词排在
        // bigram 词之前 —— 设计文档「trigram 优先级 / 保证排在单步之前」失效。
        // 修正为分层：任何 trigram 命中词的优先级都高于纯 bigram 词，
        // 层内仍按置信度降序 → 词字典序稳定排序（不改变原有让位/过滤/容量规则）。
        val trigramHits = HashSet<String>()
        val seeds = context.takeLast(2)
        for (seed in seeds) {
            for ((w, c) in bigram(seed, config.perSeedLimit)) {
                if (c < config.minConfidence) continue
                scored[w] = maxOf(scored[w] ?: 0, c)
            }
        }
        if (seeds.size == 2 && trigram != null) {
            for ((w, c) in trigram(seeds[0], seeds[1], config.perSeedLimit)) {
                if (c < config.minConfidence) continue
                // 三词窗口命中即视为高置信：标记为 trigram 层，保证排在纯单步 bigram 之前。
                trigramHits.add(w)
                scored[w] = maxOf(scored[w] ?: 0, c) + 1
            }
        }
        if (scored.isEmpty()) return emptyList()

        // 2) 稳定排序：trigram 层优先 → 置信降序 → 词字典序，保证输出可重复。
        val ordered = scored.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.key in trigramHits }
                    .thenByDescending { it.value }
                    .thenBy { it.key }
            )

        // 3) 过滤 + 生成。每键只允许一个预测词；任一键已有用户硬层则整个词让位。
        val claimedKeys = HashSet<String>()
        val entries = ArrayList<BoostEntry>()
        var wordCount = 0
        for ((word, _) in ordered) {
            if (wordCount >= config.maxWords || entries.size >= config.maxEntries) break
            if (word.length !in config.wordLenMin..config.wordLenMax) continue
            if (!word.all { it in '\u4e00'..'\u9fff' }) continue
            // 用户已经主动选过这个词 ⇒ 硬层记录更可信，不再用预测覆盖。
            if (hasUserWord(word)) continue

            val keys = keysFor(word)
                .filter { validKey(it) }
                .distinct()
                .sorted()
            if (keys.isEmpty()) continue
            // 只要有一个键存在用户硬层历史，就整体让位：避免同一词在部分键下
            // 被预测压过用户选择，造成「全拼下第一、缩写下第二」的割裂体验。
            if (keys.any { hasUserHistory(it) }) continue
            if (keys.any { claimedKeys.contains(it) }) continue

            val batch = ArrayList<BoostEntry>(keys.size)
            var blocked = false
            for (k in keys) {
                if (isPinned(k, word)) { blocked = true; break }
                batch += BoostEntry(k, 1, word)
            }
            if (blocked || batch.isEmpty()) continue
            if (entries.size + batch.size > config.maxEntries) continue

            entries.addAll(batch)
            claimedKeys.addAll(keys)
            wordCount++
        }
        return entries
    }

    private fun validKey(key: String): Boolean =
        key.length in config.keyLenMin..config.keyLenMax && key.all { it in 'a'..'z' }
}
