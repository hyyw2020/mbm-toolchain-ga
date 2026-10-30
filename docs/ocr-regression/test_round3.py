# -*- coding: utf-8 -*-
"""第 3 轮（v1.0.30）验证：NMS 去重 / 置信门控多旋转 / 术语词典校正。
真实 PP-OCRv6 模型，与 Kotlin OcrEngine 即将落地的逻辑一致。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image, ImageDraw, ImageFont
import numpy as np
import fix_pipeline as F

FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
]
FONT = next((f for f in FONT_CANDIDATES if os.path.exists(f)), None)
if FONT is None:
    raise SystemExit("未找到中文字体")


def font(sz):
    return ImageFont.truetype(FONT, sz)


def make_text(s, fs=48, bg=(20, 22, 30), fg=(240, 240, 245), pad=24):
    f = font(fs)
    tmp = Image.new("RGB", (10, 10))
    d = ImageDraw.Draw(tmp)
    bb = d.textbbox((0, 0), s, font=f)
    w = bb[2] - bb[0] + pad * 2
    h = bb[3] - bb[1] + pad * 2
    img = Image.new("RGB", (int(w), int(h)), bg)
    d = ImageDraw.Draw(img)
    d.text((pad - bb[0], pad - bb[1]), s, font=f, fill=fg)
    return img


print("=" * 60)
print("A. NMS 单元测试（合成重叠四边形）")
print("=" * 60)
# 两个高度重叠的框（同一行被 DB 拆成两框），A 面积大、B 小且重叠 A
A = [(10, 10), (210, 10), (210, 60), (10, 60)]
B = [(20, 12), (200, 12), (200, 58), (20, 58)]  # 几乎被 A 包含
C = [(400, 10), (520, 10), (520, 60), (400, 60)]  # 远离，应保留
res = F.nms_boxes([A, B, C], iou_thresh=0.5)
print(f"  输入 3 框 → 保留 {len(res)} 框（期望 2：A 与 C，B 被抑制）")
assert len(res) == 2, "NMS 应抑制被包含的 B"
print("  ✓ NMS 抑制重叠小框，保留离散框")


print()
print("=" * 60)
print("B. 术语词典校正单元测试")
print("=" * 60)
cases = [
    ("战双帕弥件", 0.82, "战双帕弥什"),     # 形近 1 字（中等置信）
    ("战双帕弥件", 0.999, "战双帕弥什"),    # 自信地错（高置信，仍须校正）
    ("云·战双帕弥件", 0.80, "云·战双帕弥什"),
    ("战双帕弥什", 0.95, "战双帕弥什"),      # 精确命中，不变
    ("第一行文字内容", 0.96, "第一行文字内容"),  # 通用文本，不误伤
    ("明曰方舟", 0.78, "明日方舟"),          # 形近
    ("方舟", 0.90, "方舟"),                  # 短词，不得误扩为「明日方舟」
    ("战斗", 0.99, "战斗"),                  # 正确 2 字词，不得被近形「原神」吞掉
    ("战双帕弥件", 0.30, "战双帕弥件"),      # 低置信(<0.4)：不校正，保持原样
    ("明曰方舟", 0.25, "明曰方舟"),          # 低置信(<0.4)：不校正，保持原样
]
for inp, sc, exp in cases:
    out = F.correct_with_lexicon(inp, sc)
    ok = "✓" if out == exp else "✗"
    print(f"  {ok} '{inp}'({sc}) → '{out}'  期望 '{exp}'")
    assert out == exp, f"校正失败: {inp}"
print("  ✓ 词典校正仅对中等置信的近形术语生效，通用文本不误伤")


print()
print("=" * 60)
print("C. 端到端：置信门控多旋转 + 90° 短文本")
print("=" * 60)
# 短文本旋转 90°：高/宽落在 (1,2)，竖排分支不触发，靠门控 90/270 恢复
img = make_text("战斗", fs=64).rotate(90, expand=True)
lines = F.recognize(img)
txt = "/".join(t for t, s, q in lines)
print(f"  90°短文本识别: {lines}  -> '{txt}'")
print("  （竖排分支仅 h>2w 触发；此处靠门控旋转恢复，详见报告）")

# 倒转 180°（cls 应能纠，门控为兜底）
img2 = make_text("云·战双帕弥什", fs=48).rotate(180, expand=True)
lines2 = F.recognize(img2)
txt2 = "/".join(t for t, s, q in lines2)
print(f"  180°文本识别: {lines2}  -> '{txt2}'")
assert "云·战双帕弥什" in txt2 or "战双帕弥什" in txt2, "180° 未恢复"


print()
print("=" * 60)
print("D. 端到端：游戏术语含形近错字 → 校正命中")
print("=" * 60)
# 直接构造一张含「战双帕弥件」的文本图（模拟 rec 输出带 1 字错）
img3 = make_text("战双帕弥件", fs=48)
lines3 = F.recognize(img3)
txt3 = "/".join(t for t, s, q in lines3)
print(f"  识别+校正: {lines3}  -> '{txt3}'")
assert "战双帕弥什" in txt3, "术语校正未命中"
print("  ✓ 形近错字经词典校正还原为正确游戏名")


print()
print("全部第 3 轮单元测试通过。")
