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
 *
 * v1.0.74 真 bug 修复：[maskSensitive] 必须由调用方按
 * `entry.sensitive && 用户打码偏好` 传入（见 [WordSelectMaskPolicy]），
 * 不能再直接取全局偏好——后者默认 true，会让所有普通条目的词块整屏变成
 * `•` 小点点、拆分选词完全不可用。默认值 false 保证新增调用点不会被误打码。
 */
class WordSelectWindow(
    private val source: String,
    private val maskSensitive: Boolean = false
) : InputWindow.ExtendedInputWindow<WordSelectWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val ui by lazy {
        WordSelectUi(context, theme, source, maskSensitive).apply {
            setContent(WordTokenizer.tokenize(source))
            // 滑动选词 → 实时上屏预览：选区变化即回填背景输入框的 composing 预编辑
            onSelectionChanged = { text ->
                if (text != null) service.setWordSelectPreview(text)
                else service.cancelWordSelectPreview()
            }
            copyButton.setOnClickListener {
                copySelected()
                // 复制到剪贴板即可，不自动上屏：清掉输入框里的实时预览，保持原输入框干净
                service.cancelWordSelectPreview()
            }
            doneButton.setOnClickListener {
                // 完成 = 把选中片段定稿上屏；无选区则直接关闭并清掉可能残留的预览
                val sel = selectedText()
                service.cancelWordSelectPreview()
                if (sel != null) service.commitWordSelectPreview(sel)
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
        // 退出滑动选词（如顶栏返回箭头）：清掉可能残留的上屏预览，避免输入框里挂着半截片段
        service.cancelWordSelectPreview()
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
