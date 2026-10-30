# -*- coding: utf-8 -*-
"""
按 OcrEngine.kt 1:1 复刻的 PP-OCRv6(small) 管线（Python/ONNX Runtime）。
用于本地复现「云·战双帕弥什」识别问题。
"""
import math
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont

MODEL_DIR = "/root/.codebuddy/artifact/mbm-gitee/plugin/recall/src/main/assets/ocr"
DET_MAX_SIDE = 1280
DET_MIN_SIDE = 736
DET_THRESH = 0.15
DET_BOX_THRESH = 0.2
DET_UNCLIP_RATIO = 1.6
DET_MAX_CANDIDATES = 1000
REC_HEIGHT = 48
REC_WIDTH_MAX = 960
CLS_THRESH = 0.9

keys = [l.rstrip("\n") for l in open(f"{MODEL_DIR}/ppocrv6_dict.txt", encoding="utf-8")]

det_sess = ort.InferenceSession(f"{MODEL_DIR}/ch_PP-OCRv6_det_small.onnx", providers=["CPUExecutionProvider"])
rec_sess = ort.InferenceSession(f"{MODEL_DIR}/ch_PP-OCRv6_rec_small.onnx", providers=["CPUExecutionProvider"])
cls_sess = ort.InferenceSession(f"{MODEL_DIR}/ch_ppocr_mobile_v2.0_cls_infer.onnx", providers=["CPUExecutionProvider"])


def det_resize(img):
    """与 Kotlin detResize 一致：长边>1280 缩到 1280；短边<736 放大到 736；32 对齐。"""
    w, h = img.size
    long_side, short_side = max(w, h), min(w, h)
    if long_side > DET_MAX_SIDE:
        ratio = DET_MAX_SIDE / long_side
    elif short_side < DET_MIN_SIDE:
        ratio = DET_MIN_SIDE / short_side
    else:
        ratio = 1.0
    rw = max(32, int(round(w * ratio / 32)) * 32)
    rh = max(32, int(round(h * ratio / 32)) * 32)
    scaled = img.resize((rw, rh), Image.BILINEAR)
    return scaled, w / rw, h / rh


def chw_bgr_normalized(img):
    """BGR 平面布局 + 0.5/0.5 归一化，与 Kotlin chwBgrNormalized 一致。"""
    arr = np.asarray(img.convert("RGB"), dtype=np.float32)  # H,W,3 RGB
    b = arr[:, :, 2] / 255.0  # 图 B
    g = arr[:, :, 1] / 255.0
    r = arr[:, :, 0] / 255.0
    out = np.stack([(b - 0.5) / 0.5, (g - 0.5) / 0.5, (r - 0.5) / 0.5], axis=0)
    return np.ascontiguousarray(out)


def detect(img):
    scaled, ratioW, ratioH = det_resize(img)
    inp = chw_bgr_normalized(scaled)[None]  # 1,3,H,W
    out = det_sess.run(None, {"x": inp})[0][0, 0]  # H,W 概率图
    mapH, mapW = scaled.height, scaled.width
    prob = out
    if prob.shape != (mapH, mapW):
        # 模型输出与输入同分辨率（代码内注释如此声称）；若不同则告警
        print(f"  [det] WARNING 输出形状 {prob.shape} != 输入 {(mapH, mapW)}")
        prob = np.array(Image.fromarray(prob).resize((mapW, mapH), Image.BILINEAR))
    return db_post_process(prob, mapW, mapH, ratioW, ratioH)


def point_in_polygon(x, y, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi):
            inside = not inside
        j = i
    return inside


def convex_hull(points):
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def min_area_rect(hull):
    best_angle, best_area, best_box = 0.0, float("inf"), None
    n = len(hull)
    for i in range(n):
        a, b = hull[i], hull[(i + 1) % n]
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dx, dy)
        if length < 1e-9:
            continue
        angle = math.atan2(dy, dx)
        ca, sa = math.cos(-angle), math.sin(-angle)
        xs, ys = [], []
        for px, py in hull:
            rx = px * ca - py * sa
            ry = px * sa + py * ca
            xs.append(rx)
            ys.append(ry)
        minX, maxX, minY, maxY = min(xs), max(xs), min(ys), max(ys)
        area = (maxX - minX) * (maxY - minY)
        if area < best_area:
            best_area, best_angle, best_box = area, angle, (minX, maxX, minY, maxY)
    if best_box is None:
        return hull, 0.0, 0.0
    ca, sa = math.cos(best_angle), math.sin(best_angle)

    def rot_back(x, y):
        return (x * ca - y * sa, x * sa + y * ca)

    minX, maxX, minY, maxY = best_box
    corners = [rot_back(minX, minY), rot_back(maxX, minY), rot_back(maxX, maxY), rot_back(minX, maxY)]
    corners = order_points(corners)
    short_side = min(maxX - minX, maxY - minY)
    return corners, best_area, short_side


def order_points(points):
    s = sorted(points, key=lambda p: (p[1], p[0]))
    top = sorted(s[:2], key=lambda p: p[0])
    bottom = sorted(s[2:], key=lambda p: p[0])
    return [top[0], top[1], bottom[1], bottom[0]]


def box_score(prob, w, h, corners):
    xs = [p[0] for p in corners]
    ys = [p[1] for p in corners]
    minX, maxX = max(0, int(min(xs))), min(w - 1, int(max(xs)))
    minY, maxY = max(0, int(min(ys))), min(h - 1, int(max(ys)))
    if minX > maxX or minY > maxY:
        return 0.0
    total, count = 0.0, 0
    for y in range(minY, maxY + 1):
        for x in range(minX, maxX + 1):
            if point_in_polygon(x, y, corners):
                total += prob[y * w + x]
                count += 1
    return total / count if count else 0.0


def unclip(corners, area):
    def dist(a, b):
        return math.hypot(a[0] - b[0], a[1] - b[1])

    perimeter = sum(dist(corners[i], corners[(i + 1) % 4]) for i in range(4))
    if perimeter < 1e-6:
        return corners
    d = area * DET_UNCLIP_RATIO / perimeter / 2.0
    cx = sum(p[0] for p in corners) / 4.0
    cy = sum(p[1] for p in corners) / 4.0
    out = []
    for x, y in corners:
        vx, vy = x - cx, y - cy
        norm = math.hypot(vx, vy)
        if norm < 1e-6:
            out.append((x, y))
        else:
            out.append((x + vx / norm * d, y + vy / norm * d))
    return out


def db_post_process(prob, mapW, mapH, ratioW, ratioH):
    # 1) 二值化
    mask = prob > DET_THRESH
    # 2) 2x2 膨胀
    dilated = mask.copy()
    ys, xs = np.where(mask)
    for y, x in zip(ys, xs):
        for dy in (0, 1):
            for dx in (0, 1):
                ny, nx = y + dy, x + dx
                if ny < mapH and nx < mapW:
                    dilated[ny, nx] = True
    # 3) 连通域
    visited = np.zeros_like(dilated, dtype=bool)
    result = []
    from collections import deque
    for y0 in range(mapH):
        for x0 in range(mapW):
            if not dilated[y0, x0] or visited[y0, x0]:
                continue
            q = deque([(y0, x0)])
            visited[y0, x0] = True
            pts = []
            while q:
                cy, cx = q.popleft()
                pts.append((cx, cy))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        if dx == 0 and dy == 0:
                            continue
                        ny, nx = cy + dy, cx + dx
                        if 0 <= nx < mapW and 0 <= ny < mapH and dilated[ny, nx] and not visited[ny, nx]:
                            visited[ny, nx] = True
                            q.append((ny, nx))
            if len(pts) < 8:
                continue
            if len(result) >= DET_MAX_CANDIDATES:
                break
            hull = convex_hull(pts)
            if len(hull) < 3:
                continue
            rect_corners, area, short_side = min_area_rect(hull)
            if short_side < 3:
                continue
            score = box_score(prob.flatten(), mapW, mapH, rect_corners)
            if score < DET_BOX_THRESH:
                continue
            expanded = unclip(rect_corners, area)
            quad = [(x * ratioW, y * ratioH) for x, y in expanded]
            result.append(quad)
    return result


def should_rotate(crop):
    resized = crop.resize((192, 48), Image.BILINEAR)
    arr = np.asarray(resized.convert("RGB"), dtype=np.float32) / 255.0
    b, g, r = arr[:, :, 2], arr[:, :, 1], arr[:, :, 0]
    inp = np.stack([(b - .5) / .5, (g - .5) / .5, (r - .5) / .5], 0)[None]
    probs = cls_sess.run(None, {"x": inp})[0][0]
    return bool(probs[1] > CLS_THRESH and probs[1] > probs[0])


def warp_crop(src, quad):
    """四点透视采样到矩形（双线性），与 Kotlin warpCrop 一致。"""
    pts = quad
    w0 = abs(pts[1][0] - pts[0][0]) + abs(pts[2][0] - pts[3][0])
    h0 = abs(pts[3][1] - pts[0][1]) + abs(pts[2][1] - pts[1][1])
    w = max(8, min(int(w0), src.width))
    h = max(8, min(int(h0), src.height))
    src_arr = np.asarray(src.convert("RGB"), dtype=np.float32)
    out = np.zeros((h, w, 3), dtype=np.float32)
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
            # v0.001.4 Fix-C：越界改 clamp（对齐 Kotlin OcrEngine.kt:957-960 的
            # coerceIn(0, srcW-1)），不再用 % 取模回绕——取模会在右/下边缘把
            # x0 回绕到 0，引入边缘伪影。注意：本函数被 fix_pipeline 的
            # warp_crop_fixed（本就 clamp）遮蔽，此改动仅统一源函数口径、
            # 对走 fix_pipeline 的 30 场景零行为影响。
            x0 = min(max(int(fx), 0), src.width - 1)
            y0 = min(max(int(fy), 0), src.height - 1)
            x1 = min(x0 + 1, src.width - 1)
            y1 = min(y0 + 1, src.height - 1)
            ax = min(max(fx - int(fx), 0.0), 1.0)
            ay = min(max(fy - int(fy), 0.0), 1.0)
            c00 = src_arr[y0, x0]
            c10 = src_arr[y0, x1]
            c01 = src_arr[y1, x0]
            c11 = src_arr[y1, x1]
            out[v, u] = (c00 * (1 - ax) * (1 - ay) + c10 * ax * (1 - ay)
                         + c01 * (1 - ax) * ay + c11 * ax * ay)
    return Image.fromarray(out.astype(np.uint8))


def ctc_decode(buf, time, classes):
    sb = []
    prev = -1
    score_sum, count = 0.0, 0
    for t in range(time):
        row = buf[t * classes:(t + 1) * classes]
        best = int(np.argmax(row))
        val = float(row[best])
        if best != 0 and best != prev:
            sb.append(best)
            score_sum += val
            count += 1
        prev = best
    text = ""
    for idx in sb:
        if 1 <= idx <= len(keys):
            text += keys[idx - 1]
        elif idx == len(keys) + 1:
            text += " "
    return text, (score_sum / count if count else 0.0)


def recognize_crop(crop):
    if should_rotate(crop):
        crop = crop.transpose(Image.ROTATE_180)
    ratio = crop.width / crop.height
    targetW = min(REC_WIDTH_MAX, max(8, math.ceil(REC_HEIGHT * ratio)))
    resized = crop.resize((targetW, REC_HEIGHT), Image.BILINEAR)
    arr = np.asarray(resized.convert("RGB"), dtype=np.float32) / 255.0
    b, g, r = arr[:, :, 2].flatten(), arr[:, :, 1].flatten(), arr[:, :, 0].flatten()
    plane = REC_HEIGHT * targetW
    inp = np.concatenate([
        ((b - .5) / .5).reshape(1, -1),
        ((g - .5) / .5).reshape(1, -1),
        ((r - .5) / .5).reshape(1, -1),
    ]).reshape(1, 3, REC_HEIGHT, targetW)
    out = rec_sess.run(None, {"x": inp})[0]
    time, classes = out.shape[1], out.shape[2]
    return ctc_decode(out.flatten(), time, classes)


def recognize(img, use_detector=True):
    if use_detector:
        boxes = detect(img)
        boxes.sort(key=lambda q: (sum(p[1] for p in q) / 4, sum(p[0] for p in q) / 4))
        lines = []
        for quad in boxes:
            crop = warp_crop(img, quad)
            r = recognize_crop(crop)
            if r:
                lines.append((r[0], r[1], quad))
        return lines
    else:
        r = recognize_crop(img)
        return [(r[0], r[1], None)] if r else []
