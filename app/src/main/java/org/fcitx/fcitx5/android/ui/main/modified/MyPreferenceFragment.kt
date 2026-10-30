/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

abstract class MyPreferenceFragment : PreferenceFragmentCompat() {
    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        // N102：dialog tag 原先直接用 Fragment 类名，同一页面里的 ListPreference 与
        // EditTextPreference 共用同一个 tag。先点开其中一个后，另一个会命中
        // findFragmentByTag != null 而直接 return，表现为「点了完全没反应」。
        // 这里把 preference 自己的 key 拼进 tag，让每个 preference 的对话框互不占用。
        val tag = dialogTag(preference)
        if (parentFragmentManager.findFragmentByTag(tag) != null) return
        val f = when (preference) {
            is EditTextPreference -> MyEditTextPreferenceDialogFragment.newInstance(preference.key)
            is ListPreference -> MyListPreferenceDialogFragment.newInstance(preference.key)
            else -> return super.onDisplayPreferenceDialog(preference)
        }
        f.setTargetFragment(this, 0)
        f.show(parentFragmentManager, tag)
    }

    /** N102：按 preference 生成唯一的 dialog tag；没有 key 时退回用 preference 类名兜底 */
    private fun dialogTag(preference: Preference): String =
        "${javaClass.name}#${preference.key ?: preference.javaClass.name}"
}