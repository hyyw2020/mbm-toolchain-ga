/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
                // MBM v1.0.28：本项目的许可与第三方声明**离线内置**在 assets/licenses/ 下
                // （AGPL-3.0 全文 / NOTICE / 上游清单），点开即用 LicenseTextActivity 渲染，
                // 不跳浏览器、不联网——APK 内必须随附开源协议说明，这是合规硬要求。
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
            1 -> showLicenseContent(licenses.first())
            else -> {
                val licenseArray = licenses.toTypedArray()
                val licenseNames = licenseArray.map { it.spdxId ?: it.name }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle(uniqueId)
                    .setItems(licenseNames) { _, idx ->
                        showLicenseContent(licenseArray[idx])
                    }
                    .setPositiveButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return true
    }

    private fun showLicenseContent(license: License) {
        if (license.url?.isNotBlank() == true) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(license.url)))
        }
    }

}