/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editorinfo

import android.content.ClipData
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import timber.log.Timber

class EditorInfoWindow : InputWindow.ExtendedInputWindow<EditorInfoWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme by manager.theme()

    private var propertyMap = mapOf<String, String>()

    private fun buildMarkdownString() = buildString {
        append("# EditorInfo Inspector\n\n")
        append("|Property|Value|\n|:-|:-|\n")
        propertyMap.forEach { (k, v) ->
            append("|$k|")
            append(v.replace("\n", "<br>"))
            append("|\n")
        }
    }

    private val ui by lazy {
        EditorInfoUi(context, theme)
    }

    override val title by lazy {
        context.getString(R.string.editor_info_inspector)
    }

    private val copyButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_content_copy_24, theme).apply {
            setOnClickListener {
                val clipData = ClipData.newPlainText("", buildMarkdownString())
                context.clipboardManager.setPrimaryClip(clipData)
                context.toast(R.string.done)
            }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            add(copyButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateView() = ui.root

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        // N114：解析 EditorInfo 可能因未知 Parcelable 抛异常，这里兜底降级为空表，
        // 避免打开面板时把输入法崩掉
        propertyMap = try {
            EditorInfoParser.parse(service.currentInputEditorInfo)
        } catch (e: Exception) {
            Timber.w(e, "N114: failed to parse EditorInfo")
            emptyMap()
        }
        ui.setValues(propertyMap)
    }

    override fun onDetached() {}
}
