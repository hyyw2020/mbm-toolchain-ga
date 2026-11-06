# MBM 输入法（fcitx5-android 分支）v1.0.76

fcitx5-android 的 MBM 定制分支。发布仓库为私有仓库，仅仓库所有者可访问；
每个发布版本均附源码快照，以满足自研部分 AGPL-3.0-or-later 的对应源码要求。

> 本 README 以 v1.0.76 实际实现为准。历史变更归档见 `docs/PATCHES_*.md`
> 与 `docs/pitfalls.md`；隐私政策见 `PRIVACY.txt`，许可与第三方组件清单见
> `LICENSE` / `NOTICE` / `docs/upstream.md`。

## 下载

进入 Releases 页面下载 `v1.0.76-toolchain-ga` 的附件（已签名，可直接覆盖安装）：

| 文件 | 说明 |
| --- | --- |
| `org.fcitx.fcitx5.android-1.0.76-arm64-v8a-release.apk` | 主程序，arm64-v8a 单 ABI，已签名 |
| `org.fcitx.fcitx5.android-1.0.76-arm64-v8a-release.tar.gz` | APK + 校验和打包 |
| `mbm-src-v1.0.76.tar.gz` | 对应源码快照（AGPL §6 / LGPL §4） |
| `SHA256SUMS.txt` | SHA-256 校验和 |

另可通过 WorkBuddy 分发 Portal 获取相同文件（下载链接需带 sharecode）。

## 本期变更（v1.0.76）：一轮真 bug 修复，不改变任何既有功能

按「只修确证缺陷、不新增也不裁剪功能」对本机表情与剪贴板链路做一轮复核，
共修 5 类真 bug（新增代码均为纯 JVM 策略或防御性加固，行为与开关语义不变）：

- **真 bug ①（表情功能永久失效，用户反馈「完全失效」的残留根因）**：
  `CustomEmojiRepository.importClipboardImage` 开头有一句「已达 500 条就
  `return false`」，而函数尾部又写了 `takeLast(500)` 的淘汰逻辑 —— 两者矛盾，
  淘汰分支永远执行不到。同时文件名由 `System.nanoTime()` 生成、**无内容去重**，
  重复复制同一张图也会不断新增条目。结果：用户累计复制 500 次表情后，
  新复制的表情**永远无法加入**，功能彻底失效。现在文件名由内容摘要派生，
  新增纯 JVM 的 `ClipboardStickerPackPolicy` 做合并（重复项只刷新为「最近使用」、
  按上限保留最新条目），配额不再被重复项耗尽，达到上限也照常收录新内容。
- **真 bug ②（分块流导致真图像被丢弃）**：`InputStream.read(byte[])` 允许
  返回少于请求长度的任意字节数（ContentProvider 基于 pipe / 分块写入时常见），
  甚至可能返回 0。旧实现只在**第一次 read** 后就把固定长 16 的数组拿去嗅探，
  且 `sniffImageMime` 用「数组长度」而非「有效字节数」判断，PNG 的 8 字节
  签名只填进 4 字节即被判为非图像静默丢弃。新增纯 JVM 的
  `ImageHeaderSniffer` 逐段累积文件头，判定一律按有效字节数。
- **真 bug ③（魔数过松导致误判入库）**：GIF 只校验 `"GIF8"` 4 字节、
  BMP 只校验 `"BM"` 2 字节，任意以这些前缀开头的普通文本/损坏数据会被
  当成图像落盘成坏表情。改为 GIF 完整 6 字节签名 + BMP 校验 14 字节
  `BITMAPFILEHEADER` 规范字段（保留字段、bfSize、bfOffBits）。真实图像
  一律不受影响。
- **真 bug ④（IME 销毁后全局钩子泄漏）**：`FcitxInputMethodService.onCreate`
  把捕获提示钩子注册到进程级单例 `ClipboardManager`，`onDestroy` 却从不
  解注册。IME 服务会反复创建/销毁，已销毁的 Service（连同 lifecycleScope
  与 View 树）被单例长期持有而泄漏，且销毁后剪贴板事件仍会触发已失效的
  协程。改为具名字段 + 「仅当仍指向本实例」时清空（与调试桥接同一模式）。
- **真 bug ⑤（过滤不一致 / 并发 / 幽灵条目）**：`captureImageSticker` 用
  大小写敏感的 `uri.scheme != "content"` 过滤，而提取器以 `ignoreCase`
  接受 `CONTENT://`，大写 scheme 候选会「被提取出来又被过滤掉」；导入
  「读清单 → 写 tmp → 重命名 → 重写清单 → 删孤儿」全程无同步，连续快速
  复制多个表情时并发协程会互相覆盖 `pack.json`、删掉对方刚落盘的文件；
  清单里指向已删除文件的幽灵条目会让表情面板出现空白格。分别改为忽略
  大小写比较、专用锁串行化、合并前过滤不存在的文件。
- **回归单测**：新增 `ImageHeaderSnifferTest`、`ClipboardStickerPackPolicyTest`
  并扩充 `ClipboardStickerExtractorTest`（弱签名拒绝、有效字节数判定）。
  全量 JVM 单测 **159/159 通过**（原 143 + 新增 16）；SBOM 与台账重跑
  （第一方 485 文件带 SPDX、0 缺失）。
- **一行未动的部分**：候选词排序与置顶、用户词库、上下文预测、Unicode
  Emoji 序列判定、拆分选词打码、剪贴板历史与备份规则、表情输出三段式、
  权限清单与全部开关默认值全部保持原样；不新增系统权限、不联网。
  代码/注释/随版本分发的文档仍不含任何第三方应用名称。

> 上期（v1.0.75）：修复「复制表情完全失效 / 输出乱码」；v1.0.74：修复
> 「拆分选词」整屏小点点；v1.0.73：默认支持全部 Unicode Emoji 序列与
> 用户自定义图像表情开放容器。详见各版本 Release Notes。

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

Release 附件已签名。证书与 v1.0.70~75 一致，覆盖安装无需卸载旧版、
不丢本机学习数据；换装不同签名版本需先卸载。

## 构建方式

```bash
cd mbm-gh
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export BUILD_ABI=arm64-v8a BUILD_VERSION_NAME=1.0.76 \
  SIGN_KEY_FILE=/path/to/key.jks SIGN_KEY_PWD=... SIGN_KEY_ALIAS=...
ANDROID_HOME=/opt/android-sdk sh gradlew :app:compileReleaseKotlin :app:assembleRelease \
  --no-daemon -PbuildVersionName=1.0.76
```

不带签名环境变量时产物为未签名 APK，需自行 `zipalign + apksigner` 签名。

## 说明

- 单一 `app` 模块：Recall 屏幕记忆已内嵌同进程（v1.0.36 起，无独立插件 APK）
- 单 ABI（arm64-v8a），不包含 32 位库
- 全离线：最终 APK 不含 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
  无任何预置训练语料，学习数据全部运行时写入本机应用私有目录
- 许可：逐文件 SPDX 头（LGPL-2.1-or-later 继承上游 + AGPL-3.0-or-later MBM 新写），
  台账见 `docs/sbom/`；第三方组件清单见 `app/src/main/assets/licenses/NOTICE.txt`
