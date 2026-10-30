/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.password.PasswordGeneratorActivity
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.RecallActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import timber.log.Timber
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        // AboutMenuProvider is tied to viewLifecycleOwner, so the about menu items
        // are automatically shown when this Fragment is visible and removed when it's not
        requireActivity().addMenuProvider(
            AboutMenuProvider(), viewLifecycleOwner, Lifecycle.State.STARTED
        )
    }

    private inner class AboutMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            }
            // MBM v1.0.36：移除主菜单「开发者」入口——设置页保留唯一入口
            // （SettingsRoute.Developer），避免「日志入口出现多个影响美观」。
            menu.item(R.string.about) {
                navigateWithAnim(SettingsRoute.About)
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute
    ) {
        addPreference(title, icon = icon) {
            navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory("Fcitx") {
                addDestinationPreference(
                    R.string.global_options,
                    R.drawable.ic_baseline_tune_24,
                    SettingsRoute.GlobalConfig
                )
                addDestinationPreference(
                    R.string.input_methods,
                    R.drawable.ic_baseline_language_24,
                    SettingsRoute.InputMethodList
                )
                addDestinationPreference(
                    R.string.addons,
                    R.drawable.ic_baseline_extension_24,
                    SettingsRoute.AddonList
                )
            }
            addCategory("Android") {
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.Theme
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.CandidatesWindow
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard,
                    SettingsRoute.Clipboard
                )
                addDestinationPreference(
                    R.string.emoji_and_symbols,
                    R.drawable.ic_baseline_emoji_symbols_24,
                    SettingsRoute.Symbol
                )
                addDestinationPreference(
                    R.string.plugins,
                    R.drawable.ic_baseline_android_24,
                    SettingsRoute.Plugin
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced
                )
            }
            // MBM：统一入口的三项实验能力与原生的密码生成器
            addCategory("MBM") {
                addPreference("ruleimport.action", R.string.rule_import, icon = R.drawable.ic_baseline_extension_24) {
                    RuleImportActivity.launch(requireContext())
                }
                addPreference("smartdict.action", R.string.smart_dict, icon = R.drawable.ic_baseline_language_24) {
                    // MBM v1.0.22：离线智能词库与「管理自定义词组」整合为同一「用户词库」屏
                    navigateWithAnim(SettingsRoute.PinyinCustomPhrase)
                }
                addPreference(R.string.recall, icon = R.drawable.ic_baseline_list_alt_24) {
                    // MBM 冻结方案：Recall 已内嵌主输入法 APK、与 IME 同进程，
                    // 不再有「独立 APK 是否安装」的判定，直接同进程拉起时间线界面。
                    runCatching { RecallActivity.launch(requireContext()) }
                        .onFailure {
                            Timber.e(it, "MBM: failed to start RecallActivity")
                            Toast.makeText(
                                requireContext(),
                                R.string.recall_launch_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
                addPreference(
                    "password.bridge",
                    R.string.privacy_and_password,
                    icon = R.drawable.ic_capslock_lock
                ) {
                    PasswordGeneratorActivity.launch(requireContext())
                }
                addPreference("handwriting.settings", R.string.handwriting, icon = R.drawable.ic_baseline_edit_24) {
                    HandwritingActivity.launch(requireContext())
                }
            }
        }
    }

    companion object {
        /**
         * MBM 冻结方案：RecallActivity 已内嵌主输入法 APK、与 IME 同进程，
         * 不再需要独立的插件包名常量（原 RECALL_PLUGIN_PACKAGE 已随跨包方案移除）。
         */
    }
}