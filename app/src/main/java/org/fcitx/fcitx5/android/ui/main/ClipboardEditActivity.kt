/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.databinding.ActivityClipboardEditBinding
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.str

class ClipboardEditActivity : Activity() {

    private val scope: CoroutineScope = MainScope()

    private lateinit var editText: EditText

    private lateinit var binding: ActivityClipboardEditBinding

    private var entryId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes.gravity = Gravity.TOP
        binding = ActivityClipboardEditBinding.inflate(layoutInflater).apply {
            editText = clipboardEditText
            clipboardEditCancel.setOnClickListener { finish() }
            clipboardEditOk.setOnClickListener { finishEditing() }
            clipboardEditCopy.setOnClickListener { finishEditing(copy = true) }
        }
        setContentView(binding.root)
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        processIntent(intent)
    }

    private fun finishEditing(copy: Boolean = false) {
        val str = editText.str
        scope.launch {
            ClipboardManager.updateText(entryId, str)
            if (copy) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("", str))
            }
        }
        finish()
    }

    /**
     * 编辑框可承载的正文上限（字符数）。超过则无法在 EditText 内联编辑，
     * 仅放预览并禁用破坏性保存（见 U2 修复说明）。
     */
    private val EDIT_LIMIT_CHARS = 1_000_000

    private suspend fun setEntry(entry: ClipboardEntry) {
        entryId = entry.id
        if (!entry.isBlob) {
            editText.setText(entry.text)
            return
        }
        // U2 修复：blob 条目必须加载**全文**进编辑框，而不是 256 字符预览——
        // 否则「打开→什么都没改→确定」会触发 ClipboardManager.updateText 把 100MB
        // 原文降级为内联预览并删除 blob（不可撤销）。
        val stream = ClipboardManager.readFullText(entry) ?: run {
            editText.setText(entry.text)
            return
        }
        stream.buffered().use { s ->
            val (text, truncated) = readCapped(s, EDIT_LIMIT_CHARS)
            if (truncated) {
                // 全文过大：仅放预览并禁用「确定/复制」，避免把超大文本塞进 EditText，
                // 也避免误删原文（用户可用「分享」获取全文）
                editText.setText(entry.text)
                binding.clipboardEditOk.isEnabled = false
                binding.clipboardEditCopy.isEnabled = false
                Toast.makeText(
                    this,
                    "内容过大，无法内联编辑，请用「分享」获取全文",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                editText.setText(text)
            }
        }
    }

    /** 限长读取流文本，返回（文本, 是否因超限截断）。用于避免把 100MB 整段读进内存。 */
    private fun readCapped(stream: InputStream, limitChars: Int): Pair<String, Boolean> {
        val reader = stream.bufferedReader()
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var read: Int
        var total = 0
        while (reader.read(buf).also { read = it } != -1) {
            sb.append(buf, 0, read)
            total += read
            if (total >= limitChars) return sb.toString() to true
        }
        return sb.toString() to false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        scope.launch {
            intent.run {
                if (getBooleanExtra(LAST_ENTRY, false)) {
                    ClipboardManager.lastEntry
                } else {
                    ClipboardManager.get(getIntExtra(ENTRY_ID, -1))
                }
            }?.let { setEntry(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ENTRY_ID = "id"
        const val LAST_ENTRY = "last_entry"
    }
}
