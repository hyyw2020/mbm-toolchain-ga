# -*- coding: utf-8 -*-
"""验证 det 输出层的保守同行合并：宽间隙拆框 -> 合并回单框。

合并判据（对旋转框，用欧氏几何而非轴对齐）：
  1. 垂直方向：两框中心连线与水平轴夹角 < 45deg（即基本同一行）
  2. 垂直间距：中心 y 方向投影距离 < 0.6 x max(h1, h2)
  3. 水平间隙：中心 x 方向投影距离 - (w1+w2)/2 < 0.6 x max(h1, h2)
     （间隙小于 0.6 个字高，正常行间距通常 >= 1 个字高，不会误合并）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
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


def quad_center(q):
    return (sum(p[0] for p in q) / 4.0, sum(p[1] for p in q) / 4.0)


def quad_size(q):
    import math
    w = (math.hypot(q[1][0] - q[0][0], q[1][1] - q[0][1])
         + math.hypot(q[2][0] - q[3][0], q[2][1] - q[3][1])) / 2
    h = (math.hypot(q[3][0] - q[0][0], q[3][1] - q[0][1])
         + math.hypot(q[2][0] - q[1][0], q[2][1] - q[1][1])) / 2
    return w, h


def try_merge(a, b):
    """a, b: quad 四点（顺序 tl,tr,br,bl）。同一行且间隙小则合并，否则 None。"""
    ca, cb = quad_center(a), quad_center(b)
    wa, ha = quad_size(a)
    wb, hb = quad_size(b)
    dx = cb[0] - ca[0]
    dy = cb[1] - ca[1]
    import math
    dist = math.hypot(dx, dy)
    if dist < 1e-6:
        return None
    # 条件 1：中心连线接近水平（夹角 < 45deg）
    if abs(dy) / dist > 0.707:
        return None
    # 条件 2+3：中心距离 - 半宽和 < 0.6 x max(字高)
    hmax = max(ha, hb)
    gap = dist - (wa + wb) / 2.0
    if gap < 0.6 * hmax:
        return list(a) + list(b)  # 8 点并集，交由后续凸包/最小矩形处理
    return None


def merge_quads(quads):
    """迭代合并；返回合并后的 quad 列表。"""
    quads = [list(q) for q in quads]
    changed = True
    while changed:
        changed = False
        outer = 0
        while outer < len(quads) - 1:
            inner = outer + 1
            while inner < len(quads):
                merged = try_merge(quads[outer], quads[inner])
                if merged is not None:
                    quads[outer] = merged
                    quads.pop(inner)
                    changed = True
                else:
                    inner += 1
            outer += 1
    return quads


def recognize_with_merge(img):
    """det 输出先过同行合并，再逐框识别。"""
    boxes = P.detect(img)
    merged = [list(q) for q in boxes]
    changed = True
    while changed:
        changed = False
        i = 0
        while i < len(merged) - 1:
            j = i + 1
            while j < len(merged):
                m = try_merge(merged[i], merged[j])
                if m is not None:
                    hull = P.convex_hull([(float(x), float(y)) for x, y in m])
                    rect, area, short = P.min_area_rect(hull)
                    merged[i] = list(rect)
                    merged.pop(j)
                    changed = True
                else:
                    j += 1
            i += 1
    # 与 recognize 一致：按阅读顺序（中心 y, x）排序
    merged.sort(key=lambda q: (sum(p[1] for p in q) / 4, sum(p[0] for p in q) / 4))
    out = []
    for q in merged:
        crop = P.warp_crop(img, q)
        text, score = P.recognize_crop(crop)
        if text:
            out.append((text, score, q))
    return out


# 打修复补丁
import math


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

    import numpy as np
    w0 = dist(pts[0], pts[1]) + dist(pts[3], pts[2])
    h0 = dist(pts[0], pts[3]) + dist(pts[1], pts[2])
    w = max(8, min(int(w0), src.width))
    h = max(8, min(int(h0), src.height))
    out = np.zeros((h, w, 3), dtype=np.float32)
    srcW, srcH = src.width, src.height
    srcArr = np.asarray(src, dtype=np.float32)
    for v in range(h):
        tv = v / max(1, h - 1)
        lx = pts[0][0] + (pts[3][0] - pts[0][0]) * tv
        ly = pts[0][1] + (pts[3][1] - pts[0][1]) * tv
        rx = pts[1][0] + (pts[2][0] - pts[1][0]) * tv
        ry = pts[1][1] + (pts[2][1] - pts[1][1]) * tv
        for u in range(w):
            tu = u / max(1, w - 1)
            fx = lx + (rx - lx) * tu
            fy = ly + (ry - ly) * tu
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


P.order_points = order_points_fixed
P.warp_crop = warp_crop_fixed


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


TARGET = "云·战双帕弥什"

print("== 1. 宽间隙 + 同行合并 ==")
for g in [0, 40, 60, 80, 120, 160]:
    img = build(g, g)
    raw = P.recognize(img, True)
    merged = recognize_with_merge(img)
    raw_t = [t for t, s, q in raw]
    mrg_t = [t for t, s, q in merged]
    hit = TARGET in "".join(mrg_t).replace(" ", "")
    print(f"  间隙={g:3d}px: 原始 {len(raw)} 框 {raw_t}")
    print(f"           合并 {len(merged)} 框 {mrg_t}  {'[命中]' if hit else ''}")

print("\n== 2. 误合并风险：双行菜单文字（不应被合并）==")
img = Image.new("RGB", (1400, 700), (20, 22, 30))
d = ImageDraw.Draw(img)
f = ImageFont.truetype(FONT, 48)
d.text((100, 80), "账号管理", font=f, fill=(240, 240, 245))
d.text((100, 200), "消息通知", font=f, fill=(240, 240, 245))
d.text((100, 320), "隐私设置", font=f, fill=(240, 240, 245))
d.text((100, 440), "关于我们", font=f, fill=(240, 240, 245))
raw = P.recognize(img, True)
merged = recognize_with_merge(img)
print(f"  原始 {len(raw)} 框 -> {[t for t, s, q in raw]}")
print(f"  合并 {len(merged)} 框 -> {[t for t, s, q in merged]}")

print("\n== 3. 同行多词（真实句子，同行相邻词本就不拆，验证不退化）==")
img = Image.new("RGB", (1400, 320), (255, 255, 255))
d = ImageDraw.Draw(img)
d.text((100, 100), "欢迎来到帕弥什世界", font=ImageFont.truetype(FONT, 96), fill=(30, 30, 30))
merged = recognize_with_merge(img)
print(f"  合并 {len(merged)} 框 -> {[t for t, s, q in merged]}")
