/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 隐私政策事实准确性契约（v1.0.91）：
 * 隐私政策文本随 APK 分发，其陈述必须与产品/发布事实一致：
 *  - 发布仓库可见性：GitHub 仓库已公开，不得再称「私有」。
 *  - 版本头必须反映当前发布版本，不得停留在历史版本。
 *  - 「开源许可」入口描述须与 v1.0.90 后的实际导航布局一致。
 *
 * 纯 JVM 契约测试，读取 assets/licenses/PRIVACY.txt 文本断言，不依赖 Android 运行时。
 */

package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert
import org.junit.Test
import java.io.File

class PrivacyPolicyAccuracyTest {

    private fun readPrivacy(): String {
        // 权威源在仓库根目录；assets 版由 syncLicenseAssets 任务从根目录复制而来。
        // 优先读根目录，避免依赖构建任务的同步时序。
        val candidates = listOf(
            File("../PRIVACY.txt"),
            File("PRIVACY.txt"),
            File("src/main/assets/licenses/PRIVACY.txt"),
            File("app/src/main/assets/licenses/PRIVACY.txt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("无法定位 PRIVACY.txt: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `发布仓库不得被描述为私有`() {
        val text = readPrivacy()
        Assert.assertFalse(
            "GitHub 仓库已改为 public，隐私政策不得再称「发布仓库（私有）」",
            text.contains("发布仓库（私有）")
        )
    }

    @Test
    fun `版本头须反映当前发布版本`() {
        val text = readPrivacy()
        Assert.assertTrue(
            "隐私政策版本头须标注当前版本 v1.0.91",
            text.contains("v1.0.91")
        )
    }

    @Test
    fun `开源许可入口描述须与实际导航一致`() {
        val text = readPrivacy()
        Assert.assertFalse(
            "AGPL/NOTICE/上游清单入口已移到「关于 → 许可」，不得再笼统称在「关于 → 开源许可」查看全部合规文本",
            text.contains("关于 → 开源许可") && text.contains("AGPL-3.0")
        )
    }
}
