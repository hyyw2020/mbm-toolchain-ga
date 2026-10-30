/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.utils.AppSignatureVerifier
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.CrashLogSanitizer
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.utils.ProcessExitAttributor
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.userManager
import timber.log.Timber
import kotlin.system.exitProcess

class FcitxApplication : Application() {

    val coroutineScope = MainScope() + CoroutineName("FcitxApplication")

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            Timber.d("Device shutting down, trying to save fcitx state...")
            val fcitx = FcitxDaemon.getFirstConnectionOrNull()
                ?: return Timber.d("No active fcitx connection, skipping")
            fcitx.runImmediately { save() }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            FcitxDaemon.getFirstConnectionOrNull()?.also {
                // try to shutdown fcitx gracefully
                FcitxDaemon.stopFcitx()
            }
            AppUtil.exit()
        }
    }

    private val restartFcitxInstanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESTART_FCITX_INSTANCE) return
            if (FcitxDaemon.getFirstConnectionOrNull() != null) {
                Timber.i("Received broadcast '${intent.action}', try to restart fcitx instance ...")
                FcitxDaemon.restartFcitx()
            } else {
                Timber.i("Received broadcast '${intent.action}', but there's no fcitx instance")
            }
        }
    }

    /**
     * MBM v1.0.36：统一 adb 调试广播（唯一外部调试入口，见 utils/MbmDebug.kt）。
     * adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd state
     */
    private val mbmDebugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MBM_DEBUG) return
            MbmDebug.dispatch(context, intent)
        }
    }

    var isDirectBootMode = false
        private set

    val directBootAwareContext: Context
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

    override fun onCreate() {
        super.onCreate()
        // MBM v1.0.26 曾把 SQLCipher 拆给 Recall 独立 APK，但主输入法的剪贴板库
        // （data/clipboard/ClipboardManager.kt）仍持有 SQLCipher 加密 Room 数据库，
        // 必须在此提前显式加载，否则首次访问会 UnsatisfiedLinkError
        // （No implementation found ... SQLiteConnection.nativeOpen）。
        // SQLCipher 4.x 已移除 loadLibs()，依赖 ART 自动加载；此处显式加载只是保险，
        // 但必须在任何数据库访问之前（AGENTS.md「数据库/原生库硬性约束」#1）。
        runCatching { System.loadLibrary("sqlcipher") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val ctx = directBootAwareContext

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                val crashTime = System.currentTimeMillis()
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPreferences.getLong(lastCrashTimePrefKey, -1L)
                // make sure it was written to persistent storage
                sharedPreferences.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                startActivity<LogActivity> {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(LogActivity.FROM_CRASH, true)
                    // avoid transaction overflow
                    // MBM v1.0.26 / P2-7：先脱敏再截断 —— 输入法崩溃栈里必然夹带
                    // 用户输入内容（commitText("刚打的半句话")、FormattedText(strings=[...])）。
                    // 顺序必须是「脱敏 → 截断」：反过来截断会把字面量截半，
                    // 正则匹配不到闭合引号，脱敏规则直接失效。
                    val sanitized = CrashLogSanitizer.sanitize(e.stackTraceToString())
                    val truncated = sanitized.let {
                        if (it.length > MAX_STACKTRACE_SIZE)
                            it.take(MAX_STACKTRACE_SIZE) + "<truncated>"
                        else
                            it
                    }
                    putExtra(LogActivity.CRASH_STACK_TRACE, truncated)
                }
                exitProcess(10)
            }
        }

        instance = this
        // we don't have AppPrefs available yet
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        Timber.setupForest(verbose = sharedPrefs.getBoolean("verbose_log", false))

        Timber.d("isDirectBootMode=$isDirectBootMode")

        // MBM v1.0.26（P2-7）启动签名自校验：APK 是否还是我们签的那一个。
        // 只置标志 + 记日志，不 crash —— 误报会让输入法直接不可用，代价远大于收益。
        runCatching {
            val r = AppSignatureVerifier.verify(this)
            when {
                r.skipped -> Timber.d("AppSignatureVerifier: ${r.detail}")
                r.mismatch -> {
                    Timber.e(
                        "AppSignatureVerifier: ${r.detail}；" +
                            "current=${r.current.take(24)}… expected=${r.expected.take(24)}…"
                    )
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit(commit = true) {
                        putBoolean(AppSignatureVerifier.PREF_SIGNATURE_MISMATCH, true)
                    }
                }
                else -> Timber.d("AppSignatureVerifier: ${r.detail}")
            }
        }.onFailure { Timber.w(it, "AppSignatureVerifier failed") }

        AppPrefs.init(sharedPrefs)
        // record last pid for crash logs
        AppPrefs.getInstance().internal.pid.apply {
            val currentPid = Process.myPid()
            lastPid = getValue()
            Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            setValue(currentPid)
            // MBM v1.0.26（P1-4）进程退出归因：上一次进程是怎么死的。
            // Java 崩溃走 onCreate 里的 UncaughtExceptionHandler；原生崩溃（librime/libime
            // 段错误）、系统 LMK、ANR 后被杀、用户强杀全都不走那条路，
            // 只能靠 ActivityManager 的历史退出记录回捞。
            // 复用既有 internal.pid（上一次进程的 pid），不新增偏好项。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val prev = lastPid
                if (prev != null && prev > 0 && prev != currentPid) {
                    runCatching {
                        if (ProcessExitAttributor.appendToFile(ctx, prev)) {
                            Timber.i(
                                "ProcessExitAttributor: 上次进程(pid=$prev)退出原因已记录，" +
                                    "见 files/crash_attribution.log"
                            )
                        }
                    }.onFailure { Timber.w(it, "ProcessExitAttributor failed") }
                }
            }
        }
        ClipboardManager.init(ctx)
        ThemeManager.init(resources.configuration)
        Locales.onLocaleChange(resources.configuration)
        registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDirectBootMode) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ContextCompat.registerReceiver(
            this,
            restartFcitxInstanceReceiver,
            IntentFilter(ACTION_RESTART_FCITX_INSTANCE),
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            mbmDebugReceiver,
            IntentFilter(ACTION_MBM_DEBUG),
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.onSystemPlatteChange(newConfig)
        Locales.onLocaleChange(newConfig)
    }

    companion object {
        private var lastPid: Int? = null
        private var instance: FcitxApplication? = null
        fun getInstance() =
            instance ?: throw IllegalStateException("FcitxApplication has not been created!")

        fun getLastPid() = lastPid
        private const val MAX_STACKTRACE_SIZE = 128000

        const val ACTION_RESTART_FCITX_INSTANCE =
            "${BuildConfig.APPLICATION_ID}.action.RESTART_FCITX_INSTANCE"

        /** 统一 adb 调试广播 action（见 utils/MbmDebug.kt）。 */
        const val ACTION_MBM_DEBUG = MbmDebug.ACTION_MBM_DEBUG

        /**
         * This permission is requested by com.android.shell, makes it possible to restart
         * fcitx instance from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a org.fcitx.fcitx5.android.action.RESTART_FCITX_INSTANCE
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}