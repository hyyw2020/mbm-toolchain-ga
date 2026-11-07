/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.fcitx.fcitx5.android.data.clipboard.ClipItemTextMerge
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
            // MBM v1.0.77：ClipData 允许多个 Item（一次复制多段文字/多行/标题+正文）。
            // 旧实现只读 getItemAt(0)，其余 Item 被静默丢弃 —— 用户复制了整段内容，
            // 历史里却只剩第一截。现在枚举全部 Item 的 text 交给纯 JVM 策略合并：
            // 单 Item 原样返回（行为零变化），多 Item 以 \n 顺序连接，一段不丢。
            val count = runCatching { clipData.itemCount }.getOrDefault(0)
            if (count <= 0) return null
            val texts = (0 until count).map { i ->
                runCatching { clipData.getItemAt(i)?.text?.toString() }.getOrNull()
            }
            val merged = ClipItemTextMerge.merge(texts)
            // MBM v1.0.59：文件管理器复制文件/路径时把内容放进 URI 或 Intent 型
            // ClipData 而非 text 项，旧实现 item.text 为 null 直接返回 null，表现为
            // 「应用显示复制成功，但输入法剪贴板毫无痕迹」。兜底：遍历全部 Item，
            // 从 URI / Intent 抽取可读文本（同样支持多 Item 合并）。
            val (str, type) = if (merged != null && merged.isNotBlank()) {
                merged to desc.getMimeType(0)
            } else {
                val derived = (0 until count).mapNotNull { i ->
                    val item = runCatching { clipData.getItemAt(i) }.getOrNull()
                        ?: return@mapNotNull null
                    deriveTextFromClipItem(item)
                }.let { ClipItemTextMerge.merge(it) } ?: return null
                derived to ClipDescription.MIMETYPE_TEXT_PLAIN
            }
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                desc.extras?.getBoolean(IS_SENSITIVE) ?: false
            } else {
                false
            }
            return ClipboardEntry(
                // MBM v1.0.56：transformer（如剪贴板过滤插件 ClearURLs）若抛异常（catalog 未初始化、
                // 正则越界等）必须降级为原文，绝不能因过滤失败而整条丢弃用户的复制。
                // 否则插件开启期间所有剪贴板历史录入都会静默失效。
                text = if (transformer != null) runCatching { transformer(str) }.getOrDefault(str) else str,
                timestamp = clipData.timestamp(),
                type = type,
                sensitive = sensitive
            )
        }

        /**
         * 当 [ClipData.Item.text] 为空时，从 URI / Intent 抽取可读文本，使非文本型复制
         * （文件管理器复制文件/路径）也能在输入法剪贴板历史里留下痕迹。
         *  - URI：优先完整文件路径（file://），否则末段（content:// 的 id / 文件名），再否则退回完整 URI；
         *    长度截断到 512，避免 content:// 长查询参数撑爆条目。
         *  - Intent：取 data 字符串或 action。
         */
        private fun deriveTextFromClipItem(item: ClipData.Item): String? {
            runCatching {
                val uri: Uri? = item.uri
                if (uri != null) {
                    val s = uri.path ?: uri.lastPathSegment ?: uri.toString()
                    if (s.isNotBlank()) return s.take(512)
                }
            }.onFailure { /* uri 解析异常不影响其它分支 */ }

            runCatching {
                val intent: Intent? = item.intent
                if (intent != null) {
                    val s = intent.dataString ?: intent.action
                    if (!s.isNullOrBlank()) return s.take(512)
                }
            }.onFailure { /* intent 解析异常不影响结果 */ }

            return null
        }
    }
}