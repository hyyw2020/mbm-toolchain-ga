# 状态栏整体排除 —— 只读评估（实现位置与代价）

> **状态**：只读评估，**未改任何代码**。等用户确认后再进 Step 1 方案 / Step 2 实施。
> **依据**：`icon_baseline` 实测（合成图）+ 源码走读。

## 1. 为什么要单独排除，而不是靠置信过滤

`icon_baseline` 实测状态栏元素（合成图，对应真机 `0N谷100 / 03:39`）：

| GT 文本 | 识别 | score |
|---|---|---|
| `ON` | `ON` / `NO` | **0.9999 / 0.9993** |
| `03:39` | `03:39` | **0.9999 / 0.9998** |
| `100` | `100` | **1.0000** |
| `battery` | `_` | 0.805 / 0.915 |

**状态栏是高置信真文字**（score ≈ 1.0）。这意味着：

- 方案 A（置信阈值过滤）**对状态栏完全无效**——它是 OCR 认得最准的东西之一；
- 只有「按区域整体切掉」这一条路，且是**零误杀**（切掉的是非内容区）。

同时这也反证：图标噪声（score ≤ 0.73）与状态栏噪声（score ≈ 1.0）是**两类不同问题**，
必须分别处理，不能指望一个过滤同时解决。

## 2. 调用链（源码事实）

```
RecallService.kt:303-305   Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, ...)
RecallService.kt:310       OcrEngine.recognize(this@RecallService, bitmap, true)
OcrEngine.kt:190           suspend fun recognize(context, bitmap, useDetector)
OcrEngine.kt:197           private fun recognizeInternal(bitmap, useDetector)
OcrEngine.kt:198-199       val src = if (ARGB_8888) bitmap else bitmap.copy(...)
OcrEngine.kt:202-206       detect → mergeSameRowBoxes → nmsBoxes → 排序
OcrEngine.kt:213-219       warpCrop → recognizeCrop → correctWithLexicon → Line(...)
```

截图来自 `AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, ...)`
（`RecallService.kt:336`），是**全屏截图，含状态栏**（API 34+ 才有此能力，
见 `RecallService.kt:287-292` 的注释）。

## 3. 候选实现位置

| 位置 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. `RecallService.kt:303-310`** | `recognize()` 前把 bitmap 顶部 `statusBarHeight` 切掉 | 截图语义归 RecallService，最干净；**零 OCR 引擎改动**；不影响其他调用方 | 只惠及这一条 OCR 路径 |
| B. `OcrEngine.kt:197-199` | `src` 构造后、`detect` 前裁剪 | 所有入口统一受益 | 把 Android UI 语义（状态栏）塞进通用 OCR 引擎，职责污染；`OcrEngine` 目前只被 RecallService 调用，收益为 0 |

**倾向 A**（`OcrEngine` 当前唯一调用方就是 RecallService.kt:310，B 没有额外收益，
却引入职责污染）。

## 4. 代价清单

| # | 代价 | 严重度 | 说明 / 缓解 |
|---|---|---|---|
| 1 | **取高度** | 低 | `resources.getDimensionPixelSize(android.R.dimen.status_bar_height)`，标准 API。异常 ROM 可能返回 0 → **取 0 就不裁**，安全退化 |
| 2 | **HARDWARE 位图不可裁** | 中 | `wrapHardwareBuffer` 出来的是 HARDWARD 配置；`Bitmap.createBitmap(src, x, y, w, h)` 对 HARDWARE 位图抛 `UnsupportedOperationException`。必须先转软件位图（`OcrEngine.kt:198-199` 已有该路径，可复用/提前） |
| 3 | **坐标偏移** | 当前 0，未来高 | 裁剪后 det 框坐标整体上移 `top`。**当前 `Line.box` 无任何消费者**（`RecallService.kt:313` 只用 `it.text`；全仓 grep `\.quad`/`Line(` 在 OcrEngine 外零命中）→ 当前代价 0。但若未来用 box 做「点击定位到屏幕位置」，必须补回偏移，否则静默错位 |
| 4 | **方向 / 折叠屏 / 分屏** | 高（不确定性） | 横屏时系统栏在**侧边**，「切顶部」逻辑失效；折叠屏、分屏、画中画、无障碍特殊模式都会改变系统栏几何。固定「切顶部 N px」不是普适解法 |
| 5 | **`takeScreenshot` 是否已含状态栏** | 高（不确定性） | **这是最大未知**：若某些设备/ROM 的 `takeScreenshot` 已排除系统栏区域，再裁就是**误杀第一行真实内容**。必须真机验证截图实际内容边界 |

## 5. 零误杀的前提条件

「零误杀」成立需要同时满足：

1. 取得的状态栏高度**准确**（不偏大）；
2. 该设备的 `takeScreenshot` **确实包含**状态栏区域；
3. 当前不是横屏 / 折叠 / 分屏等系统栏不在顶部的形态。

任一不满足，都可能从「零误杀」变成「误杀第一行内容」。因此：

> **该方案必须真机验证后才能实施**，且建议加保护：裁剪后若首行内容异常
> （如原本有的文字消失）应有可观测信号——这与用户要求的「禁止静默丢弃」同向。

## 6. 更稳的替代口径（若固定顶部高度不可靠）

与其按像素高度切，不如**按检测结果反向约束**：状态栏元素的共同特征是
- 位于图像最顶部一小条（相对位置，而非绝对像素）
- 元素高度小、且**与内容区文本行不在同一基线带**

即「相对位置 + 不与任何内容行构成同行关系」。但这已属于下一轮方案设计，
此处仅记录备选，不展开。

## 7. 结论

- **技术上可行、实现位置明确**（`RecallService.kt:303-310`）、代码量小（约 10 行）；
- **最大风险不是实现，而是前提未验证**：`takeScreenshot` 的截图边界 + 系统栏几何因设备/方向而异；
- **必须真机验证**：同一设备竖屏/横屏各截一张，确认状态栏确实在截图内、高度取值正确；
- 在真机验证完成前，**不建议**把此改动并入任何 release。
