/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 自定义图像表情的富媒体输入通道（Android Rich Content / Image Keyboard）。
 *
 * 目标编辑器通过 EditorInfo.contentMimeTypes 声明可接收的 MIME 类型；
 * 输入法据此用 AndroidX Core 的 InputConnectionCompat.commitContent 提交
 * 一个由 FileProvider 生成的 content:// URI，并附带 ClipDescription 与
 * 临时读授权。这是 AOSP 定义的标准富媒体上屏协议，不依赖任何第三方
 * 应用私有接口，也不需要网络权限。
 *
 * 对未声明 image 能力的编辑器提供可配置降级：提交一段文本标签
 * （如 :smile:），避免出现「点了没反应」。降级文本由调用方传入，
 * 为 null 时返回 UNAVAILABLE 交由 UI 提示。
 *
 * 全部调用异常安全：commitContent 抛异常时自动回退，绝不让输入法崩溃。
 */
package org.fcitx.fcitx5.android.input.emoji

import android.content.ClipDescription
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import timber.log.Timber

object EmojiRichContentCommitter {

    enum class Result {
        /** 已通过 commitContent 富媒体提交。 */
        COMMITTED,
        /** 目标编辑器不支持富媒体，已按偏好降级为文本。 */
        FALLBACK_TEXT,
        /** 无输入连接或降级被禁用，未提交。 */
        UNAVAILABLE
    }

    /** 目标编辑器是否声明了可接收该 MIME 的富媒体能力。 */
    fun supports(editorInfo: EditorInfo?, mime: String): Boolean {
        val types = editorInfo?.contentMimeTypes ?: return false
        return types.any { declared ->
            declared.equals(mime, ignoreCase = true) ||
                declared == "image/*" ||
                (declared.endsWith("/*") && mime.startsWith(declared.dropLast(1), ignoreCase = true))
        }
    }

    /**
     * 提交一个图像表情。
     *
     * @param uri          FileProvider 生成、已可临时授权的 content URI
     * @param mime         image 子类型，与 ClipDescription 一致
     * @param label        内容描述，展示在接收方
     * @param fallbackText 降级文本；null 或空白表示禁用降级
     */
    fun commit(
        context: Context,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        uri: android.net.Uri,
        mime: String,
        label: String,
        fallbackText: String?
    ): Result {
        if (ic == null) return Result.UNAVAILABLE
        if (!supports(editorInfo, mime)) return fallback(ic, fallbackText)

        var info: InputContentInfoCompat? = null
        val ok = runCatching {
            val description = ClipDescription(label, arrayOf(mime))
            // linkUri 传 null：图像资产完全本地，不附带任何外部链接
            val content = InputContentInfoCompat(uri, description, null)
            info = content
            // 显式授予目标包名读权限，兼容未走 flag 授权路径的接收方实现
            editorInfo?.packageName?.let { pkg ->
                runCatching {
                    context.grantUriPermission(
                        pkg, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            InputConnectionCompat.commitContent(
                ic, editorInfo!!, content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                Bundle.EMPTY
            )
        }.onFailure { Timber.w(it, "EmojiRichContent: commitContent failed") }
            .getOrDefault(false)

        if (ok) return Result.COMMITTED
        runCatching { info?.releasePermission() }
        return fallback(ic, fallbackText)
    }

    private fun fallback(ic: InputConnection, fallbackText: String?): Result {
        if (fallbackText.isNullOrBlank()) return Result.UNAVAILABLE
        return if (runCatching { ic.commitText(fallbackText, 1); true }.getOrDefault(false)) {
            Result.FALLBACK_TEXT
        } else {
            Result.UNAVAILABLE
        }
    }
}
