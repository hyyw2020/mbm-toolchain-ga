# PATCHES v1.0.32 —— Android 10–17 兼容性与隐私合规加固

> 记录时间：2026-10-25
> 对应提交：v1.0.31a（d3e52dc）之后的合规整改批次
> 上游基线：fcitx5-android；minSdk=29 / targetSdk=37 / compileSdk=37

本文件是「检查 Android 10–17 兼容性 + 隐私合规」这一轮审计的**判定与整改记录**。
方法论教训（先实证、后归因）与可复用的审计工具结论一并沉淀，供后续版本沿用。

---

## 一、Android 10–17（API 29–37）兼容性结论

### 1.1 Native 层：符号级全量审计（工具：docs/api_audit2.py 方法）

对三个 APK（主输入法、universal、Recall 插件）内**每个 ABI 的每个 .so**：

- 用 `readelf -W --dyn-syms` 取 GLOBAL UND 符号；
- 逐一在「APK 自有库导出符号 ∪ 逐 API 级别 NDK 系统桩库」中解析，取**最早可用 API**；
- WEAK UND 单列（缺失时 GOT 项为 0，不阻断加载）。

**结果：0 个高于 minSdk(29) 的符号、0 个无法解析的符号。**

同时验证：Android 15+（targetSdk≥35）的 16KB 页大小要求 —— 全部 .so 的
PT_LOAD 段对齐均为 0x4000，合规。

> 桩库判据说明：NDK 逐 API 桩库按级别**精确过滤**而非并集（实测 API 29 桩
> 276 符 / API 33 桩 323 符，33 独有的 `APerformanceHint_*`、
> `APermissionManager_checkPermission` 等均不在 29 桩中），因此「符号在
> API N 桩中存在」可作为「API N 起可用」的权威判据。

### 1.2 Java 层：已核查的陷阱清单

| # | 项目 | 结论 |
|---|------|------|
| 1 | PendingIntent FLAG_IMMUTABLE | 全覆盖 |
| 2 | registerReceiver 导出标志 | 均使用受保护系统广播或 ContextCompat 显式 flag |
| 3 | POST_NOTIFICATIONS 运行时申请 | SDK_INT≥33 守卫 + rationale + 拒绝不再问 |
| 4 | parcelable()/serializable() | SDK_INT≥34 走类型化 API，旧版 deprecated 重载 + as? 兜底 |
| 5 | takeScreenshot 3 参重载 | 仅 API 34+ 调用（2 参在 34 已删除），guard 在位 |
| 6 | isAccessibilityDataSensitive | SDK_INT≥34 守卫 |
| 7 | AlarmManager / WorkManager / 前台服务 / 外置存储 | 无依赖 |
| 8 | enableEdgeToEdge / insets | MainActivity 等已适配，MbmActivityUi.fitInsets 统一处理 |
| 9 | 预测性返回（enableOnBackInvokedCallback） | 观察项：targetSdk 37 下由兼容层接管，无崩溃报告 |

### 1.3 手写引擎根因的更正（重要，推翻 v1.0.31a 的归因）

v1.0.31a 的提交说明将手写失败归因为「libhccr.so 引用 API 33 符号导致
Android 10–12 全废」。本轮用本地 NDK 证据链**证伪**该归因：

1. NDK 28 头文件中 `AAsset_getBuffer` 无 `__INTRODUCED_IN` 标注；
2. **API 29 桩库即导出 `AAsset_getBuffer`**（与 `AAsset_seek`、`AAsset_read`
   同在）；
3. 用户设备为 API 31 / API 37，均高于 29；
4. 真机从未在 Android 10 上报告过该失败（该失败早于 v1.0.23 即间歇出现）。

**结论**：v1.0.31a 的符号补丁（4 个 ABI 的 libncnn.a `--redefine-sym` +
垫片函数）对加载稳健性是**无害的加固**（libhccr.so 不再依赖外部镜像对
单一 NDK 符号的解析策略），但它**不是**设备侧失败的原因；已修正
`lib/handwritten/src/main/cpp/hccr_jni.cc` 中的注释表述，删除「API 33
新增」「真机实测」等未经证实的断言，改为经桩库实证的专业描述。

**设备侧真实失败原因仍未定位**（属运行期/设备相关，需真机 logcat，
MBM-HW tag）。v1.0.31a 起失败路径已有 `Log.e`，请复现用户提供日志。

教训（沉淀为纪律）：**先实证、后归因**。符号可用性以逐版本桩库为准，
不以印象或第三方转述为准；注释中的因果断言必须标注证据来源。

---

## 二、隐私合规审计结论与整改

### 2.1 既有优势（核查通过，保持）

| 维度 | 证据 |
|------|------|
| 权限最小化 | 主 APK 仅 VIBRATE / POST_NOTIFICATIONS / QUERY_ALL_PACKAGES（有注释说明的合理场景）+ 两个签名级自定义权限；**无 INTERNET / ACCESS_NETWORK_STATE** |
| 零网络行为 | 全仓库无 HttpURLConnection/okhttp/Socket 调用 |
| 无统计/崩溃 SDK | 依赖仅 androidx 系 + aboutlibraries + timber（仅本地日志） |
| 崩溃日志 | 先脱敏（CrashLogSanitizer）后截断；仅本地 SharedPreferences + LogActivity，无上报 |
| 备份排除 | 主 APK 与 Recall 均 `allowBackup=false` + data-extraction-rules 全目录排除（云备份/换机迁移） |
| 隐私模式 | PasswordFieldDetector 六层本地判定（密码变体 / noPersonalizedLearning / autofill 提示 / 单行无建议启发式 / 密码管理器包名）→ 不学习、不记忆、不索引、不记剪贴板 |
| Recall 加密 | SQLCipher + Room；raw key(32B, SecureRandom) 由 Keystore AES-256-GCM（StrongBox 优先，失败回退 TEE）信封加密后存 EncryptedSharedPreferences；密钥不可导出 |
| Recall 采集边界 | RecallPolicy 唯一事实来源：isPassword / 提示语含「密码」/ isAccessibilityDataSensitive(API34+) / 金融包名清单+关键词 / FLAG_SECURE 平台脱敏 / 用户自定义排除；隐私窗口返回 null 而非空串（防 OCR 兜底绕过隐私闸） |
| Recall 高级保护模式 | APM 开启时整体停用 + 常驻提示，不静默降级 |
| Recall 截图 | 无障碍自带 takeScreenshot（无需录屏权限），OCR 后 bitmap/hardwareBuffer 立即回收，不落盘 |
| 知情同意 | Recall 默认关闭，需系统无障碍设置显式开启；系统设置页展示服务描述；应用内常驻「不会读取什么」公示 |
| 节点卫生 | AccessibilityNodeInfo 递归回收（密码/敏感子树走 recycleChildren） |
| AGPL 合规 | 逐文件 SPDX 台账 + NOTICE + assets/licenses 三件套随 APK 分发，应用内离线可查 |

### 2.2 本轮发现并修复的缺口

| # | 缺口 | 风险 | 整改 |
|---|------|------|------|
| G1 | Recall APK 合并清单带入 onnxruntime AAR 的 **INTERNET / ACCESS_NETWORK_STATE** | 违反最小必要原则；用户视角「屏幕记忆可联网」与事实不符 | manifest `tools:node="remove"` 显式移除 |
| G2 | onnxruntime **TelemetryInitializer** Provider 被合并 | 可选 Microsoft 遥测的入口 | `tools:node="remove"` 移除 |
| G3 | Recall APK 带入 **USE_BIOMETRIC / USE_FINGERPRINT** | 权限已声明但「查看与填充」凭证门尚未接入 | `tools:node="remove"` 移除；门控实装时需重新声明（注释已说明） |
| G4 | 「关于 → 隐私政策」跳转**上游** fcitx5-android 政策页 | 未覆盖 MBM 特有数据处理（屏幕记忆、剪贴板历史、手写、密码生成器）；且需联网 | 新增 PRIVACY.txt（随 APK 分发）→ LicenseTextActivity 离线渲染；RecallActivity 加「隐私政策」对话框；上游链接降级为文末附注 |
| G5 | v1.0.31a 注释含未证实因果断言 | 误导后续维护 | 按 §1.3 更正 |

### 2.3 残留事项（不阻塞发布，后续版本处理）

| # | 事项 | 说明 |
|---|------|------|
| R1 | Recall「查看与填充」设备凭证门 | SQLCipher 已加密落盘；接入 BiometricPrompt 后需重新声明 USE_BIOMETRIC/USE_FINGERPRINT |
| R2 | Recall APK 无开源许可查看器 | assets/licenses 三件套已随包，但无界面入口；主 APK 可查看。下版补 |
| R3 | 剪贴板历史/用户词库存于应用私有目录未透明加密 | 依赖 Android 沙箱 + 文件级加密；Recall 模块已 SQLCipher。如后续升级主库加密，需评估性能 |
| R4 | 版本元数据 | 内部 versionName 由 `git describe` 生成（v0.001-N-g…），与分发文件名（v1.0.31a）不一致；建议打 tag v1.0.31 对齐 |
| R5 | 手写设备侧失败根因 | 需真机 logcat（MBM-HW tag），见 §1.3 |

---

## 三、本轮改动清单

1. `lib/handwritten/src/main/cpp/hccr_jni.cc` —— 垫片注释更正（§1.3）
2. `plugin/recall/src/main/AndroidManifest.xml` —— 移除 INTERNET /
   ACCESS_NETWORK_STATE / USE_BIOMETRIC / USE_FINGERPRINT /
   ai.onnxruntime.TelemetryInitializer（G1–G3）
3. `PRIVACY.txt`（仓库根，新增）—— 隐私政策全文，随两个 APK 分发
4. `app/build.gradle.kts` / `plugin/recall/build.gradle.kts` ——
   syncLicenseAssets 增加 PRIVACY.txt
5. `app/.../ui/main/LicenseTextActivity.kt` —— 新增 ASSET_PRIVACY
6. `app/.../ui/main/AboutFragment.kt` —— 「隐私政策」改指向本地离线全文
7. `plugin/recall/.../ui/main/RecallActivity.kt` —— 新增「隐私政策」
   按钮与离线对话框（读取 assets/licenses/PRIVACY.txt，失败退回服务描述+公示）
8. `plugin/recall/src/main/res/values/strings.xml` —— 新增
   recall_privacy_policy
9. `NOTICE` —— 版本头更新至 v1.0.32，标注隐私政策随包分发

## 四、复现与验证命令

```bash
# 合并清单核查（G1–G3 修复后应为空）
aapt2 dump badging <apk> | grep -E "INTERNET|ACCESS_NETWORK|BIOMETRIC|FINGERPRINT"
# 隐私政策随包
unzip -l <apk> | grep PRIVACY
# 16KB 对齐
for so in lib/arm64-v8a/*.so; do readelf -lW $so | grep LOAD | awk '{print $NF}'; done | sort -u
```
