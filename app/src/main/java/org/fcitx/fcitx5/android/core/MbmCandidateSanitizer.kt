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
            else FcitxEvent.CandidateListEvent(d.copy(candidates = kept))
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
        return if (end == candidates.size) candidates else candidates.copyOfRange(0, end)
    }

    /**
     * 「纯 emoji」= 文本含 emoji 区段字符，且不含任何汉字或 ASCII 字母数字。
     * 保留：汉字候选（啊 / 安庆 / 爱情）、英文 hint（aq / aquo）、纯标点（，。）、
     * ASCII 颜文字（T_T）。空文本候选不在此处理（交给 UI 自身逻辑）。
     */
    private fun isEmojiOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasEmoji = false
        for (c in text) {
            val code = c.code
            if (c in '\u4E00'..'\u9FFF') return false
            if (code < 128 && c.isLetterOrDigit()) return false
            if (code in 0x1F000..0x1FAFF ||
                code in 0x2600..0x27BF ||
                code in 0x1F1E6..0x1F1FF
            ) hasEmoji = true
        }
        return hasEmoji
    }
}
