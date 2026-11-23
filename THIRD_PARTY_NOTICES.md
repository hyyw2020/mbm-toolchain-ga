# 第三方来源声明 / Third-Party Notices

本文件记录 MBM 从外部项目借鉴的代码与数据，逐项列出来源、作者、许可与版权处理方式。
其效力与 `LICENSE`（含末尾「借鉴来源附录」）、根目录 `NOTICE` 一致；三者对同一组件的描述以本文件为准。
未经特别说明，本文件所述借鉴均依据 GNU 通用公共许可证第 3 版（GPL-3.0-or-later）进行，
原始版权声明与许可文本在移植文件中逐字保留。

## 一、借鉴自 CyIME 的实现

| 项 | 内容 |
| --- | --- |
| 上游项目 | CyIME — Android 输入法，基于 Rime 引擎 |
| 上游作者与维护者 | Cyletix |
| 上游仓库 | https://github.com/Cyletix/CyIME |
| 上游版本与提交 | 1.15.105，`6d596516d` |
| 上游许可 | GPL-3.0-or-later（见上游 `LICENSE`，GPLv3 正文） |
| 借鉴方式 | 复制实现与对应 JVM 单测至 MBM 源码，调整包名与注释表述，未改变算法行为 |
| 版权处理 | 每个移植文件保留 `SPDX-License-Identifier: GPL-3.0-or-later` 与 `SPDX-FileCopyrightText: Copyright 2026 Cyletix` 头 |
| 许可兼容 | GPL-3.0 代码并入 AGPL-3.0 工程时被允许；AGPL 为 GPL 的附加网络条款超集，移植文件继续按其原 GPLv3 授权，不重新许可为 AGPL |

### 1. 英文缩写补全链

| MBM 目标文件 | 上游来源路径 |
| --- | --- |
| `app/src/main/java/org/fcitx/fcitx5/android/recall/enhance/EnglishContractions.kt` | `app/src/main/java/com/kingzcheung/xime/glide/EnglishBigrams.kt`（`EnglishContractions` 类） |
| `app/src/main/java/org/fcitx/fcitx5/android/recall/enhance/EnglishContractionCompletion.kt` | `app/src/main/java/com/kingzcheung/xime/association/EnglishContractionCompletion.kt` |
| `app/src/test/java/org/fcitx/fcitx5/android/recall/enhance/EnglishContractionCompletionTest.kt` | `app/src/test/java/com/kingzcheung/xime/association/EnglishContractionCompletionTest.kt` |

功能：将去撇号的英文写法（如 `im`、`dont`）按上下文映射为规范缩写（`I'm`、`don't`），
并区分本身即常用词的写法（如 `its`、`well`）。MBM 借此补齐英文候选栏的缩写补全能力。

### 2. 整词接续联想链

| MBM 目标文件 | 上游来源路径 |
| --- | --- |
| `app/src/main/java/org/fcitx/fcitx5/android/recall/enhance/WordContinuationTable.kt` | `app/src/main/java/com/kingzcheung/xime/association/WordContinuationTable.kt`（含 `mergeWordContinuations`） |
| `app/src/main/java/org/fcitx/fcitx5/android/recall/enhance/AssociationCandidate.kt` | `app/src/main/java/com/kingzcheung/xime/association/AssociationCandidate.kt` |
| `app/src/test/java/org/fcitx/fcitx5/android/recall/enhance/WordContinuationTableTest.kt` | `app/src/test/java/com/kingzcheung/xime/association/WordContinuationTableTest.kt`（纯算法测试部分） |

功能：以「长词由另一词开头」的规则记录整词接续关系，例如「厄尔尼诺 → 现象」，分数为条件概率；
`mergeWordContinuations` 将整词接续按权重并入既有联想分数表。MBM 借此补齐多字词的联想能力。

### 3. 数据资产

| MBM 目标文件 | 上游来源路径 | 说明 |
| --- | --- | --- |
| `app/src/main/assets/association/english_contractions.txt` | `app/src/main/assets/english_contractions.txt` | 英文缩写表，60 行，上游由 `tools/glide_lab/build_english_bigrams.py` 生成；格式与用途见上游注释 |

### 未纳入的上游技术链及理由

以下 CyIME 技术链未并入 MBM 主程序，原因统一为架构不兼容或收益低于风险：

- **Rime 输入引擎**：MBM 基于 fcitx5，两套引擎不可并存，替换将损毁全部既有输入能力。
- **离线语音、OCR、手写模型与原生库**：MBM 采用自有实现，且上游模型与 `.so` 不在本次源码快照内。
- **Compose 界面组件**：MBM 使用 XML 视图体系，无法直接复用 Compose 组件。
- **离线翻译**：依赖上游原生翻译库，MBM 无对应运行时。
- **英文滑行识别（GlideDecoder 链）**：依赖键盘坐标与轨迹采集，需改造 View 层，接入面与风险大于收益。
- **候选释义（gloss）**：依赖 Compose 与上游设置、存储抽象，迁移成本高。

上述未并入的链作为完整源码资产存放于发布仓库 `cyime/` 子目录，仅供参照，不参与 MBM 编译。

## 二、其他第三方组件

MBM 其余第三方组件（fcitx5-android、librime、ONNX Runtime 等）的许可与来源，
见根目录 `NOTICE`、`app/src/main/assets/licenses/NOTICE.txt` 及 `docs/upstream.md`；
机器可读台账见 `docs/sbom/`。本文件不重复列举。
