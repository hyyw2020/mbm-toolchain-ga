/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import timber.log.Timber
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerWindow
import org.fcitx.fcitx5.android.input.menu.ImeMenuPopup
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private var backspaceSwipeState = Stopped

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate, except prediction candidates
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                // preedit not empty, maybe there are candidates to select ...
                select(0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        MbmDebug.logFeature("ime.picker")
        // MBM v0.0.09：输入法选择原用 service.showDialog 托管的 AlertDialog，其窗口是
        // 可抢焦点的独立窗口——在华为等 OEM ROM 上会令系统判定「IME 失焦」而强制收起
        // 输入法（用户报回「长按中英文切换键，输入法自动收起」，pitfalls #60，#55 同源）。
        // 改为面板内窗口（InputMethodPickerWindow）：不产生任何新窗口，标题栏返回键回键盘。
        ContextCompat.getMainExecutor(service).execute {
            windowManager.attachWindow(InputMethodPickerWindow())
        }
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    sendKey(action.act, action.states.states, action.code)
                }
                is SymAction -> service.postFcitxJob {
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    // MBM v1.0.25：提交前复位（select(0)+reset）用 runCatching 兜底，
                    // 保证即使 fcitx 处于候选为空/状态异常等边缘态抛错，句号等标点仍照常提交、
                    // 且不崩溃；commitText 自身也挂 imeExceptionHandler 双保险（见 postFcitxJob）。
                    runCatching { commitAndReset() }
                        .onFailure { Timber.e(it, "MBM: 提交前复位失败，已抑制以避免输入法崩溃") }
                    service.lifecycleScope.launch(imeExceptionHandler) { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    MbmDebug.logFeature("ime.switch")
                    when (langSwitchKeyBehavior) {
                                LangSwitchBehavior.Enumerate -> {
                                    service.postFcitxJob {
                                        try {
                                            if (enabledIme().size < 2) {
                                                // MBM v0.0.10：提示原用 service.showDialog 托管的
                                                // AlertDialog（可抢焦点独立窗口，pitfalls #60 同源）。
                                                // 改用 ImeMenuPopup.showBottom：贴面板底部、不抢焦点。
                                                ContextCompat.getMainExecutor(service).execute {
                                                    val items = listOf(
                                                        ImeMenuPopup.Item(
                                                            context.getString(R.string.add),
                                                            null
                                                        ) {
                                                            AppUtil.launchMainToInputMethodList(
                                                                context
                                                            )
                                                        },
                                                        ImeMenuPopup.Item(
                                                            context.getString(android.R.string.cancel),
                                                            null
                                                        ) {}
                                                    )
                                                    val menu = ImeMenuPopup(context, theme)
                                                    if (!menu.showBottom(
                                                            windowManager.view,
                                                            context.getString(R.string.add_more_input_methods),
                                                            items
                                                        )
                                                    ) {
                                                        // 极端 OEM 上弹窗失败：回退系统选择器（始终安全）
                                                        Timber.w("添加输入法提示无法显示，回退到系统选择器")
                                                        InputMethodUtil.showPicker()
                                                    }
                                                }
                                            } else {
                                                enumerateIme()
                                            }
                                } catch (e: Throwable) {
                                    // enabledIme()/enumerateIme() 任一 fcitx 调用异常都不得令输入法崩溃
                                    Timber.w(e, "枚举输入法失败，回退到系统选择器")
                                    InputMethodUtil.showPicker()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                    }
                }
                else -> {}
            }
        }
    }
}
