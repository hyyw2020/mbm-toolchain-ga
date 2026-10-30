/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.addon

import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.AddonInfo
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.CheckBoxListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.settings.ProgressFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

class AddonListFragment : ProgressFragment(), OnItemChangedListener<AddonInfo> {

    private lateinit var ui: BaseDynamicListUi<AddonInfo>

    private val addonDisplayNames = mutableMapOf<String, String>()

    // N103：原先每改一项就 launchOnReady 全量写一次，N 次写入之间既不保证顺序、
    // 失败也不回滚（界面已置灰但 fcitx 端没生效，用户还以为生效了）。
    // 这里把所有写入挂在同一条 Job 链上：新的写入必定等上一次写完再执行，
    // 因此最终状态一定是最后一次操作的结果。
    private var writeJob: Job? = null

    // N103：批量关闭被依赖的 addon 时，逐项 updateItem 会触发 N 次全量写入。
    // 置位期间跳过单项回调里的写入，改完 UI 后统一写一次。
    private var batching = false

    private fun updateAddonState() {
        if (!isInitialized || batching) return
        // 写入前的快照，失败时用于把界面回滚到 fcitx 实际持有的状态
        val snapshot = ui.entries.toList()
        val ids = snapshot.map { it.uniqueName }.toTypedArray()
        val state = snapshot.map { it.enabled }.toBooleanArray()
        val previous = writeJob
        writeJob = fcitx.lifecycleScope.launch {
            previous?.join()
            runCatching { fcitx.runOnReady { setAddonState(ids, state) } }
                .onFailure { t ->
                    Timber.w(t, "MBM: N103 写入 addon 状态失败，回滚界面")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isInitialized) return@withContext
                        batching = true
                        snapshot.forEachIndexed { idx, info ->
                            if (ui.entries.getOrNull(idx)?.enabled != info.enabled) {
                                ui.updateItem(idx, info)
                            }
                        }
                        batching = false
                        // N103：失败必须让用户看见，而不是静默不生效
                        appContext.toast("插件开关写入失败，已恢复为原状态")
                    }
                }
        }
    }

    private fun disableAddon(entry: AddonInfo, reset: () -> Unit) {
        val dependents = fcitx.runImmediately { getAddonReverseDependencies(entry.uniqueName) }
        if (dependents.isNotEmpty()) {
            fun f(depTy: FcitxAPI.AddonDep) =
                dependents.mapNotNull {
                    it.takeIf { x -> x.second == depTy }
                        ?.first
                        ?.let { u -> it.first to (addonDisplayNames[u] ?: u) }
                }

            fun mkStr(list: List<Pair<String, String>>, @StringRes template: Int) =
                list.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { it.second }
                    ?.let { getString(template, it) }

            val dep = f(FcitxAPI.AddonDep.Required)
            val depU = dep.map { it.first }.toSet()
            val depStr = mkStr(dep, R.string.disable_addon_warn_dep)
            val optDepStr = mkStr(f(FcitxAPI.AddonDep.Optional), R.string.disable_addon_warn_optdep)

            if (depStr != null || optDepStr != null) {
                val msg = buildString {
                    appendLine(getString(R.string.disable_addon_warn_name, entry.displayName))
                    depStr?.let {
                        append("- ")
                        appendLine(it)
                    }
                    optDepStr?.let {
                        append("- ")
                        appendLine(it)
                    }
                    appendLine(getString(R.string.disable_addon_warn_confirm))
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.disable_addon_warn_title))
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setMessage(msg)
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        reset()
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        // N103：原来逐项 updateItem，每改一项就触发一次全量异步写入
                        // （N 次写、无顺序保证）。改为先把界面批量改完（期间不写），
                        // 最后只写一次。
                        batching = true
                        ui.entries.toList().forEachIndexed { idx, addonInfo ->
                            if (addonInfo.uniqueName in depU) {
                                ui.updateItem(idx, addonInfo.copy(enabled = false))
                            }
                        }
                        ui.updateItem(ui.indexItem(entry), entry.copy(enabled = false))
                        batching = false
                        updateAddonState()
                    }
                    .show()
            } else {
                ui.updateItem(ui.indexItem(entry), entry.copy(enabled = false))
            }
        } else {
            ui.updateItem(ui.indexItem(entry), entry.copy(enabled = false))
        }
    }

    override suspend fun initialize(): View {
        ui = requireContext().CheckBoxListUi(
            initialEntries = fcitx.runOnReady {
                addons()
                    .sortedBy { it.uniqueName }
                    .onEach { addonDisplayNames[it.uniqueName] = it.displayName }
            },
            initCheckBox = { entry ->
                // our addon shouldn't be disabled
                isEnabled = entry.uniqueName != "androidfrontend"
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    if (!isChecked)
                        disableAddon(entry) { this.isChecked = true }
                    else
                        ui.updateItem(ui.indexItem(entry), entry.copy(enabled = true))
                }
            },
            initSettingsButton = { entry ->
                visibility =
                    if (entry.isConfigurable &&
                        entry.enabled &&
                        // we disable clipboard addon config since we take over the control
                        entry.uniqueName != "clipboard"
                    ) View.VISIBLE else View.INVISIBLE
                setOnClickListener {
                    navigateWithAnim(
                        SettingsRoute.AddonConfig(entry.displayName, entry.uniqueName)
                    )
                }
            },
            show = { it.displayName }
        )
        ui.addOnItemChangedListener(this)
        return ui.root
    }

    override fun onItemUpdated(idx: Int, old: AddonInfo, new: AddonInfo) {
        updateAddonState()
    }

    override fun onDestroy() {
        // N103：页面销毁后不要再往 fcitx 写状态
        writeJob?.cancel()
        writeJob = null
        if (isInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

}