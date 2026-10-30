/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM 设置类 Activity 的通用 UI 辅助：
 * - fitInsets：适配 edge-to-edge（targetSdk 35+ 系统强制沉浸式），
 *   避免内容被状态栏/导航栏遮挡（这是之前标题被状态栏时间压住的根因）。
 * - dp：dp → px 转换。
 *
 * 注意：这些 Activity 使用 Theme.MbmActivity（Material3 后代）。
 * 沉浸模式下必须手动把 system bars 的 inset 加到根 ScrollView 的 padding 上，
 * 否则标题会顶到状态栏下方。
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object MbmActivityUi {

    /**
     * 让传入的 ScrollView 吸收系统栏 insets，内容不再被状态栏/导航栏遮挡，
     * 并在深色背景上使用浅色系统栏图标。
     *
     * ⚠️ 关键约束（已踩坑，见 docs/pitfalls.md #5）：
     * WindowInsetsControllerCompat 的构造会走到 PhoneWindow.getInsetsController()，
     * 后者直接解引用 mDecor（DecorView）。只有在 setContentView 之后 DecorView 才存在，
     * 否则在 API 35+（Impl35→Impl31→Impl30）上会 NPE。因此「设置系统栏图标深浅」这一步
     * 必须延迟到视图挂接（DecorView 就绪）后再执行：已挂接则立即执行，否则用
     * addOnAttachStateChangeListener 等 onAttach。绝不能在 onCreate 里、setContentView 之前
     * 直接 new WindowInsetsControllerCompat。
     */
    fun fitInsets(activity: android.app.Activity, scroll: View) {
        // setDecorFitsSystemWindows 作用在 Window 上，不依赖 DecorView，onCreate 任意时机均可调用
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        // 设置系统栏图标深浅：必须在 DecorView 就绪后。
        // 所有调用点（4 个 MBM Activity）已在 setContentView 之后调用，故此处通常立即执行；
        // 用 isAttachedToWindow 兜底「早于 setContentView 调用」的情形（等 onAttach 后再设）。
        val applyAppearance: () -> Unit = {
            WindowInsetsControllerCompat(activity.window, scroll).apply {
                // 深色背景上使用浅色状态栏/导航栏图标（false = 浅色内容）
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        if (scroll.isAttachedToWindow) {
            applyAppearance()
        } else {
            scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    applyAppearance()
                    scroll.removeOnAttachStateChangeListener(this)
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
        // 「避开系统栏」只靠 insets 监听改 padding，不碰 WindowInsetsController
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 0, 0, 0))
                .build()
        }
    }

    fun dp(view: View, v: Int): Int = (v * view.resources.displayMetrics.density).toInt()

    @Suppress("unused")
    private fun unusedGuard(activity: Activity?) {}
}
