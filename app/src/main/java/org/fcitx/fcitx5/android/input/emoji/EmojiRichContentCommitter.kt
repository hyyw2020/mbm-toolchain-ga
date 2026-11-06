/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 自定义图像表情的输入通道（Android Rich Content / Image Keyboard + 系统剪贴板）。
 *
 * 目标编辑器通过 EditorInfo.contentMimeTypes 声明可接收的 MIME 类型；
 * 输入法据此用 AndroidX Core 的 InputConnectionCompat.commitContent 提交
 * 一个由 FileProvider 生成的 content:// URI，并附带 ClipDescription 与
 * 临时读授权。这是 AOSP 定义的标准富媒体上屏协议，不依赖任何第三方
 * 应用私有接口，也不需要网络权限。
 *
 * v1.0.75：多数聊天类输入框**不声明** image 能力，旧版此时降级为一段
 * 文本短码（:[label]:），用户看到的就是乱码、等于「输出失效」。现在改为：
 * 目标编辑器不支持富媒体时，把图像以 content URI 写回系统剪贴板
 * （ClipData.newUri + image 类 MIME + 临时读授权）。这是 Android 上跨应用
 * 传递图像最通用的标准通道 —— 用户在任意输入框「长按→粘贴」即可贴出
 * 原图，不依赖目标应用声明任何能力，也不含任何应用私有协议。
 * 文本短码降级仅作为用户显式开启、且剪贴板写入也失败时的最后兜底。
 *
 * 全部调用异常安全：任一路径抛异常时自动逐级回退，绝不让输入法崩溃。
 */
package org.fcitx.fcitx5.android.input.emoji

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import timber.log.Timber

object EmojiRichContentCommitter {

    enum class Result {
        /** 已通过 commitContent 富媒体上屏。 */
        COMMITTED,
        /** 目标编辑器不支持富媒体，已把图像写入系统剪贴板，可长按粘贴。 */
        COPIED_TO_CLIPBOARD,
        /** 富媒体与剪贴板均不可用，已按偏好降级为文本。 */
        FALLBACK_TEXT,
        /** 无输入连接、无 URI 或所有降级被禁用，未提交。 */
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
     * 提交一个图像表情。优先 Rich Content 上屏；不支持或失败时写回系统
     * 剪贴板（通用输出）；最后按偏好降级为文本短码。
     *
     * @param uri               FileProvider 生成、可临时授权的 content URI
     * @param mime              image 子类型，与 ClipDescription 一致
     * @param label             内容描述
     * @param fallbackText      文本降级内容；null/空白表示禁用文本降级
     * @param clipboardFallback 目标不支持富媒体时是否写回系统剪贴板
     * @param targetPackage     当前编辑器包名（用于授予 URI 读权限）
     */
    fun commit(
        context: Context,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        uri: android.net.Uri,
        mime: String,
        label: String,
        fallbackText: String?,
        clipboardFallback: Boolean,
        targetPackage: String?
    ): Result {
        // 1) 富媒体直投：目标声明了 image 能力
        if (ic != null && supports(editorInfo, mime)) {
            var info: InputContentInfoCompat? = null
            val ok = runCatching {
                val description = ClipDescription(label, arrayOf(mime))
                // linkUri 传 null：图像资产完全本地，不附带任何外部链接
                val content = InputContentInfoCompat(uri, description, null)
                info = content
                targetPackage?.let { pkg ->
                    runCatching {
                        context.grantUriPermission(
                            pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
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
        }

        // 2) 通用输出：把图像写回系统剪贴板，用户在任意输入框长按粘贴
        if (clipboardFallback && copyImageToClipboard(context, uri, mime, label, targetPackage)) {
            return Result.COPIED_TO_CLIPBOARD
        }

        // 3) 最后兜底：可选文本短码（默认关闭，避免输出乱码）
        return if (ic != null) fallback(ic, fallbackText) else Result.UNAVAILABLE
    }

    /**
     * 把图像 content URI 写回系统剪贴板。使用 ClipData.newUri 声明 image 类
     * MIME，并显式授予目标应用临时读权限；FileProvider 已配置
     * grantUriPermissions=true，粘贴方即可读取原图。异常安全。
     */
    private fun copyImageToClipboard(
        context: Context,
        uri: android.net.Uri,
        mime: String,
        label: String,
        targetPackage: String?
    ): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        // 声明 image 能力，让接收方剪贴板/编辑器识别这是一张图片而非文本
        val clip = ClipData(ClipDescription(label, arrayOf(mime)), ClipData.Item(uri))
        targetPackage?.let { pkg ->
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        cm.setPrimaryClip(clip)
        true
    }.onFailure { Timber.w(it, "EmojiRichContent: clipboard copy failed") }.getOrDefault(false)

    private fun fallback(ic: InputConnection, fallbackText: String?): Result {
        if (fallbackText.isNullOrBlank()) return Result.UNAVAILABLE
        return if (runCatching { ic.commitText(fallbackText, 1); true }.getOrDefault(false)) {
            Result.FALLBACK_TEXT
        } else {
            Result.UNAVAILABLE
        }
    }
}
