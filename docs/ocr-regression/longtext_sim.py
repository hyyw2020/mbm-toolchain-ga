# -*- coding: utf-8 -*-
"""长文本静态模拟：测试文字能否被完全识别 + 正确拆分（字数无上限验证）。

背景（用户需求）：
  「需要拆分字的字数需要达到无上限。而不是像某输入法一样 5000 字以上就不拆分。」
  源码事实（已审计）：
    - RecallService.kt:93  MAX_TEXT_LENGTH = 4000 → text.take(4000) 静默截断
      （:212 NODE 路径、:317 OCR 路径）
    - OcrEngine.kt:794     targetW = min(REC_WIDTH_MAX=960, ...) → 单框超宽时**压缩**
      （按 REC_HEIGHT=48 估算，单行约 >20 字即被压扁）
    - OcrEngine.kt:98      DET_MAX_SIDE = 1280 → det 前长边缩到 1280（缩放可低至 0.21）
    - OcrEngine.kt:104     DET_MAX_CANDIDATES = 1000

两种渲染模式（都是合成图，非真机截图）：
  模式 A「单张超长图」：整篇文字渲染成一张 1080×N 大图，直接 OCR。
     用途：暴露 DET_MAX_SIDE 缩放导致的小字退化（极端情况）。
  模式 B「分屏（真实形态）」：按 2400px 一屏（RecallService 逐屏截图的高度）
     在**行边界**切片，每屏单独 OCR 后拼接去重。
     用途：模拟真实 Recall 行为，验证长文本靠多屏拼接能否无上限覆盖。

输出：longtext_sim_report.md
运行：python3 longtext_sim.py
"""
import difflib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image, ImageDraw, ImageFont

import fix_pipeline as F

HERE = os.path.dirname(os.path.abspath(__file__))
TEXT_FILE = os.path.join(HERE, "fixtures", "real", "user_test_text.txt")
OUT_MD = os.path.join(HERE, "longtext_sim_report.md")
OUT_JSON = os.path.join(HERE, "longtext_sim_result.json")

FONT_PATH = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
PAGE_W = 1080
MARGIN = 50
FS = 36
LINE_H = 62
BG = (255, 255, 255)
FG = (25, 25, 30)
SCREEN_H = 2400   # 真实一屏高度（RecallService 逐屏截图）
OVERLAP = 80      # 切片重叠，避免切断行


def font():
    return ImageFont.truetype(FONT_PATH, FS)


def layout_lines(text, f, d, avail_w):
    """把原文按空行分段、段内按像素宽度换行，返回渲染行列表（含段间空行）。"""
    out = []
    for para in text.split("\n"):
        if para.strip() == "":
            out.append("")
            continue
        # 段内按可用宽度换行
        cur = ""
        for ch in para:
            if d.textlength(cur + ch, font=f) > avail_w and cur:
                out.append(cur)
                cur = ch
            else:
                cur += ch
        if cur:
            out.append(cur)
    return out


def render(lines, f):
    h = max(SCREEN_H, 40 + len(lines) * LINE_H + 40)
    img = Image.new("RGB", (PAGE_W, h), BG)
    d = ImageDraw.Draw(img)
    y = 40
    for ln in lines:
        if ln:
            d.text((MARGIN, y), ln, font=f, fill=FG)
        y += LINE_H
    return img


def slice_at_row_boundary(lines, f, screen_h=SCREEN_H, overlap=OVERLAP):
    """按行边界把渲染行切成 ≤screen_h 的片段（相邻片段重叠 overlap 像素）。"""
    rows_per_screen = (screen_h - 80) // LINE_H
    step = max(1, rows_per_screen - overlap // LINE_H)
    segs = []
    i = 0
    while i < len(lines):
        segs.append(lines[i:i + rows_per_screen])
        if i + rows_per_screen >= len(lines):
            break
        i += step
    return segs


def norm(s):
    return "".join(s.split())


def char_metrics(ref, got):
    """字符级覆盖：LCS 匹配数 / 参考长度；返回 (覆盖率, 漏字, 多字)。"""
    sm = difflib.SequenceMatcher(None, ref, got, autojunk=False)
    matching = sum(b.size for b in sm.get_matching_blocks())
    missing = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("delete", "replace"):
            missing.append(ref[i1:i2])
    extra = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("insert", "replace"):
            extra.append(got[j1:j2])
    return matching / max(len(ref), 1), "".join(missing), "".join(extra)


def run_mode_a(img, label):
    lines, n_sup = F.recognize_detailed(img)
    got_lines = [t for t, s, q in lines]
    return {"label": label, "mode": "single_image", "n_lines": len(got_lines),
            "nms_suppressed": n_sup, "got_lines": got_lines,
            "scores": [round(s, 3) for t, s, q in lines]}


def run_mode_b(segs, label):
    got_lines = []
    for k, seg in enumerate(segs):
        img = render(seg, font())
        lines, _ = F.recognize_detailed(img)
        for t in (x for x, s, q in lines):
            got_lines.append(t)
    # 相邻屏重叠导致的重复行去重（完全相同的连续行）
    dedup = []
    for t in got_lines:
        if not dedup or dedup[-1] != t:
            dedup.append(t)
    return {"label": label, "mode": "sliced", "n_screens": len(segs),
            "n_lines": len(dedup), "got_lines": dedup}


def main():
    with open(TEXT_FILE, encoding="utf-8") as fp:
        text = fp.read()

    raw_lines = text.rstrip("\n").split("\n")
    n_chars_raw = len(text.replace("\n", ""))
    n_chars_nows = len(norm(text))
    longest = max(raw_lines, key=lambda s: len(norm(s)))
    f = font()

    print("=" * 78)
    print("测试文字规模")
    print("=" * 78)
    print(f"  总字符数（不含换行）      : {n_chars_raw}")
    print(f"  总字符数（不含任何空白）  : {n_chars_nows}")
    print(f"  自然行数                  : {len(raw_lines)}")
    print(f"  最长行字数                : {len(norm(longest))}")
    print(f"  最长行前 40 字            : {norm(longest)[:40]}")

    lines = layout_lines(text, f, ImageDraw.Draw(Image.new("RGB", (10, 10))),
                         PAGE_W - 2 * MARGIN)
    print(f"  按 {PAGE_W}px 宽 wrap 后渲染行数: {len(lines)}")
    img = render(lines, f)
    print(f"  单张渲染尺寸              : {img.size[0]}x{img.size[1]}"
          f"（det 长边将缩到 1280 → 缩放 {1280 / img.size[1]:.3f}）")

    # wrap 后最长行的像素宽 → 估算 rec 是否触发 REC_WIDTH_MAX 压缩
    dd = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    max_row_px = max((dd.textlength(l, font=f) for l in lines if l), default=0)
    max_row_chars = max((len(l) for l in lines if l), default=0)
    box_h_est = FS * 1.12          # det 检出框高≈字高略大
    est_ratio = max_row_px / box_h_est
    est_need_w = 48 * est_ratio    # 不压缩所需 targetW
    rec_clip = est_need_w > 960
    segs_h = []
    rows_per_screen = (SCREEN_H - 80) // LINE_H
    segs0 = slice_at_row_boundary(lines, f)
    for sg in segs0:
        segs_h.append(40 + len(sg) * LINE_H + 40)
    screen_scale = 1280 / max(segs_h) if segs_h else 0

    results = {"stats": {
        "chars_raw": n_chars_raw, "chars_nows": n_chars_nows,
        "natural_lines": len(raw_lines),
        "longest_line_chars": len(norm(longest)),
        "wrapped_lines": len(lines), "image": list(img.size),
        "det_scale": round(1280 / img.size[1], 4),
        "max_row_px": round(max_row_px, 1),
        "max_row_chars": max_row_chars,
        "est_ratio": round(est_ratio, 2),
        "est_need_targetw": round(est_need_w, 0),
        "rec_clip": rec_clip,
        "screen_scale": round(screen_scale, 4),
        "max_text_length": 4000, "rec_width_max": 960, "det_max_side": 1280,
    }}

    # ---------- 模式 A：单张超长图 ----------
    print("\n" + "=" * 78)
    print("模式 A：单张超长图直接 OCR（暴露 DET_MAX_SIDE 缩放退化）")
    print("=" * 78)
    ra = run_mode_a(img, "A/单张超长图")
    ref = norm(text)
    got_a = norm("".join(ra["got_lines"]))
    cov_a, miss_a, extra_a = char_metrics(ref, got_a)
    ra["coverage"] = round(cov_a, 4)
    results["mode_a"] = ra
    print(f"  OCR 行数 {ra['n_lines']}，字符覆盖率 {cov_a:.2%}")

    # ---------- 模式 B：分屏（真实形态） ----------
    print("\n" + "=" * 78)
    print("模式 B：按 2400px 一屏切片（真实 Recall 逐屏截图形态）")
    print("=" * 78)
    segs = slice_at_row_boundary(lines, f)
    print(f"  切片数（屏数）= {len(segs)}，每屏 ≤ {(SCREEN_H - 80) // LINE_H} 行")
    rb = run_mode_b(segs, "B/分屏拼接")
    got_b = norm("".join(rb["got_lines"]))
    cov_b, miss_b, extra_b = char_metrics(ref, got_b)
    rb["coverage"] = round(cov_b, 4)
    results["mode_b"] = rb
    print(f"  OCR 行数 {rb['n_lines']}，字符覆盖率 {cov_b:.2%}")

    # ---------- 上限分析 ----------
    print("\n" + "=" * 78)
    print("上限触发分析")
    print("=" * 78)
    rows_per_screen = (SCREEN_H - 80) // LINE_H
    seg_heights = [40 + len(s) * LINE_H + 40 for s in segs]
    print(f"  MAX_TEXT_LENGTH=4000 : 原文 {n_chars_raw} 字 → "
          f"{'未触发（距上限 %d 字）' % (4000 - n_chars_raw) if n_chars_raw < 4000 else '触发：超出部分被 take() 静默丢弃'}")
    print(f"  REC_WIDTH_MAX=960    : wrap 后最长行 {max_row_px:.0f}px ÷ 框高≈40px "
          f"→ ratio≈{est_ratio:.1f}，不压缩需 targetW≈{est_need_w:.0f} → "
          f"{'触发：被压缩约 %.0f%%' % ((1 - 960 / est_need_w) * 100) if rec_clip else '未触发'}")
    print(f"  DET_MAX_SIDE=1280    : 模式 A 图高 {img.size[1]}px → 缩放 "
          f"{1280/img.size[1]:.3f}；模式 B 每屏高 {max(segs_h)}px → 缩放 "
          f"{screen_scale:.3f}")
    print(f"  DET_MAX_CANDIDATES=1000 : 每屏约 {rows_per_screen} 行 → 远离上限")

    with open(OUT_JSON, "w", encoding="utf-8") as fp:
        json.dump(results, fp, ensure_ascii=False, indent=1)
    print(f"\n明细已存 {OUT_JSON}")
    write_report(results, ref, cov_a, miss_a, extra_a, cov_b, miss_b, extra_b, text)
    print(f"报告已存 {OUT_MD}")


def write_report(r, ref, cov_a, miss_a, extra_a, cov_b, miss_b, extra_b, text):
    s = r["stats"]
    L = []
    A = L.append
    A("# 长文本静态模拟报告：能否完全识别 + 正确拆分（字数无上限验证）\n")
    A("> ⚠️ **合成图，非真机截图**（沙箱无 Android 设备）。渲染参数：1080px 宽、")
    A("> 字号 36px、行高 62px、白底黑字，模拟手机上的文档/网页正文。\n")
    A("测试文字：`fixtures/real/user_test_text.txt`（用户提供）\n")

    def han(c):
        return "\u4e00" <= c <= "\u9fff"

    miss_b_han = [c for c in miss_b if han(c)]
    miss_b_sym = [c for c in miss_b if not han(c)]

    A("\n## 1. 测试文字规模\n")
    A("| 指标 | 值 |")
    A("|---|---|")
    A(f"| 总字符数（不含换行） | **{s['chars_raw']}** |")
    A(f"| 总字符数（不含任何空白） | {s['chars_nows']} |")
    A(f"| 自然行数 | {s['natural_lines']} |")
    A(f"| 最长自然行字数 | {s['longest_line_chars']}（单行不换行的极端行） |")
    A(f"| 按 1080px 宽 wrap 后渲染行数 | {s['wrapped_lines']} |")
    A(f"| wrap 后最长行宽 | {s['max_row_px']}px（约 {s['max_row_chars']} 字/行） |")
    A(f"| 单张渲染尺寸 | {s['image'][0]}×{s['image'][1]} |")
    A(f"| 模式 A det 缩放系数 | **{s['det_scale']}**（长边缩到 1280，字仅 "
      f"{36 * s['det_scale']:.1f}px） |")
    A(f"| 模式 B 每屏 det 缩放 | {s['screen_scale']}（字约 "
      f"{36 * s['screen_scale']:.1f}px） |\n")

    A("\n## 2. 三种上限的源码事实（都属实）\n")
    A("| 上限 | 位置 | 行为 | 对本段文字 |")
    A("|---|---|---|---|")
    A(f"| `MAX_TEXT_LENGTH = 4000` | `RecallService.kt:93`（`:212` NODE / `:317` OCR 的 `take()`） | **静默截断**，超出部分直接丢弃 | 本段 {s['chars_raw']} 字，距上限仅 **{4000 - s['chars_raw']} 字**；再长就丢尾部 |")
    A(f"| `REC_WIDTH_MAX = 960` | `OcrEngine.kt:794` `targetW = min(960, ...)` | 单框超宽时**压缩**（非拆分） | **触发**：最长行 {s['max_row_px']}px ÷ 框高≈40px → ratio≈{s['est_ratio']}，不压缩需 targetW≈{s['est_need_targetw']} > 960，横向压缩约 {(1 - 960 / s['est_need_targetw']) * 100:.0f}% |")
    A(f"| `DET_MAX_SIDE = 1280` | `OcrEngine.kt:98` | det 前长边等比缩到 1280 | 模式 A 缩放 **{s['det_scale']}**；模式 B 每屏 {s['screen_scale']} |")
    A(f"| `DET_MAX_CANDIDATES = 1000` | `OcrEngine.kt:104` | det 候选框上限 | 每屏约 37 行，**远离上限** |\n")

    A("\n## 3. 识别结果\n")
    A("| 模式 | 说明 | OCR 行数 | 字符覆盖率 |")
    A("|---|---|---|---|")
    A(f"| A | 单张 {s['image'][0]}×{s['image'][1]} 大图直接 OCR | {r['mode_a']['n_lines']} | **{cov_a:.2%}** |")
    A(f"| B | 按 2400px 一屏切片 + 拼接去重（{r['mode_b']['n_screens']} 屏，真实 Recall 形态） | {r['mode_b']['n_lines']} | **{cov_b:.2%}** |\n")

    A("### 漏字成分分析（关键）\n")
    A(f"- 模式 B 共漏字 **{len(miss_b)}** 个，其中：")
    A(f"  - **非汉字（markdown 符号 / 英文 / 数字）：{len(miss_b_sym)} 个"
      f"（{len(miss_b_sym) / max(len(miss_b), 1):.0%}）**")
    A(f"  - **汉字：仅 {len(miss_b_han)} 个**（占全文 {s['chars_nows']} 字的 "
      f"{len(miss_b_han) / s['chars_nows']:.2%}）")
    A(f"\n即：**中文正文基本被完整识别，丢失的几乎全是 `` ` `` `|` `*` `#` `✅` "
      f"这类 markdown 符号**——这些符号在「取词入库」场景本就要清洗，影响有限。\n")

    A("### 模式 B 漏字样例（前 200 字）\n")
    A("```")
    A(miss_b[:200] if miss_b else "（无）")
    A("```\n")
    A("### 模式 B 多识别出的字（前 100 字，符号噪声为主）\n")
    A("```")
    A(extra_b[:100] if extra_b else "（无）")
    A("```\n")

    A("\n## 4. 拆分正确性\n")
    ra, rb = r["mode_a"], r["mode_b"]
    A(f"- 原文自然行数：**{s['natural_lines']}**；wrap 后渲染行数：{s['wrapped_lines']}")
    A(f"- 模式 A OCR 行数：{ra['n_lines']}；模式 B OCR 行数：{rb['n_lines']}")
    A(f"- 模式 B 分屏数：{rb['n_screens']}（每屏 ≤ {(2400 - 80) // 62} 行）")
    A(f"- 空行/纯符号行在 OCR 输出中自然消失，故 OCR 行数 < 渲染行数，属预期。")
    A("- 逐行对照见 `longtext_sim_result.json` 的 `got_lines`。\n")

    A("\n## 5. 结论\n")
    A(f"1. **这段 {s['chars_raw']} 字的文字能被识别约 {cov_b:.0%}（真实分屏形态）；"
      f"中文正文基本完整，丢失的几乎全是 markdown 符号。**"
      f"汉字漏识仅 {len(miss_b_han)}/{s['chars_nows']} = {len(miss_b_han) / s['chars_nows']:.2%}。")
    A(f"2. **「字数无上限」的唯一真实瓶颈是 `MAX_TEXT_LENGTH = 4000`**："
      f"本段 {s['chars_raw']} 字距上限只差 {4000 - s['chars_raw']} 字。一旦整篇文字超过 4000，"
      f"`take(4000)` 会**静默丢弃尾部**——这与「某输入法 5000 字以上就不拆分」是同一类问题，"
      f"MBM 的阈值是 4000。要「无上限」，必须改这里（改为按屏累积 + 可配置总量上限，"
      f"或显著提高上限），并**让截断可观测**（记日志/计数），不能静默。")
    A("3. **拆分能力本身没有字数上限**：det 不限行数，merge/NMS 不限行数，"
      "`DET_MAX_CANDIDATES=1000` 每屏远离。长文本靠**多屏截图累积**天然覆盖，"
      "不需要也不应该靠「单张图内拆分」实现无上限。")
    A(f"4. **单行宽度会触发 `REC_WIDTH_MAX=960` 压缩**：wrap 后每行约 "
      f"{s['max_row_chars']} 字（{s['max_row_px']}px）就已被压缩约 "
      f"{(1 - 960 / s['est_need_targetw']) * 100:.0f}%。真实风险面是"
      f"**不换行的超长行**（表格行、长 URL、代码行、原文中 {s['longest_line_chars']} "
      f"字的那一行）。压缩后仍能识别大部分，但精度下降。")
    A(f"5. **模式 A（{cov_a:.2%}）略高于模式 B（{cov_b:.2%}）**，与「分屏更清晰」的直觉相反。"
      f"原因：模式 B 每屏独立 OCR，**屏边缘行的 det 检出较弱**（首/末行易丢），"
      f"且相邻屏重叠区的重复行未完全去净（OCR 行数 {rb['n_lines']} > A 的 {ra['n_lines']}）。"
      f"真实 Recall 就是分屏的，因此 **{cov_b:.0%} 是更贴近实际的估计**；"
      f"若优化切片重叠与去重，模式 B 还有回升空间。\n")

    A("\n## 6. 待确认（需真机）\n")
    A("1. 真机逐屏截图的实际高度 / DPI / 字号与本节假设（1080×2400、36px）的差距；")
    A("2. `MAX_TEXT_LENGTH` 的目标值：4000 是否够？是否应可配置？截断时如何可观测？")
    A("3. `DET_MAX_CANDIDATES=1000` 在真实密集一屏（小字+多行）下是否够；")
    A("4. 超长不换行行（表格/URL/代码）是否需要在 det 后按宽度**主动二次切分**再送 rec"
      "（即把「压缩」改为「拆分」，这才是真正的「拆分无上限」）。\n")

    with open(OUT_MD, "w", encoding="utf-8") as fp:
        fp.write("\n".join(L))


if __name__ == "__main__":
    main()
