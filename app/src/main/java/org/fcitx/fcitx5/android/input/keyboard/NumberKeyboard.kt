/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "Number"

        /**
         * MBM v1.0.39：改为「图二」样式——左列运算符竖排（% / + *）、数字栅格居中、
         * 右上角退格、右侧 - *、底行「返回 / !?:# / 0 / 空格 / 换行」。
         * 与图二的唯一差异：右列第 3 键用 `*`（乘号）替代 `@`——用户硬性要求
         * 加减乘除全部可直接输入，乘号优先级高于 @；顶部工具条（KawaiiBar）不在此布局内。
         */
        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                NumPadKey("%", 0x025, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
                NumPadKey("1", 0xffb1, 30f, 0f),
                NumPadKey("2", 0xffb2, 30f, 0f),
                NumPadKey("3", 0xffb3, 30f, 0f),
                BackspaceKey(),
            ),
            listOf(
                NumPadKey("/", 0xffaf, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
                NumPadKey("4", 0xffb4, 30f, 0f),
                NumPadKey("5", 0xffb5, 30f, 0f),
                NumPadKey("6", 0xffb6, 30f, 0f),
                NumPadKey("-", 0xffad, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
            ),
            listOf(
                NumPadKey("+", 0xffab, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
                NumPadKey("7", 0xffb7, 30f, 0f),
                NumPadKey("8", 0xffb8, 30f, 0f),
                NumPadKey("9", 0xffb9, 30f, 0f),
                NumPadKey("*", 0xffaa, 23f, 0.15f, KeyDef.Appearance.Variant.Alternative),
            ),
            listOf(
                LayoutSwitchKey("返回", TextKeyboard.Name, 0.2f, KeyDef.Appearance.Variant.Alternative),
                LayoutSwitchKey("!?#", PickerWindow.Key.Symbol.name, 0.2f, KeyDef.Appearance.Variant.AltForeground),
                NumPadKey("0", 0xffb0, 30f, 0.25f),
                MiniSpaceKey(),
                ReturnKey(0.2f),
            )
        )
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val space: TextKeyView by lazy { findViewById(R.id.button_mini_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    @SuppressLint("MissingSuperCall")
    override fun onPopupAction(action: PopupAction) {
        // leave empty on purpose to disable popup in NumberKeyboard
    }

}