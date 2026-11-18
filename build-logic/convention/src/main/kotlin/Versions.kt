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
    // v1.0.61：词库（自定义词组）UI 三视图合一 + 锁定/删除/排序/置顶按钮常驻 + 用户置顶最高优先级档（PIN_ORDER=1）。
    // v1.0.62：自动词组键去重（一词组一行，修「三个海一耶哇」）+ 应用搜索单字母键（修「输入 m 不出 MT管理器」）
    //          + 置顶/锁定即时保存生效 + Recall 注入链路提速与可观测（reload 去抖 100ms / 引擎就绪兜底 reload）。
    // v1.0.63：用户选择驱动的个性化优先级（预判增强）—— CommitStringEvent 捕获上屏中文候选，复用 keysForName 反推首字母键，
    //          (键=>词) 维护选择计数+时间衰减+上下文，同键 Top-3 写入自定义词组 order 4/6/7（与 1/2/3/5/8/9 档位隔离互不误删），
    //          选过的词下次输入该拼音即靠前；纯离线、不写原文日志、敏感串跳过。
    // v1.0.64：修 v1.0.63 个性化优先级 bug——长键拼接垃圾污染候选（输入 aq 冒出「安庆安全/爱情安庆/安全爱情安庆」等）。
    //          根因：HANZI_RE 把最长 6 连汉字当一词、键取其首字母，fcitx 自定义词组前缀匹配使长键(如 aqaqaq)被短输入(aq)命中。
    //          改为只提升「用户选过的两字词」(键长恒 2)、更长上屏串切相邻 2 字窗口学到可复用短词；升级遗留清理清掉旧长键垃圾。
    // v1.0.65：修输入单字母 a 时候选栏尾部冒出 4~5 个惊恐脸 emoji 挤占汉字候选（fcitx5 quickphrase
    //          emoji 别名表 :fearful:/:anguished:/:open_mouth:/:astonished: 被单字母前缀成组命中）。
    //          新增 MbmCandidateSanitizer：候选事件进入 UI 前仅从尾部截掉纯 emoji/重复候选，
    //          前面汉字候选顺序与 native 索引完全不变（点击不错位）；emoji 面板能力保留。
    //          顺带把事件日志改为只打事件类型，不再输出候选内容（隐私：输入/候选不写日志）。
    // v1.0.66：修「选择驱动优先级」根本失效——native 自定义词组 order 是「候选位」不是「权重」
    //          (pinyin.cpp: CandidateOrder{order-1,...}, order=1 才是第一候选, <=0 跳过)。
    //          旧档位 [4,6,7] 把「安全」永远钉在第 4 位后，选多少次都压不过原生首候选「爱情」。
    //          改为 order=1 + 只 boost Top-1；归属判定改用持久化精确 key=value 托管集合
    //          (不再靠 order 区间，避免与 PersonalPhrase 3..7 / 置顶 1 互删)，跳过用户手动置顶。
    //          另修 PersonalPhrase 接续/双向/三连拼接词挂前词短键(aq)污染：改挂整短语键；
    //          migrateLegacy v3 清旧档残留并全量重算，升级后历史记忆立即按 order=1 生效。
    // v1.0.67：修「1.0.66 仍要输好几遍才把词组提到第一」。根因：flush 按 score=ln(1+count)*decay*ctx
    //          排序取 Top-1 写 order=1，主动选一次的词 count=1 分数低，永远抢不过同键反复被默认
    //          上屏的高频词（aq 的「爱情」）；且 kept 用全量 managedKeys 剔除、fresh 只含脏键，
    //          每次 flush 会误删非脏键已学到的置顶，用户只得反复重选。
    //          改为 last-commit-wins：每键只记「最近一次主动选择上屏的词」(winner_v4)，
    //          选一次即无条件写 order=1；精确只清理脏键名下旧行；落盘 400→60ms + reloadNow 立即重载；
    //          migrate v4 从历史 records 按最近上屏重建 winner，升级后历史记忆立即生效、无需重输。
    // v1.0.68：全量代码审查修复多处确证缺陷（跨 7 个文件，含 1 处档位注释校正），功能语义不变（修 bug 不新增/裁减功能）：
    //          MbmCandidateSanitizer 改按 Unicode code point 识别 emoji（原逐 UTF-16 Char 遍历，
    //          U+1F6xx 代理对永远匹配不到 ⇒ v1.0.65 的尾部 emoji 抑制实际从未生效）+ 全 emoji 栏保底
    //          + CandidateListEvent.total 随截短同步；
    //          UserChoiceStore 落盘失败不再误提交托管状态、无 winner 脏键不再误删置顶、
    //          粘贴/长文本不再冒充主动选词、winner 只收独立 2 字词、scheduleSave 加同步、
    //          loadRecords 逐条容错（原单条脏数据会吞掉全部历史记忆）；
    //          PinyinCustomPhraseFragment.saveConfig 补 CustomPhraseManager.writeLock（防并发 lost update）；
    //          PhrasePinStore / PhraseLockStore 懒加载缓存补线程同步；RecallWordStore 档位注释校正。
    // v1.0.69：候选词优先级优化改分层架构（硬层有序 Top-K 栈 + 软层融合打分），
    //          新增零 Android 依赖的纯算法层 rank/ChoiceRanker.kt（19 个 JVM 单测全通过），
    //          UserChoiceStore 改为委托并保留 v1.0.66~68 全部防御（精确托管/写锁/失败重试/迁移/隐私）。
    //          修正确定性次位：aq 选安全后选爱情 => 爱情 order=1、安全 order=2（旧版只保证第1）。
    //          融合打分只作用于次位重排/衰减退役/淘汰，永不推翻「最近一次选择即第一」硬规则；
    //          不做「同键短时改选⇒误选」的自动推断，避免误伤核心示例（详见设计文档 2.3）。
    // v1.0.70：修「哔哩哔哩输入优先级总被 OCR 排为顺序 2」。根因是 v1.0.69 硬层只认
    //          「2 字词 + 2 字母键」，4 字词「哔哩哔哩」/ 4 字母键 blbl 被 wordLen/keyLen
    //          两道过滤挡在门外，用户主动选也不写 order=1，只能吃 OCR/应用名自动模块的
    //          order=2/3（native `CandidateOrder{order-1}` 下即第 2 候选位）。
    //          扩展：单次上屏为单一连续汉字段时整段视为一个完整词（phraseLenMax=8），
    //          键放宽到 2..12 字母；UserChoiceStore.loadState 同步放宽 length==2 硬编码，
    //          否则重启即丢状态。仍不做窗口切片、仍排除单字母键，v1.0.66~69 全部防御保留。
    //          AppPhraseImporter/RecallWordStore 的 minByOrNull{order} 保证用户 order=1
    //          不会被 OCR 的 order=2/3 覆盖。新增多字词/长键/防污染 JVM 单测。
    // v1.0.71：一轮真 bug 修复，不改变任何既有功能。
    //  1. PersonalPhraseImporter.syncFromUsage 的 merged=(kept+finalEntries) 缺跨 kept 的
    //     key=value 去重（dedup 只覆盖 finalEntries 内部），用户选过的词同时是个人高频词时
    //     必然产生同键同值重复行；补 groupBy.minByOrNull{order}，与其余 3 个写入模块一致。
    //  2. v1.0.69 硬层 Top-K 写 order=1/2/3，2 与 3 落进 AppPhraseImporter{2,8} /
    //     RecallWordStore{3,9} / PersonalPhraseImporter{3..7} 的 isOwnEntry 区间，会被当
    //     作自动模块旧条目剔除，用户第 2/3 候选排序随机丢失（v1.0.70 只护住了 order=1）。
    //     新增只读 UserChoiceStore.isOwnChoiceEntry，三模块剔除时按精确 key=value 让位。
    //  3. recordCommit 的 privacyMode 硬编码 false，注释声称的纵深防御从未生效；改读
    //     PrivacyController.inPrivacyMode（commitText 第一道过滤保持不变）。
    //  4. ChoiceRanker.importState / UserChoiceStore.loadState 的 records 与 stacks/samples
    //     校验口径不一致（后者有 validKey，前者几乎不设防），畸形/遗留脏 id 会被载入。
    //     统一为「合法 key + 2..8 字值」，新增 3 个 JVM 单测（含多字词/长键防误拒）。
    // v1.0.72：预测功能增强（不改变既有行为，只新增通道）。
    //  1. 根因修复：native `pinyin.cpp` 用 lookup(pyBeforeCursor) 精确匹配用户实际敲入的
    //     原始拼音串，此前全仓库只写「首字母缩写键」(aq/blbl)，全拼用户(anquan)永远
    //     吃不到 v1.0.66~71 的全部个性化排序与 N-gram 预测。新增 PhraseKeys.forWord，
    //     写词组时同一 order 额外挂全拼键；UserChoiceStore 的托管/剔除范围同步覆盖，
    //     避免旧全拼行脱离托管成为孤儿。首字母键一行不动，零回归。
    //  2. 新增 ContextPredictor（纯算法层）+ PredictionStore（Android 层）：复用本地
    //     bigram/trigram，把「刚上屏的词的高置信下一词」写成 order=1 预测冠军，
    //     60ms 落盘 + reloadNow。严格让位：任一键已有用户硬层历史、该词已被用户选过、
    //     已手动置顶，一律不写；每次上屏全量替换托管行，不长期占首位。
    //     「选择 >= 1 次即第一」硬规则完全不被削弱。新增 17 个 JVM 单测。
    //  3. 同步修正文档与实际代码不符项（隐私政策/NOTICE/README/许可标注/离线许可入口/
    //     备份排除规则），并补 v1.0.72 源码快照以满足 AGPL §6 对应源码要求。
    // v1.0.73：表情输入输出的完整支持（不改变任何既有输入功能）。
    //  1. 真 bug 修复：EmojiModifier.isValidEmoji 与 EmojiPickerPolicy 用
    //     Paint.hasGlyph(整串) / UnicodeSet.contains(整串) 判定多码点序列，而两者对
    //     ZWJ / 肤色 / RI 旗帜 / Tag / 键帽等复合 RGI 序列恒返回 false —— 于是「隐藏
    //     不支持的表情」默认 true 时，PickerData.Emoji 中全部复合序列被静默裁掉。
    //     新增零 Android 依赖的 EmojiSequence（UTS#51 结构判据），数据层判定与宿主
    //     字体渲染能力解耦；开关默认改 false，默认支持全部 Unicode Emoji 序列。
    //  2. 新增「用户自定义图像表情」开放容器：CustomEmojiProtocol/Repository，
    //     用户运行时经 SAF 导入 image 资产或 ZIP，落应用私有目录 + pack.json 清单，
    //     不预置、不下载任何第三方专有素材；文件名净化、MIME 白名单、体积/条数上限、
    //     Zip Slip 防护、清单逐条容错，全部可由 JVM 单测验证。
    //  3. 富媒体输入/输出闭环：EmojiRichContentCommitter 走 Android Rich Content API
    //     (InputConnectionCompat.commitContent + FileProvider content:// 临时授权)，
    //     目标不支持时降级文本；表情面板动态追加「自定义表情」分类并渲染缩略图，
    //     剪贴板复制侧捕获 image 资产落盘，实现「复制表情后完美显示」。
    //  4. 合规：清除第一方代码/注释/文档中的第三方应用名称，新增文件仅使用
    //     Unicode/CLDR/AndroidX/AOSP 等标准与开源组件名称及 SPDX 许可标识。
    // v1.0.74：真 bug 修复（不改变任何既有输入功能）。
    //  1. 用户实测「反复点拆分选词，显示的都是小点点」：v1.0.45~73 的
    //     WordSelectWindow 直接读取全局偏好 clipboardMaskSensitive（默认 true）
    //     作为整屏打码开关，未判断被打开的条目本身是否被系统标记为敏感；于是
    //     任意普通文本进入拆分选词，WordSelectUi 都把每个词块替换成 `•`，
    //     面板只剩一排小点点、无法辨认也无法选词。改为与剪贴板列表同一语义：
    //     仅 `entry.sensitive && 用户打码偏好` 时遮蔽。
    //  2. 新增零 Android 依赖的 WordSelectMaskPolicy（打码判定 + 词块展示文本）
    //     与 6 个 JVM 回归单测；普通条目在任何偏好下都显示原文，敏感条目的
    //     隐私保护不回退。不改动候选排序、词库、预测与表情链路。
    // v1.0.75：修复「复制表情完全失效 / 输出乱码」（不改变任何既有输入功能）。
    //  1. 真 bug（复制侧窄门）：captureImageSticker 只认「剪贴板声明 image 类
    //     MIME + ClipData.Item.uri」这一种形态，宿主应用复制表情时几乎都不是
    //     这样（content URI 不带声明、URI 藏在纯文本/URI 列表/HTML img 标签、
    //     或 Intent 型 ClipData），第一步就 return，表现为复制后毫无反应。
    //     改为枚举所有 Item 的 uri/text/htmlText/intent.data，用纯函数提取
    //     content URI 候选；新增 ClipboardStickerExtractor 以文件头魔数嗅探
    //     真实位图格式（PNG/JPEG/GIF/WebP/BMP/HEIC/AVIF），仓库单流读取落盘，
    //     不信任剪贴板声明的 MIME，短码/乱码/HTML 一律不入库。
    //  2. 真 bug（输出侧）：多数聊天输入框不声明 EditorInfo.contentMimeTypes
    //     的 image 能力，旧版此时降级提交文本短码 :[label]:，即用户看到的乱码，
    //     等于输出失效。改为「富媒体直投 → 不支持则把图像以 content URI 写回
    //     系统剪贴板（ClipData.newUri + image 类 MIME + 临时读授权，任意输入框
    //     长按粘贴即可贴出原图）→ 文本短码降级默认关」三段式通用输出。
    //  3. 静默无反馈：捕获成功后注册即时提示钩子，告知「已加入表情面板」。
    //     新增 ClipboardStickerExtractorTest；全程不联网、不含任何第三方应用
    //     名称、不内置任何专有素材，不触碰候选排序/词库/预测链路。
    // v1.0.76：一轮真 bug 修复（不改变任何既有功能）。
    //  1. 复制侧表情包配额：importClipboardImage 达到 500 条即 return false，
    //     使尾部 takeLast 淘汰逻辑成为死代码；且文件名用 nanoTime 生成、
    //     无内容去重，重复复制同一张图会不断新增条目。用户累计复制 500 次
    //     后新表情永久无法加入 —— 正是「表情支持完全失效」的残留根因。
    //     改为文件名由内容摘要派生 + ClipboardStickerPackPolicy 纯策略合并
    //     （重复项刷新为最近、按上限保留最新），配额永不耗尽。
    //  2. 分块流魔数嗅探：InputStream.read 允许分块返回（ContentProvider 基于
    //     pipe 时常见）甚至返回 0，旧实现只在第一次 read 后就用「数组长度」
    //     而非「有效字节数」判定，PNG 8 字节签名被拆开即误判丢弃真图像。
    //     新增 ImageHeaderSniffer 逐段累积 + sniffImageMime(head, validBytes)。
    //  3. GIF/BMP 魔数过松：只校验 "GIF8" / "BM" 前缀，普通文本会被误判为
    //     图像落盘；改为 GIF 完整 6 字节签名、BMP 校验 14 字节文件头规范字段。
    //  4. 生命周期：FcitxInputMethodService 注册到 ClipboardManager 单例的
    //     捕获钩子在 onDestroy 未解注册，IME 反复重建会泄漏已销毁 Service。
    //  5. 一致性：captureImageSticker 的 scheme 比较大小写敏感，与提取器
    //     ignoreCase 提取出的 "CONTENT://" 候选互相抵消；导入全程串行化并
    //     过滤清单中指向已删除文件的幽灵条目，修并发覆盖与面板空白格。
    //  新增 16 个 JVM 单测，全量 159/159 通过；第一方 485 文件带 SPDX、0 缺失。
    // v1.0.77：无上限复制 / 粘贴的完整性审计与补齐（存储/上屏/导出/分享本就无上限，
    //          本版修 4 处让「完整」成立：多 Item 只记第一项、trim 静默丢字、写回
    //          系统剪贴板超限静默失败却谎报成功、大文本后半段静默隐藏），并把 6 处
    //          散落硬上限收口到 ClipboardCapacityPolicy（UI 单次装载可配置、截断明确披露）。
    // v1.0.78：修复 150MB 佐证发现的分块契约偏差——ClipboardPasteChunker 内层固定按
    //          chunkSize 读，上一块 UTF-8 carry 前移后单块可达 chunkSize+carry
    //          （混合 UTF-8 实测 262146 = 256KB+2，超出标称 256KB）。改为内层读
    //          chunkSize-carry，单块严格 ≤ chunkSize，标称契约与实现一致；
    //          不影响拼接完整性（仍逐字节一致、无替换符、无丢字）。
    // v1.0.79：修复剪贴板复制自定义表情时「显示乱码、输出错误表情」。
    //          根因是 ClipboardEntry.fromClipData 把宿主应用私有表情编码串
    //          （控制字符 + emoji + Latin 扩展乱码）当作可粘贴正文落库；
    //          v1.0.75~78 都只修「图片导入表情面板」这一侧，从未碰文本链路。
    //          现在捕获到图像时把历史条目改为可富媒体上屏的 sticker token，
    //          并修复「一次复制多张只导入第一张」；普通文本复制行为零变化。
    // v1.0.80：预测增强（不改变既有功能，只新增软层信号）。
    //          隐式负反馈（拒绝信号）：用户选中某词时，同键下被展示却未被选中的
    //          兄弟候选累加一次拒绝信号，软层分被温和压制、更快沉底/退役，使个性化
    //          Top-K 更贴合真实选择；选中词自身清零拒绝；硬层冠军(order=1) 语义完全
    //          不受影响。纯本地 SP/JSON、零 Android 依赖、7 个新增 JVM 单测、0 回归。
    //          无新开源库、无 SPDX 变化、无训练数据、全离线。
    // v1.0.81：一轮真 bug 修复（不改变既有功能，只收敛错误行为）。
    //          Bug1：ClipboardMediaEntryPolicy.looksLikeUriDerived 把「含 :// /
    //          以 / 开头」两条快捷判定放在纯 ASCII 守卫之前，导致含 URL 的普通
    //          可读文本（中文说明+链接、多词英文句子）被误判为 URI 派生文本；
    //          图文同次复制时调用方据此把用户文字替换成图像 token 条目 -> 文字丢失。
    //          修复为快捷判定先过同一套紧凑 ASCII 守卫，纯 URI 串行为不变。
    //          Bug2：ClipboardManager.updateText 在更新 DB 元数据前就物理删除旧
    //          blob，绕过 N01/N02 引用计数 GC；blob 内容寻址共享（不同 sensitive
    //          行、软删可 undo 行、两条写入路径），会永久删除其它条目全文。
    //          改为先更新元数据、再按 allBlobShas() 引用计数条件回收；
    //          allBlobShas() 去掉 deleted=0 使软删可恢复行也计入引用。
    //          新增纯 JVM 策略 ClipboardBlobRefPolicy + 8 个 JVM 单测，全量 210/210。
    //          无新开源库、无 SPDX 变化、无训练数据、全离线。签名沿用 v1.0.80
    //          同一证书（db238888…b8d0），可直接覆盖安装 v1.0.80。
    // v1.0.82：一轮真 bug 修复（不改变既有功能，只让声明的判据真正生效）。
    //          Bug：RecallTextUtils.properNounTokens 的 KDoc 判据 2 要求「至少一个
    //          ASCII 字母或命名分隔符」，实现却用 Char.isLetter()，而汉字是 Unicode
    //          字母（Character.isLetter('苹')==true），对任何含汉字的 token 恒为 true，
    //          判据 2 形同虚设。于是「苹果12个」等汉字+数字短句、以及整句纯汉字短语
    //          被通道 D 当成「混合中英文专名」越过 isAllHan 门控写进词库，污染候选。
    //          收紧为真正的 ASCII 字母 + PROPERNOUN_GLUE 分隔符；含英文版本号的应用名
    //          （雨见cBeta）、含 · 的游戏名（云·战双帕弥什）仍正常命中。同类疏漏
    //          AppPhraseImporter 已用 code<128 修过，此处是漏网。新增 6 个 JVM 单测，
    //          全量 216/216 零回归。无新开源库、无 SPDX 变化、无训练数据、全离线。
    //          签名沿用 v1.0.80/81 同一证书（db238888…b8d0），可覆盖安装。
    // v1.0.83：一轮真 bug 修复（不改变既有功能，只让声明的运算符真正生效）。
    //          Bug：CalcEngine.hasOperator 只检查 + - * / × ÷，漏掉了文档文法
    //          `postfix := primary '%'？（n% = n/100）` 中的后缀 % 运算符。于是
    //          「10%」「50%」「200-50%」这类算式被「无运算符」闸门整体拒掉、返回 null，
    //          而解析器本身（parsePostfix 做 n/100）早已正确支持 %。
    //          修复为把 % 纳入 hasOperator 的运算符集合；仅有后缀 % 的合法算式现在正常求值。
    //          新增 CalcEngineTest 回归单测（10%→0.1 / 50%→0.5 / 100*5%→5 / 200-50%→199.5，
    //          5%% 仍拒），全量单测零回归。无新开源库、无 SPDX 变化、无训练数据、全离线。
    //          签名沿用 v1.0.80~82 同一证书（db238888…b8d0），可覆盖安装。
    // v1.0.84： audits 核实后修复 2 个真 bug（外部 agent 审的是 Gitee v1.0.35 旧 fork，
    //          本版为 GitHub 主线 v1.0.83；逐条对当前代码核实后只认领仍成立的）：
    //          (1) P1 安全：TableManager 码表导入的 confName/dictName 来自 SAF DISPLAY_NAME
    //              （文档提供者可控），裸 File(tempDir,name) 可被绝对路径或 ../../ 穿越，
    //              写到沙箱任意位置覆盖 databases/。抽纯 JVM 的 TableImportPaths.resolve 走
    //              既有 PathSafety.safeResolve（单段名 + canonical 校验）；3 处调用点全替换。
    //          (2) P1 健壮：ZipStream.extract 写文件前未建父目录，zip 只含 sub/dir/a.txt
    //              而无显式目录条目时抛 FileNotFoundException，备份/主题/码表导入失败；
    //              加 file.parentFile?.mkdirs()，canonical 穿越防护保持不变。
    //          驳回项：HardwareBuffer 泄漏（RecallService 已有 P0-1 finally 修复，agent 看的是旧版）、
    //          ZipStream 无 cap（加 cap 会误伤合法大备份，违反不改变原有功能）、
    //          blob dedup 明文抖动（key==null 降级路径、无数据损坏、测试已覆盖结果正确）。
    //          新增 ZipStreamTest + TableImportPathSafetyTest 共 6 个 JVM 单测（先失败后转绿），
    //          全量单测零回归。无新开源库、无 SPDX 变化、无训练数据、全离线。
    //          签名沿用 v1.0.80~83 同一证书（db238888…b8d0），可覆盖安装。
    const val baseVersionCode = 38
    const val baseVersionName = "0.1.5"

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
