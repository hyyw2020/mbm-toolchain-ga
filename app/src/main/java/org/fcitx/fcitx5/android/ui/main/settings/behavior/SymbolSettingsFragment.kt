/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.CustomEmojiImportActivity
import org.fcitx.fcitx5.android.utils.addPreference

class SymbolSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().symbols) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // v1.0.73：自定义图像表情包的导入 / 管理入口。资产全部由用户运行时导入，
        // 设置项本身不携带任何第三方专有素材。
        screen.addPreference(
            R.string.custom_emoji_import,
            R.string.custom_emoji_import_summary
        ) {
            CustomEmojiImportActivity.launch(requireContext())
        }
    }
}
