/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.65：候选栏尾部 emoji / 装饰候选抑制（行为级守卫）。
 *
 * 现象（用户录屏反馈）：在「搜索应用」等界面输入单字母 `a`，候选栏在「啊」之后
 * 一次冒出 4~5 个几乎相同的惊恐/惊讶脸（😨😧😮😲），挤占汉字候选；输入 `aq`
 * 时尾部也会挂一个 😍。这些不是 MBM 的 Recall / UserChoice 注入（两者只匹配汉字），
 * 而是 fcitx5 quickphrase 自带的 emoji 别名表（quickphrase.d/emoji-eac.mb 里
 * :fearful: / :anguished: / :open_mouth: / :astonished: 等条目在单字母前缀下被成组命中）。
 *
 * 修法：候选事件进入 UI 之前，仅从**尾部**连续剔除「纯 emoji」候选。
 *  - 只截尾部 ⇒ 前面所有汉字 / 英文候选的**顺序与 native 索引完全不变**，
 *    用户点击候选时 `select(index)` 仍精确指向原词条，零索引错位风险；
 *    （若在中间删除，UI 位置与 native 索引会整体错位，选词必错。）
 *  - 不动中间候选、不改 hasPrev/hasNext ⇒ 翻页与展开候选窗口行为不变。
 *  - emoji 面板（PickerWindow）与 `:别名:` 快速输入能力完整保留，只是不再
 *    在拼音短码下自动挤占前排候选。
 *  - 顺带做尾部连续同文本去重，防「同词多行重复」再冒头。
 *
 * v1.0.68 修复（本模块两个确证缺陷）：
 *  1. 原 isEmojiOnly 用 `for (c in text)` 遍历，Kotlin/Java 的 Char 是 **UTF-16 code unit**。
 *     😨😧😮😲 均属 U+1F6xx（supplementary plane），在字符串里是 high+low surrogate
 *     两个 Char（code 落在 0xD800..0xDFFF），永远匹配不到 0x1F000..0x1FAFF 等区段 ——
 *     即 v1.0.65 声称的「抑制单字母 a 尾部 emoji」实际从未生效。改为按 Unicode
 *     code point 遍历（Character.toCodePoint 合并代理对）。
 *  2. 修复 1 后，若用户正在用 `:别名:` / emoji 前缀主动输入表情，整栏候选可能全是
 *     emoji，被一路截到空 ⇒ 表情快速输入功能被破坏。加保底：整栏全为 emoji 时
 *     原样返回，不截断（仅在前面存在正常候选时才剔除尾部 emoji）。
 *
 * 纯离线：不联网、不落盘、不记录任何候选内容。
 */
package org.fcitx.fcitx5.android.core

object MbmCandidateSanitizer {

    /** 统一入口：所有 Fcitx 事件（CandidatesView / KawaiiBar / 展开窗口）都经这里分发。 */
    fun sanitize(event: FcitxEvent<*>): FcitxEvent<*> = when (event) {
        is FcitxEvent.PagedCandidateEvent -> {
            val d = event.data
            val kept = trimTrailing(d.candidates)
            if (kept.size == d.candidates.size) event
            else FcitxEvent.PagedCandidateEvent(
                d.copy(
                    candidates = kept,
                    cursorIndex = d.cursorIndex.coerceAtMost(kept.lastIndex)
                )
            )
        }
        is FcitxEvent.CandidateListEvent -> {
            val d = event.data
            val kept = trimTrailing(d.candidates)
            if (kept.size == d.candidates.size) event
            else FcitxEvent.CandidateListEvent(
                d.copy(
                    candidates = kept,
                    // v1.0.68：candidates 截短后 total 必须同步减少，否则 UI 仍按旧总数
                    // 显示/分页。total=-1（未知）或未超过当前数组长度时保持原值，
                    // 避免误伤「跨页候选总数」语义。
                    total = if (d.total >= d.candidates.size) {
                        (d.total - (d.candidates.size - kept.size)).coerceAtLeast(kept.size)
                    } else d.total
                )
            )
        }
        else -> event
    }

    /**
     * 从尾部连续剔除 emoji / 重复候选，遇到第一个正常候选立即停止。
     * 返回原数组表示无需改动（避免不必要的事件重建）。
     */
    private fun trimTrailing(candidates: Array<CandidateWord>): Array<CandidateWord> {
        var end = candidates.size
        while (end > 0 && isEmojiOnly(candidates[end - 1].text)) end--
        // 尾部连续同文本去重（保留最前一个），同样只影响尾部索引
        while (end > 1 && candidates[end - 1].text == candidates[end - 2].text) end--
        // v1.0.68 保底：整栏全是 emoji 时不截断 —— 说明用户正在主动用 :别名:/
        // emoji 前缀输入表情，必须保留该能力，避免把候选栏清空。
        if (end == 0) return candidates
        return if (end == candidates.size) candidates else candidates.copyOfRange(0, end)
    }

    /**
     * 「纯 emoji」= 按 Unicode code point 含 emoji 区段字符，且不含任何汉字或
     * ASCII 字母数字。保留：汉字候选（啊 / 安庆 / 爱情）、英文 hint（aq / aquo）、
     * 纯标点（，。）、ASCII 颜文字（T_T）。空文本候选不处理（交给 UI 自身逻辑）。
     *
     * 必须按 code point 而非 Char 遍历：supplementary plane 的 emoji（U+1F300+，
     * 含本模块要抑制的 😨😧😮😲）在 UTF-16 里是代理对，逐 Char 看不到真实码点。
     */
    private fun isEmojiOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasEmoji = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val code = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                i++
                Character.toCodePoint(c, text[i])
            } else {
                c.code
            }
            // 汉字（基本区 + 扩展 A / 扩展 B 及以上）⇒ 正常候选，绝不剔除
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code >= 0x20000) return false
            // ASCII 字母数字（含英文 hint / 颜文字）⇒ 正常候选
            if (code < 128 && Character.isLetterOrDigit(code)) return false
            if (code in 0x1F000..0x1FAFF ||   // 绝大多数 emoji（U+1F6xx 惊恐脸在此）
                code in 0x1FC00..0x1FFFD ||   // 扩展符号
                code in 0x2600..0x27BF        // BMP 内杂项符号 / dingbats（☀ ❤ ✌）
            ) hasEmoji = true
            i++
        }
        return hasEmoji
    }
}
