/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 用户自定义图像表情包导入界面。
 *
 * 通过 Android Storage Access Framework 让用户自行选择图像资产或 ZIP 压缩包，
 * 复制到应用私有目录并建立本地清单。本界面与输入法本体均**不携带、不下载**
 * 任何第三方专有表情素材；全部资产由使用者在运行时提供并对来源负责。
 *
 * 技术依据：Android SAFF / ActivityResultContracts.OpenMultipleDocuments、
 * AndroidX Core（Apache-2.0）。许可标识遵循 SPDX。
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.emoji.CustomEmojiRepository
import org.fcitx.fcitx5.android.ui.main.MbmActivityUi.fitInsets

class CustomEmojiImportActivity : ComponentActivity() {

    private lateinit var statusView: TextView

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        // SAF 返回的 content:// 读权限是临时的，必须立即消费（复制到私有目录），
        // 不能持久化 URI 后再读。
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { CustomEmojiRepository.importFromUris(this@CustomEmojiImportActivity, uris) }
                    .getOrDefault(0)
            }
            Toast.makeText(
                this@CustomEmojiImportActivity,
                if (count > 0) getString(R.string.custom_emoji_import_result, count)
                else getString(R.string.custom_emoji_import_failed),
                Toast.LENGTH_SHORT
            ).show()
            updateStatus()
        }
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
            text = getString(R.string.custom_emoji_import_title)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_FG)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.custom_emoji_import_hint)
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(4), 0, dp(14))
        })

        root.addView(MaterialButton(this).apply {
            text = getString(R.string.custom_emoji_pick_files)
            setBackgroundColor(COLOR_ACCENT)
            setTextColor(COLOR_BG)
            setOnClickListener {
                pickFiles.launch(
                    arrayOf(
                        "image/png", "image/jpeg", "image/webp", "image/gif",
                        "image/bmp", "image/heic", "image/heif", "image/avif",
                        "application/zip", "application/octet-stream"
                    )
                )
            }
        })

        root.addView(MaterialButton(this).apply {
            text = getString(R.string.custom_emoji_clear)
            setOnClickListener { confirmClear() }
        })

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(statusView)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
            setBackgroundColor(COLOR_BORDER)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.custom_emoji_import_summary)
            textSize = 11f
            setTextColor(COLOR_DIM)
        })

        setContentView(scroll)
        fitInsets(this, scroll)
        updateStatus()
    }

    private fun confirmClear() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.custom_emoji_clear)
            .setMessage(R.string.custom_emoji_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    CustomEmojiRepository.clearAll()
                    withContext(Dispatchers.Main) { updateStatus() }
                }
                Toast.makeText(
                    this@CustomEmojiImportActivity,
                    R.string.custom_emoji_cleared, Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val packs = CustomEmojiRepository.listPacks()
            val count = CustomEmojiRepository.itemCount()
            val text = if (count == 0) getString(R.string.custom_emoji_empty)
            else getString(R.string.custom_emoji_pack_count, packs.size, count)
            withContext(Dispatchers.Main) { statusView.text = text }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val COLOR_BG = 0xFF0E1116.toInt()
        private val COLOR_BORDER = 0xFF2B313A.toInt()
        private val COLOR_FG = 0xFFE6EDF3.toInt()
        private val COLOR_DIM = 0xFF7D8590.toInt()
        private val COLOR_ACCENT = 0xFF58A6FF.toInt()

        fun launch(context: Context) {
            context.startActivity(Intent(context, CustomEmojiImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
