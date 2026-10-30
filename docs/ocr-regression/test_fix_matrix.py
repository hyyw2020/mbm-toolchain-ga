# -*- coding: utf-8 -*-
"""修复后验证矩阵：间隙扫描 / 倾角扫描 / 倾角×间隙组合（含修复前对照）。

补丁与已落地 OcrEngine.kt 的两处修复一致：
  1. order_points  -> 绕质心极角排序 + min(x+y) 起点
  2. warp_crop     -> 欧氏边长（而非轴对齐 x/y 差值）
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image, ImageDraw, ImageFont

import ppocr_pipeline as P

FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
]
FONT = next((f for f in FONT_CANDIDATES if os.path.exists(f)), None)
if FONT is None:
    raise SystemExit("未找到中文字体，请把任一 CJK 字体路径加入 FONT_CANDIDATES")


# ---------- 与 Kotlin 修复等价的 Python 实现 ----------
def order_points_fixed(points):
    cx = sum(p[0] for p in points) / len(points)
    cy = sum(p[1] for p in points) / len(points)
    s = sorted(points, key=lambda p: math.atan2(p[1] - cy, p[0] - cx))
    start = min(range(len(s)), key=lambda i: s[i][0] + s[i][1])
    return [s[(start + i) % len(s)] for i in range(len(s))]


def warp_crop_fixed(src, quad):
    pts = quad

    def dist(a, b):
        return math.hypot(a[0] - b[0], a[1] - b[1])

    w0 = dist(pts[0], pts[1]) + dist(pts[3], pts[2])  # 上边 + 下边
    h0 = dist(pts[0], pts[3]) + dist(pts[1], pts[2])  # 左边 + 右边
    w = max(8, min(int(w0), src.width))
    h = max(8, min(int(h0), src.height))
    out = np.zeros((h, w, 3), dtype=np.float32)
    srcW, srcH = src.width, src.height
    srcArr = np.asarray(src, dtype=np.float32)
    for v in range(h):
        tv = v / max(1, h - 1)
        leftX = pts[0][0] + (pts[3][0] - pts[0][0]) * tv
        leftY = pts[0][1] + (pts[3][1] - pts[0][1]) * tv
        rightX = pts[1][0] + (pts[2][0] - pts[1][0]) * tv
        rightY = pts[1][1] + (pts[2][1] - pts[1][1]) * tv
        for u in range(w):
            tu = u / max(1, w - 1)
            fx = leftX + (rightX - leftX) * tu
            fy = leftY + (rightY - leftY) * tu
            x0 = min(max(int(fx), 0), srcW - 1)
            y0 = min(max(int(fy), 0), srcH - 1)
            x1 = min(x0 + 1, srcW - 1)
            y1 = min(y0 + 1, srcH - 1)
            ax = min(max(fx - x0, 0.0), 1.0)
            ay = min(max(fy - y0, 0.0), 1.0)
            top = srcArr[y0, x0] * (1 - ax) + srcArr[y0, x1] * ax
            bot = srcArr[y1, x0] * (1 - ax) + srcArr[y1, x1] * ax
            out[v, u] = top * (1 - ay) + bot * ay
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGB")


def apply_fix():
    P.order_points = order_points_fixed
    P.warp_crop = warp_crop_fixed


def revert_fix():
    P.order_points = ORIG_ORDER
    P.warp_crop = ORIG_WARP


ORIG_ORDER = P.order_points
ORIG_WARP = P.warp_crop


# ---------- 图像构造 ----------
def build(gap_after_yun, gap_after_dot, font_size=96, bg=(20, 22, 30), fg=(240, 240, 245)):
    img = Image.new("RGB", (1400, 320), bg)
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, font_size)
    x = 200
    for t, gap in [("云", gap_after_yun), ("·", gap_after_dot), ("战双帕弥什", 0)]:
        d.text((x, 100), t, font=f, fill=fg)
        bb = d.textbbox((x, 100), t, font=f)
        x = bb[2] + gap
    return img


def rotate(img, ang):
    return img.rotate(ang, resample=Image.BILINEAR, expand=False, fillcolor=(20, 22, 30))


def show(lines):
    return [t for t, s, q in lines], [round(s, 2) for t, s, q in lines]


TARGET = "云·战双帕弥什"

print("== A. 修复后：间隙扫描（font=96，字高约 85px）==")
apply_fix()
for g in [0, 20, 40, 60, 80]:
    lines = P.recognize(build(g, g), True)
    texts, scores = show(lines)
    joined = "".join(texts).replace(" ", "")
    hit = TARGET in joined
    print(f"  间隙={g:3d}px ({g/85:.2f}x字高): {len(lines)} 框 -> {texts}  scores={scores}  {'[命中]' if hit else ''}")

print("\n== B. 修复后：倾角扫描（无间隙）==")
for ang in [-20, -15, -12, -10, -8, -6, 0, 6, 8, 10, 12, 15, 20]:
    base = build(0, 0)
    lines = P.recognize(rotate(base, ang), True)
    texts, scores = show(lines)
    joined = "".join(texts).replace(" ", "")
    hit = TARGET in joined
    print(f"  倾角={ang:+3d}deg: {len(lines)} 框 -> {texts}  scores={scores}  {'[命中]' if hit else ''}")

print("\n== C. 修复后：倾角 x 间隙组合 ==")
for ang, g in [(-10, 30), (-12, 40), (10, 40), (-8, 60), (-6, 20), (8, 20)]:
    lines = P.recognize(rotate(build(g, g), ang), True)
    texts, scores = show(lines)
    joined = "".join(texts).replace(" ", "")
    hit = TARGET in joined
    print(f"  倾角={ang:+d}deg 间隙={g}px: {len(lines)} 框 -> {texts}  scores={scores}  {'[命中]' if hit else ''}")

print("\n== D. 修复前对照：同样组合 ==")
revert_fix()
for ang, g in [(-10, 30), (-12, 40), (10, 40), (-8, 60), (-6, 20), (8, 20)]:
    lines = P.recognize(rotate(build(g, g), ang), True)
    texts, scores = show(lines)
    joined = "".join(texts).replace(" ", "")
    hit = TARGET in joined
    print(f"  倾角={ang:+d}deg 间隙={g}px: {len(lines)} 框 -> {texts}  scores={scores}  {'[命中]' if hit else ''}")
