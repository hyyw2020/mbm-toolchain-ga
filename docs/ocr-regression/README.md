# OCR 旋转文本 / 宽间隙拆框回归验证工程

> 对应修复：commit `483ff4a`（MBM v0.001.1，见 `plugin/recall/.../ocr/OcrEngine.kt`
> 头部 v1.0.28 注释）。用户实报「识别不到云·战双帕弥什，拆分有问题」——
> 倾斜 10-15° 的游戏 logo 在 v0.001 上识别结果恒为空。
>
> 第二轮（v1.0.29，commit 见仓库历史）：26 场景同源扫描，修复竖排识别盲区 +
> 同行合并加固，扫描脚本与完整修复版管线一并归档。

## 文件

| 文件 | 说明 |
|---|---|
| `ppocr_pipeline.py` | 按 `OcrEngine.kt` 1:1 移植的 Python 管线（onnxruntime + PIL），加载插件 assets 内的真实 PP-OCRv6 模型 |
| `fix_pipeline.py` | 完整修复版管线（order_points 极角 + warp_crop 欧氏边长 + 同行合并 + 竖排 rot90），与 Kotlin v1.0.29 一致 |
| `test_fix_matrix.py` | 验证矩阵：间隙扫描 / 倾角扫描 / 倾角×间隙组合，猴子补丁切换修复前后对照 |
| `test_merge.py` | 同行框合并验证：宽间隙拆框合并效果 + 双行菜单误合并风险 |
| `scan_bugs.py` | 26 场景同源扫描（拆分类 / 识别类 / 旋转组合类），输出 scan_results.json |
| `verify_fix_dirs.py` | 修复方向可行性验证（竖排 rot90 / rec 宽度上限 / 形近字是否句子相关） |

## 依赖

```bash
pip install onnxruntime pillow numpy
# 另需任一 CJK 字体（Noto Sans CJK 优先，见脚本内 FONT_CANDIDATES）
```

模型路径：脚本默认从
`plugin/recall/src/main/assets/ocr/` 读取 4 个资产
（`ch_PP-OCRv6_det_small.onnx` / `ch_PP-OCRv6_rec_small.onnx` /
`ch_ppocr_mobile_v2.0_cls_infer.onnx` / `ppocrv6_dict.txt`），
与 APK 内打包的完全一致。

## 跑法

```bash
python3 test_fix_matrix.py   # 倾角/间隙/组合矩阵 + 修复前后对照
python3 test_merge.py        # 同行合并 + 误合并风险
```

## 结论（真实模型实测，2026-10-24）

- 修复前：倾角 ≥ ±6° 识别结果恒为 `''`（score 0.000）
- 修复后：-20°~+15° 全部有识别结果，多数角度精确命中「云·战双帕弥什」
- 宽间隙（≥0.71×字高）det 拆两框 → 同行合并后完整命中；
  4 行菜单文字无误合并
- 残留边界（均属 rec 模型精度/det 模型激活行为，非代码缺陷）：
  - ±20° 个别形近字误差或触边丢字
  - 间隙 ≥1.4×字高时「·」概率图不激活（max≈0.008），无从恢复
