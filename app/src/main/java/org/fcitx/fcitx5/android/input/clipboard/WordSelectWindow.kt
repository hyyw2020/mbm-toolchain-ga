/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.ClipData
import android.content.ClipboardManager as SystemClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import org.mechdancer.dependency.manager.must
import timber.log.Timber

/**
 * MBM v1.0.45：「滑动选词」窗口（拆分选词）。
 *
 * 从剪贴板条目长按菜单「拆分选词」进入：把条目全文按词典拆成词块，
 * 滑动选中片段后「复制」——片段写入系统剪贴板并登记为新的剪贴板条目，
 * 「完成」或顶栏返回箭头退出。复制行为不改动原条目。
 *
 * 顶栏标题与返回箭头由 [InputWindow.ExtendedInputWindow] 公共栏提供。
 */
class WordSelectWindow(private val source: String) :
    InputWindow.ExtendedInputWindow<WordSelectWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val maskSensitive by AppPrefs.getInstance().clipboard.clipboardMaskSensitive

    private val ui by lazy {
        WordSelectUi(context, theme, source, maskSensitive).apply {
            setContent(WordTokenizer.tokenize(source))
            copyButton.setOnClickListener {
                copySelected()
            }
            doneButton.setOnClickListener {
                windowManager.attachWindow(ClipboardWindow())
            }
        }
    }

    override val title: String by lazy {
        context.getString(R.string.slide_select_words)
    }

    override fun onCreateView(): View = ui.root

    override fun onAttached() = Unit

    override fun onDetached() {
        ui.release()
    }

    /**
     * 复制选中片段：
     *  1. 写系统剪贴板（IME 在前台时允许写入）；
     *  2. 登记（去重）为剪贴板面板条目，回到面板即可一键上屏；
     *  3. Toast「已复制」，面板保持打开，可继续调整选区。
     */
    private fun copySelected() {
        val selected = ui.selectedText()
        if (selected == null) {
            Toast.makeText(context, R.string.word_select_empty_hint, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as SystemClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(null, selected))
        }.onFailure {
            // 系统剪贴板写入失败不致命：条目仍会登记进面板
            Timber.w(it, "滑动选词写系统剪贴板失败")
        }
        service.lifecycleScope.launch(imeExceptionHandler) {
            runCatching { ClipboardManager.addText(selected) }
                .onFailure { Timber.e(it, "滑动选词登记条目失败") }
        }
        Toast.makeText(context, R.string.word_select_copied, Toast.LENGTH_SHORT).show()
    }
}
