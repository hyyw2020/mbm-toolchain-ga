/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 *
 * 写回系统剪贴板的统一通道：小文本原路，大文本走文件 URI 并如实反馈。
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import androidx.core.content.FileProvider
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber
import java.io.File

/**
 * MBM v1.0.77：修复「写回系统剪贴板超限被吞掉、却仍提示已复制」。
 *
 * 旧实现（拆分选词的「复制」、编辑页的「保存并复制」）都是直接
 * `setPrimaryClip(ClipData.newPlainText(...全文...))`。系统剪贴板走 Binder
 * 事务，约 1MB 上限；文本一大就抛 `TransactionTooLargeException`。旧代码用
 * `runCatching {}` 吞掉异常后**照样弹「已复制」**，用户拿不到内容却以为成功，
 * 是典型的静默失败 + 误导性反馈。
 *
 * 新策略（[ClipboardCapacityPolicy.copyPlan] 按 UTF-16 码元计费判定）：
 *  - 安全范围 → `newPlainText` 原路，行为零变化；
 *  - 超限 → 把完整文本写入 cacheDir/clipcopy 下的临时文件，以
 *    `ClipData.newUri` + `text/plain` 交给系统剪贴板（FileProvider 读授权），
 *    并返回 [Result.AsFile] 由调用方**如实告知**用户「已以文件形式放入剪贴板」；
 *  - 任一路径失败 → [Result.Failed]，绝不谎报成功。
 *
 * 关键：无论走哪条路径，调用方都应把同一份全文登记进剪贴板历史
 * （[ClipboardManager.addText] 无上限），用户随后可从面板用分块粘贴把
 * **完整原文**上屏到任意输入框 —— 这才是绕开平台 Binder 边界的可靠通道。
 *
 * 隐私：临时文件与既有的「分享」目录同属应用私有 cacheDir，写入前按最近
 * 保留策略清理旧文件；不联网、不上传、不写入外部存储。
 */
object SystemClipboardCopy {

    sealed class Result {
        /** 已作为纯文本完整放入系统剪贴板。 */
        object Copied : Result()

        /** 文本过大，已以 FileProvider URI 形式放入系统剪贴板（可粘贴到支持 URI 的应用）。 */
        object AsFile : Result()

        /** 写入失败，调用方必须如实提示。 */
        data class Failed(val error: Throwable) : Result()
    }

    private const val DIR_NAME = "clipcopy"
    private const val KEEP_RECENT = 5

    fun copy(context: Context, text: String): Result = runCatching {
        val cm = context.clipboardManager
        when (ClipboardCapacityPolicy.copyPlan(text)) {
            ClipboardCapacityPolicy.CopyPlan.PLAIN -> {
                cm.setPrimaryClip(ClipData.newPlainText(null, text))
                Result.Copied
            }
            ClipboardCapacityPolicy.CopyPlan.FILE_URI -> {
                val file = writeFile(context, text)
                val uri = FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", file
                )
                val item = ClipData.Item(uri)
                val clip = ClipData(
                    ClipDescription("MBM large text", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)),
                    item
                )
                cm.setPrimaryClip(clip)
                Result.AsFile
            }
        }
    }.getOrElse {
        Timber.w(it, "SystemClipboardCopy: setPrimaryClip failed (len=%d)", text.length)
        Result.Failed(it)
    }

    /**
     * 写临时文件。保留最近 [KEEP_RECENT] 个文件，避免刚放上系统剪贴板的 URI
     * 立刻被下一次复制清理掉；也不会让 cacheDir 无限增长。
     */
    private fun writeFile(context: Context, text: String): File {
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        dir.listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_RECENT - 1)
            ?.forEach { it.delete() }
        val f = File(dir, "bigtext_${System.nanoTime()}_${text.length}.txt")
        f.bufferedWriter().use { it.write(text) }
        return f
    }
}
