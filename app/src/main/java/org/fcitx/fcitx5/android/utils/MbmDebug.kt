/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MbmDebug —— 统一 adb 调试枢纽（MBM v1.0.36 起）。
 *
 * 设计目标：
 *  1. 输入法所有可点击功能都支持 adb 自动测试：每个可点击控件用 [markFeature]
 *     打 `content-desc="mbm:feature:<id>"`，adb 用 uiautomator dump 解析该标识拿
 *     到屏幕坐标后 `input tap` 真实点击；控件在真实点击路径上调用 [logFeature]
 *     打出统一日志 `MBM-DBG: feature=<id>`，测试脚本 grep 断言即知功能是否被触发。
 *  2. 唯一外部 adb 入口：`ACTION_MBM_DEBUG` 广播（FcitxApplication 动态注册）。
 *     adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd <...>
 *     支持 cmd = log_set / log_dump / state / ime_dump / func_list / click。
 *  3. 日志/调试 UI 入口唯一（开发者页内实时日志 + 本页 adb 说明），不重复铺。
 *
 * 完整自动测试见仓库 `scripts/mbm_adb_test.sh`：dump -> 解析 mbm:feature 坐标 ->
 * 逐个 input tap -> logcat 断言 feature=<id>，遍历所有按键与功能。
 */
package org.fcitx.fcitx5.android.utils

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object MbmDebug {

    const val TAG = "MBM-DBG"

    /** 唯一外部 adb 广播 action（与 FcitxApplication.ACTION_MBM_DEBUG 一致）。 */
    const val ACTION_MBM_DEBUG = "org.fcitx.fcitx5.android.action.MBM_DEBUG"

    /** 控件可测试标识前缀：`content-desc="mbm:feature:<id>"`。 */
    const val FEATURE_PREFIX = "mbm:feature:"

    /**
     * 功能标记的 View tag key（应用私有区间 0x01000000–0x7FFFFFFF，0x7F0B0001）。
     * contentDescription 会被业务逻辑覆写（IdleUi.updateMenuButtonContentDescription /
     * setHideKeyboardIsVoiceInput 曾把 bar.menu/bar.hide 标记擦成人类文案），
     * 故 [collectFeatureViews] 以 tag 为主、contentDescription 兜底。
     */
    const val FEATURE_TAG_KEY = 0x7F0B0001

    /** 最近 feature 日志环形缓冲（自动测试断言用，跨窗口不清空由调用方管理）。 */
    private val featureLog = ArrayDeque<String>()

    @Synchronized
    fun clearFeatureLog() = featureLog.clear()

    @Synchronized
    fun hasRecentFeature(id: String): Boolean = id in featureLog

    /** 可点击功能注册表：id -> 描述（供 `func_list` 列出，adb 可 grep）。 */
    val features: Map<String, String> = linkedMapOf(
        // 工具栏 / 候选条
        "bar.menu" to "工具栏菜单（展开/收起/数字行）",
        "bar.hide" to "隐藏键盘/切语音",
        "bar.undo" to "撤销 Ctrl+Z",
        "bar.redo" to "重做 Ctrl+Shift+Z",
        "bar.cursor" to "光标移动（打开编辑窗）",
        "bar.clipboard" to "打开剪贴板候选",
        "bar.more" to "更多（状态区）",
        "bar.handwriting" to "打开手写",
        "bar.candidate_expand" to "候选展开/收起",
        // 键盘按键
        "key.caps" to "大小写切换",
        "key.layout" to "布局切换",
        "key.backspace" to "退格",
        "key.quickphrase" to "快捷引语/Unicode",
        "key.comma" to "逗号（弹菜单 Emoji/引语/Unicode）",
        "key.lang" to "语言/输入法切换",
        "key.space" to "空格（长按枚举/切换）",
        "key.longpress" to "按键长按（空格枚举等）",
        "key.return" to "回车（弹菜单 Emoji）",
        "key.number" to "数字行数字键",
        "key.sym" to "符号切换",
        // 候选
        "candidate.select" to "候选词上屏",
        "candidate.longpress" to "候选长按动作菜单",
        "candidate.prev" to "候选上一页",
        "candidate.next" to "候选下一页",
        "candidate.tab" to "候选 Tab 动作",
        // 剪贴板
        "clipboard.paste" to "剪贴板条目粘贴（屏幕记忆词库上屏）",
        "clipboard.swipe_delete" to "剪贴板条目滑动删除",
        "clipboard.enable" to "启用剪贴板监听",
        "clipboard.clear" to "清空剪贴板",
        "clipboard.import" to "导入剪贴板",
        "clipboard.longpress" to "剪贴板条目长按菜单",
        // 手写
        "handwriting.recognize" to "手写抬笔识别",
        "handwriting.backspace" to "手写退格",
        "handwriting.punct" to "手写标点上屏",
        "handwriting.clear" to "手写清除",
        "handwriting.space" to "手写空格",
        "handwriting.enter" to "手写换行",
        "handwriting.symbol" to "手写符号面板",
        "handwriting.candidate" to "手写候选上屏",
        // 文本编辑
        "editing.move" to "光标移动键",
        "editing.select" to "选择/全选",
        "editing.cut" to "剪切",
        "editing.copy" to "复制",
        "editing.paste" to "粘贴",
        "editing.backspace" to "编辑退格",
        "editing.clipboard" to "编辑页开剪贴板",
        // 表情/符号/颜文字
        "picker.item" to "表情/符号/颜文字项上屏",
        "picker.longpress" to "表情项长按弹键盘",
        "picker.tab" to "表情分类 Tab",
        // 状态区
        "status.action" to "状态区动作项",
        "status.settings" to "状态区设置",
        "status.editorinfo" to "状态区编辑器信息",
        "status.selftest" to "状态区：自动测试（内置全功能自测）",
        // 输入法
        "ime.switch" to "输入法切换",
        "ime.picker" to "输入法选择器",
        // 设置页主干
        "smartdict.action" to "离线智能词库操作",
        "ruleimport.action" to "规则与词库导入",
        "pinyin.phrase" to "用户词库（自定义词组/智能候选/已装词库）",
        "password.bridge" to "密码桥/填充",
        "handwriting.settings" to "手写设置页",
        // daemon 行为层（v3.3：不经 UI，直接经 fcitx daemon 程序化断言，覆盖 fcitx 全局配置全量表）
        "daemon.ime.current" to "fcitx 当前输入法查询",
        "daemon.ime.enabled" to "fcitx 已启用输入法列表",
        "daemon.ime.available" to "fcitx 可用输入法列表",
        "daemon.ime.enumerate" to "fcitx 输入法枚举（前后往返还原）",
        "daemon.hotkey.toggle" to "热点键切换输入法(TriggerKeys)",
        "daemon.hotkey.activate" to "热点键激活输入法(ActivateKeys)",
        "daemon.hotkey.deactivate" to "热点键停用输入法(DeactivateKeys)",
        "daemon.hotkey.alt_toggle" to "临时切换输入法(AltTriggerKeys)",
        "daemon.hotkey.enumerate_fwd" to "向前枚举输入法(EnumerateForwardKeys)",
        "daemon.hotkey.enumerate_bwd" to "向后枚举输入法(EnumerateBackwardKeys)",
        "daemon.hotkey.enumerate_trigger" to "修饰键持按枚举(EnumerateWithTriggerKeys)",
        "daemon.hotkey.consume_redundant" to "消费冗余激活键(ConsumeRedundantActivateKeys)",
        "daemon.hotkey.skip_first" to "枚举跳过首个输入法(EnumerateSkipFirst)",
        "daemon.hotkey.enumerate_group" to "分组枚举输入法(EnumerateGroup*Keys)",
        "daemon.hotkey.paging" to "回退翻页键(PrevPage/NextPage)",
        "daemon.hotkey.candidate_nav" to "回退候选导航键(Prev/NextCandidate)",
        "daemon.hotkey.toggle_preedit" to "切换内嵌预编辑(ImeKey/TogglePreedit)",
        "daemon.hotkey.modifier_timeout" to "修饰键快捷键时限(ModifierOnlyKeyTimeout)",
        "daemon.behavior.active_default" to "默认激活输入法(ActiveByDefault)",
        "daemon.behavior.reset_focus" to "焦点进入重置状态(resetStateWhenFocusIn)",
        "daemon.behavior.share_state" to "共享输入状态(ShareInputState)",
        "daemon.behavior.show_preedit" to "程序内显示预编辑(PreeditEnabledByDefault)",
        "daemon.behavior.ime_info" to "切换输入法显示信息(ShowInputMethodInformation)",
        "daemon.behavior.compact_info" to "紧凑输入法信息(CompactInputMethodInformation)",
        "daemon.behavior.page_size" to "默认候选每页数(DefaultPageSize)",
        "daemon.behavior.password" to "密码字段输入法(AllowInputMethodForPassword)",
        "daemon.behavior.password_preedit" to "密码字段显示预编辑(ShowPreeditForPassword)",
        "daemon.behavior.autosave" to "自动保存间隔(AutoSavePeriod)",
    )

    /** 与输入法服务桥接：状态/日志/自测命令由 FcitxInputMethodService 实现。 */
    @Volatile
    var bridge: Bridge? = null

    interface Bridge {
        /** 输入法整体状态（当前窗口/包名/输入类型/候选/剪贴板/词库/手写/fcitx pid）。 */
        fun stateDump(): String
        /** 编辑器信息 + 候选详情。 */
        fun imeDump(): String
        /** 开/关详细日志（等价开发者页开关，但不弹 UI）。 */
        fun applyVerbose(verbose: Boolean)
        /** 导出当前 logcat 到 filesDir/mbm_logcat.txt，返回路径（供 adb pull）。 */
        fun dumpLogcat(): String
        /**
         * 运行一轮全功能自动测试（要求键盘窗口已打开；视图操作在主线程，导出走后台）。
         * 完成后 onDone 收到报告（主线程回调）；报告自动导出 Download 文件夹 + 剪贴板。
         */
        fun runSelfTest(onDone: (SelfTestReport) -> Unit)
    }

    /**
     * 统一可点击功能日志。所有可点击功能在真实点击路径上调用本方法，
     * 打出 `MBM-DBG: feature=<id>`，adb logcat 即可观测/断言每个功能是否被点击。
     * 同时记入 [featureLog] 环形缓冲，供 UI 内自动测试断言。
     */
    fun logFeature(id: String, extra: String = "") {
        Timber.tag(TAG).w("feature=$id${if (extra.isNotEmpty()) " $extra" else ""}")
        @Synchronized
        fun record() {
            featureLog.addLast(id)
            while (featureLog.size > 256) featureLog.removeFirst()
        }
        record()
    }

    /**
     * 给可点击控件打 adb/UI 自动测试标识：`content-desc="mbm:feature:<id>"` + View tag（双轨）。
     * tag（[FEATURE_TAG_KEY]）是主通道——业务逻辑可能覆写 contentDescription；
     * content-desc 供 `uiautomator dump` 解析拿控件屏幕坐标再 `input tap`。
     * 输入法内置自动测试则直接递归收集该标识并 `performClick`。
     */
    fun markFeature(view: View, id: String) {
        val marker = "$FEATURE_PREFIX$id"
        view.contentDescription = marker
        view.setTag(FEATURE_TAG_KEY, marker)
    }

    /**
     * 自动测试结果条目（v3：仅 PASS/FAIL 二元判定，无 SKIP）。
     *
     * 每个注册表功能 id 必产出恰好一条 [FeatureResult]：
     *  - [State.PASS]：真实点击/手势触发并在日志中观测到 `feature=<id>`。
     *  - [State.FAIL]：未触发 / 未执行 / 超出窗口自测范围。FAIL 必须给出
     *    具体原因（[cause]）与源码级定位（[source]，`文件:符号` 形式），
     *    不允许出现无解释的失败。
     */
    data class FeatureResult(
        val id: String,
        val state: State,
        val phase: String = "",
        val cause: String = "",
        val source: String = "",
    ) {
        enum class State { PASS, FAIL }

        companion object {
            @JvmStatic
            fun pass(id: String, phase: String = "", cause: String = "") =
                FeatureResult(id, State.PASS, phase, cause)

            @JvmStatic
            fun fail(id: String, phase: String, cause: String, source: String = "") =
                FeatureResult(id, State.FAIL, phase, cause, source)
        }

        /** 报告渲染用的一行说明（FAIL 自动附源码定位）。 */
        val detail: String
            get() = if (state == State.PASS) cause.trim()
            else (cause + (if (source.isNotEmpty()) "（源码:$source）" else "")).trim()
    }

    /**
     * 自动测试报告（v3）：PASS/FAIL 二元判定 + 源码级失败原因 + 专业排版。
     * [durationMs] 由引擎在结束时传入；设备信息在 [toText] 内读取。
     */
    data class SelfTestReport(
        val results: List<FeatureResult> = emptyList(),
        val diagnostics: List<String> = emptyList(),
        val durationMs: Long = 0L,
    ) {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        val passCount = results.count { it.state == FeatureResult.State.PASS }
        val failCount = results.count { it.state == FeatureResult.State.FAIL }

        val passRate: String
            get() = if (results.isEmpty()) "0.0"
            else String.format(java.util.Locale.US, "%.1f", passCount * 100.0 / results.size)

        val summary: String
            get() = "PASS=$passCount FAIL=$failCount TOTAL=${results.size}"

        private fun deviceLine(): String = runCatching {
            "android.os.Build.MODEL=${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}"
        }.getOrDefault("unknown")

        /** 专业排版：头部元信息 → 汇总 → 失败项（源码级）→ 成功项 → 诊断。 */
        fun toText(): String {
            val bar = "=".repeat(68)
            val sep = "-".repeat(68)
            val fails = results.filter { it.state == FeatureResult.State.FAIL }
            val passes = results.filter { it.state == FeatureResult.State.PASS }
            return buildString {
                appendLine(bar)
                appendLine(" MBM 全功能自动测试报告")
                appendLine(bar)
                appendLine(" 版本      : MBM v1.0.36 (org.fcitx.fcitx5.android)")
                appendLine(" 生成时间  : $time")
                appendLine(" 耗时      : ${durationMs} ms")
                appendLine(" 设备      : ${deviceLine()}")
                appendLine(" 判定规则  : 仅 PASS/FAIL 二元；FAIL 均附具体原因与源码定位")
                appendLine(bar)
                appendLine(" 汇总  ${summary}  通过率=${passRate}%")
                appendLine()
                appendLine(" [失败项 FAIL]（${fails.size}）")
                appendLine(sep)
                if (fails.isEmpty()) {
                    appendLine("  （无）")
                } else {
                    fails.forEachIndexed { i, r ->
                        appendLine(" ${i + 1}. ${r.id}  @ ${r.phase}")
                        appendLine("    原因 : ${r.cause}")
                        if (r.source.isNotEmpty()) appendLine("    源码 : ${r.source}")
                    }
                }
                appendLine()
                appendLine(" [成功项 PASS]（${passes.size}）")
                appendLine(sep)
                if (passes.isEmpty()) {
                    appendLine("  （无）")
                } else {
                    passes.forEachIndexed { i, r ->
                        val c = if (r.cause.isNotEmpty()) "  — ${r.cause}" else ""
                        appendLine(" ${i + 1}. ${r.id}  @ ${r.phase}${c}")
                    }
                }
                if (diagnostics.isNotEmpty()) {
                    appendLine()
                    appendLine(" [诊断信息]")
                    appendLine(sep)
                    diagnostics.forEach { appendLine(" $it") }
                }
                appendLine()
                appendLine(bar)
            }
        }

        fun save(context: Context) {
            runCatching {
                java.io.File(context.filesDir, "mbm_selftest.txt").writeText(toText())
            }.onFailure { Timber.tag(TAG).w(it, "selftest 报告保存失败") }
        }
    }

    /** 最近一次自测报告的导出位置（Download 文件夹路径优先），UI Toast 用。 */
    @Volatile
    var lastExportPath: String? = null

    /**
     * 自测报告导出（v2）：filesDir + 公共 Download 文件夹（MediaStore，免权限）+ 剪贴板全文。
     * 涉及文件 IO 与 MediaStore binder IPC，**必须后台线程调用**（主线程铁律）。
     */
    suspend fun exportSelfTestReport(context: Context, report: SelfTestReport) =
        withContext(Dispatchers.IO) {
            val text = report.toText()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val fileName = "MBM_自动测试_$ts.txt"
            var localPath: String? = null
            runCatching {
                val f = java.io.File(context.filesDir, "mbm_selftest.txt")
                f.writeText(text)
                localPath = f.absolutePath
            }.onFailure { Timber.tag(TAG).w(it, "selftest 报告 filesDir 保存失败") }

            var downloadPath: String? = null
            var clipboardOk = false
            runCatching {
                // minSdk=29：MediaStore.Downloads 恒可用，写公共 Download 无需存储权限
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("MediaStore insert 返回 null")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法打开输出流")
                downloadPath = "Download/$fileName"
            }.onFailure { Timber.tag(TAG).w(it, "selftest 报告 Download 导出失败") }

            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("MBM 自动测试报告", text))
                clipboardOk = true
            }.onFailure { Timber.tag(TAG).w(it, "selftest 报告剪贴板复制失败") }

            lastExportPath = downloadPath ?: localPath
            Timber.tag(TAG).w(
                "selftest => ${report.summary} 导出=${lastExportPath ?: "全部失败"} " +
                    "filesDir=${localPath != null} 剪贴板=$clipboardOk"
            )
        }

    /** 自动测试结束：后台导出 + logcat 摘要（adb 无线调试下可 grep selftest）。 */
    suspend fun finishSelfTest(context: Context, report: SelfTestReport) {
        exportSelfTestReport(context, report)
    }

    /** 完整一套 adb 测试指令（开发者页展示/复制用）。 */
    fun adbCommands(): String = """
        # ===== MBM 输入法全功能自动测试 =====
        # 方式一（推荐）：输入法内置自动测试——状态区「自动测试」按钮点一下，
        # 输入法自己遍历所有窗口/按钮，真实点击并断言。
        # 报告自动导出三处：① 公共 Download 文件夹 MBM_自动测试_*.txt（无需任何权限）
        #                ② 剪贴板（可直接粘贴发给开发者）
        #                ③ files/mbm_selftest.txt（adb run-as 可取）
        # 也可以用广播远程触发（无线调试下）：
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd self_test
        adb logcat -d -s MBM-DBG | grep selftest
        adb shell run-as org.fcitx.fcitx5.android cat files/mbm_selftest.txt

        # 方式二：adb 外部驱动脚本（遍历所有可点击功能，逐一真实点击并断言日志）。
        # 前置：adb 已连接、小企鹅输入法为当前输入法、设备上有聚焦的输入框。
        # （脚本在仓库 scripts/mbm_adb_test.sh）
        adb push scripts/mbm_adb_test.sh /sdcard/
        adb shell sh /sdcard/mbm_adb_test.sh
        # 或本机跑（adb 已连接）： bash scripts/mbm_adb_test.sh

        # --- 手动：dump 当前输入法窗口层级，找 mbm:feature 节点坐标 ---
        adb shell uiautomator dump /sdcard/mbm_ui.xml
        adb pull /sdcard/mbm_ui.xml /tmp/mbm_ui.xml

        # --- 手动：点某个功能，断言是否打出 feature=<id> ---
        adb logcat -c
        adb shell input tap <x> <y>
        adb logcat -d -s MBM-DBG

        # --- 详细日志开/关（等价开发者页开关，不弹 UI）---
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd log_set --ez verbose true
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd log_set --ez verbose false

        # --- 输入法状态 / 编辑器信息 dump ---
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd state
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd ime_dump

        # --- 导出 logcat 到 filesDir/mbm_logcat.txt（run-as 可取）---
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd log_dump
        adb shell run-as org.fcitx.fcitx5.android ls -l files/mbm_logcat.txt

        # --- 列出全部可点击功能 id（点击时打 MBM-DBG: feature=<id>）---
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd func_list

        # --- 观测所有点击日志 ---
        adb logcat -v threadtime -T 1 -s MBM-DBG

        # --- 重启 fcitx 实例（既有独立入口）---
        adb shell am broadcast -a org.fcitx.fcitx5.android.action.RESTART_FCITX_INSTANCE
    """.trimIndent()

    /** 处理 MBM_DEBUG 广播。返回结果摘要（由 receiver 记入日志）。 */
    fun dispatch(context: Context, intent: Intent): String {
        val cmd = intent.getStringExtra("cmd") ?: "state"
        val result = try {
            when (cmd) {
                "log_set" -> {
                    val v = intent.getBooleanExtra("verbose", true)
                    bridge?.applyVerbose(v)
                    "log_set verbose=$v 已应用"
                }
                "log_dump" -> {
                    val path = bridge?.dumpLogcat() ?: "无桥接（输入法未运行）"
                    "log_dump => $path"
                }
                "state" -> {
                    val s = bridge?.stateDump() ?: "无桥接（输入法未运行）"
                    "state => $s"
                }
                "ime_dump" -> {
                    val s = bridge?.imeDump() ?: "无桥接（输入法未运行）"
                    "ime_dump => $s"
                }
                "func_list" -> "func_list => 共${features.size}个可点击功能：\n" +
                    features.entries.joinToString("\n") { "  ${it.key} = ${it.value}" }
                "click" -> {
                    val id = intent.getStringExtra("feature") ?: return "click 缺 feature 参数"
                    logFeature("adb-click:$id", "由 adb MBM_DEBUG 注入")
                    "click feature=$id 已记录（观测；真实 UI 由用户点击触发）"
                }
                "self_test" -> {
                    val ok = bridge?.runSelfTest { } != null
                    if (ok) "self_test 已触发（键盘窗口需打开；报告自动导出 Download 文件夹 + 剪贴板，" +
                        "logcat grep selftest 或 files/mbm_selftest.txt 亦可见）"
                    else "self_test 失败：输入法未运行（无桥接）"
                }
                else -> "未知 cmd=$cmd；可用：log_set/log_dump/state/ime_dump/func_list/click"
            }
        } catch (e: Exception) {
            "cmd=$cmd 执行异常: ${e.message}"
        }
        Timber.tag(TAG).w("dbg cmd=$cmd => $result")
        return result
    }
}

/**
 * 递归收集功能标记控件，返回 (控件, id)。标记 = View tag（[MbmDebug.FEATURE_TAG_KEY]）
 * 优先、content-desc 兜底（业务逻辑可能覆写 contentDescription）。
 * 输入法内置自动测试用它找出当前窗口内所有可测按钮。
 */
fun View.collectFeatureViews(): List<Pair<View, String>> {
    val out = ArrayList<Pair<View, String>>()
    fun walk(v: View) {
        val d = v.getTag(MbmDebug.FEATURE_TAG_KEY)?.toString() ?: v.contentDescription?.toString()
        if (d?.startsWith(MbmDebug.FEATURE_PREFIX) == true) {
            out.add(v to d.removePrefix(MbmDebug.FEATURE_PREFIX))
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }
    walk(this)
    return out
}
