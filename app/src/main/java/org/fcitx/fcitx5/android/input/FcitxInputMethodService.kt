/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.customphrase.AppPhraseImporter
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PersonalPhraseImporter
import org.fcitx.fcitx5.android.recall.RecallService
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.data.password.PasswordFillBridge
import org.fcitx.fcitx5.android.data.password.PrivacyController
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.clipboard.ClipboardBlobStore
import org.fcitx.fcitx5.android.data.clipboard.ClipboardPasteChunker
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.imeFallbackScope
import org.fcitx.fcitx5.android.data.smartdict.SmartDictManager
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import java.io.File
import kotlin.math.max

class FcitxInputMethodService : LifecycleInputMethodService(), MbmDebug.Bridge {

    private lateinit var fcitx: FcitxConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val cachedKeyEvents = LruCache<Int, KeyEvent>(78)
    private var cachedKeyEventIndex = 0

    /**
     * Saves MetaState produced by hardware keyboard with "sticky" modifier keys, to clear them in order.
     * See also [InputConnection#clearMetaKeyStates(int)](https://developer.android.com/reference/android/view/inputmethod/InputConnection#clearMetaKeyStates(int))
     */
    private var lastMetaState: Int = 0

    private lateinit var pkgNameCache: PackageNameCache

    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private val navbarMgr = NavigationBarManager()
    private val inputDeviceMgr = InputDeviceManager { isVirtualKeyboard ->
        postFcitxJob {
            setCandidatePagingMode(if (isVirtualKeyboard) 0 else 1)
        }
        currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard)
        if (isVirtualKeyboard) {
            hideStatusIcon()
        } else {
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        window.window?.let {
            navbarMgr.evaluate(it, isVirtualKeyboard)
        }
    }

    private var capabilityFlags = CapabilityFlags.DefaultFlags

    private val selection = CursorTracker()

    val currentInputSelection: CursorRange
        get() = selection.latest

    private val composing = CursorRange()
    private var composingText = FormattedText.Empty

    /**
     * MBM v1.0.26 / P1-6（Android 17，API 37）：
     * 当前是否「有候选被高亮」——即 fcitx 候选列表 cursorIndex >= 0。
     * 只在 [commitTextInternal] 消费；客户端预编辑被清空、或新一次输入开始时复位。
     * 该标记是进程内瞬态，绝不落盘、绝不出现在日志里（不含任何用户内容）。
     */
    @Volatile
    private var suggestionSelected = false

    /**
     * MBM v1.0.47：滑动选词「实时预编辑上屏」进行中标记。
     * 置位期间 [handleCursorUpdate] 忽略编辑器自发的光标/选区回调
     * （那些回调由我们的 setComposingText 触发，不应回灌 fcitx 状态机，
     * 否则会让原本干净的预编辑被 reset 掉）。
     */
    @Volatile
    private var wordSelectPreviewActive = false
    private var wordSelectPreviewStart = 0
    private var wordSelectPreviewText = ""

    private fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    private var cursorUpdateIndex: Int = 0

    private var highlightColor: Int = DefaultHighlightColor

    private val prefs = AppPrefs.getInstance()
    private val inlineSuggestions by prefs.keyboard.inlineSuggestions
    private val ignoreSystemCursor by prefs.advanced.ignoreSystemCursor

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.advanced.disableAnimation,
        prefs.advanced.ignoreSystemWindowInsets,
    )

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, fcitx, theme)
        setInputView(newInputView)
        inputDeviceMgr.setInputView(newInputView)
        inputView = newInputView
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme) {
        navbarMgr.evaluate(window.window!!, inputDeviceMgr.isVirtualKeyboard)
        replaceInputView(theme)
        replaceCandidateView(theme)
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        replaceInputView(ThemeManager.activeTheme)
    }

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        replaceCandidateView(ThemeManager.activeTheme)
    }

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        replaceInputViews(it)
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit): Job {
        // MBM v1.0.25：挂 imeExceptionHandler，吞掉 fcitx 操作中的未捕获异常。
        // 否则 select/reset 等原生调用在候选为空或状态异常时抛错会沿默认处理器上抛，
        // 直接令整个输入法进程崩溃（同源 Bug：见 ImeSafe.kt 与 pitfalls）。
        val job = fcitx.lifecycleScope.launch(imeExceptionHandler, start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        jobs.trySend(job)
        return job
    }

    override fun onCreate() {
        // MBM v1.0.36：注册 adb 调试桥接（MbmDebug）
        MbmDebug.bridge = this
        fcitx = FcitxDaemon.connect(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch(imeExceptionHandler) {
            fcitx.runImmediately { eventFlow }.collect {
                // N30：单条事件处理异常不应终止整个事件收集协程（否则键盘永久失灵），逐条兜底
                runCatching { handleFcitxEvent(it) }.onFailure { Timber.w(it, "handleFcitxEvent failed") }
            }
        }
        pkgNameCache = PackageNameCache(this)
        // MBM v0.0.05：应用名即时候选——首次把本机应用名按拼音键写入自定义词组，
        // 之后输入 qmqz 直接出「全民枪战2」（libime 通用词库没有应用名，
        // 用户也从未选过 → 冷启动死锁，见 pitfalls #56）
        // 首次导入含 SharedPreferences 读 + 应用枚举 + 词库落盘，全程 IO 线程，
        // 与 onCreate 主路径并行不阻塞（v0.0.10）
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppPhraseImporter.ensureImported(this@FcitxInputMethodService, fcitx)
            }
        }
        // MBM v1.0.46：个人学习 → 候选预测闭环。
        //  - 启动后先把历史高频个人词同步进候选（首屏即用）；
        //  - 之后每 120s 仅在「本地统计有新增」时再同步一次（无新增不 reload，零开销）。
        // 学习统计由 commitText 在隐私模式外持续记录（见 SmartDictManager.recordUsage）。
        lifecycleScope.launch(imeExceptionHandler) {
            // v1.0.53：initialSync 与周期性 syncIfDirty 内含「读加密文件 + 全量 JSON 解析」，
            // 必须放到 IO 线程，否则冷启动时会在主线程同步阻塞（输入法启动卡顿 / ANR 风险）。
            // （常规上屏的 requestContextRerank 早已在 Dispatchers.IO 下执行，见 commitText）
            withContext(Dispatchers.IO) {
                PersonalPhraseImporter.initialSync(this@FcitxInputMethodService)
            }
            // 生命周期作用域销毁时本协程被取消，delay 抛 CancellationException 自然退出；
            // 仅当「本地统计有新增」才同步，无新增不触发 reload（零开销）
            while (true) {
                kotlinx.coroutines.delay(120_000)
                withContext(Dispatchers.IO) {
                    runCatching { PersonalPhraseImporter.syncIfDirty(this@FcitxInputMethodService) }
                }
            }
        }
        // 应用装卸后延迟重枚举（等安装/卸载完成再读 label）
        packageReceiver = AppPhraseImporter.registerPackageReceiver(this) {
            val job = lifecycleScope.launch {
                kotlinx.coroutines.delay(3000)
                AppPhraseImporter.refresh(this@FcitxInputMethodService, fcitx)
            }
            packageRefreshJob?.cancel()
            packageRefreshJob = job
        }
        // MBM：密码生成器 → 当前输入框的填写桥（不落剪贴板、不学习）
        PasswordFillBridge.register { text ->
            val ic = currentInputConnection ?: return@register false
            // N38：原实现零校验，会把明文密码填进任何已聚焦的输入框（搜索框、备注等）。
            // 仅在当前是密码类字段时才填写，避免焦点已切走时密码明文泄漏。
            if (!isPasswordField(currentInputEditorInfo)) return@register false
            runCatching {
                ic.commitText(text, 1)
                true
            }.getOrDefault(false)
        }
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = resources.configuration
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.CommitStringEvent -> {
                commitText(event.data.text, event.data.cursor)
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    when (it.sym.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace -> handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return -> handleReturnKey()
                        FcitxKeyMapping.FcitxKey_Left -> handleArrowKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        FcitxKeyMapping.FcitxKey_Right -> handleArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        else -> if (it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    // use cached event if available
                    cachedKeyEvents.remove(it.timestamp)?.let { keyEvent ->
                        /**
                         * intercept the KeyEvent which would cause the default [android.text.method.QwertyKeyListener]
                         * to show a Gingerbread-style CharacterPickerDialog
                         */
                        if (keyEvent.unicodeChar == KeyCharacterMap.PICKER_DIALOG_INPUT.code) {
                            currentInputConnection?.sendKeyEvent(
                                KeyEvent(
                                    keyEvent.downTime, keyEvent.eventTime,
                                    keyEvent.action, keyEvent.keyCode,
                                    keyEvent.repeatCount, keyEvent.metaState, -1,
                                    keyEvent.scanCode, keyEvent.flags, keyEvent.source
                                )
                            )
                            return@event
                        }
                        currentInputConnection?.sendKeyEvent(keyEvent)
                        if (KeyEvent.isModifierKey(keyEvent.keyCode)) {
                            when (keyEvent.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    // save current metaState when modifier key down
                                    lastMetaState = keyEvent.metaState
                                }
                                KeyEvent.ACTION_UP -> {
                                    // only clear metaState that would be missing when this modifier key up
                                    currentInputConnection?.clearMetaKeyStates(lastMetaState xor keyEvent.metaState)
                                    lastMetaState = keyEvent.metaState
                                }
                            }
                        }
                        return@event
                    }
                    // simulate key event
                    val keyCode = it.sym.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        // recognized keyCode
                        val eventTime = SystemClock.uptimeMillis()
                        if (it.up) {
                            sendUpKeyEvent(eventTime, keyCode, it.states.metaState)
                        } else {
                            sendDownKeyEvent(eventTime, keyCode, it.states.metaState)
                        }
                    } else {
                        // no matching keyCode, commit character once on key down
                        if (!it.up && it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Fcitx KeyEvent: $it")
                        }
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                // 预编辑被清空（composing 结束）时，候选高亮态一并复位，
                // 避免下次 commitText 误带「候选选择中」标记。
                if (event.data.isEmpty()) suggestionSelected = false
                updateComposingText(event.data)
            }
            is FcitxEvent.PagedCandidateEvent -> {
                // MBM v1.0.26 / P1-6（Android 17，API 37）：
                // fcitx 候选列表的 cursorIndex 即「当前高亮候选」，对应
                // InputType.TYPE_TEXT_FLAG_ENABLE_TEXT_SUGGESTION_SELECTED 所称的
                // "active selected suggestion"。编辑器若声明了该 flag，
                // 我们在 commitText 时必须附带
                // TextAttribute.Builder.setTextSuggestionSelected(true)，
                // 读屏软件才能把「选择候选词」与「普通输入」区分播报
                // （AccessibilityEvent.TEXT_CHANGE_TYPE_COMPOSITION_SELECTION）。
                suggestionSelected =
                    event.data.candidates.isNotEmpty() && event.data.cursorIndex >= 0
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                handleDeleteSurrounding(before, after)
            }
            is FcitxEvent.IMChangeEvent -> {
                // N73：`subtypeOf` 返回 null 时原写法 `?: return` 会直接结束整个函数，
                // 跳过下方的状态栏图标更新 → 图标永久停留在上一个输入法。
                // 这里只在「切换子类型」这一子块内提前返回，状态栏图标更新不受影响。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val im = event.data.uniqueName
                    val subtype = SubtypeManager.subtypeOf(im)
                    if (subtype != null) {
                        skipNextSubtypeChange = im
                        // [^1]: notify system that input method subtype has changed
                        switchInputMethod(InputMethodUtil.componentName, subtype)
                    }
                }
                if (inputDeviceMgr.evaluateOnInputMethodActivate()) {
                    showStatusIcon(StatusIconMapping.fromEntry(event.data))
                }
            }
            is FcitxEvent.SwitchInputMethodEvent -> {
                val (reason) = event.data
                if (reason != FcitxEvent.SwitchInputMethodEvent.Reason.CapabilityChanged &&
                    reason != FcitxEvent.SwitchInputMethodEvent.Reason.Other
                ) {
                    if (inputDeviceMgr.evaluateOnInputMethodSwitch()) {
                        // show inputView for [CandidatesView] when input method switched by user
                        forceShowSelf()
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * N38 辅助：判断当前输入框是否为密码类字段。
     * 密码填写桥只在密码字段上生效，避免把明文密码误填进普通输入框。
     */
    private fun isPasswordField(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun handleDeleteSurrounding(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        if (before > 0) {
            selection.predictOffset(-before)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(before, after)
        } else {
            ic.deleteSurroundingText(before, after)
        }
    }

    private fun handleBackspaceKey() {
        val lastSelection = selection.latest
        if (lastSelection.isNotEmpty()) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            selection.predictOffset(-1)
        }
        // In practice nobody (apart from ourselves) would set `privateImeOptions` to our
        // `DeleteSurroundingFlag`, leading to a behavior of simulating backspace key pressing
        // in almost every EditText.
        if (currentInputEditorInfo.privateImeOptions != DeleteSurroundingFlag ||
            currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (lastSelection.isEmpty()) {
            if (lastSelection.start <= 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                currentInputConnection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                currentInputConnection.deleteSurroundingText(1, 0)
            }
        } else {
            currentInputConnection.commitText("", 0)
        }
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (actionLabel?.isNotEmpty() == true && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    private fun handleArrowKey(keyCode: Int) {
        val type = currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = currentInputEditorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (type == InputType.TYPE_NULL ||
            // confirm URL suggestion in browser location bar, see also https://bugzilla.mozilla.org/show_bug.cgi?id=1999915
            type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        ) {
            sendDownUpKeyEvents(keyCode)
            return
        }
        val (start, end) = currentInputSelection
        val offset = if (start == end) 1 else 0
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> start - offset
            KeyEvent.KEYCODE_DPAD_RIGHT -> end + offset
            else -> return
        }
        currentInputConnection.setSelection(target, target)
    }

    fun commitText(text: String, cursor: Int = -1, record: Boolean = true) {
        // MBM 冻结方案：IME 打字时刻同步抓取 —— 通知已连接的 RecallService 紧跟一次采样，
        // 实现「打字即更新词库」的无延迟体验。notifyTyping 全程空安全（instance 可空、
        // picker 由 lazy 初始化无 lateinit 窗口期），P2-5 修复：删除外层 runCatching——
        // 它把「服务未开启」的预期空操作当异常路径，还会静默吞掉真实异常掩盖问题。
        RecallService.notifyTyping()
        // MBM v1.0.22：把用户提交的多字文本喂给「离线智能词库」本地统计，
        // 生成智能候选词。隐私模式（密码框等）下不统计，与 PrivacyController 约定一致。
        // 在独立 IO 协程里做文件读写，绝阻塞输入法主线程；异常被 imeExceptionHandler 吞掉，
        // 不影响输入。record=false 用于下方递归提交，避免重复统计。
        if (record && !PrivacyController.inPrivacyMode) {
            val t = text
            imeFallbackScope.launch(Dispatchers.IO) {
                runCatching { SmartDictManager.recordUsage(t) }
                // MBM v1.0.48：上屏即更新「上下文预测」——刚提交的词作为前词，
                // 把其高频后继词抬到候选前列（见 PersonalPhraseImporter.requestContextRerank）。
                runCatching { PersonalPhraseImporter.requestContextRerank(this@FcitxInputMethodService) }
            }
        }
        // MBM v1.0.25：提交整段包 runCatching —— 任意调用方（CommitAction 标点提交、
        // fcitx CommitStringEvent 回灌等）只要本函数内抛异常（composing/selection 状态异常、
        // InputConnection 瞬断等）都只记日志、绝不令输入法进程崩溃（同源 Bug 防护）。
        runCatching {
            val ic = currentInputConnection ?: return@runCatching
            // when composing text equals commit content, finish composing text as-is
            if (composing.isNotEmpty() && composingText.toString() == text) {
                val c = if (cursor == -1) text.length else cursor
                val target = composing.start + c
                resetComposingState()
                ic.withBatchEdit {
                    if (selection.current.start != target) {
                        selection.predict(target)
                        ic.setSelection(target, target)
                    }
                    ic.finishComposingText()
                }
                return@runCatching
            }
            // committed text should replace composing (if any), replace selected range (if any),
            // or simply prepend before cursor
            val start = if (composing.isEmpty()) selection.latest.start else composing.start
            resetComposingState()
            if (cursor == -1) {
                selection.predict(start + text.length)
                commitTextInternal(ic, text, 1)
            } else {
                val target = start + cursor
                selection.predict(target)
                ic.withBatchEdit {
                    commitTextInternal(ic, text, 1)
                    setSelection(target, target)
                }
            }
        }.onFailure { Timber.e(it, "MBM: commitText(\"$text\") 异常，已抑制以避免输入法崩溃") }
    }

    /**
     * MBM v1.0.31：粘贴剪贴板条目 —— 支持 100MB+ 大文本，粘贴结果 = 完整原文。
     *
     * 背景：`InputConnection.commitText` 走 Binder 事务（上限约 1MB），
     * 直接把 100MB 字符串交给它必抛 `TransactionTooLargeException`。
     * 绕行设计（不是缩减目标，见 docs/design/unlimited-clipboard-design.md §3.1.3）：
     *  1. 小文本走原 [commitText] 路径（行为不变）；
     *  2. 大文本流式读 blob 文件，按 [ClipboardBlobStore.PASTE_CHUNK_BYTES] 分块
     *     commit，块间处理 UTF-8 多字节字符边界（不切断字符）；
     *  3. 每块独立 `withBatchEdit`，任一块失败立即停止并报告**已粘贴量**；
     *  4. 完成后做首尾校验（getTextBeforeCursor 尾部比对）——抓住目标输入框
     *     maxLength 截断这类静默失败；
     *  5. 全程不喂 SmartDict 统计（100MB 进词库统计会是灾难），不走候选属性路径。
     */
    fun pasteClipboardEntry(entry: ClipboardEntry) {
        val ic = currentInputConnection
        if (ic == null) {
            // 诊断：屏幕记忆词库（剪贴板候选）「点了没反应」时输出当前输入目标，
            // 用于区分不同 app/不同搜索框的差异（无输入连接是静默失败主因）。
            val ei = currentInputEditorInfo
            Timber.w(
                "MBM: pasteClipboardEntry 无输入连接，忽略（pkg=%s inputType=%d imeOptions=%d）",
                ei?.packageName,
                ei?.inputType,
                ei?.imeOptions
            )
            return
        }
        if (!entry.isBlob) {
            // 诊断：小文本 commit 失败多为目标框 maxLength / password / 拒绝接收，
            // 记录目标信息以便定位「不同搜索框词库无法调用」。
            val ok = runCatching { commitText(entry.text) }.isSuccess
            if (!ok) {
                val ei = currentInputEditorInfo
                Timber.w(
                    "MBM: pasteClipboardEntry commitText 失败（pkg=%s inputType=%d len=%d）",
                    ei?.packageName,
                    ei?.inputType,
                    entry.text.length
                )
            }
            return
        }
        val blobRef = entry.blobRef!!
        val totalBytes = entry.byteLen
        // 结束可能存在的预编辑，从干净状态开始分块粘贴
        runCatching {
            resetComposingState()
            ic.finishComposingText()
        }
        pasteJob?.cancel()
        pasteJob = imeFallbackScope.launch(Dispatchers.IO) {
            val stream = ClipboardBlobStore.open(blobRef)
            if (stream == null) {
                pasteToast("条目文件丢失，无法粘贴")
                return@launch
            }
            var pastedBytes = 0L
            var failed = false
            var lastReport = 0L
            val committed = stream.use { s ->
                // U4 修复：commitText 必须跑在输入法主线程——InputConnection 是跨进程
                // Binder 代理，在 IO 线程调用会让目标 App 的 ViewRootImpl.checkThread
                // 抛异常（崩的是微信/备忘录），被 runCatching 吞掉则表现为「点了没反应」。
                // 流式读取仍在 IO，仅提交跨回主线程。
                ClipboardPasteChunker.chunks(s) { text, byteLen ->
                    val ok = runCatching {
                        withContext(Dispatchers.Main.immediate) {
                            ic.withBatchEdit { ic.commitText(text, 1) }
                        }
                    }.isSuccess
                    if (ok) {
                        pastedBytes += byteLen
                        // 进度：每约 32MB 报一次（子协程发 toast，回调本身非 suspend）
                        if (pastedBytes - lastReport >= 32L * 1024 * 1024) {
                            lastReport = pastedBytes
                            this.launch {
                                pasteToast("正在粘贴… ${pastedBytes / 1048576}/${totalBytes / 1048576} MB")
                            }
                        }
                        true
                    } else {
                        failed = true
                        false
                    }
                }
            }
            if (!failed) pastedBytes = committed
            if (failed) {
                pasteToast("粘贴中断：已粘贴 ${pastedBytes / 1048576} MB / ${totalBytes / 1048576} MB（请重试）")
                Timber.w("MBM: chunked paste failed at %d/%d bytes", pastedBytes, totalBytes)
                return@launch
            }
            if (pastedBytes != totalBytes) {
                pasteToast("粘贴不完整：${pastedBytes}/${totalBytes} 字节")
                Timber.w("MBM: chunked paste incomplete %d/%d", pastedBytes, totalBytes)
                return@launch
            }
            // 尾部校验：抓目标输入框 maxLength 静默截断
            val tailOk = verifyPasteTail(ic, blobRef, totalBytes)
            pasteToast(
                if (tailOk) "已粘贴 ${totalBytes / 1048576} MB，校验通过"
                else "已粘贴 ${totalBytes / 1048576} MB，但目标输入框可能截断了内容（长度限制？）"
            )
        }
    }

    /**
     * 尾部校验：读取 blob 末尾 [TAIL_CHECK_BYTES] 字节，与 `getTextBeforeCursor` 比对。
     * 便宜（一次 ≤64 字符的 Binder 事务），能抓住 maxLength 截断。
     * 注意：这是**启发式**告警——目标应用自动补字符（如自动换行）也可能误报，
     * 所以文案是「可能截断」而非「已截断」。
     * 纯逻辑在 [ClipboardPasteChunker]（expectedTailText / tailMatches，可单测）。
     *
     * MBM v1.0.34：blob 加密后不能 seek 文件，改走 [ClipboardBlobStore.open]
     * 拿解密流，由 expectedTailText 流式环缓冲取尾。
     */
    private suspend fun verifyPasteTail(
        ic: android.view.inputmethod.InputConnection,
        blobRef: String,
        totalBytes: Long
    ): Boolean {
        val stream = ClipboardBlobStore.open(blobRef) ?: return true // 读不了就放过（不误报）
        val expected = stream.use { s ->
            ClipboardPasteChunker.expectedTailText(s, TAIL_CHECK_BYTES)
        } ?: return true
        // U4 修复：getTextBeforeCursor 同样必须在主线程调用
        val before = withContext(Dispatchers.Main.immediate) {
            runCatching { ic.getTextBeforeCursor(TAIL_CHECK_CHARS, 0)?.toString() }
                .getOrNull() ?: ""
        }
        return ClipboardPasteChunker.tailMatches(before, expected)
    }

    private suspend fun pasteToast(msg: String) {
        runCatching {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@FcitxInputMethodService, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Android 17（API 37）候选选择态上报。
     *
     * 编辑器在 `EditorInfo.inputType` 声明
     * `InputType.TYPE_TEXT_FLAG_ENABLE_TEXT_SUGGESTION_SELECTED`（0x100000，API 37）后，
     * 有权知道「当前这次提交是不是用户从候选列表里选中的词」。
     * 该信息只能通过 `InputConnection.commitText(CharSequence, int, TextAttribute)`
     * （API 33 起为 default 方法）携带，[TextAttribute.Builder.setTextSuggestionSelected]
     * 则是 API 37 才加进 [TextAttribute.Builder] 的。
     *
     * 语义：仅在候选列表确实有高亮项（见 [suggestionSelected]）且系统 >= 37 时走带属性的重载；
     * 其余情况保持老的 `commitText(CharSequence, int)`，行为与 v1.0.25 完全一致 ——
     * 老系统/老编辑器走 else 分支，不产生任何可观察差异。
     */
    @RequiresApi(37)
    private fun commitTextWithSuggestionAttribute(
        ic: android.view.inputmethod.InputConnection,
        text: String,
        newCursorPosition: Int
    ): Boolean {
        val attr = TextAttribute.Builder()
            .setTextSuggestionSelected(true)
            .build()
        return ic.commitText(text, newCursorPosition, attr)
    }

    private fun commitTextInternal(
        ic: android.view.inputmethod.InputConnection,
        text: String,
        newCursorPosition: Int
    ) {
        if (!suggestionSelected || Build.VERSION.SDK_INT < 37) {
            ic.commitText(text, newCursorPosition)
            return
        }
        // 只有「从候选列表选中并提交」这一条路径会走到这里；
        // 反射/反射式调用一律不用 —— API 37 的方法是 default 方法，直接调用即可。
        runCatching { commitTextWithSuggestionAttribute(ic, text, newCursorPosition) }
            .onFailure {
                Timber.w(it, "MBM: commitText(TextAttribute) 失败，回退普通提交")
                ic.commitText(text, newCursorPosition)
            }
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun deleteSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        currentInputConnection?.commitText("", 1)
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        val lastSelection = selection.latest
        currentInputConnection?.also {
            val start = max(lastSelection.start + offsetStart, 0)
            val end = max(lastSelection.end + offsetEnd, 0)
            if (start > end) return
            selection.predict(start, end)
            it.setSelection(start, end)
        }
    }

    fun cancelSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        currentInputConnection?.setSelection(end, end)
    }

    private lateinit var lastKnownConfig: Configuration

    override fun onConfigurationChanged(newConfig: Configuration) {
        postFcitxJob { reset() }
        /**
         * skip keyboard|keyboardHidden changes, because we have [inputDeviceMgr]
         * skip uiMode (system light/dark mode) changes, because we have [onThemeChangeListener]
         * to replace InputView(s) when needed
         * [android.inputmethodservice.InputMethodService.onConfigurationChanged] would call
         * resetStateForNewConfiguration() which calls initViews() causes InputView(s) to be replaced again
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1984
         */
        val f = ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        /**
         * perform `super.onConfigurationChanged` only when `newConfig` diff fall outside "skipped" flags
         * we have to calculate the mask ourselves because nobody knows how `handledConfigChanges` works
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1876
         */
        if (diff and f != diff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig = newConfig
    }

    override fun onWindowShown() {
        super.onWindowShown()
        highlightColor =
            styledColorOrDefault(android.R.attr.colorAccent, DefaultHighlightColor).alpha(0.4f)
        InputFeedbacks.syncSystemPrefs()
    }

    override fun onCreateInputView(): View? {
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // input method layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        // expand inputArea to fullscreen
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        /**
         * expand InputView to fullscreen, since [android.inputmethodservice.InputMethodService.setInputView]
         * would set InputView's height to [ViewGroup.LayoutParams.WRAP_CONTENT]
         */
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var inputViewLocation = intArrayOf(0, 0)

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceMgr.isVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            // MBM v1.0.43：可触摸区域上沿必须覆盖浮在键盘上方的计算条 / 预编辑条，
            // 否则点击这些条上的候选项会被判定为「点击 IME 外部」→ 键盘收起、候选点不上屏。
            val top = inputView?.touchableTopY() ?: inputViewLocation[1]
            outInsets.apply {
                contentTopInsets = top
                visibleTopInsets = top
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onEvaluateFullscreenMode() = false

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        // reason to use a self increment index rather than timestamp:
        // KeyUp and KeyDown events actually can happen on the same time
        val timestamp = cachedKeyEventIndex++
        cachedKeyEvents.put(timestamp, event)
        val sym = KeySym.fromKeyEvent(event)
        if (sym != null) {
            val states = KeyStates.fromKeyEvent(event)
            val up = event.action == KeyEvent.ACTION_UP
            postFcitxJob {
                sendKey(sym, states, event.scanCode, up, timestamp)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceMgr.evaluateOnKeyDown(event, this)) {
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceMgr.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceMgr.evaluateOnUpdateEditorToolType(toolType, this)
    }

    private var firstBindInput = true

    override fun onBindInput() {
        // N44：多屏机型下 currentInputBinding 可能为 null，非空断言会 NPE；与 onUnbindInput 保持一致用安全调用。
        val uid = currentInputBinding?.uid ?: return
        val pkgName = pkgNameCache.forUid(uid)
        Timber.d("onBindInput: uid=$uid pkg=$pkgName")
        postFcitxJob {
            // ensure InputContext has been created before focusing it
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype)
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }

    /**
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    private var skipNextSubtypeChange: String? = null

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im")
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            if (skipNextSubtypeChange == im) {
                skipNextSubtypeChange = null
                return
            }
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // update selection as soon as possible
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated. but it's the client app's responsibility to send
        // right cursor position, try to workaround this would simply introduce more bugs.
        selection.resetTo(attribute.initialSelStart, attribute.initialSelEnd)
        resetComposingState()
        // MBM v1.0.26 / P1-6：换编辑器/重启输入时清空候选高亮态，
        // 防止上一个编辑器残留的候选标记被误带到本次 commitText 上。
        suggestionSelected = false
        // MBM：本地判定是否为密码输入框，进入隐私模式（不学习、不记忆、不索引）
        PrivacyController.update(attribute, attribute.packageName)
        val flags = CapabilityFlags.fromEditorInfo(attribute)
        capabilityFlags = flags
        // EditorInfo may change between onStartInput and onStartInputView
        inputDeviceMgr.notifyOnStartInput(attribute)
        Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
        // MBM v0.0.05：应用搜索场景（hint「搜索应用」/ 桌面类包名）把应用名候选
        // 切到 order=1 置顶；普通场景 order=8 靠后不打扰常用词（pitfalls #56）
        val appPhraseScene = if (AppPhraseImporter.isAppSearchScene(
                attribute.packageName, attribute.hintText
            )
        ) AppPhraseImporter.SCENE_APP_SEARCH else AppPhraseImporter.SCENE_NORMAL
        // MBM v0.0.10：onSceneChanged 首访会读 SharedPreferences（首访阻塞读盘），
        // 其内部 rebuild 也要枚举已安装应用——整段放 IO 线程，
        // 保证 onStartInput 主路径零磁盘/包枚举阻塞（切换输入框瞬间不掉帧）
        // N72：onStartInput 的 IO 协程挂异常处理器，避免包枚举/读盘异常上抛致进程崩溃
        lifecycleScope.launch(imeExceptionHandler) {
            withContext(Dispatchers.IO) {
                AppPhraseImporter.onSceneChanged(
                    this@FcitxInputMethodService, fcitx, appPhraseScene
                )
            }
        }
        val isNullType = attribute.isTypeNull()
        // wait until InputContext created/activated
        postFcitxJob {
            if (restarting) {
                // when input restarts in the same editor, focus out to clear previous state
                focus(false)
                // try focus out before changing CapabilityFlags,
                // to avoid confusing state of different text fields
            }
            // EditorInfo can be different in onStartInput and onStartInputView,
            // especially in browsers
            setCapFlags(flags)
            // for hardware keyboard, focus to allow switching input methods before onStartInputView
            if (!isNullType) {
                focus(true)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.d("onStartInputView: restarting=$restarting")
        postFcitxJob {
            focus(true)
        }
        if (inputDeviceMgr.evaluateOnStartInputView(info, this)) {
            // because onStartInputView will always be called after onStartInput,
            // editorInfo and capFlags should be up-to-date
            inputView?.startInput(info, capabilityFlags, restarting)
        } else {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        // onUpdateSelection can left behind when user types quickly enough, eg. long press backspace
        cursorUpdateIndex += 1
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd] cand=[$candidatesStart,$candidatesEnd]")
        handleCursorUpdate(
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
            cursorUpdateIndex
        )
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = contentView.height.toFloat()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal =
                if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition[0] = horizontal
            anchorPosition[1] = bounds.bottom
            anchorPosition[2] = horizontal
            anchorPosition[3] = bounds.top
        } else {
            anchorPosition[0] = info.insertionMarkerHorizontal
            anchorPosition[1] = info.insertionMarkerBottom
            anchorPosition[2] = info.insertionMarkerHorizontal
            anchorPosition[3] = info.insertionMarkerTop
        }
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(anchorPosition)
        val (xOffset, yOffset) = decorLocation
        anchorPosition[0] -= xOffset
        anchorPosition[1] -= yOffset
        anchorPosition[2] -= xOffset
        anchorPosition[3] -= yOffset
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        newComposingStart: Int,
        newComposingEnd: Int,
        updateIndex: Int
    ) {
        // MBM v1.0.47：滑动选词上屏预览期间，编辑器因我们的 setComposingText 收到的
        // 光标/选区回调一律忽略，避免回灌 fcitx 状态机造成预编辑错乱。
        if (wordSelectPreviewActive) return
        if (selection.consume(newSelStart, newSelEnd)) {
            // try restore composing range in case it was dropped by InputFilter
            // but only when prediction matches, since InputFilter can also change editor content
            // ref:
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/Editor.java#2083
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/TextView.java#7351
            if (newComposingStart == -1 && newComposingEnd == -1 && composing.isNotEmpty()) {
                currentInputConnection?.setComposingRegion(composing.start, composing.end)
            }
            return // do nothing if prediction matches
        } else {
            // cursor update can't match any prediction: it's treated as a user input
            selection.resetTo(newSelStart, newSelEnd)
        }
        // skip selection range update, we only care about selection cursor (zero width) here
        if (newSelStart != newSelEnd) return
        // do reset if composing is empty && input panel is not empty
        if (composing.isEmpty()) {
            postFcitxJob {
                if (!isEmpty()) {
                    Timber.d("handleCursorUpdate: reset")
                    reset()
                }
            }
            return
        }
        // check if cursor inside composing text
        if (composing.contains(newSelStart)) {
            if (ignoreSystemCursor) return
            // fcitx cursor position is relative to client preedit (composing text)
            val position = newSelStart - composing.start
            // move fcitx cursor when cursor position changed
            if (position != composingText.cursor) {
                // cursor in InvokeActionEvent counts by "UTF-8 characters"
                val codePointPosition = composingText.codePointCountUntil(position)
                postFcitxJob {
                    if (updateIndex != cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to $codePointPosition")
                    moveCursor(codePointPosition)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: focus out/in")
            resetComposingState()
            // cursor outside composing range, finish composing as-is
            currentInputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            postFcitxJob {
                focusOutIn()
            }
        }
    }

    // because setComposingText(text, cursor) can only put cursor at end of composing,
    // sometimes onUpdateSelection would receive event with wrong cursor position.
    // those events need to be filtered.
    // because of https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r45/core/java/android/view/inputmethod/BaseInputConnection.java#851
    // it's not possible to set cursor inside composing text
    private fun updateComposingText(text: FormattedText) {
        val ic = currentInputConnection ?: return
        val lastSelection = selection.latest
        ic.beginBatchEdit()
        try {
        if (composingText.spanEquals(text)) {
            // composing text content is up-to-date
            // update cursor only when it's not empty AND cursor position is valid
            if (text.length > 0 && text.cursor >= 0) {
                val p = text.cursor + composing.start
                if (p != lastSelection.start) {
                    Timber.d("updateComposingText: set Android selection ($p, $p)")
                    ic.setSelection(p, p)
                    selection.predict(p)
                }
            }
        } else {
            // composing text content changed
            Timber.d("updateComposingText: '$text' lastSelection=$lastSelection")
            if (text.isEmpty()) {
                if (composing.isEmpty()) {
                    // do not reset saved selection range when incoming composing
                    // and saved composing range are both empty:
                    // composing.start is invalid when it's empty.
                    selection.predict(lastSelection.start)
                } else {
                    // clear composing text, put cursor at start of original composing
                    selection.predict(composing.start)
                    composing.clear()
                }
                ic.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                // skip cursor reposition when:
                // - preedit cursor is at the end
                // - cursor position is invalid
                if (text.cursor == text.length || text.cursor < 0) {
                    selection.predict(composing.end)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                    ic.setSelection(p, p)
                }
            }
            Timber.d("updateComposingText: composing=$composing")
        }
        composingText = text
        } finally {
            // N63：无论中间 setComposingText/setSelection 是否抛异常（composing 区间失效、Binder 断开），
            // 都必须 endBatchEdit，否则目标编辑器永久停在 batch edit 态、后续输入不再刷新
            ic.endBatchEdit()
        }
    }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        composingText = FormattedText.Empty
        ic.finishComposingText()
    }

    // ============ MBM v1.0.47：滑动选词「实时预编辑上屏」 ============

    /**
     * 把当前选中的词块片段作为 composing 预编辑实时写入目标输入框（上屏预览）。
     * 滑动选词过程中每当选区变化就调用一次，目标框里即可看到「跟着手指走」的预览。
     * 空串或无输入连接时退化为取消。
     */
    fun setWordSelectPreview(text: String) {
        if (text.isEmpty()) {
            cancelWordSelectPreview()
            return
        }
        val ic = currentInputConnection ?: return
        // 预览起始点复用当前光标（首次）或上一次预览起点（连续滑动），
        // 不与 fcitx 的 composing 状态纠缠，互不污染。
        val start = if (wordSelectPreviewActive) wordSelectPreviewStart else selection.latest.start
        wordSelectPreviewStart = start
        wordSelectPreviewText = text
        wordSelectPreviewActive = true
        runCatching {
            ic.beginBatchEdit()
            try {
                ic.setComposingText(text, text.length)
            } finally {
                ic.endBatchEdit()
            }
        }.onFailure { Timber.e(it, "MBM: setWordSelectPreview 失败") }
    }

    /**
     * 取消预览：丢弃 composing 文本，输入框恢复原状（不提交任何内容）。
     * 用于滑动中途清空选区、或「复制」后保持原输入框干净。
     */
    fun cancelWordSelectPreview() {
        if (!wordSelectPreviewActive) return
        val ic = currentInputConnection
        wordSelectPreviewActive = false
        wordSelectPreviewText = ""
        if (ic != null) {
            runCatching {
                ic.beginBatchEdit()
                try {
                    ic.setComposingText("", 1)
                } finally {
                    ic.endBatchEdit()
                }
            }.onFailure { Timber.e(it, "MBM: cancelWordSelectPreview 失败") }
        }
        resetComposingState()
    }

    /**
     * 完成选词：把指定片段定稿上屏（commitText），并返回是否确有内容提交。
     * 不依赖预览状态，直接用面板传入的选区文本，确保「先复制后完成」等交叉操作也正确上屏。
     */
    fun commitWordSelectPreview(text: String): Boolean {
        if (text.isEmpty()) {
            wordSelectPreviewActive = false
            return false
        }
        val ic = currentInputConnection ?: run {
            wordSelectPreviewActive = false
            wordSelectPreviewText = ""
            return false
        }
        // 先把可能存在的实时预览清掉，再从干净光标处 commit，避免重复或错位。
        cancelWordSelectPreview()
        val start = selection.latest.start
        resetComposingState()
        val ok = runCatching {
            ic.beginBatchEdit()
            try {
                ic.commitText(text, 1)
            } finally {
                ic.endBatchEdit()
            }
        }.isSuccess
        if (ok) selection.predict(start + text.length)
        Timber.d("MBM: commitWordSelectPreview 提交「${text.take(16)}…」len=${text.length} ok=$ok")
        return ok
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // ignore inline suggestion when disabled by user || using physical keyboard with floating candidates view
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return null
        val theme = ThemeManager.activeTheme
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        // U8 修复：输入法退场即取消进行中的分块粘贴，避免剩余内容被写进上一个 App 的后台输入框
        pasteJob?.cancel()
        decorLocationUpdated = false
        inputDeviceMgr.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        resetComposingState()
        postFcitxJob {
            focusOutIn()
        }
        hideStatusIcon()
        showingDialog?.dismiss()
        inputView?.dismissCandidateActionMenu()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput")
        // U8 修复：输入法完全退场时取消进行中的分块粘贴
        pasteJob?.cancel()
        postFcitxJob {
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        cachedKeyEvents.evictAll()
        cachedKeyEventIndex = 0
        cursorUpdateIndex = 0
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        val uid = currentInputBinding?.uid ?: return
        Timber.d("onUnbindInput: uid=$uid")
        postFcitxJob {
            deactivate(uid)
        }
    }

    override fun onDestroy() {
        // MBM v1.0.36：解除 adb 调试桥接（仅当仍指向本实例）
        if (MbmDebug.bridge === this) MbmDebug.bridge = null
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        packageReceiver?.let { runCatching { unregisterReceiver(it) } }
        packageRefreshJob?.cancel()
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    // ============ MBM v1.0.36：MbmDebug 桥接（统一 adb 调试） ============
    /** MBM 内置自测：可达浮动候选视图（它是 contentView 下键盘 InputView 的兄弟节点，键盘树内收集不到）。 */
    fun selfTestCandidateView(): android.view.View? = candidatesView

    /** MBM v3.5：自测防递归标志——自测运行期间再次点击自测入口只打日志不重入。 */
    @Volatile
    var mbmSelfTestRunning: Boolean = false
        private set

    override fun runSelfTest(onDone: (MbmDebug.SelfTestReport) -> Unit) {
        if (mbmSelfTestRunning) {
            onDone(MbmDebug.SelfTestReport(
                listOf(MbmDebug.FeatureResult.fail(
                    "precondition", "engine",
                    "自测正在进行中，忽略重复触发（防递归）",
                    "FcitxInputMethodService.kt:runSelfTest mbmSelfTestRunning 守卫"
                )),
                listOf("selfTest: 已在运行，忽略重复触发"),
                SystemClock.elapsedRealtime()
            ))
            return
        }
        mbmSelfTestRunning = true
        lifecycleScope.launch {
            try {
                val report = inputView?.runMbmSelfTest() ?: MbmDebug.SelfTestReport(
                    listOf(MbmDebug.FeatureResult.fail(
                        "precondition", "engine",
                        "键盘窗口未打开（inputView 为空），自测引擎未执行",
                        "FcitxInputMethodService.kt:runSelfTest"
                    ))
                )
                MbmDebug.finishSelfTest(applicationContext, report)
                onDone(report)
            } finally {
                mbmSelfTestRunning = false
            }
        }
    }

    override fun stateDump(): String {
        val ei = currentInputEditorInfo
        val win = inputView?.debugWindowDump() ?: "window=none"
        val fcitxOk = FcitxDaemon.getFirstConnectionOrNull() != null
        return "pkg=${ei?.packageName} inputType=${ei?.inputType} $win fcitx=$fcitxOk"
    }

    override fun imeDump(): String {
        val ei = currentInputEditorInfo ?: return "无输入目标（键盘未关联输入框）"
        return "pkg=${ei.packageName} inputType=${ei.inputType} imeOptions=${ei.imeOptions} " +
            "hint=${ei.hintText} label=${ei.label} privateIme=${ei.privateImeOptions}"
    }

    override fun applyVerbose(verbose: Boolean) {
        Timber.setupForest(verbose)
        // release 下原生日志仍强制非 verbose（Fcitx.setLogRule），与开发者页开关一致
        runCatching { FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { setLogRule(verbose) } }
        Timber.tag(MbmDebug.TAG).w("verbose=$verbose 已应用（adb）")
    }

    override fun dumpLogcat(): String {
        val out = File(filesDir, "mbm_logcat.txt")
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
            val txt = p.inputStream.bufferedReader().readText()
            out.writeText(txt)
            "已导出 ${txt.length} 字符 => ${out.absolutePath}（adb run-as 可取）"
        }.getOrElse { "导出失败: ${it.message}" }
    }

    private var packageReceiver: BroadcastReceiver? = null
    private var packageRefreshJob: Job? = null

    private var showingDialog: Dialog? = null

    /** MBM v1.0.31：进行中的大文本分块粘贴（新粘贴会取消旧的） */
    private var pasteJob: Job? = null

    fun showDialog(dialog: Dialog) {
        try {
            showingDialog?.dismiss()
            dialog.window?.also {
                it.attributes.apply {
                    token = decorView.windowToken
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                }
                it.addFlags(
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                )
                it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
            }
            dialog.setOnDismissListener {
                showingDialog = null
            }
            dialog.show()
            showingDialog = dialog
        } catch (e: Throwable) {
            // 在部分 OEM（如 EMUI / Android 15）的 IME 窗口内，dialog.show() 可能因窗口令牌非法
            // 抛 WindowManager.BadTokenException。捕获并仅记录，避免整个输入法进程崩溃。
            Timber.e(e, "显示输入法对话框失败")
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val DefaultHighlightColor = 0x008577  // material_deep_teal_500
        const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"

        /** 大文本粘贴尾部校验：取原文末 64 字节 / 比对游标前 64 字符 */
        const val TAIL_CHECK_BYTES = 64
        const val TAIL_CHECK_CHARS = 64
    }
}
