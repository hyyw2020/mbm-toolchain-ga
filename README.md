# MBM 输入法（fcitx5-android 分支）v1.0.39

fcitx5-android 的 MBM 定制分支。私有仓库，仅仓库所有者可访问。

> 本 README 以 v1.0.39 实际实现为准，覆盖早期版本（v1.0.37 及以前）的描述。
> 历史变更归档见 `docs/PATCHES_*.md` 与 `docs/pitfalls.md`。

## 下载

进入 Releases 页面下载 `v1.0.38-toolchain-ga` 的附件（已签名，可直接安装）：

| 文件 | 说明 |
| --- | --- |
| `MBM-v1.0.38-arm64-v8a-release.apk` | 主程序，arm64-v8a 单 ABI，V3.0 已签名 |
| `mbm-src-v1.0.38.tar.gz` | 源码快照 |
| `SHA256SUMS.txt` | 上述两项的 SHA-256 校验和 |

另可通过 WorkBuddy 分发 Portal 获取相同文件。

## 本期变更（v1.0.39）

- **数字键盘重排（图二样式）**：左列运算符竖排（% / + *）、数字栅格、右上退格、
  底行「返回 / !?:# / 0 / 空格 / 换行」。顶部工具条 UI 不变。
  与图二的唯一差异：右列第 3 键用 `*` 替代 `@`——保证加减乘除全部可直接输入。
- **全局计算器候选（任意键盘/输入框，光标前文本实时提取算式）（全局默认开启，无开关）**：输入 `123+456` 时候选条给出
  【579】与【123+456=579】。求值为纯 Kotlin 标准库本地精确十进制运算
  （`CalcEngine.kt`，递归下降，支持括号 / 一元正负 / 后缀 % / ×÷），
  不联网、不落盘；电话拨号盘与密码输入框不启用。
- **Recall「火力全开」隐私裁决**：删除「时间线已锁定/解锁查看」设备凭证门、
  高级保护模式停采、密码框/敏感窗口整屏丢弃；安全改由 SQLCipher 静态加密 +
  Keystore 信封密钥 + 不联网承担。详见 `PRIVACY.txt` v1.0.39。
- **修复**：原生库安装失败 `INSTALL_FAILED_CONTAINER_ERROR res=-18`
  （`useLegacyPackaging = true`）。

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

Release 附件已签名（V3.0）。覆盖安装无需卸载旧版；换装自签名版本需先卸载。

## 构建方式

```bash
cd mbm-gh
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
ANDROID_HOME=/opt/android-sdk sh gradlew :app:assembleRelease --no-daemon \
  -PBUILD_ABI=arm64-v8a
```

构建产物为未签名 APK，需自行 `zipalign + apksigner` 签名后安装。

## 说明

- 单一 `app` 模块：Recall 屏幕记忆已内嵌同进程（v1.0.36 起，无独立插件 APK）
- 单 ABI（arm64-v8a），不包含 32 位库
- 许可：逐文件 SPDX 头（LGPL-2.1-or-later 继承上游 + AGPL-3.0-or-later MBM 新写），
  台账见 `docs/sbom/`；第三方组件清单见 `app/src/main/assets/licenses/NOTICE.txt`
