/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.view.KeyEvent
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class TextEditingWindow : InputWindow.ExtendedInputWindow<TextEditingWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val hapticOnRepeat by AppPrefs.getInstance().keyboard.hapticOnRepeat

    private val buttonRipple by ThemeManager.prefs.keyRippleEffect
    private val buttonBorder by ThemeManager.prefs.keyBorder
    private val buttonRadius by ThemeManager.prefs.textEditingButtonRadius

    private var hasSelection = false
    private var userSelection = false

    private fun sendDirectionKey(keyEventCode: Int) {
        service.sendCombinationKeyEvents(keyEventCode, shift = hasSelection || userSelection)
    }

    private val ui by lazy {
        TextEditingUi(context, theme, buttonRipple, buttonBorder, buttonRadius.toFloat()).apply {
            fun CustomGestureView.onClickWithRepeating(block: () -> Unit) {
                setOnClickListener { block() }
                repeatEnabled = true
                onRepeatListener = {
                    block()
                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(this)
                }
            }

            MbmDebug.markFeature(leftButton, "editing.move")
            leftButton.onClickWithRepeating {
                MbmDebug.logFeature("editing.move", "left")
                sendDirectionKey(KeyEvent.KEYCODE_DPAD_LEFT)
            }

            MbmDebug.markFeature(upButton, "editing.move")
            upButton.onClickWithRepeating {
                MbmDebug.logFeature("editing.move", "up")
                sendDirectionKey(KeyEvent.KEYCODE_DPAD_UP)
            }

            MbmDebug.markFeature(downButton, "editing.move")
            downButton.onClickWithRepeating {
                MbmDebug.logFeature("editing.move", "down")
                sendDirectionKey(KeyEvent.KEYCODE_DPAD_DOWN)
            }

            MbmDebug.markFeature(rightButton, "editing.move")
            rightButton.onClickWithRepeating {
                MbmDebug.logFeature("editing.move", "right")
                sendDirectionKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            }

            MbmDebug.markFeature(homeButton, "editing.move")
            homeButton.setOnClickListener {
                MbmDebug.logFeature("editing.move", "home")
                sendDirectionKey(KeyEvent.KEYCODE_MOVE_HOME)
            }
            MbmDebug.markFeature(endButton, "editing.move")
            endButton.setOnClickListener {
                MbmDebug.logFeature("editing.move", "end")
                sendDirectionKey(KeyEvent.KEYCODE_MOVE_END)
            }
            MbmDebug.markFeature(selectButton, "editing.select")
            selectButton.setOnClickListener {
                MbmDebug.logFeature("editing.select")
                if (hasSelection) {
                    userSelection = false
                    service.cancelSelection()
                } else {
                    userSelection = !userSelection
                    updateSelection(false, userSelection)
                }
            }
            MbmDebug.markFeature(selectAllButton, "editing.select")
            selectAllButton.setOnClickListener {
                MbmDebug.logFeature("editing.select", "all")
                // activate select button after operation
                userSelection = true
                service.currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }
            MbmDebug.markFeature(cutButton, "editing.cut")
            cutButton.setOnClickListener {
                MbmDebug.logFeature("editing.cut")
                // deactivate select button after operation
                userSelection = false
                service.currentInputConnection?.performContextMenuAction(android.R.id.cut)
            }
            MbmDebug.markFeature(copyButton, "editing.copy")
            copyButton.setOnClickListener {
                MbmDebug.logFeature("editing.copy")
                userSelection = false
                service.currentInputConnection?.performContextMenuAction(android.R.id.copy)
            }
            MbmDebug.markFeature(pasteButton, "editing.paste")
            pasteButton.setOnClickListener {
                MbmDebug.logFeature("editing.paste")
                userSelection = false
                service.currentInputConnection?.performContextMenuAction(android.R.id.paste)
            }
            MbmDebug.markFeature(backspaceButton, "editing.backspace")
            backspaceButton.onClickWithRepeating {
                MbmDebug.logFeature("editing.backspace")
                userSelection = false
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            MbmDebug.markFeature(clipboardButton, "editing.clipboard")
            clipboardButton.setOnClickListener {
                MbmDebug.logFeature("editing.clipboard")
                windowManager.attachWindow(ClipboardWindow())
            }
        }
    }

    override fun onCreateView(): View = ui.root

    override fun onAttached() {
        val range = service.currentInputSelection
        onSelectionUpdate(range.start, range.end)
    }

    override fun onDetached() {}

    override fun onSelectionUpdate(start: Int, end: Int) {
        hasSelection = start != end
        ui.updateSelection(hasSelection, userSelection)
    }

    override val title by lazy {
        context.getString(R.string.text_editing)
    }

    override fun onCreateBarExtension(): View = ui.extension
}
