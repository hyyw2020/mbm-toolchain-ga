// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

package org.fcitx.fcitx5.android.lib.handwritten

import android.content.Context
import java.io.BufferedReader

/**
 * 手写候选增强词库（MBM v0.0.02，对齐主流半屏手写布局）：
 *
 *  - 拼音：识别出的单字 → 普通话拼音（无声调，ü→v），显示在候选条下方；
 *  - 关联词：识别出的首字 → 常用词（如「岁」→ 岁月/岁数），追加在单字候选之后。
 *
 * 数据由 tools/gen_handwriting_lexicon.py 离线生成（jieba 词频 / pypinyin 短语），
 * 以 TSV 形式随 assets 打包（lib 模块 assets 合并进主 app）：
 *   handwriting_pinyin.tsv : 每行 "字\t拼音"
 *   handwriting_words.tsv  : 每行 "字\t词1 词2 ..."（每字最多 4 个 2~4 字词）
 *
 * 加载约 300KB 文本，首次调用在后台协程完成（识别本身已在后台线程）。
 */
class HandwritingLexicon private constructor(
    private val pinyin: Map<Char, String>,
    private val words: Map<Char, List<String>>
) {

    fun pinyinOf(char: Char): String? = pinyin[char]

    fun wordsOf(char: Char): List<String> = words[char] ?: emptyList()

    companion object {
        private const val PINYIN_ASSET = "handwriting_pinyin.tsv"
        private const val WORDS_ASSET = "handwriting_words.tsv"

        @Volatile
        private var instance: HandwritingLexicon? = null

        /** 进程内单例；首次调用解析 assets（约几十毫秒），请在后台线程调用。 */
        fun get(context: Context): HandwritingLexicon =
            instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }

        private fun load(context: Context): HandwritingLexicon {
            val pinyin = HashMap<Char, String>(4200)
            val words = HashMap<Char, List<String>>(3900)
            runCatching {
                context.assets.open(PINYIN_ASSET).bufferedReader().use { it.readLines() }
            }.onSuccess { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0 && tab + 1 < line.length) {
                        pinyin[line[0]] = line.substring(tab + 1)
                    }
                }
            }
            runCatching {
                context.assets.open(WORDS_ASSET).bufferedReader().use { it.readLines() }
            }.onSuccess { lines ->
                lines.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0 && tab + 1 < line.length) {
                        words[line[0]] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
                    }
                }
            }
            return HandwritingLexicon(pinyin, words)
        }

        /** 仅供单元测试/调试：重置单例（词库文件更新后）。 */
        internal fun resetForTest() {
            instance = null
        }
    }
}
