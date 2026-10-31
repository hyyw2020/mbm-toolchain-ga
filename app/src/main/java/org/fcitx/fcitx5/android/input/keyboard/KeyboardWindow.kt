/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout
    private lateinit var windowRoot: LinearLayout

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme)
        )
    }
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    // MBM v1.0.36：自测引擎需读当前布局名（诊断候选相位字母布局复位结果）
    val currentLayoutName: String get() = currentKeyboardName

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            // MBM v1.0.39：切换布局前先把计算缓冲原文提交，避免用户输入丢失
            flushCalc()
            switchLayout(it.act)
        } else if (interceptCalcAction(it)) {
            // 已被计算器缓冲消费（数字键盘算式输入）
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        // MBM v1.0.39：数字键盘计算器候选条（缓冲非空时显示，键盘区相应缩短）
        windowRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        buildCalcBar()
        windowRoot.addView(
            calcBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        windowRoot.addView(
            keyboardView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
        )
        attachLayout(TextKeyboard.Name)
        return windowRoot
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val target = to.ifEmpty { lastSymbolType }
        ContextCompat.getMainExecutor(service).execute {
            if (keyboards.containsKey(target)) {
                if (remember && target != TextKeyboard.Name) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) return@execute
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        // MBM v1.0.39：计算器只在数字键盘（TYPE_CLASS_NUMBER 且非密码变体）启用；
        // 电话拨号盘与密码输入框保持旧的直通上屏行为，杜绝误提交。
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val eligible = cls == InputType.TYPE_CLASS_NUMBER &&
                variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
        // 新输入会话（含同字段重聚焦）：与拼音预编辑同语义，未上屏缓冲一律丢弃
        calcBuffer.setLength(0)
        calcEligible = eligible
        refreshCalcBar()
        val targetLayout = when (cls) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        currentKeyboard?.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
        // 键盘窗口卸载（切窗/收起）：与拼音预编辑同语义，未上屏的算式缓冲直接丢弃
        calcBuffer.setLength(0)
        if (this::calcBar.isInitialized) calcBar.visibility = View.GONE
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }

    // ===================== MBM v1.0.39：数字键盘计算器（全局默认开启，无开关） =====================
    // 交互模型（与拼音预编辑一致）：
    //  - 数字键盘上的数字/运算符不直接上屏，先进入本地缓冲并显示在候选条；
    //  - 缓冲为完整算式时候选条给出【结果】与【算式=结果】两个候选，点选即上屏；
    //  - 空格 / 换行 / 切换布局：先把缓冲原文原样上屏，再执行原动作；
    //  - 退格删缓冲末字符，缓冲空后恢复正常退格；
    //  - 字段切换 / 键盘收起：缓冲丢弃（同拼音未选词丢失语义）。
    // 安全边界：电话拨号盘与密码输入框不启用（直通上屏）；全部本地计算、不联网。

    private val calcBuffer = StringBuilder()
    private var calcEligible = false
    private lateinit var calcBar: LinearLayout
    private lateinit var calcExprView: TextView

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun buildCalcBar() {
        calcBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        calcExprView = TextView(context).apply {
            textSize = 14f
            setTextColor(theme.candidateCommentColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
    }

    private fun refreshCalcBar() {
        if (!this::calcBar.isInitialized) return
        val text = calcBuffer.toString()
        if (!calcEligible || text.isEmpty()) {
            calcBar.visibility = View.GONE
            return
        }
        calcBar.removeAllViews()
        calcBar.addView(
            calcExprView.apply { this.text = text },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val result = CalcEngine.evaluate(text)
        if (result != null) {
            calcBar.addView(calcChip(result, accent = true) { commitCalc(result) })
            calcBar.addView(calcChip("$text=$result", accent = false) { commitCalc("$text=$result") })
        }
        calcBar.visibility = View.VISIBLE
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

    /** 数字键盘按键的 keysym → 算式字符（非算式字符返回 null，不拦截）。 */
    private fun calcCharOf(sym: Int): Char? = when (sym) {
        in 0xffb0..0xffb9 -> '0' + (sym - 0xffb0)
        FcitxKeyMapping.FcitxKey_KP_Add -> '+'
        FcitxKeyMapping.FcitxKey_KP_Subtract -> '-'
        FcitxKeyMapping.FcitxKey_KP_Multiply -> '*'
        FcitxKeyMapping.FcitxKey_KP_Divide -> '/'
        FcitxKeyMapping.FcitxKey_KP_Decimal, FcitxKeyMapping.FcitxKey_period -> '.'
        0x025 -> '%' // Latin-1 keysym '%'（FcitxKeyMapping 未生成该常量）
        else -> null
    }

    /**
     * 计算器按键拦截。返回 true 表示该按键已被缓冲消费，不再转发 fcitx 引擎。
     */
    private fun interceptCalcAction(action: KeyAction): Boolean {
        if (!calcEligible) return false
        when (action) {
            is KeyAction.SymAction -> {
                val sym = action.sym.sym
                return when (sym) {
                    FcitxKeyMapping.FcitxKey_BackSpace -> {
                        if (calcBuffer.isNotEmpty()) {
                            calcBuffer.deleteCharAt(calcBuffer.length - 1)
                            refreshCalcBar()
                            true
                        } else false
                    }
                    FcitxKeyMapping.FcitxKey_space, FcitxKeyMapping.FcitxKey_Return -> {
                        if (calcBuffer.isNotEmpty()) {
                            // 原文上屏（同步 InputConnection 写入），随后原动作照常执行
                            commitCalc(calcBuffer.toString())
                            false
                        } else false
                    }
                    else -> {
                        val c = calcCharOf(sym)
                        if (c == null) return false
                        if (calcBuffer.length >= 64) return true
                        calcBuffer.append(c)
                        refreshCalcBar()
                        true
                    }
                }
            }
            else -> {
                // 符号键盘切换 / 快捷短语等其他动作：先提交原文，不丢失用户输入
                if (calcBuffer.isNotEmpty()) commitCalc(calcBuffer.toString())
                return false
            }
        }
    }

    /** 切换布局前的原文提交（仅数字键盘有缓冲）。 */
    private fun flushCalc() {
        if (calcEligible && currentKeyboardName == NumberKeyboard.Name && calcBuffer.isNotEmpty()) {
            commitCalc(calcBuffer.toString())
        }
    }

    /** 把文本同步上屏并清空缓冲（InputConnection 写入在主线程即时生效，顺序可靠）。 */
    private fun commitCalc(text: String) {
        calcBuffer.setLength(0)
        refreshCalcBar()
        runCatching { service.finishComposing() }
            .onFailure { Timber.w(it, "MBM calc: finishComposing failed, commit anyway") }
        service.lifecycleScope.launch(imeExceptionHandler) {
            service.commitText(text)
        }
    }
    // ===================== 计算器结束 =====================
}