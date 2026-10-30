/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.ClipData
import android.os.Bundle
import android.os.Debug
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File

class DeveloperFragment : PaddingPreferenceFragment() {

    private lateinit var hprofFile: File
    private lateinit var launcher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) {
                hprofFile.delete()
                return@registerForActivityResult
            }
            val ctx = requireContext()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openOutputStream(uri)!!.use { o ->
                            hprofFile.inputStream().use { i -> i.copyTo(o) }
                        }
                    }
                } catch (e: Exception) {
                    ctx.toast(e)
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) {
                            hprofFile.delete()
                        }
                    }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.real_time_logs) {
                startActivity<LogActivity>()
            }
            // MBM v1.0.36：唯一 adb 调试指令入口（统一调试广播 MBM_DEBUG）。
            // 展示完整一整套 adb 测试指令，点击即复制到剪贴板。
            addPreference(Preference(context).apply {
                title = "adb 调试指令"
                summary = "复制完整一套测试指令（统一入口，不影响 UI 美观）"
                setOnPreferenceClickListener {
                    val cmd = MbmDebug.adbCommands()
                    runCatching {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("mbm-adb", cmd))
                    }
                    AlertDialog.Builder(context)
                        .setTitle("adb 调试指令（已复制到剪贴板）")
                        .setMessage(cmd)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    true
                }
            })
            addPreference(MySwitchPreference(context).apply {
                key = AppPrefs.getInstance().internal.verboseLog.key
                setTitle(R.string.verbose_log)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setOnPreferenceChangeListener { _, newValue ->
                    val verbose = (newValue as? Boolean) == true
                    Timber.setupForest(verbose)
                    FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                        setLogRule(verbose)
                    }
                    true
                }
            })
            addPreference(MySwitchPreference(context).apply {
                key = AppPrefs.getInstance().internal.editorInfoInspector.key
                setTitle(R.string.editor_info_inspector)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(R.string.restart_fcitx_instance) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.restart_fcitx_instance)
                    .setMessage(R.string.restart_fcitx_instance_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            FcitxDaemon.restartFcitx()
                            context.toast(R.string.done)
                        }
                    }
                    .show()
            }
            addPreference(R.string.delete_and_sync_data) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.delete_and_sync_data)
                    .setMessage(R.string.delete_and_sync_data_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                DataManager.deleteAndSync()
                            }
                            context.toast(R.string.synced)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPreference(R.string.clear_clb_db) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.clear_clb_db)
                    .setMessage(R.string.clear_clp_db_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                ClipboardManager.nukeTable()
                            }
                            context.toast(R.string.done)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPreference(R.string.capture_heap_dump) {
                val fileName = "${context.packageName}_${iso8601UTCDateTime()}.hprof"
                hprofFile = context.cacheDir.resolve(fileName)
                System.gc()
                Debug.dumpHprofData(hprofFile.absolutePath)
                launcher.launch(fileName)
            }
        }
    }

}