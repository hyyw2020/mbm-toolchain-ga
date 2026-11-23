/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 许可页布局契约（v1.0.90）：
 * 「许可」页面必须保持 aboutLibraries 原生的组件列表 + 点击弹窗样式，
 * 即截图中的干净列表（组件:版本 / SPDX 两行）与点击后的许可详情弹窗。
 * 不得在列表顶部插入额外的合成入口（MBM 许可协议 / 第三方许可声明 /
 * 上游组件清单），那些文本已随 APK 分发在 assets/licenses/ 下，无需在许可页重复罗列。
 *
 * 纯 JVM 源码契约测试，读取 LicensesFragment.kt 文本断言，不依赖 Android 运行时。
 */

package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert
import org.junit.Test
import java.io.File

class LicensesFragmentLayoutTest {

    private fun readSource(): String {
        // Gradle 的 JVM 单测工作目录为模块根（app/），兼容从仓库根执行的情形
        val candidates = listOf(
            File("src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt"),
            File("app/src/main/java/org/fcitx/fcitx5/android/ui/main/LicensesFragment.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("无法定位 LicensesFragment.kt: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `许可页不含三个自制头部条目`() {
        val src = readSource()
        listOf("R.string.mbm_license", "R.string.third_party_notice", "R.string.upstream_components")
            .forEach { token ->
                Assert.assertFalse(
                    "许可页不应插入合成头部条目，仍引用了 $token",
                    src.contains(token)
                )
            }
    }

    @Test
    fun `许可页保留 aboutLibraries 原生列表与点击弹窗`() {
        val src = readSource()
        Assert.assertTrue(
            "必须保留 aboutLibraries 组件列表解析",
            src.contains("Libs.Builder()")
        )
        Assert.assertTrue(
            "必须保留点击组件后的许可详情弹窗",
            src.contains("showLicenseDialog")
        )
        Assert.assertTrue(
            "组件列表必须按 uniqueId 排序",
            src.contains("sortedBy")
        )
    }

    @Test
    fun `合规文档离线查看入口须收敛到关于页`() {
        val candidates = listOf(
            File("src/main/java/org/fcitx/fcitx5/android/ui/main/AboutFragment.kt"),
            File("app/src/main/java/org/fcitx/fcitx5/android/ui/main/AboutFragment.kt")
        )
        val src = candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("无法定位 AboutFragment.kt")
        listOf("ASSET_AGPL", "ASSET_NOTICE", "ASSET_UPSTREAM").forEach { token ->
            Assert.assertTrue(
                "关于页必须保留合规文档 $token 的离线查看入口",
                src.contains(token)
            )
        }
    }
}
