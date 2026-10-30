# -*- coding: utf-8 -*-
"""图标场景夹具（Icon Fixtures）—— 新增缺陷类「图标误检+误识」的回归资产。

⚠️ 诚实声明（重要）：
  本文件所有 PNG 均为 **PIL 合成图**，不是真机截图。沙箱无 Android 设备，
  无法取得 MBM 设置页真实截图。合成目的是：
    1. 在拿到真实截图前，先把「图标场景」纳入回归集（此前 30 场景零覆盖）；
    2. 跑出 baseline：图标框的 score / 几何 / 是否参与 merge 分布。
  **合成图 ≠ 真实截图**：图标的形状、线宽、颜色、抗锯齿与 Material 矢量图标
  有差距，baseline 的绝对数值可能偏离真机。真机截图到手后必须重跑校准。

用户提供的真实样本（非合成）：见同目录 `icon_real_samples.md`
（2026-10-25 用户手机截图 OCR 输出，含状态栏噪声「0N谷100」）。

运行：python3 icon_fixtures.py  → 在 fixtures/ 下生成 PNG + manifest.json
"""
import json
import math
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "fixtures", "icons")
FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
]
FONT = next((f for f in FONT_CANDIDATES if os.path.exists(f)), None)
assert FONT, "未找到中文字体"

# MBM 设置页为深色主题
BG = (18, 20, 26)
FG = (232, 234, 240)
ICON = (122, 134, 153)      # Android 设置单色 tint 灰
ICON_ACCENT = (91, 157, 255)
SUB = (154, 163, 175)

FS = 46          # 菜单正文字号
ICON_BOX = 52    # 图标边长
ROW_H = 116      # 行高
X_ICON = 64
X_TEXT = 140


def font(sz=FS):
    return ImageFont.truetype(FONT, sz)


def tw(d, s, f):
    bb = d.textbbox((0, 0), s, font=f)
    return bb[2] - bb[0], bb[3] - bb[1]


# ---------------- 单色图标（近似 Material 轮廓风格，实心更易被 det 检出） ----------------

def ic_gear(d, x, y, s, c=ICON):
    """齿轮：外圆 + 8 齿 + 中心孔"""
    cx, cy = x + s / 2, y + s / 2
    r = s * 0.30
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)
    for k in range(8):
        a = k * math.pi / 4
        tw_, th = s * 0.16, s * 0.20
        px, py = cx + math.cos(a) * r * 1.15, cy + math.sin(a) * r * 1.15
        d.rectangle([px - tw_ / 2, py - th / 2, px + tw_ / 2, py + th / 2], fill=c)
    hr = s * 0.11
    d.ellipse([cx - hr, cy - hr, cx + hr, cy + hr], fill=BG)


def ic_round_rect(d, x, y, w, h, c=ICON, r=0.22, fill=None):
    """圆角矩形；r 为圆角半径占 h 的比例。fill 非空时实心，否则描边。"""
    d.rounded_rectangle([x, y, x + w, y + h], radius=int(h * r),
                        outline=None if fill is not None else c,
                        fill=fill, width=max(2, int(h * 0.09)))


def ic_keyboard(d, x, y, s, c=ICON):
    ic_round_rect(d, x, y + s * 0.18, s, s * 0.64, c=c, r=0.18)
    for i in range(3):
        for j in range(4):
            kx = x + s * 0.18 + j * s * 0.21
            ky = y + s * 0.32 + i * s * 0.18
            d.rectangle([kx, ky, kx + s * 0.12, ky + s * 0.10], fill=BG)


def ic_clipboard(d, x, y, s, c=ICON):
    ic_round_rect(d, x + s * 0.14, y + s * 0.10, s * 0.72, s * 0.80, c=c, r=0.12)
    d.rounded_rectangle([x + s * 0.34, y, x + s * 0.66, y + s * 0.20],
                        radius=int(s * 0.08), fill=c)


def ic_smile(d, x, y, s, c=ICON):
    cx, cy, r = x + s / 2, y + s / 2, s * 0.34
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=c, width=max(2, int(s * 0.09)))
    for ex in (-0.32, 0.32):
        d.ellipse([cx + s * ex - s * 0.05, cy - s * 0.14,
                   cx + s * ex + s * 0.05, cy - s * 0.04], fill=c)
    d.arc([cx - s * 0.20, cy - s * 0.06, cx + s * 0.20, cy + s * 0.22],
          start=20, end=160, fill=c, width=max(2, int(s * 0.09)))


def ic_puzzle(d, x, y, s, c=ICON):
    ic_round_rect(d, x + s * 0.10, y + s * 0.10, s * 0.80, s * 0.80, c=c, r=0.10)
    d.ellipse([x + s * 0.38, y, x + s * 0.62, y + s * 0.20], fill=c)


def ic_lock(d, x, y, s, c=ICON):
    d.rounded_rectangle([x + s * 0.18, y + s * 0.44, x + s * 0.82, y + s * 0.88],
                        radius=int(s * 0.10), fill=c)
    d.arc([x + s * 0.26, y + s * 0.08, x + s * 0.74, y + s * 0.60],
          start=180, end=360, fill=c, width=max(2, int(s * 0.09)))
    kk = s * 0.09
    d.rectangle([x + s * 0.46, y + s * 0.60, x + s * 0.54, y + s * 0.72], fill=BG)


def ic_pen(d, x, y, s, c=ICON):
    d.polygon([(x + s * 0.20, y + s * 0.80), (x + s * 0.72, y + s * 0.16),
               (x + s * 0.86, y + s * 0.30), (x + s * 0.34, y + s * 0.94),
               (x + s * 0.20, y + s * 0.94)], fill=c)


def ic_screen(d, x, y, s, c=ICON):
    ic_round_rect(d, x + s * 0.10, y + s * 0.14, s * 0.80, s * 0.62, c=c, r=0.12)
    d.rectangle([x + s * 0.42, y + s * 0.80, x + s * 0.58, y + s * 0.90], fill=c)


def ic_list(d, x, y, s, c=ICON):
    for i in range(3):
        yy = y + s * (0.18 + i * 0.28)
        d.ellipse([x + s * 0.14, yy, x + s * 0.26, yy + s * 0.12], fill=c)
        d.rounded_rectangle([x + s * 0.34, yy + s * 0.02, x + s * 0.86, yy + s * 0.10],
                            radius=int(s * 0.04), fill=c)


def ic_book(d, x, y, s, c=ICON):
    ic_round_rect(d, x + s * 0.14, y + s * 0.16, s * 0.72, s * 0.68, c=c, r=0.08)
    d.rectangle([x + s * 0.46, y + s * 0.16, x + s * 0.54, y + s * 0.84], fill=BG)


def ic_wifi(d, x, y, s, c=ICON):
    cx, cy = x + s / 2, y + s * 0.82
    for rr in (0.30, 0.20, 0.10):
        d.arc([cx - s * rr, cy - s * rr, cx + s * rr, cy + s * rr],
              start=215, end=325, fill=c, width=max(2, int(s * 0.09)))
    d.ellipse([cx - s * 0.05, cy - s * 0.05, cx + s * 0.05, cy + s * 0.05], fill=c)


def ic_bluetooth(d, x, y, s, c=ICON):
    cx, cy = x + s / 2, y + s / 2
    d.line([(cx, cy - s * 0.36), (cx + s * 0.24, cy - s * 0.10)], fill=c, width=max(2, int(s * 0.09)))
    d.line([(cx + s * 0.24, cy - s * 0.10), (cx - s * 0.24, cy + s * 0.14)], fill=c, width=max(2, int(s * 0.09)))
    d.line([(cx - s * 0.24, cy + s * 0.14), (cx, cy + s * 0.36)], fill=c, width=max(2, int(s * 0.09)))
    d.line([(cx, cy - s * 0.36), (cx - s * 0.24, cy - s * 0.10)], fill=c, width=max(2, int(s * 0.09)))
    d.line([(cx - s * 0.24, cy - s * 0.10), (cx, cy + s * 0.36)], fill=c, width=max(2, int(s * 0.09)))


def ic_switch(d, x, y, s, on=True):
    """Android 开关：圆角轨道 + 圆钮"""
    w, h = s * 1.7, s * 0.92
    track = ICON_ACCENT if on else (74, 82, 96)
    knob = (255, 255, 255)
    d.rounded_rectangle([x, y + (s - h) / 2, x + w, y + (s - h) / 2 + h],
                        radius=int(h / 2), fill=track)
    kd = h - s * 0.16
    kx = x + w - kd - s * 0.08 if on else x + s * 0.08
    d.ellipse([kx, y + (s - h) / 2 + s * 0.08, kx + kd, y + (s - h) / 2 + s * 0.08 + kd], fill=knob)


def ic_battery(d, x, y, s, pct=100):
    w, h = s * 1.15, s * 0.56
    d.rounded_rectangle([x, y + (s - h) / 2, x + w, y + (s - h) / 2 + h],
                        radius=int(s * 0.08), outline=SUB, width=max(2, int(s * 0.07)))
    d.rectangle([x + w + s * 0.06, y + (s - h) / 2 + h * 0.28,
                 x + w + s * 0.16, y + (s - h) / 2 + h * 0.72], fill=SUB)
    iw = (w - s * 0.16) * pct / 100
    d.rectangle([x + s * 0.08, y + (s - h) / 2 + s * 0.08,
                 x + s * 0.08 + iw, y + (s - h) / 2 + h - s * 0.08], fill=SUB)


def ic_plain_circle(d, x, y, s, c=ICON):
    """实心圆：最容易触发误检的图标形态"""
    d.ellipse([x, y, x + s, y + s], fill=c)


# ---------------- 页面构建 ----------------

def row(d, y, icon_fn, text, f=None, switch=None, icon_color=None, gt=None):
    if icon_fn is not None:
        ix = X_ICON
        iy = y + (ROW_H - ICON_BOX) / 2
        if icon_color is not None:
            icon_fn(d, ix, iy, ICON_BOX, icon_color)
        else:
            icon_fn(d, ix, iy, ICON_BOX)
        if gt is not None:
            gt.append({"type": "icon", "bbox": [ix, iy, ICON_BOX, ICON_BOX]})
    if text:
        ty = y + (ROW_H - FS) / 2 - 4
        d.text((X_TEXT, ty), text, font=f or font(), fill=FG)
        if gt is not None:
            bb = d.textbbox((X_TEXT, ty), text, font=f or font())
            gt.append({"type": "text", "text": text,
                       "bbox": [bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1]]})
    if switch is not None:
        sx = 1180
        sy = y + (ROW_H - ICON_BOX) / 2
        ic_switch(d, sx, sy, ICON_BOX, on=switch)
        if gt is not None:
            gt.append({"type": "switch", "bbox": [sx, sy, ICON_BOX * 1.7, ICON_BOX]})


def header(d, y, text, gt=None):
    d.text((X_ICON, y + 8), text, font=font(34), fill=SUB)
    if gt is not None:
        bb = d.textbbox((X_ICON, y + 8), text, font=font(34))
        gt.append({"type": "text", "text": text,
                   "bbox": [bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1]]})


def status_bar(d, W, gt=None):
    """复刻用户截图顶部噪声：'ON' + 时间 + 电量（OCR 读出 '0N谷100'）"""
    f = font(30)
    d.text((40, 22), "ON", font=f, fill=SUB)
    t = "03:39"
    w, _ = tw(d, t, f)
    d.text(((W - w) / 2, 22), t, font=f, fill=SUB)
    d.text((W - 300, 22), "100", font=f, fill=SUB)
    ic_battery(d, W - 130, 18, 34, pct=100)
    if gt is not None:
        for s, xx in [("ON", 40), ("03:39", (W - w) / 2), ("100", W - 300)]:
            bb = d.textbbox((xx, 22), s, font=f)
            gt.append({"type": "status", "text": s,
                       "bbox": [bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1]]})
        gt.append({"type": "status", "text": "battery",
                   "bbox": [W - 130, 18, 34 * 1.15, 34]})


def page(W, H):
    img = Image.new("RGB", (W, H), BG)
    return img, ImageDraw.Draw(img)


def save(img, name, gt=None):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name)
    img.save(p)
    return p, gt


def build_all():
    made = []

    # ===== 图1：MBM 设置页复刻（对应用户真实截图）=====
    img, d = page(1280, 1560)
    gt = []
    status_bar(d, 1280, gt)
    y = 110
    header(d, y, "小企鹅输入法", gt); y += 74
    for ic, t in [(ic_keyboard, "虚拟键盘"), (ic_screen, "候选窗口"),
                  (ic_clipboard, "剪贴板"), (ic_smile, "表情和符号"),
                  (ic_puzzle, "插件"), (ic_gear, "高级")]:
        row(d, y, ic, t, gt=gt); y += ROW_H
    y += 30
    header(d, y, "MBM", gt); y += 74
    for ic, t in [(ic_list, "规则与词库"), (ic_book, "用户词库（含智能候选词）"),
                  (ic_screen, "Recall 屏幕记忆"), (ic_lock, "隐私与密码"),
                  (ic_pen, "离线手写")]:
        row(d, y, ic, t, gt=gt); y += ROW_H
    made.append(save(img, "icon_mbm_settings.png", gt))

    # ===== 图2：带开关的行（含单字标签）=====
    img, d = page(1280, 900)
    gt = []
    status_bar(d, 1280, gt)
    y = 110
    header(d, y, "网络与连接", gt); y += 80
    for ic, t, on in [(ic_wifi, "无线网络", True), (ic_bluetooth, "蓝牙", False),
                      (ic_gear, "飞行模式", False)]:
        row(d, y, ic, t, switch=on, gt=gt); y += ROW_H
    y += 40
    header(d, y, "单字标签行（误杀高风险）", gt); y += 80
    row(d, y, ic_plain_circle, "是", gt=gt); y += ROW_H
    row(d, y, ic_plain_circle, "否", gt=gt); y += ROW_H
    row(d, y, None, "确认", gt=gt); y += ROW_H
    made.append(save(img, "icon_switch_rows.png", gt))

    # ===== 图3：单字菜单项 + 竖排单字 =====
    img, d = page(1280, 1000)
    gt = []
    y = 110
    header(d, y, "单字菜单项", gt); y += 80
    for ic, t in [(ic_plain_circle, "设"), (ic_plain_circle, "置"),
                  (ic_plain_circle, "帮"), (ic_plain_circle, "助")]:
        row(d, y, ic, t, gt=gt); y += ROW_H
    y += 30
    header(d, y, "竖排单字（形态最接近图标）", gt); y += 80
    f = font(FS)
    for i, ch in enumerate("以弥止争"):
        xx, yy = 150, y + i * 100
        d.text((xx, yy), ch, font=f, fill=FG)
        bb = d.textbbox((xx, yy), ch, font=f)
        gt.append({"type": "text", "text": ch,
                   "bbox": [bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1]]})
    made.append(save(img, "icon_single_char.png", gt))

    # ===== 图4：小图标 + 短文字（高密度）=====
    img, d = page(1000, 1100)
    gt = []
    y = 90
    for ic, t in [(ic_gear, "设置"), (ic_lock, "安全"), (ic_book, "词库"),
                  (ic_pen, "手写"), (ic_smile, "表情"), (ic_puzzle, "扩展"),
                  (ic_clipboard, "剪贴"), (ic_screen, "屏幕"), (ic_wifi, "网络"),
                  (ic_bluetooth, "蓝牙")]:
        row(d, y, ic, t, gt=gt); y += 100
    made.append(save(img, "icon_dense_small.png", gt))

    return made


if __name__ == "__main__":
    paths = build_all()
    os.makedirs(OUT, exist_ok=True)
    manifest = {
        "disclaimer": "全部为 PIL 合成图，非真机截图；真机截图到手后必须重跑校准。",
        "real_sample_source": "用户 2026-10-25 手机截图 OCR 输出（见 icon_real_samples.md）",
        "images": [{"file": os.path.basename(p), "ground_truth": gt} for p, gt in paths],
    }
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as fp:
        json.dump(manifest, fp, ensure_ascii=False, indent=1)
    for p, _ in paths:
        print("生成", p)
    print("\n⚠️ 这些是合成图，不是真实截图。")
