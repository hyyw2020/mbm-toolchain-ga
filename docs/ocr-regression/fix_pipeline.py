# -*- coding: utf-8 -*-
"""完整修复版管线（order_points + warp_crop + merge_same_row 三修复合一）。

与 Kotlin OcrEngine.kt v1.0.28 保持一致；未打补丁的原始逻辑见 ppocr_pipeline.py。
供同源 bug 扫描复用：所有场景统一走本模块，确保「修复后基线」一致。
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
from PIL import Image

import ppocr_pipeline as P

# ---------------- 修复 1：order_points 极角排序 ----------------

_orig_order_points = P.order_points


def order_points_fixed(points):
    cx = sum(p[0] for p in points) / len(points)
    cy = sum(p[1] for p in points) / len(points)
    s = sorted(points, key=lambda p: math.atan2(p[1] - cy, p[0] - cx))
    start = min(range(len(s)), key=lambda i: s[i][0] + s[i][1])
    return [s[(start + i) % len(s)] for i in range(len(s))]


# ---------------- 修复 2：warp_crop 欧氏边长 ----------------

_orig_warp_crop = P.warp_crop


def warp_crop_fixed(src, quad):
    pts = quad

    def dist(a, b):
        return math.hypot(a[0] - b[0], a[1] - b[1])

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


# ---------------- 修复 3：同行框合并 ----------------

def _quad_center(q):
    return (sum(p[0] for p in q) / 4.0, sum(p[1] for p in q) / 4.0)


def _quad_size(q):
    w = (math.hypot(q[1][0] - q[0][0], q[1][1] - q[0][1])
         + math.hypot(q[2][0] - q[3][0], q[2][1] - q[3][1])) / 2
    h = (math.hypot(q[3][0] - q[0][0], q[3][1] - q[0][1])
         + math.hypot(q[2][0] - q[1][0], q[2][1] - q[1][1])) / 2
    return w, h


def try_merge_same_row(a, b):
    ca, cb = _quad_center(a), _quad_center(b)
    wa, ha = _quad_size(a)
    wb, hb = _quad_size(b)
    dx = cb[0] - ca[0]
    dy = cb[1] - ca[1]
    dist = math.hypot(dx, dy)
    if dist < 1e-6:
        return None
    if abs(dy) / dist > 0.707:
        return None
    # 垂直投影重叠率（旋转鲁棒）：以 A 的文字方向为基准轴投影
    # 同一行拆开的框（词内宽间隙）重叠率 ~1.0；紧凑双行（行距 <=0.65x 字高）
    # 重叠率 <0.35，不会误合并
    ax = a[1][0] - a[0][0]
    ay = a[1][1] - a[0][1]
    alen = math.hypot(ax, ay)
    if alen < 1e-6:
        return None
    vx, vy = -ay / alen, ax / alen  # A 的文字垂直方向
    pa = [p[0] * vx + p[1] * vy for p in a]
    pb = [p[0] * vx + p[1] * vy for p in b]
    ov = min(max(pa), max(pb)) - max(min(pa), min(pb))
    hmin = min(max(pa) - min(pa), max(pb) - min(pb))
    if hmin < 1e-6 or ov / hmin < 0.8:
        return None
    gap = dist - (wa + wb) / 2.0
    if gap < 0.6 * max(ha, hb):
        return list(a) + list(b)
    return None


def merge_same_row_boxes(boxes):
    merged = [list(q) for q in boxes]
    changed = True
    while changed:
        changed = False
        i = 0
        while i < len(merged) - 1:
            j = i + 1
            while j < len(merged):
                m = try_merge_same_row(merged[i], merged[j])
                if m is not None:
                    hull = P.convex_hull([(float(x), float(y)) for x, y in m])
                    rect, area, short = P.min_area_rect(hull)
                    merged[i] = list(rect)
                    merged.pop(j)
                    changed = True
                else:
                    j += 1
            i += 1
    return merged


# ---------------- 修复 4：det 后 NMS 去重（解决「拆分」重叠框） ----------------
# DB 在文字密集处常对一行文本输出多个相互重叠的连通域框，若不抑制会
# 导致重复行 / 拼接乱码。标准 IoU-NMS：按面积降序，重叠度过高的低优先框被抑制。
# 凸四边形可用 Sutherland-Hodgman 精确求交（无需 shapely 等依赖）。

def _poly_area(poly):
    s = 0.0
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2.0


def _clip_polygon(subject, clip):
    """Sutherland-Hodgman：用凸多边形 clip 裁剪 subject（均为凸四边形时精确）。"""
    def inside(p, a, b):
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0
    def intersect(p1, p2, a, b):
        dcx, dcy = a[0] - b[0], a[1] - b[1]
        dpx, dpy = p1[0] - p2[0], p1[1] - p2[1]
        n1 = a[0] * b[1] - a[1] * b[0]
        n2 = p1[0] * p2[1] - p1[1] * p2[0]
        den = dcx * dpy - dcy * dpx
        if abs(den) < 1e-9:
            return p1
        return ((n1 * dpx - n2 * dcx) / den, (n1 * dpy - n2 * dcy) / den)
    out = list(subject)
    n = len(clip)
    for i in range(n):
        a, b = clip[i], clip[(i + 1) % n]
        inp = out
        out = []
        if not inp:
            break
        s = inp[-1]
        for p in inp:
            if inside(p, a, b):
                if not inside(s, a, b):
                    out.append(intersect(s, p, a, b))
                out.append(p)
            elif inside(s, a, b):
                out.append(intersect(s, p, a, b))
            s = p
    return out


def _poly_iou(a, b):
    inter = _clip_polygon(a, b)
    if len(inter) < 3:
        return 0.0
    ai = _poly_area(inter)
    if ai <= 0:
        return 0.0
    union = _poly_area(a) + _poly_area(b) - ai
    return ai / union if union > 0 else 0.0


def nms_boxes_counted(boxes, iou_thresh=0.5):
    """按面积降序做 IoU-NMS；返回 (保留框列表, 被抑制框数)。

    nms_boxes 的计数版，供 A/B 顺序实验统计「NMS 实际抑制了多少框」——
    这是判断「现状顺序下 NMS 是否空转」的直接证据。
    """
    order = sorted(range(len(boxes)), key=lambda i: -_poly_area(boxes[i]))
    keep = []
    suppressed = [False] * len(boxes)
    n_sup = 0
    for i in order:
        if suppressed[i]:
            continue
        keep.append(boxes[i])
        for j in order:
            if j == i or suppressed[j]:
                continue
            if _poly_iou(boxes[i], boxes[j]) > iou_thresh:
                suppressed[j] = True
                n_sup += 1
    return keep, n_sup


def nms_boxes(boxes, iou_thresh=0.5):
    """按面积降序做 IoU-NMS；返回保留的框（顺序重排）。"""
    keep, _ = nms_boxes_counted(boxes, iou_thresh)
    return keep


# ---------------- 修复 5：置信度门控的多旋转回退（泛化旋转处理） ----------------
# 旧逻辑仅 cls 纠 180° + 竖排特例；cls 偶发判错或文本呈 90°/270° 任意朝向时
# 会漏识。改为四向取最高分（0/90/180/270），见 recognize_crop_robust。
# ⚠️ 不做「base 高置信即采信」短路——模型会「自信地错」，必须靠 max 选对。

def recognize_crop_robust(crop):
    # base 已含 cls 纠 180°（即 0/180 两向取优）。cls 无法纠正 90°/270°，
    # 故再补 rot90 / rot270 两向，四向（0/90/180/270）取最高分。
    # ⚠️ 不做「base 高置信即采信」的短路：模型会「自信地错」（实测某 90°
    # 文本 base 给「原神」0.9998，而正确朝向 rot270 仅 1.000，必须靠 max
    # 而非阈值来选对）。正确朝向通常置信最高，max 选择稳健。
    base = P.recognize_crop(crop)
    cands = [base] if base else []
    for rot in (Image.ROTATE_90, Image.ROTATE_270):
        r = P.recognize_crop(crop.transpose(rot))
        if r:
            cands.append(r)
    if not cands:
        return None
    cands.sort(key=lambda x: -x[1])
    return cands[0]


def recognize_crop_vertical(crop):
    """v1.0.29 竖排支持 + v1.0.30 置信门控多旋转。"""
    if crop.height > crop.width * 2:
        # 竖排：双向 rot90 取高分（cls 只纠 180°，竖排是盲区）
        best = ("", -1.0)
        for rot in (Image.ROTATE_90, Image.ROTATE_270):
            t, s = P.recognize_crop(crop.transpose(rot))
            if s > best[1]:
                best = (t, s)
        return best if best[0] else None
    return recognize_crop_robust(crop)


# ---------------- 修复 6：IME 闭环的术语词典后校正（本项目独有优势） ----------------
# OCR 直接喂给 fcitx5 中文输入法。利用「字符级编辑距离」对游戏/UI 术语做离线
# 校正：OCR 错多为形近（如「战双帕弥件」→「战双帕弥什」，仅 1 字之差），
# 拼音未必相近，故以字符而非拼音度量。仅对中等置信（0.5~0.97）结果触发，
# 且仅当与某术语的字符距离很小（>= 半数字符一致）才替换，避免误伤通用文本。

LEXICON = [
    "战双帕弥什", "云·战双帕弥什", "明日方舟", "原神", "崩坏", "崩坏3",
    "碧蓝航线", "少女前线", "阴阳师", "王者荣耀", "和平精英", "第五人格",
    "光遇", "光·遇", "星际公民", "命运冠位指定", "碧蓝幻想", "游戏王",
    "英雄联盟", "绝区零", "鸣潮", "蛋仔派对", "永劫无间", "逆水寒",
]


def _char_edit_distance(a, b):
    if a == b:
        return 0
    la, lb = len(a), len(b)
    dp = list(range(lb + 1))
    for i in range(1, la + 1):
        prev = dp[0]
        dp[0] = i
        for j in range(1, lb + 1):
            cur = dp[j]
            cost = 0 if a[i - 1] == b[j - 1] else 1
            dp[j] = min(dp[j] + 1, dp[j - 1] + 1, prev + cost)
            prev = cur
    return dp[lb]


def correct_with_lexicon(text, score):
    # 触发条件：rec 至少输出了连贯内容（score>=0.4）。
    # ⚠️ 不做「高置信跳过」——模型「自信地错」（如「战双帕弥件」score 0.999）
    # 恰是最该校正的情况。安全由「长度差+字符距离」双重约束保证：
    # 仅当与某术语长度差 ≤1 且字符距离 ≤2 时才替换，避免「方舟→明日方舟」
    # 这类短词误扩、以及长句偶然贴近术语的误伤。
    if not text or score < 0.4:
        return text
    best_term, best_d = None, 999
    for term in LEXICON:
        d = _char_edit_distance(text, term)
        if d < best_d:
            best_d, best_term = d, term
    # 安全约束（防「战斗」被近形 2 字词「原神」吞掉）：
    #  - 错误数随长度缩放（每 ~3 字容 1 错）：best_d <= max(1, len//3)
    #  - 至少半数字符一致：best_d * 2 <= len
    #  - 长度差 <= 1（防「方舟」误扩为「明日方舟」）
    if (best_term and best_term != text
            and best_d <= max(1, len(text) // 3)
            and best_d * 2 <= len(text)
            and abs(len(text) - len(best_term)) <= 1):
        return best_term
    return text


# ---------------- det→(NMS/合并) 顺序开关（A/B 实验用，Kotlin 暂不动） ----------------
#  "merge_nms"(a, 现状=Kotlin v1.0.30 顺序): detect → 同行合并 → NMS
#  "nms_merge"(b, 候选1):                     detect → NMS → 同行合并
_ORDER = "merge_nms"
LAST_NMS_SUPPRESSED = 0


def recognize_detailed(img, order=None):
    """返回 (lines, nms_suppressed)，order 见 _ORDER 注释。

    用于 A/B 实验：同一张图在两种 det→NMS/合并顺序下比对 pass/fail 与
    NMS 实际抑制框数。默认 order=None → 走 _ORDER（"merge_nms"，与
    Kotlin v1.0.30 现状一致，保证默认回归不变）。
    """
    global LAST_NMS_SUPPRESSED
    order = order or _ORDER
    det = P.detect(img)
    if order == "nms_merge":
        kept, n_sup = nms_boxes_counted(det)
        boxes = merge_same_row_boxes(kept)
    else:  # "merge_nms"（现状）
        merged = merge_same_row_boxes(det)
        boxes, n_sup = nms_boxes_counted(merged)
    LAST_NMS_SUPPRESSED = n_sup
    boxes.sort(key=lambda q: (sum(p[1] for p in q) / 4, sum(p[0] for p in q) / 4))
    lines = []
    for quad in boxes:
        crop = warp_crop_fixed(img, quad)
        r = recognize_crop_vertical(crop)
        if r:
            text = correct_with_lexicon(r[0], r[1])
            score = r[1] if text == r[0] else max(r[1], 0.9)
            lines.append((text, score, quad))
    return lines, n_sup


def recognize(img, order=None):
    """完整修复版 recognize：det → NMS → 同行合并 → 排序 → rec(门控旋转) → 词典校正。

    order 默认 None → 走 _ORDER（"merge_nms"，与 Kotlin v1.0.30 一致）。
    """
    lines, _ = recognize_detailed(img, order)
    return lines


def recognize_original(img):
    """未修复基线（用于对照）。"""
    return P.recognize(img, True)


# 打补丁，保证 min_area_rect 内部走极角排序
P.order_points = order_points_fixed
P.warp_crop = warp_crop_fixed
