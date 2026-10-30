/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.os.Build
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import timber.log.Timber

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

abstract class ManagedPreferenceFragment(private val preferenceProvider: ManagedPreferenceProvider) :
    PaddingPreferenceFragment() {

    private val evaluator = ManagedPreferenceVisibilityEvaluator(preferenceProvider) {
        lifecycleScope.launch {
            it.forEach { (key, enable) ->
                findPreference<Preference>(key)?.isEnabled = enable
            }
        }
    }

    open fun onPreferenceUiCreated(screen: PreferenceScreen) {}

    @CallSuper
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        evaluator.evaluateVisibility()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(preferenceManager.context).also { screen ->
                preferenceProvider.createUi(screen)
                onPreferenceUiCreated(screen)
            }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // N97：DE 存储同步会阻塞磁盘 IO（首次还要等 XML 加载），
            // 放主线程会让每次退出设置页都掉帧，低端机上可 ANR。
            // 用 application 级 scope 承载，避免 Fragment 销毁时协程被取消导致不同步。
            appScope.launch(Dispatchers.IO) {
                try {
                    AppPrefs.getInstance().syncToDeviceEncryptedStorage()
                } catch (e: Exception) {
                    Timber.e(e, "syncToDeviceEncryptedStorage failed")
                }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        evaluator.destroy()
        super.onDestroy()
    }
}