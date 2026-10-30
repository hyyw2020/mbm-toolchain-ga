/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.password

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.MbmActivityUi.fitInsets

/**
 * MBM 高强度密码生成器 —— 与《密码生成器原型.html》1:1 对应的原生界面。
 *
 * 所有随机与强度计算在本地完成（[PasswordGenerator] 使用 SecureRandom），不联网。
 */
class PasswordGeneratorActivity : Activity() {

    private lateinit var root: LinearLayout

    // 字符集
    private lateinit var swUpper: SwitchMaterial
    private lateinit var swLower: SwitchMaterial
    private lateinit var swDigits: SwitchMaterial
    private lateinit var swSymbols: SwitchMaterial

    // 长度与数量
    private lateinit var etLength: TextInputEditText
    private lateinit var etCount: TextInputEditText

    // 每类最少
    private lateinit var etMinUpper: TextInputEditText
    private lateinit var etMinLower: TextInputEditText
    private lateinit var etMinDigits: TextInputEditText
    private lateinit var etMinSymbols: TextInputEditText

    // 排除规则
    private lateinit var swExcludeConfusing: SwitchMaterial
    private lateinit var swExcludeWeak: SwitchMaterial
    private lateinit var swNoRepeat: SwitchMaterial
    private lateinit var swNoSequential: SwitchMaterial
    private lateinit var etExcludeCustom: TextInputEditText

    // 自定义符号
    private lateinit var etCustomSymbols: TextInputEditText
    private lateinit var swCustomOnly: SwitchMaterial

    private lateinit var swCheckStrength: SwitchMaterial

    private lateinit var warn: TextView
    private lateinit var stat: TextView
    private lateinit var outContainer: LinearLayout
    private lateinit var toFill: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        syncEnabledStates()
    }

    // ---------- UI 构建 ----------

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        root.addView(title("MBM 密码生成器"))
        root.addView(subtitle("基于 SecureRandom 无偏采样，全部计算在本地完成"))

        warn = TextView(this).apply {
            setTextColor(COLOR_WARN)
            textSize = 13f
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(warn)
        root.addView(divider())

        // 字符集
        root.addView(sectionTitle("字符集"))
        swUpper = root.addSwitch("大写字母", "A-Z", true)
        swLower = root.addSwitch("小写字母", "a-z", true)
        swDigits = root.addSwitch("数字", "0-9", true)
        swSymbols = root.addSwitch("特殊符号", "!@#\$…", true)

        // 长度与数量
        root.addView(divider())
        root.addView(sectionTitle("长度与数量"))
        etLength = root.addNumberField("密码长度", "4 - 128", DEFAULT_LENGTH)
        etCount = root.addNumberField("生成数量", "1 - 100", DEFAULT_COUNT)

        // 每类最少
        root.addView(divider())
        root.addView(sectionTitle("每类字符最少数量"))
        etMinUpper = root.addNumberField("大写最少个数", "", 1)
        etMinLower = root.addNumberField("小写最少个数", "", 1)
        etMinDigits = root.addNumberField("数字最少个数", "", 1)
        etMinSymbols = root.addNumberField("符号最少个数", "", 1)

        // 排除规则
        root.addView(divider())
        root.addView(sectionTitle("排除规则"))
        swExcludeConfusing = root.addSwitch("排除易混淆字符", "0 O 1 l I |", false)
        swExcludeWeak = root.addSwitch("排除常见弱密码", "password / 123456", true)
        swNoRepeat = root.addSwitch("不允许重复字符", "", false)
        swNoSequential = root.addSwitch("不允许连续序列", "abc / 123", false)
        etExcludeCustom = root.addTextField("额外排除字符", "如 \"'`")

        // 自定义符号
        root.addView(divider())
        root.addView(sectionTitle("自定义符号"))
        etCustomSymbols = root.addTextField("追加自定义符号", "如 ~€§")
        swCustomOnly = root.addSwitch("只使用自定义符号", "", false)

        // 强度
        root.addView(divider())
        root.addView(sectionTitle("生成与评估"))
        swCheckStrength = root.addSwitch("显示强度评估", "", true)

        // 按钮
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(MaterialButton(this).apply {
            text = "生成密码"
            setBackgroundColor(COLOR_ACCENT)
            setTextColor(COLOR_BG)
            layoutParams = linearParams(1f)
            setOnClickListener { generate() }
        })
        actions.addView(MaterialButton(this).apply {
            text = "恢复默认"
            layoutParams = linearParams(1f, startMargin = dp(10))
            setOnClickListener { resetToDefaults() }
        })
        root.addView(actions)

        stat = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(14), 0, dp(6))
            visibility = View.GONE
        }
        root.addView(stat)

        outContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(outContainer)

        // 隐私填充（把生成的密码直接填入当前输入框）
        toFill = MaterialButton(this).apply {
            text = "填入当前输入框"
            visibility = View.GONE
            setOnClickListener { commitToEditor(outContainer.tag as? String ?: "") }
        }
        root.addView(toFill)

        setContentView(scroll)
        fitInsets(this, scroll)
    }

    // ---------- 组件工厂 ----------

    private fun LinearLayout.addSwitch(label: String, hint: String, checked: Boolean): SwitchMaterial {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(9), dp(4), dp(9))
        }
        val textHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = linearParams(1f)
        }
        textHost.addView(TextView(context).apply {
            text = label
            setTextColor(COLOR_FG)
            textSize = 14f
        })
        if (hint.isNotEmpty()) {
            textHost.addView(TextView(context).apply {
                text = hint
                setTextColor(COLOR_DIM)
                textSize = 11f
            })
        }
        row.addView(textHost)
        val switch = SwitchMaterial(context).apply { isChecked = checked }
        row.addView(switch)
        addView(row)
        switch.setOnCheckedChangeListener { _, _ -> syncEnabledStates(); clearWarn() }
        return switch
    }

    private fun LinearLayout.addNumberField(label: String, hint: String, value: Int): TextInputEditText {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(COLOR_FG)
            textSize = 14f
            layoutParams = linearParams(1f)
        })
        val inputLayout = TextInputLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val edit = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        inputLayout.addView(edit)
        row.addView(inputLayout)
        if (hint.isNotEmpty()) {
            row.addView(TextView(context).apply {
                text = hint
                setTextColor(COLOR_DIM)
                textSize = 11f
                setPadding(dp(6), 0, 0, 0)
            })
        }
        addView(row)
        edit.tag = hint
        return edit
    }

    private fun LinearLayout.addTextField(label: String, hint: String): TextInputEditText {
        val inputLayout = TextInputLayout(context).apply {
            this.hint = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val edit = TextInputEditText(context).apply {
            setSingleLine(true)
            textSize = 13f
        }
        inputLayout.addView(edit)
        addView(inputLayout)
        edit.hint = hint
        return edit
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(COLOR_FG)
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_DIM)
        setPadding(0, dp(2), 0, dp(10))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(COLOR_DIM)
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.topMargin = dp(10) }
        setBackgroundColor(COLOR_BORDER)
    }

    // ---------- 逻辑 ----------

    private fun readConfig(): PasswordGenerator.Config {
        fun num(edit: TextInputEditText, fallback: Int) =
            edit.text?.toString()?.trim()?.toIntOrNull() ?: fallback

        return PasswordGenerator.Config(
            useUpper = swUpper.isChecked,
            useLower = swLower.isChecked,
            useDigits = swDigits.isChecked,
            useSymbols = swSymbols.isChecked,
            length = num(etLength, DEFAULT_LENGTH),
            count = num(etCount, DEFAULT_COUNT),
            minUpper = num(etMinUpper, 0),
            minLower = num(etMinLower, 0),
            minDigits = num(etMinDigits, 0),
            minSymbols = num(etMinSymbols, 0),
            excludeConfusing = swExcludeConfusing.isChecked,
            excludeWeak = swExcludeWeak.isChecked,
            noRepeat = swNoRepeat.isChecked,
            noSequential = swNoSequential.isChecked,
            excludeCustom = etExcludeCustom.text?.toString() ?: "",
            customSymbols = etCustomSymbols.text?.toString() ?: "",
            customSymbolsOnly = swCustomOnly.isChecked,
            checkStrength = swCheckStrength.isChecked
        )
    }

    private fun generate() {
        clearWarn()
        val cfg = readConfig()

        if (!listOf(cfg.useUpper, cfg.useLower, cfg.useDigits, cfg.useSymbols).any { it }) {
            swUpper.isChecked = true; swLower.isChecked = true
            swDigits.isChecked = true; swSymbols.isChecked = true
            showWarn("至少需要启用一类字符，已自动全部启用")
            return
        }
        if (cfg.length !in 4..128) {
            showWarn("密码长度需在 4 - 128 之间"); return
        }
        if (cfg.count !in 1..100) {
            showWarn("生成数量需在 1 - 100 之间"); return
        }
        if (cfg.customSymbolsOnly && cfg.customSymbols.isBlank()) {
            swCustomOnly.isChecked = false
            showWarn("未填写自定义符号，已忽略「只使用自定义符号」"); return
        }

        try {
            val result = PasswordGenerator.generate(cfg)
            clearWarn()
            render(result)
        } catch (e: Exception) {
            showWarn(e.message ?: "生成失败")
            outContainer.removeAllViews()
            stat.visibility = View.GONE
            toFill.visibility = View.GONE
        }
    }

    private fun render(result: PasswordGenerator.Result) {
        outContainer.removeAllViews()
        stat.visibility = View.VISIBLE
        stat.text = "字符池 ${result.poolSize} 个 · 共 ${result.entries.size} 个" +
            if (result.short) "（受约束限制，少于请求数量）" else ""

        result.entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBox()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
            }
            row.addView(TextView(this).apply {
                text = "${index + 1}"
                setTextColor(COLOR_DIM)
                textSize = 11f
            })
            row.addView(TextView(this).apply {
                text = entry.value
                setTextColor(COLOR_FG)
                typeface = Typeface.MONOSPACE
                textSize = 13f
                layoutParams = linearParams(1f, startMargin = dp(10))
            })
            if (entry.entropy != null) {
                row.addView(TextView(this).apply {
                    text = "${String.format("%.1f", entry.entropy)} bit"
                    setTextColor(COLOR_DIM)
                    textSize = 11f
                })
                row.addView(levelTag(entry.level ?: ""))
            }
            row.addView(MaterialButton(this).apply {
                text = "复制"
                setOnClickListener { copy(entry.value) }
            })
            // N31：点行不再把明文密码写进系统剪贴板（会被剪贴板历史/其他应用读取），
            // 改为直接提交到当前输入框（走 IME 桥接，不落剪贴板、不留学习记录）。
            // 明确的「复制」按钮仍提供显式复制能力。
            row.setOnClickListener { commitToEditor(entry.value) }
            outContainer.addView(row)
        }

        result.entries.firstOrNull()?.value?.let {
            outContainer.tag = it
            toFill.visibility = View.VISIBLE
        }
    }

    private fun levelTag(level: String) = TextView(this).apply {
        text = level
        textSize = 10f
        setTypeface(null, Typeface.BOLD)
        val c = when (level) {
            "弱" -> COLOR_ERR
            "中" -> COLOR_WARN
            "强" -> COLOR_OK
            else -> COLOR_PURPLE
        }
        setTextColor(c)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        setBackgroundColor(withAlpha(c, 0.15f))
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = dp(6); it.marginEnd = dp(6) }
        layoutParams = p
    }

    private fun copy(value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MBM Password", value))
        Toast.makeText(this, R.string.password_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * 将密码直接提交给当前输入框（走输入法服务桥接，不落剪贴板、不留学习记录）
     */
    private fun commitToEditor(value: String) {
        if (value.isEmpty()) return
        val ok = PasswordFillBridge.commit(value)
        Toast.makeText(
            this,
            if (ok) R.string.password_filled else R.string.password_fill_hint,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun syncEnabledStates() {
        fun setEnabled(edit: TextInputEditText, on: Boolean) {
            edit.isEnabled = on
            edit.alpha = if (on) 1f else 0.35f
        }
        setEnabled(etMinUpper, swUpper.isChecked)
        setEnabled(etMinLower, swLower.isChecked)
        setEnabled(etMinDigits, swDigits.isChecked)
        setEnabled(etMinSymbols, swSymbols.isChecked)
        setEnabled(etCustomSymbols, swSymbols.isChecked)

        // 每类最少不能超过总长度
        val length = etLength.text?.toString()?.toIntOrNull() ?: DEFAULT_LENGTH
        val pairs = listOf(
            etMinUpper to swUpper, etMinLower to swLower,
            etMinDigits to swDigits, etMinSymbols to swSymbols
        )
        pairs.forEach { (edit, sw) ->
            if (!sw.isChecked) return@forEach
            val v = edit.text?.toString()?.toIntOrNull() ?: 0
            if (v > length) edit.setText(length.toString())
        }
    }

    private fun resetToDefaults() {
        swUpper.isChecked = true
        swLower.isChecked = true
        swDigits.isChecked = true
        swSymbols.isChecked = true
        etLength.setText(DEFAULT_LENGTH.toString())
        etCount.setText(DEFAULT_COUNT.toString())
        etMinUpper.setText("1")
        etMinLower.setText("1")
        etMinDigits.setText("1")
        etMinSymbols.setText("1")
        swExcludeConfusing.isChecked = false
        swExcludeWeak.isChecked = true
        swNoRepeat.isChecked = false
        swNoSequential.isChecked = false
        etExcludeCustom.setText("")
        etCustomSymbols.setText("")
        swCustomOnly.isChecked = false
        swCheckStrength.isChecked = true
        syncEnabledStates()
        clearWarn()
        outContainer.removeAllViews()
        stat.visibility = View.GONE
        toFill.visibility = View.GONE
        Toast.makeText(this, R.string.password_reset_default, Toast.LENGTH_SHORT).show()
    }

    private fun showWarn(msg: String) {
        warn.text = "⚠ $msg"
        warn.visibility = View.VISIBLE
    }

    private fun clearWarn() {
        warn.visibility = View.GONE
    }

    // ---------- 工具 ----------

    private fun roundedBox() = GradientDrawable().apply {
        setColor(COLOR_PANEL)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun linearParams(weight: Float, startMargin: Int = 0, topMargin: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).also {
            it.marginStart = startMargin
            it.topMargin = topMargin
        }

    private fun withAlpha(color: Int, ratio: Float): Int {
        val a = (255 * ratio).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    companion object {
        /** MBM v1.0.36：当前前台实例引用——自测 settings 层点击进入后经它返回 */
        @Volatile
        var current: PasswordGeneratorActivity? = null
        const val DEFAULT_LENGTH = 16
        const val DEFAULT_COUNT = 5

        private const val COLOR_BG = 0xFF0E1116.toInt()
        private const val COLOR_PANEL = 0xFF161B22.toInt()
        private const val COLOR_BORDER = 0xFF2B313A.toInt()
        private const val COLOR_FG = 0xFFE6EDF3.toInt()
        private const val COLOR_DIM = 0xFF7D8590.toInt()
        private const val COLOR_ACCENT = 0xFF58A6FF.toInt()
        private const val COLOR_OK = 0xFF3FB950.toInt()
        private const val COLOR_WARN = 0xFFD29922.toInt()
        private const val COLOR_ERR = 0xFFF85149.toInt()
        private const val COLOR_PURPLE = 0xFFBC8CFF.toInt()

        fun launch(context: Context) {
            context.startActivity(Intent(context, PasswordGeneratorActivity::class.java).apply {
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
}
