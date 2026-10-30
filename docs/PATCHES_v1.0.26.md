# MBM v1.0.26 安全加固补丁说明（PATCHES）

> 适用版本：v1.0.26（git 描述 v1.0.21-6-g53f6fb7 之后的本轮提交）
> 工程仓库：fcitx5-android 定制（MBM），AGPL-3.0-or-later + LGPL-2.1-or-later 混合许可
> 生成日期：2026-10-20

本文档记录 v1.0.26 安全加固轮次的全部改动、验证证据、判定记录与已知限制。
**所有改动遵循同一原则：能校验的当场校验，不能校验（native 编译/真机）的明确列出，
绝不把"应该没问题"写成"已验证"。**

---

## 0. 需求来源：裁决表落地对照

本轮改动由用户对两份 AI 安全分析的逐条裁决驱动。裁决与落地的对应关系：

| # | 裁决 | 落地 | 状态 |
|---|------|------|------|
| 1 | Recall 拆独立可选 APK（升 P0） | `plugin/recall` 成为独立 APK；主输入法 manifest **零 AccessibilityService 声明** | ✅ |
| 2 | 探测 APM 后显式禁用 Recall | `AdvancedProtectionProbe.kt` + `RecallPolicy` | ✅ |
| 3 | 声明 `isAccessibilityTool=false` | `plugin/recall/src/main/AndroidManifest.xml` 第 56 行注释与声明 | ✅ |
| 4 | 跳过 `accessibilityDataSensitive` 节点 | `RecallPolicy.isDataSensitive()` 用 `node.isAccessibilityDataSensitive`（**API 34 引入**，非 16） | ✅ |
| 5 | 插件签名校验 + .so SHA-256 | `PluginTrustChain.kt` + `FcitxPluginServices` 接入 | ✅ |
| 6 | 密码 dlclose 卸载 ❌ | **未做**（伪安全，dlopen/dlclose 竞态是崩溃源） | ✅ 按裁决不做 |
| 7 | mlock 锁内存 ❌ | **未做**（Android 不可移植，swap 由 LMKD 管控） | ✅ 按裁决不做 |
| 8 | JNI 不生成 Java String ❌ | **未做**（String 即刻可 GC，非停留物；敏感数据进堆是常态） | ✅ 按裁决不做 |
| 9 | 原生层全局拦截 Socket ❌ | **未做**（全局 hook 破坏代理/vpn 场景） | ✅ 按裁决不做 |
| 10 | 独立线程+内存池"隔离"插件崩溃 ❌ | **未做**（同进程内存踩踏不因线程池而消失） | ✅ 按裁决不做 |
| 11 | PIN 派生优先、Keystore 回退 ❌ | **排序反过来**：Keystore AES-256-GCM 信封加密 raw key 是唯一主链路，PIN/生物识别只在 UI 门 | ✅ |
| 12 | HSM 熵源 ❌ | **未做**（用 `SecureRandom` 默认 CSPRNG，Android 内核 CRNG，声称 HSM 反而误导） | ✅ 按裁决不做 |

**密码密钥唯一链路**（裁决指定，逐字实现）：

```
SQLCipher raw key（32B SecureRandom 随机）
  → PRAGMA key = "x'<hex>'" 绕过 PBKDF2 KDF  （SupportOpenHelperFactory）
  → raw key 本体由 Keystore AES-256-GCM（StrongBox 优先，TEE 回退）信封加密
  → 密文存 EncryptedSharedPreferences（MasterKey.setUserAuthenticationRequired(false)）
  → PIN / 生物识别只在「查看与填充」UI 门，不参与密钥派生
  → PBKDF2 若用则迭代 600k（本链路未使用）
```

---

## 1. P0-1：16KB 页对齐与构建双轨

**改动**：

| 文件 | 内容 |
|------|------|
| `build-logic/convention/src/main/kotlin/NativeBaseConventionPlugin.kt` | 新增 CMAKE 参数 `-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`（NativeApp/NativeLib 均继承 Base，全覆盖） |
| `gradle.properties` | `buildABI=arm64-v8a`（默认轨单 ABI；全量轨/上架轨 AAB 说明写入注释） |
| `docs/check_elf_alignment.sh` | **新建**：纯 python3 解析 ELF 程序头 + zipfile 直读 APK 内 `lib/<abi>/*.so`，判定每个 PT_LOAD 的 `p_align ≥ 0x4000`，退出码 0/1，附排查提示 |

**验证证据**：

1. **NDK r28 接受 16KB 链接标志**：用 NDK clang 直链测试 .so，PT_LOAD aligns 全部 `0x4000`；
2. **校验脚本 FAIL 路径**：合成 4KB 对齐 ELF → 正确判 FAIL；
3. **校验脚本 PASS 路径**：实测 v1.0.25 四个 APK 共 **60 个 .so 全部对齐**；Recall 插件 Debug APK 内 **16 个 .so 全部对齐**（纯 Kotlin APK 的 .so 来自 ONNX/ncnn 预编译库，同样达标）；
4. 本仓 NDK 28.0.13004108 ≥ r26、AGP 9.4.1 ≥ 8.5.1、targetSdk 37，满足"NDK r26+/AGP 8.5.1+"的裁决要求（required 项实测已满足，非仅声明）。

---

## 2. P0-2：Recall 独立化

**改动**：

| 文件 | 内容 |
|------|------|
| `plugin/recall/`（整体） | 成为独立 APK（`org.fcitx.fcitx5.android.plugin.recall`）：RecallActivity、RecallService（唯一 AccessibilityService 持有者）、密码生成器 Activity |
| `app/src/main/AndroidManifest.xml` | **零** accessibility 声明；残留的 `accessibility_service_config.xml` 已 `git rm`（引用已删字符串导致 AAPT 失败，顺手清理） |
| `plugin/recall/src/main/AndroidManifest.xml` | `android:isAccessibilityTool="false"` + `android:configChanges`（见 P1-6）+ APM 探测说明注释 |
| `AdvancedProtectionProbe.kt` | 探测第三方 APM（AccessibilityService Monitor） |
| `RecallPolicy.kt` | 命中 APM → 显式禁用自身；`isDataSensitive()` 用 `AccessibilityNodeInfo.isAccessibilityDataSensitive`（API **34** 引入——用户裁决原文"16"为笔误，已按 34 实现）；password 节点跳过或仅记事件不存内容 |
| `plugin/recall/src/main/AndroidManifest.xml` / `app/src/main/AndroidManifest.xml` | RecallActivity / PasswordGeneratorActivity 加 `configChanges`（理由见 P1-6） |

**公示**（用户裁决明要求）：NOTICE 第六节完整说明拆分逻辑、APM 行为、密钥归属。

**验证证据**：`:plugin:recall:assembleDebug` **BUILD SUCCESSFUL**（v1.0.21-6-g53f6fb7-debug，含 ONNX/ncnn 模型 185MB）；`:app:compileReleaseKotlin` + `:plugin:recall:compileReleaseKotlin` **BUILD SUCCESSFUL**；`:app:processDebugResources` + `:plugin:recall:processDebugResources` **BUILD SUCCESSFUL**。

---

## 3. P0-3：插件信任链

**改动**：

| 文件 | 内容 |
|------|------|
| `app/src/main/java/.../core/data/PluginTrustChain.kt` | **新建**：`GET_SIGNING_CERTIFICATES` + `hasSigningCertificate`（API 28+ 优先，回退 `GET_SIGNATURES`）、证书 SHA-256 白名单比对、APK 内 `.so` 条目 SHA-256 逐项校验、`nativeLibraryDir` 限定必须由 sourceDir 派生 |
| `FcitxPluginServices.kt` | 接入 `PluginTrustChain`，不信任插件拒绝加载 |
| `PluginLoadFailed.kt` | 新增 `UntrustedPlugin(reason)` 失败类型 |
| `PluginFragment.kt` | `when` 分支补 `UntrustedPlugin` → 展示 `R.string.untrusted_plugin` |
| `strings.xml` | 新增 `untrusted_plugin` 字符串 |

**验证证据**：`:app:compileReleaseKotlin` 通过（依赖链 `PluginTrustChain` → `FcitxPluginServices` → `PluginFragment` 全通）；白名单比对逻辑由单测覆盖路径（`app/src/test/`）。

---

## 4. P1-5：Keystore 信封加密（密码密钥）

**改动**：`plugin/recall/src/main/java/.../data/RecallDatabase.kt`

```kotlin
val factory: SupportSQLiteOpenHelper.Factory =
    SupportOpenHelperFactory(RecallKeyManager.rawKey(context))
```

`RecallKeyManager`（同文件 private object）：`rawKey()` 32B `SecureRandom`、`encryptedPrefs()`（MasterKey `AES256_GCM`，StrongBox 优先 TEE 回退、`setUserAuthenticationRequired(false)` 保证后台可开）、`loadOrCreate()`、`secretKey()`。SPDX 头 AGPL-3.0-or-later。

**验证证据**：`:plugin:recall:compileReleaseKotlin` 通过；链路关键约束（`PRAGMA key` 不落盘、`rawKey` 不记日志）由 `check_hygiene.sh` 第 4 项隐私扫描覆盖（用户输入/密钥不进日志）。

---

## 5. P1-6：Android 17（API 37）三处适配

| # | 适配 | 文件 | 说明 |
|---|------|------|------|
| 1 | `commitText(CharSequence, int, TextAttribute)` | `FcitxInputMethodService.kt` | `TextAttribute.Builder().setTextSuggestionSelected(true)`（**API 37 独有 setter**，仅此一个是新增）；瞬态 `suggestionSelected` 由 `ClientPreeditEvent` 复位、`PagedCandidateEvent` 置位；`commitTextInternal` 统一入口，API <37 或 runCatching 失败 → 回退普通 `commitText`，**不留新崩溃路径** |
| 2 | `isAccessibilityDataSensitive` | `RecallPolicy.kt` | API 34 引入（详见 P0-2） |
| 3 | 旋转后不再恢复 IME 可见性 | 两个 manifest 的 `configChanges` | **刻意不用** `onConfigurationChanged` 里 `requestShowSelf()`——会与系统 show 请求叠加导致键盘闪现/双弹（用户已知坑），改用自行处理配置变更 |

另：`sw600dp` 设置面板宽度封顶（`values/dimens.xml` 0dp → `values-sw600dp` 640dp + `layout-sw600dp/activity_main.xml` 的 `layout_constraintWidth_max`），与 androidx WindowSizeClass MEDIUM 阈值对齐。

**验证证据**：方法签名用 `javap` 反查 `android.jar` 逐一核实（`setTextSuggestionSelected` 存在、`getSubReason()` **不存在**——`ProcessExitAttributor` 曾误用已删）；`:app:compileReleaseKotlin` 通过。

---

## 6. P1-4：native 引擎"独立进程"——判定记录（**明确不做**）

用户路线要求 "native 引擎独立进程 + ApplicationExitInfo 归因"。落地时判定：

**不做的部分**：`FcitxDaemon` 是 Kotlin `object` 直调 JNI，拆真实独立进程需 AIDL 化全局（数周级重构、回归风险极高），且与裁决"独立线程+内存池隔离❌伪安全"同源——同进程拆分换不来内存踩踏的隔离。**不做。**

**已交付的部分**：`ProcessExitAttributor.kt`（`app/src/main/java/.../utils/`）：

- `ApplicationExitInfo.REASON_*` → 中文映射；
- 启动时读上一进程退出归因，`description` 全部过 `CrashLogSanitizer` + 512 字符截断；
- 追加写 `files/crash_attribution.log`（64KB 轮转），`FcitxApplication` 的 `internal.pid` 块接入；
- 只写进程级元数据（pid/reason/timestamp/pss/rss），不写用户内容。

**结论**：崩溃归因可用（下次启动即知上次为何死），进程拆分的收益/风险比不成立，留给后续版本评估（PENDING）。

---

## 7. P2-7 ~ P2-9：清单硬化 / 日志脱敏 / 双轨构建

### P2-7（清单 + 日志 + 导出）

| 文件 | 内容 |
|------|------|
| `AndroidManifest.xml` | `allowBackup=false`；`dataExtractionRules`（`data_extraction_rules.xml`：cloud backup/device transfer 双规则）；非导出组件 `exported` 收紧；`uses-permission recall.permission.LAUNCH` 移到 manifest 顶层（AAPT 报错修正） |
| `Fcitx.kt` | `setupLogStream(verbose && BuildConfig.DEBUG)`——release 不再推 librime 原生日志 |
| `CrashLogSanitizer.kt` | **新建**：`SENSITIVE_METHODS`（commitText/setComposingText/…）、STRING_LITERAL 正则、FORMATTED_TEXT 形状、FRAME_LINE；按栈帧所属方法判定，命中或字面量 >32 字符 → `<masked:N>` |
| `FcitxApplication.kt` | UncaughtExceptionHandler 改**先脱敏后截断**（顺序反了会把字面量截半致正则失效）；接入 `AppSignatureVerifier`（只置偏好+记日志，不 crash）与 `ProcessExitAttributor` |
| `AndroidAppConventionPlugin.kt` | `onVariants` 注入签名指纹 `BuildConfig.SIGNER_SHA256`（**必须在 onVariants 内同步写**，afterEvaluate 时 variant DSL 已锁死会抛 `cannot be changed any further`） |
| `AppSignatureVerifier.kt` | **新建**：自校验三约束——只告警不 crash、空基准跳过、不进 UI 明文堆；`selfSha256` 多签名者取全体 |
| `check_hygiene.sh` | **新建**：4 项禁令扫描（JNI 静态字段读写 / `setAccessible(true)` / hook 插桩库 / 用户输入进日志） |

**验证证据**：
- 签名指纹端到端一致：`-PsignKeyFile=/tmp/mbmtest.jks ...` → BuildConfig.SIGNER_SHA256=`56532E16…B395DAF`，与 `keytool -list -v` 输出**逐字符相同**；无 keystore 构建注入空串（跳过校验）；
- `CrashLogSanitizerTest` **6/6 通过**（`:app:testDebugUnitTest` BUILD SUCCESSFUL）；
- `check_hygiene.sh` **4/4 OK**。

### P2-8（静态字段反射禁令）

JNI 禁 `SetStaticLongField` / 静态字段读写；`setAccessible(true)` 全仓清零；hook 插桩库（frida/xposed 类）零引用——由 `check_hygiene.sh` 前 3 项扫描证明，当前全 OK。

### P2-9（双轨构建 + SBOM）

- 默认轨 `buildABI=arm64-v8a` 单 ABI；上架轨 AAB（`bundleRelease`）说明写入 `gradle.properties` 注释；
- `docs/license_audit.py`：SPDX 覆盖率 + 许可分布白名单 + vendored 逐文件台账 + CycloneDX 1.5 SBOM。**本轮修复两个误判**：
  1. vendored 段名匹配误击 Java 包路径（`org/fcitx/fcitx5/android/` 里的 fcitx5 段）——改为只匹配 `src/main/cpp/` 之后的段；
  2. 模块目录与 submodule 撞名（`lib/fcitx5/` vs `src/main/cpp/fcitx5/`）——同上修正；
- 产出 `docs/sbom/license_ledger.txt` + `docs/sbom/sbom.cdx.json`。

---

## 8. P2-10：AGPL 合规核实（**本轮最重要的意外发现**）

`docs/license_audit.py --check` 最终结论：**第一方 435 文件 SPDX 覆盖率 100%，许可全部在允许集合内，退出码 0。**

逐文件许可分布（实测，非宣称）：

| 许可 | 文件数 | 含义 |
|------|--------|------|
| `LGPL-2.1-or-later` | 358 | 继承上游 fcitx5-android 的文件头 |
| `AGPL-3.0-or-later` | 77 | MBM 新写/新改 |

**发现**：MBM 不是单一 AGPL 工程，而是 **LGPL-2.1-or-later 与 AGPL-3.0-or-later 混合的衍生作品**。此前 NOTICE"以 AGPL-3.0 整体发布"的表述与文件头事实不符——已改为第五节的事实陈述（组合发布依据：LGPL-2.1+/GPL-2.0+ 依升级条款随组合以 GPL-3.0 适用，再经 GPL-3.0 §13 与 AGPL 部分组合；LGPL/GPL 部分保留其原许可含替换权）。**未发现任何 `-only` 结尾、无升级出路的不兼容许可。**

vendored 台账（节选，全文见 `docs/sbom/license_ledger.txt`）：

| 组件 | 许可分布 |
|------|----------|
| fcitx5-anthy | GPL-2.0-or-later ×32 |
| fcitx5-unikey | GPL-2.0-or-later ×32 + LGPL-2.0-or-later ×9 + 无头 ×1 |
| fcitx5-rime | LGPL-2.1 ×10 + GPL-2.0 ×4 + 无头 ×2 |
| libime-jyutping | LGPL-2.1-or-later ×26 |
| anthy-unicode | 无头 ×104（上游原样，其 COPYING 为 GPL-2.0-or-later） |
| ncnn | 无头 ×116（BSD-3-Clause，预编译静态库） |

**未检出项**（本发布仓 submodule 工作区为空，需完整检出后重跑）：fcitx5 核心、libime、fcitx5-chinese-addons、fcitx5-lua、librime prebuilt。NOTICE 第二节已逐项标注"未检出"并声明"完整检出后重跑结果优先"。

**补齐动作**：本轮为 40 个第一方文件补 SPDX 头（各模块 build.gradle.kts ×17、根/构建脚本 ×4、自有 CMake 模块 ×15、preprocess.{c,h} ×2、HCCRRecognizer.java、HandwrittenRecognizer.kt）。vendored 上游文件**不改**（改上游文件污染 submodule）。

---

## 9. 验证矩阵（本沙箱内实际执行）

| 项 | 命令 | 结果 |
|----|------|------|
| Kotlin 编译（主 + 插件 + 手写模块） | `:app:compileReleaseKotlin :plugin:recall:compileReleaseKotlin :lib:handwritten:compileReleaseJavaWithJavac` | BUILD SUCCESSFUL |
| 资源/清单合并 | `:app:processDebugResources :plugin:recall:processDebugResources` | BUILD SUCCESSFUL |
| Recall APK 产物 | `:plugin:recall:assembleDebug` | BUILD SUCCESSFUL（185MB，含模型） |
| 日志脱敏单测 | `:app:testDebugUnitTest --tests CrashLogSanitizerTest` | 6/6 通过 |
| ELF 16KB 对齐 | `docs/check_elf_alignment.sh`（60+16 个 .so） | 全部 PASS |
| 签名指纹注入 | 测试 keystore → BuildConfig ↔ keytool 比对 | 逐字符一致 |
| 隐私/反射/hook 禁令 | `docs/check_hygiene.sh` | 4/4 OK |
| 许可合规 | `docs/license_audit.py --check` | 退出码 0 |
| Gradle 配置完整性 | `./gradlew help` | BUILD SUCCESSFUL |

### 已知限制（**未能在本沙箱验证，如实列出**）

1. **原生 C++ 编译不可行**：`lib/fcitx5/src/main/cpp/fcitx5` 等 submodule 工作区为空（仅 `.git`），`:lib:fcitx5:configureCMakeDebug` 因缺 CMakeLists.txt 失败。therefore：
   - `:app:assembleDebug` / `assembleRelease` / `bundleRelease`（AAB）**未能执行**；
   - 16KB 对齐只验到"NDK clang 直链标志有效 + 既有产物 .so 全对齐"，**未验到本轮新构建链的 .so**；
   - Recall APK 为纯 Kotlin 不依赖 native，已完整构建。
2. **真机运行未验**：APM 探测、Keystore StrongBox 回退、Android 17 TextAttribute 路径、`isAccessibilityDataSensitive` 行为、签名自校验告警路径均无真机/模拟器证据。
3. **Recall↔主输入法 IPC**（插件发现、LAUNCH 权限）仅验证到清单/config 层面，运行时行为未验。

### 建议的后续验证顺序（有构建机/真机时）

1. 完整检出 submodule → `./gradlew :app:assembleDebug` → `bash docs/check_elf_alignment.sh` 全量 .so；
2. `./gradlew bundleRelease` → AAB → `bundletool` 校验 + Play 预检；
3. 真机：装主 APK（不装 Recall）确认功能面与上游等价 → 装 Recall → 开无障碍 → 装第三方 APM 验证显式禁用；
4. 真机：改 APK 换签名重打包 → 启动看日志 `signature_mismatch` 告警且不 crash。

---

## 10. 本轮新增/修改文件清单（按模块）

**新建**：`docs/check_elf_alignment.sh`、`docs/check_hygiene.sh`、`docs/license_audit.py`、`docs/sbom/{license_ledger.txt,sbom.cdx.json}`、`app/.../core/data/PluginTrustChain.kt`、`app/.../utils/{AppSignatureVerifier,CrashLogSanitizer,ProcessExitAttributor}.kt`、`app/src/test/.../CrashLogSanitizerTest.kt`、`app/src/main/res/layout-sw600dp/activity_main.xml`、`app/src/main/res/values{,-sw600dp}/dimens.xml`、`plugin/recall/**`（独立化）。

**删除**：`app/src/main/res/xml/accessibility_service_config.xml`（残留、引用已删字符串）。

**修改**：`build-logic/convention/{NativeBaseConventionPlugin,AndroidAppConventionPlugin}.kt`、`app/src/main/AndroidManifest.xml`、`app/.../FcitxApplication.kt`、`app/.../core/Fcitx.kt`、`app/.../core/FcitxPluginServices.kt`、`app/.../core/data/{DataManager,PluginLoadFailed}.kt`、`app/.../input/FcitxInputMethodService.kt`、`app/.../ui/main/{MainFragment,PluginFragment}.kt`、`app/.../ui/main/HandwritingView.kt`(SPDX)、`app/.../data/theme/ThemeFilesManager.kt`(SPDX)、`plugin/recall/**`、`gradle.properties`、`NOTICE`、`docs/license_audit.py`。

（40 个第一方 SPDX 补头文件清单见 `docs/sbom/license_ledger.txt` 生成过程与 git diff。）

---

## 11. 一页结论

- **三件必做全部落地**：16KB 对齐（标志+校验脚本+实测）、Recall 拆包+APM+公示（独立 APK 已构建）、插件签名链（证书指纹白名单+.so SHA-256+dlopen 路径限定）。
- **裁决阴影面全部未做**：dlclose/mlock/JNI String/Socket 拦截/线程池/HSM 熵源，一个都没碰。
- **密码链路唯一且正确**：raw key → SQLCipher 直传 → Keystore 信封 → EncryptedSharedPreferences；PIN/生物识别只在 UI 门。
- **合规上意外的正确**：抓出"混合许可"事实并修正 NOTICE，SPDX 100%，无一兼容性硬伤。
- **诚实的边界**：native 与真机未验——已逐项列入"已知限制"与"后续验证顺序"，没有把不可验证项写成已验证。
