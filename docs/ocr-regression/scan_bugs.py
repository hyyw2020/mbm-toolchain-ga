# -*- coding: utf-8 -*-
"""同源 bug 系统性扫描：拆分类 / 识别类 / 旋转组合类。

全部场景走 fix_pipeline.recognize（三修复复合版），
与 Kotlin OcrEngine.kt v1.0.28 行为一致。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image, ImageDraw, ImageEnhance, ImageFont

import fix_pipeline as F

FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
]
FONT = next((f for f in FONT_CANDIDATES if os.path.exists(f)), None)
assert FONT, "未找到中文字体"

RESULTS = []


def record(cat, name, expect, img, note=""):
    order = os.environ.get("OCR_ORDER", "merge_nms")
    lines = F.recognize(img, order=order)
    texts = [t for t, s, q in lines]
    scores = [round(s, 2) for t, s, q in lines]
    flat = "".join(texts).replace(" ", "")
    ok = all(e in flat for e in expect) if expect else None
    RESULTS.append({
        "cat": cat, "name": name, "expect": expect, "got": texts,
        "scores": scores, "boxes": len(lines), "ok": ok, "note": note,
        "order": order, "nms_suppressed": F.LAST_NMS_SUPPRESSED,
    })
    mark = "OK " if ok else ("FAIL" if ok is False else "----")
    print(f"[{mark}] {cat}/{name}: {len(lines)}框 -> {texts} {scores} nms_sup={F.LAST_NMS_SUPPRESSED} {note}")


def font(sz):
    return ImageFont.truetype(FONT, sz)


def shear_italic(img, k=0.25):
    """水平斜切模拟斜体。"""
    w, h = img.size
    return img.transform((w + int(k * h), h), Image.AFFINE,
                         (1, -k, k * h, 0, 1, 0), resample=Image.BILINEAR)


# ================= A. 拆分类 =================
print("== A. 拆分类 ==")

# A1 紧凑双行（误合并风险——合并修复的直接风险面）
# 行距 = 字高 + 间隙；gap_ratio 为间隙/字高（0.35 表示行间隙 0.35x 字高）
for gap_ratio in [0.2, 0.35, 0.5, 0.8]:
    fs = 48
    img = Image.new("RGB", (900, 400), (20, 22, 30))
    d = ImageDraw.Draw(img)
    f = font(fs)
    y1 = 60
    y2 = y1 + int(fs * (1 + gap_ratio))
    d.text((80, y1), "第一行文字内容", font=f, fill=(240, 240, 245))
    d.text((80, y2), "第二行文字内容", font=f, fill=(240, 240, 245))
    record("A", f"紧凑双行-间隙{gap_ratio}x字高", ["第一行文字内容", "第二行文字内容"], img,
           f"行中心距={y2-y1}px")

# A1b 倾斜紧凑双行（旋转 + 紧凑，合并判据最严苛场景）
for ang in [8, 12]:
    fs = 48
    img = Image.new("RGB", (900, 400), (20, 22, 30))
    d = ImageDraw.Draw(img)
    f = font(fs)
    d.text((80, 60), "第一行文字内容", font=f, fill=(240, 240, 245))
    d.text((80, 60 + int(fs * 1.35)), "第二行文字内容", font=f, fill=(240, 240, 245))
    rot = img.rotate(ang, resample=Image.BILINEAR, expand=False, fillcolor=(20, 22, 30))
    record("A", f"倾斜{ang}度紧凑双行", ["第一行文字内容", "第二行文字内容"], rot)

# A1c 回归：云·战双帕弥什宽间隙（上周合并修复目标场景，确认未破坏）
for g in [60, 80]:
    img = Image.new("RGB", (1400, 320), (20, 22, 30))
    d = ImageDraw.Draw(img)
    f = font(96)
    x = 200
    for t, gap in [("云", g), ("·", g), ("战双帕弥什", 0)]:
        d.text((x, 100), t, font=f, fill=(240, 240, 245))
        bb = d.textbbox((x, 100), t, font=f)
        x = bb[2] + gap
    record("A", f"回归-云战双宽间隙{g}px", ["云·战双帕弥什"], img)

# A2 双行不同字号
img = Image.new("RGB", (900, 400), (20, 22, 30))
d = ImageDraw.Draw(img)
d.text((80, 50), "大标题文字", font=font(64), fill=(240, 240, 245))
d.text((80, 190), "小字副标题说明", font=font(28), fill=(200, 200, 210))
record("A", "双行不同字号(64/28)", ["大标题文字", "小字副标题说明"], img)

# A3 竖排文本
img = Image.new("RGB", (300, 900), (20, 22, 30))
d = ImageDraw.Draw(img)
f = font(56)
for i, ch in enumerate("以弥止争"):
    d.text((100, 60 + i * 80), ch, font=f, fill=(240, 240, 245))
record("A", "竖排四字", ["以弥止争"], img)

# A4 超长词（> REC_WIDTH_MAX=960）
img = Image.new("RGB", (2400, 300), (255, 255, 255))
d = ImageDraw.Draw(img)
long_text = "露西亚深红之渊武装构造体编号九三零四一七号机体已确认损毁请立即前往维修站进行整备"
d.text((60, 100), long_text, font=font(64), fill=(30, 30, 30))
record("A", "超长词-32字", [long_text], img)

# A5 三行块
img = Image.new("RGB", (900, 500), (20, 22, 30))
d = ImageDraw.Draw(img)
for i, t in enumerate(["账号与安全", "消息通知设置", "隐私与权限"]):
    d.text((80, 60 + i * 130), t, font=font(48), fill=(240, 240, 245))
record("A", "三行菜单", ["账号与安全", "消息通知设置", "隐私与权限"], img)

# ================= B. 识别类 =================
print("\n== B. 识别类 ==")

# B1 小字号
for fs in [14, 18, 24, 32]:
    img = Image.new("RGB", (800, 200), (255, 255, 255))
    d = ImageDraw.Draw(img)
    d.text((60, 80), "战双帕弥什云游戏", font=font(fs), fill=(30, 30, 30))
    record("B", f"小字号-{fs}px", ["战双帕弥什云游戏"], img)

# B2 低对比度
for lv in [30, 60, 100, 150]:
    img = Image.new("RGB", (800, 200), (40, 42, 50))
    d = ImageDraw.Draw(img)
    d.text((60, 80), "低对比度文字测试", font=font(40), fill=(40 + lv, 42 + lv, 50 + lv))
    record("B", f"低对比度-灰度差{lv}", ["低对比度文字测试"], img)

# B3 半透明 UI（模拟游戏浮层）
base = Image.new("RGB", (800, 300), (120, 90, 60))
d = ImageDraw.Draw(base)
d.rectangle([40, 60, 760, 240], fill=(30, 30, 30))
overlay = Image.new("RGBA", (800, 300), (0, 0, 0, 0))
do = ImageDraw.Draw(overlay)
do.text((80, 120), "半透明浮层文字", font=font(44), fill=(255, 255, 255, 160))
img = Image.alpha_composite(base.convert("RGBA"), overlay).convert("RGB")
record("B", "半透明浮层-alpha160", ["半透明浮层文字"], img)

# B4 反白字
img = Image.new("RGB", (800, 200), (245, 245, 250))
d = ImageDraw.Draw(img)
d.text((60, 80), "反白文字测试", font=font(44), fill=(25, 25, 30))
record("B", "反白字-亮底暗字", ["反白文字测试"], img)

# B5 斜体
img = Image.new("RGB", (900, 220), (255, 255, 255))
d = ImageDraw.Draw(img)
d.text((60, 80), "斜体文字识别测试", font=font(48), fill=(30, 30, 30))
record("B", "斜体-shear0.25", ["斜体文字识别测试"], shear_italic(img))

# B6 中英混排 + 数字
img = Image.new("RGB", (900, 200), (255, 255, 255))
d = ImageDraw.Draw(img)
d.text((60, 80), "帕弥什 PM-2024 机体", font=font(44), fill=(30, 30, 30))
record("B", "中英数字混排", ["帕弥什", "PM-2024", "机体"], img)

# ================= C. 旋转组合 =================
print("\n== C. 旋转组合 ==")

# C1 倾斜 + 小字号
for ang in [8, 12]:
    img = Image.new("RGB", (900, 300), (20, 22, 30))
    d = ImageDraw.Draw(img)
    d.text((80, 120), "倾斜小字测试内容", font=font(24), fill=(240, 240, 245))
    rot = img.rotate(ang, resample=Image.BILINEAR, expand=False, fillcolor=(20, 22, 30))
    record("C", f"倾斜{ang}度+24px小字", ["倾斜小字测试内容"], rot)

# C2 倾斜 + 低对比度
for ang in [8, 12]:
    img = Image.new("RGB", (900, 300), (40, 42, 50))
    d = ImageDraw.Draw(img)
    d.text((80, 120), "倾斜低对比度文字", font=font(44), fill=(100, 102, 110))
    rot = img.rotate(ang, resample=Image.BILINEAR, expand=False, fillcolor=(40, 42, 50))
    record("C", f"倾斜{ang}度+低对比度", ["倾斜低对比度文字"], rot)

# C3 倾斜 + 描边艺术字（最接近真实 logo 形态）
for ang in [10, 15]:
    img = Image.new("RGB", (1100, 340), (18, 20, 28))
    d = ImageDraw.Draw(img)
    f = font(96)
    d.text((90, 110), "云·战双帕弥什", font=f, fill=(18, 20, 28),
           stroke_width=4, stroke_fill=(220, 180, 90))
    rot = img.rotate(ang, resample=Image.BILINEAR, expand=False, fillcolor=(18, 20, 28))
    record("C", f"倾斜{ang}度+描边艺术字", ["云·战双帕弥什"], rot)

# ================= 汇总 =================
fails = [r for r in RESULTS if r["ok"] is False]
print("\n===== 汇总 =====")
print(f"总场景 {len(RESULTS)}，通过 {len(RESULTS)-len(fails)}，失败 {len(fails)}")
for r in fails:
    print(f"  FAIL {r['cat']}/{r['name']}: got={r['got']} expect={r['expect']} {r['note']}")

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scan_results.json")
with open(out, "w", encoding="utf-8") as fp:
    json.dump(RESULTS, fp, ensure_ascii=False, indent=1)
print(f"\n明细已存 {out}")
