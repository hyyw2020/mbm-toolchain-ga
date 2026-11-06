# MBM 输入法（fcitx5-android 分支）v1.0.73

fcitx5-android 的 MBM 定制分支。发布仓库为私有仓库，仅仓库所有者可访问；
每个发布版本均附源码快照，以满足自研部分 AGPL-3.0-or-later 的对应源码要求。

> 本 README 以 v1.0.73 实际实现为准。历史变更归档见 `docs/PATCHES_*.md`
> 与 `docs/pitfalls.md`；隐私政策见 `PRIVACY.txt`，许可与第三方组件清单见
> `LICENSE` / `NOTICE` / `docs/upstream.md`。

## 下载

进入 Releases 页面下载 `v1.0.73-toolchain-ga` 的附件（已签名，可直接覆盖安装）：

| 文件 | 说明 |
| --- | --- |
| `org.fcitx.fcitx5.android-1.0.73-arm64-v8a-release.apk` | 主程序，arm64-v8a 单 ABI，已签名 |
| `org.fcitx.fcitx5.android-1.0.73-arm64-v8a-release.tar.gz` | APK + 校验和打包 |
| `mbm-src-v1.0.73.tar.gz` | 对应源码快照（AGPL §6 / LGPL §4） |
| `SHA256SUMS.txt` | SHA-256 校验和 |

另可通过 WorkBuddy 分发 Portal 获取相同文件（下载链接需带 sharecode）。

## 本期变更（v1.0.73）：默认支持全部表情 + 用户自定义图像表情

- **默认支持所有 Unicode Emoji 序列**：新增零 Android 依赖的
  `core/EmojiSequence.kt`，按 Unicode 码点结构判定表情（ZWJ 组合、
  Fitzpatrick 肤色、VS15/VS16、键帽、Regional Indicator 区旗、TAG 序列，
  依据 The Unicode Standard / UTS #51 与 CLDR RGI）。`EmojiModifier` 不再用
  `Paint.hasGlyph(整串)` 裁剪——该调用对多码点序列恒为 false，会把新版
  复合表情静默丢弃；`hideUnsupportedEmojis` 默认值由 `true` 改为 `false`。
  文本通道走标准 `commitText` 原样上屏，宿主字体缺字形属渲染层问题。
- **用户自定义图像表情（开放容器）**：新增 `CustomEmojiProtocol`（纯数据层）、
  `CustomEmojiRepository`（本机仓库）、`EmojiRichContentCommitter`、
  `CustomEmojiImportActivity`。资产 100% 由用户运行时通过系统文件选择器
  （SAF，单图或 ZIP）导入，落在 `filesDir/custom_emoji/` 并附 `pack.json`
  清单。**APK 不预置、不内置、不下载任何第三方专有表情包**，也不新增
  系统权限。安全边界：文件名净化、MIME/扩展名白名单、8MiB 单文件 /
  64MiB 总量 / 500 条上限、Zip Slip 防护、清单逐条容错。
- **富媒体输入/输出闭环**：目标编辑器声明 `contentMimeTypes` 时，用
  `InputConnectionCompat.commitContent` + `FileProvider` `content://` URI
  临时读授权上屏图像；不支持时按开关降级为文本短码，全程异常安全。
  表情面板动态追加「自定义表情」分类并渲染缩略图；剪贴板复制侧识别
  `ClipData.Item.uri` 并导入容器，实现「复制表情后也能完美显示」。
- **合规**：第一方代码、注释与随版本分发文档中不含任何第三方应用名称，
  仅出现 The Unicode Standard / CLDR / AOSP / AndroidX / JetBrains
  kotlinx.serialization 等公开标准与开源组件名称及 SPDX 许可标识。
  `PRIVACY.txt` / `NOTICE` / `LICENSE` / `docs/upstream.md` 同步至 v1.0.73，
  SBOM 与逐文件许可台账已重跑生成（第一方 477 文件带 SPDX 头、0 缺失）。
- 全量 JVM 单测 **122/122 通过**（原 100 + 新增 22），`compileDebugKotlin`
  与 `testDebugUnitTest` 均 BUILD SUCCESSFUL。

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

Release 附件已签名。证书与 v1.0.70/71/72 一致，覆盖安装无需卸载旧版、
不丢本机学习数据；换装不同签名版本需先卸载。

## 构建方式

```bash
cd mbm-gh
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export BUILD_ABI=arm64-v8a BUILD_VERSION_NAME=1.0.73 \
  SIGN_KEY_FILE=/path/to/key.jks SIGN_KEY_PWD=... SIGN_KEY_ALIAS=...
ANDROID_HOME=/opt/android-sdk sh gradlew :app:compileReleaseKotlin :app:assembleRelease \
  --no-daemon -PbuildVersionName=1.0.73
```

不带签名环境变量时产物为未签名 APK，需自行 `zipalign + apksigner` 签名。

## 说明

- 单一 `app` 模块：Recall 屏幕记忆已内嵌同进程（v1.0.36 起，无独立插件 APK）
- 单 ABI（arm64-v8a），不包含 32 位库
- 全离线：最终 APK 不含 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
  无任何预置训练语料，学习数据全部运行时写入本机应用私有目录
- 许可：逐文件 SPDX 头（LGPL-2.1-or-later 继承上游 + AGPL-3.0-or-later MBM 新写），
  台账见 `docs/sbom/`；第三方组件清单见 `app/src/main/assets/licenses/NOTICE.txt`
