# MBM 输入法（fcitx5-android 分支）v1.0.74

fcitx5-android 的 MBM 定制分支。发布仓库为私有仓库，仅仓库所有者可访问；
每个发布版本均附源码快照，以满足自研部分 AGPL-3.0-or-later 的对应源码要求。

> 本 README 以 v1.0.74 实际实现为准。历史变更归档见 `docs/PATCHES_*.md`
> 与 `docs/pitfalls.md`；隐私政策见 `PRIVACY.txt`，许可与第三方组件清单见
> `LICENSE` / `NOTICE` / `docs/upstream.md`。

## 下载

进入 Releases 页面下载 `v1.0.74-toolchain-ga` 的附件（已签名，可直接覆盖安装）：

| 文件 | 说明 |
| --- | --- |
| `org.fcitx.fcitx5.android-1.0.74-arm64-v8a-release.apk` | 主程序，arm64-v8a 单 ABI，已签名 |
| `org.fcitx.fcitx5.android-1.0.74-arm64-v8a-release.tar.gz` | APK + 校验和打包 |
| `mbm-src-v1.0.74.tar.gz` | 对应源码快照（AGPL §6 / LGPL §4） |
| `SHA256SUMS.txt` | SHA-256 校验和 |

另可通过 WorkBuddy 分发 Portal 获取相同文件（下载链接需带 sharecode）。

## 本期变更（v1.0.74）：修复「拆分选词」整屏小点点

- **真 bug 修复（用户录屏实测反馈）**：反复点「拆分选词」时词块全是一排
  `•` 小点点，看不出字、也无法选词。根因是 v1.0.45~73 的
  `WordSelectWindow` 直接把全局偏好 `clipboardMaskSensitive`（`AppPrefs`
  中默认 `true`）当作「当前条目是否敏感」传给 `WordSelectUi`，后者据此把
  每个词块整体替换成 `•`；而剪贴板列表的正确语义是
  `entry.sensitive && 用户打码偏好`——只有系统 `EXTRA_IS_SENSITIVE` 标记为
  敏感的条目才遮蔽。默认配置下任何普通文本都会被整屏打码，功能等于不可用。
- **修复方式（零回归）**：新增零 Android 依赖的 `WordSelectMaskPolicy`，
  由 `ClipboardWindow` 按 `entry.sensitive && clipboardMaskSensitive` 计算后
  显式传入。普通条目在任何偏好下都显示原文；敏感条目在偏好开启时仍打码，
  隐私保护不回退。`WordSelectWindow` 构造新增参数（默认 `false`），避免
  后续调用点漏传再次误打码。
- **回归单测**：新增 `WordSelectMaskPolicyTest`（6 个 JVM 单测），覆盖
  「普通条目 + 默认开关 true → 显示原文」这一用户实际场景、与列表一致的
  真值表、中文/标点/Emoji/换行原样保留、遮蔽长度上限与不泄漏原字符。
- **一行未动的部分**：候选词排序与置顶、用户词库、上下文预测、表情链路、
  剪贴板历史与备份规则、权限清单全部保持原样；不新增系统权限、不联网。
- 全量 JVM 单测 **128/128 通过**（原 122 + 新增 6），`compileDebugKotlin`
  与 `testDebugUnitTest` 均 BUILD SUCCESSFUL；SBOM 与逐文件许可台账已重跑
  （第一方 479 文件带 SPDX 头、0 缺失）。

> 上期（v1.0.73）：默认支持全部 Unicode Emoji 序列、用户自定义图像表情
> 开放容器与 Rich Content 富媒体输入输出闭环，详见该版本 Release Notes。

## 校验

```bash
sha256sum -c SHA256SUMS.txt
```

## 工具链

| 组件 | 版本 |
| --- | --- |
| JDK | Zulu 27.0.1 (27.30+107-CA) |
| Gradle | 9.8.2 (all) |
| AGP | 9.5.0 (GA) |
| NDK | 30.0.16248370 (r30) |
| CMake | 4.4.5 |
| compileSdk / targetSdk | 37 / 37 |

## 安装

Release 附件已签名。证书与 v1.0.70~73 一致，覆盖安装无需卸载旧版、
不丢本机学习数据；换装不同签名版本需先卸载。

## 构建方式

```bash
cd mbm-gh
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export BUILD_ABI=arm64-v8a BUILD_VERSION_NAME=1.0.74 \
  SIGN_KEY_FILE=/path/to/key.jks SIGN_KEY_PWD=... SIGN_KEY_ALIAS=...
ANDROID_HOME=/opt/android-sdk sh gradlew :app:compileReleaseKotlin :app:assembleRelease \
  --no-daemon -PbuildVersionName=1.0.74
```

不带签名环境变量时产物为未签名 APK，需自行 `zipalign + apksigner` 签名。

## 说明

- 单一 `app` 模块：Recall 屏幕记忆已内嵌同进程（v1.0.36 起，无独立插件 APK）
- 单 ABI（arm64-v8a），不包含 32 位库
- 全离线：最终 APK 不含 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
  无任何预置训练语料，学习数据全部运行时写入本机应用私有目录
- 许可：逐文件 SPDX 头（LGPL-2.1-or-later 继承上游 + AGPL-3.0-or-later MBM 新写），
  台账见 `docs/sbom/`；第三方组件清单见 `app/src/main/assets/licenses/NOTICE.txt`
