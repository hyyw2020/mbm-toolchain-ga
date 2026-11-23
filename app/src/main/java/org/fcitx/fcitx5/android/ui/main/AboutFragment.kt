/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class AboutFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy) {
                // MBM v1.0.32（隐私合规）：改为展示本产品的隐私政策全文。
                // 原实现跳转上游 fcitx5-android 的政策页，既需要联网，也未覆盖
                // 本产品特有的数据处理（屏幕记忆捕获、剪贴板历史、手写、密码
                // 生成器）。政策文本随 APK 分发（assets/licenses/PRIVACY.txt），
                // 离线可查；上游政策仅作附注链接保留在文末。
                LicenseTextActivity.launch(
                    requireContext(),
                    LicenseTextActivity.ASSET_PRIVACY,
                    getString(R.string.privacy_policy)
                )
            }
            addPreference(
                R.string.open_source_licenses,
                R.string.licenses_of_third_party_libraries
            ) {
                navigateWithAnim(SettingsRoute.License)
            }
            addPreference(R.string.source_code, R.string.github_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.githubRepo)))
            }
            // MBM v1.0.90：本项目许可全文 / NOTICE / 上游组件的离线查看入口收敛到
            // 本条目（弹窗选择），使「许可」页得以保持 aboutLibraries 原生的干净列表。
            // 三个文本仍随 APK 分发在 assets/licenses/ 下，全程离线，不跳浏览器。
            addPreference(R.string.license, Const.licenseSpdxId) {
                val titles = arrayOf(
                    getString(R.string.mbm_license),
                    getString(R.string.third_party_notice),
                    getString(R.string.upstream_components)
                )
                val assets = arrayOf(
                    LicenseTextActivity.ASSET_AGPL,
                    LicenseTextActivity.ASSET_NOTICE,
                    LicenseTextActivity.ASSET_UPSTREAM
                )
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.license)
                    .setItems(titles) { _, index ->
                        LicenseTextActivity.launch(requireContext(), assets[index], titles[index])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addCategory(R.string.version) {
                isIconSpaceReserved = false
                addPreference(R.string.current_version, Const.versionName)
                addPreference(R.string.build_git_hash, BuildConfig.BUILD_GIT_HASH) {
                    val commit = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
                    val uri = Uri.parse("${Const.githubRepo}/commit/${commit}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_time, formatDateTime(BuildConfig.BUILD_TIME))
            }
        }
    }
}
