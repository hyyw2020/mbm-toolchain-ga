/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.34：主输入法本地数据加密密钥链路（R3 第一期）。
 *
 * 与 Recall 的 RecallKeyManager 同构（两个 APK 独立分发、不能共用代码），
 * 但刻意**不再使用** androidx.security.crypto 的 EncryptedSharedPreferences /
 * MasterKey——那两个 API 已被 Google 标记弃用（本轮登记为 R6），且对本品而言
 * 是冗余层：这里存放的「wrapped raw key + IV」本身已经过 Keystore 信封加密，
 * 再套一层 EncryptedSharedPreferences 不增加任何安全属性。
 *
 * 密钥链路（唯一一条，与 Recall 对齐）：
 *  1. raw key = 32 字节 SecureRandom 随机数（非口令派生）；
 *  2. raw key 由 Android Keystore 中的 AES-256-GCM 密钥信封加密
 *     （StrongBox 优先，设备不支持时回退 TEE；密钥不可导出）；
 *  3. 密文与 IV 以 Base64 存**普通** SharedPreferences——解开它需要
 *     Keystore 里的密钥材料，而那是 TEE/StrongBox 内的；
 *  4. setUserAuthenticationRequired(false)：IME 进程随时可被拉起/杀死，
 *     开库不能依赖用户当时在场（PIN/生物识别只放在 Recall 的 UI 门上）。
 *
 * 效果：文件被提取也解不开；换机/恢复出厂后 Keystore 密钥随设备销毁，
 * 旧密文不可恢复（重新生成 raw key，DB 按新库处理，blob 成为孤儿被 GC）。
 */
package org.fcitx.fcitx5.android.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppKeyManager {

    private const val KEY_ALIAS = "mbm_clip_data_key"
    private const val PREFS_NAME = "mbm_clip_secure_prefs"
    private const val KEY_WRAPPED = "wrapped_raw_key"
    private const val KEY_IV = "wrapped_iv"
    private const val KEY_SIZE = 32

    @Volatile
    private var cachedRawKey: ByteArray? = null

    /** raw key（32B）。进程内只解封一次；进程被杀后由 Keystore 重新解封。 */
    fun rawKey(context: Context): ByteArray {
        cachedRawKey?.let { return it }
        return synchronized(this) {
            cachedRawKey ?: loadOrCreate(context, prefs(context)).also { cachedRawKey = it }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadOrCreate(context: Context, prefs: SharedPreferences): ByteArray {
        // 已有密文 → 解封失败（Keystore 被清/换机恢复）时返回 null，调用方
        // 走「按新库处理」路径（DB 重建、blob 成孤儿由 GC 回收）。
        val wrappedB64 = prefs.getString(KEY_WRAPPED, null)
        val ivB64 = prefs.getString(KEY_IV, null)
        if (wrappedB64 != null && ivB64 != null) {
            val raw = runCatching {
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val data = Base64.decode(wrappedB64, Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
                    doFinal(data)
                }
            }.getOrNull()
            if (raw != null && raw.size == KEY_SIZE) return raw
            // 解封失败：旧密文已不可读，换新 key（旧 blob/DB 视为新库）
            Timber.i("AppKeyManager: unwrap failed, rotating key")
        }
        val raw = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        prefs.edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(cipher.doFinal(raw), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        return raw
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
