/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import org.fcitx.fcitx5.android.ui.main.MbmActivityUi.fitInsets
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.rules.RuleFormat
import org.fcitx.fcitx5.android.data.rules.RuleImporter

/**
 * 统一「规则与词库」导入 —— MBM 全 app 唯一的规则导入入口。
 *
 * 不按应用分设入口：fcitx5 / Rime / 第三方 Rime 系方案 / 自定义词表
 * 在同一列表里按「格式 + 来源」展示，用户勾选后统一安装，
 * 并在界面上明确展示每类文件的安装位置。
 */
class RuleImportActivity : ComponentActivity() {

    private val pending = mutableListOf<RuleImporter.Item>()
    private lateinit var listContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var installButton: MaterialButton

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val analyzed = RuleImporter.analyze(this@RuleImportActivity, uris)
            pending.clear()
            pending += analyzed
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "规则与词库导入"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_FG)
        })
        root.addView(TextView(this).apply {
            text = "唯一入口 · 自动识别格式 · 兼容 fcitx5 / Rime / 第三方 Rime 系方案 / 自定义词表"
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(2), 0, dp(10))
        })

        root.addView(locationCard())
        root.addView(divider())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this).apply {
            text = "选择文件"
            setBackgroundColor(COLOR_ACCENT)
            setTextColor(COLOR_BG)
            layoutParams = linearParams(1f)
            setOnClickListener {
                pickFiles.launch(
                    arrayOf(
                        "application/zip", "application/octet-stream",
                        "text/plain", "text/x-yaml", "application/yaml"
                    )
                )
            }
        })
        actions.addView(MaterialButton(this).apply {
            text = "全选 / 全不选"
            layoutParams = linearParams(1f, startMargin = dp(8))
            setOnClickListener { toggleSelectAll() }
        })
        root.addView(actions)

        installButton = MaterialButton(this).apply {
            text = "安装选中项"
            visibility = View.GONE
            setOnClickListener { installSelected() }
        }
        root.addView(installButton)

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(statusView)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(scroll)
        fitInsets(this, scroll)
        updateStatus()
    }

    /** 明确展示各类规则的安装位置（用户可见） */
    private fun locationCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(COLOR_PANEL)
        addView(TextView(context).apply {
            text = "安装位置"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_DIM)
        })
        listOf(
            RuleFormat.InstallTarget.RIME to RuleImporter.rimeUserDir().absolutePath,
            RuleFormat.InstallTarget.PINYIN to RuleImporter.pinyinDir().absolutePath
        ).forEach { (target, path) ->
            addView(TextView(context).apply {
                text = "• ${labelOf(target)}：$path"
                textSize = 11f
                setTextColor(COLOR_FG)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun labelOf(target: RuleFormat.InstallTarget) = when (target) {
        RuleFormat.InstallTarget.RIME -> "Rime 方案 / 词典"
        RuleFormat.InstallTarget.PINYIN -> "拼音词库（libime）"
        RuleFormat.InstallTarget.TABLE -> "码表输入法"
        RuleFormat.InstallTarget.UNKNOWN -> "未识别"
    }

    private fun render() {
        listContainer.removeAllViews()
        installButton.visibility = if (pending.any { it.selected }) View.VISIBLE else View.GONE
        pending.forEachIndexed { index, item ->
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
                ).also { it.topMargin = dp(8) }
            }
            val check = CheckBox(this).apply {
                isChecked = item.selected
                setOnCheckedChangeListener { _, checked ->
                    pending[index] = item.copy(selected = checked)
                    installButton.visibility =
                        if (pending.any { it.selected }) View.VISIBLE else View.GONE
                }
            }
            row.addView(check)

            val textHost = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = linearParams(1f, startMargin = dp(6))
            }
            textHost.addView(TextView(this).apply {
                text = item.fileName
                setTextColor(COLOR_FG)
                textSize = 13f
                setSingleLine(true)
            })
            textHost.addView(TextView(this).apply {
                text = "${item.format.displayName} · 来源：${item.format.sourceTag} · ${item.detail}"
                setTextColor(COLOR_DIM)
                textSize = 11f
            })
            textHost.addView(TextView(this).apply {
                text = "安装至：${labelOf(item.format.target)}"
                setTextColor(COLOR_ACCENT)
                textSize = 11f
            })
            row.addView(textHost)
            listContainer.addView(row)
        }
        updateStatus()
    }

    private fun toggleSelectAll() {
        val target = pending.any { !it.selected }
        pending.indices.forEach { pending[it] = pending[it].copy(selected = target) }
        render()
    }

    private fun installSelected() {
        val selected = pending.filter { it.selected }
        if (selected.isEmpty()) return
        installButton.isEnabled = false
        installButton.text = "安装中…"
        lifecycleScope.launch {
            val outcomes = RuleImporter.install(this@RuleImportActivity, selected)
            val ok = outcomes.count { it.ok }
            val failed = outcomes - outcomes.filter { it.ok }.toSet()
            statusView.text = buildString {
                appendLine("成功 $ok / ${outcomes.size}")
                failed.forEach { append("✗ ${it.item.fileName}：${it.message}\n") }
                if (ok > 0) append("提示：重启输入法以让 Rime 完成部署")
            }
            installButton.text = "安装选中项"
            installButton.isEnabled = true
            Toast.makeText(
                this@RuleImportActivity,
                getString(R.string.rule_import_result, ok, outcomes.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateStatus() {
        if (pending.isEmpty()) {
            statusView.text = "尚未选择文件。支持 zip / yaml / txt / dict / scel / conf。"
        } else {
            statusView.text = "已识别 ${pending.size} 项，可勾选后安装。"
        }
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.topMargin = dp(10); it.bottomMargin = dp(10) }
        setBackgroundColor(COLOR_BORDER)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun linearParams(weight: Float, startMargin: Int = 0, topMargin: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).also {
            it.marginStart = startMargin
            it.topMargin = topMargin
        }

    companion object {
        /** MBM v1.0.36：当前前台实例引用——自测 settings 层点击进入后经它返回 */
        @Volatile
        var current: RuleImportActivity? = null
        private const val COLOR_BG = 0xFF0E1116.toInt()
        private const val COLOR_PANEL = 0xFF161B22.toInt()
        private const val COLOR_BORDER = 0xFF2B313A.toInt()
        private const val COLOR_FG = 0xFFE6EDF3.toInt()
        private const val COLOR_DIM = 0xFF7D8590.toInt()
        private const val COLOR_ACCENT = 0xFF58A6FF.toInt()

        fun launch(context: Context) {
            context.startActivity(Intent(context, RuleImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Suppress("unused")
    private fun unusedInputTypeGuard() = InputType.TYPE_CLASS_TEXT
}
