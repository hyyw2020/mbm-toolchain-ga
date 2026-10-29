# mbm-v1.0.37 工具链刷新（GA）

fcitx5-android MBM 分支 v1.0.37，使用全最新 GA 工具链在 Debian 12 上构建的产物。

私有仓库：仅仓库所有者登录后可访问和下载。

## 下载

进入 Releases 页面下载 `v1.0.37-toolchain-ga` 的附件：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `org.fcitx.fcitx5.android-v1.0.37-arm64-v8a-release-unsigned.apk` | 约 82 MB | ARM64 真机用，未签名 |
| `org.fcitx.fcitx5.android.plugin.recall-v1.0.37-arm64-v8a-release-unsigned.apk` | 约 82 MB | Recall 屏幕记忆插件，arm64-v8a，先装主程序 |
| `mbm-v1.0.37-src.zip` | 约 415 MB | 源码快照，不加密，含 `docs/BUILDING_LINUX_FULL.md` |
| `mbm-v1.0.37-src-toolchain-ga.tar.gz` | 约 390 MB | 同内容 tar.gz（旧格式，备查） |
| `SHA256SUMS.txt` / `SHA256SUMS-plugins-arm64-v8a.txt` | - | 校验和 |

## 本期变更（v1.0.37）

- 键盘视觉风格化重构（仿主流移动输入法）：圆角键面、加大键间距、蓝调强调回车与空格；
  新增浅色 / 深色两套圆角内置主题（ModernLight / ModernDark）。
- 交互与功能零改动，仅视觉层调整。
- 合规清理：代码库内第三方输入法专有名词全部移除，仅保留 fcitx5 本体标识；
  细胞词库（scel）导入类由专名重构为中性命名。

### 本版修复（v1.0.37 第二轮交付，覆盖更新）

- 修复回车键（`button_return`）与空格键（`button_space`）在矮键盘 / 横屏下圆角被
  `GradientDrawable` 强行截断、背景变形或背景消失的绘制缺陷：圆角半径改为不超过
  可用高度的一半，并加入最小高度兜底，使胶囊/蓝键在任意键高下稳定显示。
- 修复默认主题回退逻辑中的冗余死代码（`if (BuildConfig.DEBUG) X else X` 两分支相同），
  消除误导性写法。

## 校验

```bash
sha256sum -c SHA256SUMS.txt
cd plugins && sha256sum -c SHA256SUMS-plugins-arm64-v8a.txt
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

APK 未签名，安装前需自行签名：

```bash
apksigner sign --ks your.keystore \
  --out signed.apk org.fcitx.fcitx5.android-v1.0.37-arm64-v8a-release-unsigned.apk
adb install signed.apk
```

## 构建方式

源码包内含 `docs/BUILDING_LINUX_FULL.md`：从零到产物的完整手册。

```bash
unzip mbm-v1.0.37-src.zip
cd mbm-v1.0.37
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
CMAKE_BUILD_PARALLEL_LEVEL=2 sh gradlew assembleRelease --no-daemon \
  -PbuildABI=arm64-v8a -PbuildVersionName=v1.0.37 -x lint -x test
```

## 说明

- versionCode：arm64-v8a 为 142
- 两个 APK 均为单一 ABI，不包含 32 位库
- 构建过程中未生成或保存任何 keystore
