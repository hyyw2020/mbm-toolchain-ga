/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 插件信任链（MBM v1.0.26 / P0-3）。
 *
 * 立项原因：「不加固」哲学下，唯一的有效加载门槛不是运行时对抗，而是
 * **加载前验证**。三层校验，任一不过即拒绝加载：
 *
 *   1. 签名白名单：plugin 包签名证书 SHA-256 必须命中内置白名单
 *      （优先 PackageManager.hasSigningCertificate，API 28+ 原子校验）；
 *      另有「同签名」策略：与主输入法同签名者自动可信（我们自建的所有
 *      官方插件插件包都属此类）。
 *   2. .so SHA-256：逐个 .so 计算 APK 内条目哈希，与白名单中记录比对。
 *      白名单里没记的插件走「仅记录日志」模式（fail-open 但可审计），
 *      记了的则 fail-close。
 *   3. dlopen 路径：native 库只允许来自 sourceDir 派生的
 *      applicationInfo.nativeLibraryDir / sourceDir，
 *      拒绝任何「任意字符串路径」加载。
 */
package org.fcitx.fcitx5.android.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class TrustResult(val trusted: Boolean, val reason: String) {
    companion object {
        fun ok(reason: String = "trusted") = TrustResult(true, reason)
        fun fail(reason: String) = TrustResult(false, reason)
    }
}

object PluginTrustChain {

    /** 官方插件包名 → .so 条目名 → 期望的 SHA-256（hex 小写）。
     *  发版时由 tools/plugins_so_manifest.py 生成填入；留空走 fail-open。 */
    private val EXPECTED_SO_SHA256: Map<String, Map<String, String>> = emptyMap()

    /** 独立签名的第三方插件白名单：包名 → 签名证书 SHA-256（hex 大写冒号分隔） */
    private val SIGNER_WHITELIST: Map<String, Set<String>> = emptyMap()

    private const val TAG = "MBM-PluginTrust"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 取 APK 签名证书（API 28+ 用 GET_SIGNING_CERTIFICATES） */
    @SuppressLint("PackageManagerGetSignatures")
    private fun signaturesOf(pm: PackageManager, pkg: String): Array<Signature>? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                arrayOf(signing.signingCertificateHistory?.firstOrNull() ?: signing.apkContentsSigners.first())
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
    }.getOrNull()

    /** 主输入法自身全部签名者指纹（用于「同签名即可信」策略，多签名者全量比对） */
    fun selfSignerSha256s(): Set<String> =
        signaturesOf(appContext.packageManager, BuildConfig.APPLICATION_ID)
            ?.mapNotNull { it.toByteArray()?.let(::sha256) }
            ?.toSet()
            ?: emptySet()

    /**
     * 包级签名校验。
     *
     * **MBM v1.0.35：签名校验停用**。分发模式已改为「APK 无签名 + 使用者自行签名」，
     * 主包与插件包可能用使用者自己的（不同）密钥分别签名，内置的「白名单 / 与主包
     * 同签名」判据都会把合法的自签插件拒之门外（这正是截图反馈「尚未安装」之外的
     * 第二层会挡人的地方）。这里直接信任，交由后续两层（.so 审计、dlopen 路径限定）
     * 继续兜底——.so 清单当前为空（fail-open 仅记录），dlopen 路径校验保持不变。
     *
     * 多签名者（APK Signature Scheme v2/v3 轮换历史，或同 APK 多签名者）场景下
     * **每一个签名者都必须可信**——只校验第一个等于给攻击者留门：
     * 白名单证书排在第一、自己的证书排第二即可绕过。
     *
     * @return TrustResult；trusted=false 时调用方不得加载该插件。
     */
    fun verifyPackage(pkg: String): TrustResult {
        // MBM v1.0.35：签名校验停用（见函数 KDoc）。原白名单 / 同签名逻辑保留在
        // verifyPackageInternal，需要恢复时把开关改回 true。
        if (!SIGNATURE_CHECK_ENABLED) {
            return TrustResult.ok("signature check disabled (user-signed builds)")
        }
        return verifyPackageInternal(pkg)
    }

    /** MBM v1.0.35：签名校验开关——false = 信任任意签名（使用者自签分发模式）。 */
    private const val SIGNATURE_CHECK_ENABLED = false

    private fun verifyPackageInternal(pkg: String): TrustResult {
        val pm = appContext.packageManager
        val sigs = signaturesOf(pm, pkg)
            ?: return TrustResult.fail("cannot read signing certificates of $pkg")
        val fps = sigs.mapNotNull { it.toByteArray()?.let(::sha256) }
        if (fps.isEmpty())
            return TrustResult.fail("empty signing certificate of $pkg")

        // 1) 显式白名单：全部签名者都必须命中
        val allowedUppercase = SIGNER_WHITELIST[pkg]
        if (allowedUppercase != null) {
            val fpsColon = fps.map { it.uppercase().chunked(2).joinToString(":") }
            return if (fpsColon.all { fpColon ->
                allowedUppercase.any { it.equals(fpColon, ignoreCase = true) }
            }) {
                TrustResult.ok("whitelisted signer(s) x${fps.size}")
            } else {
                TrustResult.fail("signer(s) not all in whitelist: ${fpsColon.joinToString("|")}")
            }
        }
        // 2) 同签名策略：官方插件的默认来源——签名者集合必须与主输入法完全一致
        val self = selfSignerSha256s()
        if (self.isNotEmpty() && fps.toSet() == self) {
            return TrustResult.ok("same signer set as main app (${fps.size})")
        }
        // 3) 默认拒绝（不加固哲学：只加载能验证的东西）
        return TrustResult.fail("unknown signer set for $pkg: ${fps.joinToString("|") { it.take(12) }}")
    }

    /** .so SHA-256 校验：白名单未记录该插件时仅告警不拦截（fail-open 可审计） */
    fun verifyNativeLibraries(pkg: String): TrustResult {
        val expected = EXPECTED_SO_SHA256[pkg] ?: run {
            Timber.tag(TAG).w("no .so manifest for $pkg, log-only mode")
            return TrustResult.ok("no-manifest log-only")
        }
        val pm = appContext.packageManager
        val apkPath = runCatching {
            pm.getApplicationInfo(pkg, 0).sourceDir
        }.getOrNull() ?: return TrustResult.fail("no apk path of $pkg")
        return runCatching {
            ZipFile(File(apkPath)).use { zip ->
                expected.forEach { (entry, want) ->
                    val got = zip.getInputStream(zip.getEntry(entry))?.use { input ->
                        sha256(input.readBytes())
                    } ?: return TrustResult.fail("missing native entry lib/$entry in $pkg")
                    if (got != want.lowercase()) {
                        return TrustResult.fail("sha256 mismatch of lib/$entry in $pkg")
                    }
                }
            }
            TrustResult.ok("so-hash matched (${expected.size})")
        }.getOrElse { TrustResult.fail("verify error: ${it.message}") }
    }

    /**
     * dlopen 路径校验：只接受由 sourceDir 派生的 nativeLibraryDir。
     * @return 规范化后的合法目录，或 null 表示拒绝。
     */
    fun allowedNativeLibraryDir(pkg: String, candidateDir: String): File? {
        val pm = appContext.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: return null
        val allowed = setOf(info.nativeLibraryDir, File(File(info.sourceDir).parentFile, "lib").absolutePath)
        val normalized = File(candidateDir).absolutePath
        if (normalized !in allowed) {
            Timber.tag(TAG).w("reject non-sourceDir native path for $pkg: $candidateDir")
            return null
        }
        return File(normalized).takeIf { it.isDirectory }
    }

    /** 组合校验：插件可加载前的唯一入口 */
    fun verifyPlugin(context: Context, pkg: String): TrustResult {
        val pkgResult = verifyPackage(pkg)
        if (!pkgResult.trusted) {
            Timber.tag(TAG).e("plugin $pkg REJECTED: ${pkgResult.reason}")
            return pkgResult
        }
        val soResult = verifyNativeLibraries(pkg)
        if (!soResult.trusted) {
            Timber.tag(TAG).e("plugin $pkg REJECTED: ${soResult.reason}")
            return soResult
        }
        Timber.tag(TAG).i("plugin $pkg trusted (${pkgResult.reason}; ${soResult.reason})")
        return TrustResult.ok("${pkgResult.reason}; ${soResult.reason}")
    }
}
