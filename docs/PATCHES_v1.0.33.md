# PATCHES v1.0.33 —— 残留事项整改：凭证门 / 许可查看器 / 手写加固

> 记录时间：2026-10-25
> 对应提交：v1.0.33（基于 v1.0.32 `3ff4692`）
> 上游基线：fcitx5-android；minSdk=29 / targetSdk=37 / compileSdk=37

本文件是 v1.0.32 审计报告 §4.5 残留事项（R1/R2/R5）与打包正确性问题的整改记录。

---

## 一、R1：Recall「查看与填充」设备凭证门（已实装）

- **改动**：`RecallActivity` 新增锁定浮层 + `BiometricPrompt`
  （`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`）；验证通过后才 `observe()`
  加载记录，锁定期间时间线不渲染、不加载。
- **设计约束（与 RecallKeyManager 密钥链路裁决一致）**：门只挡 UI，
  不参与 DB 密钥派生（IME/插件进程最容易被杀，密钥只驻留内存会反复
  要求重输 PIN）。
- **降级路径**：`BiometricManager.canAuthenticate` 非 SUCCESS（设备未
  设置任何凭证）时跳过验证门，保持可用并记审计日志（数据仍有
  SQLCipher 静态加密兜底）。
- **权限**：恢复 `USE_BIOMETRIC` / `USE_FINGERPRINT` 声明
  （v1.0.32 曾因门控未接入而移除；注释已说明来龙去脉）。
- **审计**：验证通过 / 跳过均写 RecallAuditLog（ACTION_UNLOCK）。

## 二、R2：Recall 开源许可查看器（已补齐）

`RecallActivity` 新增「开源许可」入口：对话框列出 AGPL-3.0 全文 /
NOTICE / 上游清单，均读自随包分发的 `assets/licenses/`，离线可查。
与隐私政策共用 `showTextDialog()`（可滚动、可选中复制）。

## 三、R5：手写引擎加载加固（根因仍未定位，先加固+可观测）

设备侧间歇性「手写引擎初始化失败」的真实原因**仍未定位**（v1.0.31a
的符号归因已证伪，见 pitfalls #73）。本轮做两件确定有益的事：

1. **短读循环**（`hccr_jni.cc` `read_asset`）：旧代码单次
   `AAsset_read` 读不满即判失败。压缩存储的 asset 受内部解压缓冲块
   大小限制会短读，这是间歇性失败的可疑诱因之一；现循环读满为止。
2. **分级错误日志**（tag `MBM-HW`，与 Kotlin 侧 HandwritingWindow
   一致）：`nativeInit` 六个失败点分别打日志——
   `asset_manager_null` / `param_name_null` / `param_asset` /
   `load_param(rc,bytes)` / `model_name_null` / `model_asset` /
   `load_model(rc,bytes)`；成功时打 `init ok`（param/bin 字节数 +
   线程数）。真机复现时按《测试指引》§9 抓日志即可定位真实成因。
3. 修正了日志实现中的一个生命周期错误：字符串必须在
   `ReleaseStringUTFChars` **之前**打印（释放后指针已失效）。

## 四、打包正确性：Universal 包只在全 ABI 构建时产出

`NativeBaseConventionPlugin`：`isUniversalApk = (buildAbiOverride == null)`。
此前 `buildABI=arm64-v8a` 单架构快速构建也会产出 universal 包——那是
一个「只有单 ABI 却名为通用包」的误导性产物，装到别的架构设备上必缺
.so。现仅在未设置 ABI override（全 ABI 构建）时产出 universal。

**构建验证中发现的覆盖盲区（tag 前补丁）**：`plugin/recall` 不应用
native-base 约定插件（其 `app-convention` 不含该插件），v1.0.28 在模块
自己的 `splits` 块里硬编码 `isUniversalApk = true`，约定插件的修复管不到
它。该 universal 是真·全 ABI 包（4 架构 16 个 .so，实测 197MB），并非
「单 ABI 冒充」，但体积超 Gitee release 100MB 附件上限、本就无法随
release 分发，在只做 arm64 分发的发布流程里属于纯死产物，且易被误选。
已将 recall 的 splits 判据改为与约定插件同源（env `BUILD_ABI` →
property `buildABI`，语义同 `Utils.epn`）：仅全 ABI 构建时产出
universal（保留 v1.0.28「供模拟器 / x86」的意图），单架构快速构建只出
对应 ABI 分包。重建验证：override 构建下 recall 仅产 arm64 包。

## 五、工程沉淀

- `docs/api_audit2.py` 入库：native 符号级 API 兼容审计工具
  （NDK 逐版本桩库判据），PATCHES 文档此前引用但未随仓库分发。
- 版本元数据：补打 tag `v1.0.32`（c7ef28e）与 `v1.0.33`（本提交），
  使 `git describe` 生成的内部 versionName 与分发版本号对齐
  （审计报告残留事项 R4）。

## 六、构建验证整改（tag 前补丁）

首次构建 v1.0.33 时 `:plugin:recall:compileDebugKotlin` 失败，两处问题：

1. **BiometricPrompt 构造器类型不匹配**：`RecallActivity` 继承
   `ComponentActivity`，而 `androidx.biometric.BiometricPrompt` 的公开构造器
   只接受 `FragmentActivity` 或 `Fragment`（内部借 `FragmentManager` 在配置
   变更后仍能派发回调）。已改为继承 `FragmentActivity`（`ComponentActivity`
   的子类，不引入主题约束，`lifecycleScope` 等能力不变）。依赖侧
   `libs.androidx.appcompat` 已传递带入 `androidx.fragment`，编译可见。
2. **NOTICE 失实声明**：自有代码表原写 `data/password/`「Keystore 信封加密、
   SQLCipher raw key」，但该包实际只有密码字段检测 / 填充桥接 / 本地随机
   生成器（纯内存运算，不持久化、不联网），无任何加密引用。加密链路的真实
   位置是 `plugin/recall/.../data/RecallDatabase.kt`（NOTICE 第三方小节
   已有准确描述并交叉引用）。已改为如实描述，权限归属与代码一致。

**验证工具缺陷（tag 后补丁）**：`docs/api_audit2.py` 原只审计第一个 APK
参数（第二个参数被当作汇总标签），两包联跑时 recall 的 4 个 .so 被漏审、
汇总行却显示审过。已改为遍历所有入参；修复后分别重审：app 26 库 /
recall 4 库，above-minSdk 0、unknown 0。另 recall 模块 universal 包
覆盖盲区见第四节。

## 七、仍未完成

| # | 事项 | 状态 |
|---|------|------|
| R3 | 主输入法剪贴板历史/词库透明加密 | 待评估性能代价（Recall 已 SQLCipher） |
| R5 | 手写设备侧失败根因 | 待真机日志（v1.0.33 起有分级日志） |
| R6 | `androidx.security:security-crypto` 弃用 | `MasterKey`/`EncryptedSharedPreferences` 已被 Google 标记弃用（本轮构建 12 条弃用警告），API 29–37 仍正常工作；待官方给出迁移路径后评估（Recall 数据库口令信封加密依赖它） |

## 八、复现与验证命令

```bash
# 凭证门：装 recall APK → 打开时间线 → 应弹出设备凭证验证
# 许可查看器：时间线页底部「开源许可」→ 三个条目均可离线打开
# 手写日志：见 100MB无上限剪贴板-测试指引.md §9
aapt2 dump badging <apk> | grep -E "uses-permission|INTERNET"
```
