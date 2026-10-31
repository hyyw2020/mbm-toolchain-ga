# 崩溃陷阱记录（docs/pitfalls.md）

> 长期记忆：每次修复崩溃后，把「现象 / 根因 / 修复」三行追加到本文件。
> 改动 UI / Activity / 主题相关代码前，先读本文件 + `CLAUDE.md`（或 `AGENTS.md` /
> `.cursorrules`），复述将遵守的约束再动手。

## 1. [CRASH] Material 组件在 AppCompat 主题下构造即崩
- **现象**：从入口冷启动 `RuleImportActivity`（以及同根的 `SmartDictActivity`、
  `RecallActivity`、`PasswordGeneratorActivity`）即闪退。logcat：
  `java.lang.IllegalArgumentException: The style on this component requires your app theme to be Theme.MaterialComponents (or a descendant.)`
  栈顶：`com.google.android.material.button.MaterialButton.<init>` →
  `org.fcitx.fcitx5.android.ui.main.RuleImportActivity.buildUi(RuleImportActivity.kt:90)`
- **根因**：四个 MBM Activity 在 `AndroidManifest.xml` 中未指定 theme，继承了应用主主题
  `Theme.FcitxAppTheme`（parent=`Theme.AppCompat.Light.NoActionBar`）。AppCompat 不是
  MaterialComponents / Material3 的后代；`MaterialButton` / `SwitchMaterial` /
  `TextInputLayout` 在构造时强制 `ThemeEnforcement.checkTheme`，主题链不匹配直接抛
  `IllegalArgumentException`。**同一根因，四个 Activity 都会崩**；首份日志只抓到
  `RuleImportActivity`。
- **修复**：在 `app/src/main/res/values/themes.xml` 新增 `Theme.MbmActivity`
  （parent=`Theme.Material3.DayNight.NoActionBar`，仅覆盖 window 背景/状态栏/导航栏颜色）；
  在 `AndroidManifest.xml` 把四个 MBM Activity 的 `android:theme` 指向
  `@style/Theme.MbmActivity`。**未改全局 `Theme.FcitxAppTheme`**（输入视图依赖其 AppCompat
  链），以最小改动消除崩溃。约束与自检清单见根目录 `CLAUDE.md` / `AGENTS.md` / `.cursorrules`。
- **崩溃栈（节选，设备 Honor PTP-AN70 / Android 15 / API 37）**：
  ```
  E/AndroidRuntime: java.lang.RuntimeException: Unable to start activity ComponentInfo{org.fcitx.fcitx5.android/org.fcitx.fcitx5.android.ui.main.RuleImportActivity}
  Caused by: java.lang.IllegalArgumentException: The style on this component requires your app theme to be Theme.MaterialComponents (or a descendant).
      at com.google.android.material.internal.ThemeEnforcement.checkTheme(ThemeEnforcement.java:249)
      at com.google.android.material.internal.ThemeEnforcement.checkMaterialTheme(ThemeEnforcement.java:218)
      at com.google.android.material.internal.ThemeEnforcement.checkCompatibleTheme(ThemeEnforcement.java:146)
      at com.google.android.material.internal.ThemeEnforcement.obtainStyledAttributes(ThemeEnforcement.java:78)
      at com.google.android.material.button.MaterialButton.<init>(MaterialButton.java:300)
      at com.google.android.material.button.MaterialButton.<init>(MaterialButton.java:288)
      at com.google.android.material.button.MaterialButton.<init>(MaterialButton.java:284)
      at org.fcitx.fcitx5.android.ui.main.RuleImportActivity.buildUi(RuleImportActivity.kt:90)
      at org.fcitx.fcitx5.android.ui.main.RuleImportActivity.onCreate(RuleImportActivity.kt:60)
  ```

## 2. [INVESTIGATED] SQLCipher "nativeOpen" UnsatisfiedLinkError —— 经排查本构建不成立（已加固）
- **现象（外部 AI 分析假设）**：有分析称从入口冷启动 Recall / RuleImport / SmartDict 即闪退，
  栈为 `java.lang.UnsatisfiedLinkError: No implementation found for long
  net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen(...)`，
  调用链 `SupportSQLiteDriver → SupportHelper.getWritableDatabase →
  Room InvalidationTracker.syncTriggers`。**但本工程没有任何一份真实崩溃日志包含该栈**
  （唯一一份日志是 #1 的 MaterialButton 主题崩），故先排查、再下结论。
- **根因排查（证据，逐条核对）**：
  1. `libsqlcipher.so` 已打包进 `Universal.apk` 全部 4 ABI（arm64-v8a / armeabi-v7a / x86 /
     x86_64），每个 1–2MB；`unzip -l Universal.apk | grep libsqlcipher` 可核对。
  2. 合并清单 `android:extractNativeLibs=true` → ART 正常抽取并加载打包的 .so
     （fcitx5 数十个 .so 能正常加载即证明该机制可用）；`libsqlcipher.so` 同样可加载。
  3. 抽出 `libsqlcipher.so` 校验：导出 `JNI_OnLoad`，字符串表含 `nativeOpen` / `sqlite3_open`
     → 库一加载即注册 `SQLiteConnection.nativeOpen`，不会 "No implementation found"。
  4. `net.zetetic:sqlcipher-android:4.19.1` 的 Java 代码**不含任何 `System.loadLibrary`、
     也没有 `loadLibs` 方法**（已逐类反编译确认）——4.x 依赖 Android ART 在首次 native 调用时
     自动加载；所以"缺 loadLibs 调用"这一断言在 4.19.1 上不成立，且 `loadLibs` 已不存在，
     调用它会**编译失败**。
  5. 本工程 release 关闭 minify（无混淆），且 AAR 自带 consumer proguard 已保留
     `net.zetetic.database.**`，R8 不会剥掉 SQLCipher。
  结论：该 `UnsatisfiedLinkError` 在本构建中**不会复现**；真正导致三个 Activity 冷启动崩的是 #1 的
  Material 主题，已在 v1.0.1 修复。注：RuleImport / SmartDict **只崩在 UI（#1 主题）**，并不碰
  SQLCipher 库；只有 Recall 走加密库，而加密库在本构建可正常加载。
- **修复（防御性加固，非修"已发生崩溃"）**：
  1. `FcitxApplication.onCreate` 开头新增 `System.loadLibrary("sqlcipher")`，在任何数据库访问前
     显式加载（对 4.x 是保险，且 `System.loadLibrary` 对已加载库幂等、不抛异常）；fail-fast——
     若打包缺失对应 ABI 的 .so，会在开库前立刻暴露而非事后难定位。
  2. `app/proguard-rules.pro` 显式保留 `net.zetetic.database.**` 及 native 方法
     （防未来开启 minify 后被剥）。
- **验证方式**：release 构建 + 清数据冷启动 → 进入 Recall 页触发首次读库
  （`RecallRepository` 调 `RecallDatabase.get`）；`adb logcat` 不应出现 `UnsatisfiedLinkError`
  / `dlopen failed`；或直接 `unzip -l Universal.apk | grep libsqlcipher` 确认 4 ABI 俱在、
  `aapt2 dump xmltree` 确认 `extractNativeLibs=true`。

## 3. [UI] edge-to-edge 下 MBM Activity 标题被状态栏/导航栏遮挡
- **现象**：在 targetSdk 35+ 设备上，四个 MBM 设置 Activity（Recall / RuleImport /
  SmartDict / PasswordGenerator）的标题（如「Recall 屏幕记忆」）直接顶到状态栏下方，
  被状态栏时间/图标压住，且底部按钮可能被导航条遮挡。Android 12+（尤其 35+）默认强制
  edge-to-edge，系统栏透明，内容会绘制到系统栏之下，除非手动处理 `WindowInsets`。
- **根因**：四个 Activity 用 `ScrollView` 作根、`setContentView(scroll)`，但**没有对系统栏
  做 insets 适配**，也没有 `android:fitsSystemWindows`（自定义 View 组合场景该属性不生效）。
- **修复**：新增 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MbmActivityUi.kt`
  的 `fitInsets(activity, scroll)`：
  1. `WindowCompat.setDecorFitsSystemWindows(window, false)`（关闭系统帮着垫 insets）；
  2. `ViewCompat.setOnApplyWindowInsetsListener(scroll)` 把 `systemBars()` 的
     `left/top/right/bottom` 设为 `scroll` 的 padding，并把该 insets 清零以免子 View 重复加；
  3. `WindowInsetsControllerCompat` 设 `isAppearanceLightStatusBars=false` /
     `isAppearanceLightNavigationBars=false`（深色背景用浅色系统栏图标，避免看不清）。
  四个 Activity 在 `setContentView` 后各调一次 `fitInsets(this, scroll)`。
- **验证方式**：在 API 35 真机/模拟器进入 Recall 页，标题与状态栏时间不再重叠；
  `adb shell dumpsys activity top` 或截图确认顶/底留白。

## 4. [DESIGN] Recall 捕获数据「死胡同」——无消费路径、无去向日志
- **现象**：用户反馈「只看见 Recall 捕获了几百条数据，但没有明确按钮/引导让我导入到实际输入法
  词库」「实际上也沒有日志记录数据去向，很危险」。确属真实缺陷：捕获数据只能进本地密文库，
  既进不了输入法、也没有任何审计记录，用户完全不知道数据去哪了。
- **根因**：① `SmartDictManager.recordUsage()` **无任何调用方**，F2「智能候选词」仅靠将来
  提交文本时被动累计，Recall 数据从不注入；② Recall 页只有「删除/清空」，没有「导入/导出」，
  也没有「数据去向」展示。捕获与消费两端断裂。
- **修复（v1.0.3，全离线闭环）**：
  1. 新增 `RecallActivity` 的「导入到输入法」按钮：勾选记录 → 经
     `ClipboardManager.addText(text, pinned=true)` 写入 fcitx5 剪贴板库（输入时在剪贴板候选
     可见并可一键上屏，无需拼音标注）；去重，返回新增/已存在数量。
  2. 新增「导出为文本」按钮：选中文本落地到
     `getExternalFilesDir(DIRECTORY_DOWNLOADS)/Recall-导出-<时间戳>.txt`，路径回显给用户。
  3. 新增 `app/src/main/java/org/fcitx/fcitx5/android/recall/data/RecallAuditLog.kt`：
     每次「导入/导出/删除/清空」追加一行到本地明文文件 `filesDir/recall_audit.log`
     （与 SQLCipher 密文库分离，便于随时查看）；Recall 页底部「数据去向」面板实时显示最近 30 条。
  4. Recall 页增加「数据怎么用」引导文案 + 空态提示，明确告诉用户数据不出本机。
- **关键约束**：所有操作均在本机完成，不新增任何网络权限；审计日志仅记「时间/动作/条数/去向」，
  不含被捕获的明文内容，避免二次泄露。禁止把捕获文本用于联网上传或第三方共享。
- **验证方式**：真机勾选若干条 → 点「导入到输入法」→ Toast 显示新增/已存在数，且输入法剪贴板
  候选出现这些文本；点「导出」→ 文件管理器可见 `Recall-导出-*.txt`；页面底部「数据去向」出现
  对应记录行；`adb shell run-as org.fcitx.fcitx5.android cat files/recall_audit.log` 可查。

## 5. [CRASH][已证实] fitInsets 在 DecorView 就绪前构造 WindowInsetsControllerCompat → API 35+ NPE
- **现象**：API 35+ 设备（Honor PTP-AN70 / Android 15 / API 37）冷启动 `RecallActivity` 即闪退。
  `RuleImport` / `SmartDict` / `PasswordGenerator` 的 `fitInsets` 均在 `setContentView` **之后**调用，
  **不**触发此 NPE（调用顺序正确）。崩溃栈：
  ```
  E/AndroidRuntime: java.lang.RuntimeException: Unable to start activity ComponentInfo{.../...ui.main.RecallActivity}
  Caused by: java.lang.NullPointerException: Attempt to invoke virtual method
    'android.view.WindowInsetsController com.android.internal.policy.DecorView.getWindowInsetsController()'
    on a null object reference
      at com.android.internal.policy.PhoneWindow.getInsetsController(PhoneWindow.java:4534)
      at androidx.core.view.WindowInsetsControllerCompat$Impl30.<init>(...)
      at androidx.core.view.WindowInsetsControllerCompat$Impl31.<init>(...)
      at androidx.core.view.WindowInsetsControllerCompat$Impl35.<init>(...)
      at androidx.core.view.WindowInsetsControllerCompat.<init>(...)
      at org.fcitx.fcitx5.android.ui.main.MbmActivityUi.fitInsets(MbmActivityUi.kt:29)   [修复前行号]
      at org.fcitx.fcitx5.android.ui.main.RecallActivity.buildUi(RecallActivity.kt:81) [修复前：setContentView 之前调用]
      at org.fcitx.fcitx5.android.ui.main.RecallActivity.onCreate(RecallActivity.kt:68)
  ```
- **根因**：`MbmActivityUi.fitInsets` 构造 `WindowInsetsControllerCompat(activity.window, scroll)`，
  其 API 30+ 实现（`Impl30` 起）在构造时调用 `PhoneWindow.getInsetsController()`，直接解引用
  `mDecor`（DecorView）。DecorView 仅在 `setContentView` 之后才创建；而 `RecallActivity.buildUi()`
  **先**调 `fitInsets`（第 81 行）**后**才 `setContentView(scroll)`（第 251 行），故此时 `mDecor == null` → NPE。
  **为何只在 API 35+（回答用户之问）**：该 NPE 路径（`Impl30` 调 `getInsetsController()` 解引用 `mDecor`）
  自 API 30 即存在；用户设备 API 37 走 `Impl35` 分支（`Impl35→Impl31→Impl30` 链）恰好命中；
  低版本若 `PhoneWindow.getInsetsController()` 在 `mDecor` 为 null 时返回 `null`（而非抛 NPE）则不崩，
  或低版本设备未走此路径。**无论哪版，只要 DecorView 未就绪就构造 WindowInsetsControllerCompat 即危险**，
  故修复须对全 API 兜底，而非只绕开 35+。
- **修复**（v1.0.4，公共位置一次性完成，未逐 Activity 打补丁、未用假修复）：
  1. `MbmActivityUi.fitInsets` 把「设置系统栏图标深浅」（`WindowInsetsControllerCompat` 构造）延迟到
     视图挂接（DecorView 就绪）后：若 `scroll.isAttachedToWindow` 为 true 则立即执行，否则用
     `scroll.addOnAttachStateChangeListener { onViewAttachedToWindow { ... } }` 等 `onAttach` 再执行，
     **一次性兜底全部 4 个 MBM Activity**，不再依赖调用时机（本工程 4 处调用均在 `setContentView` 之后，故通常立即执行）。
  2. 同时把 `RecallActivity` 的 `fitInsets(this, scroll)` 调用从 `setContentView` 之前移到之后
     （与另外三个 Activity 顺序一致），作为清晰、正确的调用顺序。
  3. 「避开系统栏」仍只用 `ViewCompat.setOnApplyWindowInsetsListener` 改 padding，不碰 `WindowInsetsController`。
  4. **未**用 try-catch 包异常 / **未**用 `if (SDK_INT < 35)` 绕开 / **未**降 targetSdk / **未**只加日志。
- **验证方式**：API 35+ 模拟器（API 35/36/37 任一）release 构建，冷启动依次打开
  `Recall` / `RuleImport` / `SmartDict` / `PasswordGenerator`：
  `adb install -r Universal.apk` →
  `adb shell am start -n org.fcitx.fcitx5.android/.ui.main.RecallActivity`（及其余三个），
  `adb logcat | grep -i "AndroidRuntime\|FATAL"` 不应有 NPE / RuntimeException；
  且标题与状态栏时间不再重叠（insets padding 生效）。低版本（API 30–34）复测一次确认不回归。
- **与本工程其他崩溃的区分**：与 #1（MaterialButton 主题 `IllegalArgumentException`，[已证实]）、
  #2（SQLCipher `UnsatisfiedLinkError`，经排查本构建不成立、已加固）**栈完全不同、根因独立**，三者互不覆盖。
  #1/#2 均为**真实有效**的长期记忆（#1 带真实崩溃栈、Theme.MbmActivity 绑定已在 Manifest 生效；
  #2 为经反编译核对后的「非本构建根因」结论），**非猜测**，故不予删除或标注证伪；本条目为新增的独立根因。

## 6. [ANR] 无障碍回调在主线程遍历节点树 → 输入法进程无响应
- **现象**：打开 Recall 页面或后台持续捕获时，系统弹出「小企鹅输入法无响应。是否将其关闭？」
  （等待 / 关闭）。设备 HONOR PTP-AN70、API 37。卡顿/无响应而非闪退。
- **根因**：`RecallService.onAccessibilityEvent()`（**主线程回调**）里直接执行
  `rootInActiveWindow` + `walk()` 递归遍历整棵 `AccessibilityNodeInfo` 树。
  无障碍节点树的每一次 `getChild(i)` / 属性读取都是**跨进程 binder IPC**；
  大页面（系统 UI、内容密集页、高速滚动页面）动辄上百次 IPC，把输入法进程的主线程
  卡死 >5s → ANR。服务跑在输入法进程里，故提示框显示「小企鹅输入法」。
  另：`onDestroy()` 原 `runBlocking { trimJob.cancelAndJoin() }` 在主线程同步等待协程取消，
  也是潜在主线程阻塞点。
- **修复**（v1.0.5，公共位置一次性完成）：
  1. `onAccessibilityEvent` 只做最廉价的节流判断（时间 + 包名），随后 `scope.launch` 把
     `captureNodeTree()`（rootInActiveWindow、walk 遍历、入库、视情况触发 OCR）整体丢到
     后台协程（`Dispatchers.Default`）。主线程回调里**不做任何 binder IPC / 遍历**。
  2. `onDestroy()` 改为 `trimJob?.cancel()`（异步取消），不再在主线程 `runBlocking` 等待。
  3. **未**用 try-catch 包异常 / **未**降低捕获频率掩盖问题（节流间隔已是设计值，非补丁）。
- **验证方式**：API 35+ 真机/模拟器，开启 Recall 无障碍服务，在内容密集的应用（设置、微信聊天列表）
  高速滚动 1–2 分钟，观察是否再弹「无响应」；`adb shell am monitor` 或
  `adb bugreport | grep -A20 "ANR org.fcitx.fcitx5.android"` 不应出现新 ANR；
  同时确认节点捕获仍在正常入库（Recall 列表增长）。
- **与本工程其他崩溃的区分**：与 #1（MaterialButton 主题）、#2（SQLCipher）、#5（fitInsets NPE）
  **机制完全不同**（此处是 ANR 而非 FATAL，根因是主线程 binder IPC 阻塞）。

## 7. [BUG] 诊断日志导出只有头部、没有 logcat 正文
- **现象**：从「查看日志」页导出 `.txt`，文件只有 `Device Info` / `Package Info` / `Build Info`
  三段头信息，没有 `--------- beginning of main` 及任何日志正文，无法用于故障诊断。
  用户反馈两次构建（08:09:22Z / 05:11:53Z）导出的都是这种「空日志」文件。
- **根因**（两个叠加）：
  1. `LogView.setLogcat` 的收集协程里用 `it.first()` 取日志级别首字符；logcat 输出**含空行**，
     `String.first()` 对空字符串抛 `NoSuchElementException`，**杀死整个 logcat 收集协程** →
     之后所有行不再显示、导出文件只剩头信息。
  2. `Logcat.getLogAsync()` 旧参数 `arrayOf("logcat", pid?.let { "--pid=$it" } ?: "", "-d")` 在
     `pid` 为空时把**空字符串**塞进参数数组，logcat 收到非法空参数直接退出，读不到任何行；
     部分 ROM 对 `--pid` 过滤的支持/权限也各异，进一步导致空输出。
- **修复**（v1.0.5）：
  1. `Logcat` 改为 `buildArgs()` 仅在存在 pid 时追加 `--pid`；快照显式读取
     `main + system + crash` 三缓冲区；**带 --pid 读空时降级为不带 --pid 再读一次**
     （非特权应用本就只能读本 UID 的日志，安全性不变）。
  2. `LogView` 收集时 `if (it.isBlank()) return@onEach` 跳过空行，不再崩溃。
  3. `LogActivity` 导出时：若 `currentLog` 为空，现场调 `Logcat().snapshot()` 抓快照兜底写入；
     若快照仍为空，写明原因并引导用电脑 `adb logcat` 抓取（ROM 限制 / 缓冲区清空）。
- **验证方式**：导出日志 → 文件应含 `--------- beginning of main` 及多行 `V/D/I/W/E` 日志；
  勾选一条日志为空行不应再使后续日志消失；若设备确实读不到 logcat，文件末尾应出现
  「警告：未读到任何 logcat 输出…用 adb logcat 抓取」说明而非静默空文件。

## 8. [BUG] Recall 页面仍卡/ANR：主线程全量 render + 节点不回收 + 协程泄漏（v1.0.6 自查补刀）
- **现象**：v1.0.5 已把无障碍遍历移出主线程（#6），但 Recall 页面在后台持续捕获时仍可能卡顿/无响应；
  长期使用、表里已累积成百上千条的设备上尤为明显。
- **根因**（v1.0.6 自查发现，三处相互独立）：
  1. **主线程全量 render（最致命）**：`RecallRepository.all()` 返回**整张** `recall` 表；无障碍服务后台
     每次 INSERT 都让 Room Flow 向主线程发射全表，`RecallActivity.render()` 同步 `removeAllViews()` +
     重建**全部** Row。长期累积后表有成百上千条，高频全量重建 → 主线程持续繁忙 → ANR。
     这比 #6 的遍历更致命，是 Recall 页面无响应的**真正主因**。
  2. **`AccessibilityNodeInfo` 从不 `recycle()`**：`walk()` 递归遍历产生大量节点（含 binder 引用），
     全部未回收；`rootInActiveWindow` 返回的 root 也未回收。后台高频遍历 → 节点对象堆积 → 内存泄漏、
     进程被系统回收。`this` 在 `walk` 递归返回后立即 `child.recycle()`。
  3. **`observe()` 协程泄漏**：搜索框每次 `afterTextChanged` 都 `lifecycleScope.launch { repeatOnLifecycle{…} }`
     新开协程，旧的 collect 不取消，多个流并发 `render()` → 竞态与重复重建。
- **修复**（v1.0.6）：
  1. DAO `all()` / `search()` 加 `LIMIT 300`，UI 只重建最近 300 条；
     `render()` 加内容签名（records + selection），**内容未变则跳过全量重建**，彻底消除
     「DB 高频发射 → 主线程反复 render」这一 ANR 主链路。
  2. `walk()` 递归返回后立即 `child.recycle()`；`captureNodeTree()` 在 `finally` 中 `root.recycle()`；
     用 `captureMutex` 串行化捕获，保证同一时刻只有一个 root 在内存。
  3. `observe()` 改为**单次启动**，基于 `queryFlow.flatMapLatest` 切换查询；搜索只更新 `queryFlow.value`，
     旧查询自动取消，不再泄漏协程。
- **验证方式**：长期使用的设备（表已累积数百条）打开 Recall，后台持续捕获 1–2 分钟，
  页面滚动/勾选流畅、不弹无响应；`adb shell dumpsys meminfo org.fcitx.fcitx5.android` 观察
  `Views`/`Activities` 不持续增长；连续输入搜索词，logcat 中不应出现多个 collectLatest 并发。
- **与本工程其他崩溃的区分**：与 #5（fitInsets NPE）、#6（服务主线程遍历）、#7（日志空文件）
  **机制均不同**，本条目补的是 #6 之后仍残留的「页面侧」主线程缺陷 + 资源泄漏。

## （后续崩溃/缺陷请按「现象 / 根因 / 修复 / 验证方式」格式继续追加，编号递增）

## 9. [BUG] Recall 列表「共 N 条记录」但下方不显示任何捕获词（v1.0.7）
- **现象**：Recall 页面顶部显示「共 47 条记录」，但中间记录区一片空白，看不到任何捕获到的词。
- **根因**：`RecallActivity.render()` 中每条记录行 `row` 被加入**垂直** `listContainer`，却用了
  `linearParams(1f)` 生成的 `LinearLayout.LayoutParams(0, WRAP_CONTENT, weight=1f)`。
  在**垂直** LinearLayout 里 `weight` 只作用于**高度轴**，宽度因此被设为字面 **0** →
  每一行宽度都是 0，整列完全不可见。Summary 文案由 `records` 计算得出所以照常显示，
  制造了「有数据但看不到」的假象。
- **修复**（v1.0.7）：行改用 `LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { topMargin=dp(6) }`
  （垂直列表子项必须显式 `MATCH_PARENT` 宽，绝不能用 `weight` 当宽度）。
  卡片样式圆角化（`GradientDrawable` 圆角背景），行内容改为「捕获词（粗体主文本）+ 时间 + 来源应用」，
  行尾加独立「删除」按钮；`observe()` 仅在 `onCreate` 启动一次（删掉清空/删除回调里重复调用的 `observe()`，
  改由 Room Flow 自动发射刷新），新增「反选」按钮。
- **验证方式**：打开 Recall，若该设备已捕获过文字，下方应立即出现卡片列表，每张卡片可见捕获词、时间、来源；
  点任意卡片的「删除」应即时移除该条并触发审计日志；「全选/全不选/反选」应正确改变所有卡片勾选态。

## 10. [BUILD] Fragment 里用 splitties DSL 必须显式 receiver，否则整块解析失败（v1.0.7）
- **现象**：`PinyinCustomPhraseFragment.onCreateView` 直接写 `val root = verticalLayout { ... }` 并大量使用
  `editText{}`、`styledColor`、`dp`、`lParams(width=,height=,weight=)`，编译报几十个
  `Unresolved reference: verticalLayout / editText / styledColor / dp` 与 `lParams None of the following candidates`。
- **根因**：splitties 的 `verticalLayout` / `editText` / `styledColor` / `dp` 都是**扩展函数**，需要
  `Context` / `View` / `Ui` 作为 receiver。`Fragment.onCreateView` 顶层**没有**这些 receiver，
  导致整块 lambda 上下文全部失效、所有扩展解析失败。另外 `lParams` 没有 `width/height/weight` 三参数命名重载，
  `android.R.attr.colorDimForeground` 在 framework 中并不存在（`styledColor` 引用它同样报错）。
- **修复**（v1.0.7）：用 `val ctx = requireContext(); val root = ctx.verticalLayout { ... }` 显式给 receiver，
  内部以 `ctx.editText{}` / `ctx.styledColor(...)` 调用；`lParams` 改为位置参数 + 尾 lambda 设字段
  `lParams(matchParent, 0) { weight = 1f }`；提示色用 `android.R.attr.textColorHint`。
  注意：重写文件时务必保留原文件的 `import splitties.resources.styledColor` 与 `import splitties.dimensions.dp`，
  否则即使 receiver 对了也会 `Unresolved reference`。
- **隐蔽陷阱**：增量构建时若旧 class 命中缓存，第一次 `assembleRelease` 可能**跳过编译这些文件**并以旧字节码产出
  APK（表现为「构建成功但功能没生效」）。改了 build-logic 的 `Versions.kt` 后，必须强制重编 build-logic
  （`:build-logic:convention:compileKotlin --rerun-tasks`）并确认 app 的 `compileReleaseKotlin` 真正重编、
  APK 的 `versionCode` 随之变化，才能确保改动生效。
- **验证方式**：`aapt2 dump badging Universal.apk | grep versionCode` 应等于 `baseVersionCode*10 + abiId`；
  安装后「管理自定义词组」页面顶部应有搜索框与「删除未锁定词组」按钮，每行右侧有锁定图标。

## 11. [CRASH] MaterialButton 在非 MaterialComponents 主题下必崩（v1.0.8，真实日志实锤）
- **现象**：打开「管理自定义词组」页面立即闪退。日志（HUAWEI GLA-AL00，API 31，versionCode 142）：
  `java.lang.IllegalArgumentException: The style on this component requires your app theme to be
  Theme.MaterialComponents (or a descendant).`
  `at com.google.android.material.internal.ThemeEnforcement.checkTheme(ThemeEnforcement.java:249)`
  `at com.google.android.material.button.MaterialButton.<init>(MaterialButton.java:300)`
  `at ...PinyinCustomPhraseFragment.onCreateView(PinyinCustomPhraseFragment.kt:216)`
- **根因（双层实证）**：
  1. 宿主 `MainActivity`（承载设置页导航）在 Manifest 无独立主题 → 继承 `Theme.FcitxAppTheme`，
     parent 为 **`Theme.AppCompat.Light.NoActionBar`**（night 为 `Theme.AppCompat.DayNight.NoActionBar`），
     **不是** MaterialComponents/Material3 主题。
  2. 从 Material 1.14.0 AAR 的 `res/values/values.xml` 实证：
     - `Widget.MaterialComponents.Button` → `enforceMaterialTheme = true`（MaterialButton 默认样式）→ 在 AppCompat 主题下构造即抛异常；
     - `Widget.Design.TextInputLayout`（TextInputLayout 的**默认**样式）→ `enforceMaterialTheme = false` → 安全。
     这解释了为什么上游 `materialTextInput`（TextInputLayout）在同主题下能正常工作，而 MaterialButton 不能。
- **修复**（v1.0.8）：`MaterialButton(ctx)` → `AppCompatButton(ctx)`（宿主是 AppCompatActivity，主题一致且无强制检查）。
- **主题速查表**（本工程）：
  - `Theme.FcitxAppTheme` = AppCompat 主题 → **禁用** MaterialButton / SwitchMaterial / 带 Material 默认样式的组件；AppCompatButton / 普通控件安全。
  - `Theme.MbmActivity` = Material3.DayNight.NoActionBar → MaterialButton 安全（v1.0.7 日志中 RecallActivity 在此主题下正常进出，实证）。
- **回归风险**：低。AppCompatButton 在 AppCompat 主题下为原生观感按钮，与设置页一致；无样式回退问题。
- **验证方式**：设置 → 管理自定义词组，页面应正常打开，顶部有搜索框、「删除未锁定词组」按钮、列表正常渲染。

## 12. [PRIVACY] 云备份/换机迁移会带走 Recall 捕获库（v1.0.8）
- **现象/风险**：`android:allowBackup="true"` + 备份规则只排除了 `file/usr`、`descriptor.json` 等，
  未排除 Room 数据库与 SharedPreferences。`recall.db`（无障碍捕获的用户全部输入文字）、
  `recall_audit.log`（导入去向等操作记录）、`mbm_phrase_lock.xml` 会被 cloud-backup / device-transfer 带走。
- **根因**：备份规则 `full_backup_content.xml` / `data_extraction_rules.xml` 只按 `file` 域排除，
  漏掉 `database` / `sharedpref` 域。
- **修复**（v1.0.8）：两个备份规则文件中追加
  `<exclude domain="database" path="recall.db"/>`、`<exclude domain="sharedpref" path="mbm_phrase_lock.xml"/>`、
  `<exclude domain="file" path="recall_audit.log"/>`。
- **回归风险**：低。仅影响备份内容，不影响运行；用户手动导出/导入不受影响。
- **验证方式**：`aapt2 dump xmltree Universal.apk --file res/xml/full_backup_content.xml` 应包含三条新 exclude。

## 13. [UI] 密码生成器界面「显示不正常」——垂直布局子项用 weight 当宽度导致宽度 0（v1.0.9）
- **现象（用户反馈「随机密码的界面显示不正常」）**：打开密码生成器后，页面只剩「MBM 密码生成器」标题与几个小标题，
  所有开关行（大写/小写/数字/符号/排除规则/强度…）、所有文本框（额外排除字符/自定义符号）、以及生成后的密码结果卡片
  **全部不可见**；仅标题与分隔线可见。
- **根因（与 v1.0.7 Recall 列表 #9 同源）**：`PasswordGeneratorActivity` 的 `root`/`outContainer` 是**垂直** `LinearLayout`，
  但 `addSwitch`、`addTextField`、结果 `render()` 的 row 都用 `linearParams(1f)` =
  `LinearLayout.LayoutParams(0, WRAP_CONTENT, weight=1f)` 作为这些子项的布局参数。
  在**垂直** LinearLayout 中 `weight` 只作用于**高度轴**，宽度被赋为字面 **0** → 子项整行宽度 0，完全不可见。
  （水平布局里 `linearParams(1f)` 占宽是对的；误用发生在垂直容器的**顶层子项**。）
- **修复**（v1.0.9）：
  1. 所有垂直容器顶层子项的布局参数改为 `LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)`
     （`addSwitch`/`addNumberField` 的 row、`addTextField` 的 `TextInputLayout`、`render()` 的结果卡片 row）。
  2. 保留水平 row 内弹性子项的 `linearParams(1f)`（标签、密码文本、按钮区为正确用法）。
  3. `roundedBox()` 原实现取 `R.drawable.ic_launcher_background`（adaptive icon 背景 drawable）再用 `setTint` 上色做卡片背景，
     在不同密度/主题下显示不可靠；改为直接 `GradientDrawable().apply { setColor(COLOR_PANEL); cornerRadius = dp(10) }` 圆角卡片。
  4. 移除未使用的 `ContextCompat` import。
- **注**：该 Activity 在 Manifest 已声明 `Theme.MbmActivity`（Material3 主题），Material 组件不会像 E-01 那样崩溃，
  所以本次是纯布局 bug 而非主题崩溃；Material 组件保留不动。
- **回归风险**：低。仅改布局参数与卡片背景 drawable，逻辑未动；`Actions` 按钮区、`render` 内密码文本仍用 `weight` 占宽，行为不变。
- **验证方式**：打开密码生成器 → 应看到完整的开关列表、文本框、生成后每行密码卡片（含序号/密码/熵/强度标签/复制按钮）；
  `aapt2` 无需。建议真机确认：关闭某类字符后其「最少个数」输入框应置灰且宽度正常。

## 14. [UI] 「管理自定义词组」列表、规则导入/智能词库列表行宽 0（同源，v1.0.10）
- **现象（同源自查发现）**：`RuleImportActivity`（规则与词库导入）与 `SmartDictActivity`（离线智能词库）两页，
  状态栏提示「已识别 N 项 / 共 N 项词库」，但**列表行（文件名+格式、词库名+大小）整行不可见**，
  与 v1.0.7 Recall 列表 #9、v1.0.9 密码界面 #13 完全一致的表现。
- **根因（与 #9 / #13 同源）**：两者 `render()` 里每个列表项是一个**水平** `LinearLayout`（`row`），
  但它被 `addView` 进**垂直** `listContainer`；`row` 的布局参数却写成
  `linearParams(1f)` = `LinearLayout.LayoutParams(0, WRAP_CONTENT, weight=1f)`。
  在**垂直**父容器里 `weight` 只作用于**高度轴**，宽度被赋为字面 **0** → `row` 整行宽度 0，不可见。
  （`linearParams` helper 本身没问题：在**水平**容器里它占宽是对的，例如按钮区、行内 `textHost` / 标签 / 密码文本。）
- **排查范围（穷尽）**：全仓 `LinearLayout.LayoutParams(` 调用 + 每个 `linearParams(...)` 调用点的父容器方向——
  - `RecallActivity`、`PasswordGeneratorActivity`：列表行已用 `MATCH_PARENT`（#9/#13 修复过），无残留。
  - `PinyinCustomPhraseFragment`：搜索结果用普通 `TextView`（无 width=0 陷阱），无同类问题。
  - 仅 `RuleImportActivity:175` 与 `SmartDictActivity:148` 命中：其 `row` 是垂直 `listContainer` 的子项却用 `linearParams(1f)`。
- **修复**（v1.0.10）：两处 `row.layoutParams` 改为
  `LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)` 并保留原 `topMargin`（`dp(8)` / `dp(6)`）；
  `linearParams` helper 保留（水平容器内仍正确使用）。
- **回归风险**：低。仅改列表行布局参数，不影响选中状态、删除、安装等逻辑；按钮区/行内弹性子项仍用 `linearParams` 占宽。
- **验证方式**：规则导入页选文件后应看到逐项文件名+来源卡片；智能词库页应看到逐项词库名+大小卡片；均可勾选/删除。
  建议真机确认两页列表可见且可操作。

## 15. [新增] 离线手写模块 :lib:handwritten（Ismantic/Handwritten，v1.0.10）
- **需求**：输入法需具备离线手写输入（主流输入法明确有）。仓库此前无任何手写能力（plugin/ 无 handwriting，全仓搜不到 handwrit/手写）。
- **选型**：集成 [Ismantic/Handwritten](https://github.com/Ismantic/Handwritten)（Apache-2.0）——NCNN INT8 量化 MobileNetV2，识别 GB2312 一级 3755 字，
  top1≈95.47% / top10≈99.58%，模型 ~4.1MB，全程离线（APK 无 INTERNET 权限）。
- **模块结构** `lib/handwritten/`：
  - `src/main/cpp/`：`hccr_jni.cc`（JNI 推理，已改为 `num_threads` 用满全部核心）、`preprocess.{h,c}`（灰度→64×64 归一化）、`CMakeLists.txt`
  - `src/main/cpp/ncnn/<abi>/`：ncnn-20250503-android 预编译静态库（arm64-v8a/armeabi-v7a/x86/x86_64）
  - `src/main/assets/`：`model.ncnn.{param,bin}` + `charset.json`（sha256 已与 HF 元数据核对一致）
  - `src/main/java/com/shiyu/handwritten/runtime/HCCRRecognizer.java`：上游 Java 封装
  - `src/main/java/org/fcitx/fcitx5/android/lib/handwritten/HandwrittenRecognizer.kt`：本仓库 Kotlin 封装（默认 top-10 候选）
  - UI：`ui/main/HandwritingActivity.kt` + `HandwritingView.kt`，`MainFragment` MBM 区新增「离线手写」入口
- **超高精度策略**：用户要求精度优先、不计耗电 → JNI 内 `net.opt.num_threads = max(4, hardware_concurrency())` 用满核心；返回 top-10 候选；`use_fp16_arithmetic=false`、`use_vulkan_compute=false` 保证 CPU 确定性最高精度。
- **关键构建坑（已踩并解决）**：
  1. `ndk { abiFilters += ... }` 必须放在 `defaultConfig` 内，顶层 `android.ndk` 不存在（Gradle 报 Unresolved reference 'ndk'）。
  2. ncnn 头文件在 `include/ncnn/`，`#include "net.h"` 要求 include 路径指向 `ncnn/<abi>/include/ncnn`，否则 `net.h file not found`。
  3. `project(hccr CXX)` 只启用 C++，导致 `preprocess.c` 不被编译 → 链接报 `undefined symbol: hccr_preprocess`；改为 `project(hccr C CXX)`。
  4. ncnn 静态库依赖 OpenMP：用 `-Wl,--whole-archive ${NDK}/.../libomp.a -Wl,--no-whole-archive` 静态链入，运行期无需 `libomp.so`；编译/链接均加 `-fopenmp`。
  5. 版本号仍只升 `baseVersionCode`（17），`baseVersionName` 不变，避免 `project(fcitx5-android VERSION)` 触发 4 ABI 全量原生重编 OOM。
- **合并重复入口**：`SmartDictActivity` 内原「导入规则/词库」按钮与 `MainFragment` 顶部「规则与词库」完全重复（都跳 `RuleImportActivity`），已移除前者，入口归一。
- **回归风险**：低。新增独立模块 + Activity，不改既有输入逻辑；选字经 `FcitxDaemon.getFirstConnectionOrNull()?.sendKey(ch)` 上屏（与键盘同一通道）。
- **验证方式**：`MainFragment` → 离线手写 → 白板写单字 → 显示 top-10 候选 → 点选上屏到当前输入框（需先启用 MBM 输入法）。
  构建产物 AAR 已核验含 `jni/<abi>/libhccr.so`×4 + `assets/model.ncnn.*` + `charset.json`。

## 16. [新增] 超高精度手写：13 路 TTA 集成 + 键盘内手写面板（v1.0.10）
- **需求**：用户明确"超高精度识别，不要在乎耗电"。据此把手写从"能识别"升级为"精度优先"。
- **TTA（test-time augmentation）集成**（`lib/handwritten/src/main/cpp/hccr_jni.cc`）：
  - 对 64×64 归一化输入生成 **13 路仿射变体**：1 原图 + 4 轴向平移(±2px) + 4 对角平移(±2,±2) + 2 缩放(0.92/1.08) + 2 微旋转(±4°)。
  - 每路独立前向 + softmax，最后按成功路数取**概率平均**再取 top-10（不是简单投票，平均概率更稳）。
  - 仿射采样用双线性，画布外补 0（归一化后 0 = 白底），与预处理口径一致。
  - 变体按 `min(cores, 13)` 分摊到多线程并行，各自累加后归约；`create_extractor()` 加了互斥保护。
- **精度优先的 Option 组合**：`use_vulkan_compute=false`、`use_fp16_arithmetic/storage/packed=false`、`use_bf16_storage=false`、
  `use_winograd_convolution=false`（winograd 会引入数值误差）、`use_sgemm_convolution=true`、`num_threads = max(4, CPU 核心数)`。
- **键盘内手写面板** `input/handwriting/HandwritingWindow.kt`（ExtendedInputWindow）：
  - 入口在键盘**工具栏**（`ButtonsBarUi.handwritingButton`，铅笔图标），进入后标题栏返回键回键盘。
  - 选字/空格/换行/退格全部走键盘同一条 `CommonKeyActionListener` 通道
    （`KeyAction.CommitAction` / `KeyAction.SymAction`），与 fcitx 状态一致，不会残留 preedit。
  - 每次抬笔即识别；识别在 `Dispatchers.Default`，识别前用 `HandwritingView.snapshot()` 拷贝位图，
    避免 UI 线程继续绘制时与后台线程争用同一个非线程安全 Bitmap。
- **设置页 `HandwritingActivity` 只做取字/复制**：Activity 会抢走输入框焦点，此时 IME 的 InputConnection 已失效，
  强行 commit 无处可去。故 Activity 内点选候选只写入剪贴板并 toast，真正边打字边手写请用键盘工具栏入口。
- **踩坑记录**：
  1. `ncnn::Extractor` **没有默认构造函数**，不能先声明后赋值；必须 `ncnn::Extractor ex = [session]{ lock; return net.create_extractor(); }();`。
  2. `getFirstConnectionOrNull()` 返回的是 `FcitxConnection`（不是 `FcitxAPI`），提交要走
     `connection.runIfReady { sendKey(...) }`，直接 `.sendKey()` 会 Unresolved reference。
  3. splitties DSL 的 `matchParent/wrapContent/lParams/add` 都要求特定接收者类型；脱离 DSL 作用域直接写
     `.lParams(width = matchParent)` 会报 receiver type mismatch。手写 UI 全部改用原生 View API，规避此类问题。
- **回归风险**：低。新增独立窗口 + 独立模块，不动既有输入逻辑；模型加载失败时 `ensureRecognizer()` 返回 null，
  候选区为空且不崩溃。

## 17. [构建] 4 ABI 分包打包 OOM：packageRelease 需要 6g 堆（v1.0.10）
- **现象**：Kotlin/原生全部编译通过，卡在 `:app:packageRelease`：
  `A failure occurred while executing PackageAndroidArtifact$IncrementalSplitterRunnable`
  → `Caused by: java.lang.OutOfMemoryError: Java heap space`（两个分包 worker 同时失败）。
- **根因**：`NativeBaseConventionPlugin` 开了 `splits.abi { isEnable = true; isUniversalApk = true }`，
  即一次构建要产出 4 个 ABI 分包 + 1 个 Universal 共 5 个 APK，切分阶段同时持有多份 dex/native/asset 缓冲区。
  加上手写模块后（4 份 libhccr.so + 4.3MB 模型资产）突破了 1.5g 堆上限。
- **解法**（已验证）：构建时显式给守护进程/分包 worker 更大堆：
  `./gradlew :app:assembleRelease -Dorg.gradle.jvmargs=-Xmx6144m`（6g 实测通过，8g 更稳）。
  不要试图靠改 `gradle.properties` 默认值去省事——CI/他人机器上同样会踩。
- **不要**为了绕开 OOM 去关 `splits.abi`：MBM 的交付要求就是「全 ABI + Universal 通用包」。
- 已同步写入 [构建说明书.txt](../构建说明书.txt) 的 5.1 节与常见问题 Q5。

## 18. [真机反馈] v1.0.11 四项修复（手写无反馈 / Recall 重复 / 词组搜索 / 智能组词闭环）
- **手写面板"等了半天什么反应没有"**：
  - 根因排查：模型层名 `in0` / `out0` 已核对无误（`model.ncnn.param` 末行 `InnerProduct linear_36 ... out0`）；
    真凶是**失败被静默吞掉** —— `ensureRecognizer()` 返回 null 时 `recognize()` 直接返回空列表，
    而空列表又走 `showEmptyHint()` 显示"请在此书写"，用户完全看不出是引擎没加载起来。
  - 修复：新增状态行（待书写 / 识别中… / **识别失败：具体异常 message**，红色），
    全过程 `Timber` 打点（`MBM-HW: engine init failed` / `bitmap=… candidates=…`）。
  - 另一处真凶嫌疑：**ncnn 并发 extractor + lightmode**。TTA 的 13 路前向并发跑，
    lightmode（默认 true）下权重会在最后一次使用后释放，并发 extractor 互相踩权重会导致
    结果错乱甚至崩溃（表现就是"没反应"/键盘消失）。已 `net.opt.lightmode = false` 让权重常驻
    （模型仅 4.3MB），并发只读共享即安全。
- **Recall 同一内容被反复记录**：`RecallRepository.record()` 原为「同包名 + 同文本 + **30 秒窗口**」去重，
  窗口一过同屏内容又入库。改为**全历史去重**（`dao.duplicates(pkg, text, 0L)`），
  同一应用的相同内容只保留首次捕获。
- **管理自定义词组搜索"无匹配结果"**：
  - 原实现匹配 `entry.serialize()` 整行（`key,order=escapedValue`），含空格/引号/换行的 value
    会被 `FcitxUtils.escapeForValue` 两端加引号并转义换行，导致搜词组原文搜不到。
  - 改为三维度匹配：**键 / 词组原文 / 词组拼音首字母**（`wjjq` → "玩机技巧"）。
- **Recall→自定义词组闭环此前并不存在**（之前只有"导入输入法剪贴板"）。新增「导出为词组」：
  - 词组提炼：按行取最大连续汉字段，2~4 字直接成词，更长按 4 字窗口切分（末尾余 2~3 字另成词），
    单次上限 200 条，**词组不带空格**；
  - 键 = 拼音首字母（"玩机技巧" → `wjjq`），顺序固定 **2**；
  - 拼音来源：`android.icu.text.Transliterator("Han-Latin; Latin-ASCII")`（系统 ICU，离线、零新依赖），
    封装在 `utils/PinyinUtils.kt`，结果带缓存。
- **fcitx5 pinyin customphrase 的两个关键机制**（决定"输入体验"）：
  1. 查词用的是**光标前的原始输入串**（`pinyin.cpp`：
     `pyBeforeCursor = context.userInput().substr(selectedLength, pyLength)`，
     `pyLength = cursor - selectedLength`），再 `index_.exactMatchSearch(key)` 精确匹配。
     所以 key 可以是**任意字符串**（`wjjq` 这种非音节串照样命中），
     但必须是**逐字完全一致**——`wanjijiqiao` 不会命中 `wjjq`。
  2. `CandidateOrder{result.order() - 1, ...}` → **order=2 就是候选第 2 位**（order=1 最前）；
     `order <= 0` 不进候选（Kotlin 侧用负号表示"禁用"），`order == 0` 写入时被直接丢弃。
- **回归风险**：低。Recall 去重只改查询参数；词组导入走既有 `CustomPhraseManager.save()` +
  `reloadPinyinCustomPhrase()` 热重载；手写改动限于状态显示与 ncnn Option。

## 19. [真机反馈] v1.0.12：词组键模糊互通 + 词组提炼截断修复
- **「快手极速版」被导入成「快手极速」——不是识别错误，是切词算法截断**：
  `extractPhrases()` 原逻辑是「>4 字的汉字段按 4 字窗口切分，尾部 <2 字丢弃」，
  5 字的「快手极速版」→ 窗口取「快手极速」+ 尾部「版」（1 字 < MIN_PHRASE_LEN）被丢。
  已改为 **≤6 字的连续汉字段整段成词**（应用名/短词场景全覆盖），只有 >6 字的长句才按 4 字窗口切分。
- **词组键要求互相通用（wjjq ↔ wanjijiqiao ↔ wanjjq ↔ wanjjiqiao）**：
  1. **引擎侧**：`CustomPhraseDict::lookup()` 只有 `exactMatchSearch`，新增
     `lookupWithFuzzy()`（非 const，结果放 `fuzzyCache_`）：精确未命中时才启用，
     条件为 **查询 ≥3 且纯小写 + 键与查询首字母一致 + 键长 ≤ 查询长**，
     且 **键是查询的子序列 或 键的字符多重集被查询覆盖**，上限 8 条并按 order 排序。
     `pinyin.cpp` 的查词调用点已切到 `lookupWithFuzzy()`。
     - 子序列覆盖：`wjjq` ⊆ `wanjijiqiao` / ⊆ `wanjjq`?（w,a,n,j,j,q → w j j q ✓）/ ⊆ `wanjjiqiao` ✓
     - 多重集覆盖兜底乱序：漏字母、多字母的输入照样命中。
     - 为什么必须加"首字母一致 + 键长≥3 + 上限 8"：否则子序列匹配会大量误命中
       （如输入 "nihao" 命中所有含 n/h/a/o 顺序的短键）。
  2. **导入侧（Kotlin）**：每个词组同时写 **首字母键 `wjjq`）** 与 **全拼键 `wanjijiqiao`** 两条
     （`PinyinUtils.fullPinyin()` 取全拼去空格）。这样全拼是**精确**命中路径，
     漏字母/多字母走上面的模糊路径，两条都覆盖。
     - 代价：管理自定义词组里同一词组会显示两行（不同键）。这是有意为之——
       只靠模糊回退挡不住 `kuaijiasuban`（j 出现在第二个 s 之前，子序列不成立）。
- **性能**：模糊仅在精确未命中时触发；`index_.foreach` 线性扫键 + O(|k|+|q|) 判断，
  键数几千时每次按键仍在微秒级；且 `kMaxFuzzy=8` 命中即停止遍历。
- **回归风险**：中低。改动落在 vendored 的 fcitx5-chinese-addons 内，但只在
  「精确查词 miss」这条分支上增加候选，不改变原有精确命中行为、不改排序语义（仍用 order-1）。

## 20. [CRASH] 离线手写永远加载失败：libhccr.so 的 NEEDED: libomp.so 未随 APK 打包（v1.0.13）
- **现象（v1.0.12 真机反馈）**：打开键盘内离线手写面板 / 设置页「离线手写」，状态行一直显示「识别失败 /
  加载失败」，永远无法识别。`HandwritingWindow.ensureRecognizer()` 捕获到的异常是 native 库加载失败。
- **根因**：`lib/handwritten/src/main/cpp/CMakeLists.txt` 用 `-fopenmp` 编译并在链接期以
  `--whole-archive ${NDK}/libomp.a` 把 OpenMP 运行时**静态链入** `libhccr.so`（推理并行用满核心）。
  但 clang 驱动在链接期会因 `-fopenmp` 自动追加共享库 `-lomp`，使 `libhccr.so` 带
  `NEEDED: libomp.so`。Android Gradle **默认不会**打包该 .so（只有 `libc++_shared.so` 等会被默认带出），
  于是 `System.loadLibrary("hccr")` → `dlopen` 在动态链接阶段因找不到 `libomp.so` 直接失败 →
  手写引擎永远加载不出来（与 v1.0.12 同机其他功能无关，唯独手写废掉）。
  实证：`llvm-readelf -d libhccr.so` 四个 ABI 均含 `NEEDED: libomp.so`；解包 v1.0.12 APK 确认
  `lib/<abi>/` 下只有 `libhccr.so` / `libc++_shared.so`，**没有 `libomp.so`**。
- **修复**（v1.0.13）：把 NDK 的 `libomp.so`（同一 NDK r28 clang 19，与静态副本同源）按 ABI 放进
  `lib/handwritten/src/main/jniLibs/<abi>/libomp.so`，随模块打包进 APK。
  动态链接器据此满足 `NEEDED`，`dlopen` 成功；运行期实际 OpenMP 运行时来自 `libhccr.so` 内
  **静态链接**的副本（`__kmpc_fork_call` / `omp_get_max_threads` 等符号在 .so 内为 `T` 定义），
  单一运行时、无双初始化。CMake 注释已写明该约束。
- **为什么不再强行去 NEEDED**：尝试过 `-Xclang -fopenmp`（仅对 cc1 生效、不触发驱动 `-lomp`），
  但本工程 clang 驱动仍会记录 `NEEDED: libomp.so`；最稳、最可读的做法就是「随包带出 libomp.so」，
  与 `libc++_shared.so` 的处理完全一致。
- **验证方式**：`unzip -l Universal.apk | grep libomp.so` 应出现 4 个 ABI 的 `libomp.so`；
  真机打开离线手写 → 写单字 → 状态行不再「加载失败」，top-10 候选正常；`llvm-readelf -d libhccr.so` 仍可见
  `NEEDED: libomp.so`（这是预期，因为 .so 现在已在包内）。

## 21. [CRASH] 剪贴板面板点右上角「清空」垃圾桶崩溃整个输入法（v1.0.13）
- **现象（v1.0.12 真机反馈）**：剪贴板面板右上角垃圾桶（清空全部）一点 → 输入法直接闪退（"输入法已停止"）。
- **根因**：清空确认用的是 `PopupMenu(context, ui.deleteAllButton).show()`。该 `PopupMenu` 在
  **输入法(IME)进程**内以 `TYPE_APPLICATION` 弹出，锚点是 IME 键盘视图（窗口令牌为 `TYPE_INPUT_METHOD`）。
  在部分 OEM（实测设备 Honor PTP-AN70 / Android 15 / API 37，EMUI 系）上，
  这类 `PopupMenu`/`ListPopupWindow` 的 `show()` 会抛 `WindowManager.BadTokenException`
  （窗口令牌非法）。而这段逻辑跑在 `service.lifecycleScope.launch { ... }` 里且**没有异常处理器**，
  未捕获的异常直接冒泡到进程级 `uncaughtExceptionHandler` → **整个输入法进程被杀**。
  （同源路径：候选长按菜单 `BaseInputView`、剪贴板条目长按菜单 `ClipboardAdapter`、
  状态区菜单 `StatusAreaWindow` 都用 `PopupMenu`，同一风险。）
- **修复**（v1.0.13）：
  1. `ClipboardWindow.promptDeleteAll` 的 `popup.show()` 包 try/catch；抛异常时回退到
     `service.showDialog(AlertDialog)` 托管的 IME 安全确认框（`TYPE_APPLICATION_ATTACHED_DIALOG`，
     令牌用 `decorView.windowToken`，全机型稳定）。
  2. 真正的删除与「撤销 Snackbar」移到 `deleteAllConfirmed()`，整段包 try/catch（Timber）；
     `showUndoSnackbar` 的 `Snackbar.make` 也包 try/catch，避免 Material 组件构造异常再炸进程。
  3. `ClipboardAdapter` / `BaseInputView` / `StatusAreaWindow` 三处 `PopupMenu.show()` 同样包 try/catch，
     失败仅记日志、不再弹菜单，绝不杀进程。
- **验证方式**：真机点剪贴板右上角垃圾桶 → 应弹出确认框（或系统稳定地弹出菜单），点「确定」清空并出现撤销条；
  输入法不应闪退。

## 22. [CRASH] 中英切换键长按崩溃整个输入法（v1.0.13）
- **现象（v1.0.12 真机反馈）**：键盘「中英切换」键**长按** → 输入法直接闪退。
- **根因**：长按触发 `KeyAction.ShowInputMethodPickerAction` → `CommonKeyActionListener.showInputMethodPicker()`
  → `service.showDialog(InputMethodPickerDialog.build(...))`。该自定义选择对话框同样以
  `TYPE_APPLICATION_ATTACHED_DIALOG` + `decorView.windowToken` 弹出；在严格 OEM/Android 15 上
  `dialog.show()` 可能因窗口令牌问题抛 `BadTokenException`，且整段在
  `fcitx.launchOnReady { service.lifecycleScope.launch { ... } }` 内**无异常处理器** → 进程被杀。
- **修复**（v1.0.13）：`showInputMethodPicker()` 包 try/catch；自定义对话框无法显示时，
  回退到系统输入法选择器 `InputMethodUtil.showPicker()`（`inputMethodManager.showInputMethodPicker()`，
  始终安全、无窗口令牌问题），保证「长按切输入法」永远可用、不崩。
  另在 `FcitxInputMethodService.showDialog()` 本体包了一层 try/catch（Timber）——这是所有 IME 对话框的
  **统一兜底**（输入法选择、AddMoreInputMethodsPrompt、枚举切换等全部走它），任一 `show()` 抛错都只记录、不杀进程。
- **验证方式**：真机长按中英切换键 → 弹出输入法选择（自定义或系统），不闪退；正常切换中/英输入法。

## 23. [HARDEN] IME 进程内「弹窗/对话框」统一加异常保护（同源审计，v1.0.13）
- **背景**：#21/#22 暴露同一类缺陷——IME 进程内展示 UI（PopupMenu / AlertDialog）若因窗口令牌/OEM 适配
  抛 `BadTokenException` / `IllegalArgumentException`，而调用又在 `lifecycleScope.launch {}` 里无异常处理器，
  就会变成**进程级崩溃**（"输入法已停止"），而非局部失败。
- **审计范围（已加固）**：`ClipboardWindow`（垃圾桶确认 + 撤销 Snackbar）、`ClipboardAdapter`、
  `BaseInputView`（候选菜单）、`StatusAreaWindow`（状态区菜单）、`CommonKeyActionListener`
  （输入法选择器）、`FcitxInputMethodService.showDialog`（集中兜底）。
- **原则**：IME 内的交互失败**必须局部化**（try/catch + Timber + 安全回退），绝不允许未捕获异常冒泡杀进程。
  后续任何在 IME 进程 `launch {}` 中 `show()` 弹窗/对话框的代码，都应沿用此模式。

## 24. [OCR] 检测(det)模型输入归一化写错 → OCR 基本不可用（功能静默失效，v1.0.14）
- **背景**：Recall 离线 OCR 用 PP-OCRv4（det 检测 + cls 方向 + rec 识别），三个模型预处理不同。
  原 `OcrEngine.chwBgrNormalized()` 对**所有**模型都用 `(x/255 - 0.5)/0.5`，被 `detect()`（det）
  与 `shouldRotate()`（cls）共用。但 `mean=std=0.5` 只适用于 **rec/clr**。
- **根因**：PP-OCRv4 **det** 官方预处理是 `(x/255 - [0.485,0.456,0.406]) / [0.229,0.224,0.225]`
  （ImageNet 统计量，PaddleOCR 官方 `inference.yml` / gitee config 一致）；**rec/clr** 才是 `0.5/0.5`。
  错把 det 喂成 `0.5/0.5`，使检测概率图整体偏移，DBPostProcess 阈值/连通域找不到文字框 →
  识别阶段无输入 → 整条 OCR 静默无输出（无任何异常，仅 `lastFailure()` 为空、召回为空）。
- **多因素确认**：
  1. 代码 `detect()` 调 `chwBgrNormalized(scaled)` → 应用 `(x/255-0.5)/0.5`；
  2. 官方文档（PaddleOCR gitee `ch_PP-OCRv4_det_cml.yml`、HF `PP-OCRv4_server_det/inference.yml`、
     CSDN 离线部署文、HF `paddleocr-v4-det-onnx` 用法示例）均写明 det `mean=[0.485,0.456,0.406]`
     `std=[0.229,0.224,0.225]`；同批资料指出 rec/clr 用 `0.5/0.5`；
  3. 三个 ONNX 模型首节点均为 BatchNorm/Conv、输入 `x` 直接进网络，未融合预处理 → 必须调用方喂正确尺度。
- **修复**（v1.0.14）：`chwBgrNormalized` 参数化为 `mean/std`（默认 `0.5/0.5`，保持 rec/clr 不变）；
  `detect()` 显式传 `DET_MEAN=[0.485,0.456,0.406]` / `DET_STD=[0.229,0.224,0.225]`。
- **验证方式**：取含中文的截图走 Recall OCR，修复前应几乎无识别结果，修复后稳定出文字行；
  对照 `OcrEngine.lastFailure()` 为空且 `isReady()` 为 true（模型本身加载正常，纯预处理尺度问题）。
- **原则**：多模型管线每个模型必须按各自官方预处理参数喂数据，不能共用一个归一化函数；
  改预处理前先 `python` 读 ONNX 首节点确认未融合归一化。

## 25. [OCR] 检测/识别/方向三模型通道顺序写反（RGB 当 BGR 喂入），OCR 仍基本不可用（v1.0.15）
- **背景**：v1.0.14 修了 det 归一化均值，但 `OcrEngine` 把张量按 **RGB** 排布喂入三个 PP-OCRv4 模型，
  而官方模型在 **BGR**（cv2.imread）下训练。v1.0.14 只改了均值、没改通道，det 概率图依旧整体偏移。
- **根因**：PP-OCRv4（det/rec/cls）预处理 `DecodeImage: img_mode: BGR`，张量 `plane0=图B plane1=图G
  plane2=图R`；原代码 `plane0=图像R`（且套 `mean[0]=0.485`，本应给 Blue）、`plane2=图像B`。等于把 R 当
  B 归一化，R/B 连同统计量整体错位 → 检测概率图偏移、文字框找不到 → OCR 静默无输出。
- **多因素确认**：
  1. PaddlePaddle gitee `configs/det/ch_PP-OCRv4/ch_PP-OCRv4_det_cml.yml`、`HF PP-OCRv4_mobile_det/
     inference.yml` 均写明 `DecodeImage: img_mode: BGR`；CSDN 离线部署文明确"官方训练时图像是 BGR，不是 RGB"；
  2. 本项目引用作参考的 RapidOCR 同样是 `cv2.imread` 后 `transpose(2,0,1)` 保持 BGR，未转 RGB；
  3. 实测三个 ONNX 末节点为 `Sigmoid`(det)/`Softmax`(rec/cls)、输入无 `Transpose/CvtColor` → 通道顺序
     必须调用方正确供给；`det` 输出已是 [0,1] 概率，故 `DET_THRESH/BOX_THRESH` 直接阈值用法正确（无需额外 sigmoid）；
  4. 旧代码注释自相矛盾（写"张量布局 BGR"却把图像 R 写入 `plane0` 并套 `mean[0]=0.485`），证实作者误把 RGB 当 BGR。
- **修复**（v1.0.15）：`chwBgrNormalized` 与 rec 内联归一化均改为真正 BGR（`plane0=图B plane1=图G
  plane2=图R`），det 的 `DET_MEAN/DET_STD` 现在正确绑定到 `[B,G,R]`；rec/cls 维持 `0.5/0.5`（与顺序无关）。
- **验证**：取含中文的截图走 Recall OCR，修复前应几乎无识别结果，修复后稳定出文字行；另 `rec` 输出类别数
  6625 = 字典 6623 + 2（blank+space），`charOf` 映射正确，无需改动。
- **原则**：多通道模型输入必须同时核对**通道顺序（BGR/RGB）与归一化参数**，二者任一写错都会让检测失效；
  改预处理前先确认模型训练时的 `img_mode` 与末节点激活（是否含 sigmoid）。

## 26. [Recall] 跨协程 Mutex 误用，OCR 兜底路径第一张截图后永久失效（v1.0.15）
- **背景**：`RecallService` 用 `kotlinx.coroutines.sync.Mutex` 做"同一时刻仅一张截图在 OCR"的串行化。
- **根因**：`ocrMutex.tryLock()` 在 `captureNodeTree` 协程（Dispatchers.Default）加锁，而 `ocrMutex.unlock()`
  写在 `takeScreenshot` 回调里 `scope.launch { ... finally { unlock() } }` 新起的**第二个协程**中。
  kotlinx `Mutex.unlock()` 必须由**加锁的那个协程**调用，否则抛 `IllegalStateException("This mutex is
  locked by ...")`；`finally` 里的 `unlock()` 抛异常前并未真正释放锁 → 该 `Mutex` 永久卡死，
  此后所有 `tryLock()` 返回 false → OCR 兜底路径在第一张截图后彻底死亡。
- **多因素确认**：
  1. 代码结构：`tryLock` 在协程 A（captureNodeTree→captureWithOcr），`unlock` 在协程 B（回调内 `scope.launch`）；
  2. kotlinx.coroutines `Mutex` 契约：`unlock()` 校验当前协程即加锁协程，跨协程必抛异常；
  3. 后果：`finally` 抛异常使锁永远占用，OCR 兜底失效（叠加 #25 的通道 Bug，Recall 的 OCR 实际完全不可用）。
- **修复**（v1.0.15）：改用 `AtomicBoolean` 的 `compareAndSet(false,true)` 抢锁、`set(false)` 释放，
  该标志可跨线程安全读写，不受协程归属约束；`captureMutex`（`withLock` 同协程加解锁）不受影响。
- **原则**：跨协程/跨回调的"是否在执行"标志用 `AtomicBoolean`/`@Volatile`，不要用 `Mutex`；
  `Mutex` 只适合「同一协程 `withLock` 包住整段临界区」的场景。

## 27. [OCR] det 概率图被错误截断到 1/4，检测永远返回空、OCR 静默失效（v1.0.16）
- **背景**：`OcrEngine.detect()` 假设 PP-OCRv4 det 模型输出是输入的 1/4，于是 `mapH=scaled.height/4`、
  `mapW=scaled.width/4`，只把全分辨率概率图的左上 1/4 拷进 `map` 做 DB 后处理。
- **根因**：实测 `ch_PP-OCRv4_det_infer.onnx` 输出与输入**同分辨率**（如 736×1056 输入→736×1056 输出，
  shape `(1,1,736,1056)`），并非 1/4。因此 `map` 只装下概率图左上 1/4，文字一旦不在该区域，
  `dbPostProcess` 连通域为空 → `detect()` 返回空列表 → 无文字框进入识别 → OCR 整条链路静默无输出。
- **多因素确认**：
  1. ONNX `out.info.shape` 实测为全分辨率，与 `scaled/4` 假设直接矛盾；
  2. 诊断：左上 1/4 区域内 `prob>0.3` 像素数 = **0**，故 `dbPostProcess` 找不到任何连通域；
  3. 概率图与输入 1:1 对齐（文字暗像素质心偏差 dx=2,dy=4），坐标仅需 `×ratioW/ratioH` 即可正确回原图；
  4. 端到端复刻：旧版识别为空 `[]`，修复版（全分辨率）正确识别「你好世界」。
- **修复**（v1.0.16）：去掉 `/4`，`mapH/mapW` 直接取缩放图（=模型输出）尺寸；概率图坐标即缩放图像素坐标，
  沿用下游 `×ratioW/ratioH` 回原图。与 #24（det 归一化）、#25（BGR 通道）、#26（跨协程 Mutex）叠加后 OCR 才真正可用。
- **原则**：用 ONNX 模型时务必**先实测输出 shape** 再写后处理分辨率假设，不要凭记忆写死 1/4/1/8 等下采样因子；
  任何「模型输出尺寸 = 输入/N」的假设都要在集成时用 `shape` 校验；DB 类后处理的坐标缩放要同时核对
  下采样因子与 resize 比例（可用原图文字质心 vs 概率图质心做 1:1 对齐自检）。

## 28. [自定义词组] 管理界面用「key,order=value」序列化串当列表项，布局严重不合理（v1.0.17）
- **背景**：`PinyinCustomPhraseFragment.showEntry()` 直接返回 `x.serialize()`（如 `huawei,2=华为`），
  列表每行就是一长串原始键值对，阅读与核对都困难，用户反馈"布局严重不合理"。
- **根因**：`DynamicListEntryUi` 只有一个 `nameText` 单行文本，没有副文本位置；列表项只能显示整段 serialize。
- **修复**（v1.0.17）：
  1. 给 `DynamicListEntryUi` 增加一个 `subText` 副文本，并把 `nameText + subText` 包进一个垂直 `textContainer`；
  2. `DynamicListAdapter` 新增可选 `showSubEntry(x): String?` 钩子（默认 null→隐藏副文本，不影响其它列表）；
  3. `PinyinCustomPhraseFragment` 覆写 `showEntry()` 返回**词组原文**（主文本），`showSubEntry()` 返回
     「键 / 首字母 / 全拼」摘要（副文本，如「键 huawei · 首字母 hw · 全拼 huawei」）。
- **原则**：列表项展示结构化对象时，主文本放"人读的最关键字段"、副文本放次要元数据，不要在 UI 里直接渲染
  内部序列化格式；通用列表控件用可选钩子扩展，避免为单一场景写死布局。

## 29. [Recall] 捕获文本未自动成词，需手动「导出为词组」（v1.0.17 新增自动写入）
- **背景**：v1.0.7 起支持「勾选记录 → 导出为词组」手动提炼中文词组写入 fcitx 自定义词组，
  但每次都要手动勾选+点按钮，用户要求"捕获后自动写入自定义词组，无延迟"。
- **修复**（v1.0.17）：
  1. 抽出 `PhraseAutoWriter`：承载「文本→中文词组提炼（extractPhrases）」+「去抖批量落盘写自定义词组」，
     手动导出与捕获自动写入共用同一套提炼/写入规则（键=拼音首字母 + 全拼键，顺序=2）；
  2. `RecallService` 在节点树与 OCR 两条捕获路径 `record()` 后调用 `PhraseAutoWriter.offer(text)`（仅廉价入队，
     不阻塞捕获主路径）；真正写文件 + `reloadPinyinCustomPhrase()` 由后台协程按 1.5s 去抖合并执行，避免高速滚动时
     每次捕获都触发 fcitx 重载导致卡顿；服务 `onDestroy` 前 `flushNow()` 落盘剩余；
  3. `RecallPreferences` 新增 `auto_add_phrase` 开关（默认开），`RecallActivity` 设置页加对应 Switch。
- **原则**："无延迟/自动"指免手动操作、捕获后数秒内可用，而非每次捕获同步重编 fcitx；高频事件写入要
  积累+去抖合并，且把重活丢到后台协程，主捕获路径只做廉价入队。

## 30. [拼音] ICU 多音字默认读音导致首字母键错误（华为音乐→hwyl）（v1.0.17）
- **背景**：自定义词组键由 `PinyinUtils.initials()` 取拼音首字母生成。"华为音乐" 被识别成 `hwyl`（应为 `hwyy`）。
- **根因**：`PinyinUtils` 用 Android 内置 ICU `Transliterator("Han-Latin; Latin-ASCII")`，它**逐字**取默认读音，
  对多音字「乐」一律给 `le`（单字音），在"音乐"里应为 `yuè`。ICU 不做上下文消歧，故生成 `yin le`→首字母 `yl`。
- **多因素确认**：
  1. 复现：ICU 对孤立"乐"→"le"，"音乐"→"yin le"，与用户反馈 `hwyl` 完全吻合；
  2. 期望：在"音乐/乐队/乐器"等词中应读 `yuè`；
  3. 数据资产是二进制 MobileDict/libime 格式，运行时解析成本高，且原设计坚持"零新依赖/离线"，故不引入新库。
- **修复**（v1.0.17）：在 ICU 之上叠加**词级校正层**——`wordPinyin` 词表（如 `音乐→yin yue`、`银行→yin hang`）
  用贪心最长匹配覆盖多音字词，未覆盖的词仍走 ICU 默认读音。关键实现：不依赖 ICU 输出的音节空格/对齐，
  而是"命中词用词表拼音、未命中字符回退 ICU 单字"重建拼音，因此对 ICU 各种输出格式都稳健
  （"华为音乐"→`hua wei yin yue`→首字母 `hwyy`）。`fullPinyin/initials` 自动受益。
- **原则**：用 ICU/字典做汉字→拼音时，多音字必须做上下文（词级）消歧；校正层用"词表匹配+单字回退"比
  直接按位置替换 ICU 音节更稳健（不受 ICU 是否加空格、是否连写影响）。词表覆盖高频多音字即可取得明显收益。

## 31. [剪贴板] Recall 自动写入剪贴板必须与「用户自己复制」路径隔离，否则刷屏（v1.0.18）
- **背景**：v1.0.17 起 Recall 支持捕获后自动写入自定义词组；用户进一步要求捕获后**直接写入剪贴板**
  （输入法剪贴板候选可见、一键上屏），同时明确要求"除非用户自己复制，防止剪贴板大量刷屏"。
- **根因/风险**：剪贴板库有两条写入路径，若 Recall 复用错的路径就会刷屏：
  1. `ClipboardManager.onPrimaryClipChanged()`：监听**系统剪贴板**，用户主动复制时入库并 `updateLastEntry()`
     （刷新"最近一条"，候选栏会跳到最新条目）；
  2. `ClipboardManager.addText()`：手动「导入到输入法」入口，直接入库。
  若 Recall 用 `setPrimaryClip` 写系统剪贴板：① 覆盖/污染用户刚复制的内容；② 触发
  `onPrimaryClipChanged` 使同一段文本被记录两次；③ 高频捕获时条目瞬间灌满并挤掉用户复制的内容。
- **修复**（v1.0.18）：新增 `ClipboardManager.addRecallText()` + `RecallClipboardWriter`，与用户路径严格隔离：
  1. **只写剪贴板数据库，绝不调 `setPrimaryClip`**——不覆盖用户复制内容、不触发监听重复记录；
  2. **不调用 `updateLastEntry()`**——候选栏不因捕获而跳动；
  3. 默认 `pinned=false`（未置顶），可被正常条数上限清理，不挤占用户条目；
  4. 写入前做长度过滤（4~200 字符）+ 去重 + 全局 2s 节流 + 单次最多 3 条；
  5. 新增 `auto_clipboard` 开关（默认开），设置页可见可控。
- **原则**：自动化来源（捕获/脚本）写入用户可见的"历史/列表"时，必须与用户主动行为路径隔离：
  不写共享的系统状态、不刷新"最近/置顶"、默认不置顶并接受自动清理，并配长度/去重/节流三重过滤。

## 32. [稳定性] 点剪贴板删除 / 长摁中英切换令整个输入法崩溃——IME 进程崩溃同源根因（v1.0.19）
- **现象**：用户报告「点剪贴板里的删除按钮，整个输入法崩溃」「长摁中英切换按钮，崩溃」。
  同类可复现/同源的还有：状态区菜单、候选操作菜单、系统输入法选择对话框在部分 OEM 上弹窗即崩。
- **同源根因（两类）**：输入法(IME)进程内异常若未被捕获，会直接令**整个进程**崩溃（键盘瞬间消失）：
  1. **协程顶层未捕获异常**：IME 窗口里 `lifecycleScope.launch { ... }`、`ClipboardManager` 等
     缺少 `CoroutineExceptionHandler` 的作用域，块内一旦抛异常（Room 写库、弹窗回调、fcitx 调用等）
     沿默认异常处理器上抛 → 进程崩溃；
  2. **IME 窗口内弹窗/对话框令牌非法**：在 EMUI / Android 12+ / MagicOS 上，`PopupMenu.show()`、
     `service.showDialog()` 会因窗口令牌类型非法抛 `WindowManager.BadTokenException`（或
     `IllegalArgumentException: ... token ... not valid`），未捕获即崩溃。
  另有一处**潜伏的 `!!` 空断言**：`CustomGestureView.lifecycleScope = findViewTreeLifecycleOwner()?.lifecycleScope!!`
  与 `LogView` 的同类写法——只要视图树尚未挂上 LifecycleOwner（极端时序），`!!` 即抛 NPE 令进程崩溃，
  长摁任意带长按手势的键（中英切换、空格等）都会踩中。
- **修复**（v1.0.19）：新增统一稳定性基建 `utils/ImeSafe.kt`
  （顶层 `imeExceptionHandler` 协程异常处理器 + `imeFallbackScope` 主线程兜底作用域 + `isBadToken` 判定），并据此：
  1. `ClipboardManager` 协程作用域挂 `imeExceptionHandler`；`ClipboardWindow` 的删除/滑动删除把
     `ClipboardManager.delete` + 撤销 Snackbar 整段 `try/catch`；
  2. `CustomGestureView` / `LogView` 的 `?.lifecycleScope!!` 改为 `?: imeFallbackScope`（保持 Main.immediate 线程模型，绝不 NPE）；
  3. `StatusAreaWindow.popup.show()`、`CommonKeyActionListener.LangSwitchAction.Enumerate`
     （`showDialog(AddMoreInputMethodsPrompt)` / `enumerateIme()`）加 `try/catch` + `imeExceptionHandler`，
     并统一回退到 `InputMethodUtil.showPicker()` 系统选择器；
  4. 既有 `ClipboardWindow.promptDeleteAll`、`CommonKeyActionListener.showInputMethodPicker` 的
     BadToken 兜底逻辑保留并统一到同一思路。
- **原则**：IME 进程内**任何** UI 事件回调/协程都必须自保——协程一律带 `imeExceptionHandler`，
  IME 窗口内弹 `PopupMenu`/`Dialog` 一律 `try/catch` 并回退系统能力；视图树 LifecycleOwner 一律用
  `?: 兜底` 而非 `!!`。新增同类弹窗/对话框时直接复用 `ImeSafe`，不要散落裸 `show()`。

## 33. [自定义词组] 管理自定义词组须有体积上限，否则文件无限膨胀（v1.0.19）
- **背景**：「管理自定义词组」既可由用户手动增删改，也可由 Recall 捕获后**自动写入**自定义词组
  （v1.0.17 起）。自动写入来源无限、用户手动添加也无限，落盘文件若不加限制会膨胀到上百 MB，
  拖慢「再次加载」且可能导致保存/导出失败。用户明确要求「管理自定义词组上限为 100MB」。
- **上限约定**：`PinyinCustomPhrase.MAX_STORE_BYTES = 100L * 1024 * 1024`（100MB）。
- **修复**（v1.0.19）：
  1. `PinyinCustomPhrase` 增加 `MAX_STORE_BYTES` 常量与 `estimateBytes()`（UTF-8 字节估值，保守按 3B/字符）；
  2. `PinyinCustomPhraseFragment.saveConfig()` 落盘前**按体积裁剪**：估算总字节，超出则反向丢弃末尾
     低优先词条直到 ≤100MB，并 `Snackbar` 提示「已按 100MB 上限裁剪 N 条」；
  3. `PhraseAutoWriter.writeBatch()` 自动写入时维护 `budget`，每条按 `serialize` 字节估值预扣，
     预算不足即停止（不落盘超额词条），避免后台累积撑爆 100MB。
- **说明**：100MB 为「序列化落盘前」的字节估值上限（非文件精确字节），留出安全余量；
  自定义词组为「词→拼音键」映射，单条通常数十字节，100MB 可容纳千万级词条，远超正常使用量。
- **原则**：任何「用户可见列表 + 自动/手动无限追加」的持久化数据，都应有体积上限并「落盘前裁剪」，
  不依赖 Room/文件系统的隐式限制；自动写入路径与手动路径共用同一预算。

## 34. [OCR·精度] 高清截图按原生分辨率入 det → 概率图退化，文字大面积漏检（v1.0.20）

- **现象**：2800×1280 的游戏/应用长截图，OCR 经常「一条字都识别不出来」或只认出零星几个词。
- **根因（实测）**：PP-OCRv4 det 是在**长边约 736~1280** 的尺度上训练与调优的。旧 `detResize`
  只在「短边 < 736」时放大、**大图一律保持原生分辨率**，于是 2816×1280（约 360 万像素）直接入网，
  概率图整体退化（均值 ~1e-5，几乎全黑），后处理只能捞到零星噪点框。
  同一批 4 张样图的关键词召回实测：

  | det 输入长边 | thresh/box=0.3/0.5 | 0.2/0.3 | 0.15/0.2 |
  |---|---|---|---|
  | 736 | 4.1% | 19.2% | 24.7% |
  | 960 | 11.0% | 23.3% | 27.4% |
  | 1120 | 5.5% | 26.0% | 32.9% |
  | **1280** | 16.4% | 32.9% | **41.1%** |
  | 1600 | 15.1% | 23.3% | 35.6% |
  | 2816（原生，旧行为）| 2.6%（旧基线）| — | — |

  → 结论：**不是**「分辨率越高越准」。长边 1280 附近最优，原生分辨率反而最差。
- **修复**（v1.0.20）：`detResize` 改为互斥三分支
  —— 长边 > 1280 等比降到 1280；否则短边 < 736 等比补到 736；否则保持原生。边长对齐 32。
- **附带收益**：det 输入从 360 万像素降到约 74 万，推理耗时大幅下降（速度诉求同时满足）。
- **相关硬约束**：det 模型要求输入边**对齐 32**，否则内部 `Add` 广播直接抛异常
  （实测 400×100 报 "25 by 26"，2800 宽报 "175 by 176"）。因此对齐不可省略；
  若后续调整 `DET_MAX_SIDE`，务必保持 `round(x/32)*32`。

## 35. [OCR·精度] warpCrop 分辨率砍半 + 最近邻采样，游戏小字必糊（v1.0.20）

- **现象**：UI 上约 20px 高的中文小字（如「觉醒」「搜索」）被识别成完全无关的汉字，
  且置信度还很高（典型「高分乱码」）。
- **根因**：旧 `warpCrop` 把裁剪尺寸写成 `w = width0 / 2`、`h = height0 / 2`，
  且采样时对浮点坐标直接 `toInt()`（**最近邻**）。小字被砍到 10px 再放大到 rec 的 48 高，
  笔画全糊；CRNN 对域外输入又极易「过度自信」，于是输出高分乱码。
- **修复**（v1.0.20）：
  1. 裁剪按**原生分辨率**取（`w = width0`、`h = height0`），不再砍半；
  2. 采样改为 **ARGB 双线性插值**（新增 `bilinearARGB()`，四邻域加权，alpha 取主像素）；
  3. rec 宽度改为**按真实宽高比动态取值**（上限 `REC_WIDTH_MAX = 960`）——
     旧实现一律压到 320，长句（如健康游戏忠告整行）被横向压扁数倍，CTC 解码必错。
     （已确认该 rec ONNX 的宽度轴是动态维度，可安全传任意宽度。）

## 36. [OCR·已证实真 bug] 识别(rec)张量行步长误用常量 REC_WIDTH(320)，长行识别整段乱码（v1.0.21）

- **根因（代码可证实，无需崩溃栈）**：`recall/ocr/OcrEngine.kt` 的 `recognizeCrop()` 在把归一化像素写入
  rec 模型张量时，行步长写成了**常量 `REC_WIDTH`(320)**：
  `val idx = y * REC_WIDTH + x`，但张量实际行宽是 v1.0.20 引入的**动态宽度** `targetW`
  （`targetW = min(REC_WIDTH_MAX=960, max(8, ceil(48 * 宽高比)))`，长句可达 960）。
  - 张量尺寸为 `3 * REC_HEIGHT(48) * targetW`，三平面按 `idx` / `plane+idx` / `2*plane+idx` 写入，
    `plane = REC_HEIGHT * targetW`。当 `targetW ≠ 320`（绝大多数含中文的长行都如此），
    用 320 当步长会让第 2 行及之后的像素写入错位到错误偏移，B/G/R 三平面整体错位 → 识别结果全乱。
  - 复刻台验证：构造 targetW=384 的宽裁剪，`y=1` 行本应落在 `idx∈[384,767]`，
    却在 `REC_WIDTH` 步长下落在 `[320,703]`，与第 0 行尾部重叠并向下错位，输出必为乱码。
- **为何隐蔽**：短词（targetW≈320）`REC_WIDTH` 与 `targetW` 恰好相等，识别正常；
  只有长句/整行（targetW 明显 >320）才暴露，表现为「短词准、长句乱」，且 v1.0.20 刚把宽度改动态，
  等于新修复顺手引入了回归而未被发现（旧实现一律压 320 反而「碰巧正确」）。
- **修复**（v1.0.21）：行步长改为 `val idx = y * targetW + x`，与张量真实行宽一致。
  顺带核实 `chwBgrNormalized()`（det/cls 共用）始终用 `y * w + x`（w=真实宽），无同类问题。
- **沉淀规则**：任何"归一化写 CHW 张量"的循环，行步长必须等于该张量的真实宽度，绝不能用字面常量；
  凡引入动态宽度，必须同步 grep 所有 `y * <常量> + x` 写张量处。

## 37. [整合·已证实真 bug] 智能候选词永远显示「暂无本地统计」+ 离线智能词库与自定义词组整合为单一「用户词库」屏（v1.0.22）
- **真 bug 根因（代码可证实，无需崩溃栈）**：`data/smartdict/SmartDictManager.kt` 的 `recordUsage(text)` 用来把用户提交文本喂进本地统计、生成「智能候选词」，
  但全工程**零调用点**（`grep -rn recordUsage` 仅有定义，无任何 `SmartDictManager.recordUsage(` 调用）。
  因此实验性功能从不记录，`SmartDictActivity` / 整合屏的「智能候选词」段永远显示「暂无本地统计」——即用户所说的「未显示任何内容」。
  - 正确接入点：`input/FcitxInputMethodService.kt` 的 `commitText(text, ...)`（用户输入法提交候选词/整句处）。
    修复：在 `commitText` 提交成功后，`imeFallbackScope.launch(Dispatchers.IO) { runCatching { SmartDictManager.recordUsage(t) } }`，
    并 `if (!PrivacyController.inPrivacyMode)` 门控（密码框等隐私模式下不统计，与 `PrivacyController`/`PasswordFieldDetector` 既有约定一致）；
    递归提交分支传 `record = false` 避免重复统计。`recordUsage` 内部已 `runCatching` 包裹文件 IO，外层再经 `imeExceptionHandler` 兜底，绝不影响输入法主线程。
- **整合（用户诉求：两者一致、功能全集）**：「离线智能词库」(`SmartDictActivity`) 与「管理自定义词组」(`PinyinCustomPhraseFragment`) 合并为单一
  `PinyinCustomPhraseFragment`（统一「用户词库」屏），顶部三段切换 `自定义词组` / `智能候选词` / `已安装词库`：
  - 自定义词组：保留原有全部能力（两行卡片、搜索键/内容/拼音首字母、锁定、多选删除、100MB 上限）。
  - 智能候选词：本地统计高频词，每条「保存为自定义词组」(转存后写入**同一** 100MB 自定义词组文件，`PinyinUtils.fullPinyin` 推导 key，IME 直接出词)
    或「忽略」(从统计移除)；工具条「一键全部转存」「清空本地统计」。
  - 已安装词库：Rime/拼音/码表词库文件勾选管理，原 `SmartDictActivity` 文件删除能力并入。
  - 主菜单「离线智能词库」入口改为 `navigateWithAnim(SettingsRoute.PinyinCustomPhrase)`，与「管理自定义词组」指向同一屏。
- **沉淀规则**：任何「统计/学习类」功能，定义采集点后**必须**在真实数据流路径上调用一次并自测有数据；纯定义不调用 = 形同虚设。
  跨屏合并功能时，优先复用已成熟的列表 UI（`BaseDynamicListUi.ui.addItem` 供程序化转存），不要另起炉灶。

## 38. [无上限·已证实] 全应用唯一的条数上限藏在 DAO 的 `LIMIT 300`，UI 渲染必须同步分块（v1.0.23）
- **现象**：用户反馈「应该是无上限的怎么最多300」。排查全工程（`grep -rn "300" --include=*.kt`）：
  `AppPrefs` 的 300 是长按延迟毫秒 / 键盘边距 dp，`PasswordGenerator` 的 300 是尝试系数，均无关；
  真正的条数上限是 `recall/data/RecallDatabase.kt` DAO 的 `LIMIT 300`（pitfalls #33 当年为防 UI 全量重建 ANR 而加的权宜之计）。
  另有两处"隐性上限"：`SmartDictManager.recordUsage` 按频次裁剪 2000 条、`topWords(50/30)` 展示截断。
- **教训**：**上限要么不存在、要么显式可见**。藏在 SQL 里的 `LIMIT` 用户无从发现，只会觉得"东西丢了"。
- **修复（v1.0.23）**：
  1. DAO 移除 `LIMIT`（all/search 全量返回）；
  2. UI 必须同步改为**分块渲染**：`RecallActivity.render()` 用 `records.chunked(300)` + 块间 `yield()` 在协程里分帧建行，
     新渲染开始时 `renderJob?.cancel()` 防并发重建。**直接把 LIMIT 拿掉而不改渲染 = 把偶发丢数据换成确定性 ANR**，两步必须一起做；
  3. `recordUsage` 取消 2000 裁剪、`topWords()` 默认 `Int.MAX_VALUE` 全量返回（JSON 文本文件数万条也仅百 KB 级，无 IO 风险）。
- **沉淀规则**：给统计/列表功能加"安全上限"前先问一句：用户视角这算不算丢数据？加在存储层（字节预算，如自定义词组 100MB）用户可理解；加在 DAO `LIMIT` 层就是暗中丢数据。

## 39. [UX·已证实] "重复词组刷屏"用显示层折叠解决，绝不能动数据层（v1.0.23）
- **需求**：捕获自动写入/反复识别会把同一个词组写多遍，列表刷屏；用户要"同词组只出现一次"的开关，但词组本身**必须继续可反复识别**。
- **正确姿势**：开关只控制**显示层**——`renderDedup()` 按 `value` 用 `LinkedHashMap` 分组、每组显示一行（`×N` 角标 + `N 条重复（数据全保留）`），
  点「编辑」落到该组第一条；数据层（`ui.entries`/落盘文件）一个字都不动。
- **错误姿势**（切勿）：把重复项从 `BaseDynamicListUi` 里 `removeItem` 掉——那会触发 `onItemRemoved` → dustman.remove → 落盘时真删数据，
  且撤销 Snackbar 会把"显示去重"变成"数据去重"。显示与数据两层必须严格分离。
- **配套**：开关状态持久化在独立 `SharedPreferences`（`user_vocab_prefs/dedup_visual`，默认开），不塞进 AppPrefs 避免为单个 UI 开关动全局配置面。

## 40. [崩溃·已证实] 无上限化是全局承诺：任何全量列表页漏改分帧 = 点进即崩（v1.0.24）
- **现象**：v1.0.23 后点设置「用户词库（含智能候选词）」**彻底崩溃**。入口是 `navigateWithAnim(SettingsRoute.PinyinCustomPhrase)`
  （v1.0.22 起不再打开 SmartDictActivity），崩溃发生在 `PinyinCustomPhraseFragment.onCreateView`。
- **根因**：v1.0.23 无上限化只给 `RecallActivity` 做了分帧渲染，但**同一版本**把 `PinyinCustomPhraseFragment` 的数据源也改成了全量
  （`topWords()` 全量 + `renderDedup()` 全量分组），而该页 `renderSmart`/`renderDedup` 仍是主线程一次性构建全部 View
  ——`onCreateView` 里同步调 `renderDedup()` 且预拉取 `loadSmartWords()`。长期自动捕获积累数千~数万条后，点进页面即 OOM/ANR。
  正是 #38 教训的"换页复发"：改了数据量、没改渲染。
- **修复（v1.0.24）**：
  1. `renderDedup()`/`renderSmart()` 改协程分帧：快照 → `Dispatchers.Default` 分组 → `chunked(300)` + 块间 `yield()` 分帧建行，
     头部「共 N 条」先渲染给即时反馈；
  2. `dedupRenderJob`/`smartRenderJob` 新渲染前 `cancel()` 防并发重建，`onDestroyView` 统一取消；
  3. 协程内 `context ?: return@launch` + `isAdded` 双保险，杜绝 detach 后 `requireContext()` 二次崩溃（同源加固）；
  4. `promoteAll()` 批量转存用 `suppressDedupRefresh` 抑制逐条触发 `renderDedup` 重建（几千条 × cancel/重启 = 主线程被打断数千次），结束统一刷一次。
- **沉淀规则**：把任何列表的数据源改成"全量"前，先 grep 它的**全部渲染调用方**；每一处渲染方必须同时满足：
  分帧建行 + 可取消 + detach 安全。三件事缺一件，就是下一个"点进即崩"。

## 41. [崩溃·已证实] 标点提交链全程无异常处理器 = 点句号/逗号等会崩整个输入法（v1.0.25）
- **现象**：中文输入时点按「句号(。)」等标点，输入法进程直接崩溃（与 #40 同源：未捕获异常沿协程默认处理器上抛）。
  标点走 `CommonKeyActionListener` 的 `CommitAction` → `service.postFcitxJob { commitAndReset() }`
  + `service.lifecycleScope.launch { service.commitText(...) }`。
- **根因**：`postFcitxJob` 的 `fcitx.lifecycleScope.launch(...)` **从未挂 `imeExceptionHandler`**；
  `commitAndReset()` 内含 `select(0)`（→ 原生 `selectCandidate`）与 `reset()`（→ 原生 `resetInputContext`）。
  在「候选为空但 preedit 缓存非空」「fcitx 状态瞬变」「输入法刚切回」等边缘态下，这些原生调用会抛
  `IllegalArgumentException`/`IllegalStateException`，异常未被捕获 → 整进程崩（见 ImeSafe.kt 记载的同源模式）。
  手写面板（v1.0.23 新增的 `，。？！` 竖排键）同样走此路径，一并修复。
- **修复（v1.0.25，三处加固）**：
  1. `FcitxInputMethodService.postFcitxJob`：`launch(imeExceptionHandler, start = LAZY)` —— 系统性兜底，
     覆盖全部 fcitx 操作（标点/符号/快捷短语/中英文切换等 Submit 类动作）的未捕获异常；
  2. `CommonKeyActionListener` 的 `CommitAction`：`commitAndReset()` 包 `runCatching`，
     即使复位失败，标点仍照常 `commitText`、不阻断提交、不崩溃；`commitText` 的 launch 也挂 `imeExceptionHandler`；
  3. `FcitxInputMethodService.commitText` 整段包 `runCatching` —— 覆盖 `CommitStringEvent` 回灌等
     无 handler 调用方，`composing`/`selection`/`InputConnection` 瞬断等异常只记日志不崩。
- **沉淀规则**：IME 进程内任何「协程顶层 / fcitx 原生调用」抛出的异常都必须有 handler 或 try/catch 兜底
  （统一用 `imeExceptionHandler` / `ImeSafe.imeFallbackScope`）。凡是 `postFcitxJob { ... }` 或
  `lifecycleScope.launch { commitText(...) }` 的新增调用点，默认就要带 handler，不要等崩了再补。

## 42. [构建·已证实] 许可审计的「路径段名匹配」误伤自家代码：包路径 `org/fcitx/fcitx5/android/` 里的 `fcitx5` 段把整棵主代码树判成 vendored（v1.0.26）
- **现象**：`docs/license_audit.py` 第一轮报告「508 带头 / 149 缺失」，收紧排除规则后反而变成「30 带头 / 40 缺失」——
  带 SPDX 的文件数暴跌，而 app/src 下明明几百个文件都带头。
- **根因**：vendored 判定用「路径任一目录段命中 submodule 名」，而 MBM 的 Java 包目录
  `app/src/main/java/org/fcitx/fcitx5/android/` 含 `fcitx5` 段；模块目录 `lib/fcitx5/` 又与 submodule
  目录 `src/main/cpp/fcitx5/` 撞名。段名匹配同时把第一方主代码树（误判为 vendored，跳过 SPDX 强制）
  和一方构建脚本（并入 vendored 台账）都漏统计。
- **修复**：vendored 判定收紧为「只在 `src/main/cpp/` 段**之后**匹配」——
  vendored 源码约定俗成都在 `src/main/cpp/<name>/` 下，Java 包路径不可能出现在 cpp/ 之后。
  修复后审计数字恢复正常（435 带头 / 0 缺失，台账不再混入 app/src 文件）。
- **沉淀规则**：任何「按目录名/段名分类源码」的脚本，先列一张**撞名表**（模块名 vs submodule 名 vs 包路径段），
  用路径的**结构性位置**（如 src/main/cpp/ 之后）而不是「出现在任何位置」做判定；数字异常（带头数暴跌/台账混入一方代码）
  本身就是判据不对的信号，先怀疑判据再怀疑代码。

## 43. [合规·已证实] MBM 是「LGPL-2.1-or-later × 358 + AGPL-3.0-or-later × 77」混合许可衍生作品，不是单一 AGPL 工程（v1.0.26）
- **现象**：NOTICE 长期自称「以 AGPL-3.0 整体发布」。逐文件 SPDX 实测：继承上游 fcitx5-android 文件头的
  358 个文件全是 `LGPL-2.1-or-later`，只有 MBM 新写/新改的 77 个是 `AGPL-3.0-or-later`。
- **风险**：对外宣称单一许可与文件头事实不符 → 误导下游（以为可整体按 AGPL 传播，实际对 LGPL 文件必须保留
  LGPL 条款含替换库权）；源码包/发布物许可声明错误属 AGPL 合规事故。
- **修复**：NOTICE 第五节改为混合许可的事实陈述——LGPL-2.1+/GPL-2.0+ 依「或更高版本」升级条款随组合作品以
  GPL-3.0 适用，再经 GPL-3.0 §13 与 AGPL-3.0 部分组合；`license_audit.py` 增加许可分布白名单检查
  （AGPL-3.0-or-later / LGPL-2.1-or-later / GPL-3.0-or-later），集合外即失败；SBOM 的 metadata.licenses
  同时列两种许可，不宣称单一。
- **沉淀规则**：衍生工程的许可结论只能来自**逐文件 SPDX 头的实测分布**，不能来自 LICENSE 文件或印象；
  每次同步上游后重跑 `license_audit.py --check`；GPL-2.0-**or-later** 有升级出路（可与 §13 组合），
  **-only** 才是不兼容——混为一谈会误杀上游、放过真问题。

## 44. [构建·已证实] PackageInfo.signatures 的可空性随 SDK 元数据波动：`signatures.isEmpty()` 直接编译失败（v1.0.26）
- **现象**：`:app:compileReleaseKotlin` 报 `AppSignatureVerifier.kt:62:23 Only safe (?.) or non-null asserted (!!.)
  calls are allowed on a nullable receiver`——同文件在别的变体/缓存状态下曾编译通过，改 SDK/清缓存后暴露。
- **修复**：`signatures.isEmpty()` → `signatures.isNullOrEmpty()`（Kotlin stdlib 对 `Array<out T>?` 的扩展），
  局部 val + 前置判空后 smart cast 成立。自校验类代码**只告警不 crash** 的约束下，更不能引入可空崩溃。
- **沉淀规则**：`PackageInfo.signatures` / `signingInfo` / 各 `PackageManager` 返回结构在 Kotlin 侧一律按**平台可空类型**
  处理（`isNullOrEmpty`、`?: return`），不要赌某个 SDK 版本的非空推断。

## 45. [构建·已证实] variant DSL 锁死期：签名指纹注入必须写进 `onVariants`，`afterEvaluate` 里改 `buildConfigFields` 必抛（v1.0.26）
- **现象**：`AndroidAppConventionPlugin` 在 `afterEvaluate` 里 `variant.buildConfigFields.put(...)` 抛
  `The value for property 'buildConfigFields' cannot be changed any further`。
- **修复**：改用 `androidComponents.onVariants { variant -> ... }`，在回调内同步写 `buildConfigFields`
  （AGP 9 的 Variant API，variant 对象在 onVariants 作用域内可写）。实测：测试 keystore → BuildConfig.SIGNER_SHA256
  与 `keytool -list -v` 输出逐字符一致。
- **沉淀规则**：AGP 8+ 的所有 variant 定制（buildConfigField / manifest placeholder / 签名注入）都进
  `androidComponents.onVariants`；`afterEvaluate` 里只能读不能写。写不进 `onVariants` 的说明用错了 API 层。

## 46. [API·已证实] AccessibilityService.takeScreenshot 的 2 参重载在 API 34 已被移除，仅剩 3 参（v1.0.26 续）
- **现象**：RecallService 的 OCR 兜底调用 `takeScreenshot(Executor, Callback)` 编译失败，Kotlin 把它解析成
  3 参 `takeScreenshot(int, Executor, Callback)`（首参期待 Int 却收到 ExecutorService）。
- **根因**：javap 证实 `android-37/android.jar` 里 `takeScreenshot` 仅剩 3 参重载
  `takeScreenshot(int, Executor, TakeScreenshotCallback)`。2 参 `takeScreenshot(Executor, Callback)`
  是 API 30 加入、API 34 移除的——不存在跨 API 30–37 的单一重载：3 参仅 34+，2 参仅 30–33。
  原代码 `guard >= R(30)` 调 3 参，在 Android 11/12/13 运行时会抛 NoSuchMethodError（OCR 整条失效）。
- **修复**：OCR 入口 guard 提到 `>= UPSIDE_DOWN_CAKE(34)`，内部只用 3 参；旧版本直接走不到，
  节点树主路径不受影响。compileSdk≥34 的工程无法静态引用已删的 2 参重载，跨版本兼容只能靠版本分支+反射，
  对本工程而言"在 34+ 启用 OCR"是最低风险的取舍。
- **沉淀规则**：凡用到 compileSdk 之后被删除的方法重载，先 javap 确认目标 android.jar 里到底有哪些签名；
  "能编译"不代表"运行不崩"——重载的增删和 API level 必须逐一核对，不能用"老代码一直这么写"当证据。

## 47. [安全·已证实] 启动签名自校验必须用「集合隶属」而非「并集串相等」（v1.0.26 续）
- **现象**：AppSignatureVerifier 对多签名 APK 永远判 mismatch，误报重打包、置 signature_mismatch 标志，
  输入法体验降级（虽按设计只告警不 crash，但是持续误报）。
- **根因**：`BuildConfig.SIGNER_SHA256` 是 keystore 首证书**单**指纹；而运行时 `selfSha256` 对多签名者
  返回 `"fp1|fp2|…"` 并集串。旧比较 `current != expected` 拿并集串和单指纹做相等比较，
  结构上永远不相等——密钥轮换 / v2-v3 签名方案 APK 必中招。
- **修复**：改为 `expected ∈ 当前签名者集合`（集合隶属），新增纯函数 `signerMatches(expected, signers)`+
  AppSignatureVerifierTest(5 例) 钉住：单签名命中、轮换期多签名仍命中、并集串不能蒙混、重打包不命中、空集不命中。
- **沉淀规则**：任何「自己证书 vs 基准证书」的校验，凡是 APK 可能有多个签名者，一律用集合关系
  （基准 ∈ 实际集合）而不是字符串相等；只读首证书做相等比较等于给轮换期 APK 埋雷。判据与展示字符串要分开
  （并集串只用于日志，绝不作为判据）。

## 48. [插件·已证实] 独立插件模块的清单短类名按模块 namespace 解析，与主 app 同名短名不是同一个类（v0.0.02）
- **现象**：Recall 插件（v1.0.26 从主 app 拆出）在真机上「启用 Recall 捕获」开关打开、无障碍列表显示已开启、
  插件管理显示已加载，但零事件、零日志、UI 无任何感知——功能完全失效。
- **根因**：插件模块 `namespace` 为 `org.fcitx.fcitx5.android.plugin.recall`，清单里服务写短名
  `.recall.RecallService`，被解析为 `org.fcitx.fcitx5.android.plugin.recall.recall.RecallService`（不存在），
  而真实类在 `org.fcitx.fcitx5.android.recall.RecallService`。**同一份 `.recall.RecallService` 字符串在拆包前的
  主 app 模块里是正确的**（namespace 不同，短名补全后恰好命中）——拆模块时原样搬清单，类名语义静默改变。
  系统侧表现极具误导性：设置里显示"已开启"，但服务永远无法实例化，AccessibilityService 完全不回调。
- **验证**：`aapt2 dump xmltree --file AndroidManifest.xml <apk>` 解包构建产物，确认解析后的全限定名。
- **修复**：清单改全限定名 `org.fcitx.fcitx5.android.recall.RecallService`。
- **沉淀规则**：① 凡跨模块搬移的 `<service>/<activity>/<receiver>` 声明，**一律先写全限定类名再搬**，
  短名只在同模块内安全；② 模块 namespace 与目标类的包前缀不一致时，短名必错，且**错而不崩**（无障碍场景
  连崩溃日志都没有）——"开关显示已开但无反应"要第一反查 aapt2 解包后的清单，而不是查运行时代码；
  ③ 拆模块 checklist 必须包含「解包构建产物核对每个组件全限定名」这一步。

## 49. [逻辑·已证实] `takeIf{isNotBlank()}` 空值歧义把「隐私丢弃」和「无文字」压成同一个 null，兜底成死代码（v0.0.02）
- **现象**：RecallService 的 OCR 兜底（无障碍节点树为空但屏幕有文字的场景）代码写完从未触发，
  日志里 discard 与空文本两种走向混在一起无法区分。
- **根因**：`collected.joinToString("\n").takeIf { it.isNotBlank() }` 把「隐私策略主动丢弃」和「采集到空文字」
  都变成 null；下游守卫 `text != null && text.isBlank()` 在两种 null 下永假，OCR 分支结构上不可达。
- **修复**：改用 Pair 解构 `(text, privateWindow)`，隐私丢弃显式返回 `null to true`、无文字返回 `null to false`，
  守卫改为 `!privateWindow && text.isNullOrBlank() && ocrEnabled`。
- **沉淀规则**：同一函数多因归果时，不要用「可空」编码多个语义分支——null 无法携带"为什么为空"。
  要么拆返回值语义（Pair/密封类/Result），要么给每个分支打点日志；写完兜底代码必须自问
  "这个分支在什么输入下第一次被执行？能不能构造一次？"，构造不出来的兜底就是死代码。

## 50. [API·已证实] compileSdk 37 的 stubs 移除了废弃包 `android.accessibility.*`，只剩 `android.view.accessibility`（v0.0.02）
- **现象**：`:plugin:recall:compileReleaseKotlin` 报 `Unresolved reference 'accessibility'`（689:78），
  指向 `android.accessibility.AccessibilityManager`；而同文件的 `android.accessibilityservice.AccessibilityService`
  （RecallService.kt）、`android.view.accessibility.*` 全部编译通过——报错的只有 `android.accessibility` 这一个包。
- **根因**：`unzip -l android-37.0/android.jar` 实测：包清单里有 `android/accessibilityservice/`、`android/view/accessibility/`，
  唯独没有 `android/accessibility/`（source jar 同样没有）。jar 体积/条目数正常（51MB/15156 项），不是截断——
  API 37 stubs 把这对老别名包整体删了。`Context.ACCESSIBILITY_SERVICE` 的文档返回类型本就是
  `android.view.accessibility.AccessibilityManager`，运行时对象也是它，换包名零行为差异。
- **修复**：`android.accessibility.AccessibilityManager` → `android.view.accessibility.AccessibilityManager`、
  `android.accessibility.AccessibilityEvent.TYPES_ALL_MASK` → `android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK`；
  javap 确认 `getEnabledAccessibilityServiceList(int)` 与 `TYPES_ALL_MASK` 在 view 包里都在（minSdk 29 无碍）。
- **沉淀规则**：`android.accessibility` 与 `android.view.accessibility` 是长期并存的双包，旧教程/旧代码写前者的很多；
  升 compileSdk 时对「 unresolved 的恰好是某个历史别名包」要第一时间 `unzip -l android.jar | grep <包名>` 验证
  stubs 是否还收录该包，而不是去翻自己的 import；同族还有 `android.view.accessibility` 优先的原则
  （`AccessibilityNodeInfo`/`AccessibilityEvent` 一直都在 view 包下，从来不该写 `android.accessibility.*`）。

## 51. [API·已证实] Android 11+ 包可见性过滤：跨包 `getPackageInfo()` 对未声明的包一律抛 NameNotFoundException（v0.0.03）
- **现象**：Recall 独立插件 APK 已安装、两个包签名也一致，主输入法点「Recall 屏幕记忆」仍弹
  「尚未安装「屏幕记忆」独立应用（org.fcitx.fcitx5.android.plugin.recall）」。用户反复确认"装了、签名一致"，
  功能依旧不可用。
- **根因**：API 30 起系统默认隐藏除「自动可见」情形外的所有其他包（自己的包、系统核心包、
  安装你的包、startActivityForResult 的调用方、绑定你服务的包、访问你 ContentProvider 的包、
  接收你输入的包等）。**同签名不在自动可见名单内**——签名只用于放行组件调用，不用于让包"可被查询"。
  主输入法清单里 `<queries>` 只有两条 `<intent>`（`${applicationId}.plugin.MANIFEST` 与 `android.view.InputMethod`），
  而 Recall 插件的 `RecallActivity` 按设计**不暴露任何 intent-filter**（只能由主输入法凭 signature 级
  LAUNCH 权限显式拉起），两条 intent 查询一条都匹配不到 → `getPackageInfo(pkg, 0)` 抛异常 →
  代码把它当成「未安装」。属于「同签名即可见」这一直觉的硬钉子。
- **修复**：主输入法 `<queries>` 中加入
  `<package android:name="org.fcitx.fcitx5.android.plugin.recall" />`；同时把
  「查不到包」与「查得到但 startActivity 失败（signature 级权限拒发 → 多为签名不一致 / 版本不匹配）」
  拆成两条提示文案，失败原因写入 Timber，避免下次再出现"无法区分到底装没装"。
- **沉淀规则**：① 凡是主包要**探测另一个 APK 是否安装**（插件/配套 App/可选组件），
  targetSdk ≥ 30 时必须在 `<queries>` 里显式声明 `<package>`，`<intent>` 只在对方真的声明了
  intent-filter 时才管用；② 「跨包调用能成功」≠「跨包查询能看见」，这两件事的权限模型不同，不要互相推断；
  ③ 检测失败时不要把「看不见」和「没安装」合并成同一条用户提示——它们需要完全不同的下一步动作。

## 52. [内存·已证实] `Bitmap.createBitmap` 副本的回收责任在调用方，一个入口修了不等于修了（v0.0.04）
- **现象**：设置页「离线手写识别」每写一笔识别一次，内存持续上涨不回落；键盘内手写面板没有这个问题。
- **根因**：`HandwritingView.snapshot()` 返回的是 `Bitmap.createBitmap(src)` 的**新副本**（v0.0.02 修键盘入口时
  已给 `HandwritingWindow` 加了 `bmp.recycle()`），而设置页入口 `HandwritingActivity.recognize()`
  从来没回收——同一个 `snapshot()` 的两个调用方，只修了一个。副本不进画布自己的缓存，纯纯的外溢泄漏。
- **修复**：`recognize()` 把回收放进 `withContext(Dispatchers.Default)` 的 `finally`，识别抛异常也保证回收；
  同时模型加载失败（`ensureRecognizer() == null`）现在弹 Toast 而不是静默清空候选行——
  原来失败时 UI 与「没识别到笔画」无法区分，属于同源的可观测性缺陷。
- **沉淀规则**：① 「XX 返回副本/新对象」的函数，所有调用方都要过一遍回收/关闭，修复时先
  `grep` 全部调用点而不是只改触发 bug 的那个；② 资源释放放 `finally` 比放成功路径可靠，
  尤其识别/IO 这类随时可能抛的路径；③ 「失败时静默清空」和「正常无结果」在 UI 上必须可区分，
  否则用户无法反馈、开发者无法定位。

## 53. [安全·已证实] Zip Slip：同一个仓库里两套解压代码，一套有防护一套没有，等于没有（v0.0.04）
- **现象**：「规则与词库」导入 Rime zip 包时，包内条目名可含 `../`，解压后文件写到了 rime 用户目录之外
  （可覆盖 app 自己的数据库/配置文件）。项目内 `utils/ZipStream.kt` 明明有 canonicalPath 防护，
  但 `data/rules/RuleImporter.kt` 的 zip 分支是另一套 `ZipFile` 代码，没复用。
- **根因**：安全防护散落在各调用点而不是收敛在解压原语里。`RuleImporter` 直接
  `File(target, entry.name)`，`entry.name` 来自用户上传的 zip——`../../databases/xxx` 这类名字
  `File()` 照单全收，写文件时就逃逸了。
- **修复**：`RuleImporter` zip 分支解压前规范化并校验 `canonicalDest.startsWith(canonicalTarget + File.separator)`，
  越界条目记 Timber 跳过；同时修了 `ZipStream.extract` 的前缀误判——
  原来只 `startsWith(canonicalDest)`，destDir=`/tmp/x` 时 `/tmp/xy` 也会被误判为合法（兄弟目录误放行），
  必须拼 `File.separator`；目录分支的 `mkdir()` 顺手改 `mkdirs()`（多级目录条目原本会静默失败）。
- **沉淀规则**：① 所有「不可信输入 → 文件路径」的拼装点（zip 条目名、DISPLAY_NAME、Intent extra）
  都要走同一个净化函数，不要在每个调用点手写；② canonicalPath 前缀比较**必须带尾部分隔符**，
  否则是"前缀字符串匹配"而不是"目录包含判断"；③ 修安全问题时先全仓 grep 同类模式
  （`File(.*entry` / `File(.*name`），防护不一致比没防护更危险——它给人"已经防了"的错觉。

## 54. [安全·已证实] DISPLAY_NAME 是攻击面：`File(dir, displayName)` 可被路径分隔符注入（v0.0.04）
- **现象**：从某些文件管理器分享导入单文件词库/方案时，落盘文件名里带目录层级，甚至能落到目标目录外。
- **根因**：`RuleImporter.analyzeOne` 的 `fileName` 直接取自 `OpenableColumns.DISPLAY_NAME`——
  这个字段完全由提供文件的 App 决定，可以含 `/`、`\`、`..` 甚至绝对路径前缀。此前
  `installRime` 单文件分支和 `installPinyin` 的缓存文件都直接 `File(target, item.fileName)`。
- **修复**：新增 `sanitizeFileName()`——只保留 basename、剔除控制字符与 `\/:*?"<>|`、
  拒绝 `.`/`..`，为空时回退 `import_<时间戳>`；单文件 Rime/拼音两条路径全部改走净化后的名字。
- **沉淀规则**：① ContentResolver 查询出来的任何字符串（DISPLAY_NAME、mime、extra）都是不可信输入，
  做路径拼装前先净化；② 净化函数要处理「剥完还有 `..`」和「剥完为空」两种退化情况，
  并给出确定性回退值；③ 文件名净化和 Zip Slip 防护是同一个问题的两个入口，要一起修。

## 55. [IME·已证实] IME 窗口内弹抢焦点的 PopupMenu，会被 OEM ROM 判定「输入法失焦」而强制收起（v0.0.05）
- **现象**：长按候选词弹出「以自定义词组的方式置顶」菜单，点完菜单（甚至菜单自动消失后）
  键盘被自动收起；重新点亮屏幕/切换应用后才能恢复。用户录屏取证时间线：
  菜单弹出(00:07.5)→菜单消失+候选区清空(00:08.5)→键盘收起(00:10)→系统文本工具栏重新出现。
- **根因**：`BaseInputView.showCandidateActionMenu` 用系统 `PopupMenu`，其内部
  ListPopupWindow 是一个**会抢焦点**的独立窗口。华为等 OEM ROM 上，IME 窗口内弹出独立窗口
  会被系统判定「IME 已失焦」→ 强制隐藏输入法；popup dismiss 后焦点虽回到 Editor
  （系统文本工具栏重新出现就是证据），IME 却不恢复（onStartInput 未被重新调用）。
  这是「弹窗令牌/OEM 适配」家族问题（#21/#22 是展示失败，本条是隐藏副作用）的第三个变种。
- **修复**：改用 `isFocusable = false` + `isOutsideTouchable = true` 的 PopupWindow
  （与 `TouchEventReceiverWindow` 同一思路）——不产生新的焦点窗口，IME 不会被系统判定失焦；
  菜单内容（标题=候选词原文 + native 动作列表）在 IME 窗口内自绘，背景用主题 popupBackgroundColor；
  点项/点外部/onFinishInputView/onDetachedFromWindow 四条路径均保证 dismiss。
- **附带改进**（用户同屏反馈「缺乏自定义词组便捷管理」）：native 只在候选本身是自定义词组时
  才返回「从自定义词组中删除」动作，普通候选词此前被「置顶」过后菜单里也拿不到删除入口。
  现在菜单会查询自定义词组，命中同文本时追加「从自定义词组中删除」
  （App 层 `CustomPhraseManager.save` + `reloadPinyinCustomPhrase()`，与设置页删除同一链路）；
  点「置顶」后 Toast 提示「长按该词可删除」，形成闭环。
- **沉淀规则**：① IME 窗口内展示任何弹层，优先用不抢焦点的 PopupWindow
  （isFocusable=false），系统 PopupMenu/ListPopupWindow/AlertDialog 在 OEM ROM 上
  都可能改变 IME 窗口的焦点状态，被系统强制收起且不自动恢复；
  ② 「弹窗 dismiss 后 EditText 重新获焦」不等于「输入法恢复显示」，这两件事在
  部分 ROM 上是脱钩的，测试 IME 内弹窗必须验「键盘还在不在」；
  ③ native 层按「候选词类型」返回动作列表时，App 层若掌握额外状态（如自定义词组内容），
  应补充对应入口，而不是让用户去设置里绕。

## 56. [IME·已证实] 应用名候选「冷启动死锁」：词库里没有→选不到→学不到；用 customphrase 精确匹配 + 场景感知 order 破局（v0.0.05）
- **现象**：华为「搜索应用」界面输入「全民枪战」首字母 `qmqz`，拼音候选只有词库里的
  「全面其中 / 前面其中 / 全面群众」——应用名根本不在候选里。用户要求「刚划到就能立即输出」。
- **根因**（三层）：
  1. libime 通用词库里没有应用名（不是常用词）；
  2. fcitx5 的学习引擎是「选过才学」——候选里没有 → 永远选不到 → 永远学不到，
     **冷启动死锁**，靠时间或使用频率永远破不了；
  3. 读 native `CustomPhraseDict::lookup` 源码确认：customphrase 的匹配是
     `exactMatchSearch`——**key 必须与用户输入原文精确全等**（输入 `qm` 不命中 key=`qmqz`），
     所以给应用名造键时必须覆盖「首字母缩写 / 全拼 / 原名 / 数字+缩写」全部习惯打法
     （`AppPhraseImporter.keysForName`：`qmqz`、`quanminqiangzhan`、`4399yxh`、`saltplayer`）。
     排序键是 `CandidateOrder=pair<size_t,size_t>`（越小越前），order=1 即置顶。
- **方案决策**（排除的路线）：
  - lua trigger/cloud：是正则触发，匹配不了「静态应用名」场景，且依赖键盘内文字，排除；
  - 「等用户选过再学」：死锁本身，排除；
  - 最终路线：**把本机 launcher 应用名按拼音键写入 pinyin 自定义词组**——native 一等公民，
    输入流同步出候选、零延迟，完全满足「1 毫秒内命中」的诉求。
- **场景感知 order 双档**（避免应用名在普通聊天场景挤占常用词）：
  - 应用搜索场景（hint 含「搜索」+「应用/软件」，辅判据：宿主是桌面类包名白名单）→
    order=1 置顶，第一候选即应用名；
  - 普通场景 → order=8 靠后（第一页内不打扰），用户选过一次可去设置里手动置顶；
  - 切换时全量重写 + `reloadPinyinCustomPhrase()`（`setAddonSubConfig("pinyin","customphrase")`）。
- **实现要点**：
  1. managed 集合管理键用 `key=value` 而**不是** `serialize()`——后者含 order，场景一切换
     管理键就失配，旧条目永远剔不掉；重写时先剔除旧 managed 再追加新条目，与用户自造词组零干扰；
  2. rebuild 加 `Mutex`：`ensureImported`（onCreate）与 `onSceneChanged`（onStartInput）
     并发时 load→save 竞态会丢条目；
  3. `onSceneChanged` 不设「未导入跳过」分支——scene 未变时函数自己就是幂等的；
  4. 枚举 launcher 应用需 `<queries><intent> MAIN/LAUNCHER` 声明（API 30+ 包可见性，同 #51 家族）；
  5. 包广播（ADDED/REMOVED/REPLACED，scheme=package）3s 防抖刷新，新渲染前 cancel 旧 Job；
  6. 设置页「用户词库」屏加「应用名快捷输入」开关 + 「刷新应用名单」；管理列表过滤 managed 条目
     （几百条应用名会淹没用户自造词组）；保存时必须合并 `freshEntries()` 再落盘，
     否则会把 managed 条目全部冲掉。
- **沉淀规则**：① 「候选里没有→学不到」是学习类功能的通用死锁结构，凡用户要求
  「首次输入就要命中」的场景，必须走预写入（词库/customphrase），不能指望运行时学习；
  ② 用 customphrase 做预写入时，先读 native 确认匹配语义（精确/模糊）与排序键结构，
  键生成要覆盖用户全部习惯打法，别只造首字母；③ 「按场景变化的字段」（order）绝不能进
  管理/去重键，管理键只含不变字段；④ 弹幕式需求（「这只是举例，你应该懂得更多方法」）
  的正确解法是把通用机制（场景感知）做出来，而不是只修举例那一个词。

## 57. [逻辑·已证实] 「一次性初始化」用布尔标记判完成 = 首次失败即永久锁死（v0.0.06）
- **现象**：v0.0.05 的应用名即时候选在用户真机上完全不生效——输入 `qmqz` 从来不出「全民枪战」，
  而同一份代码在别处逻辑上"应该能工作"。设置页也无从判断到底导入了没有。
- **根因**：`AppPhraseImporter.rebuildLocked` 结尾无条件 `putBoolean(KEY_IMPORTED, true)`，
  而 `ensureImported` 开头 `if (imported) return`。枚举应用用的是
  `pm.queryIntentActivities(MAIN/LAUNCHER)`，华为 / HarmonyOS 上包可见性过滤可能返回**空列表**。
  于是「首次启动恰好读到空列表」→ 写入 0 条 → 标记已导入 → **之后再也不重试**，功能永久失效。
  这是"初始化幂等标记"写法的经典反模式：标记应在**结果成功时**置位，而不是在**尝试过后**置位。
- **修复（三道保险）**：
  1. `putBoolean(KEY_IMPORTED, fresh.isNotEmpty())` —— 空结果不算成功，下次启动继续重试；
  2. 枚举加**回退策略**：queryIntentActivities 为空时改走
     `getInstalledApplications(0)` + `getLaunchIntentForPackage()` 判定启动器图标；
  3. 清单补 `QUERY_ALL_PACKAGES` 权限兜底包可见性（输入法需知应用名属合理场景；
     即便被系统拒绝，前两级回退仍在，最坏只是功能不生效，不会崩）。
  4. 设置页新增**自检行**：直接显示「已导入 N 个应用 / M 条候选」或「读到 0 个应用（将重试）」，
     把黑盒功能变成用户可自证的状态。
- **沉淀规则**：① 任何"只做一次"的初始化（导入/迁移/预热），完成标记必须绑定**成功判据**
  （结果非空/写入条数>0），绝不能在 try 之后无条件置位；② 依赖 PackageManager 枚举的应用信息，
  在国产 ROM 上必须假设"可能返回空"并设计回退；③ 黑盒功能一定要配一个**可见的自检出口**，
  否则用户只能报"不工作"，而开发者无法区分"没执行"还是"执行了没生效"。

## 58. [能力边界·已证实] 手写识别模型的字符集里没有符号，"符号键"还走不通跨窗口 action（v0.0.06）
- **现象**：用户反馈「离线手写不支持符号，→ > ﹥ < ﹤ 《 》 等等都没有」。
- **根因**（两层，都不是"忘了加"）：
  1. **模型能力边界**：`lib/handwritten` 的 `charset.json` 共 **3755 类，符号数为 0**
     （训练自 CASIA 类 GNT 手写数据集，只有汉字 + 字母数字）。所以画一个 `→`，
     模型只能在汉字里找形近字输出——**这不是 bug，是训练集里没有负样本**，
     不重训模型就永远识别不了符号；
  2. **入口失效**：手写面板底部虽有「符号」键，但它发的是
     `KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol)` —— 跨窗口切换动作，
     而 PickerWindow 属于键盘布局，在手写窗口内该动作不被消费，表现为**点了没反应**。
- **修复**：手写字面板**内嵌**符号视图——点「符号」直接在本面板内切换为符号网格
  （复用 `PickerData.Symbol` 七个分类：数字标点 / 括号 / 箭头 / 数学 / 序号 / 其他 / 全角），
  点击即 `CommitAction` 上屏，另有「返回手写」；不依赖任何跨窗口 action。
  同时补全用户点名的 `﹤ ﹥`（U+FE64/FE65 全角小于大于，此前各分类均缺）。
- **沉淀规则**：① 引入第三方识别模型前，先**读它的字符集/标签表**确认能力边界，
  把"模型不认识的东西"做成**显式入口**（按钮/面板），而不是让用户去写然后得到一个形近错字；
  ② 面板内的功能键不要依赖跨窗口 action，跨窗口动作在"非其宿主窗口"里往往被静默丢弃，
  要么在本窗口内自实现，要么确认广播路径真的存在；③ 用户列举符号时往往包含
  `﹤﹥` 这类全角变体，补符号要连变体一起补。

## 59. [布局·已证实] 手写板继承键盘高度 = 用户把键盘调矮后手写只剩一条缝（v0.0.07）
- **现象**：用户截图反馈「手写区域太小」——华为搜索应用界面里手写板被压成约 76dp 的横条，
  笔画根本写不开。
- **根因**（两层叠加）：
  1. 面板内部分配失衡：标题栏「高手手写」独占一行、候选条 72dp、提示行 22dp，
     三者吃掉大半高度，板子只拿剩余 weight；
  2. **全局高度继承**：`InputView.updateKeyboardSize` 把「键盘高度百分比」（按打字需求设计，
     用户往往调到 ~30% 屏高）无差别应用到所有窗口——手写需要的空间是打字的近两倍。
- **修复**：① `showTitle=false`（标题不值一行）；候选 72→50dp、提示 22→14dp；
  板子 `minimumHeight=140dp` 兜底；② **手写模式动态加高**——InputWindowManager 暴露
  `isHandwritingActive` 与 `onWindowSwitched` 回调，InputView 在手写激活时取
  `max(用户设置高度, 52% 屏高)`，切回键盘恢复用户设置，互不影响。
- **沉淀规则**：① 「按用途分高度」是 IME 面板的通用需求（手写/剪贴板/表情面板都比键盘需要更大空间），
  全局单值高度必然顾此失彼；改法是在窗口管理器暴露「当前窗口类型 + 切换回调」，
  让高度计算按窗口类型取 max(用户偏好, 该类型最低值)；② 面板内布局优化前先量用户截图里
  各区域实际占比，往往「最没信息量的区域」占着最多像素。

## 60. [窗口·已证实] IME 窗口内弹「可抢焦点的独立窗口」→ OEM ROM 强制收起输入法（v0.0.09）
- **现象**：用户回归测试报两个异常——① 点按剪贴板删除（清空）键，输入法自动收起；
  ② 长按中英文切换键，输入法自动收起。两者都无崩溃、无报错，键盘就是"自己不在了"。
- **根因**（与 #55 完全同源，只是漏改了另外两处调用点）：
  1. 剪贴板两处菜单用的都是系统 `PopupMenu`——条目长按菜单（置顶/编辑/分享/**删除**）
  和工具栏清空确认。PopupMenu 内部是可**抢焦点**的 ListPopupWindow 独立窗口；
  2. 长按中英文切换键走 `ShowInputMethodPickerAction` → `service.showDialog()` 托管的
     AlertDialog——同样是可抢焦点的独立窗口（只加了 `FLAG_ALT_FOCUSABLE_IM`，
     没有 `FLAG_NOT_FOCUSABLE`）。
  在华为等 OEM ROM 上，IME 窗口内出现抢焦点的独立窗口会被系统判定「输入法已失焦」
  而**强制收起输入法**；窗口消失后焦点回到 Editor，IME 却不恢复。
  v0.0.05 只修了候选词长按菜单（#55），剪贴板与输入法选择两条路径一直带着病根。
- **修复**（两类手法，按场景选用）：
  1. **菜单类**（剪贴板两处）：改用 `ImeMenuPopup`——`isFocusable=false` 的 PopupWindow，
     不产生新焦点窗口；支持可选标题/图标、点外部消失、锚点下方空间不足时自动上翻
     （IME 面板只占屏幕下半，PopupWindow 不会自行翻转，底部条目菜单会被裁切）。
     BaseInputView 的候选操作菜单同步重构为复用同一组件（行为不变）。
     弹窗在极端 OEM 上失败时保留 AlertDialog 降级路径。
  2. **选择器类**（输入法选择）：改为面板内窗口 `InputMethodPickerWindow`
     （`ExtendedInputWindow`，复用 InputMethodData/InputMethodListAdapter/
     SingleDividerDecoration）——**零新窗口**，系统无从判定失焦；标题栏自带返回按钮
     回键盘；切换行为与原对话框一致（fcitx 子输入法 activateIme，其它 App switchInputMethod）。
     旧 `InputMethodPickerDialog` 删除。
- **沉淀规则**：① IME 里任何"弹个窗"的需求，第一个问题必须是「这会不会产生一个
  可抢焦点的窗口」——PopupMenu、AlertDialog、系统 Dialog 全是，中招了就是
  「键盘莫名收起」且无任何日志；② 首选解法是**面板化**（做成 InputWindow，零新窗口），
  次选是非焦点 PopupWindow；`FLAG_ALT_FOCUSABLE_IM` ≠ `FLAG_NOT_FOCUSABLE`，
  前者只调与 IME 的层叠关系，窗口照样抢焦点；③ 修一类窗口 bug 要**枚举全部调用点**——
  #55 修的是候选菜单，剪贴板两条路径和输入法选择器三条路径都是同一病根的漏网之鱼，
  靠用户回归测试一条条撞出来成本太高。

## 61. [IME·性能+同源·已证实] 「不能出现卡顿」专项：手写引擎单例化 + 两处 #55/#60 最后漏网 + 识别异常路径位图泄漏（v0.0.10）

- **背景**：用户报回「优化代码执行效率，不能出现卡顿，期间检查同源和不同源 bug」。
  对 MBM 自有代码（342 个 Kotlin 文件中筛选出的热路径）做了一次全量扫描，
  命中 4 处卡顿、2 处同源漏网、1 处不同源泄漏。
- **卡顿①（主线程阻塞，最严重）**：`HandwritingWindow.onDetached` 里
  `runBlocking(Dispatchers.Default) { recognizer.close() }` —— onDetached 跑在主线程，
  runBlocking 让主线程**干等 NCNN 关库**（释放模型，几十~几百毫秒），
  每次从手写切回键盘都掉帧甚至 ANR 风险。
- **卡顿②（重复加载）**：识别器每次打开手写面板都 `new HandwrittenRecognizer`
  （NCNN 载库 + 模型反序列化几百毫秒），关闭即销毁——用户每次切入手写都要等模型。
- **卡顿③（主线程构建）**：符号面板每次点「符号」都重建 ~100 个 Button。
- **卡顿④（主线程 IPC）**：`InputMethodPickerWindow.onAttached` 的
  `InputMethodData.resolve`（IMMS `enabledInputMethodList` + `loadLabel`）
  直接跑在 Main 线程——长按切换键后面板打开的瞬间阻塞。
- **同源漏网①**：`StatusAreaWindow` 状态区子菜单一直用系统 `PopupMenu`
  （内部可抢焦点的 ListPopupWindow）——#55 修候选菜单、#60 修剪贴板+输入法选择时
  漏掉的第四条路径，点状态区带子菜单的条目同样会「键盘莫名收起」。
- **同源漏网②**：只启用一个输入法时，长按中英文切换键弹
  `AddMoreInputMethodsPrompt`（`service.showDialog` 托管的 AlertDialog，
  只加了 `FLAG_ALT_FOCUSABLE_IM`，窗口照样抢焦点，见 #60 对两个 flag 的辨析）。
- **不同源 bug**：`HandwritingWindow.recognize()` 的成功路径回收了快照位图，
  但 `rec.recognize()` 抛异常时 `runCatching` 直接吞掉、**位图不回收**——
  每写一笔泄漏一张全幅位图。`HandwritingActivity`（v0.0.08）同一逻辑有 finally，
  此入口漏掉——#52「一个入口修了不等于修了」的同源教训再次应验。
- **修复**：
  1. `HandwrittenRecognizer` 改**进程级单例**（`get(context)` 双检锁，
     applicationContext 防泄漏）：模型加载一次常驻，与 fcitx daemon 同生命周期；
     `onDetached` 只取消任务不再 close；`onAttached` 后台预热（词库+引擎），
     用户写第一笔时识别即可开始。Keyboard 内面板与全屏 Activity 共用同一实例。
  2. 符号网格按分类缓存视图树（`symbolViews`），切换分类零 Button 创建。
  3. `InputMethodData.resolve` / `AppPhraseImporter.onSceneChanged|ensureImported`
     挪 `Dispatchers.IO`（含 SharedPreferences 首访读盘）。
  4. 状态区子菜单、单输入法提示均改 `ImeMenuPopup`（后者用新增的 `showBottom`
     贴面板底部——KeyActionListener 没有按键视图锚点，这是调用方拿不到 anchor 的
     标准姿势；`AddMoreInputMethodsPrompt` 随之删除）。
  5. `recognize()` 改 try/finally 回收快照位图，异常路径一并覆盖。
- **沉淀规则**：① `onDetached/onDestroy` 里出现 `runBlocking` = 主线程卡顿告警，
  凡是「释放资源」一律扔后台协程，别让用户等一个看不见的 close；② 重量级单例
  （NCNN 引擎/模型）要么常驻不关、要么引用计数，「每次用每次建」在几百毫秒级
  初始化面前就是每次交互都卡一下；③ 同源 bug 的枚举要按**调用点**穷举而不是
  按文件——#55/#60 之后剩下的两个调用点分散在 status 包和 keyboard 包里，
  与已修文件毫无关系；④ 资源释放（recycle/close）必须写 finally，
  `runCatching { ...; release() }` 的成功路径回收等于没修（#52 的推论）。

## 62. [识别模型·已证实] 真 bug 双发（字符表 null NPE + 智能词库无锁数据竞争）+ OCR 模型 PP-OCRv4→PP-OCRv6 升级（v0.0.11）

- **背景**：用户要求「修复真 bug 并且更新最新版本的识别模型」。本轮对识别链路与
  本地词库持久化做了一次定向扫描，并把手写/OCR 两侧模型都核实到上游最新版。
- **真 bug①（换模型必触发的潜伏 NPE）**：`HCCRRecognizer.predict` 里
  `idxToChar[index]` —— `loadCharset` 按 `num_classes` 分配数组，稀疏
  `char_to_idx` 会留下 null 空洞；本地字表恰好完好（3755 类连续）所以潜伏，
  一旦更换字符表格式不同的模型，`text` 为 null，下游 `HandwritingWindow`
  的 `candidate.text[0]` 立即 NPE。修：取出时一并判 null，兜底 `"?"`。
- **真 bug②（不可逆数据丢失）**：`SmartDictManager.recordUsage/removeWord/clearUsage`
  全是「load()→改→save()」读-改-写且无任何同步，而 `commitText` 每次提交都
  `imeFallbackScope.launch(Dispatchers.IO)` 并发调用。两个后果：
  ① 并发提交互相覆盖丢统计；② 并发 `writeText` 同一文件可交叉写出损坏 JSON，
  此后 `load()` 解析失败返回**空表**，`save()` 直接覆写——用户全部使用统计
  被一次普通提交清空且不可恢复。修：写操作 `Mutex.withLock` 串行化；
  `save` 改临时文件 + 原子重命名（防半写文件）；`load` 解析失败时把损坏文件
  备份为 `.corrupt` 再返回空表（保留现场，绝不把损坏文件当空表覆写）。
- **模型侧结论**：
  - 手写模型（Ismantic/Handwritten）经 GitHub Release 下载 `handwritten-model-0.1.1.tar.zst`
    并与本地 `lib/handwritten/src/main/assets/` 三件套逐字节 SHA256 比对——
    **本地已是最新 v0.1.1**，无需更换（此前「模型可能过时」的疑虑排除）。
  - OCR 模型升级 PP-OCRv4(mobile) → **PP-OCRv6(small)**（2026-06-11 随
    PaddleOCR 3.7.0 发布，small 档 7.7M 参数为官方移动端档位；资产走
    RapidAI/RapidOCR v3.10.0 ModelScope 分发，三件 SHA256 与官方清单逐一核验）。
- **v6 兼容性核实（升级前逐项实测，防「静默失效」）**：
  1. 输入名仍为 `x`，输出按位置取 → 会话代码不变；
  2. det 输出为**全分辨率概率图**（736×736 输入→736×736 输出，#27 的结论对 v6 仍成立）；
  3. rec 输出类数 18710 = 字典 18708 + blank + 空格 → CTC 解码逻辑原样兼容；
  4. rec 预处理（48 高/动态宽/0.5/BGR）与 v4 完全一致；宽度轴实测动态（8~960 均正常）；
  5. **det 归一化必须从 v4 的 ImageNet 统计量改为 0.5/0.5**——自 PP-OCRv5 起官方
     det 预处理即 0.5/0.5（RapidOCR v3.10 `config.yaml` 实证），不随模型改会
     让概率图整体偏移、文字框找不到（#24/#25 同类静默失效）；
  6. cls 方向分类无 v6 独立资产（RapidOCR v6 路由亦无 cls），沿用 v4 cls 模型。
- **端到端实证**：Python 侧完整复刻本引擎管线（BGR 平面布局、0.5/0.5、全分辨率
  概率图、动态宽 rec、CTC 字典映射）跑渲染测试图，中文三行
  「识别模型测试 / 版本 v6 / small」全部正确识别（置信度 0.92~0.998）。
- **附带**：`extractModels` 增加过期模型清理（换模型时删掉 filesDir 里旧 .onnx，
  单个 10MB+，不清永久占用户存储）；`app` 模块 16MB 死 OCR 资产移除
  （全仓库无引用，OCR 只在 recall 插件）。
- **沉淀规则**：① 换识别模型 = 换**整套前后处理假设**，升级前必须逐项核实
  （输入输出名/图形状/归一化/字典索引映射），任一假设漂移都是静默失效；
  归一化参数跟着模型版本走，不能想当然沿旧值；② 「读-改-写」持久化无锁 +
  并发调用方 = 丢数据，且失败模式往往是**灾难性的**（解析失败→空表→覆写），
  写文件一律临时文件 + 原子重命名，解析失败保留现场；③ 数组查表拿到的是
  「可能为 null 的引用」时，消费端（`text[0]`）远在天边，生产端（查表处）
  就要兜底。

## 63. [OCR·已证实] 旋转文本识别恒空 + 宽间隙拆框：orderPoints 角点错乱 / warpCrop 差值算宽 / det 同行不合并（v0.001 → v0.001.1）
- **现象**：用户实报「识别不到云·战双帕弥什，拆分有问题」。该游戏 logo 为倾斜
  10-15° 艺术字，RecallService 截图 OCR 结果恒为 `''`（score 0.000）；
  「·」与两侧间隙 ≥ 0.7× 字高时 det 拆成「云·」+「战双帕弥什」两框，
  按行 join 后词组截断。
- **根因**（三个真 bug，真实 PP-OCRv6 模型本地复现存证）：
  ① `orderPoints` 按 y/x 排序在旋转角 × 框宽 > 框高（宽扁文本约 > 6.5°）时
  把底左角误判为顶角，角点顺序错乱；② `warpCrop` 用轴对齐 x/y 差值算裁剪
  尺寸，8° 时 648px 文本宽实算成 21px，裁剪出细条、rec 返回空；③ det 无
  同行合并，宽间隙词组拆两框。三者均只在旋转/宽间隙场景触发，正场景全对，
  故 v0.001 常规测试未暴露。
- **修复**（commit `483ff4a`，`OcrEngine.kt` v1.0.28）：① orderPoints 改极角
  排序（绕质心 atan2 + min(x+y) 起点）；② warpCrop 宽/高改欧氏边长之和；
  ③ detect 输出后新增保守同行合并（中心连线 < 45° + 间隙 < 0.6× 字高，
  双行不误合并）。附带收益：合并后框更宽，rec 由「战双帕弥件」提升为完整
  正确的「云·战双帕弥什」。
- **回归资产**：`docs/ocr-regression/`（Python 管线 1:1 移植 + 验证矩阵，
  依赖真实模型）。教训：**旋转文本必须进 OCR 回归集**——正场景全对的管线
  在 6.5° 倾角上即整体失效，而游戏/艺术字场景倾斜是常态。

## 64. [OCR·已证实] 同源缺陷第二轮：竖排识别盲区 + 同行合并误合并风险（v0.001.1 → v0.001.2）
- **现象**：26 场景系统性扫描（拆分类/识别类/旋转组合类）发现两类新缺陷：
  ① 竖排文本（游戏古风 UI / 竖排标题）识别结果恒为空；② 水平错开的紧凑
  双行（对话 UI 长短不一/缩进）被同行合并判据误并成一行，rec 输出乱码。
- **根因**：① cls 方向分类只纠正 180°，竖排（高 > 2× 宽） crop 直接送 rec
  必然为空——识别盲区，非精度问题；② 同行合并原判据（中心连线 < 45° +
  水平间隙 < 0.6× 字高）只防了「水平对齐的双行」（中心连线垂直被拦），
  水平错开时中心连线接近水平且间隙小，判据失效。
- **修复**（OcrEngine.kt v1.0.29）：① recognizeCrop 对竖排 crop 双向 rot90
  取高分（实测竖排「以弥止争」原样为空 → rot90 后 1.00）；② 合并判据新增
  垂直投影重叠率 > 0.8 条件（以框自身文字方向为基准轴，旋转鲁棒）——
  同一行拆开的框重叠率 ~1.0，紧凑双行 < 0.35，构造复现验证拦截有效。
- **扫描确认的模型极限（记录不修）**：低对比度/紧凑场景偶发「文字→文学」
  形近字（句子相关，同句「文字输入」即对）；超长词（32 字）末端形近字与
  丢字（rec 宽度上限 960→2560 结果相同，分段识别同样错，非截断）；
  描边艺术字「·→・」（U+00B7/U+30FB 混淆，不影响拼音键匹配）。
- **回归资产**：`docs/ocr-regression/`（fix_pipeline.py 完整修复版 +
  scan_bugs.py 26 场景扫描）。教训：**合并/拆分类判据必须构造「水平错开」
  变体**——水平对齐场景全对的判据，在错开形态下直接失效。

## 65. [OCR·已证实] 同源缺陷第三轮：det 重叠框未 NMS + 旋转门控缺失 + IME 词典未用（v0.001.2 → v0.001.3）
- **现象**：30 场景系统性扫描（docs/ocr-regression/scan_bugs.py）复核发现三处可继续提升：
  ① det 后无 NMS，DB 在密集处对一行文本输出多个重叠框 → 重复行/拼接乱码
  （「拆分」类问题的真源头之一）；② 旋转处理仅 cls 180° + 竖排特例，cls 判错或
  文本呈 90°/270° 任意朝向时漏识；③ OCR 直接喂 fcitx5 输入法这一闭环优势完全未用，
  游戏/UI 术语的形近错字（如「战双帕弥件」）无人纠正。
- **修复**（OcrEngine.kt v1.0.30，复用真实 PP-OCRv6 模型 + fix_pipeline.py v1.0.30）：
  ① det 后加 IoU-NMS（凸四边形 Sutherland-Hodgman 精确求交，阈值仅抑制高度重叠
  的重复框，相邻同行框 IoU 低不被误删）；② recognizeCrop 改四向取最高分（base 含
  cls 180° + rot90 + rot270），**不做「base 高置信即采信」短路**——模型会「自信地错」
  （实测某 90° 文本 base 给「原神」0.9998、正确朝向 rot270 仅 1.000，靠 max 而非
  阈值选对）；③ 新增 IME 闭环术语词典后校正：字符级编辑距离对游戏名纠错
  （「战双帕弥件」→「战双帕弥什」），词典见 assets/ocr/ocr_corrections.txt（用户可增删）。
  校正安全约束：仅中等置信、与术语长度差 ≤1 且字符距离随长度缩放（每 3 字容 1 错），
  防「方舟→明日方舟」「战斗→原神」类误伤。
- **验证**：30 场景扫描 22 通过 / 8 失败，8 个失败均为已知模型形近字极限
  （「文字→文学」「小字→小学」「露西亚→露西业」），NMS 与词典校正**零回归**；
  test_round3.py 单测覆盖 NMS 抑制、词典校正（含「自信地错」「短词误伤」反例）、
  90° 门控旋转回退。教训：**「模型自信地错」不能用置信阈值短路**，必须靠多假设
  max；IME 闭环的词典纠错是本项目独有、通用 OCR 不具备的提准手段。

## 66. [OCR·已证实] det 后处理顺序 A/B 实验（merge→nms vs nms→merge，NMS 在本套件空转）+ Fix-C 镜像 warpCrop 取模→clamp 对齐（v0.001.3 后审计）
- **背景**：v1.0.30 在 `OcrEngine.kt:206` 写成 `nmsBoxes(mergeSameRowBoxes(detect(src)))`（**先合并后 NMS**），但文件头旧注释写成「先 NMS 后合并」，顺序与注释相反。第三方红队曾主张「NMS 才是 v1.0.30 的核心修复，应把 NMS 提到 merge 之前（候选1 nms→merge）」，并误判 rec score 是 logit、0.4 门槛形同虚设——该结论已被真实模型实测证伪：rec 图已含 Softmax，输出 shape `(1,18,18710)`、每步各类概率和 = 1.0000，score 确为 [0,1] 概率，非 logit。
- **A/B 实验设计**（Kotlin 一行不动，Python 镜像加 `OCR_ORDER` 开关，真实 PP-OCRv6 模型跑 30 场景两遍）：
  - (a) `merge_nms`（现状，= Kotlin v1.0.30 顺序）：detect → 同行合并 → NMS
  - (b) `nms_merge`（候选1）：detect → NMS → 同行合并
  - 每场景记录 NMS 实际抑制框数（总计 + 逐场景）。判据（用户 Step 1 反馈）：(b)pass ≥ (a)pass **且** (a)抑制数 > 0 → 候选1 可提 Kotlin；否则采纳候选2（只改注释）。
- **实验结果**：(a) pass 22 / fail 8，NMS 总抑制框数 = 0；(b) pass 22 / fail 8，NMS 总抑制框数 = 0；pass 差值 (b−a) = 0。条件1 满足、条件2 不满足 → **采纳候选2（只改注释，Kotlin 顺序不动）**。
- **专项结论**：现状顺序下 NMS 抑制 0 框，对「DB 重叠重复框」完全不起作用；且候选顺序下 NMS 同样抑制 0 框——即 30 个合成场景里根本没有 DB 重叠重复框可抑制。**v1.0.30 引入 NMS 的立论前提（DB 密集处输出重叠框）未被本 30 场景合成套件复现**；NMS 的 IoU 逻辑有单测覆盖、正确，只是未被端到端触发。真实密集游戏截图是否会触发 DB 重叠框 = **不确定，需真机验证**。候选1（重排顺序）在此数据下无可抑制对象、无收益，不采纳。
- **Fix-C 对齐**：Python 镜像 `ppocr_pipeline.py` 的 `warp_crop` 原用 `% src.width` 取模回绕（右/下边缘把 x0 回绕到 0，引入边缘伪影），与 Kotlin `OcrEngine.kt:957-960` 的 `coerceIn(0, srcW-1)` clamp 不一致；已改为 clamp 对齐。**注意**：该函数被 `fix_pipeline.warp_crop_fixed`（本就 clamp）遮蔽，对走 fix_pipeline 的 30 场景零行为影响。Fix-C 仅改 Python 镜像 + 文档记录，未动 Kotlin。
- **本轮改动清单**（与源码事实无冲突，未停手）：
  - Fix-B：Python `fix_pipeline.py` 删除 :139 死代码 `recognize_crop_vertical`（第一版无 robust；grep 全仓确认无 Kotlin/跨文件调用者，仅 :250 定义 + :346 调用为活函数）；修正 :230 过期注释（「≥0.7 直接采用」短路 →「无高置信短路」）；补 2 条 `test_round3` 用例覆盖 score<0.4 不校正，`test_round3.py` 全绿（共 10 条）。
  - Fix-A 候选2：仅改 `OcrEngine.kt:203-205` 注释，对齐真实代码（先合并后 NMS）+ NMS 用途说明，代码一行不动。
  - Fix-C：仅改 Python 镜像 clamp，文档记录差异，未动 Kotlin。
  - 新增 `ab_order_test.py` + `ab_order_result.json`（A/B 实验可复跑）。
- **验证**：30 场景两序皆 22/8（与 debug v0.001.3 一致）；`test_round3.py` 单测全绿（含 score<0.4 两例）；`py_compile` 全部通过。
- **需真机验证**：① 真实密集游戏截图是否会触发 DB 重叠框（决定 NMS 是否有意义、顺序是否要重排）；② Fix-A/Fix-C 落地真机后，镜像与 Kotlin 的 warpCrop 边缘 1px 等细微差异。
- **release 口径**：本轮 release 包不含 Fix-A/Fix-C 任何 Kotlin 行为改动（`OcrEngine.kt` 仅一行注释）；release 与 debug 行为一致（已确认 `isMinifyEnabled=false` / `isShrinkResources=false`、ABI 仅 arm64-v8a + universal、签名 `fromProjectEnv`），30 场景验收 = 22/8 与 v0.001.3 完全一致；构建配置类静默失效风险不存在，但仍建议拆 APK 确认 `assets/ocr/` 仍在。

## 67. [OCR·baseline] 图标误检 + 误识：新缺陷类，合成图 baseline 已跑，方案待真机校准（v0.001.3 后）
- **触发**：用户真机截图 OCR 输出（2026-10-25，见 `docs/ocr-regression/icon_real_samples.md`）——
  MBM 设置页菜单项文字基本全对，但**每行前缀图标被 det 检出成文本框、rec 误识为单字**
  （心/C/唱/田/自/云/8/●。/0），顶部状态栏被整体卷入成 `0N谷100` / `03:39`。
  与 #65 的「DB 重叠重复框」是**两码事**（那个是同一行重复文字，这个是图标当文字），
  故第 66 条的 A/B 实验跑不出来很正常——它压根不在合成集里。
- **回归盲区**：原 30 场景全是纯文字合成图，图标场景**零覆盖**。按用户门禁，
  **先补用例跑 baseline，再谈方案**（无 baseline 就无「修好了」）。
- **baseline 数据**（`icon_baseline.py`，GT 驱动分类；⚠️ **合成图，非真机截图**）：
  - 图标被误识框 score ∈ **[0.186, 0.7335]**（n=9）；真实文字框 score ∈ **[0.8761, 1.0]**（n=36）
    → **区间重叠 0.0000，置信可分**（间隙 0.1426）。方向性支持 **A（置信阈值）作主判据**。
  - **B（几何）不可分**：真实单字宽高比 0.882~1.46，与图标 0.585~1.956 完全重叠——
    实测误杀清单 5 个真单字（置/帮/助/是/否，score 均 ≥0.9968）。印证「B 只能作辅助」。
  - **状态栏 score ≈ 1.0（8 个框中 7 个 ≥0.9）→ 置信过滤对它完全无效，只能按区域排除。**
  - 另有 4 个图标框 rec 输出空串（无害，不需过滤）；`Recall屏幂记忆`（0.8872）与
    竖排「以弥止争→以弥山」属 rec 形近字极限，非图标问题。
- **待办（下一轮，未做）**：① 用户提供真机截图 PNG 到 `fixtures/real/`（设置菜单、
  带开关行、单字标签行、竖排单字）→ 重跑校准 score 分布与阈值；② 任何过滤必须
  **阈值可配置 + 记录被丢弃框（score/几何/原文），禁止静默丢弃**；③ 状态栏区域排除
  的实现位置与代价已单独评估（`status_bar_exclusion.md`，倾向 `RecallService.kt:303-310`，
  最大风险是「`takeScreenshot` 是否已含状态栏」未验证，切错即误杀首行内容）。
- **教训**：**「纯文字合成图」的回归集对真实 UI 截图有系统性盲区**——图标、状态栏、
  开关、单字标签这些 UI 骨架元素全不在覆盖范围内。回归集必须包含真实 UI 截图；
  且分类必须用 ground truth，不能用启发式（第一版用 `near_text` 启发式把真单字
  「设」「否」错分成图标嫌疑，导致可分性判定失真，已废弃）。

## 68. [OCR·长文本] 「字数无上限」验证：拆分本身无上限，唯一真实瓶颈是 MAX_TEXT_LENGTH=4000 静默截断（v0.001.3 后）
- **触发**：用户要求「拆分字数无上限，不要像某输入法 5000 字以上就不拆分」，并要求
  静态模拟一段 3670 字测试文字（`fixtures/real/user_test_text.txt`）在屏幕上的识别与拆分表现。
- **源码级上限清点**（4 个，全部只读验证属实）：
  - `RecallService.kt:93 MAX_TEXT_LENGTH=4000` → `:212`（NODE）与 `:317`（OCR）的 `text.take(4000)`
    ——**静默截断**，超长部分直接丢弃、无日志无计数。这是「5000 字以上不拆分」的同构问题，MBM 阈值是 4000。
  - `OcrEngine.kt:108 REC_WIDTH_MAX=960` → `:794 targetW = min(960, ...)`，单框超宽时**压缩**而非拆分。
  - `OcrEngine.kt:98 DET_MAX_SIDE=1280` → det 前长边等比缩小。
  - `OcrEngine.kt:104 DET_MAX_CANDIDATES=1000` → 每屏约 37 行，远离上限。
- **静态模拟结论**（`longtext_sim.py`，⚠️ 合成图非真机截图）：
  - 模式 A（单张 1080×8388，det 缩放 0.153，字仅 5.5px）：覆盖率 **91.96%**，112 行。
  - 模式 B（4 屏拼接，真实 Recall 形态，每屏缩放 0.539，字约 19.4px）：覆盖率 **90.17%**，117 行。
  - **漏字成分**：324 个漏字中 301 个（93%）是非汉字（markdown 符号/英文/数字），汉字仅 23 个（0.70%）
    → **中文正文基本完整，丢失的几乎全是取词场景本就要清洗的符号**。
  - **拆分能力本身没有字数上限**：det 不限行数、merge/NMS 不限行数。长文本靠多屏累积天然覆盖。
- **反直觉点**：模式 A（缩放更狠）反而覆盖率更高。根因是模式 B 每屏独立 OCR，
  屏边缘行 det 检出弱 + 相邻屏重叠区重复行未去净（117 > 112）。**「分屏更清晰」的直觉不一定成立，
  切片重叠与去重策略本身会影响结果**；真实 Recall 是分屏的，故 90% 是更贴近实际的估计。
- **待确认（需真机/需用户决策，未做）**：① `MAX_TEXT_LENGTH` 目标值与可观测化（禁止静默）；
  ② 超长不换行行（最长自然行 693 字）是否需要在 det 后按宽度**主动二次切分**再送 rec——
  这才是把「压缩」改成「拆分」的真正「拆分无上限」；③ 真机逐屏实际高度/DPI/字号与假设的差距。
- **教训**：**判断「能否完全识别」不能只看总覆盖率**——93% 的漏字是非汉字符号时，
  对中文正文场景的实际质量远好于覆盖率数字本身。必须做漏字成分拆分（汉字 vs 符号）再下结论。

## 69. [设计] 「无上限」审计：四个无上限各有真实死因， redesign 方案已定稿（docs/design/unlimited-clipboard-design.md）
- **触发**：用户要求剪贴板/历史/词库/拆分全部无上限（100MB 文字级），
  「不允许以实现困难为由逃避目标」。先审计再设计，全部瓶颈定位到行号：
  - **100MB 单条死因 = CursorWindow 2MB**：`ClipboardEntry.text` / `RecallRecord.text`
    单列 String，查询返回行 > ~1MB 即崩。→ 解：大文本外置 blob 分片文件，DB 行只存
    sha256/preview/byte_len（`docs/design/...md` §3.1）。Binder ~1MB 是第二道平台边界
    （`setPrimaryClip`/`commitText`），绕行 = 分块 commit + FileProvider URI 通道。
  - **历史无上限死因 = 全量 Flow + 全量清理**：`RecallDao.all(): Flow<List<RecallRecord>>`
    无分页，一发射整表进内存；`ClipboardManager.removeOutdated()` 每次插入都
    `getAllUnpinned()` 全量拉内存。→ 解：keyset 分页 + SQL 化清理。
  - **词库无上限死因 = 全量内存词典**：`OcrEngine.kt:153 readLines()` 全量 +
    每行×每词 Levenshtein；`CustomPhraseManager` JNI 全量 Array。→ 解：长度分桶 +
    前缀索引 + mmap + 三重剪枝；JNI 改分页。
  - **拆分无上限死因 = 静默 take + 压缩不拆分**：`RecallService.kt:93 MAX_TEXT_LENGTH=4000`
    两处 `take()` 静默丢尾；`OcrEngine.kt:794 REC_WIDTH_MAX=960` 超宽行压缩约 18%。
    → 解：删 take（改可观测偏好）+ det 后按宽二次切分再送 rec。
- **语义陷阱（最易踩）**：`clipboardHistoryLimit` 的 `0` 旧语义是「**删全部未置顶**」
  （`ClipboardManager.kt:269-270` 注释明写），不是无上限。要做「无上限」必须重定义
  0 = 不清理，并处理旧值迁移（首次启动检测提示）。v1.0.23 已移除 Recall DAO 的
  LIMIT 300，但主输入法侧默认 10 未动，且 `trim(…, 30)`（`RecallService.kt:137`）
  硬编码 30 天、`keepDays` 偏好从未被 service 读取——「无上限」在两条链路上都只做了一半。
- **设计四约束（C1~C4）**：无硬编码容量上限 / 全路径流式 / 限制可配置可观测禁止静默 /
  平台边界给绕行不给否决。分期 P0（删雷，可独立交付）→ P1（分页+FTS）→ P2（blob 外置）
  → P3（词库索引）→ P4（二次切分）。
- **本轮纯设计，零代码改动**；不涉及 v1.0.30 release。

## 70. [实现] 「无上限」落地：100MB 粘贴全链路 + 四个新坑（v1.0.31）
- **触发**：用户「复制 100MB → 粘贴出完整 100MB 原文，不允许逃避目标」。
  落地 = app 侧 `ClipboardBlobStore`（blob 外置）+ `ClipboardPasteChunker`
  （256KB 分块 commit + UTF-8 carry）+ `ClipboardImportActivity`（文件导入通道）
  + recall 侧同构改造（blob / keyset 分页 / 删 take / 拆 PHRASE_HARD_LIMIT）。
  详见 `docs/testing/unlimited-clipboard-100mb-test-guide.md`。
- **坑 1（Room）**：`exportSchema = false` 时声明 `AutoMigration` 直接 KSP 报错
  （"Cannot create auto migrations when exportSchema is false"）；补 `exportSchema = true`
  后又缺 v1 schema JSON（历史从未导出过，补不出来）→ **改手动 Migration 1→2**
  （四条 ADD COLUMN，默认值与注解一致，旧行 codec=0 语义不变）。
  教训：给「从没导出过 schema」的老库加列，手动迁移比 AutoMigration 靠谱。
- **坑 2（UTF-8 分块）**：分块核心里 `if (tail >= total) tail = 0` 的「防死循环」守卫，
  在 chunkSize < 4 时会把「整块都是不完整字符」强行当完整解码，**切出替换符 �**。
  正确做法是删守卫：atEof=false 时 read==chunkSize≥4 > 3 ≥ tail，天然不可能
  tail==total；chunkSize<4 时整体 carry 前移、下轮补读即可（每轮至少读 1 字节，不死循环）。
  单测用 size=1/2/3 的块专门砸这个边界。
- **坑 3（协程）**：分块回调是普通 lambda（非 suspend），里面直接调
  `suspend fun pasteToast(...)` 编译不过 → 进度 toast 改 `this.launch { ... }`
  子协程发（挂在 pasteJob 下，取消可传播）。
- **坑 4（可测性）**：`object` 存储类直接读 `appContext.filesDir`，JVM 单测里
  appContext 未初始化必炸。把核心改成「显式传 dir」的 internal 函数
  （`writeTo/openIn/deleteIn/totalBytesIn/...`），公开方法只做 appContext 委托，
  单测传 TemporaryFolder 即可全覆盖（含 100MB 规模）。
- **连带**：`RecallActivity` 三条 `record.text` 路径（导出/复制到剪贴板/提炼词组）
  在 blob 化后都只拿到 preview → 改流式读全文；超过剪贴板事务上限（~900KB）时
  不截断，改缓存文件 + 系统分享面板。

## 71. [实现] 「词库大小无上限」：三层落地与一处语义细化（v1.0.31）
- **触发**：「其他要求也要满足无上限」里的词库项。落地三层：
  1. **自定义词组（用户词典）去裁剪**：`PinyinCustomPhraseFragment.saveConfig()` 原在
     估算 >100MB 时从尾部**静默丢词**（v1.0.19 的防崩裁剪）。删除裁剪，估算值降级为
     **告警阈值**（`MAX_STORE_BYTES` → `STORE_WARN_BYTES`）；保存包 runCatching，
     失败 toast 精确到「N 条 / 约 X MB」且**不 reset dirty**（留在页面里的内容下次
     onStop 自动重试）——可观测、不丢数据（C3）。
  2. **大词库免重复全量 JNI load**：`CustomPhraseManager` 加进程内快照
     （loadCached/saveCached/invalidateCache）。IME 服务与设置页同进程，保存即同步；
     此前每次长按候选菜单都 `load()` 全量大数组，百万级词库不可用。
  3. **OCR 术语词典长度分桶**：`correctWithLexicon` 原 O(N·L²) 全扫，用户词典扩到
     百万级时每行识别都要秒级。改 `lexiconByLen` 三桶扫描（长度差 ≤1 才可能通过
     安全约束）。**语义细化（有意为之）**：旧版全扫中远长度词唯一作用是「以更小
     距离抢占最佳位再被长度条件拒绝」= 制造漏校正；分桶后 = 「最近的近长度词足够
     近才校正」，与防误伤意图一致。平票按文件序，与旧版先者胜一致。
- **边界（不逃避也不隐瞒）**：native load/save 在预编译 .so 里，无法改分页/JNI，
  词库「无上限」的上限是设备内存与文件系统——这属于平台边界，绕行（缓存/分桶/
  告警）已做，剩余部分在设计文档 P3 分期里继续（mmap/前缀索引待 native 开放）。

## 72. [CRASH·真机反馈] v1.0.31 装新手写全废：符号级定位（⚠️ 根因归因已被 #73 更正，API 33 之说不成立；设备侧真实原因待真机日志）
- **现象（真机反馈，v1.0.31）**：安装「无上限」新版 APK 后，键盘内手写面板 /
  设置页离线手写均报「手写引擎初始化失败（模型或字表加载失败）」；同机其他功能
  （拼音/剪贴板/历史）全部正常。装回旧版即恢复。
- **根因（符号级定位）**：新版 `libhccr.so` 比旧版（v1.0.25 / v0.001 可用的
  4,692,088B）大 41,736B（4,733,824B），且多出 4 个未定义符号：
  `AAsset_getBuffer` / `AAsset_seek` / `memfd_create`(weak) / `__cxa_pure_virtual`。
  - `AAsset_getBuffer` 是 **API 33（Android 13）新增**。本工程 minSdk=29，
    Android 10-12 设备的 libandroid.so 没有它；`libhccr.so` 又是 BIND_NOW
    （NDK 默认 `-z now`），`System.loadLibrary("hccr")` → dlopen 在符号绑定
    阶段直接失败 → UnsatisfiedLinkError 被 `runCatching` 吞成 null → 面板报
    「模型或字表加载失败」。**与模型/字表/assets 毫无关系**（assets 逐一校验过：
    charset.json 122987 / model.ncnn.bin 4288512 / param 7648，与旧版逐字节一致）。
    > ⚠️ 本条「API 33 新增」的归因**已被 #73 用 NDK 逐版本桩库证伪**：
    > API 29 桩库即导出该符号；用户设备为 API 31/37，均高于 29。
    > 本条保留的是**符号级事实**（新增 4 个 UND、来源链条、qemu 验证、
    > 可观测性修复），「根因」部分以 #73 为准。
  - 符号来源链条：新版 libncnn.a（仓库重建时随 ncnn 升级被替换）把
    `DataReaderFromMemory` 的实现从 header inline 挪进了 `datareader.cpp.o`
    （旧版在 header 里 inline，该成员从不被链接）；本工程用
    `DataReaderFromMemory` 读模型 → 必须链接 `datareader.cpp.o` → 同文件的
    `DataReaderFromAndroidAsset::scan` 引用 `AAsset_getBuffer`（scan 只在
    `load_param_bin(AAsset*)` 路径调用，本工程从不走）。
  - 为什么以前没炸：历史可用 APK 的 libhccr.so 是更早的 libncnn.a 链出来的
    （`readelf --dyn-syms` 只有 AAssetManager_open/getLength64/read/close 四个
    远古符号）；且 Oct 24 的 v0.001 发布复用了 Oct 21 的 cxx 增量产物，
    新 libncnn.a 从未真正参与过链接，直到本次全新构建才暴露。
- **修复（v1.0.31a，双管齐下）**：
  1. **符号级外科手术**：`llvm-objcopy --redefine-sym AAsset_getBuffer=hccr_compat_AAsset_getBuffer`
     逐个 ABI（arm64-v8a / armeabi-v7a / x86 / x86_64）修补 `libncnn.a` 内的
     `datareader.cpp.o`，`ar` 替换成员 + ranlib；在 `hccr_jni.cc` 定义垫片
     （返回 nullptr，scan 死路径无行为影响；AAsset_seek / AAsset_read /
     AAsset_getLength64 在 API 29 以前即已存在，保持原样）。
  2. **qemu 端到端验证**：装 qemu-user-static，用 NDK clang 静态编译 harness
     （复刻 nativeInit：patched libncnn.a + DataReaderFromMemory + load_param/
     load_model + extractor 前向），`qemu-aarch64-static` 实跑：
     `MODEL LOADED OK / FORWARD OK out.w=3755`——新版 libncnn.a 能正常反序列化
     现有模型（param/bin 未动），排除「ncnn 升级导致模型不兼容」的备疑。
  3. **可观测性**：`HandwrittenRecognizer.get()` 的 runCatching 补
     `onFailure { Log.e(...) }`——此前静默吞异常，真机只有一句无信息文案，
     dlopen 缺符号 / 模型损坏 / 字表解析失败三者无法区分。
- **坑 4（方法论）**：lld 的归档成员抽取**不因「符号已被前面的目标文件定义」而
  跳过**（最小复现验证过：`a.o` 定义 foo + `b.o` 引用 foo + 归档同时提供 foo，
  成员照样被抽取）。所以「在自己的 .cc 里定义同名桩阻止抽取 datareader.cpp.o」
  这条路走不通——必须真正替换/修补归档成员本身。
- **坑 5（ld -y 误导）**：`-y <symbol>` 的 "reference to" 行只是记录引用关系，
  **不代表抽取原因**；本次真正的抽取触发者是 `DataReaderFromMemory` 的析构函数
  （nm -u datareader.cpp.o 里静静躺着），不是最先疑似的 AndroidAsset 符号。
- **验证**：重编 arm64 APK 后 `readelf --dyn-syms lib/arm64-v8a/libhccr.so`
  已无 `AAsset_getBuffer`，AAsset 系列回到与旧版一致的四个远古符号。

## 73. [方法论·根因更正] 「先实证、后归因」：NDK 逐版本桩库是符号可用性的唯一权威判据，#72 的 API 33 归因被证伪（v1.0.32 合规审计轮）
- **背景**：#72 把手写失败归因为「`AAsset_getBuffer` 是 API 33 新增符号，
  Android 10-12 的 libandroid.so 没有它，BIND_NOW 下 dlopen 必失败」，
  并据此对 4 个 ABI 的 libncnn.a 做了符号改名补丁（v1.0.31a）。
- **证伪过程（全部本地证据，零推测）**：
  1. NDK 28 头文件 `android/asset_manager.h` 中 `AAsset_getBuffer` **无
     `__INTRODUCED_IN` 标注**（同文件的 `AAsset_getLength64` 等也没有——
     它们是 API 9 时代就有的家族）；
  2. **API 29 桩库即导出 `AAsset_getBuffer`**（`nm -D` 实测），与
     `AAsset_seek`/`AAsset_read` 同在；API 33 独有的符号（`APerformanceHint_*`、
     `APermissionManager_checkPermission` 等）在 API 29 桩中**不存在**——
     证明桩库按级别精确过滤而非并集，「API N 桩中有」⇒「API N 起可用」；
  3. 反馈用户的设备是 API 31（Android 12 机型）与 API 37，**都高于 29**；
     该失败早于 v1.0.23 即间歇出现，从未在 Android 10 上报告过。
- **结论**：v1.0.31a 的 4 个新符号在 API 29+ 全部可解析，native 层本就
  兼容；符号补丁定位为「加载稳健性加固」（不依赖个别系统镜像对单一 NDK
  符号的解析策略），**无害但不是修复**。设备侧真实失败原因**仍未定位**，
  需真机 logcat（`MBM-HW` tag；v1.0.31a 起失败路径已有 `Log.e`）。
- **方法论（沉淀为纪律）**：
  1. 符号可用性以**逐版本 NDK 桩库**为准，不以印象、第三方转述或
     「听起来像新版 API」的直觉为准；
  2. 注释/提交信息里的**因果断言必须标注证据**；被证伪的表述要即时更正
     （本轮已更正 `hccr_jni.cc` 注释与本条 #72 标题），避免错误结论被
     后续维护当作事实继承；
  3. 设备侧问题的归因优先级：先拿到日志再谈根因；「符号缺失」这类
     **可静态证伪**的假设应最先用桩库/头文件排除，不要用来解释一切。
- **配套**：v1.0.32 合规审计轮的完整记录见 docs/PATCHES_v1.0.32.md
  （Android 10–17 兼容性 + 隐私合规，含 recall 权限最小化与隐私政策落地）。

## 74. [加固] 手写 asset 读取必须循环读满 + 失败必须分级记日志（v1.0.33）
- **背景**：设备侧间歇性「手写引擎初始化失败（模型或字表加载失败）」真实
  原因仍未定位（#72 的符号归因已被 #73 证伪）。本轮把可静态加固的疑点
  先加固掉，并把可观测性做实，等真机日志。
- **坑 1（短读）**：旧 `read_asset` 单次 `AAsset_read(asset, buf, len)`
  读不满即返回空向量 → nativeInit 判 `param_bytes.empty()` /
  `model_bytes.empty()` → 面板报「模型或字表加载失败」。但
  **AAsset_read 不保证一次读满请求长度**——压缩存储的 asset 受内部解压
  缓冲块大小限制会短读。现改为循环读满（`while (done < length)`，
  `got <= 0` 即 EOF/错误跳出）。这是「间歇失败」的可疑诱因之一：
  模型 bin 4.2MB 是否短读取决于该 asset 在具体 APK 里的存储方式与
  设备实现细节，跨版本/跨设备表现不稳定完全说得通。
- **坑 2（日志时机）**：给 nativeInit 加分级日志时踩到 JNI 生命周期
  错误——`env->ReleaseStringUTFChars(param, p)` 之后 `p` 即失效，
  把 `p` 带进日志是 use-after-release。日志必须在释放前打印；空指针
  （GetStringUTFChars OOM）也要单独一个分支。
- **坑 3（可观测性设计）**：一个 `return 0` 后面跟六种成因
  （manager 空 / 名字空 / param 缺失 / load_param rc / bin 缺失 /
  load_model rc），真机只有一句无信息文案，排障全靠猜。分级日志用
  `stage=` 前缀 + 关键数值（rc、字节数），tag 与 Kotlin 侧
  HandwritingWindow 的 `MBM-HW` 对齐，一条 grep 全拿到。
- **配套**：v1.0.33 完整改动见 docs/PATCHES_v1.0.33.md（另有 Recall
  设备凭证门、许可查看器、universal 包打包正确性）。

## 75. [构建·依赖] BiometricPrompt 只接受 FragmentActivity；「改完不构建」等于没改完（v1.0.33）
- **背景**：Recall 设备凭证门引入 `androidx.biometric.BiometricPrompt`，
  宿主 `RecallActivity` 是 `ComponentActivity`。代码评审看不出问题，
  编译期才炸：`BiometricPrompt(p0: FragmentActivity|Fragment, ...)`。
- **坑 1（宿主类型）**：`ComponentActivity` 是 `FragmentActivity` 的父类，
  不满足构造器签名。androidx.biometric 借 `FragmentManager` 在配置变更后
  仍能派发回调，因此签名卡死 FragmentActivity。修法：宿主改为继承
  `FragmentActivity`（无主题约束，`lifecycleScope` 等能力不变）。
- **坑 2（流程）**：凭证门这种跨 Activity 类型 / 跨库 API 的改动，
  静态评审抓不到签名级错误——**改完必须当轮构建**。本轮首构建失败暴露的
  第二处问题是 NOTICE 对 `data/password/` 加密能力的失实声明（该包实际
  无加密实现，链路在 recall 的 RecallDatabase.kt），同样是「文档声称与
  代码不符，构建/核查才发现」。
- **坑 3（验证工具也会骗人）**：符号审计脚本 `docs/api_audit2.py` 原签名
  是 `main(argv[1], argv[2] as label)`——传两个 APK 时只审第一个，第二个
  被当成显示标签静吞。本轮两包联跑，汇总行写着 recall 包的路径，实际审的
  是 app 包（26 库），recall 的 4 个 .so（onnxruntime / sqlcipher 等）
  被漏审却显示「审过」。已改为遍历所有入参。教训：**汇总数字不等于验证
  覆盖**——报告「审了 N 个库」之前，先确认 N 与 `unzip -l | grep .so`
  的实物清单对得上。
- **教训**：凡涉及第三方库构造器/生命周期宿主的改动，把「编译通过」
  列为完成标准；NOTICE/PATCHES 等文档的能力归属描述要与 `grep` 结果
  逐条对账（本轮纪律延续 #73 的「先实证、后断言」）。

## 76. [密码·语言·测试] Kotlin 中缀优先级坑 + 尾块短读 + 「同构镜像 ≠ 行为一致」（v1.0.34）
- **背景**：R3 第一期给两个 APK 的 blob 文件加静态加密
  （分块流式 AES-256-GCM，StreamingFileCipher）。同一份设计在两个模块
  各写一份（不能共用代码），测试也各自写。三个独立缺陷叠加，差点让
  有问题的密码实现上线。
- **坑 1（Kotlin 中缀优先级）**：`ByteArray(1 shl 24 + 16)` 想申请
  16MB+16B 缓冲，实际解析为 `1 shl (24+16)` = `1 shl 40` = `1 shl 8`
  = **256**——中缀函数（`shl`/`shr`/`ushr`）优先级**低于**算术运算符。
  recall 侧无意识带了括号 `(1 shl 24) + 16` 所以正确，app 侧没带，
  行为相反：大块读取时 `BufferedInputStream.implRead` 抛
  IndexOutOfBoundsException。规则：**`shl` 与任何算术混用必须显式
  加括号**；本仓库已全量扫过无第二例。
- **坑 2（尾块短读）**：解密端 `fillChunk` 按「每块都是满块」
  （chunkSize+16）读取，合法尾块更短 → 误抛 EOFException。修法：
  块间无长度前缀时靠「读到位数 vs EOF」区分——EOF 时已读 ≥16B
  （至少一个 tag）按尾块解密，GCM 校验自然放行真尾块、拒绝截断块
  （AEADBadTagException）；不足 16B 才抛 EOFException。
- **坑 3（测试数据偏差 + 构建顺序掩盖）**：尾块 bug 活过实现期，
  因为①性能基准用 100MB（恰为 400×256KB 整块），尾块路径从未执行
  ——「基准跑过」不等于「边界覆盖」；②recall 测试先失败 → 构建中断，
  `:app:testDebugUnitTest` 根本没跑，而 test-results 目录里躺着上一轮
  的旧 XML，粗看会误以为 app 也测过了。对策：多模块测试加
  `--continue`；核对测试报告前先看时间戳/数量是否符合本轮预期。
- **教训**：「同构镜像」是代码组织方式，不是质量保证——镜像两份
  代码必须**各自**跑各自的测试；密码实现的测试尺寸要显式覆盖
  0 / 1 / 块-1 / 块 / 块+1 五档边界（本轮 StreamingFileCipherTest
  的 `round trip across chunk boundaries` 即为此而写）。

## 77. [构建·工具链] AGP/Gradle 升级：警告不等于事实、跨任务「doFirst 传值」在配置缓存下不成立（v1.0.35）

**背景**：v1.0.35 把工具链切到 **AGP 9.4.0 + Gradle 9.8.2**（JDK 20、Build
Tools 36.1.0、NDK 28.0、compileSdk/targetSdk 37 均满足 AGP 9.4 要求）。
切换本身配置期一次通过，但过程中踩到三个非显而易见的点。

### 坑 1：AGP 警告说「无效果」，实测其实有效——先实测再下结论

配置期报：
```
WARNING: Accessing value buildConfigFields in variant debug has no effect
as the feature buildConfig is disabled.
```
按字面理解会得出「P2-7 启动签名自校验的 `BuildConfig.SIGNER_SHA256`
注入失效、安全防线静默消失」的结论。**实测（`generateDebugBuildConfig`
后直接看生成的 BuildConfig.java）证明字段是写进去的**：
`SIGNER_SHA256 = "E3F54C88…"`。警告与实际行为相反。

- 教训：**构建警告是线索不是结论**，尤其涉及安全防线时，必须落到
  产物上验证（生成源码/APK 内容），不能靠警告文字推断严重性。
- 处理：不抑制警告，改由**注入方**（`AndroidAppConventionPlugin`）显式
  `buildFeatures { buildConfig = true }`——依赖默认值不写进代码就是隐式
  契约，将来 AGP 真默认关闭时这条防线会无声消失。

### 坑 2：配置缓存下，任务间靠「共享 Property + doFirst」偷传值会断

本工程 native install 任务（`CMakeBuildInstallTask`）需要的
cmake 路径与 C++ 构建目录，靠另一批任务
（`ExternalNativeBuildJsonTask`）的 `doFirst` 里 `set()` 到**同一个
Property 对象**上传递，任务间只靠 `mustRunAfter` 约束顺序，没有
`dependsOn`（原注释明言「can't declare the dependency relationship」）。

非 CC 下这些任务共享同一个 Property 实例，能跑通；**启用配置缓存后每个
任务从缓存反序列化出各自独立的属性副本**，json 任务 `doFirst` 写进去的
值传不到 install 任务，报
`Cannot query the value of property 'cmakeExe' because it has no value`。

- 教训：**配置缓存不只是「别在配置期干执行期的活」，它还会切断
  执行期的跨任务隐式通信**。凡属「A 任务执行时把值塞给 B 任务读」的
  hack，在 CC 下都不成立，必须改成配置期确定的输入属性。
- 本轮处置：CC 暂不启用（gradle.properties 保留注释与原因），但**前置
  改造已完成**——任务内不再捕获 `project`、不再用不可序列化 lambda。
  彻底解法是把 cmake/cxx 路径改为配置期可确定（不依赖 doFirst）。

### 坑 3：任务 action 闭包捕获 `project` / lambda 是 CC 的两类硬伤

CC 报告 12 个问题全部指向 `CMakeBuildInstallTask`，两类：

1. `invocation of 'Task.project' at execution time is unsupported`——
   任务里 `project.providers.exec {}`。修法：改为注入
   `@get:Inject abstract val execOperations: ExecOperations`。
2. `cannot serialize object of type DefaultProject`——两个来源：
   - 任务属性是 AGP internal 的 `CxxAbiModel`（间接持有 Project）。
     修法：摊平成两个 `Property<String>`（cmakeExe / cxxBuildFolder），
     任务真正只用到这两项，语义等价。
   - `doLast {}` 闭包里访问 `project.extensions` / `project.assetsDir`，
     以及 extension 里的 `Map<String, (File) -> Unit>` lambda。修法：
     把「安装后改文件」数据化为 `TextReplacement(path, from, to)`
     （`Serializable` 数据类），作任务输入属性；`afterEvaluate` 里把
     值 set 进去；extension 暴露 `textReplacement(path, from, to)` DSL
     方法（类型在 build-logic 默认包，模块脚本无法 import，故用方法）。

- 教训：**任务类里出现 `project.` 基本就是 CC 债务**；`Property<T>` 的
  T 必须是可序列化类型，AGP/KGP 的 internal 模型类不算。

### 顺带确认的三件事

- Gradle 9.8.2 内嵌 Kotlin **2.4.10**，与本工程 KGP 版本恰好一致，
  省掉一整类 Kotlin DSL 编译冲突。
- `providers.exec {}`（本工程 `runCmd` 的实现）是官方推荐的配置期外部
  进程 API，**不在** Gradle 9.8「配置期启进程」的弃用范围内（被弃用的是
  `ProcessBuilder` / `Runtime.exec` / `ExecOperations` 配置期调用）。
- 唯一残留弃用 `Configuration.setVisible(boolean)` 来自第三方插件内部
  （本仓库代码零调用），Gradle 11 才移除，无法在本工程侧修复。

### 「发挥新特性」落地清单（本轮）

| 项 | 状态 |
| --- | --- |
| AGP 9.4.1 → 9.4.0、Gradle 9.6.1 → 9.8.2 | ✅ |
| `android.newDsl=true` + `android.builtInKotlin=true`（提前强制 AGP 10 严格行为） | ✅ 完整 assembleDebug 通过 |
| `org.gradle.parallel` + `org.gradle.tooling.parallel`（显式声明，Gradle 10 起解耦） | ✅ |
| `org.gradle.caching=true`（本地构建缓存） | ✅ |
| `buildFeatures.buildConfig = true`（注入方显式声明） | ✅ |
| CC 前置改造（去 project 捕获 / 去 lambda / 注入 ExecOperations） | ✅ |
| `org.gradle.configuration-cache=true` | ⏸ 见坑 2，待跨任务传值改造 |

## 78. [交付·链接] 中文文件名的 URL 编码必须机器生成并实测：下载页主 APK 链接 404 了两个版本（v1.0.35）

**现象**：下载页（`/workspace/apkdl/index.html`）上线后逐个 `curl -I` 实测，
Recall 包 200（86,152,091 bytes），**主输入法包 404**。而文件确实在目录里、
体积也对得上。

**根因**：页内链接里的中文文件名是**手工拼接 percent-encoding**，其中
「剪贴板」的「贴」被写成 `%E8%B4%B9`，正确值是 `%E8%B4%B4`：

```
错：fcitx5-android-%E6%97%A0%E4%B8%8A%E9%99%90%E5%89%AA%E8%B4%B9%E6%9D%BF-...apk
对：fcitx5-android-%E6%97%A0%E4%B8%8A%E9%99%90%E5%89%AA%E8%B4%B4%E6%9D%BF-...apk
                                              ^^^^^^ 贴=B4，不是 B9（B9 是别的字）
```

同一个页面里「无上限历史」那串是对的，所以只有带「剪贴板」的四条链接坏掉
——**这正是最容易蒙混过关的地方：页面能打开、部分链接能用、卡片和 SHA 都对，
唯独主包下载 404**。该错误自 v1.0.33 页面起存在了两轮。

**修法**：用 `urllib.parse.quote(name)` 生成编码，再整体替换；修完后逐条
`curl -I` 校验（看 HTTP 状态 + `content-length` 是否等于文件真实字节数）。

- 教训：**带非 ASCII 字符的下载链接必须机器生成 + 逐条实测**，
  不能靠「照上一版复制」——错编码会随复制一起继承。
- 教训（更一般）：「页面能开」不等于「链接能用」。交付前对**每个**
  链接发一次请求，校验状态码与长度，别只看首页 200。

**顺带**：发布静态目录时，页内链接若带上一版的 `?sharecode=...` 应清掉，
新站点的访问凭据由新链接自身携带。

## 79. [真机反馈·真 bug] debug 主包查 Recall 插件用了无 .debug 后缀的包名——「尚未安装」提示与签名无关（v1.0.35u）

**真机现象**（用户截图）：debug 主输入法 → 设置「Recall 屏幕记忆」→
toast「尚未安装『屏幕记忆』独立应用（org.fcitx.fcitx5.android.plugin.recall）」，
而插件 debug 包明明已安装。

**根因**：`MainFragment.RECALL_PLUGIN_PACKAGE` 硬编码为
`org.fcitx.fcitx5.android.plugin.recall`（无后缀）；而约定插件与主包一样
挂 `applicationIdSuffix = ".debug"`，debug 插件包实际叫
`…plugin.recall.debug`。debug 主包永远查不到 debug 插件包 → 误报未安装。
「插件已装但拉不起」与「真的没装」的区分逻辑（pitfalls #51）完全没机会执行，
因为第一步 `getPackageInfo` 就 NameNotFoundException 了。

**修法**：包名按主包 applicationId 派生，不再硬编码——

```kotlin
"org.fcitx.fcitx5.android.plugin.recall" +
    BuildConfig.APPLICATION_ID.removePrefix("org.fcitx.fcitx5.android")
```

debug → `…plugin.recall.debug`，release → `…plugin.recall`，两全。

- **教训**：`applicationIdSuffix` 会同时改主包与插件的包名，**跨包引用
  （查询 / <queries> / 显式 Intent 的 setClassName）必须跟着派生**，硬编码
  基名只能在 release 包里碰巧工作。用户报「未安装」时先核对双方真实包名
  （`aapt2 dump badging`），再谈签名与可见性。

**同轮**（用户要求「APK 不签名、自己签」）：
- `AppSignatureVerifier` 加 `ENABLED=false` 总开关（构建期注入的基准指纹
  对自签包必然误报重打包；类与注入链保留，单点可恢复）；
- `PluginTrustChain.verifyPackage` 加 `SIGNATURE_CHECK_ENABLED=false`
  （自签分发下主包与插件可能用不同密钥，「同签名才信任」会拒掉合法插件；
  .so 审计与 dlopen 路径限定保留）；
- debug 变体 `signingConfig = null`：AGP 对未配置签名的 debug 变体也会
  输出 `-unsigned.apk`，与 release 无 keystore 时的行为一致。

## 80. [真机反馈·真 bug 二连] 「已找到但无法打开」：LAUNCH 权限名与 <queries> 同样漏派生 .debug 后缀（v1.0.35u）

**承接 #79**。修掉包名硬编码后，真机推进到下一步：toast 变为
「屏幕上已找到『屏幕记忆』应用，但无法打开它」——而用户确认两包
签名完全一致。

**根因（同一类错误的第二处 / 第三处）**：
- 插件 RecallActivity 带 `android:permission="${applicationId}.permission.LAUNCH"`
  （applicationId 派生 → debug 实际要求 `…plugin.recall.debug.permission.LAUNCH`）；
- 主包 manifest `<uses-permission>` 硬编码 `…plugin.recall.permission.LAUNCH`
  （无后缀）→ debug 组合下主包持有的权限与 Activity 要求的**不是同一个权限**
  → SecurityException；
- `<queries><package>` 同样硬编码无后缀（本例因主包持有
  `QUERY_ALL_PACKAGES` 未暴露，但 release 之外的可见性声明仍是错的）。

**「能找到但打不开」的完整链路**：QUERY_ALL_PACKAGES 让包可见（找到）→
setClassName 显式拉起 → 系统校验调用方是否持有 Activity 的 permission →
权限名不匹配 → SecurityException → 主包落入「已安装但拉不起」分支，
其提示文案里罗列的「签名不一致」只是该分支的常见原因之一，这次不是。

**修法（自签免校验方向，撤门而非续门）**：
- 插件 RecallActivity 删除 `android:permission`（`<permission>` 定义保留）；
  页面自身仍有设备凭证门、数据仍有 SQLCipher，撤此门不降低数据安全；
- 主包删除对应 `<uses-permission>`；
- `<queries><package>` 改 `${recallPluginPackage}` 占位符，buildTypes 注入
  （debug = `…plugin.recall.debug`，release = `…plugin.recall`），
  与 MainFragment 的派生规则同源。

- **教训**：`applicationIdSuffix` 的派生面包括——跨包查询包名、`<queries>`、
  **自定义权限名（定义与申请两端）**、显式 Intent 的包名与类名。修 bug 时
  只改 grep 到的第一处（#79）是「按线索修」，把后缀派生一次找齐才是
  「按机制修」。判据：凡是字符串里出现 `org.fcitx.fcitx5.android` 基名的
  跨包引用，都必须能回答「debug 构建下它应该是什么」。

## 81. [真机反馈·真 bug] 主输入法剪贴板 Room 崩溃：SQLCipher 加载调用被误删（v1.0.36）
- **现象**：主输入法访问剪贴板/触发 Room SQLCipher 即崩，logcat：
  `java.lang.UnsatisfiedLinkError: No implementation found for long net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen(...)`，
  栈顶 `SQLiteConnection.nativeOpen`；Version Name `07c8aba`、Build Time `2026-10-27T09:25:00Z`、
  Git Hash `07c8aba0…`、设备 HONOR PTP-AN70 / API 37。
- **根因**：`data/clipboard/ClipboardManager.kt` 仍用 SQLCipher 加密 Room 库
  （`net.zetetic:sqlcipher-android:4.19.1` + `SupportOpenHelperFactory`），但
  `FcitxApplication.kt` 的 v1.0.26 注释误判「SQLCipher 已拆给 Recall、不再需要加载」，
  删掉了 `System.loadLibrary("sqlcipher")`，违反 AGENTS.md 数据库/原生库硬性约束 #1。
  `libsqlcipher.so` 一直在 APK（非 ABI 缺失、非打包缺失），纯粹是加载调用丢失。
- **修复**：`FcitxApplication.onCreate()` 顶部恢复
  `runCatching { System.loadLibrary("sqlcipher") }`（与 `RecallApplication.kt:21` 同模式）。
- **验证**：AGP 9.5.0-rc02 重建后 `unzip -l` 确认 `lib/arm64-v8a/libsqlcipher.so`
  （2,100,672 B）在包内；`strings classes.dex` 含 `sqlcipher` 加载串；`unzip -t` 完整；
  下载回源 sha256 一致。
- **教训**：删「不再需要」的 `System.loadLibrary` 前，先确认该组件所有 DB 是否仍用
  对应加密库；不能因某插件拆分就假设主包不再需要。

## 82. [真机反馈·真 bug] 手写引擎「模型或字表加载失败」根因=InputStream.readAllBytes 缺 API（v1.0.36）
- **现象**：真机键盘内手写面板 / 设置页离线手写均报「手写引擎初始化失败（模型或
  字表加载失败）」。logcat（tag MBM-HW / HandwrittenRecognizer）：
  ```
  I MBM-HW  : init ok: param=7648 bin=4288512 threads=1
  E HandwrittenRecognizer: java.lang.NoSuchMethodError: No virtual method
    readAllBytes()[B in class Ljava/io/InputStream;
    at com.shiyu.handwritten.runtime.HCCRRecognizer.loadCharset(HCCRRecognizer.java:74)
  ```
- **根因**：`HCCRRecognizer.loadCharset`（lib/handwritten/…/runtime/HCCRRecognizer.java:74）
  用 `InputStream.readAllBytes()` 读 `charset.json`——该方法是 **JDK 9 / Android API 33**
  才加入；目标设备 runtime 无此方法 → `NoSuchMethodError`（D8 改写为
  `IntentKt$$ExternalSyntheticApiModelOutline0`）。**native 层 `init ok` 证明模型加载正常**，
  是 Java 层读字表（charset.json）时炸——这正解释了 #72–#74 长期「未定位」的间歇失败
  （曾误归因 AAsset_getBuffer 符号，被 #73 用 NDK 桩库证伪）。
- **修复**：新增 `readAllBytesCompat(InputStream)`（ByteArrayOutputStream + 8192 缓冲循环读，
  `InputStream.read` 远古 API 全版本可用，且天然循环读满，对齐 #74 教训），替换
  `stream.readAllBytes()`。项目内 grep 确认 `readAllBytes` 仅此一处。
- **验证**：AGP 9.5.0-rc02 重建，`dexdump classes3.dex` 有 `readAllBytesCompat` 定义且
  无 `readAllBytes` 调用；APK `unzip -t` 完整；下载 URL Content-Length=82,173,286 与本地一致。
- **教训**：①「模型/字表加载失败」≠模型损坏——native `init ok` 但 Java 层读字表
  API 不兼容同样报此文案；② vendored/第三方库使用 JDK 9+ API（`readAllBytes`/
  `transferTo`/`List.of` 等）且 minSdk 低于该 API 时，低版本设备 `NoSuchMethodError`，
  静态排查应 grep 这些 Java 9 方法；③ 该问题在 #72–#74 因无真实 Java 栈被误归因符号，
  本轮拿到 `NoSuchMethodError` 栈即一次定位——真实栈优先级最高。

## 83. [工程规范·统一 adb 调试架构] 输入法所有可点击功能支持 adb 调试（v1.0.36）
- **需求**（用户本轮）：「所有可点击功能都应支持 adb 调试」「交付完整一整套 adb 指令」
  「日志入口在 APK 中不要出现多个（影响 UI 美观）」。
- **方案**：单例 `utils/MbmDebug.kt` 为唯一调试枢纽 + 唯一 `MBM_DEBUG` 广播 +
  唯一 UI 入口（设置页→开发者→「adb 调试指令」，主菜单「开发者」入口已移除）。
  - 广播：`am broadcast -a org.fcitx.fcitx5.android.action.MBM_DEBUG --es cmd <子命令>`
    （`FcitxApplication` 动态注册，权限同 `PERMISSION_TEST_INPUT_METHOD`）。
  - `logFeature(id, detail?)` 统一打 `MBM-DBG: feature=<id>`，测试脚本 grep 断言。
  - 可点击功能布点：KawaiiBarComponent（bar.*/clipboard.paste）、ClipboardWindow
    （clipboard.paste window）、CandidatesView（candidate.*）、BaseKeyboard（key.press/
    longpress/repeat）、HandwritingWindow（handwriting.*）、TextEditingWindow（editing.*）、
    StatusAreaWindow（status.action）。
  - Bridge（FcitxInputMethodService 实现）：stateDump/imeDump/applyVerbose/dumpLogcat。
- **坑（编译）**：① `FcitxInputMethodService` 须显式 `: MbmDebug.Bridge`，漏掉报
  `ASSIGNMENT_TYPE_MISMATCH`（Bridge? 期望）；② `Timber.setupForest` 是
  `org.fcitx.fcitx5.android.utils` 顶层扩展，须在服务 import；③ DeveloperFragment 同时
  import `android.content.ClipboardManager` 与 `org.fcitx.fcitx5.android.data.clipboard.ClipboardManager`
  同名冲突，须用全限定名 `android.content.ClipboardManager::class.java` 并删 android.content import。
- **验证**：AGP 9.5.0-rc02 重建 4m51s；`classes.dex` grep `MbmDebug`（classes.dex/3）/
  `MBM-DBG`/`ACTION_MBM_DEBUG`/`adbCommands`/`logFeature`/`setupForest` 与各 feature id
  （handwriting.recognize、status.action、editing.move、key.press、candidate.select、
  clipboard.paste、bar.menu）全部命中；下载 URL Content-Length=82,185,662 与本地一致。
- **方案修正（用户纠偏）**：用户要求是「adb 完整跑一遍自动测试所有按键所有功能」，
  不是「用户点击 + adb 观测」。改为 adb 真实驱动：每个可点击控件用
  `MbmDebug.markFeature(view,id)` 打 `content-desc="mbm:feature:<id>"`，测试脚本
  `scripts/mbm_adb_test.sh` 用 `uiautomator dump` 解析该标识拿屏幕坐标 →
  逐个 `adb shell input tap` 真实点击 → `logcat -d` 断言 `MBM-DBG: feature=<id>` 出现。
  已布点：bar.*/clipboard.paste、PagedCandidatesUi(candidate.select/prev/next)、
  BaseKeyboard(key.press 所有键)、HandwritingWindow(handwriting.*)、
  TextEditingWindow(editing.*)、StatusAreaWindow(status.action)、
  ClipboardAdapter(clipboard.paste 条目)。Kotlin 字符串模板
  `contentDescription="mbm:feature:$id"` 编译后前缀与 id 是**两个独立 dex 字符串**，
  grep 验证需分别查 `mbm:feature:` 与各 id，不能查拼接串。重建 5m13s，
  dex 全命中；URL=82,187,206、文叔叔 https://c.wss.ink/f/l7rya0smurx。
- **内置自测引擎 v1→v2（用户纠偏：SKIP=58 海洋 + 报告用户拿不到）**：
  ① v1 引擎只从 `windowManager.view` 收集标记控件，但工具栏按钮（bar.menu/hide/
    cursor/clipboard/more/handwriting）都在 `kawaiiBar.view`（`windowManager.view`
    的**兄弟节点**，同在 InputView 的 keyboardView 里）→ 所有开窗按钮找不到 →
    状态区/手写/编辑/剪贴板相位全 SKIP，报告 58 条 SKIP。v2 改从 InputView 整棵
    视图树（`this`）收集。
  ② v2 前置门：键盘相位 0 个标记控件 → 只报 1 条 `precondition` FAIL + 视图树
    样本诊断，直接返回，不产生 SKIP 海洋；工具栏未渲染时先点 bar.menu 展开重采。
  ③ 手写识别不再 SKIP：对 board（`handwriting.recognize` 标记的 HandwritingView）
    合成 1 笔斜线 `MotionEvent` down/move/up 直调 `onTouchEvent` 触发抬笔回调
    （`onStrokeEndListener` → logFeature+recognize）；剪贴板相位前
    `setPrimaryClip` 预置系统剪贴板条目保证面板有内容；危险键后置（bar.menu→
    bar.hide→status.settings，最后 3 个测完才离 IME）。
  ④ `bar.hide` 的点击路径（KawaiiBarComponent.hideKeyboardCallback）原本不打
    logFeature，v2 补上；BaseKeyboard 高频键分键标记（SpaceKey/MiniSpaceKey→
    key.space、BackspaceKey→key.backspace、ReturnKey→key.return，其余 key.press），
    按压日志同步用 featureId，断言粒度才与标记粒度一致。
  ⑤ 报告自动导出三处（minSdk=29，MediaStore.Downloads 免权限）：公共
    `Download/MBM_自动测试_<ts>.txt`（RELATIVE_PATH=Download）+ 剪贴板全文 +
    filesDir/mbm_selftest.txt；`MbmDebug.lastExportPath` 供状态区 Toast 明示位置；
    导出走 `withContext(Dispatchers.IO)`（主线程禁文件 IO/MediaStore binder 铁律）。
  ⑥ 遗留诚实 SKIP（带原因分类）：无独立标记键（key.caps/lang 等与 key.press
    共享）、无浮动候选时的 candidate.*、页级功能（ime.*/设置页 smartdict 等）、
    status.selftest（自测触发点，防递归）。重建 3m45s，dex 全命中
    （exportSelfTestReport/precondition/危险键/key.longpress）；
    APK=82,237,050 B（2026-10-27 16:55 构建），v1 版文叔叔
    https://c.wss.ink/f/l7sgmq5t9al → **v2 版文叔叔
     https://c.wss.ink/f/l7ssil1nogj**（1 天有效；c.wss.ink/f/ 是 JS 分享页，
     curl 只拿到落地 HTML，浏览器打开点「下载」即可；校验以 wss.py 上传
     100% 完成为准）。
- **内置自测引擎 v2→v3（用户纠偏：v2 实测仍 33 条 SKIP；FAIL 要源码级原因；报告要专业）**：
  ① 模型：`FeatureResult.State` 移除 SKIP（仅 PASS/FAIL），字段扩为
     `(id, state, phase, cause, source)` + `pass()/fail()` 工厂；FAIL 的 `detail`
     自动拼 `（源码:<source>）`。`SelfTestReport(results, diagnostics, durationMs)`
     加 `passRate` + 设备行（Build.MODEL/SDK_INT）；`toText()` 专业排版：
     头部元信息（版本/时间/耗时/设备/判定规则）→ 汇总 → 失败项逐条
     （原因 : xxx / 源码 : 文件.kt:符号）→ 成功项清单 → 诊断段。
  ② 原 33 条 SKIP 全部转源码级 FAIL（`sweepFail` 按类给因）：系统层
     （ime.switch/ime.picker 属 Android UI 不可达）、设置页功能（smartdict.
     action/ruleimport.action/pinyin.phrase/password.bridge/handwriting.settings
     入口在设置 Activity 不在 IME 窗口）、无埋点（bar.candidate_expand、
     candidate.tab 代码中无 markFeature 调用）、条件渲染（status.editorinfo
     仅 editorInfoInspector=true 时添加，StatusAreaWindow.kt:221-222）、
     面板无控件（clipboard.enable/clear/import 入口在设置页）、
     自测入口（status.selftest 防递归）。
  ③ key.longpress 转实测：对空格键合成 MotionEvent（ACTION_DOWN 持 650ms 后
     UP，dispatch 给 key.space 视图）触发 SpaceKey 的 LongPress 行为
     （BaseKeyboard.kt:220 打 key.longpress）；BaseKeyboard featureId 扩键型
     （CapsKey→key.caps、LayoutSwitchKey/ImageLayoutSwitchKey→key.layout、
     QuickPhraseKey→key.quickphrase、CommaKey→key.comma、LanguageKey→key.lang、
     NumPadKey→key.number、SymbolKey→key.sym）；IME 切换型键
     （key.layout/key.sym/key.lang/key.quickphrase）并入「危险键」最后测。
  ④ candidatePhase：浮动候选条是键盘 InputView 的兄弟窗（windowManager.contentView
     下），键盘视图树收集不到 → `FcitxInputMethodService.selfTestCandidateView()`
     暴露 `candidatesView` 给引擎；无候选（evaluateVisibility=false 未渲染）→
     4 条 candidate.* 源码级 FAIL（CandidatesView.kt:140-145）。
  ⑤ **编译坑（K2 已证实）**：字符串模板 `$deviceLine()` 是**非法语法**——
     模板条目只接受裸标识符 `$x`（属性引用）或花括号表达式 `${...}`；
     `$foo()` 被解析为 `$foo`（引用）+ 字面量 `()`，而 `foo` 是函数 →
     K2 报 `function invocation 'deviceLine()' expected`。函数调用必须写
     `${foo()}`。用 kotlin-compiler-embeddable 2.4.21 最小复现核实
     （`$f()` 与 `${f()}` 对照，前者必报错）。
   ⑥ 验证：compileReleaseKotlin 3m53s 绿 → assembleRelease 3m49s；
      APK=82,260,418 B（2026-10-27 17:51 构建）；dex 核对
      （classes3.dex strings）：key.caps/key.layout/key.quickphrase/key.comma/
      key.lang/key.number/key.sym、selfTestCandidateView、candidate.*/
      clipboard.swipe_delete/longpress、bar.candidate_expand、sweepFail、
      longPressGesture/swipeGesture、precondition、durationMs 全部命中；
      中文报告标题「MBM 全功能自动测试报告」以 MUTF-8 存于 classes3.dex
      （ASCII strings 抓不到，须按 UTF-8 字节匹配）。文叔叔
      https://c.wss.ink/f/l7t7ak767fp（1 天有效）。
- **内置自测引擎 v3→v3.1（真机实测 40 FAIL 根因定位 + 编排修复）**：
  实测（PTP-AN70/SDK37，2026-10-28）：PASS=28 FAIL=40 TOTAL=68（41.2%）、
  16.4s。格式达标（0 SKIP、FAIL 全带源码级原因）；40 条 FAIL 中约 14 条属
  编排缺陷（把「可测功能」误报为不可测），其余为真·不可达。四大根因：
  ① **标记擦除**：`markFeature` 原只写 contentDescription，随后
     `IdleUi.updateMenuButtonContentDescription()`（IdleUi.kt:153-159）与
     `setHideKeyboardIsVoiceInput`（L174-183）把 menuButton/hideKeyboardButton
     的 contentDescription 覆写成人类文案（expand_toolbar/hide_keyboard/
     switch_to_voice_input）→ bar.menu/bar.hide 找不到。修复：标记双轨——
     `MbmDebug.FEATURE_TAG_KEY=0x7F0B0001`（应用私有 tag key 区间
     0x01000000–0x7FFFFFFF），`markFeature` 同时写 contentDescription +
     `setTag`，`collectFeatureViews` tag 优先、contentDescription 兜底，
     系统性防业务逻辑擦除标记通道。
  ② **开闸人被消费**：窗口相位原从 InputView 整树收集，status 相位误点
     kawaiiBar 兄弟节点上的 bar.cursor/bar.clipboard/bar.handwriting 三个开闸按钮
     → 三个窗口瞬开即关 + 开闸按钮进 `tested` → 后续各相位
     `clickOpener` 因 `id in tested` 早退 → editing.*/handwriting.*/clipboard
     窗口从未真正打开（editing.* ×8、handwriting.* ×8 全 FAIL）。修复：
     `attachWindow` 会把旧窗口视图移出 `windowManager.view`
     （InputWindowManager.kt:154-166），故该 FrameLayout 的子节点就是
     「当前附着窗口子树」→ 窗口相位（status/handwriting/editing/clipboard/picker）
     一律 `collectWindow()`（= `windowManager.view.collectFeatureViews()`）
     收集；开闸按钮只由各相位自己的 `clickOpener`（整树）首次点击。
  ③ **跨窗按键泄漏**：手写相位整树收集误点键盘的 key.layout/key.lang/key.sym
     → 中途切布局/IME → 后续危险键相位键位缺失。修复：危险键重排为
     quickphrase→lang→sym（此时仍在原布局）→ 回键盘窗口 → layout →
     bar.menu/bar.hide（布局切换重建键盘后工具栏仍在，kawaiiBar 兄弟节点
     不受影响）。
  ④ **候选恒空**：v3 键盘相位 `distinctBy` 后 key.press 只按 1 次，从未输入
     拼音音节 → 候选条 0 候选 → candidate.* ×4 FAIL。修复：
     `typePinyinForCandidates()`——退格 ×3 清残留 preedit → 按
     `KeyView.def`（Appearance.Text）的 `displayText` 定位字母键 → 依次点
     "ni"（字符键经 BaseKeyboard.kt:220 Behavior.Press →
     CommonKeyActionListener.kt:99-101 `FcitxKeyAction`→`sendKey` 进入拼音
     引擎生成候选）→ 等 500ms 再进 candidatePhase；缺字母键给诚实 FAIL。
  ⑤ **工具栏态决定开闸按钮可见性**：开闸按钮仅在 IdleUi Toolbar 态渲染；
     空态/剪贴板态/数字行态/内联建议态下点 bar.menu 直接切 Toolbar
     （KawaiiBarComponent.kt:299-302 else 分支）→ 新增 `ensureToolbar()`：
     各窗口相位开闸前若整树找不到开闸按钮，则点 bar.menu 至多 2 次展开
     （不写 pass/fail——bar.menu 判定归危险键相位）。
  ⑥ 小修：picker.longpress 改「最多 3 条目轮试」（首条目常为占位项、
     监听 null，PickerPageUi.kt:194/219-223）；剪贴板相位预置等待 800ms→
     1500ms（fcitx 剪贴板监听→Room 异步落库）；clipboard.paste 加去重守卫
     （闲时态粘贴 chip 已在键盘相位判定时跳过面板点击，保证每 id 恰好一条
     判定）。
  ⑦ 剩余诚实 FAIL（不造 SKIP）：clipboard.swipe_delete/longpress（路径未埋
     点）、candidate.tab/bar.candidate_expand（无 markFeature 调用）、
     设置页 5 项（smartdict.action/ruleimport.action/pinyin.phrase/
     password.bridge/handwriting.settings）、ime.switch/ime.picker（系统层）、
     status.selftest（防递归）、status.editorinfo（开关关）、
     key.quickphrase/key.number（该键型不在当前布局）。
  验证：compileReleaseKotlin 1m57s 绿（3 executed）→ assembleRelease 320
  tasks（55 executed）；APK=82,267,810 B（2026-10-27 18:39 构建）；
  dex 核对（classes3.dex）：FEATURE_TAG_KEY 常量 0x7F0B0001 命中 3 处；
  中文诊断串「清残留后拼音输入」「次展开工具栏」「拼音输入 'ni' 未产生
   候选结果」「窗口子树无条目」「依次试按」按 MUTF-8 字节全部命中。
 - **内置自测引擎 v3.1→v3.2（bar extension 系统性回归修复 + 覆盖率收官 80.6%）**：
   v3.1 真机 58.2%（39/67）定位出「窗口相位全 scope 到 windowManager.view 漏掉
   titleUi 上的 bar extension」系统性回归（picker tab / status.settings /
   editing.clipboard 三类扩展视图经 KawaiiBarComponent.onWindowAttached
   挂到 kawaiiBar 兄弟节点的 titleUi，不在当前窗口子树内）。v3.2 修复：
   ① `InputWindowManager` 暴露 `current: InputWindow?`；
   ② `InputView.collectWindowWithBarExtension()` = 窗口子树 +
   `(current as? ExtendedInputWindow<*>)?.onCreateBarExtension()` 合并收集，
   picker/status/editing 相位改用之（picker tab 经 root-scope 点分类页轮扫）；
   ③ status.editorinfo 自测临时 `AppPrefs.internal.editorInfoInspector.set(true)`
   → 开状态窗 → 测 → 恢复 false（扩展视图 lazy、窗口每次新建须先置 pref）；
   ④ 候选相位多音节 ni→hao→shi 轮试 + `fcitx.currentIme()` 引擎诊断；
   ⑤ 埋点补齐：KawaiiBarComponent expand listener `logFeature("bar.candidate_expand")`、
   ClipboardAdapter 条目长按 `logFeature("clipboard.longpress")`。
   真机实测（PTP-AN70/SDK37，2026-10-28 15:34）：PASS=54 FAIL=13
   TOTAL=67（80.6%）、24.7s。v3.1 FAIL→v3.2 PASS 全部命中：picker.tab ×3、
   status.settings、editing.clipboard、candidates.previous/next/select/clear、
   bar.menu、bar.hide、key.layout/lang/sym/press、clipboard.paste、status.action、
   picker.longpress:Emoticon/:Symbol。
   剩余 13 条 FAIL 全部为结构性不可达（逐条源码级实锤，非编排缺陷，引擎
   在 InputView 作用域内已无提升空间，80.6% 即真实覆盖率上限）：
   ① settings.* ×8（smartdict/ruleimport/pinyin.phrase/password.bridge/
      handwriting.settings/clipboard.enable/clipboard.clear/clipboard.import）：
      功能 UI 在 MBM 设置页 Activity，不在 InputView 视图树内，自测引擎
      运行于 IME 进程无法驱动设置页控件；
   ② ime.switch/ime.picker：Android 系统输入法切换器/选择器为系统 UI，
      不在应用进程控制范围（InputView.kt:550-555 sweep 显式判 FAIL 并
      注明「Android 系统 UI 范畴」）；
   ③ status.selftest：状态窗 SelfTest 条目点击触发
      `FcitxInputMethodService.runSelfTest`（L1440-1452）重入整套自测管线，
      异步完成且无法在自测进程内自证（StatusAreaWindow.kt:154-167）；
   ④ key.number/key.quickphrase：NumberPadKey 仅存在于 NumberKeyboard 布局、
      QuickPhrase 键型属 QuickPhrase 布局预设，当前 QWERTY 布局键位中
      不存在该键型（键盘相位 53 标记控件 16 种 id 中无二者）；强行切布局
      会改变用户键盘设置，故保留诚实 FAIL；
      【v1.0.39 覆盖】NumberKeyboard 布局已按用户指定重排（图二样式），
      QuickPhraseKey 被移出该布局，key.quickphrase 回归结构性 FAIL；
   ⑤ clipboard.swipe_delete：剪贴板条目 CustomGestureView 未开
      swipeEnabled（swipe 仅 picker 条目/键位/候选展开按钮启用），
      功能本身未实现，属诚实 FAIL。
  - **内置自测引擎 v3.2→v3.2.1（相位间窗口污染修复）**：
    v3.2 真机 68.7%（46/67，PTP-AN70/SDK37，2026-10-28 05:52）定位出两处
    编排缺陷（引擎态 keyboard-us 下的 candidates×4 FAIL 属状态相关，切拼音
    引擎即恢复）：① 键盘相位 clickPhase 把 `bar.candidate_expand` 当普通按钮
    点击（v3.2 新增标记落入 root collect 的 kawaiiBar 工具栏），展开按钮点击
    附带切窗副作用；② 键盘相位点击 `key.return`（回车 Emoji 菜单设置开启）
    附带弹开 emoji picker 窗 → 键盘视图被换出 → key.longpress 的 `collect()`
    找不到 key.space，误报「当前布局无空格键」（同轮 54 控件初始列表实际含
    key.space，属收集时窗污染而非布局缺失）。v3.2.1 修复（InputView.kt）：
    ① 键盘相位排除集加 `bar.candidate_expand`（判定权归 candidates 相位）；
    ② key.longpress 检查前 `attachWindow(KeyboardWindow); delay(300)` 兜底重附；
    ③ candidatePhase 开头同样兜底重附（拼音输入与展开按钮测试需键盘窗附着）。
    教训：相位间窗口副作用必须隔离——任何点击都可能附带切窗（窗口管理器
    attachWindow 换出视图），后置相位收集前先重附目标窗。
  - **内置自测引擎 v3.2.1→v3.3（daemon 行为层全功能覆盖）**：
    v3.2.1 真机复验（PTP-AN70/SDK37，2026-10-28 07:12，68.7% 46/67）确认
    两处编排污染已修复：`key.longpress` 由误报「无空格键」→ PASS；`bar.candidate_expand`
    由污染 FAIL → 引擎态诚实 FAIL（keyboard-us 无候选，候选条未渲染，展开按钮不可测）。
    剩余 21 FAIL 中 5 项为引擎态相关（candidates×4 + picker.longpress:Emoji，
    切拼音即恢复）、16 项为既定结构性项，无编排缺陷残留。
    v3.3 新增 **daemon 行为层**（用户要求「全功能」）：不经 UI，直接经
    `fcitx.runImmediately { }` 程序化断言 fcitx daemon，覆盖 IME 引擎查询/枚举
    与 fcitx 全局配置全量表（设置页 GlobalConfigFragment 与自测共享同一
    `getGlobalConfig()` 回读路径），对应 fcitx 设置页的快捷键轮换、修饰键
    250ms 时限、默认激活、共享输入状态、预编辑显示等项。
    新增 24 条注册项（daemon.ime.* ×4 + daemon.hotkey.* ×14 + daemon.behavior.* ×10），
    TOTAL 67→91。关键点：① `getGlobalConfig()` 返回 `{cfg: <值>, desc: <schema>}`，
    值在 `cfg` 子树，Hotkey/Behavior 为嵌套组（native-lib.cpp:193-205 mergeConfigDesc）；
    ② 值格式宽容校验（KeyList 空默认视为合法、bool 收 True/False/1/0、int 按范围、
    枚举非空即可），避免对 fcitx 序列化格式误判造成假 FAIL；③ 枚举往返
    `enumerateIme(true)+enumerateIme(false)` 还原原引擎，无副作用；④ `sweepFail`
    新增 daemon.* 兜底分类（when 内不能用 `id.startsWith(...)` 分支——`when(subject)`
    把布尔分支当 subject 相等比较，报 Incompatible types 'String' and 'Boolean'，
    须提到 when 外 if 判断）。
  - **内置自测引擎 v3.3 真机复验（PTP-AN70/SDK37，2026-10-28 08:29，77.9% 74/95）**：
    daemon 行为层 28 项**全部 PASS**（daemon.ime.*×4 + daemon.hotkey.*×14 +
    daemon.behavior.*×10），验证 fcitx 全局配置程序化回读全链路可用：
    `getGlobalConfig()` cfg 子树 Hotkey/Behavior 各 1 组齐全（诊断第 170 行），
    hotkey.modifier_timeout 回读 250ms、behavior.page_size=5、autosave=30、
    share_state=All 等真实值断言通过，枚举往返
    before=pinyin→fwd→mid=keyboard-us→after=pinyin 可逆。非 daemon 部分
    PASS=46 FAIL=21 与 v3.2.1 完全一致，**无回归**。剩余 21 FAIL 全部为
    既定结构性/引擎态项：candidates×4 + bar.candidate_expand（本轮引擎态
    FAIL，见下）、clipboard.swipe_delete/picker.longpress:Emoji/key.quickphrase/
    key.number/candidate.tab/clipboard×3/status.selftest/ime×2/settings×5。
    关键教训：**本轮报告 candidates 相位 FAIL 的根因是测试时未切拼音引擎**
    （诊断：candidates 前置 `fcitx.currentIme()=keyboard-us`，拼音 'ni/hao/shi'
    轮试落 keyboard-us 无候选；daemon 层枚举阶段才读到 pinyin，因危险键相位
    点 key.lang 已切走）。复验时务必先切拼音引擎再触发自测，预期 candidates
    5 项（prev/next/select/longpress + bar.candidate_expand）转 PASS →
    79/95 ≈ 83.2%。这印证「candidates 相位依赖引擎态」，非编排缺陷。
  - **内置自测引擎 v3.4（candidates 引擎守卫）**：08:29 与 08:49 两份复验
    报告完全相同（均 77.9% 74/95，candidates 5 项 FAIL），证实仅靠"提示用户
    先切引擎"不可靠。v3.4 在 `typePinyinForCandidates()` 开头加**引擎守卫**：
    读 `fcitx.currentIme().uniqueName`，非 "pinyin" 则
    `runImmediately { activateIme("pinyin") }` + delay(400)，再读确认并记入
    诊断（`candidates 引擎守卫: 原引擎=X → activateIme(pinyin) → 现引擎=Y`；
    切失败按原引擎继续，不阻断）。这样 candidates 相位不再依赖用户前置操作，
    无论当前引擎如何，拼音输入都能落到 pinyin 产生候选。激活依赖
    `FcitxAPI.activateIme`（Fcitx.kt:134 → extern setInputMethod，Fcitx.kt:315）。
     v3.4 APK=82,302,494 B（10-28 00:58，BUILD SUCCESSFUL 4m10s，dex 全 HIT）→
     `https://c.wss.ink/f/l7w8tcrddt1`（私有 L7w8tCrddn1qGZwR）。
  - **内置自测引擎 v3.5（FAIL=0 攻坚·IME 层 16 项真实实现）**：v3.4 复验仍
     21 FAIL（用户要求 fail0，「功能未正常实现则不能 bypass」）。21 项构成：
     candidates×4 + bar.candidate_expand（引擎态，v3.4 已守卫）、clipboard×4
     （swipe_delete/enable/clear/import 无埋点）、picker.longpress:Emoji（popup
     条件绑定）、key.quickphrase（QuickPhraseKey 零布局引用）、key.number（仅
     NumberKeyboard 布局）、candidate.tab（无 UI）、status.selftest（递归）、
     ime×2（switch/picker 无日志）、settings×5（跨 Activity，本轮未解）。
     v3.5 IME 层全部真实修复：
     ① `NumberKeyboard.kt` 第一行末加 `QuickPhraseKey()`（key.quickphrase 接线，
        原类存在但零布局引用）【v1.0.39 覆盖：该键已随图二布局重排移除，
        本项自测回归结构性 FAIL，详见文末第 78 条】；
     ② `ClipboardUi.kt` deleteAllButton/importButton、`ClipboardInstructionUi.kt`
        Enable.enableButton 加 `markFeature`（clipboard.clear/import/enable）；
     ③ `ClipboardWindow.kt` onSwiped/enableButton/deleteAllButton/importButton 加
        `logFeature`（clipboard.swipe_delete/enable/clear/import）；
     ④ `CommonKeyActionListener.kt` showInputMethodPicker 加 `logFeature("ime.picker")`、
        LangSwitchAction 加 `logFeature("ime.switch")`——ime×2 是 MBM 键盘功能
        （key.lang 点击=切换、长按=选择器，面板内窗口不抢焦点），非系统 UI；
     ⑤ `FcitxInputMethodService.kt` 加 `mbmSelfTestRunning`（@Volatile）防递归，
        `StatusAreaWindow.kt` SelfTest 分支加守卫（运行中只打日志不重入，修复
        `return@when` 语法错——when 内不能用 label return，改 if 包裹）；
     ⑥ `PaginationUi.kt` 加 `tabIcon`（ic_baseline_keyboard_tab_24，prevIcon 左侧），
        `PagedCandidatesUi.kt` 绑定 markFeature+logFeature（candidate.tab）+ onNextPage；
     ⑦ `InputView.kt` 扩展：危险键相位切 NumberKeyboard 断 key.number/key.quickphrase
        再切回；status 相位点击 status.selftest 验证防递归日志；clipboardPhase
        扩展 clear/import 点击 + enable 状态断言；clickPickerPhase 遍历扩大
        （Emoji 12 个）；typePinyinForCandidates 音节扩到 10 个（促多页候选）；
        危险键相位 key.lang 点击+长按断 ime.switch/ime.picker；候选相位加
        candidate.tab。
     v3.5 APK=82,315,550 B（10-28 04:42，BUILD SUCCESSFUL 4m21s，dex 全 HIT：
     标记串 clipboard×4/ime×2/status.selftest/candidate.tab/key.quickphrase/key.number
     均在 classes3.dex，FEATURE_TAG_KEY 0x7F0B0001×3，QuickPhraseKey 接线）→
     `https://c.wss.ink/f/l7xu36y4fbn`。
     **settings×5 仍未解**：入口在设置页 MainFragment（SmartDictActivity/
     RuleImportActivity/PinyinCustomPhrase/PasswordGeneratorActivity/HandwritingActivity
     真实存在），自测引擎跑 IME 窗口层够不到设置页 Activity。待设置页自测层
     （入口打 markFeature+logFeature、MainActivity 伴生引用、自测跳转收集真实点击）。
  - **内置自测引擎 v3.6（FAIL=0 第二轮·4 项真机 FAIL 根因修复）**：v3.5 复验
     （10-28 13:29，85.3% 81/95）仍 14 FAIL 的 4 项 IME 层根因定位并修复：
     ① **candidates×6（含 bar.candidate_expand 连带）**：v3.5 引擎守卫已生效
        （诊断「原引擎=keyboard-us→现引擎=pinyin」）但 ni/hao/shi 仍无候选。
        v3.6 在 `typePinyinForCandidates()` 加**字母布局复位**（`switchLayout
        (TextKeyboard.Name)`——键盘相位可能已切符号/数字布局，字母键缺失则
        拼音无法输入）+ **`fcitx.runImmediately { focus(true) }`**（自测无真实
        焦点时 IC 未激活，拼音引擎不组词）+ 失败诊断增强（累计命中/最佳音节/
        现布局/候选窗 visible——下次复验可精确定位）。`KeyboardWindow` 加公开
        `currentLayoutName` 访问器（原 `currentKeyboardName` 是 private，编译
        INVISIBLE_REFERENCE 错）；
     ② **status.selftest**：标记原打在 `StatusAreaAdapter.onItemClick`（点击后
        才生效），自测 status 相位**点击前**收集条目 → 标记缺失。v3.6 把
        markFeature 移到 `onBindViewHolder`（渲染时打标），绑定期即可被收集；
     ③ **clipboard.swipe_delete**：`swipeGesture()` 把触摸事件发给条目视图 v，
        但 ItemTouchHelper 挂在 **RecyclerView**（attachToRecyclerView），事件
        不经 RecyclerView 的 onIntercept/onTouch → onSwiped 不触发。v3.6 把
        手势事件 dispatch 到**条目所在的 RecyclerView 祖先**（generateSequence
        向上找），坐标用条目在 RV 中的中心；
     ④ **picker.longpress:Emoji**：长按监听仅 `policy.popup(item)!=null`（=
        EmojiModifier.produceSkinTones 非空，即可肤色修改 emoji）才绑，Emoji
        首 12 个可能均不可修改。v3.6 把 Emoji 条目**按 produceSkinTones 非空
        优先排序**再长按，保证先试可修改 emoji（Symbol/Emoticon 已 PASS）。
      v3.6 APK=82,319,798 B（10-28 05:56，BUILD SUCCESSFUL 3m54s，dex 全 HIT）→
      `https://c.wss.ink/f/l7yd3jao17p`。
      **settings×5 仍未解**（跨 5 个 Activity：MainFragment 4 项 +
      PinyinCustomPhraseFragment 1 项），需专门设置页自测层（导航栈管理 + 逐页
      收集），下一增量实现。

  - **内置自测引擎 v3.7（FAIL=0 第三轮·settings×5 跨 Activity 自测层 + status.selftest 双计修复）**：
      v3.6 复验（10-28 14:34，PASS=82 FAIL=14 TOTAL=96，85.4%）暴露：
      **status.selftest 双计假 FAIL**（相位 L71 PASS + 专用逻辑 L29-31 FAIL → TOTAL 虚增
      96；clickPhaseWindow 已实测加入 `tested`，专用逻辑重复判定）。v3.7 把 status 相位
      专用逻辑外包 `if ("status.selftest" !in tested)`——一次实测一次判定，消除双计。
      **settings×5 跨 Activity 自测层（v3.7 实现）**：
      ① `PreferenceScreen.kt` 加 **FeaturePreference**（绑定视图时 `markFeature(itemView,
         featureId)`）+ `addPreference(featureId, ...)` 重载（onClick 包装 `logFeature`）；
      ② MainFragment 4 项（ruleimport.action / smartdict.action / password.bridge /
         handwriting.settings）改走 FeaturePreference；PinyinCustomPhraseFragment 用户词库页
         根打 `markFeature("pinyin.phrase")`；
      ③ MainActivity / RuleImportActivity / PasswordGeneratorActivity / HandwritingActivity
         各加伴生 `current`（onResume 设 / onDestroy 清），供自测返回；
      ④ InputView `runSettingsLayer(tested)`：跳设置页后滚动主列表到底（保证入口绑定）→
         逐项真实点击（onClick 内 logFeature 断言跳转响应）→ 返回（用户词库页
         onBackPressed；新 Activity 伴生 current finish）。
      v3.7 APK=82,331,874 B（10-28 07:08，BUILD SUCCESSFUL 3m12s，dex 全 HIT：
      FeaturePreference 在 classes.dex、settings 标记串在 classes3.dex）→
      `https://c.wss.ink/f/l7yvyu6mo85`。
      **剩余 FAIL（待 v3.8）**：candidates×6（hit=3 字母键送达但拼音不组词，疑 fcitx
      IC 无 preedit/自测无真实编辑焦点，需真机诊断）；clipboard.swipe_delete（v3.6 改
      RV dispatch 仍未生效）；picker.longpress:Emoji（v3.6 排序仍未生效）。

  - **内置自测引擎 v3.8（FAIL=0 第四轮·v3.7 复验 91.6% 的 8 项 FAIL 处置）**：
      v3.7 复验（10-28 15:39，PASS=87 FAIL=8 TOTAL=95，91.6%）settings×5 全 PASS +
      status.selftest 双计消除；剩 8 FAIL = candidates×6（含 bar.candidate_expand 连带，
      拼音不组词）+ clipboard.swipe_delete + picker.longpress:Emoji。v3.8 处置：
      ① **picker.longpress:Emoji 根因**——Emoji 条目排序取 emoji 用 `KeyView.def`，
         但 PickerPageUi 所有条目**共享同一 keyAppearance def**（TextKeyView(ctx,theme,
         keyAppearance)，L80/88），`def.displayText` 对所有条目相同 → 排序形同失效，
         试按前 12 个（多为无 modifier base 的笑脸）produceSkinTones 全 null、无 popup
         绑定。**修复**：排序改用 `TextKeyView.mainText.text`（= policy.transform(item)，
         实际 emoji），把 produceSkinTones 非空的提前；
      ② **clipboard.swipe_delete**——onSwiped 埋点存在但从未触发；v3.6 改 RV dispatch
         仍失败。**增强** swipeGesture：8 步平滑 MOVE（ItemTouchHelper 需 MOVE 逐步更新
         滑动位移，位移超 touchSlop 后 onInterceptTouchEvent 才返回 true 拦截、后续事件
         才进 RV.onTouchEvent 即 onSwiped 触发路径；单次大位移 MOVE 可能被条目 child
         消费、RV.onTouchEvent 不达）+ 诊断打 target 类型/条目坐标；
      ③ **candidates×6**——v3.7 诊断 hit=3 仍无候选；onStartInput 仅 `!isNullType` 才
         focus（FcitxInputMethodService.kt:1077），若自测触发字段为 NULL type，IC 无
         preedit、拼音不组词是环境限制。**增强**：拼音输入前 `fcitx.reset()` 清 IC preedit
         残留（键盘相位可能残留提交/组合状态污染组词）+ 读 `currentInputEditorInfo`
         诊断（pkg/inputType/nullType）揭示真机 IC 状态。
      v3.8 APK=82,333,566 B（10-28 08:00，BUILD SUCCESSFUL 3m22s，dex 全 HIT）→
      `https://c.wss.ink/f/l7z94pt4kel`。

  - **内置自测引擎 v3.9（FAIL=0 第五轮·v3.8 复验 92.6% 的 7 项 FAIL 处置）**：
      v3.8 复验（10-28 17:12，PASS=88 FAIL=7 TOTAL=95，92.6%）：**clipboard.swipe_delete
      转 PASS**（v3.8 8 步 MOVE 生效）；剩 7 FAIL = candidates×6（含 bar.candidate_expand
      连带）+ picker.longpress:Emoji。v3.8 复验新诊断 `editorInfo pkg=com.hihonor.notepad
      type=2277537 nullType=false`（L132）——**备忘录正文 CLASS_TEXT，非 NULL type，
      推翻 v3.8「NULL type 环境限制」假设**。v3.9 处置：
      ① **picker.longpress:Emoji 根因**——`PickerData.Emoji`（L96）第一个分类条目全是
         笑脸（无肤色 modifier base，`EmojiModifier.produceSkinTones` 全 null → 长按
         popup 全不绑定）；可肤色的 👋👍 在第二个「人/手势」分类。**修复**
         （InputView.kt:clickPickerPhase L482-490）：`phase == "picker:Emoji"` 时先点
         `picker.tab` 第 2 个切到人/手势分类 → delay(450) → 重新 collectFeatureViews
         收集条目再长按；
      ② **candidates×6**——IC 可组词仍不组词（键盘相位后 IC 可能失焦/未激活）。
         **增强**（L859-872）：拼音输入前经 `currentInputBinding.uid +
         currentInputEditorInfo.packageName` 在 `postFcitxJob { activate(uid, pkgName) }`
         主动重激活输入上下文（`activate` 是 FcitxAPI 方法，postFcitxJob 的 lambda
         receiver 即 FcitxAPI；基类 LifecycleInputMethodService 的 activate 是
         protected，InputView 不能直接调，故走 lambda 内调用）+ 诊断打
         uid/pkg（无 binding 时诊断「IC 未绑定」）。
      ③ **构建坑**：`./gradlew` 出现 `Permission denied`（gradlew 可执行位丢失），
         改用 `sh gradlew`；`--offline` 模式 android-jni transform 输出缺失（fragment/
         viewpager2/gson/serialization 的 NDK 提取变体从未缓存），且首次非离线构建把
         `:plugin:recall:kspReleaseKotlin` 的 UP-TO-DATE 状态冲掉后缺 KSP
         2.0.10-1.0.24 jar（缓存仅 2.3.12）→ 沙箱可直连 maven central（curl 200），
         **去掉 --offline** 让 Gradle 补齐缺失件。
      v3.9 APK=82,336,458 B（10-28 10:44，BUILD SUCCESSFUL 6m，dex HIT：
      「重激活 IC uid=」在 classes3.dex）→ `https://c.wss.ink/f/l80g8wxsg25`。
      **待真机复验**：candidates×6（看新诊断「重激活 IC uid/pkg」+ editorInfo，若激活
      后仍不组词则查 sendKey→拼音引擎 preedit 时序）；picker.longpress:Emoji（切分类
      后长按应命中可肤色 emoji）。

## 78. [UX] 数字键盘重排（图二）+ 计算器候选拦截与直通上屏语义的冲突面（v1.0.39）

- **背景**：用户要求①数字键盘整体改为「图二」样式（左列 % / - + 竖排、数字栅格、
  右上退格、底行「返回 / !?:# / 0 / 空格 / 换行」），②顶部工具条（KawaiiBar）UI 不变，
  ③支持加减乘除：输入 `123+456` 时候选给出【579】与【123+456=579】且结果必须正确，
  全局默认开启、不可关闭。
- **布局**：`NumberKeyboard.kt` 四行重排。与图二唯一差异：右列第 3 键用 `*` 替代 `@`
  （乘号必须可直接输入，用户硬性要求加减乘除）；`QuickPhraseKey`、`, = . "` 移除。
  后果：内置自测 `key.quickphrase` 回归结构性 FAIL（见第 ④ 条注记）。
- **计算器**（`CalcEngine.kt` + `KeyboardWindow.kt`）：
  - 求值器为递归下降 + BigDecimal 精确十进制（除法 16 位有效数字 HALF_UP），
    支持括号 / 一元正负 / 后缀 %（n%=n/100）/ ×÷ 归一化；数字必须形如 `123` 或
    `12.34`（拒绝 `.5`/`5.`/`1.2.3`），除零不出候选——「括号里必须正确」由此保证；
  - 拦截面：仅 `currentKeyboardName==Number` 且 `EditorInfo` 为 `TYPE_CLASS_NUMBER`
    且非密码变体时，NumPadKey 的 keysym 不再发引擎，改为进本地缓冲并渲染在
    KeyboardWindow 顶部候选条（chips：结果 + 算式=结果，点选即 commitText）；
  - 上屏语义与拼音预编辑对齐：空格/换行/切布局先把缓冲**原文**同步上屏再执行原动作
    （`service.commitText` 走 InputConnection 主线程同步写，先于引擎 sendKey，顺序可靠）；
    退格删缓冲末字符；字段切换/键盘收起直接丢缓冲（同拼音未选词丢失语义）；
  - 陷阱：候选点击若走 `fcitx.select(idx)` 会落到 native（native 无此候选，必然失配），
    故候选条为 KeyboardWindow 自绘 View，点击直接 commitText，不经引擎；
  - 电话拨号盘（TYPE_CLASS_PHONE）与数字密码框（TYPE_NUMBER_VARIATION_PASSWORD）
    不启用缓冲，保持直通上屏，杜绝拨号/输 PIN 时字段为空的误用面。
- **文档同步**：PRIVACY.txt（根目录与 assets/licenses 双份）升至 v1.0.39 并新增
  计算器条目；NOTICE.txt 版本行更新；README.md 以当前实现覆盖 v1.0.37 旧描述。
- **【v1.0.39b 覆盖】全局化**：真机验收发现候选条原挂在 KeyboardWindow 内部，
  切到符号键盘（PickerWindow）即消失、QWERTY 上完全不生效。重构为
  `CalcCandidateComponent`（宿主提升到 InputView 键盘区上方，跨窗口常驻），
  新增「光标前文本模式」：监听 onSelectionUpdate，从输入框光标前文本提取尾部
  算式（`getTextBeforeCursor(64)` → 尾串扫描 ≥3 字符且含数字 → CalcEngine 求值），
  点选原子地「删算式+提交」；拼音预编辑非空（preeditEmpty 门控）与密码类字段
  不启用；KeyboardWindow 仅保留缓冲模式的按键拦截委托。
- **【v1.0.39c 覆盖】严格文法**：真机验收指出 878--4=882、5623--4*3/5+8=5633.4
  属「错误算式出结果」（悖论）。求值器收紧：①正负号仅允许式首或开括号后且至多
  一个（连续运算符一律拒绝，负数须写 (-4)）；②后缀 % 至多一个；③括号按高等
  数学规范支持 ( ) [ ] { } 与 |x|：成对、内层层级 ≤ 外层（圆→方→花，f(g(x)) 与
  {[(a+b)c+d]e} 合法，([1+2]) 层级倒置拒绝）、错配拒绝；④全角括号归一化。
  LaTeX/矩阵记法无法在键盘输入，不在支持范围。39 个边界用例全部通过。
