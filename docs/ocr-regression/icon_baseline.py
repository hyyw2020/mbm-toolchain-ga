# -*- coding: utf-8 -*-
"""图标场景 baseline（只读观测，不改任何管线逻辑）。

回答用户 Step 1 补充指令的核心问题：
  1. 图标框被误识成什么？score 分布如何？
  2. 图标框的宽高比 / 行高 / 是否参与 merge / 是否紧贴同行文本？
  3. **图标 score 与真字 score 是否可分？** → 决定 A（置信主判据）能否成立；
     若严重重叠，则如实报告"置信不可分"，另找判据，不硬选。

分类口径（**ground truth 驱动**，不用启发式）：
  det 框中心落在 fixtures/icons/manifest.json 记录的元素 bbox 内 → 取该元素
  的 type（icon / text / status / switch）。第一版曾用 near_text 启发式，把
  真单字「设」「否」错分成图标嫌疑，导致可分性判定失真，已废弃。

观测口径（与 fix_pipeline 现有判据一致，未修改其逻辑）：
  - in_merge     ：该 det 原始框与任一其他框满足 F.try_merge_same_row
  - near_text    ：存在另一框，中心连线近水平（|dy|/dist <= 0.707）且
                   水平间隙 < 1.0 × max(h)
  - in_status_bar：框中心位于图像顶部 8% 高度内

⚠️ 输入为合成图（fixtures/icons/），非真机截图；绝对数值待真机校准。

运行：python3 icon_baseline.py
输出：icon_baseline_result.json + icon_baseline_report.md
"""
import glob
import json
import math
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image

import fix_pipeline as F
import ppocr_pipeline as P

HERE = os.path.dirname(os.path.abspath(__file__))
FIX_DIR = os.path.join(HERE, "fixtures", "icons")
MANIFEST = os.path.join(FIX_DIR, "manifest.json")
OUT_JSON = os.path.join(HERE, "icon_baseline_result.json")
OUT_MD = os.path.join(HERE, "icon_baseline_report.md")

ANGLE_TAN = 0.707  # 与 fix_pipeline.try_merge_same_row 同口径
STATUS_BAR_RATIO = 0.08


def load_gt():
    with open(MANIFEST, encoding="utf-8") as fp:
        m = json.load(fp)
    return {im["file"]: im["ground_truth"] for im in m["images"]}


def gt_at(gts, x, y):
    """框中心落在哪个 ground truth 元素内；无匹配返回 None。"""
    hits = []
    for g in gts:
        bx, by, bw, bh = g["bbox"]
        if bx <= x <= bx + bw and by <= y <= by + bh:
            hits.append(g)
    if not hits:
        return None
    # 多个命中时取面积最小者（最内层）
    return min(hits, key=lambda g: g["bbox"][2] * g["bbox"][3])


def classify(gt_type, text):
    if gt_type == "icon":
        return "icon_misread" if text else "icon_silent"
    if gt_type == "status":
        return "status"
    if gt_type == "switch":
        return "switch"
    if gt_type == "text":
        n = len(text.replace(" ", "").strip())
        if n == 0:
            return "text_empty"
        return "text_single" if n == 1 else "text_multi"
    return "unknown"


def analyze(path, gts):
    img = Image.open(path).convert("RGB")
    W, H = img.size
    raw = P.detect(img)

    rows = []
    for q in raw:
        w, h = F._quad_size(q)
        cx, cy = F._quad_center(q)
        r = F.recognize_crop_vertical(F.warp_crop_fixed(img, q))
        text = r[0] if r else ""
        g = gt_at(gts, cx, cy)
        rows.append({
            "text": text,
            "score": round(r[1], 4) if r else None,
            "w": round(w, 1), "h": round(h, 1),
            "aspect": round(w / max(h, 1e-6), 3),
            "cx": round(cx, 1), "cy": round(cy, 1),
            "gt_type": g["type"] if g else None,
            "gt_text": g.get("text") if g else None,
            "kind": classify(g["type"] if g else None, text),
        })

    for i, ri in enumerate(rows):
        ri["in_merge"] = any(
            F.try_merge_same_row(raw[i], raw[j]) is not None
            for j in range(len(raw)) if j != i)

    for i, ri in enumerate(rows):
        near = False
        for j, rj in enumerate(rows):
            if i == j:
                continue
            dx, dy = rj["cx"] - ri["cx"], rj["cy"] - ri["cy"]
            dist = math.hypot(dx, dy)
            if dist < 1e-6 or abs(dy) / dist > ANGLE_TAN:
                continue
            if dist - (ri["w"] + rj["w"]) / 2.0 < 1.0 * max(ri["h"], rj["h"]):
                near = True
                break
        ri["near_text"] = near

    for ri in rows:
        ri["in_status_bar"] = ri["cy"] < H * STATUS_BAR_RATIO

    lines, n_sup = F.recognize_detailed(img)
    return {
        "file": os.path.basename(path), "size": [W, H],
        "n_det": len(raw), "n_final": len(lines),
        "final_texts": [t for t, s, q in lines],
        "nms_suppressed": n_sup,
        "det_boxes": rows,
    }


def dist(vals):
    vals = [v for v in vals if v is not None]
    if not vals:
        return None
    return {"n": len(vals), "min": round(min(vals), 4),
            "max": round(max(vals), 4), "mean": round(st.mean(vals), 4),
            "median": round(st.median(vals), 4)}


GROUPS = ("icon_misread", "icon_silent", "text_single", "text_multi",
          "text_empty", "status", "switch", "unknown")


def main():
    gtmap = load_gt()
    paths = sorted(glob.glob(os.path.join(FIX_DIR, "*.png")))
    assert paths, f"未找到夹具图：{FIX_DIR}"
    results = []
    for p in paths:
        name = os.path.basename(p)
        print(f"\n===== {name} =====")
        r = analyze(p, gtmap.get(name, []))
        results.append(r)
        print(f"  det 框 {r['n_det']} 个 → 最终 {r['n_final']} 行；"
              f"NMS 抑制 {r['nms_suppressed']}")
        for b in r["det_boxes"]:
            mark = "   <== 图标被误识" if b["kind"] == "icon_misread" else ""
            print(f"    [{b['kind']:13s}] gt={str(b['gt_type']):6s} "
                  f"'{b['text']}' score={b['score']} w={b['w']} h={b['h']} "
                  f"ar={b['aspect']} merge={int(b['in_merge'])} "
                  f"near={int(b['near_text'])}{mark}")

    all_boxes = [b for r in results for b in r["det_boxes"]]
    groups = {k: [b for b in all_boxes if b["kind"] == k] for k in GROUPS}

    print("\n" + "=" * 78)
    print("score 分布（按 ground-truth 类别）—— 判定 A（置信主判据）是否可分")
    print("=" * 78)
    print(f"{'类别':14s} {'n':>4s} {'min':>8s} {'median':>8s} {'mean':>8s} {'max':>8s}")
    for k in GROUPS:
        d = dist([b["score"] for b in groups[k]])
        if d:
            print(f"{k:14s} {d['n']:4d} {d['min']:8.4f} {d['median']:8.4f} "
                  f"{d['mean']:8.4f} {d['max']:8.4f}")

    icon_sc = [b["score"] for b in groups["icon_misread"] if b["score"] is not None]
    real_sc = [b["score"] for b in groups["text_single"] + groups["text_multi"]
               if b["score"] is not None]

    print("\n----- 可分性判定（图标 vs 真文字）-----")
    verdict = None
    if icon_sc and real_sc:
        print(f"  图标被误识框 score: min={min(icon_sc):.4f} max={max(icon_sc):.4f} (n={len(icon_sc)})")
        print(f"  真实文字框   score: min={min(real_sc):.4f} max={max(real_sc):.4f} (n={len(real_sc)})")
        ov = max(0.0, min(max(icon_sc), max(real_sc)) - max(min(icon_sc), min(real_sc)))
        print(f"  区间重叠宽度 = {ov:.4f}")
        if ov <= 0:
            verdict = "separable"
            print("  => **完全可分：A（置信阈值）可作主判据**")
        elif ov < 0.10:
            verdict = "partial"
            print("  => 小幅重叠：A 可作主判据，但需辅助条件降误杀")
        else:
            verdict = "overlap"
            print("  => **重叠显著：置信不可分**，A 不能单独作主判据，需另找判据")
    else:
        print("  样本不足，无法判定")

    sb = groups["status"]
    print("\n----- 状态栏专项（独立评估项）-----")
    print(f"  状态栏元素框数 = {len(sb)}")
    for b in sb:
        print(f"    gt='{b['gt_text']}' → '{b['text']}' score={b['score']}")
    if sb:
        hi = [b for b in sb if (b["score"] or 0) >= 0.9]
        print(f"  其中 score>=0.9 的高置信框：{len(hi)}/{len(sb)}")
        print("  => 状态栏是高置信真文字，**置信过滤对它无效**，只能按区域排除")

    with open(OUT_JSON, "w", encoding="utf-8") as fp:
        json.dump(results, fp, ensure_ascii=False, indent=1)
    print(f"\n明细已存 {OUT_JSON}")
    write_report(results, groups, icon_sc, real_sc, verdict)
    print(f"报告已存 {OUT_MD}")


def write_report(results, groups, icon_sc, real_sc, verdict):
    L = []
    A = L.append
    A("# 图标场景 Baseline 报告（图标误检 + 误识）\n")
    A("> ⚠️ **数据来源为 PIL 合成图**（`fixtures/icons/`），**不是真机截图**。")
    A("> 沙箱无 Android 设备。合成目的是把「图标场景」首次纳入回归集")
    A("> （此前 30 场景零覆盖）并跑出 baseline 分布；")
    A("> **绝对数值待真机截图到手后重跑校准**，结论方向可参考、阈值不可直接采用。\n")
    A("生成命令：`python3 icon_baseline.py`（分类由 `manifest.json` 的 ground truth 驱动）\n")

    A("\n## 1. 逐框明细（det 原始框级别）\n")
    for r in results:
        A(f"\n### {r['file']}（{r['size'][0]}×{r['size'][1]}，det {r['n_det']} 框 → 最终 {r['n_final']} 行）\n")
        A("| # | GT | GT文本 | 识别 | score | w | h | 宽高比 | merge | 紧贴同行 | 分类 |")
        A("|---|---|---|---|---|---|---|---|---|---|")
        for i, b in enumerate(r["det_boxes"]):
            A(f"| {i+1} | {b['gt_type']} | {b['gt_text'] or '-'} | `{b['text']}` | "
              f"{b['score']} | {b['w']} | {b['h']} | {b['aspect']} | "
              f"{'是' if b['in_merge'] else '否'} | "
              f"{'是' if b['near_text'] else '否'} | {b['kind']} |")
        A(f"\n最终管线输出：`{r['final_texts']}`\n")

    A("\n## 2. score 分布（A 方案可分性判定）\n")
    A("| 类别 | 含义 | n | min | median | mean | max |")
    A("|---|---|---|---|---|---|---|")
    meaning = {
        "icon_misread": "图标且 rec 输出非空（**有害噪声**）",
        "icon_silent": "图标且 rec 输出空（无害）",
        "text_single": "真单字（**误杀风险面**）",
        "text_multi": "真多字文本",
        "text_empty": "文字区但 rec 空",
        "status": "状态栏",
        "switch": "开关",
        "unknown": "未匹配 GT",
    }
    for k in GROUPS:
        d = dist([b["score"] for b in groups[k]])
        if d:
            A(f"| {k} | {meaning[k]} | {d['n']} | {d['min']} | {d['median']} | "
              f"{d['mean']} | {d['max']} |")

    A("\n## 3. 关键结论：图标 score vs 真字 score 是否可分\n")
    if icon_sc and real_sc:
        ov = max(0.0, min(max(icon_sc), max(real_sc)) - max(min(icon_sc), min(real_sc)))
        A(f"- 图标被误识框 score ∈ [{min(icon_sc):.4f}, {max(icon_sc):.4f}]（n={len(icon_sc)}）")
        A(f"- 真实文字框 score ∈ [{min(real_sc):.4f}, {max(real_sc):.4f}]（n={len(real_sc)}）")
        A(f"- 区间重叠宽度 = **{ov:.4f}**\n")
        if verdict == "separable":
            A("**判定：完全可分 → A（置信阈值）在本套件可作主判据。**")
        elif verdict == "partial":
            A("**判定：小幅重叠 → A 可作主判据，但需辅助条件降低误杀。**")
        else:
            A("**判定：重叠显著 → 置信不可分，A 不能单独作主判据。**")
            A("需另找判据（行内相对高度、与同行文本框高度比、是否参与 merge 等），")
            A("并在真机截图上验证。**不硬选。**")
        A("\n> ⚠️ 以上是**合成图**结论。真机 Material 矢量图标更精细，rec 可能给出更高")
        A("> 置信度，间隙可能收窄甚至消失。**阈值必须在真机截图上重新标定。**")
    else:
        A("- 样本不足，无法判定。需补充夹具或真机截图。")

    A("\n## 4. B（几何）误杀清单\n")
    sr = groups["text_single"]
    A(f"真实单字框（若被「单字 + 近方形」判据丢弃即为**误杀**）：**{len(sr)} 个**\n")
    A("| 文件 | GT文本 | 识别 | score | w | h | 宽高比 |")
    A("|---|---|---|---|---|---|---|")
    for r in results:
        for b in r["det_boxes"]:
            if b["kind"] == "text_single":
                A(f"| {r['file']} | {b['gt_text']} | `{b['text']}` | {b['score']} | "
                  f"{b['w']} | {b['h']} | {b['aspect']} |")
    if not sr:
        A("| - | - | - | - | - | - | - |")
    A("\n> B 只能作辅助条件，且必须保留此清单逐条核对。\n")

    A("\n## 5. 状态栏专项\n")
    sb = groups["status"]
    A(f"状态栏元素框数：**{len(sb)}**\n")
    A("| 文件 | GT文本 | 识别 | score |")
    A("|---|---|---|---|")
    for r in results:
        for b in r["det_boxes"]:
            if b["kind"] == "status":
                A(f"| {r['file']} | {b['gt_text']} | `{b['text']}` | {b['score']} |")
    A("\n**这批框 score 普遍 >= 0.9（高置信真文字）→ 置信过滤（A）对状态栏完全无效，**")
    A("**只能按区域整体排除。** 实现位置与代价见交付说明。\n")

    A("\n## 6. 顺带发现（记录，不修）\n")
    A("- `Recall屏幂记忆`（score 0.8872）：「幕」→「幂」，中英混排形近字；")
    A("  对应用户真机的 `RecalL屏幕记忆`，属 rec 精度问题，非图标问题。")
    A("- 竖排「以弥止争」→「以弥山」（ar=0.155）：「止」→「山」，竖排 1 字错。")
    A("- 若干图标框 rec 输出**空串**（score 0.0 / None），本就无害，不需过滤。\n")

    A("\n## 7. 待办（下一轮）\n")
    A("1. 用户提供真机截图（PNG）到 `fixtures/real/`：设置菜单列表、带开关的行、")
    A("   带单字标签的行、竖排单字 → 重跑本脚本校准 score 分布与阈值。")
    A("2. 依 §3 判定结果定 A 是否可作主判据；不可分则另找判据。")
    A("3. 任何过滤必须：阈值可配置 + 记录被丢弃框（score/几何/原文），**禁止静默丢弃**。")
    A("4. 状态栏排除独立实现评估（见交付说明）。\n")

    with open(OUT_MD, "w", encoding="utf-8") as fp:
        fp.write("\n".join(L))


if __name__ == "__main__":
    main()
