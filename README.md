# MBM 输入法（fcitx5-android 分支）v1.0.78

fcitx5-android 的 MBM 定制分支。发布仓库为私有仓库，仅仓库所有者可访问；
每个发布版本均附源码快照，以满足自研部分 AGPL-3.0-or-later 的对应源码要求。

> 本 README 以 v1.0.78 实际实现为准。历史变更归档见 `docs/PATCHES_*.md`
> 与 `docs/pitfalls.md`；隐私政策见 `PRIVACY.txt`，许可与第三方组件清单见
> `LICENSE` / `NOTICE` / `docs/upstream.md`。

## 下载

进入 Releases 页面下载 `v1.0.78-toolchain-ga` 的附件（已签名，可直接覆盖安装）：

| 文件 | 说明 |
| --- | --- |
| `org.fcitx.fcitx5.android-1.0.78-arm64-v8a-release.apk` | 主程序，arm64-v8a 单 ABI，已签名 |
| `org.fcitx.fcitx5.android-1.0.78-arm64-v8a-release.tar.gz` | APK + 校验和打包 |
| `mbm-src-v1.0.78.tar.gz` | 对应源码快照（AGPL §6 / LGPL §4） |
| `SHA256SUMS.txt` | SHA-256 校验和 |

另可通过 WorkBuddy 分发 Portal 获取相同文件（下载链接需带 sharecode）。

## 本期变更（v1.0.78）：修复大文本分块上屏的块大小契约偏差

150MB 端到端佐证（对 v1.0.77 的独立压力测试，见 `docs/` 与 `evidence/`）发现一个
**非致命但确凿的契约偏差**：`ClipboardPasteChunker` 分块时内层固定按 `chunkSize`
读取，上一块 UTF-8 多字节字符的 carry 前移后，单块实际可达 `chunkSize + carry`
（混合 UTF-8 实测 **262146 = 256KB + 2**，超出标称 256KB）。拼接结果仍逐字节
一致、无替换符、无丢字，且 262146 B 仍只占 Binder 1MB 事务的 25%，但「单块
≤ 标称值」这一文档化契约与实现不符。

**修复**：内层读取长度改为 `chunkSize - carry`，使每块 `total = carry + (chunkSize -
carry) = chunkSize`，单块**严格 ≤ 256KB**；对 `chunkSize < 4` 的测试/病态用法保留
「至少读 1 字节」的推进保证，避免死循环。仅改一个既有分块类，未触碰任何其他
链路。新增 1 个回归单测：混合 UTF-8（3 字节中文 + 4 字节 emoji 跨块边界）下
逐块断言 `byteLen ≤ chunkSize` 且拼接 == 原文。

## 上期变更（v1.0.77）：无上限复制 / 粘贴的完整性审计与补齐

用户问「先前要求的无上限字数复制或粘贴是否已完美实施」。逐层审计后结论：
**核心链路早已真正无上限，但周边有 4 处真实缺口让「完整」二字不成立**。
本轮补齐这 4 处，并把散落的硬上限收口成一处可配置、可观测的策略。

### 已经无上限、本版一行未动的部分（审计证据）

| 环节 | 实现 | 上限 |
| --- | --- | --- |
| 单条文本存储 | `ClipboardBlobStore`：>256KB 外置 blob 文件，流式写入 + 静态加密 + 内容寻址去重，DB 行恒定只存 preview / 元数据 | 无（绕开 CursorWindow 2MB） |
| 剪贴板历史条数 | `clipboardHistoryLimit` 默认 `0` = 不清理，`removeOutdated()` 纯 SQL | 无（只受设备存储） |
| 从文件导入 | `ClipboardManager.importFromUri` 流式读入 | 无 |
| 粘贴到当前输入框 | `pasteClipboardEntry` → `ClipboardPasteChunker` 按 256KB 分块 `commitText`，处理 UTF-8 跨块边界、主线程提交、尾部校验 | 无（100MB 完整上屏，绕开 Binder 1MB） |
| 分享给其他应用 | `shareEntry` 大文本经 FileProvider URI 流式分享 | 无 |
| Recall 捕获文本 | 已移除旧 `MAX_TEXT_LENGTH=4000` 静默截断 | 无 |

### 本轮修的 4 个真 bug

- **真 bug ①（复制侧多 Item 丢内容）**：`ClipboardEntry.fromClipData` 只读
  `ClipData.getItemAt(0)`，源码里 `TODO: handle multiple items` 明写未处理。
  Android 允许一次复制携带多个 Item（多段文字 / 多行 / 标题+正文），旧实现
  只把第一项写进历史，其余静默丢弃。新增纯 JVM 的 `ClipItemTextMerge`：
  单 Item 原样返回（快路径行为零变化），多 Item 按序以 `\n` 连接，一段不丢；
  非文本型 Item 的 URI / Intent 派生分支同样改为遍历全部 Item。
- **真 bug ②（`trim()` 静默丢字，逐字保真失效）**：`insertEntry` 与
  `updateText` 都先 `text.trim()` 再落库，用户复制「  abc」历史里变成「abc」；
  且哈希按 trim 后文本算，「 A 」与「A」被判为同一条却只存了裁剪值。
  新增纯 JVM 的 `ClipboardTextFidelity` 固化三条语义：全空白才丢弃、
  原文落库、去重 / 哈希按原文，与 blob 流式写入的字节口径一致。
- **真 bug ③（写回系统剪贴板超限静默失败却谎报成功）**：拆分选词的
  「复制」与编辑页的「保存并复制」都直接 `setPrimaryClip(newPlainText(全文))`。
  系统剪贴板走 Binder 事务（约 1MB，按 **UTF-16 码元**计费），文本一大就抛
  `TransactionTooLargeException`；旧代码用 `runCatching{}` 吞掉后**照样弹
  「已复制」**。新增 `SystemClipboardCopy` + 统一策略 `ClipboardCapacityPolicy`：
  安全范围原路 `newPlainText`；超限改以 FileProvider URI + `text/plain` 放入
  剪贴板，并**按真实结果如实提示**。无论哪条路径，全文都同时登记进无上限的
  剪贴板历史，用户可从面板用分块粘贴把完整原文上屏——这才是绕开平台
  Binder 边界的可靠通道。顺带修复编辑页用 Activity `MainScope` 写库、
  紧接着 `finish()` → `onDestroy()` → `scope.cancel()` 导致「确定 / 保存并复制」
  偶发不生效的竞态（改由应用级作用域执行）。
- **真 bug ④（大文本后半段被静默隐藏）**：`ClipboardWindow.onSplitSelect`
  用 `readCapped(stream, MAX_CHARS).first` 读 blob 全文，**把截断标志丢掉了**，
  拆分选词面板完全不提示「后面还有内容」，用户以为看到的是全文，选区永远
  覆盖不到后半段。现在把该标志经 `WordSelectWindow` 传到 `WordSelectUi`，
  与分词器自身的词块触顶合并后统一披露（并指引调大设置项）。

### 更优方案：统一容量策略 + 可配置 + 明确披露

把原先散落在 `WordTokenizer.MAX_CHARS / MAX_TOKENS`、
`ClipboardWindow.VIEW_FULL_TEXT_LIMIT_CHARS`、
`ClipboardEditActivity.EDIT_LIMIT_CHARS`、剪贴板事务软阈值等 6 处的硬编码
收口到纯 JVM 的 `ClipboardCapacityPolicy`，并厘清语义：

- **存储 / 上屏 / 导出 / 分享：仍无上限**，不受该策略任何常量约束；
- **UI 单次装载 / 单条系统剪贴板事务：必须有边界**（内存与平台事务的物理
  约束），但一律 **可配置 + 可观测 + 绝不静默丢字**：新增剪贴板设置项
  「单次加载字数（0 = 默认）」，用户可主动调大以查看 / 拆分更长文本；
  `WordTokenizer.tokenize` 默认不再做字符级二次截断，配置才真正生效；
  任何截断都在 UI 显示提示，并指引调大该设置项。

### 边界（如实说明）

Android 系统剪贴板本身受 Binder ~1MB 事务限制，**其他应用无法把 100MB
文字放进系统剪贴板**，输入法监听侧自然也收不到——这是平台物理边界，
任何输入法都无法改变。本应用用「从文件导入 + 文件分享 + 分块直接上屏」
三条通道完整覆盖「能存、能找、能粘贴 100MB 文字」的目标，属绕行而非缩减。

- **回归单测**：新增 `ClipItemTextMergeTest`(8)、`ClipboardTextFidelityTest`(5)、
  `ClipboardCapacityPolicyTest`(8) 共 21 个纯 JVM 用例。全量 **180/180 通过**
  （原 159 + 21）；SBOM 与台账重跑（第一方 492 文件带 SPDX、0 缺失）。
- **一行未动的部分**：候选词排序与置顶、用户词库、上下文预测、Unicode
  Emoji 序列判定、表情输出三段式、拆分选词打码语义、剪贴板加密与备份规则、
  权限清单与全部既有开关默认值；不新增系统权限、不联网。
  代码 / 注释 / 随版本分发的文档仍不含任何第三方应用名称。

> 上期（v1.0.77）：无上限复制 / 粘贴完整性审计与补齐（修多 Item 只记第一项、
> trim 静默丢字、写回系统剪贴板超限谎报成功、大文本后半段静默隐藏 4 类真 bug）；
> v1.0.76：修复表情配额耗尽、分块流嗅探、魔数过松、服务销毁钩子泄漏等 5 类真 bug；
> v1.0.75：修复「复制表情完全失效 / 输出乱码」；v1.0.74：修复「拆分选词」整屏小点点。
> 详见各版本 Release Notes。

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

Release 附件已签名。证书与 v1.0.70~78 一致，覆盖安装无需卸载旧版、
不丢本机学习数据；换装不同签名版本需先卸载。

## 构建方式

```bash
cd mbm-gh
printf 'sdk.dir=/opt/android-sdk\n' > local.properties
export BUILD_ABI=arm64-v8a BUILD_VERSION_NAME=1.0.78 \
  SIGN_KEY_FILE=/path/to/key.jks SIGN_KEY_PWD=... SIGN_KEY_ALIAS=...
ANDROID_HOME=/opt/android-sdk sh gradlew :app:compileReleaseKotlin :app:assembleRelease \
  --no-daemon -PbuildVersionName=1.0.78
```

不带签名环境变量时产物为未签名 APK，需自行 `zipalign + apksigner` 签名。

## 说明

- 单一 `app` 模块：Recall 屏幕记忆已内嵌同进程（v1.0.36 起，无独立插件 APK）
- 单 ABI（arm64-v8a），不包含 32 位库
- 全离线：最终 APK 不含 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
  无任何预置训练语料，学习数据全部运行时写入本机应用私有目录
- 许可：逐文件 SPDX 头（LGPL-2.1-or-later 继承上游 + AGPL-3.0-or-later MBM 新写），
  台账见 `docs/sbom/`；第三方组件清单见 `app/src/main/assets/licenses/NOTICE.txt`
