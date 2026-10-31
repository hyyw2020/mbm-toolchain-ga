/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.39：全局计算器候选条（跨窗口常驻组件，宿主为 InputView，位于键盘区上方）。
 *
 * 两种工作模式（互斥）：
 *  1. 缓冲模式（数字键盘）：TYPE_CLASS_NUMBER 且非密码的字段上，KeyboardWindow 把
 *     数字/运算符按键拦截进本地缓冲，缓冲实时渲染在本条；空格/换行/切布局先把
 *     缓冲原文上屏再执行原动作。
 *  2. 光标前文本模式（任意键盘、任意窗口，含符号/表情 PickerWindow 与 QWERTY）：
 *     监听 onSelectionUpdate，从输入框光标前文本提取尾部算式，求值后给出
 *     【结果】【算式=结果】两个候选；点选按 InputConnection 原子地
 *     「删除算式 + 提交替换文本」，与当前哪个键盘窗口在前台无关。
 *
 * 硬约束：
 *  - 拼音预编辑非空时绝不干预 InputConnection（preeditEmpty 门控），避免与引擎抢写；
 *  - 密码类字段（文本密码/数字密码）不启用；
 *  - 求值一律经 CalcEngine（仅完整合法算式才出候选，保证【】里必须正确）；
 *  - 全部本地计算、不联网、无开关（全局默认开启）。
 */
package org.fcitx.fcitx5.android.input.calc

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.CalcEngine
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import timber.log.Timber

class CalcCandidateComponent :
    UniqueViewComponent<CalcCandidateComponent, LinearLayout>(), InputBroadcastReceiver {

    companion object {
        /** 允许出现在算式尾串中的字符（从光标处向前扫描）。 */
        private const val EXPR_CHARS = "0123456789.+-*/×÷%() \t\u3000"

        private val TEXT_PASSWORD_VARIATIONS = intArrayOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
    }

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()

    /** 缓冲模式（仅数字键盘 + 数字字段）：KeyboardWindow 拦截按键后写入。 */
    private var bufferMode = false
    private val buffer = StringBuilder()

    /** 光标前文本模式：当前生效的算式（求值通过才非空）。 */
    private var fieldExpr: String? = null

    /** 引擎预编辑为空时才允许光标前文本模式（拼音组合中绝不干预）。 */
    private var preeditEmpty = true

    /** 密码类字段：两种模式都不启用。 */
    private var sensitiveField = false

    /** 渲染去重：内容未变不重建视图（onSelectionUpdate 触发很频繁）。 */
    private var lastRenderKey: String? = null

    /** 兜底刷新的挂起任务（个别字段不上报光标更新，按键后延迟补一次）。 */
    private var pendingRefresh: Runnable? = null

    override val view by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = ViewGroup.GONE
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // ===================== 缓冲模式 API（KeyboardWindow 调用） =====================

    val bufferNotEmpty: Boolean get() = buffer.isNotEmpty()

    fun setBufferMode(on: Boolean) {
        bufferMode = on
        if (!on) buffer.clear()
        render()
    }

    /** 追加一个算式字符；返回 true 表示已被缓冲消费（不再转发引擎）。 */
    fun appendBuffer(c: Char): Boolean {
        if (!bufferMode) return false
        if (buffer.length >= 64) return true
        buffer.append(c)
        render()
        return true
    }

    /** 退格：消费缓冲末字符返回 true；缓冲空返回 false（走正常退格）。 */
    fun backspaceBuffer(): Boolean {
        if (!bufferMode || buffer.isEmpty()) return false
        buffer.deleteCharAt(buffer.length - 1)
        render()
        return true
    }

    /** 空格 / 换行 / 切布局前调用：缓冲原文先原样上屏，原动作随后照常执行。 */
    fun commitBufferRaw() {
        if (buffer.isEmpty()) return
        commitBufferText(buffer.toString())
    }

    /** 键盘窗口卸载 / 字段切换：未上屏缓冲丢弃（与拼音未选词同语义）。 */
    fun clearBuffer() {
        buffer.clear()
        fieldExpr = null
        render()
    }

    private fun commitBufferText(text: String) {
        buffer.clear()
        render()
        runCatching { service.finishComposing() }
            .onFailure { Timber.w(it, "MBM calc: finishComposing failed, commit anyway") }
        service.lifecycleScope.launch(imeExceptionHandler) {
            service.commitText(text)
        }
    }

    // ===================== 广播回调 =====================

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        sensitiveField = variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
                (cls == InputType.TYPE_CLASS_TEXT && variation in TEXT_PASSWORD_VARIATIONS)
        // 新输入会话：未上屏内容一律丢弃（与拼音预编辑同语义）
        buffer.clear()
        fieldExpr = null
        preeditEmpty = true
        render()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        val changed = preeditEmpty != empty
        preeditEmpty = empty
        if (changed && !bufferMode) render()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        if (bufferMode) return
        updateFieldExpr()
    }

    /** 按键后兜底刷新（个别输入框不上报光标更新）。 */
    fun refreshFieldExpr() {
        if (bufferMode) return
        pendingRefresh?.let { view.removeCallbacks(it) }
        val r = Runnable {
            pendingRefresh = null
            updateFieldExpr()
        }
        pendingRefresh = r
        view.postDelayed(r, 60)
    }

    // ===================== 光标前文本提取与提交 =====================

    private fun updateFieldExpr() {
        if (bufferMode || sensitiveField || !preeditEmpty) {
            setFieldExpr(null)
            return
        }
        val ic = service.currentInputConnection
        val before = ic?.let { runCatching { it.getTextBeforeCursor(64, 0) }.getOrNull() }
        val expr = if (before.isNullOrEmpty()) null else trailingExpr(before.toString())
        setFieldExpr(if (expr != null && CalcEngine.evaluate(expr) != null) expr else null)
    }

    private fun setFieldExpr(e: String?) {
        if (fieldExpr == e) return
        fieldExpr = e
        render()
    }

    /** 取光标前文本的「尾部算式」：从末尾向前收集算式字符，截出候选串。 */
    private fun trailingExpr(before: String): String? {
        var i = before.length
        while (i > 0 && before[i - 1] in EXPR_CHARS) i--
        if (i == before.length) return null
        val expr = before.substring(i).trim()
        // 至少 3 字符且含数字，避免普通文本里的孤立运算符噪音
        return expr.takeIf { it.length >= 3 && it.any { c -> c.isDigit() } }
    }

    /** 光标前文本模式点选：原子地「删算式 + 提交替换文本」，随后重算。 */
    private fun commitField(expr: String, replacement: String) {
        runCatching { service.finishComposing() }
            .onFailure { Timber.w(it, "MBM calc: finishComposing failed before field commit") }
        val ic = service.currentInputConnection ?: return
        val ok = runCatching {
            ic.beginBatchEdit()
            try {
                val before = ic.getTextBeforeCursor(expr.length, 0)
                if (before != null && before.toString() == expr) {
                    ic.deleteSurroundingText(expr.length, 0)
                    ic.commitText(replacement, 1)
                    true
                } else {
                    false
                }
            } finally {
                ic.endBatchEdit()
            }
        }.getOrDefault(false)
        if (ok) {
            fieldExpr = null
            render()
        } else {
            updateFieldExpr()
        }
    }

    // ===================== 渲染 =====================

    private val exprView: TextView by lazy {
        TextView(context).apply {
            textSize = 14f
            setTextColor(theme.candidateCommentColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
    }

    private fun render() {
        val bar = view
        var key: String? = null
        var exprText: String? = null
        var result: String? = null
        var fromBuffer = false
        if (bufferMode) {
            val text = buffer.toString()
            if (text.isNotEmpty()) {
                key = "B:$text"
                exprText = text
                result = CalcEngine.evaluate(text)
                fromBuffer = true
            }
        } else {
            val e = fieldExpr
            if (e != null && !sensitiveField && preeditEmpty) {
                key = "F:$e"
                exprText = e
                result = CalcEngine.evaluate(e)
            }
        }
        if (key == lastRenderKey) return
        lastRenderKey = key
        if (key == null) {
            bar.visibility = ViewGroup.GONE
            return
        }
        val expr = exprText ?: return
        val res = result
        bar.removeAllViews()
        bar.addView(
            exprView.apply { text = expr },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (res != null) {
            if (fromBuffer) {
                bar.addView(calcChip(res, accent = true) { commitBufferText(res) })
                bar.addView(calcChip("$expr=$res", accent = false) { commitBufferText("$expr=$res") })
            } else {
                bar.addView(calcChip(res, accent = true) { commitField(expr, res) })
                bar.addView(calcChip("$expr=$res", accent = false) { commitField(expr, "$expr=$res") })
            }
        }
        bar.visibility = ViewGroup.VISIBLE
    }

    private fun calcChip(text: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (accent) theme.accentKeyTextColor else theme.candidateTextColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (accent) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }
}
