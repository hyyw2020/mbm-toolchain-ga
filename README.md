# mbm-v1.0.36 工具链刷新（GA）

fcitx5-android MBM 分支 v1.0.36，使用全最新 GA 工具链在 Debian 12 上构建的产物。

私有仓库：仅仓库所有者登录后可访问和下载。

## 下载

进入 Releases 页面下载 `v1.0.36-toolchain-ga` 的附件：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `org.fcitx.fcitx5.android-v1.0.36-arm64-v8a-release-unsigned.apk` | 79 MB | ARM64 真机用，未签名 |
| `org.fcitx.fcitx5.android-v1.0.36-x86_64-release-unsigned.apk` | 81 MB | x86_64 模拟器用，未签名 |
| `mbm-v1.0.36-src-toolchain-ga.tar.gz` | 228 MB | 源码快照，含预编译原生库与文档 |
| `SHA256SUMS.txt` | - | 校验和 |

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

APK 未签名，安装前需自行签名：

```bash
apksigner sign --ks your.keystore \
  --out signed.apk org.fcitx.fcitx5.android-v1.0.36-arm64-v8a-release-unsigned.apk
adb install signed.apk
```

## 构建方式

源码包内含 `docs/BUILD_ENV_LINUX.md`，覆盖 Linux 环境下上述全部版本组件的安装与配置。

```bash
tar -xzf mbm-v1.0.36-src-toolchain-ga.tar.gz
cd mbm-v1.0.36
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
CMAKE_BUILD_PARALLEL_LEVEL=2 sh gradlew assembleRelease --no-daemon \
  -PbuildABI=arm64-v8a -PbuildVersionName=v1.0.36 -x lint -x test
```

## 说明

- versionCode：arm64-v8a 为 132，x86_64 为 134
- 两个 APK 均为单一 ABI，不包含 32 位库
- 构建过程中未生成或保存任何 keystore
