# CLAUDE.md — Mixed Bug Method (MBM) 项目规则

> 本文件供 Claude / 各类编码 Agent / Cursor 在**每次开工前自动读取**。
> 与之配套的长期记忆见 `docs/pitfalls.md`（崩溃记录）。

## 项目概要
MBM 是 fcitx5-android 的 MBM 分支：Kotlin UI + C++ 原生（Fcitx5 / libime / librime 等）。
构建与跨平台说明见仓库根 `构建说明书.txt`；崩溃修复记录见 `docs/pitfalls.md`。

## 崩溃处理铁律（最高优先级，所有改动前先遵守）
> 本工程已多次被「没有崩溃栈就猜根因」的 AI 分析带偏，故立此铁律。
1. **没有真实崩溃栈，禁止提出任何修复方案。** 猜测性结论一律标注 `[未证实]`，
   不得写入本文件与 `docs/pitfalls.md`，更不得据此改代码。
2. **结论被新证据证伪时，必须主动删除或标注 `[已证伪-勿采用]`，禁止继续沿用。**
3. 已带真实崩溃栈、且经反编译/源码核对的结论，标注 `[已证实]` 后可沉淀为规则；
   但同一 Activity 的不同崩溃若栈不同，属**不同根因**，不得互相覆盖或合并。
4. 修复涉及公共 UI 基类（如 `MbmActivityUi`）时，必须在基类一次改完，
   **禁止**逐 Activity 打补丁；验证时须覆盖所有复用该基类的页面。

## 主线程 / ANR 铁律（违反会卡死输入法进程，已踩过坑，[已证实]）
> 输入法是系统常驻进程，主线程被阻塞 >5s 即弹「小企鹅输入法无响应」，比闪退更隐蔽。
1. **`AccessibilityService.onAccessibilityEvent()` 回调运行在主线程，绝不可在其内遍历节点树或做 binder IPC。**
   旧实现在回调里直接 `rootInActiveWindow` + 递归 `walk()` 遍历 `AccessibilityNodeInfo` 树，
   每次 `getChild` 都是跨进程 IPC，大页面上百次 → ANR（见 `docs/pitfalls.md` #6）。
   **正确做法**：回调里只做廉价节流/包名判断，真正的遍历与入库用
   `CoroutineScope(Dispatchers.Default).launch { ... }` 丢到后台协程。
2. **任何 Service 回调（`onAccessibilityEvent` / `onInterrupt`）与 `onDestroy` 里禁止 `runBlocking` 同步等待协程。**
   旧 `onDestroy` 用 `runBlocking { cancelAndJoin() }` 在主线程等协程取消，同为阻塞点；改 `cancel()` 异步取消即可。
3. **主线程禁止执行文件 IO / 数据库同步读取**（如审计日志 `recall_audit.log` 读取应放 `Dispatchers.IO`）。
4. **遍历 `AccessibilityNodeInfo` 树必须成对 `recycle()`：** `walk()` 递归返回后立即 `child.recycle()`，
   `rootInActiveWindow` 返回的 root 在 `finally` 中 `root.recycle()`；未回收的节点（含 binder 引用）
   会堆积导致内存泄漏、进程被系统回收（见 `docs/pitfalls.md` #8）。捕获应串行（`Mutex`），
   同一时刻只持有一个 root 在内存。
5. **列表 UI 禁止在主线程高频全量重建：** 数据来自 Room Flow 时，DAO 查询必须 `LIMIT`（如 `LIMIT 300`），
   `render()` 须做内容签名去重（records + 选区未变则跳过重建），否则无障碍服务后台高频 INSERT 会
   让 Flow 反复向主线程发射全表、引发 ANR（见 `docs/pitfalls.md` #8）。协程收集用单一 `flatMapLatest`
   切换查询，禁止每次输入都新开 `lifecycleScope.launch { repeatOnLifecycle }` 导致泄漏。
6. 验证 ANR 修复：开启 Recall 无障碍服务后，在内容密集应用高速滚动，观察是否再弹「无响应」，
   并用 `adb shell am monitor` / `adb bugreport | grep ANR` 确认无新 ANR；长期使用的设备上确认
   `dumpsys meminfo` 的 `Views` 不持续增长。

## Android UI 硬性约束（违反会直接崩溃，已踩过坑）
1. **任何使用 Material 控件的 Activity，其 theme 必须是 `Theme.Material3.*` 或
   `Theme.MaterialComponents.*` 的后代。** 本工程全局应用主题 `Theme.FcitxAppTheme`
   （parent=`Theme.AppCompat.Light.NoActionBar`）**刻意保持 AppCompat 不变**——输入视图
   依赖它；但所有 MBM 设置类 Activity 必须显式绑定 `Theme.MbmActivity`（Material3 后代）。
   **不得**把全局主题改成 Material（会破坏输入视图）。
2. 新增/修改 Activity 时：AndroidManifest 中**不得**使用 `@android:style/...`、
   `Theme.NoDisplay`、`Theme.Translucent` 等平台主题；需要透明/无标题/对话框效果，
   新建 style 且 parent 指向应用主主题（或 `Theme.MbmActivity`），只覆盖
   `windowIsTranslucent`、`windowBackground`、`windowNoTitle` 等 window 级 item。
3. **不得**直接使用 `com.google.android.material.*` 组件
   （MaterialButton / MaterialTextView / MaterialCardView / SwitchMaterial /
   TextInputLayout / MaterialToolbar …），除非已确认该 Activity 主题链继承自 Material
   主主题。优先复用 `ui/common` 下已有封装或 `androidx.appcompat` 控件。
4. 代码里动态创建 View 时，传入的 `Context` 必须是该 `Activity`，
   **禁止**传 `applicationContext`（会丢失主题）。
5. 交付/提交前逐条自检：
   - [ ] 新增/修改的 Activity 的 theme 继承自 Material 主主题（或已确认不会用 Material 控件）
   - [ ] 未引入 Material 专属控件，或主题链已确认匹配
   - [ ] 该 Activity 能从入口冷启动打开且不崩
6. **edge-to-edge（targetSdk 35+）下，所有自定义 `ScrollView`/根 View 的 Activity 必须处理
   `WindowInsets`**：用 `WindowCompat.setDecorFitsSystemWindows(window, false)` +
   `ViewCompat.setOnApplyWindowInsetsListener` 把系统栏 inset 加到根 View 的 padding 上
   （助手已封装 `MbmActivityUi.fitInsets(activity, scroll)`，四个 MBM Activity 均已调用）。
   **禁止**依赖 `android:fitsSystemWindows`（仅对系统根布局有效，自定义组合 View 不生效）。
   **禁止**在深色背景上保留浅色系统栏图标而不设置 `isAppearanceLightStatusBars=false`（会看不清）。

## WindowInsets / 系统栏适配（本工程已踩坑，[已证实]）
> 对应 `docs/pitfalls.md` #5。根因：`WindowInsetsControllerCompat` 构造即解引用
> `PhoneWindow.mDecor`（DecorView），而 DecorView 在 `setContentView` 之后才存在。
1. **`WindowInsetsControllerCompat` 必须在 DecorView 就绪后获取**：`setContentView` 之后 /
   `onAttachedToWindow` / `View.addOnAttachStateChangeListener`（本工程 `MbmActivityUi.fitInsets`
   用 `isAttachedToWindow` + `addOnAttachStateChangeListener` 兜底）均可。**禁止**在 `onCreate` 中、
   `setContentView` 之前构造它。
2. 仅为「避开系统栏」时，优先用 `ViewCompat.setOnApplyWindowInsetsListener` 处理 padding，
   **不**直接操作 `WindowInsetsController`。`WindowInsetsController` 仅用于设置系统栏图标深浅
   （`isAppearanceLightStatusBars` 等），且同样须在 DecorView 就绪后进行。
3. **AndroidX 在 API 35+ 走 `WindowInsetsControllerCompat$Impl35` 分支**（Impl35→Impl31→Impl30
   链式调用 `PhoneWindow.getInsetsController()`），任何 `WindowInsets` 相关改动**必须在 API 35+
   真机/模拟器上验证**，不能只测低版本。
4. 涉及公共 UI 基类（如 `MbmActivityUi.fitInsets`）的修复，必须在基类一次改完，
   **禁止**逐 Activity 打补丁。

## 数据流向硬性约束（用户隐私，违反即「危险」）
> MBM 在本机捕获屏幕文字（Recall）、生成密码——这些都是敏感数据，**用户必须知道去向**。
1. 任何"被动收集"的数据（如 Recall 捕获的屏幕文本）都必须有**显式消费入口**
   （导入/导出/使用），**不得**只存不消费、也不告知用户怎么用。
2. 凡涉及用户数据的"导入 / 导出 / 删除 / 清空 / 同步"操作，**必须**写入本地审计日志
   （`recall/data/RecallAuditLog.kt`，记「时间/动作/条数/去向」，不含明文内容），
   并在 UI 上向用户展示（Recall 页「数据去向」面板）。
3. **所有数据处理必须在本机完成，不新增任何网络权限、不联网、不第三方共享。**
   MBM 的 APK 经 `aapt2 dump permissions` 校验只允许 VIBRATE / POST_NOTIFICATIONS / PLUGIN，
   **禁止**引入 INTERNET / ACCESS_NETWORK_STATE 等网络权限（AndroidManifest 用
   `tools:node="remove"` 已剔除 onnxruntime 注入的权限）。
4. 审计日志只记元信息（动作/数量/去向路径），**禁止**把被捕获的明文内容写入明文日志文件，
   避免二次泄露。

## 数据库 / 原生库硬性约束（违反会直接崩溃，已排查并加固）
> 本工程 Recall 功能使用 SQLCipher 加密数据库（`net.zetetic:sqlcipher-android:4.19.1`
> + Room + `SupportOpenHelperFactory`）。相关代码：`recall/data/RecallDatabase.kt`、
> `recall/data/RecallRepository.kt`、`recall/RecallService.kt`、`ui/main/RecallActivity.kt`。
1. 任何依赖原生库（`.so`）的组件，**必须在 `Application.onCreate` 最前面显式完成加载**，
   且必须早于任何数据库/组件访问；不得依赖"用到时自动加载"。本工程已在
   `FcitxApplication.onCreate` 开头调用 `System.loadLibrary("sqlcipher")`。
   **注意**：SQLCipher 4.x 已移除 `SQLiteDatabase.loadLibs(context)` 方法，不要调用它
   （会编译失败）；4.x 依赖 Android ART 自动加载，提前 `System.loadLibrary` 只是保险。
2. 新增/修改数据库相关代码前，先确认：加载逻辑存在 → 加载早于首次访问 →
   APK 内确实包含对应 ABI 的 `.so`（用 `unzip -l app-release.apk | grep libsqlcipher` 核对）。
3. **不得**修改 `abiFilters` / `splits` / `extractNativeLibs` / `zipalign` 配置，
   除非明确说明对 ABI 覆盖率的影响（改 `extractNativeLibs=false` 且未做 4KB 对齐会导致
   `dlopen failed`）。
4. 遇到 `UnsatisfiedLinkError` / `dlopen failed` / `No implementation found`：
   **先定位是"没加载""没打包"还是"ABI 不匹配"**，再动手；
   **禁止**用降级到明文 SQLite 或 `try-catch` 吞异常来绕过。
5. 涉及数据库的改动**必须**在 release 构建（`minifyEnabled=true` 时）、清数据冷启动下验证，
   不能只跑 debug。本工程当前 release 关闭 minify，但 `app/proguard-rules.pro` 已保留
   `net.zetetic.database.**` 以防未来开启。
6. 每次修复崩溃后，把「现象 / 根因 / 修复 / 验证方式」四行追加进 `docs/pitfalls.md`。

## 改动前工作流（必须遵守）
- 每次改动 UI / Activity / 主题 / 数据库 / 原生库相关代码前：
  1. 先读 `CLAUDE.md`（本文件）与 `docs/pitfalls.md`；
  2. 用一两句话复述你将遵守的上述约束（UI + 数据库/原生库），再动手。
- 若本次修复了崩溃：把「现象 / 根因 / 修复」三行追加进 `docs/pitfalls.md`
  （格式见该文件第一条），并把崩溃栈贴为证据。
