/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.utils.timestamp

@Entity(tableName = ClipboardEntry.TABLE_NAME)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "-1")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = ClipDescription.MIMETYPE_TEXT_PLAIN)
    val type: String = ClipDescription.MIMETYPE_TEXT_PLAIN,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val sensitive: Boolean = false,
    /**
     * MBM v1.0.31 大文本外置（无上限）：
     * [text] 列对 blob 条目只存 preview（前 256 字符），全文在
     * filesDir/clipblobs/<sha256>.bin，由 [blobRef] 指向。
     * [blobRef] == null → 小文本，全文就在 [text] 列。
     */
    @ColumnInfo(defaultValue = "NULL")
    val blobRef: String? = null,
    /** 全文 UTF-8 字节数（小文本 = text.toByteArray().size；大文本 = blob 文件长度）。 */
    @ColumnInfo(defaultValue = "0")
    val byteLen: Long = 0L,
    /** 全文 SHA-256 十六进制；小文本同样计算（大文本去重走 findBySha）。 */
    @ColumnInfo(defaultValue = "\"\"")
    val sha256: String = "",
    /** 0=内联（text 即全文）；1=blob 文件。 */
    @ColumnInfo(defaultValue = "0")
    val codec: Int = CODEC_INLINE
) {
    /** 是否大文本条目（全文在 blob 文件中） */
    val isBlob: Boolean get() = blobRef != null

    companion object {
        const val BULLET = "•"

        const val TABLE_NAME = "clipboard"

        const val CODEC_INLINE = 0
        const val CODEC_BLOB = 1

        private val IS_SENSITIVE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }

        fun fromClipData(
            clipData: ClipData,
            transformer: ((String) -> String)? = null
        ): ClipboardEntry? {
            val desc = clipData.description
            // TODO: handle multiple items (when does this happen?)
            val item = clipData.getItemAt(0) ?: return null
            val str = item.text?.toString() ?: return null
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                desc.extras?.getBoolean(IS_SENSITIVE) ?: false
            } else {
                false
            }
            return ClipboardEntry(
                text = if (transformer != null) transformer(str) else str,
                timestamp = clipData.timestamp(),
                type = desc.getMimeType(0),
                sensitive = sensitive
            )
        }
    }
}