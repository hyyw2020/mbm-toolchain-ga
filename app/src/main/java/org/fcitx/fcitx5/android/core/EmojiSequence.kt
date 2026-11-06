/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Unicode Emoji 序列判据（纯 Kotlin，零 Android 依赖，可在 JVM 单测中验证）。
 *
 * 术语依据：The Unicode Standard / UTS #51 "Unicode Emoji" 与 Unicode CLDR
 * `RGI_Emoji` 集合。一个「表情」在文本通道里不是单个 Char，而是一个
 * extended grapheme cluster，可能由以下成分组成：
 *   - 基础码点（Extended_Pictographic / Emoji_Presentation 区段）
 *   - U+200D ZERO WIDTH JOINER 连接的多个基础码点（ZWJ sequences）
 *   - U+1F3FB..U+1F3FF Fitzpatrick 肤色修饰符（Emoji_Modifier）
 *   - U+FE0E / U+FE0F 变体选择符（VS15 / VS16）
 *   - U+20E3 COMBINING ENCLOSING KEYCAP（键帽序列，基础为 0-9 / # / *）
 *   - U+1F1E6..U+1F1FF Regional Indicator 成对（旗帜）
 *   - U+E0020..U+E007F TAG 字符序列（subdivision flags）
 *
 * 设计要点：判定「是否应展示/是否应原样提交」只依据 Unicode 码点结构，
 * **不依赖宿主设备字体**（Paint.hasGlyph）与 ICU 版本的 RGI 集合覆盖度。
 * 宿主字体缺字形属于渲染层问题，绝不能成为数据层裁剪表情的理由；
 * 否则新版序列（ZWJ 组合、Unicode 15/16 新增码点）会被静默丢弃。
 *
 * 本文件不引用任何第三方应用或专有资产；所有名称为 Unicode 标准、
 * CLDR 数据规范与 Java/Kotlin 标准库。
 */
package org.fcitx.fcitx5.android.core

object EmojiSequence {

    // ------------------------------------------------------------------ 码点成分

    const val ZWJ = 0x200D
    const val VS15 = 0xFE0E
    const val VS16 = 0xFE0F
    const val KEYCAP = 0x20E3
    const val TAG_CANCEL = 0xE007F

    /** Fitzpatrick 肤色修饰符（Emoji_Modifier）。 */
    fun isSkinToneModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    /** 变体选择符（含 VS15/VS16 与补充变体选择符区）。 */
    fun isVariationSelector(cp: Int): Boolean =
        cp == VS15 || cp == VS16 || cp in 0xE0100..0xE01EF

    fun isZwj(cp: Int): Boolean = cp == ZWJ

    /** Regional Indicator（旗帜由两个 RI 组成）。 */
    fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    /** TAG 字符序列（subdivision flags）。 */
    fun isTagCharacter(cp: Int): Boolean = cp in 0xE0020..0xE007F

    fun isKeycapCombining(cp: Int): Boolean = cp == KEYCAP

    /** 键帽序列允许的 ASCII 基础字符。 */
    fun isKeycapBase(cp: Int): Boolean =
        cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A

    /**
     * Extended_Pictographic / Emoji_Presentation 主区段。
     * 覆盖 BMP 内的杂项符号与 dingbats，以及 SMP 的 U+1F000..U+1FAFF、
     * U+1FC00..U+1FFFD（扩展符号），并包含若干独立 BMP emoji 码点。
     * 这是「结构判据」，不要求设备字体真的能画出字形。
     */
    fun isEmojiBase(cp: Int): Boolean = when {
        cp in 0x1F000..0x1FAFF -> true
        cp in 0x1FC00..0x1FFFD -> true
        cp in 0x2600..0x27BF -> true
        cp in 0x2190..0x21FF -> true     // 箭头（↔ 等带 Emoji_Presentation）
        cp in 0x2300..0x23FF -> true     // ⌚ ⏰ ⏩ 等
        cp in 0x25A0..0x25FF -> true     // 几何图形（▶ ✅ 等）
        cp in 0x2B00..0x2BFF -> true     // ⬛ ⭐ 等
        cp in 0x2930..0x293F -> true     // ⤴ ⤵
        cp in 0x3200..0x32FF -> true     // ㊗ ㊙ 等带圈 CJK
        cp in 0x1F000..0x1F0FF -> true   // 扑克牌 / 麻将（已由上段覆盖，显式保留可读性）
        // 独立 BMP emoji 码点
        cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 ||
            cp == 0x2122 || cp == 0x2139 || cp == 0x24C2 || cp == 0x3030 ||
            cp == 0x303D || cp == 0x3297 || cp == 0x3299 -> true
        else -> false
    }

    /** 汉字（基本区 + 扩展 A / B 及以上）与 ASCII 字母数字：表情序列中不允许出现。 */
    fun isHan(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp >= 0x20000

    fun isAsciiAlnum(cp: Int): Boolean =
        cp < 128 && (cp in '0'.code..'9'.code || cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code)

    // ------------------------------------------------------------------ 序列工具

    /** 按 Unicode code point 遍历（合并 UTF-16 代理对）。 */
    inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                i++
                Character.toCodePoint(c, text[i])
            } else {
                c.code
            }
            action(cp)
            i++
        }
    }

    fun codePoints(text: String): IntArray {
        val out = ArrayList<Int>(text.length)
        forEachCodePoint(text) { out.add(it) }
        return out.toIntArray()
    }

    /** 字符串中是否至少含一个表情基础码点。 */
    fun hasEmojiBase(text: String): Boolean {
        var found = false
        forEachCodePoint(text) { if (isEmojiBase(it)) found = true }
        return found
    }

    /**
     * 判断一个字符串是否为**完整合法**的 Unicode Emoji 序列。
     *
     * 允许：基础码点、RI 成对、TAG 序列、键帽序列、肤色修饰符、
     * 变体选择符、ZWJ、组合记号（如 U+FE0F 后的一般组合符）。
     * 拒绝：汉字、ASCII 字母数字（键帽基础除外且必须跟 U+20E3）、
     * 空白、以及任何未归入表情序列的普通文本。
     *
     * 该判据与宿主字体无关，是「默认支持全部表情」的数据层基础。
     */
    fun isEmojiSequence(text: String): Boolean {
        if (text.isEmpty()) return false
        val cps = codePoints(text)
        var i = 0
        var baseCount = 0
        var riRun = 0
        while (i < cps.size) {
            val cp = cps[i]
            when {
                isZwj(cp) -> {
                    // ZWJ 必须前后都有基础码点，不能出现在序列首尾
                    if (i == 0 || i + 1 >= cps.size) return false
                    i++
                }
                isVariationSelector(cp) || isSkinToneModifier(cp) ||
                    isTagCharacter(cp) || cp == TAG_CANCEL || isKeycapCombining(cp) -> {
                    i++
                }
                isRegionalIndicator(cp) -> {
                    riRun++
                    baseCount++
                    i++
                }
                isKeycapBase(cp) -> {
                    // 仅当后续紧跟 U+20E3 才算键帽序列，否则视为普通文本。
                    // 允许中间夹 VS16（RGI 键帽序列的规范写法为 "1 FE0F 20E3"）。
                    var j = i + 1
                    while (j < cps.size && isVariationSelector(cps[j])) j++
                    if (j >= cps.size || !isKeycapCombining(cps[j])) return false
                    baseCount++
                    i = j + 1
                }
                isEmojiBase(cp) -> {
                    baseCount++
                    i++
                }
                isHan(cp) || isAsciiAlnum(cp) || cp == 0x20 || cp == 0x0A || cp == 0x0D -> return false
                // 其它组合记号（如 U+0300 起）在已出现基础码点后允许
                cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF -> i++
                else -> return false
            }
        }
        if (baseCount == 0) return false
        // RI 必须成对出现（单个 RI 不是完整旗帜）；混合其它基础码点时不做此限制
        if (riRun > 0 && riRun % 2 != 0) return false
        return true
    }

    /**
     * 按 extended grapheme cluster 粗略切分：把基础码点连同其后所有
     * 连接/修饰成分归为同一簇。用于「整串复制的表情」原样显示与删除保护，
     * 避免 ZWJ 序列被从中间截断产生孤立代理项或残片。
     */
    fun graphemeClusters(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val cps = codePoints(text)
        val result = ArrayList<String>()
        val current = ArrayList<Int>()
        var pendingRi = false
        var afterZwj = false
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            val isJoiner = isZwj(cp)
            val isContinuation = isVariationSelector(cp) || isSkinToneModifier(cp) ||
                isTagCharacter(cp) || cp == TAG_CANCEL || isKeycapCombining(cp) ||
                cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF
            val isRi = isRegionalIndicator(cp)

            if (current.isNotEmpty() && !isJoiner && !isContinuation) {
                // ZWJ 后紧跟的基础码点必须与前段同簇；RI 成对合并；
                // 其余基础码点开启新簇。
                val shouldMerge = afterZwj || (isRi && pendingRi)
                if (!shouldMerge) {
                    result.add(current.toClusterString())
                    current.clear()
                }
            }
            current.add(cp)
            pendingRi = if (isRi) !pendingRi else false
            afterZwj = isJoiner
            i++
        }
        if (current.isNotEmpty()) result.add(current.toClusterString())
        return result
    }

    private fun List<Int>.toClusterString(): String = buildString {
        this@toClusterString.forEach { appendCodePoint(it) }
    }

    /**
     * 与 UTS #51 语义一致的「纯表情文本」判定：由一个或多个合法的
     * grapheme cluster 组成，且不含汉字、普通 ASCII 文本与空白。
     * 供候选分类、剪贴板 MIME 推断等复用。
     */
    fun isEmojiOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        val clusters = graphemeClusters(text)
        if (clusters.isEmpty()) return false
        return clusters.all { isEmojiSequence(it) }
    }

    /** 该字符串是否含需要按 grapheme cluster 原子处理的复合序列。 */
    fun isComposite(text: String): Boolean {
        var composite = false
        forEachCodePoint(text) {
            if (isZwj(it) || isRegionalIndicator(it) || isTagCharacter(it) ||
                isSkinToneModifier(it) || it == KEYCAP) composite = true
        }
        return composite
    }
}
