/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.calc.CalcCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
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

    /** MBM v1.0.39：全局计算器候选条（宿主在 InputView，跨窗口常驻；缓冲/光标前文本双模式）。 */
    private val calc: CalcCandidateComponent by manager.must()

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
            calc.commitBufferRaw()
            switchLayout(it.act)
        } else if (interceptCalcAction(it)) {
            // 已被计算器缓冲消费（数字键盘算式输入）
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
            // 光标前文本模式兜底刷新（多数字段靠 onSelectionUpdate，个别字段需补一次）
            calc.refreshFieldExpr()
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        return keyboardView
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
        // MBM v1.0.39：缓冲模式仅在数字键盘场景（TYPE_CLASS_NUMBER 且非密码变体）；
        // 其余字段由组件的「光标前文本模式」全局接管（含符号键盘 / QWERTY / PickerWindow）。
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val eligible = cls == InputType.TYPE_CLASS_NUMBER &&
                variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
        calc.setBufferMode(eligible)
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
        calc.clearBuffer()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }

    // ===================== MBM v1.0.39：计算器（状态与渲染在 CalcCandidateComponent） =====================
    // 缓冲模式（数字键盘）：数字/运算符键不直接上屏，进组件缓冲并渲染在 InputView 层候选条；
    //   空格/换行/切布局：缓冲原文先上屏再执行原动作；退格删缓冲末字符。
    // 光标前文本模式（任意键盘/窗口，含符号键盘与 PickerWindow）：组件监听 onSelectionUpdate，
    //   从输入框提取尾部算式给出候选，点选原子地「删算式+提交」。
    // 安全边界：密码类字段不启用；拼音预编辑非空时不干预；全部本地计算、不联网、无开关。

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
     * 仅缓冲模式（数字键盘+数字字段）会消费按键；其余一律放行。
     */
    private fun interceptCalcAction(action: KeyAction): Boolean {
        when (action) {
            is KeyAction.SymAction -> {
                val sym = action.sym.sym
                return when (sym) {
                    FcitxKeyMapping.FcitxKey_BackSpace -> calc.backspaceBuffer()
                    FcitxKeyMapping.FcitxKey_space, FcitxKeyMapping.FcitxKey_Return -> {
                        if (calc.bufferNotEmpty) {
                            // 原文上屏（同步 InputConnection 写入），随后原动作照常执行
                            calc.commitBufferRaw()
                            false
                        } else false
                    }
                    else -> {
                        val c = calcCharOf(sym)
                        c != null && calc.appendBuffer(c)
                    }
                }
            }
            else -> {
                // 符号键盘切换 / 快捷短语等其他动作：先提交原文，不丢失用户输入
                calc.commitBufferRaw()
                return false
            }
        }
    }
    // ===================== 计算器结束 =====================
}