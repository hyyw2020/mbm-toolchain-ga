# PATCHES v1.0.35 —— 构建工具链升级：AGP 9.4.0 + Gradle 9.8.2

> 记录时间：2026-10-26
> 范围：**只动构建工具链与构建逻辑，不改任何产品代码**。目标是用
> AGP 9.4.0 + Gradle 9.8.2 重新构建，所有功能与原 v1.0.34 完全一致。

---

## 一、版本与兼容性核对

| 组件 | AGP 9.4.0 要求 | 本工程实际 | 结论 |
| --- | --- | --- | --- |
| Gradle | 最低 9.6.0（默认 9.6.0） | **9.8.2** | ✅ 高于最低要求 |
| JDK | 最低 17 | **Zulu 20**（Gradle 9.8.2 支持 17–27） | ✅ 无需降级 |
| SDK Build Tools | 36.0.0 | **36.1.0** | ✅ |
| NDK | 默认 28.2.13676358 | **28.0.13004108**（`Versions.kt` 显式锁定） | ✅ 显式锁定不受默认值影响 |
| 最高 API | 37 | **compileSdk / targetSdk 37** | ✅ |
| 内嵌 Kotlin | Gradle 9.8.2 = 2.4.10 | **KGP 2.4.10** | ✅ 版本恰好一致 |

变更点只有两行版本号：

- `gradle/wrapper/gradle-wrapper.properties`：`gradle-9.6.1-bin.zip` → `gradle-9.8.2-bin.zip`
- `gradle/libs.versions.toml`：`androidGradlePlugin = "9.4.1"` → `"9.4.0"`

> 注：v1.0.34 时本工程已在 AGP 9.4.1；本轮按指定版本锁定到 9.4.0。

## 二、环境安装（供复现）

Gradle 官方源在容器内直连仅 ~20KB/s，改用镜像并手动安装：

```bash
# 1) 下载分发包（腾讯云镜像，实测 7 秒）
curl -L -o /tmp/g982.zip https://mirrors.cloud.tencent.com/gradle/gradle-9.8.2-bin.zip
# 2) 解压到 /opt/gradle
sudo mkdir -p /opt/gradle && sudo unzip -q -d /opt/gradle /tmp/g982.zip
export GRADLE_HOME=/opt/gradle/gradle-9.8.2
export PATH=$GRADLE_HOME/bin:$PATH
# 3) 预置 wrapper 缓存，避免 ./gradlew 联网下载
#    目录名 = MD5(distributionUrl) 的 base36（本项目为 8wepis63pzuhrlfaiy04bagwu）
cp /tmp/g982.zip ~/.gradle/wrapper/dists/gradle-9.8.2-bin/8wepis63pzuhrlfaiy04bagwu/
(cd ~/.gradle/wrapper/dists/gradle-9.8.2-bin/8wepis63pzuhrlfaiy04bagwu \
  && unzip -q -o gradle-9.8.2-bin.zip && touch gradle-9.8.2-bin.zip.ok)
# 4) Android SDK 路径
echo 'sdk.dir=/opt/android-sdk' > local.properties
```

`./gradlew --version` 输出 `Gradle 9.8.2` / `Kotlin: 2.4.10` / `Launcher JVM: 20`。

## 三、新特性落地清单

| 新特性 | 落地方式 | 状态 |
| --- | --- | --- |
| Gradle 9.8.2 | wrapper 分发 + `/opt/gradle` 手动安装 | ✅ |
| AGP 9.4.0 | 版本目录锁定 | ✅ |
| **AGP 10 严格行为预检** | `android.newDsl=true` + `android.builtInKotlin=true` | ✅ 完整 `assembleDebug` 通过 |
| 并行执行 | `org.gradle.parallel=true` | ✅ |
| **模型构建并行显式化**（Gradle 9.4 新增独立开关，10 起与 parallel 解耦，不写会报错） | `org.gradle.tooling.parallel=true` | ✅ |
| 本地构建缓存 | `org.gradle.caching=true` | ✅（实测 4 个任务 from cache） |
| `buildFeatures.buildConfig` 显式声明 | 由注入方 `AndroidAppConventionPlugin` 开启 | ✅ |
| 配置缓存（CC） | 前置改造完成，开关暂不启用 | ⏸ 见第六节 |

### 关于 `android.newDsl=true` / `android.builtInKotlin=true`

AGP 9.0 起新 DSL/Variant API 与内置 Kotlin 默认启用，**AGP 10.0 将移除
opt-out 开关**。官方建议在 9.x 上把这两个开关顶到 `true` 做提前验证。
本工程已全部通过（含 `:app` 与 `:plugin:recall` 的完整 `assembleDebug`），
说明 AGP 10 升级路径上的 DSL 层面障碍已清空。工程本身也已不再 apply
`kotlin-android`（已依赖 AGP 内置 Kotlin）。

## 四、为兼容配置缓存所做的构建逻辑改造

配置缓存报 12 个问题，全部指向自定义任务 `CMakeBuildInstallTask`。逐项消除：

| 问题 | 原实现 | 改造后 |
| --- | --- | --- |
| `invocation of 'Task.project' at execution time` | `project.providers.exec {}` | `@get:Inject abstract val execOperations: ExecOperations` |
| `cannot serialize DefaultProject`（属性） | `Property<CxxAbiModel>`（AGP internal，间接持有 Project） | 摊平成 `cmakeExe` / `cxxBuildFolder` 两个 `Property<String>` |
| `cannot serialize DefaultProject`（闭包） | `doLast {}` 内访问 `project.extensions` / `project.assetsDir` | 改为任务输入属性 `textReplacements`，`@TaskAction` 内处理 |
| 不可序列化 lambda | extension 的 `Map<String, (File) -> Unit>` | 数据类 `TextReplacement(path, from, to)`（`Serializable`）+ DSL 方法 `textReplacement(...)` |
| `Task.project`（delete 任务） | `doLast` 里 `project.assetsDir.resolve(it)` | 配置期解析成 `List<File>` 再进闭包 |

涉及文件：`NativeBuildTasks.kt`、`FcitxComponentPlugin.kt`、
`FcitxHeadersPlugin.kt`、`plugin/jyutping/build.gradle.kts`。

> `TextReplacement` 位于 build-logic 的**默认包**，模块脚本无法 import，
> 因此 extension 暴露 `textReplacement(path, from, to)` 方法供脚本调用。

## 五、功能一致性验证

| 验证项 | 结果 |
| --- | --- |
| JVM 单测（app 55 + recall 25） | **80 个全绿，0 失败 0 跳过** |
| `:app:assembleDebug` / `:plugin:recall:assembleDebug` | BUILD SUCCESSFUL |
| `:plugin:jyutping:assembleDebug` | BUILD SUCCESSFUL |
| jyutping 文本替换（本轮改造直接受影响点） | APK 内 `usr/share/fcitx5/addon/jyutping.conf` = `Library=libIMEJyutping;libjyutping`，**与改造前完全一致** |
| app APK 元数据 | versionCode 12、targetSdk 37、compileSdk 37 |
| native 库 | 27 个 `.so`，含 `libsqlcipher.so` |
| 加密相关类在 dex | `StreamingFileCipher`、`AppKeyManager` 均在 |
| `BuildConfig.SIGNER_SHA256` | 已注入（P2-7 签名自校验保持有效） |

## 六、暂未启用配置缓存的原因（如实记录）

启用 `org.gradle.configuration-cache=true` 后配置缓存条目能存下（12 个
序列化问题已全部消除），但**构建失败**：

```
Cannot query the value of task ':lib:fcitx5:installFcitxDevelComponent'
property 'cmakeExe' because it has no value available.
```

根因：本工程 native install 任务所需的 cmake 路径与 C++ 构建目录，靠
`ExternalNativeBuildJsonTask` 的 `doFirst` 写进**共享 Property 对象**传递，
任务间只有 `mustRunAfter`、没有 `dependsOn`。非 CC 下共享同一实例能跑通；
**CC 下每个任务反序列化出独立副本，值传不过来**。

彻底解法是把两个路径改为**配置期可确定**（不依赖另一个任务的执行期
副作用），本轮未做（涉及 AGP native 模型取值时机，风险高于收益）。
因此：开关保持注释关闭，注释中写明原因与后续方向，详见 pitfalls #77 坑 2。

## 七、仍未完成（跨轮）

- R3-2 词库加密（需改 native 侧）
- R5 手写真机根因（待真机 logcat）
- R6 security-crypto 弃用迁移
- **R8（新增）**：配置缓存彻底启用（需改造 native install 的取值时机）
- **R9（新增）**：AGP 10 将移除对 internal AGP 类的访问，本工程
  `AndroidAppConventionPlugin` 仍引用 `com.android.build.gradle.internal.tasks.*`
  与 `org.gradle.api.internal.provider.AbstractProperty`（用于禁用
  baseline profile 任务与剔除 app-metadata），需在升 AGP 10 前替换
- Gitee 推送授权（本地持续领先 origin/main）

## 八、复现命令

```bash
cd <repo>
./gradlew --version                                   # Gradle 9.8.2 / Kotlin 2.4.10
./gradlew :app:assembleDebug :plugin:recall:assembleDebug
./gradlew :plugin:jyutping:assembleDebug              # 验证文本替换
./gradlew :app:testDebugUnitTest :plugin:recall:testDebugUnitTest --continue
```
