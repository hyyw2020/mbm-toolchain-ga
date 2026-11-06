/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.AlertDialog
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.License
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addPreference

class LicensesFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        lifecycleScope.launch {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
                // MBM v1.0.28 / v1.0.72：本项目的许可与第三方声明**离线内置**在
                // assets/licenses/ 下（AGPL-3.0 全文 / NOTICE / 上游清单），点开即用
                // LicenseTextActivity 渲染，不跳浏览器、不联网。
                // v1.0.72 修复：下方 aboutLibraries 生成的第三方库条目原先点击会
                // startActivity(ACTION_VIEW, license.url) 跳到 spdx.org / zetetic.net，
                // 与「应用内离线查看」承诺冲突，且本应用无 INTERNET 权限、无浏览器的
                // 设备会抛未捕获 ActivityNotFoundException。改为应用内弹窗展示许可
                // 名称/SPDX 标识/出处文本，全程离线。
                addPreference(
                    title = getString(R.string.mbm_license),
                    summary = getString(R.string.mbm_license_summary)
                ) {
                    LicenseTextActivity.launch(
                        requireContext(),
                        LicenseTextActivity.ASSET_AGPL,
                        getString(R.string.mbm_license)
                    )
                }
                addPreference(
                    title = getString(R.string.third_party_notice),
                    summary = getString(R.string.third_party_notice_summary)
                ) {
                    LicenseTextActivity.launch(
                        requireContext(),
                        LicenseTextActivity.ASSET_NOTICE,
                        getString(R.string.third_party_notice)
                    )
                }
                addPreference(
                    title = getString(R.string.upstream_components),
                    summary = getString(R.string.upstream_components_summary)
                ) {
                    LicenseTextActivity.launch(
                        requireContext(),
                        LicenseTextActivity.ASSET_UPSTREAM,
                        getString(R.string.upstream_components)
                    )
                }
                val jsonString = resources.openRawResource(R.raw.aboutlibraries)
                    .bufferedReader()
                    .use { it.readText() }
                Libs.Builder()
                    .withJson(jsonString)
                    .build()
                    .libraries
                    .sortedBy {
                        if (it.tag == "native") it.uniqueId.uppercase() else it.uniqueId.lowercase()
                    }
                    .forEach {
                        addPreference(
                            title = "${it.uniqueId}:${it.artifactVersion}",
                            summary = it.licenses.joinToString { l -> l.spdxId ?: l.name }
                        ) {
                            showLicenseDialog(it.uniqueId, it.licenses)
                        }
                    }
            }
        }
    }

    private fun showLicenseDialog(uniqueId: String, licenses: Set<License>): Boolean {
        when (licenses.size) {
            0 -> {}
            1 -> showLicenseContent(uniqueId, licenses.first())
            else -> {
                val licenseArray = licenses.toTypedArray()
                val licenseNames = licenseArray.map { it.spdxId ?: it.name }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle(uniqueId)
                    .setItems(licenseNames) { _, idx ->
                        showLicenseContent(uniqueId, licenseArray[idx])
                    }
                    .setPositiveButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return true
    }

    /**
     * v1.0.72：纯离线展示。许可全文（AGPL-3.0）与组件清单已随 APK 附在前三项入口，
     * 这里只展示第三方库的许可名称 / SPDX 标识 / 官方出处文本，不再触发
     * ACTION_VIEW 跳转浏览器（应用无 INTERNET 权限，离线是硬约束）。
     */
    private fun showLicenseContent(uniqueId: String, license: License) {
        val label = (license.spdxId ?: license.name).ifBlank { license.name }
        val message = buildString {
            append("组件：").append(uniqueId).append('\n')
            append("许可：").append(license.name).append('\n')
            append("SPDX：").append(label)
            license.url?.takeIf { it.isNotBlank() }?.let {
                append("\n\n许可全文出处（仅文本展示，不跳转、不联网）：\n").append(it)
            }
        }
        AlertDialog.Builder(context)
            .setTitle(label)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

}