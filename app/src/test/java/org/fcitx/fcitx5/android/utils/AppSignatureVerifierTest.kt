/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：签名自校验的「集合隶属」语义（v1.0.26 修复）。
 *
 * 旧实现把多签名者并集串 `"fp1|fp2|…"` 与单指纹基准做相等比较，
 * 永远对不上 → 密钥轮换 / 多签名者 APK 必误报重打包。
 */
class AppSignatureVerifierTest {

    @Test
    fun singleSignerMatches() {
        assertTrue(AppSignatureVerifier.signerMatches("ABC", setOf("ABC")))
    }

    @Test
    fun multiSignerRotationStillMatches() {
        // 密钥轮换期：APK 同时带旧证与新证，基准（旧证指纹）应命中集合
        assertTrue(AppSignatureVerifier.signerMatches("OLDKEY", setOf("OLDKEY", "NEWKEY")))
    }

    @Test
    fun joinedStringMustNotTrickOldBug() {
        // 旧 bug 的失败形态：基准若是并集串，绝不能靠「集合相等」蒙混过关
        assertFalse(AppSignatureVerifier.signerMatches("OLD|NEW", setOf("OLD", "NEW")))
    }

    @Test
    fun repackagedMismatch() {
        assertFalse(AppSignatureVerifier.signerMatches("OURS", setOf("ATTACKER")))
    }

    @Test
    fun emptySignersNeverMatches() {
        assertFalse(AppSignatureVerifier.signerMatches("OURS", emptySet()))
    }
}
