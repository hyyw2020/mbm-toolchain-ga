# MBM 上游组件与许可声明

MBM 以 fcitx5-android 为基座，所有上游代码以 submodule / vendored 方式引入，
并**完整保留**各自的 License 与版权声明。本文件列出全部上游组件、引入方式、
固定提交与许可条款。

## 1. 主仓库

| 项目 | 地址 | 许可 |
|---|---|---|
| fcitx5-android | https://github.com/fcitx5-android/fcitx5-android | LGPL-2.1-or-later |

## 2. 直接子模块（固定提交）

| 路径 | 仓库 | 固定提交 |
|---|---|---|
| `lib/fcitx5/src/main/cpp/fcitx5` | fcitx/fcitx5 | `1e00551899f9d0fa5418d899f468b6e421401adf` |
| `lib/fcitx5/src/main/cpp/prebuilt` | fcitx5-android/prebuilt | `92ab7d6291a1fa426f199be917857ebb6054d08c` |
| `lib/fcitx5-lua/src/main/cpp/fcitx5-lua` | fcitx/fcitx5-lua | `9bf8d3dd2c6240d8e3e194d09547acbfa6a81204` |
| `lib/libime/src/main/cpp/libime` | fcitx/libime | `ecd23795ff7ea63a55a1b88fc4767946b999e102` |
| `lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons` | fcitx/fcitx5-chinese-addons | `19f06898581419d3e4d37492f33a14e841e2680e` |
| `plugin/rime/src/main/cpp/fcitx5-rime` | fcitx/fcitx5-rime | `00be19a18ea5b5d1748f9fe6f6bad50f42f96781` |
| `plugin/rime/src/main/cpp/rime-prelude` | rime/rime-prelude | `082425ea0684bca36474415d4a0e8db9b016487e` |
| `plugin/rime/src/main/cpp/rime-essay` | rime/rime-essay | `054920de4f54c9e5994276a96a4fc2a35cb51aa3` |
| `plugin/rime/src/main/cpp/rime-luna-pinyin` | rime/rime-luna-pinyin | `56b934b099dfbeab842320f13aa8b461a6ab3e42` |
| `plugin/rime/src/main/cpp/rime-stroke` | rime/rime-stroke | `1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48` |
| `plugin/anthy/src/main/cpp/anthy-cmake` | fcitx5-android/anthy-cmake | `2fe8bfe88c034f75abc01c86ac874ae9f8be15e6` |
| `plugin/anthy/src/main/cpp/fcitx5-anthy` | fcitx/fcitx5-anthy | `9a7fd5142d3367ed28813eb60f8a2251294823b9` |
| `plugin/chewing/src/main/cpp/fcitx5-chewing` | fcitx/fcitx5-chewing | `60096e8ef49df21de6c155d7a0785a58fcccb955` |
| `plugin/hangul/src/main/cpp/fcitx5-hangul` | fcitx/fcitx5-hangul | `ede437b2d38bcc37abf382d9136f3ccbf8e16c08` |
| `plugin/jyutping/src/main/cpp/libime-jyutping` | fcitx/libime-jyutping | `6943567083f7e261ea14c470d316acacd64c7b6f` |
| `plugin/sayura/src/main/cpp/fcitx5-sayura` | fcitx/fcitx5-sayura | `a69985130b6e6d69cae461c4e400d3847879a9d9` |
| `plugin/thai/src/main/cpp/fcitx5-libthai` | fcitx/fcitx5-libthai | `fd10ea0a8b9f980892bd61384c5f5a56f0b8bc52` |
| `plugin/unikey/src/main/cpp/fcitx5-unikey` | fcitx/fcitx5-unikey | `ed9170c35c1a6275d105e03d7f9bc4f674c1469c` |
| `plugin/clipboard-filter/ClearURLsRules` | ClearURLs/Rules | `11086f40512774dcadef54079f1ba023bfacf940` |

## 3. 嵌套子模块

| 路径 | 仓库 | 固定提交 | 说明 |
|---|---|---|---|
| `lib/libime/src/main/cpp/libime/src/libime/core/kenlm` | kpu/kenlm | `4cb443e60b7bf2c0ddf3c745378f76cb59e254e5` | libime 语言模型依赖 |
| `lib/fcitx5/src/main/cpp/fcitx5/third_party/yoga` | facebook/yoga | `042f5013152eb81c1552dec945b88f7b95ca350f` | 布局引擎 |
| `plugin/anthy/src/main/cpp/anthy-cmake/anthy-unicode` | fujiwarat/anthy-unicode | `fc2260cf60100bb868c20f7ca493e8c2e01dcf11` | 日语 Anthy 引擎 |

## 4. 许可条款摘录

v1.0.72 更正：以下按 `docs/license_audit.py` 的逐文件 SPDX 实测与各子模块内
COPYING / README 校准。旧版本把 fcitx5-chinese-addons 写成 GPL-3.0-or-later
并援引 GPL-3.0 §13，实测其 COPYING 为 LGPL-2.1、README 标 LGPL-2.1+；
亦曾把 librime 列为本产品包含的组件，但 `.gitmodules` 中并无 librime 本体。

- **LGPL-2.1-or-later（主体）**：Fcitx5 核心、libime、fcitx5-lua、
  fcitx5-chinese-addons（SPDX ×88）、fcitx5-rime（×10）、fcitx5-chewing（×2）、
  fcitx5-hangul（×3）、libime-jyutping。
  按 LGPL-2.1 条款单独保留；其 "or-later" 只上溯至 LGPL-3.0，不得被升格为
  AGPL，作为聚合体分发时随本产品一同提供源码即可。
- **GPL-2.0-or-later（含升级条款）**：fcitx5-anthy（×32）、fcitx5-unikey（×32）、
  fcitx5-sayura（×2）、fcitx5-libthai（×6）、fcitx5-rime（×4）、
  fcitx5-chewing（×1）、fcitx5-hangul（×1）。
  可依升级条款升至 GPL-3.0-or-later，再依 AGPL-3.0 §13 与本作品组合发布。
- **fcitx5-unikey 另有 LGPL-2.0-or-later ×9**：按 LGPL 路径单独保留。
- **librime 本体**：未以源码子模块引入；`.gitmodules` 只有 fcitx5-rime 与
  rime-* 词库，预编译依赖（若有）在 fcitx5-android/prebuilt 中按上游声明处理。
- **ClearURLs Rules**：LGPL-3.0-or-later。
- **kenlm**：LGPL-2.1（Apache-2.0 双许可可选）。
- **yoga**：MIT License。
- **anthy-unicode**：LGPL-2.1-or-later。
- **rime-prelude / rime-essay / rime-luna-pinyin / rime-stroke**：各自上游声明
  （GPL-3.0 / LGPL-3.0 / CC 等，见各子模块内 LICENSE 文件）。

## 5. MBM 新增第三方依赖

| 组件 | 用途 | 许可 |
|---|---|---|
| ONNX Runtime Android（Microsoft） | 离线 OCR 推理（随主 APK 分发） | MIT |
| PaddleOCR PP-OCRv6 small 模型（assets/ocr/） | 离线 OCR 模型 | Apache-2.0 |
| SQLCipher Android（Zetetic） | 屏幕记忆 + 剪贴板本地加密存储 | BSD-3-Clause |
| RapidOCR v3.10.0（算法参考，未包含代码） | OCR 流水线算法参考 | Apache-2.0 |
| Ismantic/Handwritten（vendored 代码 + 模型） | 离线手写识别（lib/handwritten） | Apache-2.0 |
| ncnn（Tencent，预编译静态库 ncnn-20250503-android） | 离线手写推理引擎 | BSD-3-Clause |
| AndroidX Core / Core-Ktx（AndroidX） | Rich Content API 兼容封装（InputConnectionCompat / InputContentInfoCompat / FileProvider） | Apache-2.0 |
| kotlinx.serialization（JetBrains） | 自定义图像表情清单的本机序列化 | Apache-2.0 |

v1.0.73 表情功能另引用以下**公开标准**（非第三方库、非专有资产，仅实现其
码点判据或系统协议，故不计入上表依赖）：
- **The Unicode Standard / UTS #51「Unicode Emoji」**：Extended_Pictographic、
  ZWJ 序列、Emoji_Modifier、Variation Selector、Keycap、Regional Indicator、
  TAG 序列等码点结构判据（`core/EmojiSequence.kt`）。
- **Unicode CLDR `RGI_Emoji` 集合**：表情序列的规范性参考。
- **Android Rich Content API / Image Keyboard**（AOSP `EditorInfo.contentMimeTypes`
  与 `InputConnection.commitContent`）：图像表情上屏协议。
- **Android Storage Access Framework**（AOSP）：用户运行时导入图像资产。
- **Android Clipboard Framework**（AOSP `ClipboardManager` / `ClipData` /
  `ContentResolver`）：v1.0.75 复制侧读取与输出侧写回系统剪贴板的标准通道。
- **公开图像格式签名**（PNG / JPEG / GIF / WebP / BMP / ISO Base Media File
  Format）：v1.0.75 用文件头魔数嗅探真实位图类型，不信任剪贴板声明的 MIME
  （`data/emoji/ClipboardStickerExtractor.kt`）。均为公开规范，非第三方库。

上述标准与组件均以公开规范或 SPDX 许可标识表达；本产品**不内置、不分发
任何第三方专有表情包**，图像表情资产 100% 由使用者在运行时自行导入，
著作权与来源由导入者负责。宿主应用若不写入系统剪贴板、或仅写私有短码/
加密素材，则受 Android 平台边界限制，无法取得图像字节。

### 5.1 离线手写模块说明

- 上游仓库：https://github.com/Ismantic/Handwritten（Apache-2.0）
- 引入方式：**vendored**（非 submodule），代码置于 `lib/handwritten/`，
  完整保留上游版权与许可，模块内另附 `lib/handwritten/NOTICE`。
- 模型：`assets/model.ncnn.param` / `assets/model.ncnn.bin` / `assets/charset.json`，
  MobileNetV2 INT8 量化，GB2312 一级 3755 字，随 APK 打包，**不联网**（APK 无 INTERNET 权限）。
- MBM 的修改：JNI 层线程数用满核心；新增 **13 路 TTA 集成**推理（平移 / 缩放 / 微旋转变体
  概率平均）；禁用 vulkan / fp16 / bf16 / winograd 以追求最高精度。改动均在文件头注明。

## 6. 仅作功能 / 规则格式对照，未包含代码

- **第三方 Rime 系输入法项目 A**：基于 Rime 定制开发。
  MBM 兼容其 Rime 方案与词库格式，**不包含**其任何代码，也不反编译其 APK。
- **第三方 Rime 系输入法项目 B**：基于 Rime 构建。同上，仅作规则格式对照。
- **HeliBoard / AOSP Keyboard / 问墨 Wenmo / FUTO Keyboard / AnySoftKeyboard /
  FlorisBoard / Urik**：隐私与离线能力的做法参考（密码字段不学习、无网络权限、
  SQLCipher 本地加密），不包含其代码。

## 7. 合规红线

- 不反编译、不并入任何闭源 APK 的代码。
- 任何上游代码的改动均需在对应文件头保留原 SPDX 声明并追加 MBM 修改说明。
- 发布物必须包含 `LICENSE`（AGPL-3.0 全文）、`NOTICE` 与本文件。
