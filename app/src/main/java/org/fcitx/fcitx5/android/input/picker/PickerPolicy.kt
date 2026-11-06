/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.core.EmojiSequence
import org.fcitx.fcitx5.android.data.emoji.CustomEmojiProtocol
import org.fcitx.fcitx5.android.data.emoji.CustomEmojiRepository
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.popup.EmojiModifier

interface PickerPolicy {
    /**
     * Whether Picker should include this symbol
     */
    fun filter(raw: String): Boolean

    /**
     * Actual string to commit when this symbol was selected
     */
    fun transform(raw: String): String

    /**
     * Long press options for this symbol
     */
    fun popup(raw: String): KeyDef.Popup.Keyboard?

    /**
     * Return different value to make the Picker rebuild all pages on attach
     */
    fun invalidateKey(): Any
}

/**
 * Default policy: show all symbols as provided and never need to rebuild
 */
class DefaultPickerPolicy : PickerPolicy {
    override fun filter(raw: String) = true
    override fun transform(raw: String) = raw
    override fun popup(raw: String) =
        KeyDef.Popup.Keyboard.Preset(label = raw, transformPunctuation = false)

    override fun invalidateKey() = Unit
}

/**
 * Emoji policy: filter unsupported glyph, transform skintone, rebuild on preference changes
 *
 * v1.0.73：并入「自定义图像表情」分类条目的处理。面板条目有两种：
 *  - Unicode Emoji 序列：走肤色修饰 / 字体能力判定；
 *  - sticker token（私用区前缀）：不是待上屏文本，不得参与肤色变换、
 *    不得被字体过滤裁掉，点击时由 PickerPageUi 转成 CommitStickerAction。
 */
class EmojiPickerPolicy : PickerPolicy {
    data class Prefs(
        val hideUnsupportedEmojis: Boolean,
        val defaultSkinTone: EmojiModifier.SkinTone,
        val stickerRevision: Int
    )

    private val symbolPrefs = AppPrefs.getInstance().symbols
    private val hideUnsupportedEmojis by symbolPrefs.hideUnsupportedEmojis
    private val defaultSkinTone by symbolPrefs.defaultEmojiSkinTone

    private val prefs
        get() = Prefs(hideUnsupportedEmojis, defaultSkinTone, CustomEmojiRepository.revision)

    override fun filter(raw: String): Boolean {
        // 自定义表情条目永远放行：其 token 不是 Unicode Emoji 序列，
        // 若走 isValidEmoji 会被整体裁掉。
        if (CustomEmojiProtocol.parseToken(raw) != null) return true
        // 默认（v1.0.73 起）全量放行：默认支持所有 Unicode Emoji 序列。
        if (!hideUnsupportedEmojis) return true
        // 手动开启隐藏时：复合序列（ZWJ / 肤色 / RI / Tag / 键帽）一律保留，
        // 因为 Paint.hasGlyph 对多码点串恒返回 false，不能作为裁剪依据；
        // 仅对「单码点且系统字体确实无字形」的条目隐藏。
        if (EmojiSequence.codePoints(raw).size > 1) return true
        return !EmojiModifier.lacksSystemGlyph(raw)
    }

    override fun transform(raw: String): String {
        if (CustomEmojiProtocol.parseToken(raw) != null) return raw
        return EmojiModifier.getPreferredTone(raw, defaultSkinTone)
    }

    override fun popup(raw: String): KeyDef.Popup.Keyboard? {
        if (CustomEmojiProtocol.parseToken(raw) != null) return null
        val items = EmojiModifier.produceSkinTones(raw, defaultSkinTone) ?: return null
        return KeyDef.Popup.Keyboard.Explicit(items)
    }

    /** revision 纳入 key，导入/清空后 PickerPagesAdapter.refreshIfNeeded 会重建分类。 */
    override fun invalidateKey(): Prefs = prefs
}
