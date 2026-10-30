/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import splitties.resources.drawable
import splitties.resources.styledColor
import org.fcitx.fcitx5.android.utils.MbmDebug

fun PreferenceScreen.addCategory(title: String, block: PreferenceCategory.() -> Unit) {
    val category = PreferenceCategory(context).apply {
        setTitle(title)
    }
    addPreference(category)
    block.invoke(category)
}

fun PreferenceScreen.addCategory(@StringRes title: Int, block: PreferenceCategory.() -> Unit) {
    addCategory(context.getString(title), block)
}

fun Preference.setup(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null
) {
    isSingleLineTitle = false
    setTitle(title)
    setSummary(summary)
    if (icon == null) {
        isIconSpaceReserved = false
    } else {
        setIcon(context.drawable(icon)?.apply {
            setTint(context.styledColor(android.R.attr.colorControlNormal))
        })
    }
    onClick?.also {
        setOnPreferenceClickListener { _ ->
            it.invoke()
            true
        }
    }
}

fun PreferenceGroup.addPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null
) {
    addPreference(Preference(context).apply { setup(title, summary, icon, onClick) })
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    summary: String,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null
) {
    addPreference(context.getString(title), summary, icon, onClick)
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null
) {
    addPreference(context.getString(title), summary?.let(context::getString), icon, onClick)
}

class LongClickPreference(context: Context) : Preference(context) {
    private var onLongClick: (() -> Unit)? = null

    fun setOnPreferenceLongClickListener(callback: (() -> Unit)? = null) {
        onLongClick = callback
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke()
            true
        }
    }
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    addPreference(LongClickPreference(context).apply {
        setup(context.getString(title), summary?.let { context.getString(it) }, icon, onClick)
        setOnPreferenceLongClickListener(onLongClick)
    })
}

/** MBM v1.0.36：带 feature 标记的设置项——绑定视图时打 markFeature，
 *  点击时打 logFeature（自测 settings 层可定位入口并断言点击响应）。 */
class FeaturePreference(context: Context, private val featureId: String) : Preference(context) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        MbmDebug.markFeature(holder.itemView, featureId)
    }
}

fun PreferenceGroup.addPreference(
    featureId: String,
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val wrapped = onClick?.let { cb -> { MbmDebug.logFeature(featureId); cb() } }
    addPreference(FeaturePreference(context, featureId).apply {
        setup(context.getString(title), summary?.let { context.getString(it) }, icon, wrapped)
    })
}
