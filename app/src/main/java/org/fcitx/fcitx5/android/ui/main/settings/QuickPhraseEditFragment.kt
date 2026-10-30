/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhrase
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseData
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseEntry
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.setPaddingDp
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class QuickPhraseEditFragment : ProgressFragment(), OnItemChangedListener<QuickPhraseEntry> {
    private val args by lazyRoute<SettingsRoute.QuickPhraseEdit>()

    private val quickPhrase: QuickPhrase by lazy {
        args.param.quickPhrase
    }

    private lateinit var ui: BaseDynamicListUi<QuickPhraseEntry>

    private val dustman = NaiveDustman<QuickPhraseEntry>()

    // N50：保存中的标志。原实现没有任何复位保护，一旦保存抛异常就永久卡在 true、之后再也存不进去
    private val busy = AtomicBoolean(false)
    private var pendingSave: Job? = null

    override suspend fun initialize(): View {
        val initialEntries = withContext(Dispatchers.IO) {
            quickPhrase.loadData()
        }
        ui = object : BaseDynamicListUi<QuickPhraseEntry>(
            requireContext(),
            Mode.FreeAdd("", converter = { QuickPhraseEntry("", "") }),
            initialEntries,
        ) {
            override fun showEntry(x: QuickPhraseEntry): String = x.run {
                "$keyword → ${phrase.replace("\n", "\\n")}"
            }

            override fun showEditDialog(
                title: String,
                entry: QuickPhraseEntry?,
                block: (QuickPhraseEntry) -> Unit
            ) {
                val (keywordLayout, keywordField) = materialTextInput {
                    setHint(R.string.quickphrase_keyword)
                }
                keywordField.apply {
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                val (phraseLayout, phraseField) = materialTextInput {
                    setHint(R.string.quickphrase_phrase)
                }
                entry?.apply {
                    keywordField.setText(keyword)
                    phraseField.setText(phrase)
                }
                val layout = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(keywordLayout, lParams(matchParent))
                    add(phraseLayout, lParams(matchParent))
                }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .onPositiveButtonClick onClick@{
                        val keyword = keywordField.str.trim()
                        // "keyword" cannot contain any black characters
                        if (keyword.isBlank()) {
                            keywordField.error = getString(
                                R.string._cannot_be_empty,
                                getString(R.string.quickphrase_keyword)
                            )
                            keywordField.requestFocus()
                            return@onClick false
                        } else {
                            keywordField.error = null
                        }
                        // "phrase" may contain blank characters
                        val phrase = phraseField.str
                        if (phrase.isEmpty()) {
                            phraseField.error = getString(
                                R.string._cannot_be_empty,
                                getString(R.string.quickphrase_phrase)
                            )
                            phraseField.requestFocus()
                            return@onClick false
                        } else {
                            phraseField.error = null
                        }
                        block(QuickPhraseEntry(keyword, phraseField.str))
                        return@onClick true
                    }
                    .setCanceledOnTouchOutside(false)
            }
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        resetDustman()
        ui.setViewModel(viewModel)
        viewModel.toolbarButton.value =
            if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
        return ui.root
    }

    // The dustman didn't work well in this case,
    // as one key may map to many phrases

    override fun onItemAdded(idx: Int, item: QuickPhraseEntry) {
        dustman.addOrUpdate(item.serialize(), item)
        scheduleSave()
    }

    override fun onItemRemoved(idx: Int, item: QuickPhraseEntry) {
        dustman.remove(item.serialize())
        scheduleSave()
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, QuickPhraseEntry>>) {
        batchRemove(indexed)
        scheduleSave()
    }

    override fun onItemUpdated(idx: Int, old: QuickPhraseEntry, new: QuickPhraseEntry) {
        dustman.addOrUpdate(new.serialize(), new)
        scheduleSave()
    }

    /**
     * N50：原实现只在 onStop 保存 —— 系统回收/直接 finish 时 onStop 不保证被调用，改动就丢了。
     * 改为「每次改动后防抖落盘」+「onPause 强制 flush」，不再依赖退出时机。
     */
    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = lifecycleScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveConfig()
        }
    }

    private fun saveConfig() {
        if (!dustman.dirty) return
        // N50：并发写同一文件没有意义，让上一次写完后重试
        if (!busy.compareAndSet(false, true)) {
            scheduleSave()
            return
        }
        resetDustman()
        // N50：NonCancellable 让写盘脱离 lifecycleScope 的父 Job，
        // 退出页面时不会因为协程被取消而写一半 / 根本没写
        lifecycleScope.launch(NonCancellable) {
            try {
                val snapshot = ui.entries.toList()
                withContext(Dispatchers.IO) {
                    quickPhrase.saveData(QuickPhraseData(snapshot))
                }
                // tell parent that we need to reload
                parentFragmentManager.setFragmentResult(
                    RESULT,
                    Bundle().apply { putParcelable(RESULT, quickPhrase) }
                )
            } catch (e: Exception) {
                Timber.e(e, "N50: 快捷短语保存失败")
                // 复位脏标记，让后续改动还能再触发一次保存
                dustman.forceDirty()
            } finally {
                // N50：无论成功失败都必须复位，否则 busy 永久为 true
                busy.set(false)
            }
        }
    }

    /** N50：退出前取消防抖并立刻落盘 */
    private fun flushPendingSave() {
        pendingSave?.cancel()
        pendingSave = null
        saveConfig()
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.associateBy { it.serialize() })
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(quickPhrase.name)
    }

    override fun onPause() {
        // N50：onPause 一定会被调用（onStop 不一定），在这里把未落盘的改动写完
        if (isInitialized) {
            flushPendingSave()
        }
        super.onPause()
    }

    override fun onStop() {
        if (isInitialized) {
            ui.exitMultiSelect()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

    companion object {
        const val RESULT = "dirty"

        // N50：变更后防抖落盘的窗口
        private const val SAVE_DEBOUNCE_MS = 500L
    }

}