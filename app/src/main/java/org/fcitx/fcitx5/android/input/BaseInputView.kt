/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.reloadPinyinCustomPhrase
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.menu.ImeMenuPopup
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: FcitxInputMethodService,
    val fcitx: FcitxConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    /**
     * Update UI (from cached events in FcitxAPI) to match fcitx's state, before ready to receive real events
     */
    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: FcitxEvent<*>)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
    }

    var handleEvents = false
        set(value) {
            field = value
            if (field) {
                onStartHandleFcitxEvent()
                if (eventHandlerJob == null) {
                    setupFcitxEventHandler()
                }
            } else {
                eventHandlerJob?.cancel()
                eventHandlerJob = null
            }
        }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx) }
    }

    private var candidateActionMenu: ImeMenuPopup? = null

    val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    /**
     * 候选操作菜单（长按候选词）。
     *
     * 历史坑（v0.0.05，见 pitfalls #55）：原实现用系统 `PopupMenu`，其内部
     * ListPopupWindow 是会**抢焦点**的独立窗口。在华为等 OEM ROM 上，IME 窗口内
     * 弹出独立窗口会被系统判定「IME 已失焦」而强制隐藏输入法；popup 消失后焦点
     * 虽回到 Editor（系统文本工具栏重新出现），IME 却不恢复——表现为「长按候选词
     * 点完菜单，键盘自动收起」（用户录屏取证：菜单弹出→消失→候选清空→键盘收起）。
     *
     * 现改为 [ImeMenuPopup]（`isFocusable = false` 的 PopupWindow，与
     * [TouchEventReceiverWindow] 同一思路）：不产生新的焦点窗口，IME 不会因此被
     * 系统收起。同款修复在 v0.0.09 复用到剪贴板菜单（pitfalls #60）。
     *
     * 另（用户反馈）：菜单除 native 候选动作外，若候选词文本已存在于自定义词组，
     * 追加「从自定义词组中删除」——native 只在候选本身是自定义词组时才返回删除
     * 动作，普通候选词此前被「置顶」过后菜单里也拿不到删除入口，用户只能进设置删。
     */

    fun showCandidateActionMenu(idx: Int, text: String, view: View) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        service.lifecycleScope.launch {
            val actions = fcitx.runOnReady { getCandidateActions(idx) }
            // native 的 PINYIN_DELETE_CUSTOMPHRASE(=2) 只在候选本身是自定义词组时
            // 才会出现在 actions 里；词组里已有同文本时不必重复追加。
            val alreadyPinned = withContext(Dispatchers.IO) {
                runCatching {
                    CustomPhraseManager.loadCached()?.any { it.value == text } == true
                }.getOrDefault(false)
            }
            val entries = mutableListOf<Pair<String, Runnable>>()
            actions.forEach { action ->
                val run = Runnable {
                    if (action.id == PINYIN_CUSTOMPHRASE) {
                        // 置顶后告知删除入口，闭环「便捷管理」诉求
                        Toast.makeText(
                            service,
                            "已置顶为自定义词组，长按该词可删除",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    triggerCandidateAction(idx, action.id)
                }
                entries += action.text to run
            }
            if (alreadyPinned && actions.none { it.id == PINYIN_DELETE_CUSTOMPHRASE }) {
                entries += "从自定义词组中删除" to Runnable { deleteCustomPhraseByValue(text) }
            }
            if (entries.isEmpty()) return@launch
            InputFeedbacks.hapticFeedback(view, longPress = true)
            val menu = ImeMenuPopup(themedContext, theme)
            val items = entries.map { (label, action) ->
                ImeMenuPopup.Item(label, null, action)
            }
            candidateActionMenu = if (menu.show(view, text, items)) menu else null
        }
    }

    /** App 层按文本删除自定义词组并通知 native reload（与设置页删除同一链路） */
    private fun deleteCustomPhraseByValue(value: String) {
        service.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // v1.0.41：读-改-写置于全局写锁内，避免与 Recall 写词 / 应用名场景重建并发丢条目
                CustomPhraseManager.withWriteLock {
                    runCatching {
                        // MBM v1.0.31：走缓存版 load/save，大词库下避免每次长按都全量 JNI load
                        val items =
                            CustomPhraseManager.loadCached() ?: return@runCatching false
                        val remaining = items.filterNot { it.value == value }
                        if (remaining.size == items.size) return@runCatching false
                        CustomPhraseManager.saveCached(remaining.toTypedArray())
                        true
                    }.getOrDefault(false)
                }
            }
            Toast.makeText(
                service,
                if (ok) "已从自定义词组删除「$value」" else "自定义词组中未找到「$value」",
                Toast.LENGTH_SHORT
            ).show()
            if (ok) fcitx.runIfReady { reloadPinyinCustomPhrase() }
        }
    }

    companion object {
        /**
         * native `PinyinActionableCandidateList` 的候选动作 id
         * （lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/im/pinyin/pinyincandidate.cpp
         *   enum { PINYIN_FORGET, PINYIN_CUSTOMPHRASE, PINYIN_DELETE_CUSTOMPHRASE }）。
         * 用于在 App 层判断 native 是否已返回删除动作，避免重复追加菜单项。
         */
        const val PINYIN_CUSTOMPHRASE = 1
        const val PINYIN_DELETE_CUSTOMPHRASE = 2
    }

    /** 输入结束 / 视图 detach 时关闭候选操作菜单（避免非焦点弹窗悬挂在已收起的 IME 上） */
    fun dismissCandidateActionMenu() {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
    }

    private val navbarBackground by ThemeManager.prefs.navbarBackground

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navbarBackground != ThemePrefs.NavbarBackground.Full) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        // use navigation bar insets when available
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        // in case navigation bar insets goes wrong (eg. on LineageOS 21+ with gesture navigation)
        // use mandatory system gesture insets
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            // check system gesture insets and fallback to navigation_bar_frame_height just in case
            val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, context.navbarFrameHeight())
            }
        }
        return insetsBottom
    }

    private val ignoreSystemWindowInsets by AppPrefs.getInstance().advanced.ignoreSystemWindowInsets

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ignoreSystemWindowInsets) {
            // suppress view's own onApplyWindowInsets
            setOnApplyWindowInsetsListener { _, insets -> insets }
        } else {
            // on API 35+, we must call requestApplyInsets() manually after replacing views,
            // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
            requestApplyInsets()
        }
    }

    override fun onDetachedFromWindow() {
        handleEvents = false
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        super.onDetachedFromWindow()
    }
}
