/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.JavaVersion

object Versions {

    val java = JavaVersion.VERSION_11
    const val compileSdk = 37
    const val minSdk = 29
    const val targetSdk = 37

    const val defaultCMake = "4.4.5"
    const val defaultNDK = "30.0.16248370"
    const val defaultBuildTools = "37.0.0"

    // NOTE: increase this value to bump version code
    // 注意：baseVersionName 会喂给 CMake（project(fcitx5-android VERSION ${VERSION_NAME})），
    // 改动它会触发 4 个 ABI 全量原生重编；在受限构建环境（8GB cgroup / 32 核）下会 OOM。
    // 发新版只改 baseVersionCode（仅影响 Gradle 层 versionCode，不触发 native 重编）。
    // v1.0.19：剪贴板删除/长摁中英切换崩溃同源修复 + 自定义词组 100MB 上限。
    // v1.0.20：OCR det 概率图分辨率截断修复 + warpCrop 原生分辨率双线性 + 样图高精度识别。
    // v1.0.21：修复 OCR 识别(rec)张量行步长误用常量 REC_WIDTH(320)、长行识别乱码的真 bug（见 pitfalls #36）。
    // v1.0.22：整合「离线智能词库」与「管理自定义词组」为单一「用户词库」屏（三分区）；修复智能候选词永远为空的真 bug
    //          （recordUsage 从未接入提交路径，现于 FcitxInputMethodService.commitText 离主线程、隐私门控下调用）。
    // v1.0.23：无上限化（移除记忆召回 LIMIT 300、智能统计不再裁剪 2000、候选词全量展示）+
    //          用户词库新增「优化视觉：同词组只出现一次」开关（数据全保留，仅显示折叠）+
    //          半屏手写风格化（手写板留白圆角抬升 + 右侧竖排 ⌫，。？！ + 底部功能行）。
    // v1.0.24：修复点「用户词库」即崩溃 —— 无上限数据在主线程一次性全量建 View 导致 OOM/ANR
    //          （renderDedup/renderSmart 改协程分帧 chunked(300)+yield，与 RecallActivity 同源策略，
    //          见 pitfalls #40；并防并发重建、防 detach 后 requireContext 崩溃、批量转存抑制逐条刷新）。
    // v1.0.25：修复「输入时点击中文句号(。)等标点崩溃」 —— 同源 Bug：
    //          CommitAction 提交路径的 postFcitxJob{commitAndReset()} 与 commitText 全程无异常处理器，
    //          select(0)/reset() 在候选为空或状态异常时抛错会令整个输入法进程崩溃。
    //          加固：postFcitxJob 挂 imeExceptionHandler；CommitAction 的 commitAndReset 用 runCatching 兜底、
    //          commitText 挂 imeExceptionHandler 且整段包 runCatching（见 ImeSafe.kt 与 pitfalls #41）。
    // v0.0.02：修复 Recall 独立化(P0-2)引入的两个真 bug ——
    //          ① 插件清单服务短名 `.recall.RecallService` 在插件命名空间
    //             (org.fcitx.fcitx5.android.plugin.recall) 下被解析成不存在的类，
    //             无障碍服务永远无法实例化（用户开启后零事件，功能完全失效）；
    //             改为全限定名，RecallActivity 新增服务连接状态行（见 pitfalls #48）。
    //          ② OCR 兜底死代码：captureNodeTree 的 `text != null && text.isBlank()`
    //             与 takeIf{isNotBlank()} 矛盾永假，「启用离线 OCR 兜底」从未触发；
    //             改为区分「隐私丢弃」与「无文字」两种 null（见 pitfalls #49）。
    //          手写面板风格化：手写板占满剩余空间、候选条加首候选拼音+关联词
    //          (HandwritingLexicon，assets 拼音表 3755 字全覆盖 + 关联词)、
    //          右侧快捷键加宽、底部加符号键、空格键智能选首候选。
    // v0.0.03：修复「插件已安装、签名也一致，主输入法仍提示尚未安装屏幕记忆独立应用」——
    //          Android 11（API 30）起跨包查询受包可见性过滤，<queries> 里只声明了两个
    //          intent，而 Recall 插件的 RecallActivity 按设计不暴露任何 intent-filter，
    //          匹配不到 → getPackageInfo 抛 NameNotFoundException（见 pitfalls #51）。
    //          同时把「未安装」与「已安装但拉不起」两类失败分开提示（后者多为签名不一致）。
    // v0.0.04：修 4 个真 bug（见 pitfalls #52-#54）：
    //          ① HandwritingActivity（设置页手写入口）snapshot() 位图副本从不 recycle，
    //             每写一笔泄漏一张全幅位图——v0.0.02 只修了键盘内 HandwritingWindow
    //             调用方，同一函数的两个调用点只修了一个（#52）；
    //          ② RuleImporter 的 Rime zip 解压分支无 Zip Slip 防护，entry.name 可含
    //             "../" 写出目标目录，可覆盖 app 数据库/配置；utils/ZipStream.kt 明明有
    //             防护但两套解压代码没复用，防护不一致（#53）；
    //          ③ ZipStream.extract 前缀比较只 startsWith(canonicalDest) 不带尾分隔符，
    //             destDir=/tmp/x 时 /tmp/xy 兄弟目录被误放行（#53）；
    //          ④ DISPLAY_NAME 是攻击面：installRime/installPinyin 单文件分支直接
    //             File(target, displayName)，可被路径分隔符注入；新增 sanitizeFileName()（#54）。
    // v0.0.05：修「长按候选词后输入法自动收起」（用户录屏取证）：
    //          BaseInputView 候选操作菜单原用系统 PopupMenu——其内部 ListPopupWindow
    //          是抢焦点的独立窗口，华为等 OEM ROM 上 IME 窗口内弹独立窗口会被系统判定
    //          「IME 失焦」强制隐藏输入法，dismiss 后焦点回 Editor 但 IME 不恢复。
    //          改为 isFocusable=false 的 PopupWindow（同 TouchEventReceiverWindow 思路），
    //          不产生新焦点窗口。附带：菜单追加「从自定义词组中删除」便捷入口
    //          （native 只在候选本身是自定义词组时才返回删除动作）（见 pitfalls #55）。
    // v0.0.09：修「点按剪贴板删除键 / 长按中英文切换键后输入法自动收起」——
    //          与 #55 同源（IME 窗口内弹可抢焦点的独立窗口，OEM ROM 判定失焦强制收起），
    //          但漏改了另外三处调用点：剪贴板条目长按菜单、剪贴板清空确认（系统 PopupMenu），
    //          以及输入法选择器（service.showDialog 的 AlertDialog）。修复：
    //          菜单类统一改 ImeMenuPopup（isFocusable=false 的 PopupWindow，含上翻定位），
    //          候选菜单同步重构复用；输入法选择改为面板内窗口 InputMethodPickerWindow
    //          （零新窗口），旧 InputMethodPickerDialog 删除（见 pitfalls #60）。
    // v0.0.10：性能与稳定性轮（用户要求「不能出现卡顿」+ 同源/不同源 bug 排查）——
    //          卡顿：① 手写引擎改进程级单例（NCNN 模型加载一次常驻，不再每次打开
    //          面板重载、关闭时 runBlocking 主线程等待）；② 符号面板网格按分类缓存
    //          （不再每次重建 ~100 个 Button）；③ 输入法选择面板的 IMMS/包管理器
    //          查询挪 IO 线程；④ onStartInput 的应用名场景判断挪 IO 线程。
    //          同源 bug：#55/#60 最后一处漏网——状态区子菜单系统 PopupMenu 与
    //          单输入法时的 AddMoreInputMethodsPrompt AlertDialog 均改 ImeMenuPopup。
    //          不同源 bug：手写识别异常路径泄漏快照位图（#52 同源教训）补 finally 回收。
    // v0.0.11：真 bug 修复 + 识别模型升级（用户要求「修复真 bug 并且更新最新版本的识别模型」）——
    //          真 bug：① HCCRRecognizer 字符表稀疏时 idxToChar[index] 为 null，
    //          下游 candidate.text[0] NPE（换模型必触发，本地字表暂完好故潜伏）；
    //          ② SmartDictManager「读-改-写」无锁：commitText 每次提交都并发
    //          launch(IO) recordUsage，并发互相覆盖丢统计，并发写同文件可产生
    //          损坏 JSON → load 失败返回空表 → save 把用户全部使用统计清空
    //          （不可逆数据丢失）。改 Mutex + 临时文件原子重命名 + 损坏文件备份。
    //          模型：手写模型经 SHA256 逐字节比对确认已是最新（Ismantic/Handwritten
    //          v0.1.1，本地三件套与上游完全一致）；OCR 模型 PP-OCRv4(mobile) →
    //          PP-OCRv6(small)（2026-06-11 上游最新，RapidOCR v3.10 ModelScope
    //          分发 SHA 已核验），det 归一化随模型从 ImageNet 统计量改 0.5/0.5
    //          （v5 起官方变更，不随改会静默失效），cls 沿用 v4 资产，
    //          extractModels 增加过期模型清理；app 模块死 OCR 资产（16MB）移除。
    //          端到端实测：中文三行全部正确识别（置信度 0.92~0.998）。
    // v0.001：版本体系重置（仓库重建后的首个正式发布版本）。
    //          - 版本命名由 v0.0.x 切换为 v0.001，baseVersionCode 归 1
    //            （versionCode = 1×10 + abiId，arm64 = 12）。
    //          - 主包瘦身：移除 app 模块残留的 onnxruntime 死依赖
    //            （Kotlin/native/CMake/Gradle 四类引用路径全查零引用），
    //            主包 89.4→77.1MB、通用包 140→87.7MB。
    //          - 许可合规：LICENSE 增补「适用范围声明」，明确 AGPL-3.0 仅覆盖
    //            MBM 自研代码、不覆盖上游 LGPL-2.1+/GPL-3.0 组件（原先
    //            README 写「整体 AGPL-3.0」在法律上站不住）；APK 内新增
    //            assets/licenses/{AGPL-3.0.txt,NOTICE.txt,UPSTREAM.txt}，
    //            应用内「关于 → 许可证」可离线查看，不再跳浏览器（无需联网）。
    // v1.0.33：Recall 查看/填充设备凭证门（BiometricPrompt）+ 开源许可查看器 +
    //          手写 nativeInit 分级日志与短读循环；recall splits 判据对齐，
    //          单架构构建不再产出 197MB universal 死产物。
    // v1.0.34：R3 第一期——主输入法剪贴板数据库改 SQLCipher（一次性后台迁移，
    //          失败自动回退明文不丢数据），两个 APK 的 blob 大文本文件新增
    //          分块流式 AES-256-GCM 静态加密（Keystore 信封密钥 + 遗留明文
    //          自动识别），详见 docs/PATCHES_v1.0.34.md。
    // v1.0.35：构建工具链升级（AGP 9.4.0 + Gradle 9.8.2），产品代码零改动、
    //          功能完全一致（80 单测全绿 + jyutping 文本替换产物逐字节等价）。
    //          顺带落地 AGP 10 严格行为预检（newDsl / builtInKotlin）、
    //          并行与构建缓存，并完成配置缓存的前置改造（见 pitfalls #77）。
    // v1.0.36：构建工具链例行升级（AGP 9.4.1 + Kotlin 2.4.21 + KSP 2.3.12 +
    //          build-tools 37.0.0 + Gradle 分发包改华为镜像 -all），产品代码零改动；
    //          NDK 28.0.13004108 / CMake 3.31.6 显式锁定保持不变（见 docs/PATCHES_v1.0.36.md）。
    // v1.0.37：产品层键盘视觉风格化重构（仿主流移动输入法：圆角键面、加大键间距、
    //          蓝调强调回车与空格，新增浅色/深色两套圆角内置主题），交互与功能零改动；
    //          同步清理代码库内全部第三方输入法专有名词引用（仅保留 fcitx5 本体标识），
    //          细胞词库导入（scel）类由专名重构为中性命名（见 docs/ 合规说明）。
    // N85：原恒为 1 → arm64 versionCode 恒为 12，升级无法覆盖安装、用户须卸载重装丢全部数据。
    // 每次发版必须 +1（arm64 = baseVersionCode*10 + 2）。
    const val baseVersionCode = 14
    const val baseVersionName = "0.1.4"

    val supportedABIs = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    const val fallbackABI = "arm64-v8a"

    fun calculateVersionCode(abi: String = fallbackABI): Int {
        val abiId = when (abi) {
            "armeabi-v7a" -> 1
            "arm64-v8a" -> 2
            "x86" -> 3
            "x86_64" -> 4
            else -> 0
        }
        return baseVersionCode * 10 + abiId
    }
}
