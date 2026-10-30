/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 系统「高级保护模式」探测。
 *
 * Android 16（API 36）起提供 android.app.AdvancedProtectionManager，
 * 用户在系统设置开启「高级保护」后，平台会主动限制可能泄露屏幕内容的应用行为，
 * Google 明确把密码管理器、自动化工具排除在无障碍合法用途之外。
 *
 * 裁决（P0-2）：命中 APM 时 Recall **显式停用并提示**，不静默降级。
 * 用反射调用，保证低 compileSdk 也能编译、低版本设备上安全返回 false。
 */
package org.fcitx.fcitx5.android.recall

import android.content.Context
import timber.log.Timber

object AdvancedProtectionProbe {

    /** 系统是否带 AdvancedProtectionManager（API 36+） */
    val isSupported: Boolean = runCatching {
        Class.forName("android.app.AdvancedProtectionManager")
        true
    }.getOrDefault(false)

    /**
     * @return true = 用户开启了高级保护模式，Recall 必须停用。
     */
    fun isAdvancedProtectionEnabled(context: Context): Boolean {
        if (!isSupported) return false
        return runCatching {
            val clazz = Class.forName("android.app.AdvancedProtectionManager")
            val mgr = context.getSystemService(clazz) ?: return false
            val enabled = clazz.getMethod("isAdvancedProtectionEnabled").invoke(mgr)
            enabled as? Boolean ?: false
        }.onFailure {
            Timber.w(it, "APM probe failed, treat as disabled")
        }.getOrDefault(false)
    }
}
