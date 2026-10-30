/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.31：大文本导入 —— 剪贴板历史「无上限」的输入通道。
 *
 * 为什么需要它：系统剪贴板（Binder ~1MB）传不进大文本，
 * 用户复制 100MB 文字的真实路径是「文件 → 导入 → 剪贴板历史 → 粘贴」。
 * 导入全程流式（不读进内存），支持任意大小（100MB+）。
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.ui.main.MbmActivityUi.fitInsets

class ClipboardImportActivity : ComponentActivity() {

    private lateinit var statusView: TextView

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(COLOR_BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "导入文本到剪贴板"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_FG)
        })
        root.addView(TextView(this).apply {
            text = "选择 .txt 等文本文件，流式导入剪贴板历史，大小无上限（100MB+ 亦可）。" +
                "导入后在输入法剪贴板面板点击条目即可完整粘贴原文。"
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(2), 0, dp(12))
        })

        root.addView(MaterialButton(this).apply {
            text = "选择文本文件"
            setBackgroundColor(COLOR_ACCENT)
            setTextColor(COLOR_BG)
            setOnClickListener {
                pickFile.launch(arrayOf("text/*", "application/octet-stream"))
            }
        })

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(12), 0, 0)
            text = "尚未选择文件。"
        }
        root.addView(statusView)

        setContentView(scroll)
        fitInsets(this, scroll)
    }

    private fun importFile(uri: Uri) {
        statusView.text = "导入中…（流式写入，不占内存）"
        lifecycleScope.launch {
            val bytes = ClipboardManager.importFromUri(uri)
            if (bytes == null) {
                statusView.text = "导入失败：无法读取该文件（详见日志）。"
                Toast.makeText(this@ClipboardImportActivity, "导入失败", Toast.LENGTH_SHORT).show()
            } else {
                val mb = bytes / 1048576.0
                val size = if (bytes >= 1048576) "%.1f MB".format(mb) else "$bytes 字节"
                statusView.text = "已导入 $size（SHA-256 去重已生效；打开输入法剪贴板面板即可粘贴全文）"
                Toast.makeText(this@ClipboardImportActivity, "已导入 $size", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_BG = 0xFF0E1116.toInt()
        private const val COLOR_FG = 0xFFE6EDF3.toInt()
        private const val COLOR_DIM = 0xFF7D8590.toInt()
        private const val COLOR_ACCENT = 0xFF58A6FF.toInt()

        fun launch(context: Context) {
            context.startActivity(Intent(context, ClipboardImportActivity::class.java))
        }
    }
}
