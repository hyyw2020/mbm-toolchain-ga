/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dialog

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class InputMethodListAdapter(
    val entries: List<InputMethodData>,
    private var enabledIndex: Int,
    private val onEntryClick: (InputMethodData) -> Unit
) : RecyclerView.Adapter<InputMethodListAdapter.Holder>() {

    inner class Holder(val ui: InputMethodEntryUi) : RecyclerView.ViewHolder(ui.root)

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(InputMethodEntryUi(parent.context))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ime = entries[position]
        holder.ui.root.apply {
            isChecked = position == enabledIndex
            text = ime.name
            setOnClickListener { onEntryClick(ime) }
        }
    }

    /**
     * 把勾选态从旧位置移到 [position]。
     *
     * N110：这个方法原先全项目没有调用点（死代码），切换后勾选框一直停在原来的那一行；
     * 而 [enabledIndex] 在列表里没有匹配项时是 -1，直接 notifyItemChanged(-1) 会抛异常。
     * 这里补上越界保护，并由 [InputMethodPickerWindow] 在点击条目时真正调用，
     * 让状态同步到界面。
     */
    fun setEnabled(position: Int) {
        if (position !in entries.indices) return
        val old = enabledIndex
        if (position == old) return
        enabledIndex = position
        // N110：旧位置可能从未被选中（-1），此时不需要刷新
        if (old in entries.indices) notifyItemChanged(old)
        notifyItemChanged(position)
    }
}
