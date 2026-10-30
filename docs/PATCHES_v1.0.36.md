# PATCHES v1.0.36 —— 构建工具链例行升级 + 源码快照可构建修复

> 记录时间：2026-10-27
> 范围：**只动构建工具链与构建逻辑，不改任何产品代码（app / plugin 运行时代码零改动）**。
> 所有功能与 v1.0.35 完全一致。

---

## 一、版本决策（全部取「当前最新稳定版」，2026-10-27 核对）

| 组件 | v1.0.35 | v1.0.36 | 说明 |
| --- | --- | --- | --- |
| Gradle | 9.8.2（bin，官方源） | **9.8.2（-all，华为云镜像）** | 9.8.2 已是最新稳定版；改用 -all 分发包，wrapper 默认指向华为云镜像 |
| AGP | 9.4.0 | **9.4.1** | 官方 `latest` 元数据为 9.6.0-alpha01（alpha，不用）；9.4.1 = 最新稳定版 |
| Kotlin | 2.4.10 | **2.4.21** | 最新稳定版 |
| KSP | 2.3.10 | **2.3.12** | 最新稳定版（2.3.x 线，与 Kotlin 解耦） |
| Build-Tools | 36.1.0 | **37.0.0** | `Versions.kt` `defaultBuildTools` |
| JDK | 17+（验证环境 20） | 17+（**验证环境 25.0.2 LTS**） | AGP 要求 17+；25 为当前最新 LTS |
| NDK | 28.0.13004108 | **不变** | `Versions.kt` 显式锁定；不在无原生构建环境的机器上做未验证升级 |
| CMake | 3.31.6 | **不变** | 同上 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 29 | **不变** | |
| baseVersionCode | 13 | **不变（13）** | arm64 versionCode 维持 132 规划，本版不产新 APK |

## 二、源码快照（无 .git）可构建修复 —— 本轮唯一的真 bug 修复

zip / tar 快照没有 `.git`，v1.0.35 快照在配置期必炸两处（构建说明书 4.2 镜像方式同样命中；
git clone 检出则一切正常，故长期未暴露）：

1. **`version = gitVersion()` 抛「Cannot find '.git' directory」**
   （palantir git-version 插件在无仓库时直接抛异常，无任何回退）。
   修复：`runCatching { gitVersion().toString() }.getOrElse { "0.0.0" }`。
   涉及 `build-logic/convention/build.gradle.kts`、`lib/plugin-base/build.gradle.kts`、
   `lib/common/build.gradle.kts` 共 3 处。
2. **`ProjectExtensions.runCmd` 的回退是死代码**：`providers.exec` 默认不忽略非零退出码，
   进程失败（git 退出 128）时 `output.result.get()` 直接抛
   「Process 'command 'git'' finished with non-zero exit value 128」，
   `defaultValue` 分支永远走不到。修复：显式 `isIgnoreExitValue = true`，
   令 `exitValue == 0` 判断与回退真正生效。

效果：无 `.git` 时 `buildVersionName` 回退 `Versions.baseVersionName`（0.1.4）、
`buildCommitHash` 回退 `N/A`、build-logic 三模块 version 回退 `0.0.0`；
git 检出（clone）路径行为逐字节不变。

## 三、改动文件清单

| 文件 | 改动 |
| --- | --- |
| `gradle/wrapper/gradle-wrapper.properties` | distributionUrl → 华为云镜像 `gradle-9.8.2-all.zip` |
| `gradle/libs.versions.toml` | AGP 9.4.0→9.4.1；Kotlin 2.4.10→2.4.21；KSP 2.3.10→2.3.12 |
| `build-logic/convention/src/main/kotlin/Versions.kt` | `defaultBuildTools` 36.1.0→37.0.0（+ v1.0.36 注释） |
| `build-logic/convention/build.gradle.kts` | gitVersion 降级（见第二节） |
| `lib/plugin-base/build.gradle.kts` | 同上 |
| `lib/common/build.gradle.kts` | 同上 |
| `build-logic/convention/src/main/kotlin/ProjectExtensions.kt` | `runCmd` `isIgnoreExitValue = true`（见第二节） |
| `README.md` | 当前版本 / 工具链行、v1.0.36 变更段 |
| `构建说明书.txt` | 修订头、第 2.2 步 build-tools、第 5.1 步 buildVersionName、第 6 步 aapt、Q5、附录 B |
| `Linux构建优化指导书.txt` | 新增：受限 Linux（低内存 cgroup / Firecracker microVM / Ubuntu 24.04 无 SDK）的内存、磁盘与镜像优化指导 |
| `docs/PATCHES_v1.0.36.md` | 本文档 |

## 四、环境安装记录（本机 Windows 11，12 核 / 64GB，供复现）

- **JDK 25.0.2（最新 LTS）**：华为云 openjdk 镜像
  `https://mirrors.huaweicloud.com/openjdk/25.0.2/openjdk-25.0.2_windows-x64_bin.zip`
  → 解压至 `C:\Users\Vulcan\AndroidDev\jdk-25.0.2`（不改全局 JAVA_HOME，构建时按次覆盖）。
- **SDK 组件**（sdkmanager，直连 dl.google.com）：
  - 工程锁定版（配置期校验需要）：`ndk;28.0.13004108`、`cmake;3.31.6`
  - 通用最新版（机器环境，工程未引用）：`ndk;30.0.16248370`、`cmake;4.1.2`
  - 已有：`build-tools;37.0.0`、`platforms;android-37`
- **Gradle 分发包**：wrapper 自动从华为云镜像下载 `gradle-9.8.2-all.zip`
  （缓存于 `~/.gradle/wrapper/dists/gradle-9.8.2-all/7w5rb2ehgct03spyae4f04zap/`）。
- **Maven 仓库**：settings.gradle.kts 官方源（gradlePluginPortal / google / mavenCentral）
  直连可解析 AGP 9.4.1 / Kotlin 2.4.21 / KSP 2.3.12，本轮无需 init-script 镜像加速。
- `local.properties`（`sdk.dir`）为机器侧文件，不随源码包分发。

## 五、验证记录

`gradlew help`（JDK 25.0.2，Windows，无 `.git` 快照 + 子模块未检出）：

- `:build-logic:convention:compileKotlin` 以 **Kotlin 2.4.21 + AGP 9.4.1 + KSP 2.3.12** 编译通过；
- 全部 19 个模块（settings.gradle.kts include 全集）配置期通过，
  含 externalNativeBuild 模块的 NDK 28.0.13004108 / CMake 3.31.6 锁定版校验；
- `BUILD SUCCESSFUL in 35s`。

**受限说明（如实记录）**：本机无 MSYS2 / ECM / gettext，19 个 git 子模块均未检出，
`:app:assembleRelease` 完整原生构建无法在本机执行；本轮以**配置期全量通过**为验证边界。
NDK / CMake 锁定值未变，原生链路零风险。完整构建仍按 `构建说明书.txt` 第 1~6 步
（git 检出，或 4.2 快照 + 附录 A 固定 SHA 子模块）。

## 六、复现命令

```bash
cd <repo>
export JAVA_HOME=<jdk-25.0.2 或任意 jdk-17+>
./gradlew --version          # Gradle 9.8.2（华为镜像 -all）
./gradlew help               # 配置期验证（无需 .git、无需原生环境）
# 完整构建：按 构建说明书.txt 第 1~6 步
```
