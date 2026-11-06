# MBM 输入法（fcitx5-android 分支）v1.0.75

fcitx5-android 的 MBM 定制分支。发布仓库为私有仓库，仅仓库所有者可访问；
每个发布版本均附源码快照，以满足自研部分 AGPL-3.0-or-later 的对应源码要求。

> 本 README 以 v1.0.75 实际实现为准。历史变更归档见 `docs/PATCHES_*.md`
> 与 `docs/pitfalls.md`；隐私政策见 `PRIVACY.txt`，许可与第三方组件清单见
> `LICENSE` / `NOTICE` / `docs/upstream.md`。

## 下载

进入 Releases 页面下载 `v1.0.75-toolchain-ga` 的附件（已签名，可直接覆盖安装）：

| 文件 | 说明 |
| --- | --- |
| `org.fcitx.fcitx5.android-1.0.75-arm64-v8a-release.apk` | 主程序，arm64-v8a 单 ABI，已签名 |
| `org.fcitx.fcitx5.android-1.0.75-arm64-v8a-release.tar.gz` | APK + 校验和打包 |
| `mbm-src-v1.0.75.tar.gz` | 对应源码快照（AGPL §6 / LGPL §4） |
| `SHA256SUMS.txt` | SHA-256 校验和 |

另可通过 WorkBuddy 分发 Portal 获取相同文件（下载链接需带 sharecode）。

## 本期变更（v1.0.75）：修复「复制表情完全失效 / 输出乱码」

用户实测：复制聊天应用里的表情后，粘贴出来是 `¨Ľ🐸Ľ` 之类的乱码、
图片显示为空占位方格，输入法「根本不支持表情的复制显示和输出」。核对代码
后确认是三个真缺陷，全部修复（不改变任何既有输入功能）：

- **真 bug ①（复制侧窄门，失效主因）**：`captureImageSticker` 旧实现只认
  「剪贴板声明 image 类 MIME + `ClipData.Item.uri`」这一种形态，第一步就
  `return`。而宿主应用复制表情时几乎都不这样：content URI 不带 image 声明、
  URI 藏在纯文本 / URI 列表 / HTML `img src`、或 Intent 型 ClipData。
  现在枚举所有 Item 的 `uri / text / htmlText / intent.data`，用纯函数提取
  content URI 候选；新增零 Android 依赖的 `ClipboardStickerExtractor`，以
  文件头魔数嗅探真实位图格式（PNG/JPEG/GIF/WebP/BMP/HEIC/AVIF），仓库
  单流读取落盘。**不信任剪贴板声明的 MIME**，短码/乱码/HTML 一律不入库，
  避免把乱码当表情存进去。
- **真 bug ②（输出侧乱码）**：多数聊天输入框不声明
  `EditorInfo.contentMimeTypes` 的 image 能力，旧版此时降级提交文本短码
  `:[label]:`，正是用户看到的乱码、等于输出失效。改为三段式通用输出：
  `commitContent` 富媒体直投 → 不支持则把图像以 content URI **写回系统
  剪贴板**（`ClipData` + image 类 MIME + 临时读授权，任意输入框长按粘贴
  即可贴出原图）→ 文本短码降级**默认关闭**。
- **真 bug ③（静默无反馈）**：旧版接住也只 `bumpRevision()` 静默入库，
  键盘无任何提示、不能感知。现在捕获成功注册即时提示，明确告知「已加入
  表情面板可用」。
- **合规与边界**：全程不联网、不解析网络链接；代码/注释/文档不含任何
  第三方应用名称，仅使用公开标准与 AOSP/AndroidX 组件名及 SPDX 标识；
  不预置、不内置、不下载任何第三方专有表情素材。宿主应用若不写入系统
  剪贴板、或只写私有短码/加密素材，则平台层无法取得图像字节——这是
  Android 平台边界，任何输入法均无法还原。
- **回归单测**：新增 `ClipboardStickerExtractorTest`（魔数嗅探、多形态
  URI 提取、短码不误判、扩展名白名单对齐）。全量 JVM 单测 **143/143 通过**
  （原 128 + 新增 15）；SBOM 与台账重跑（第一方 481 文件带 SPDX、0 缺失）。
- **一行未动的部分**：候选词排序与置顶、用户词库、上下文预测、Unicode
  Emoji 序列判定、剪贴板历史与备份规则、权限清单全部保持原样；
  不新增系统权限、不联网。

> 上期（v1.0.74）：修复「拆分选词」整屏小点点；再上期（v1.0.73）：默认
> 支持全部 Unicode Emoji 序列、用户自定义图像表情开放容器与 Rich Content
> 富媒体闭环。详见各版本 Release Notes。

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

Release 附件已签名。证书与 v1.0.70~74 一致，覆盖安装无需卸载旧版、
不丢本机学习数据；换装不同签名版本需先卸载。

## 构建方式

```bash
cd mbm-gh
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export BUILD_ABI=arm64-v8a BUILD_VERSION_NAME=1.0.75 \
  SIGN_KEY_FILE=/path/to/key.jks SIGN_KEY_PWD=... SIGN_KEY_ALIAS=...
ANDROID_HOME=/opt/android-sdk sh gradlew :app:compileReleaseKotlin :app:assembleRelease \
  --no-daemon -PbuildVersionName=1.0.75
```

不带签名环境变量时产物为未签名 APK，需自行 `zipalign + apksigner` 签名。

## 说明

- 单一 `app` 模块：Recall 屏幕记忆已内嵌同进程（v1.0.36 起，无独立插件 APK）
- 单 ABI（arm64-v8a），不包含 32 位库
- 全离线：最终 APK 不含 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
  无任何预置训练语料，学习数据全部运行时写入本机应用私有目录
- 许可：逐文件 SPDX 头（LGPL-2.1-or-later 继承上游 + AGPL-3.0-or-later MBM 新写），
  台账见 `docs/sbom/`；第三方组件清单见 `app/src/main/assets/licenses/NOTICE.txt`
