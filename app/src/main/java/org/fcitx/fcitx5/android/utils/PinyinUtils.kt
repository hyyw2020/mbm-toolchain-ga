/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM: 汉字→拼音工具。基于 Android 内置 ICU 的 Transliterator（Han-Latin; Latin-ASCII），
 * 完全离线、零新依赖。用于：
 *  - Recall 捕获文本导出为自定义词组时生成拼音首字母键（"玩机技巧" → "wjjq"）；
 *  - 管理自定义词组的搜索（输入 wjjq 也能命中"玩机技巧"）。
 *
 * 多音字校正（v1.0.17 新增）：
 *  ICU 的 Han-Latin 对多音字只会给出单字默认读音（如「乐」一律读 le），在「音乐 / 银行」等
 *  词里就错了，导致生成的首字母键完全不对（"华为音乐" 被识别成 hwyl）。这里在 ICU 之上叠加一层
 *  **词级校正**：用常用多音字词的正确整词拼音做最长匹配覆盖，未覆盖的词仍走 ICU 默认读音。
 *  该层离线、零依赖，不引入任何新库。
 */
package org.fcitx.fcitx5.android.utils

import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

object PinyinUtils {

    private val hanToLatin by lazy {
        runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
    }

    // 注意：ConcurrentHashMap 不允许 null 的 key/value。toPinyin/fullPinyin/initials 都可能返回 null
    // （如纯数字 / 符号 / emoji 经 ICU 转写后无字母），因此用 PinyinBox 包裹以同时缓存「成功结果」与
    // 「无结果」，避免向 ConcurrentHashMap 塞 null 触发 NullPointerException（见崩溃日志 NPE@PinyinUtils.kt:125）。
    private class PinyinBox(val value: String?)

    private val pinyinCache = ConcurrentHashMap<String, PinyinBox>()
    private val initialsCache = ConcurrentHashMap<String, PinyinBox>()

    /**
     * 多音字词级校正表：词 -> 正确整词拼音（音节空格分隔，音节数 == 字数）。
     * 仅覆盖高频多音字组合；未覆盖的词仍走 ICU 默认读音。
     */
    private val wordPinyin: Map<String, String> = mapOf(
        // 乐 yuè
        "音乐" to "yin yue", "乐器" to "yue qi", "乐队" to "yue dui",
        "乐曲" to "yue qu", "乐谱" to "yue pu", "声乐" to "sheng yue",
        "乐团" to "yue tuan", "乐章" to "yue zhang", "乐手" to "yue shou", "乐理" to "yue li",
        // 乐 lè
        "快乐" to "kuai le", "欢乐" to "huan le", "乐园" to "le yuan",
        "乐观" to "le guan", "乐意" to "le yi", "乐趣" to "le qu",
        // 行 háng
        "银行" to "yin hang", "行业" to "hang ye", "行情" to "hang qing",
        "行列" to "hang lie", "行长" to "hang zhang",
        // 行 xíng
        "行走" to "xing zou", "行人" to "xing ren", "行动" to "xing dong",
        "行为" to "xing wei", "自行车" to "zi xing che", "品行" to "pin xing",
        // 重 zhòng
        "重量" to "zhong liang", "重要" to "zhong yao", "重点" to "zhong dian", "重心" to "zhong xin",
        // 重 chóng
        "重新" to "chong xin", "重复" to "chong fu", "重叠" to "chong die", "重阳" to "chong yang",
        // 长 cháng
        "长江" to "chang jiang", "长短" to "chang duan", "长度" to "chang du",
        "长期" to "chang qi", "长城" to "chang cheng",
        // 长 zhǎng
        "长大" to "zhang da", "家长" to "jia zhang", "生长" to "sheng zhang", "首长" to "shou zhang",
        // 发 fā
        "发现" to "fa xian", "发展" to "fa zhan", "发明" to "fa ming", "发生" to "fa sheng", "出发" to "chu fa",
        // 发 fà
        "头发" to "tou fa", "理发" to "li fa",
        // 都 dū
        "首都" to "shou du", "都市" to "du shi",
        // 当 dāng
        "当然" to "dang ran", "当时" to "dang shi",
        // 当 dàng
        "上当" to "shang dang", "当铺" to "dang pu",
        // 好 hǎo
        "好玩" to "hao wan", "好人" to "hao ren", "好事" to "hao shi",
        // 好 hào
        "好奇" to "hao qi", "爱好" to "ai hao", "好学" to "hao xue",
        // 还 hái
        "还是" to "hai shi", "还有" to "hai you",
        // 还 huán
        "归还" to "gui huan",
        // 只 zhǐ
        "只有" to "zhi you", "只要" to "zhi yao", "只是" to "zhi shi",
        // 一只
        "一只" to "yi zhi",
        // 着 zhe
        "看着" to "kan zhe",
        // 着 zháo
        "着急" to "zhao ji", "着火" to "zhao huo",
        // 应 yīng
        "应该" to "ying gai", "应当" to "ying dang",
        // 应 yìng
        "答应" to "da ying", "响应" to "xiang ying",
        // 场 chǎng
        "广场" to "guang chang", "场面" to "chang mian",
        // 一场
        "一场" to "yi chang",
        // 得 dé
        "得到" to "de dao", "觉得" to "jue de",
        // 地 dì
        "土地" to "tu di", "地方" to "di fang",
        // 银行（确保单字不成词时也能命中）
        "一行" to "yi hang",
    )

    private val MAX_WORD_LEN = 4

    /** 是否全部为汉字（含扩展区之外的常用区间） */
    fun isAllChinese(text: String): Boolean =
        text.isNotEmpty() && text.all { it in '\u4E00'..'\u9FFF' }

    /**
     * 汉字转全拼小写："玩机技巧" → "wan ji ji qiao"。
     * 含非汉字字符时同样尽力转换；失败返回 null。
     * 多音字经 [wordPinyin] 词级校正（如"音乐"→yin yue）。
     */
    fun toPinyin(text: String): String? {
        if (text.isBlank()) return null
        pinyinCache[text]?.let { return it.value }
        val transformer = hanToLatin ?: return null.also { pinyinCache[text] = PinyinBox(null) }
        val base = runCatching { transformer.transliterate(text) }
            .getOrNull()
            ?.lowercase()
            ?.trim()
            ?.takeIf { it.any { c -> c.isLetter() } }
        // 文本中无多音字词时直接返回 ICU 结果（保持原有行为，零额外开销）
        val result = if (base != null && wordPinyin.keys.any { text.contains(it) }) {
            rebuildWithWordMap(text)
        } else {
            base
        }
        pinyinCache[text] = PinyinBox(result)
        return result
    }

    /**
     * 用「词表贪心最长匹配 + 非命中字符回退 ICU 单字」重建拼音，
     * 不依赖 ICU 输出的音节空格/对齐格式，因此对 ICU 各种输出都稳健。
     */
    private fun rebuildWithWordMap(text: String): String {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var matched: String? = null
            for (len in MAX_WORD_LEN.coerceAtMost(text.length - i) downTo 2) {
                val sub = text.substring(i, i + len)
                if (wordPinyin.containsKey(sub)) {
                    matched = sub
                    break
                }
            }
            if (matched != null) {
                out += wordPinyin[matched]!!.split(' ').filter { it.isNotEmpty() }
                i += matched.length
            } else {
                // 单字符走 ICU（保留其默认读音/转写）
                val c = text.substring(i, i + 1)
                val p = runCatching { hanToLatin?.transliterate(c) }
                    .getOrNull()
                    ?.lowercase()
                    ?.trim()
                    ?.takeIf { it.any { ch -> ch.isLetter() } } ?: c
                out += p
                i += 1
            }
        }
        return out.joinToString(" ")
    }

    /** 全拼去空格："玩机技巧" → "wanjijiqiao"（用于自定义词组的全拼键） */
    fun fullPinyin(text: String): String? =
        toPinyin(text)?.filter { it.isLetter() }?.takeIf { it.isNotEmpty() }

    /**
     * 拼音首字母："玩机技巧" → "wjjq"。取每个音节的首字母；
     * 转换失败或无字母结果返回 null。
     */
    fun initials(text: String): String? {
        if (text.isBlank()) return null
        initialsCache[text]?.let { return it.value }
        val py = toPinyin(text) ?: run {
            initialsCache[text] = PinyinBox(null)
            return null
        }
        val sb = StringBuilder()
        var atSyllableStart = true
        for (c in py) {
            if (c.isLetter()) {
                if (atSyllableStart) {
                    sb.append(c)
                    atSyllableStart = false
                }
            } else {
                atSyllableStart = true
            }
        }
        val result = sb.toString().takeIf { it.isNotEmpty() }
        initialsCache[text] = PinyinBox(result)
        return result
    }
}
