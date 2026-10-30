/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent

class LogAdapter(private val entries: ArrayList<CharSequence> = ArrayList()) :
    RecyclerView.Adapter<LogAdapter.Holder>() {
    inner class Holder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    /**
     * N47：日志页原先无容量上限，退后台仍在采集 → 一路涨到 OOM。
     * 这里按 [MAX_ENTRIES] 环形截断：只保留最近这么多条，超出就丢最旧的。
     *
     * @param line 新的一行日志
     */
    fun append(line: CharSequence) {
        // 超出容量时先砍掉最旧的若干条（通常是 1 条），保持内存有界
        val dropped = if (entries.size >= MAX_ENTRIES) {
            val drop = entries.size - MAX_ENTRIES + 1
            entries.subList(0, drop).clear()
            drop
        } else {
            0
        }
        entries.add(line)
        if (dropped > 0) {
            notifyItemRangeRemoved(0, dropped)
        }
        notifyItemInserted(entries.size - 1)
    }

    fun clear() {
        val size = entries.size
        entries.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun fullLogString() = entries.joinToString("\n")

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        parent.textView {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setTextClassifier(TextClassifier.NO_OP)
            }
            layoutParams = MarginLayoutParams(wrapContent, wrapContent).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.textView.text = entries[position]
    }

    companion object {
        /**
         * N47：日志保留上限（条）。页面只看近期日志，够定位问题即可，
         * 有界才不会在长时间停留在日志页/退后台采集时被撑爆。
         */
        private const val MAX_ENTRIES = 5000
    }
}
