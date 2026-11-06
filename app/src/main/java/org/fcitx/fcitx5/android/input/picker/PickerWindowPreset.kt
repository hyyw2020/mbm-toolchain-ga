/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.emoji.CustomEmojiRepository
import org.fcitx.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.ImagePickerSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextPickerSwitchKey

fun symbolPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Symbol,
    data = PickerData.Symbol,
    density = PickerPageUi.Density.High,
    switchKey = ImageLayoutSwitchKey(R.drawable.ic_number_pad, NumberKeyboard.Name)
)

fun emojiPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Emoji,
    data = PickerData.Emoji,
    density = PickerPageUi.Density.Medium,
    switchKey = TextPickerSwitchKey(":-)", PickerWindow.Key.Emoticon),
    popupPreview = false,
    followKeyBorder = false,
    policy = EmojiPickerPolicy(),
    // v1.0.73：动态追加「自定义图像表情」分类。数据来自用户运行时导入的本地包，
    // 每次重建分类（含导入/清空后的 revision 变化）都会重新求值，无需重建窗口。
    dataProvider = {
        val tokens = CustomEmojiRepository.allTokens()
        if (tokens.isEmpty()) PickerData.Emoji
        else PickerData.Emoji + listOf(
            PickerData.Category("✦", R.drawable.ic_baseline_tag_faces_24) to tokens.toTypedArray()
        )
    }
)

fun emoticonPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Emoticon,
    data = PickerData.Emoticon,
    density = PickerPageUi.Density.Low,
    switchKey = ImagePickerSwitchKey(R.drawable.ic_baseline_tag_faces_24, PickerWindow.Key.Emoji),
    popupPreview = false,
    followKeyBorder = false
)
