/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.os.Build
import android.view.View
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.DynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.ui.main.settings.ProgressFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import timber.log.Timber

class InputMethodListFragment : ProgressFragment(), OnItemChangedListener<InputMethodEntry> {

    // N104：原先每次顺序变动都 launchOnReady 一次，快速拖动会并发多条写入；
    // 且 setEnabledIme() 之后再 enabledIme() 是第二次独立往返，写进系统的 subtype
    // 顺序可能是上一轮的。这里改成串行（新的写入等上一次写完）+ 只做一次往返：
    // 用本次提交的列表顺序直接同步 subtype，保证系统里的顺序与界面显示一致。
    private var writeJob: Job? = null

    private fun updateIMState() {
        if (!isInitialized) return
        val ordered = ui.entries.toList()
        val previous = writeJob
        writeJob = fcitx.lifecycleScope.launch {
            previous?.join()
            runCatching {
                fcitx.runOnReady {
                    setEnabledIme(ordered.map { it.uniqueName }.toTypedArray())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // N104：不再回查 enabledIme()，直接用本次写入的顺序同步 subtype
                        SubtypeManager.syncWith(ordered.toTypedArray())
                    }
                }
            }.onFailure {
                Timber.w(it, "MBM: N104 写入输入法顺序失败")
            }
        }
    }

    private lateinit var ui: BaseDynamicListUi<InputMethodEntry>

    override suspend fun initialize(): View {
        val available = fcitx.runOnReady { availableIme().toSet() }
        val initialEnabled = fcitx.runOnReady { enabledIme().toList() }
        ui = requireContext().DynamicListUi(
            mode = BaseDynamicListUi.Mode.ChooseOne {
                (available - entries.toSet()).toTypedArray()
            },
            initialEntries = initialEnabled,
            enableOrder = true,
            initSettingsButton = { entry ->
                setOnClickListener {
                    navigateWithAnim(
                        SettingsRoute.InputMethodConfig(entry.displayName, entry.uniqueName),
                    )
                }
            },
            show = { it.displayName }
        )
        ui.addOnItemChangedListener(this@InputMethodListFragment)
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

    override fun onStop() {
        if (::ui.isInitialized) {
            ui.exitMultiSelect()
        }
        super.onStop()
    }

    override fun onDestroy() {
        // N104：页面销毁后不再往 fcitx 写顺序
        writeJob?.cancel()
        writeJob = null
        if (::ui.isInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

    override fun onItemSwapped(fromIdx: Int, toIdx: Int, item: InputMethodEntry) {
        updateIMState()
    }

    override fun onItemAdded(idx: Int, item: InputMethodEntry) {
        updateIMState()
    }

    override fun onItemRemoved(idx: Int, item: InputMethodEntry) {
        updateIMState()
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, InputMethodEntry>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: InputMethodEntry, new: InputMethodEntry) {
        updateIMState()
    }
}