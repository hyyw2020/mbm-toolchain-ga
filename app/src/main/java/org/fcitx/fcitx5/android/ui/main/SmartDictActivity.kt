/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import org.fcitx.fcitx5.android.ui.main.MbmActivityUi.fitInsets
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.smartdict.SmartDictManager

/**
 * 实验性模式：离线智能词库系统。
 *
 * 支持单选、全选、自定义（多选）选择、删除；可一键跳转统一导入入口。
 */
class SmartDictActivity : ComponentActivity() {

    private var entries: List<SmartDictManager.DictEntry> = emptyList()
    private val selection = mutableSetOf<Int>()
    private lateinit var listContainer: LinearLayout
    private lateinit var summary: TextView
    private lateinit var smartWords: TextView
    private lateinit var deleteButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        reload()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(COLOR_BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "离线智能词库"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_FG)
        })
        root.addView(TextView(this).apply {
            text = "实验性模式 · 完全离线 · 本地统计生成智能候选词"
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(2), 0, dp(10))
        })

        summary = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
        }
        root.addView(summary)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "全选" to { selectAll() },
            "全不选" to { clearSelection() },
            "刷新" to { reload() }
        ).forEach { (label, action) ->
            actions.addView(MaterialButton(this).apply {
                text = label
                layoutParams = linearParams(1f)
                setOnClickListener { action() }
            })
        }
        root.addView(actions)

        deleteButton = MaterialButton(this).apply {
            text = "删除选中项"
            visibility = View.GONE
            setOnClickListener { deleteSelected() }
        }
        root.addView(deleteButton)

        root.addView(TextView(this).apply {
            text = "智能候选词（本地统计）"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_DIM)
            setPadding(0, dp(14), 0, dp(4))
        })
        smartWords = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_FG)
            setBackgroundColor(COLOR_PANEL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(smartWords)

        root.addView(TextView(this).apply {
            text = "已安装词库"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_DIM)
            setPadding(0, dp(14), 0, dp(4))
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(scroll)
        fitInsets(this, scroll)
    }

    private fun reload() {
        lifecycleScope.launch {
            entries = withContext(Dispatchers.IO) { SmartDictManager.listAll() }
            val words = withContext(Dispatchers.IO) { SmartDictManager.topWords() }
            render(words)
        }
    }

    private fun render(words: List<Pair<String, Int>>) {
        listContainer.removeAllViews()
        summary.text = "共 ${entries.size} 项词库/方案记录"
        smartWords.text = if (words.isEmpty()) "暂无本地统计（仅在非密码输入下记录）"
        else words.joinToString("  ") { (w, c) -> "$w×$c" }

        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(COLOR_PANEL)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                // 不能用 linearParams(1f)：listContainer 是垂直 LinearLayout，width=0 会让整行不可见
                // （与 Recall/密码界面同源 bug）。垂直列表子项宽度必须 MATCH_PARENT。
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(6) }
            }
            val check = CheckBox(this).apply { isChecked = selection.contains(index) }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) selection += index else selection -= index
                deleteButton.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
            }
            row.addView(check)

            val host = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = linearParams(1f, startMargin = dp(6))
            }
            host.addView(TextView(this).apply {
                text = entry.name
                setTextColor(COLOR_FG)
                textSize = 13f
                setSingleLine(true)
            })
            host.addView(TextView(this).apply {
                text = "${entry.kind.display} · ${"%.1f".format(entry.sizeBytes / 1024f / 1024f)} MB"
                setTextColor(COLOR_DIM)
                textSize = 11f
            })
            row.addView(host)
            listContainer.addView(row)
        }
        deleteButton.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun selectAll() {
        selection.clear()
        selection += entries.indices
        render(SmartDictManager.topWords())
        deleteButton.visibility = View.VISIBLE
    }

    private fun clearSelection() {
        selection.clear()
        render(SmartDictManager.topWords())
    }

    private fun deleteSelected() {
        val targets = selection.mapNotNull { entries.getOrNull(it) }
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { SmartDictManager.delete(targets) }
            selection.clear()
            reload()
            Toast.makeText(
                this@SmartDictActivity,
                getString(R.string.smart_dict_deleted, n),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun linearParams(weight: Float, startMargin: Int = 0, topMargin: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).also {
            it.marginStart = startMargin
            it.topMargin = topMargin
        }

    companion object {
        private const val COLOR_BG = 0xFF0E1116.toInt()
        private const val COLOR_PANEL = 0xFF161B22.toInt()
        private const val COLOR_FG = 0xFFE6EDF3.toInt()
        private const val COLOR_DIM = 0xFF7D8590.toInt()

        fun launch(context: Context) {
            context.startActivity(Intent(context, SmartDictActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
