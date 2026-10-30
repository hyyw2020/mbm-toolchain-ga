/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.menu.ImeMenuPopup
import org.fcitx.fcitx5.android.utils.MbmDebug
import kotlin.math.min

abstract class ClipboardAdapter(
    private val theme: Theme,
    private val entryRadius: Float,
    private val maskSensitive: () -> Boolean
) : PagingDataAdapter<ClipboardEntry, ClipboardAdapter.ViewHolder>(diffCallback) {

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: ClipboardEntry,
                newItem: ClipboardEntry
            ): Boolean {
                return oldItem == newItem
            }
        }

        /**
         * excerpt text to show on ClipboardEntryUi, to reduce render time of very long text
         * @param str text to excerpt
         * @param mask mask text content with "•"
         * @param lines max output lines
         * @param chars max chars per output line
         */
        fun excerptText(
            str: String,
            mask: Boolean = false,
            lines: Int = 4,
            chars: Int = 128
        ): String = buildString {
            val length = str.length
            var lineBreak = -1
            for (i in 1..lines) {
                val start = lineBreak + 1   // skip previous '\n'
                val excerptEnd = min(start + chars, length)
                lineBreak = str.indexOf('\n', start)
                if (lineBreak < 0) {
                    // no line breaks remaining, substring to end of text
                    if (mask) {
                        append(ClipboardEntry.BULLET.repeat(excerptEnd - start))
                    } else {
                        append(str.substring(start, excerptEnd))
                    }
                    break
                } else {
                    val end = min(excerptEnd, lineBreak)
                    // append one line exactly
                    if (mask) {
                        append(ClipboardEntry.BULLET.repeat(end - start))
                    } else {
                        appendLine(str.substring(start, end))
                    }
                }
            }
        }
    }

    private var menu: ImeMenuPopup? = null

    class ViewHolder(val entryUi: ClipboardEntryUi) : RecyclerView.ViewHolder(entryUi.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ClipboardEntryUi(parent.context, theme, entryRadius)).apply {
            MbmDebug.markFeature(entryUi.root, "clipboard.paste")
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        if (entry == null) {
            // U9 修复：占位/加载中条目没有真实数据，必须清除旧点击/长按监听，
            // 否则会粘出上一次绑定在该视图上的过期条目
            with(holder.entryUi) {
                root.setOnClickListener(null)
                root.setOnLongClickListener(null)
            }
            return
        }
        with(holder.entryUi) {
            setEntry(excerptText(entry.text, entry.sensitive && maskSensitive()), entry.pinned, entry.isBlob)
            root.setOnClickListener {
                onPaste(entry)
            }
            root.setOnLongClickListener {
                MbmDebug.logFeature("clipboard.longpress")
                // MBM v0.0.09：条目菜单原用系统 PopupMenu（可抢焦点的独立窗口），
                // 在 OEM ROM 上会令系统判定「IME 失焦」而强制收起输入法——用户报回
                // 「点按删除键输入法自动收起」（pitfalls #60）。改用非焦点 PopupWindow。
                menu?.dismiss()
                val items = buildList {
                    if (entry.pinned) {
                        add(
                            ImeMenuPopup.Item(
                                ctx.getString(R.string.unpin),
                                R.drawable.ic_outline_push_pin_24
                            ) { onUnpin(entry.id) }
                        )
                    } else {
                        add(
                            ImeMenuPopup.Item(
                                ctx.getString(R.string.pin),
                                R.drawable.ic_baseline_push_pin_24
                            ) { onPin(entry.id) }
                        )
                    }
                    add(
                        ImeMenuPopup.Item(
                            ctx.getString(R.string.edit),
                            R.drawable.ic_baseline_edit_24
                        ) { onEdit(entry.id) }
                    )
                    // U1 修复：大文本条目的 text 列只有 256 字符预览，全文在 blob；
                    // 提供可达的「查看全文」入口，避免「查看更多点不开」
                    add(
                        ImeMenuPopup.Item(
                            ctx.getString(R.string.view_full_text),
                            null
                        ) { onViewFullText(entry) }
                    )
                    add(
                        ImeMenuPopup.Item(
                            ctx.getString(R.string.share),
                            R.drawable.ic_baseline_share_24
                        ) { onShare(entry) }
                    )
                    add(
                        ImeMenuPopup.Item(
                            ctx.getString(R.string.delete),
                            R.drawable.ic_baseline_delete_24
                        ) { onDelete(entry.id) }
                    )
                }
                menu = ImeMenuPopup(ctx, theme).takeIf { it.show(root, null, items) }
                true
            }
        }
    }

    fun getEntryAt(position: Int) = getItem(position)

    fun onDetached() {
        menu?.dismiss()
        menu = null
    }

    abstract fun onPaste(entry: ClipboardEntry)

    abstract fun onViewFullText(entry: ClipboardEntry)

    abstract fun onPin(id: Int)

    abstract fun onUnpin(id: Int)

    abstract fun onEdit(id: Int)

    abstract fun onShare(entry: ClipboardEntry)

    abstract fun onDelete(id: Int)

}