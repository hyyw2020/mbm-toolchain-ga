/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 应用名即时候选（MBM v0.0.05）。
 *
 * 痛点（用户录屏+截图反馈）：在华为「搜索应用」界面输入应用名首字母缩写
 * （如「全民枪战」→ qmqz），拼音候选只有词库里的「全面其中 / 前面其中」等
 * 无关词——应用名既不在 libime 通用词库、用户也从未选过它（冷启动死锁：
 * 候选里没有 → 永远选不到 → 永远学不到）。用户要求「刚打到就能立即输出」。
 *
 * 方案：把本机已安装（有启动器图标）应用名按拼音键写入 pinyin 自定义词组
 * （native 引擎一等公民，输入流同步出候选、零延迟）：
 *  - key = 应用名首字母缩写（qmqz）/ 全拼（quanminqiangzhan）/ 纯英文原名（saltplayer）
 *  - value = 完整应用名（「全民枪战2」，上屏即可被搜索界面命中）
 *  - order 按输入场景动态切换（见 [onSceneChanged]）：
 *      应用搜索场景（hint「搜索应用」/ 桌面类包名）→ order=1 置顶，第一候选即应用名；
 *      普通场景 → order=8 靠后不打扰常用词，用户选过一次后可在设置里手动置顶。
 *
 * 写入条目全部登记在 SharedPreferences 的 managed 集合中（key=value 为管理键），
 * 重写时先剔除旧条目再追加新条目，与用户自造词组互不干扰；管理界面过滤显示。
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.core.reloadPinyinCustomPhrase
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.utils.PinyinUtils
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

object AppPhraseImporter {

    private const val PREFS = "mbm_app_phrase"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_IMPORTED = "imported"
    private const val KEY_SCENE = "scene"
    private const val KEY_MANAGED = "managed_keys"

    /** 普通输入场景：应用候选排第 8 位左右（第一页内，不挤占常用词） */
    private const val ORDER_NORMAL = 8

    /** 应用搜索场景：order=1 置顶，第一候选即应用名 */
    private const val ORDER_APP_SEARCH = 1

    const val SCENE_NORMAL = 0
    const val SCENE_APP_SEARCH = 1

    /**
     * 桌面 / 应用抽屉类包名（华为「搜索应用」等全局搜索的宿主）。
     * 仅作辅助判据——hint 文本「搜索应用」才是主判据（见 [isAppSearchScene]）。
     */
    private val APP_SEARCH_PACKAGES = setOf(
        "com.huawei.android.launcher",
        "com.miui.home",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.bbk.launcher2",
        "com.oppo.launcher",
        "com.android.quicksearchbox"
    )

    /** rebuild 互斥：ensureImported（onCreate）与 onSceneChanged（onStartInput）可能并发，
     *  不加锁会 load→save 竞态丢条目（上层窗口极小但真实存在） */
    private val rebuildMutex = Mutex()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 管理键：order 会随场景变化，因此不能用 serialize()（含 order），只用 key=value */
    fun managedKeyOf(p: PinyinCustomPhrase): String = "${p.key}=${p.value}"

    fun isManaged(ctx: Context, p: PinyinCustomPhrase): Boolean =
        prefs(ctx).getStringSet(KEY_MANAGED, emptySet())?.contains(managedKeyOf(p)) == true

    /**
     * 确实是「应用名条目」的判定（在 [isManaged] 之上再加归属判定 [isOwnEntry]）。
     * v1.0.41：Recall 捕获的新词常与应用名同形（key=value 完全一致），仅用 [isManaged]
     * 会把这些 Recall 词条一并过滤掉（管理界面里看不见、用户保存时还会被整批丢掉）。
     */
    fun isManagedAppEntry(ctx: Context, p: PinyinCustomPhrase): Boolean =
        isManaged(ctx, p) && isOwnEntry(p)

    /**
     * 当前 EditorInfo 是否「应用搜索」场景：
     * 主判据 hint 文本含「搜索」且含「应用/软件」；辅判据宿主是桌面类包名。
     */
    fun isAppSearchScene(packageName: String?, hintText: CharSequence?): Boolean {
        val hint = hintText?.toString().orEmpty()
        if (hint.contains("搜索") && (hint.contains("应用") || hint.contains("软件"))) return true
        return packageName in APP_SEARCH_PACKAGES
    }

    // ---------- 条目生成 ----------

    /**
     * 应用名 → 拼音键集合。
     * - 首字母缩写：「全民枪战2」→ qmqz / qmqz2
     * - 全拼：「全民枪战2」→ quanminqiangzhan / quanminqiangzhan2
     * - 纯英文/数字名：「Salt Player」→ saltplayer / sp
     */
    fun keysForName(name: String): Set<String> {
        val keys = mutableSetOf<String>()
        val ini = PinyinUtils.initials(name)
        val full = PinyinUtils.fullPinyin(name)
        // 名字中的非汉字片段（英文词、数字），按原顺序拼接为后缀/前缀
        val extras = Regex("[A-Za-z0-9]+").findAll(name)
            .map { it.value.lowercase() }
            .toList()
        ini?.let {
            keys += it
            if (extras.isNotEmpty()) keys += it + extras.joinToString("")
        }
        full?.let {
            keys += it
            if (extras.isNotEmpty()) keys += it + extras.joinToString("")
        }
        // 数字前缀名（4399游戏盒 / 12306）：用户习惯「数字+首字母」（4399yxh）
        val digitPrefix = name.takeWhile { it.isDigit() }
        if (digitPrefix.isNotEmpty()) {
            ini?.let { keys += digitPrefix + it }
            full?.let { keys += digitPrefix + it }
        }
        // 纯非汉字名（ICU 转换可能失败/失真）：直接用原名小写去空格 + 首字母
        val ascii = name.lowercase().filter { it.isLetterOrDigit() }
        if (ascii.isNotEmpty()) {
            keys += ascii
            val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            if (words.size > 1) keys += words.map { it.first().lowercaseChar() }.joinToString("")
        }
        return keys.filter { it.length in 1..32 }.toSet()
    }

    /**
     * 枚举本机有启动器图标的应用（label 去重、按名排序保证输出稳定）。
     *
     * v0.0.06 修复「qmqz 完全不出全民枪战」：v0.0.05 只用 queryIntentActivities 一条路，
     * 华为等 ROM 上包可见性过滤会让它返回**空列表**，而当时 rebuild 不管成败都把
     * KEY_IMPORTED 置 true —— 首次空导入即永久锁死，用户再也等不到应用名候选。
     * 现在：① 主策略失败/为空时回退 getInstalledApplications + getLaunchIntentForPackage；
     *      ② 清单补 QUERY_ALL_PACKAGES 兜底包可见性；③ 空结果不再写 IMPORTED（见 [rebuildLocked]）。
     */
    fun queryLauncherApps(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val primary = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.loadLabel(pm).toString().takeIf { l -> l.isNotBlank() } }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) {
            Timber.d("MBM: queryLauncherApps primary=${primary.size}")
            return primary
        }
        // 回退：枚举已安装应用，用「能否取到启动 Intent」判定有启动器图标
        val fallback = runCatching {
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .mapNotNull { pm.getApplicationLabel(it).toString().takeIf { l -> l.isNotBlank() } }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
        Timber.w("MBM: queryLauncherApps primary empty, fallback=${fallback.size}")
        return fallback
    }

    private fun generateEntries(ctx: Context, order: Int): List<PinyinCustomPhrase> {
        if (!isEnabled(ctx)) return emptyList()
        return queryLauncherApps(ctx).flatMap { name ->
            keysForName(name).map { key -> PinyinCustomPhrase(key, order, name) }
        }
    }

    /**
     * 本模块写过的条目判定（order ∈ {ORDER_APP_SEARCH=1, ORDER_NORMAL=8}）。
     *
     * 关键背景（v1.0.41 修复）：应用名与 Recall 屏幕记忆捕获的新词经常是同一个 value
     * （「芝士录屏」既是已安装应用名，也会被屏幕记忆捕获），两者 key=value 完全同形。
     * 旧实现按 key=value 无差别剔除，于是每次「输入框场景切换」（桌面搜索 ↔ 普通）
     * 触发的场景重建都会把 Recall 刚写入的热词条一并删掉，只剩 order=8 的应用名条目；
     * native 侧 order=8 → 第 8 候选位，超出键盘可见候选条（4~5 个），
     * 表现为「桌面搜索框能出、其它输入框出不来」的即时性失效。
     *
     * 按 order 归属剔除后两者可以共存：同一 key 的多条目由 native 取 order 最小者，
     * 应用搜索场景 order=1 置顶，普通场景 Recall 的 order=2 靠前 —— 全场景即时可见。
     */
    private fun isOwnEntry(p: PinyinCustomPhrase): Boolean =
        p.order == ORDER_APP_SEARCH || p.order == ORDER_NORMAL

    // ---------- 导入 / 重写 ----------

    /**
     * 全量重写：剔除旧 managed 条目 → 按当前 scene 的 order 生成新条目 → 合并落盘。
     * 调用方负责在 fcitx ready 后调 [reload]。
     */
    private suspend fun rebuild(ctx: Context, scene: Int) = withContext(Dispatchers.IO) {
        rebuildMutex.withLock {
            // 与 Recall 写词共享同一把文件锁，避免读-改-写交叉丢条目（v1.0.41）
            CustomPhraseManager.withWriteLock { rebuildLocked(ctx, scene) }
        }
    }

    private fun rebuildLocked(ctx: Context, scene: Int) {
        val order = if (scene == SCENE_APP_SEARCH) ORDER_APP_SEARCH else ORDER_NORMAL
        val fresh = generateEntries(ctx, order)
        val managedKeys = fresh.map { managedKeyOf(it) }.toSet()
        // MBM v1.0.31：缓存版 load/save（词库无上限 + 大词库免重复全量 JNI load）
        val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull() ?: emptyArray()
        val oldManaged = prefs(ctx).getStringSet(KEY_MANAGED, emptySet()) ?: emptySet()
        // 只剔除本模块自己写过的条目（order ∈ {1, 8}），见 [isOwnEntry] 注释。
        val kept = existing.filterNot { isOwnEntry(it) && oldManaged.contains(managedKeyOf(it)) }
        val merged = kept + fresh
        runCatching { CustomPhraseManager.saveCached(merged.toTypedArray()) }
            .onFailure { Timber.w(it, "MBM: save app phrase entries failed") }
        prefs(ctx).edit()
            .putStringSet(KEY_MANAGED, managedKeys)
            // 关键：应用列表为空说明这次枚举没成功（ROM 包可见性限制 / 时机过早），
            // 不能标记「已导入」，否则永远不再重试（v0.0.05 的锁死 bug）。
            .putBoolean(KEY_IMPORTED, fresh.isNotEmpty())
            .putInt(KEY_SCENE, scene)
            .apply()
        Timber.d("MBM: app phrase rebuilt scene=$scene entries=${fresh.size} total=${merged.size} imported=${fresh.isNotEmpty()}")
        if (fresh.isEmpty()) Timber.w("MBM: app list empty -> not marked imported, will retry next launch")
    }

    /** 首次导入（IME 启动时）；已导入过则不做任何事 */
    suspend fun ensureImported(ctx: Context, fcitx: FcitxConnection) {
        if (prefs(ctx).getBoolean(KEY_IMPORTED, false)) return
        rebuild(ctx, prefs(ctx).getInt(KEY_SCENE, SCENE_NORMAL))
        fcitx.runOnReady { reloadPinyinCustomPhrase() }
    }

    /** 输入场景变化（onStartInput）：仅 scene 真正变化时才重写+reload */
    suspend fun onSceneChanged(ctx: Context, fcitx: FcitxConnection, scene: Int) {
        if (prefs(ctx).getInt(KEY_SCENE, SCENE_NORMAL) == scene) return
        // 未导入过（与 onCreate 的 ensureImported 竞态）：同样按当前 scene 导入，
        // 否则「搜索应用」场景首次进入不会置顶（rebuild 幂等，重复执行无害）
        rebuild(ctx, scene)
        fcitx.runOnReady { reloadPinyinCustomPhrase() }
    }

    /** 手动刷新（设置按钮 / 应用装卸广播） */
    suspend fun refresh(ctx: Context, fcitx: FcitxConnection) {
        rebuild(ctx, prefs(ctx).getInt(KEY_SCENE, SCENE_NORMAL))
        fcitx.runOnReady { reloadPinyinCustomPhrase() }
    }

    /** 自检信息：已导入的应用数与生成的候选条目数（设置页展示，便于用户/排障确认是否真的导入成功） */
    fun stats(ctx: Context): Pair<Int, Int> {
        val names = queryLauncherApps(ctx)
        val entries = if (isEnabled(ctx)) names.sumOf { keysForName(it).size } else 0
        return names.size to entries
    }

    /** 上次导入是否真的拿到了应用列表（false = 尚未成功导入，会持续重试） */
    fun isImported(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_IMPORTED, false)

    /** 保存用户词组时合并当前应用条目（保证用户保存不会冲掉 managed 条目） */
    fun freshEntries(ctx: Context): List<PinyinCustomPhrase> =
        generateEntries(
            ctx,
            if (prefs(ctx).getInt(KEY_SCENE, SCENE_NORMAL) == SCENE_APP_SEARCH)
                ORDER_APP_SEARCH else ORDER_NORMAL
        )

    // ---------- 应用装卸监听 ----------

    /**
     * 监听应用装卸：安装/卸载/更新后延迟重枚举。
     * 注意 API 30+ 枚举应用需清单 <queries> 声明 MAIN/LAUNCHER intent（已声明）。
     */
    fun packageChangeReceiver(onChanged: () -> Unit): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REMOVED,
                    Intent.ACTION_PACKAGE_REPLACED -> onChanged()
                }
            }
        }
    }

    fun registerPackageReceiver(ctx: Context, onChanged: () -> Unit): BroadcastReceiver {
        val receiver = packageChangeReceiver(onChanged)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ctx.registerReceiver(receiver, filter)
        return receiver
    }
}
