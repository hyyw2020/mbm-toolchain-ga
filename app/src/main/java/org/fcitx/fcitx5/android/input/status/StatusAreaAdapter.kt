/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.MbmDebug

abstract class StatusAreaAdapter : RecyclerView.Adapter<StatusAreaAdapter.Holder>() {
    inner class Holder(val ui: StatusAreaEntryUi) : RecyclerView.ViewHolder(ui.root)

    var entries: Array<StatusAreaEntry> = arrayOf()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(StatusAreaEntryUi(parent.context, theme))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.ui.setEntry(entry)
        holder.ui.root.setOnClickListener {
            onItemClick(it, entry)
        }
        // v3.6：SelfTest 条目标记在渲染时打（onItemClick 打标在点击后才生效，
        // 自测 status 相位在点击前收集条目，需绑定期打标才能定位到自测入口）
        if (entry is StatusAreaEntry.Android && entry.type == StatusAreaEntry.Android.Type.SelfTest) {
            MbmDebug.markFeature(holder.ui.root, "status.selftest")
        }
    }

    abstract val theme: Theme

    override fun getItemCount() = entries.size

    abstract fun onItemClick(view: View, entry: StatusAreaEntry)
}