/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.resources.styledDimenPxSize
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.checkBox
import splitties.views.dsl.core.imageButton
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp
import splitties.views.textAppearance

class DynamicListEntryUi(override val ctx: Context) : Ui {

    val handleImage = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_drag_handle_24)!!.apply {
            setTint(styledColor(android.R.attr.colorAccent))
        }
        setPaddingDp(3, 0, 3, 0)
    }

    val multiselectCheckBox = checkBox()

    val checkBox = checkBox()

    val nameText = textView {
        setPaddingDp(0, 12, 0, 0)
        textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
    }

    /** 副文本行（如自定义词组的「键 / 首字母 / 全拼」）。无内容时隐藏。 */
    val subText = textView {
        textSize = 12f
        setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
        setPaddingDp(0, 2, 0, 12)
    }

    val editButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        imageDrawable = drawable(R.drawable.ic_baseline_edit_24)!!.apply {
            setTint(styledColor(android.R.attr.colorControlNormal))
        }
    }

    val settingsButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        imageDrawable = drawable(R.drawable.ic_baseline_settings_24)!!.apply {
            setTint(styledColor(android.R.attr.colorControlNormal))
        }
    }

    /** 置顶按钮（置顶/取消置顶，最高优先级档）。默认 GONE，由调用方按需启用。 */
    val pinButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        imageDrawable = drawable(R.drawable.ic_baseline_arrow_upward_24)!!.apply {
            setTint(styledColor(android.R.attr.colorControlNormal))
        }
    }

    override val root: View = constraintLayout {
        layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
        backgroundColor = styledColor(android.R.attr.colorBackground)
        minHeight = styledDimenPxSize(android.R.attr.listPreferredItemHeightSmall)

        val paddingStart = styledDimenPxSize(android.R.attr.listPreferredItemPaddingStart)

        add(handleImage, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            startOfParent(paddingStart)
        })

        add(multiselectCheckBox, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            after(handleImage, paddingStart)
        })

        add(checkBox, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            after(multiselectCheckBox, paddingStart)
        })

        // 主文本（词组原文）置于上方，副文本（键/拼音）置于其下方，二者横跨左侧勾选框与右侧按钮之间
        add(nameText, lParams {
            width = matchConstraints
            height = wrapContent
            topOfParent(paddingStart)
            after(checkBox, paddingStart)
            before(editButton)
        })

        add(subText, lParams {
            width = matchConstraints
            height = wrapContent
            below(nameText)
            after(checkBox, paddingStart)
            bottomOfParent(paddingStart)
            before(editButton)
        })

        add(editButton, lParams {
            width = dp(53)
            height = matchConstraints
            centerVertically()
            before(pinButton)
        })

        add(pinButton, lParams {
            width = dp(53)
            height = matchConstraints
            centerVertically()
            before(settingsButton)
        })

        add(settingsButton, lParams {
            width = dp(53)
            height = matchConstraints
            centerVertically()
            endOfParent()
        })
    }
}
