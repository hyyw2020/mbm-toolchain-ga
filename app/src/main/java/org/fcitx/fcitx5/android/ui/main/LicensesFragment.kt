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
                // MBM v1.0.90：许可页保持 aboutLibraries 原生组件列表 + 点击弹窗样式。
                // 本项目的 AGPL-3.0 全文 / NOTICE / 上游清单仍随 APK 分发在
                // assets/licenses/ 下，并由「关于」页的许可入口离线查看，无需在本页顶部重复罗列。
                // aboutLibraries 生成的第三方库条目点击弹窗展示许可名称 / SPDX 标识 /
                // 出处文本，全程离线（本应用无 INTERNET 权限，不跳浏览器）。
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
     * v1.0.90：纯离线展示。本项目许可全文 / NOTICE / 上游清单随 APK 分发在
     * assets/licenses/ 下并由「关于」页离线查看；这里只展示第三方库的许可名称 /
     * SPDX 标识 / 官方出处文本，不触发 ACTION_VIEW 跳转浏览器（应用无 INTERNET 权限）。
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