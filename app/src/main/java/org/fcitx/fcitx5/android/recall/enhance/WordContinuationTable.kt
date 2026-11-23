/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Cyletix
 *
 * 移植自 CyIME（https://github.com/Cyletix/CyIME），原路径
 * app/src/main/java/com/kingzcheung/xime/association/WordContinuationTable.kt。
 * 依 GPLv3 保留原版权与许可；来源说明见仓库 THIRD_PARTY_NOTICES.md 与 LICENSE 附录。
 */
package org.fcitx.fcitx5.android.recall.enhance

import java.io.Reader

/**
 * 整词后续接续表。常规联想表仅识别一至两个字符的上文，四字以上词只能按末字联想；
 * 本表记录「长词由另一词开头」时的接续关系（例如「厄尔尼诺现象」记录为「厄尔尼诺 → 现象」）。
 * 分数为条件概率：长词词频 ÷（起始词自身词频 + 所有以该词开头的长词词频）。
 *
 * 数据文件格式由构建阶段从词库生成：gzip 文本，首行为文件头，其后每行
 * `上文\t接续\t词频\t总数`；上文为 2–4 个汉字，按字符串顺序升序排列，同一上文的各行按词频降序，
 * 每个上文最多 [MAX_PER_CONTEXT] 行。
 */
class WordContinuationTable private constructor(
    private val contextText: CharArray,
    private val contextStart: IntArray,
    private val rowStart: IntArray,
    private val text: CharArray,
    private val textStart: IntArray,
    private val scores: FloatArray,
) {
    /** 匹配结果。[contextLength] 为匹配词覆盖的上文末尾字符数。 */
    class Match(val contextLength: Int, val candidates: List<AssociationCandidate>)

    val contextCount: Int get() = contextStart.size - 1

    /**
     * 查询上文结束后最可能接续的内容。从最长到最短依次尝试上文末尾的 2–4 字词，
     * 返回首个命中行；候选按分数降序，最多 [topK] 项。
     */
    fun lookup(context: String, topK: Int = MAX_PER_CONTEXT): Match? {
        if (topK <= 0) return null
        val tail = context.takeLastWhile { it in '一'..'鿿' }.takeLast(MAX_CONTEXT_LENGTH)
        for (size in tail.length downTo MIN_CONTEXT_LENGTH) {
            val row = find(tail, tail.length - size)
            if (row < 0) continue
            val first = rowStart[row]
            return Match(size, List(minOf(topK, rowStart[row + 1] - first)) { index ->
                val candidate = first + index
                AssociationCandidate(
                    String(text, textStart[candidate], textStart[candidate + 1] - textStart[candidate]),
                    scores[candidate],
                )
            })
        }
        return null
    }

    /** 在以字符串顺序排列的上文中二分查找，返回等于 tail.substring(from) 的行号，未命中返回 -1。 */
    private fun find(tail: String, from: Int): Int {
        var low = 0
        var high = contextCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compare(middle, tail, from)
            if (order < 0) low = middle + 1 else if (order > 0) high = middle - 1 else return middle
        }
        return -1
    }

    /** 比较第 [row] 行上文与 tail.substring(from)：按 UTF-16 单元逐位比较，相等时以长度区分。 */
    private fun compare(row: Int, tail: String, from: Int): Int {
        val start = contextStart[row]
        val length = contextStart[row + 1] - start
        val other = tail.length - from
        for (index in 0 until minOf(length, other)) {
            val difference = contextText[start + index].code - tail[from + index].code
            if (difference != 0) return difference
        }
        return length - other
    }

    companion object {
        const val ASSET = "association/dict-words-v1.gz"
        const val HEADER = "# cyime-dict-word-continuations-v1"
        const val MIN_CONTEXT_LENGTH = 2
        const val MAX_CONTEXT_LENGTH = 4
        const val MAX_PER_CONTEXT = 4

        /** 校验并加载表数据；格式错误时抛出异常并附带具体行信息。 */
        fun read(reader: Reader): WordContinuationTable {
            val contextText = StringBuilder()
            val text = StringBuilder()
            var contextStart = IntArray(1024)
            var rowStart = IntArray(1024)
            var textStart = IntArray(4096)
            var scores = FloatArray(4096)
            var contexts = 0
            var candidates = 0
            var last = ""
            var inRow = 0
            var lastCount = 0L
            reader.buffered().useLines { lines ->
                val iterator = lines.iterator()
                require(iterator.hasNext() && iterator.next().startsWith(HEADER)) { "整词联想表文件头不正确" }
                iterator.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val fields = line.split('\t')
                    require(fields.size == 4) { "整词联想表该行不是四列：$line" }
                    val context = fields[0]
                    val count = fields[2].toLong()
                    val total = fields[3].toLong()
                    require(
                        context.length in MIN_CONTEXT_LENGTH..MAX_CONTEXT_LENGTH &&
                            context.all { it in '一'..'鿿' } &&
                            fields[1].length in 1..6 && count > 0 && total >= count
                    ) { "整词联想表该行数据不正确：$line" }
                    if (context != last) {
                        require(context > last) { "整词联想表未按上文排序：$context" }
                        if (contexts + 1 >= contextStart.size) {
                            contextStart = contextStart.copyOf(contextStart.size * 2)
                            rowStart = rowStart.copyOf(rowStart.size * 2)
                        }
                        contextStart[contexts] = contextText.length
                        rowStart[contexts] = candidates
                        contextText.append(context)
                        contexts++
                        last = context
                        inRow = 0
                    } else {
                        require(count <= lastCount) { "整词联想表同一上文未按词频排序：$line" }
                    }
                    require(inRow < MAX_PER_CONTEXT) { "整词联想表一个上文超过 $MAX_PER_CONTEXT 行：$context" }
                    if (candidates + 1 >= textStart.size) {
                        textStart = textStart.copyOf(textStart.size * 2)
                        scores = scores.copyOf(scores.size * 2)
                    }
                    textStart[candidates] = text.length
                    text.append(fields[1])
                    scores[candidates] = count.toFloat() / total
                    candidates++
                    inRow++
                    lastCount = count
                }
            }
            contextStart[contexts] = contextText.length
            rowStart[contexts] = candidates
            textStart[candidates] = text.length
            fun chars(builder: StringBuilder) = CharArray(builder.length).also { builder.getChars(0, builder.length, it, 0) }
            return WordContinuationTable(
                chars(contextText), contextStart.copyOf(contexts + 1), rowStart.copyOf(contexts + 1),
                chars(text), textStart.copyOf(candidates + 1), scores.copyOf(candidates),
            )
        }
    }
}

/**
 * 将整词接续合并进联想分数表 [scores]（候选 → 分数）。仅当整词长度超过基础联想表识别的上文长度时才合并。
 * 两侧分数均为「上文后接该词」的概率，使用同一权重 [weight]，同一候选取较大值；合并后按常规分数排序。
 */
internal fun mergeWordContinuations(
    scores: MutableMap<String, Float>,
    baseContextLength: Int,
    words: WordContinuationTable.Match?,
    weight: Float,
) {
    if (words == null || words.contextLength <= baseContextLength) return
    for (candidate in words.candidates) {
        val score = weight * candidate.score
        if (score > (scores[candidate.text] ?: 0f)) scores[candidate.text] = score
    }
}
