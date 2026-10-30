/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 独立 APK 的 Application。
 * 只提供进程级 applicationContext 与 SQLCipher 原生库的提前加载，
 * 不初始化任何主输入法的状态（本 APK 与主输入法完全解耦）。
 */
package org.fcitx.fcitx5.android.plugin.recall

import android.app.Application

class RecallApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 防御性：在任何数据库访问之前显式加载 SQLCipher 原生库。
        // SQLCipher 4.x 移除 loadLibs()，依赖 ART 首次 native 调用时自动加载；
        // 提前加载可以在打包缺 ABI 时 fail-fast（见主工程 pitfalls #2）。
        runCatching { System.loadLibrary("sqlcipher") }
    }

    companion object {
        lateinit var instance: RecallApplication
            private set
    }
}
