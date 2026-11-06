# MBM 输入法（fcitx5-android 分支）v1.0.72

fcitx5-android 的 MBM 定制分支。发布仓库为私有仓库，仅仓库所有者可访问；
每个发布版本均附源码快照，以满足自研部分 AGPL-3.0-or-later 的对应源码要求。

> 本 README 以 v1.0.72 实际实现为准。历史变更归档见 `docs/PATCHES_*.md`
> 与 `docs/pitfalls.md`；隐私政策见 `PRIVACY.txt`，许可与第三方组件清单见
> `LICENSE` / `NOTICE` / `docs/upstream.md`。

## 下载

进入 Releases 页面下载 `v1.0.72-toolchain-ga` 的附件（已签名，可直接覆盖安装）：

| 文件 | 说明 |
| --- | --- |
| `org.fcitx.fcitx5.android-1.0.72-arm64-v8a-release.apk` | 主程序，arm64-v8a 单 ABI，已签名 |
| `org.fcitx.fcitx5.android-1.0.72-arm64-v8a-release.tar.gz` | APK + 校验和打包 |
| `mbm-src-v1.0.72.tar.gz` | 对应源码快照（AGPL §6 / LGPL §4） |
| `SHA256SUMS.txt` | SHA-256 校验和 |

另可通过 WorkBuddy 分发 Portal 获取相同文件（下载链接需带 sharecode）。

## 本期变更（v1.0.72）：预测功能增强

- **全拼键覆盖（根因修复）**：native `pinyin.cpp` 用 `customPhrase_.lookup(pyBeforeCursor)`
  精确匹配用户实际敲入的原始拼音串，而此前全仓库只写「首字母缩写键」
  （`安全`→`aq`、`哔哩哔哩`→`blbl`）。习惯全拼的用户输入 `anquan` / `bilibilli`
  时词库无对应键，v1.0.66~71 的全部个性化排序与 N-gram 预测完全不生效。
  新增 `PhraseKeys.forWord`：写词组时同一 order 额外挂全拼键；`UserChoiceStore`
  的托管/剔除范围同步覆盖，避免旧全拼行脱离托管。首字母键一行不动，零回归。
- **上下文「下一词」预测冠军**：新增零 Android 依赖的 `ContextPredictor`
  （17 个 JVM 单测）+ `PredictionStore`。复用本机 `SmartDictManager` 的
  bigram/trigram，把「刚上屏的词的高置信下一词」写成 `order=1` 预测冠军，
  60ms 落盘 + `reloadNow()`。严格让位：任一键已有用户硬层历史、该词已被用户
  选过、已手动置顶，一律不写；每次上屏全量替换托管行，不长期占首位。
  **「选择 >= 1 次即第一」的硬规则完全不被削弱。**
- **文档与代码对齐**：`PRIVACY.txt` 升到 v1.0.72，纠正「Recall 为独立可选
  APK」等失实描述、补全权限清单与新增学习状态披露；`NOTICE` 第六/七节重写；
  `LICENSE` 头部与 `docs/upstream.md` 更正 fcitx5-chinese-addons 的许可标注；
  应用内「关于 → 开源许可」不再跳浏览器，全程离线；备份规则改为整域排除。
- 全量 JVM 单测 **100/100 通过**（原 83 + 新增 17）。

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

Release 附件已签名。证书与 v1.0.70/71 一致，覆盖安装无需卸载旧版、
不丢本机学习数据；换装不同签名版本需先卸载。

## 构建方式

```bash
cd mbm-gh
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export BUILD_ABI=arm64-v8a BUILD_VERSION_NAME=1.0.72 \
  SIGN_KEY_FILE=/path/to/key.jks SIGN_KEY_PWD=... SIGN_KEY_ALIAS=...
ANDROID_HOME=/opt/android-sdk sh gradlew :app:compileReleaseKotlin :app:assembleRelease \
  --no-daemon -PbuildVersionName=1.0.72
```

不带签名环境变量时产物为未签名 APK，需自行 `zipalign + apksigner` 签名。

## 说明

- 单一 `app` 模块：Recall 屏幕记忆已内嵌同进程（v1.0.36 起，无独立插件 APK）
- 单 ABI（arm64-v8a），不包含 32 位库
- 全离线：最终 APK 不含 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
  无任何预置训练语料，学习数据全部运行时写入本机应用私有目录
- 许可：逐文件 SPDX 头（LGPL-2.1-or-later 继承上游 + AGPL-3.0-or-later MBM 新写），
  台账见 `docs/sbom/`；第三方组件清单见 `app/src/main/assets/licenses/NOTICE.txt`
