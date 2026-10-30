/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 启动签名自校验（MBM v1.0.26 / P2-7）。
 *
 * 「不加固」哲学下，这是唯一在启动时就能发现「APK 被换签名重打包」的检查：
 * 别人拿我们的 APK 改代码后用另一把 key 重签，Play Protect 未必拦得住，
 * 但签名指纹一定变。构建时把本变体签名证书的 SHA-256 写进 BuildConfig，
 * 运行时算自己的指纹和它比。
 *
 * 三条设计约束（缺一条都会变成新的崩溃源）：
 *  1. **只告警，不 crash**。误报（比如用户自己换了构建配置）就足以让输入法
 *     彻底不可用，代价远大于收益；发现不一致只记日志 + 置一个偏好标志。
 *  2. **BuildConfig.SIGNER_SHA256 为空时跳过**。本地/未配置 keystore 的构建
 *     注入空串，此时无基准可比，跳过而不是判失败。
 *  3. **不出现在日志明文之外的地方**。指纹不是敏感信息，但也不往 UI 上堆，
 *     只在设置页「关于」里以可复制文本形式展示，方便用户自己核对。
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.fcitx.fcitx5.android.BuildConfig
import java.security.MessageDigest

object AppSignatureVerifier {

    /** 偏好里的标志位：签名与构建时基准不一致 */
    const val PREF_SIGNATURE_MISMATCH = "signature_mismatch"

    /**
     * MBM v1.0.35：自校验总开关，**当前为 false（停用）**。
     * 构建时注入的 SIGNER_SHA256 是「当时那把 debug keystore」的指纹，而本版本起
     * APK 一律不签名、由使用者自行签名——任何人重签后此校验必然误报「疑似重打包」。
     * 既然分发模式已改为「无签名 + 使用者自签」，构建期基准失去了参照意义。
     * 类与 BuildConfig.SIGNER_SHA256 注入链保留，需要时把开关改回 true 即可单点恢复。
     */
    private const val ENABLED = false

    data class Result(
        val current: String,
        val expected: String,
        val mismatch: Boolean,
        /** 无基准可比（未注入指纹 / 系统不支持） */
        val skipped: Boolean,
        val detail: String
    ) {
        val ok: Boolean get() = skipped || !mismatch
    }

    /**
     * 取自身签名证书的指纹**集合**（大写 Hex，与 BuildConfig.SIGNER_SHA256 同格式）。
     *
     * 返回集合而非单串：支持多签名者 / 密钥轮换（v2/v3 签名方案）。
     * 构建时注入的 [BuildConfig.SIGNER_SHA256] 只是 keystore 首证书的单指纹，
     * 运行时必须与「当前 APK 实际签名者集合」做**隶属比较**（`expected in signers`），
     * 而非把并集串和单指纹做相等比较——后者在轮换期必不相等，会误报重打包。
     */
    fun selfSha256Set(context: Context): Set<String> {
        val pm = context.packageManager
        val pkg = context.packageName
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            }.getOrNull() ?: return emptySet()
            val signing = info.signingInfo ?: return emptySet()
            // 多签名者（轮换期）取实际签名者；单签名者取签名历史（含已轮换出的旧证）
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            (runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }.getOrNull() ?: return emptySet())
        }
        // PackageInfo.signatures 的可空性随 SDK 元数据版本波动，
        // 统一按可空防御（本类第 1 条约束：不能因自校验引入新崩溃）
        if (certs.isNullOrEmpty()) return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return certs.mapNotNull { sig ->
            runCatching { digest.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) } }
                .getOrNull()
        }.toSet()
    }

    /** 向下兼容 / 日志展示用：指纹并集串（不作为判据） */
    fun selfSha256(context: Context): String = selfSha256Set(context).joinToString("|")

    /**
     * 纯函数（可单测，不依赖 Android）：基准指纹是否命中「当前签名者集合」。
     *
     * 旧实现的 bug：把多签名者并集串（`"fp1|fp2|…"`）与单指纹基准做**相等**比较，
     * 结构上永远对不上 → 密钥轮换 / v2-v3 签名方案的 APK 必误报重打包。
     * 正确语义是**集合隶属**（`expected ∈ signers`），逐个比对。
     */
    internal fun signerMatches(expected: String, signers: Set<String>): Boolean = expected in signers

    /**
     * 执行自校验。
     *
     * @param expected 基准指纹；默认取构建时注入的 BuildConfig.SIGNER_SHA256
     */
    fun verify(context: Context, expected: String = BuildConfig.SIGNER_SHA256): Result {
        if (!ENABLED) {
            return Result(
                current = "",
                expected = "",
                mismatch = false,
                skipped = true,
                detail = "签名自校验已停用（v1.0.35 起 APK 无签名、由使用者自行签名）"
            )
        }
        val signers = runCatching { selfSha256Set(context) }.getOrDefault(emptySet())
        val current = signers.joinToString("|")
        if (expected.isBlank() || signers.isEmpty()) {
            return Result(
                current = current,
                expected = expected,
                mismatch = false,
                skipped = true,
                detail = if (expected.isBlank())
                    "构建时未注入签名基准，跳过自校验"
                else
                    "无法读取自身签名证书，跳过自校验"
            )
        }
        // 隶属比较：基准指纹在「当前签名者集合」中即视为一致（兼容密钥轮换）
        val mismatch = !signerMatches(expected, signers)
        return Result(
            current = current,
            expected = expected,
            mismatch = mismatch,
            skipped = false,
            detail = if (mismatch) "当前签名指纹与构建基准不一致（疑似重打包/换签名）" else "签名指纹与构建基准一致"
        )
    }
}
