# -*- coding: utf-8 -*-
"""修复方向可行性验证：
  1. 竖排文本 rot90 后 rec 是否可识别
  2. 超长词：提高 rec 宽度上限能否救回
  3. 「字→学」是否句子相关（换句测低对比度）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import ppocr_pipeline as P

FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"


def font(sz):
    return ImageFont.truetype(FONT, sz)


print("== 1. 竖排 rot90 可行性 ==")
img = Image.new("RGB", (300, 900), (20, 22, 30))
d = ImageDraw.Draw(img)
f = font(56)
for i, ch in enumerate("以弥止争"):
    d.text((100, 60 + i * 80), ch, font=f, fill=(240, 240, 245))

# 复刻 fix_pipeline 的裁剪逻辑（warp 到矩形后）手动送不同旋转
import fix_pipeline as F
boxes = F.merge_same_row_boxes(P.detect(img))
print(f"  det 框数: {len(boxes)}")
for q in boxes:
    crop = F.warp_crop_fixed(img, q)
    print(f"  crop 尺寸: {crop.size} (h={crop.height}, w={crop.width})")
    # 原样
    t0, s0 = P.recognize_crop(crop)
    print(f"  原样 rec: {t0!r} {s0:.2f}")
    # 逆时针 90
    c1 = crop.transpose(Image.ROTATE_90)
    t1, s1 = P.recognize_crop(c1)
    print(f"  逆时针90: {t1!r} {s1:.2f}  (crop {c1.size})")
    # 顺时针 90
    c2 = crop.transpose(Image.ROTATE_270)
    t2, s2 = P.recognize_crop(c2)
    print(f"  顺时针90: {t2!r} {s2:.2f}  (crop {c2.size})")

print("\n== 2. 超长词：rec 宽度上限实验 ==")
long_text = "露西亚深红之渊武装构造体编号九三零四一七号机体已确认损毁请立即前往维修站进行整备"
img = Image.new("RGB", (2400, 300), (255, 255, 255))
d = ImageDraw.Draw(img)
d.text((60, 100), long_text, font=font(64), fill=(30, 30, 30))
boxes = F.merge_same_row_boxes(P.detect(img))
print(f"  det 框数: {len(boxes)}")
for q in boxes:
    crop = F.warp_crop_fixed(img, q)
    print(f"  crop 尺寸: {crop.size}, ratio={crop.width/crop.height:.1f}")

    # 手动 rec：绕过 REC_WIDTH_MAX，直接按比例 resize
    import math
    ratio = crop.width / crop.height
    for cap in [960, 1280, 1920, 2560]:
        targetW = min(cap, max(8, math.ceil(P.REC_HEIGHT * ratio)))
        resized = crop.resize((targetW, P.REC_HEIGHT), Image.BILINEAR)
        arr = np.asarray(resized.convert("RGB"), dtype=np.float32) / 255.0
        b, g, r = arr[:, :, 2].flatten(), arr[:, :, 1].flatten(), arr[:, :, 0].flatten()
        plane = P.REC_HEIGHT * targetW
        inp = np.concatenate([
            ((b - .5) / .5).reshape(1, -1),
            ((g - .5) / .5).reshape(1, -1),
            ((r - .5) / .5).reshape(1, -1),
        ]).reshape(1, 3, P.REC_HEIGHT, targetW)
        out = P.rec_sess.run(None, {"x": inp})[0]
        time, classes = out.shape[1], out.shape[2]
        text, score = P.ctc_decode(out.flatten(), time, classes)
        print(f"  cap={cap:5d} -> targetW={targetW:5d}: {text!r} {score:.2f}")

    # 分段识别：按 48 高对应 ~900 宽切片（重叠 100px）
    print("  --- 分段识别（每片宽 ~900，重叠 100）---")
    seg = 900
    ov = 100
    x = 0
    parts = []
    while x < crop.width:
        piece = crop.crop((x, 0, min(x + seg, crop.width), crop.height))
        if piece.width < 50:
            break
        t, s = P.recognize_crop(piece)
        parts.append((x, t, s))
        if x + seg >= crop.width:
            break
        x += seg - ov
    for x, t, s in parts:
        print(f"    x={x:4d}: {t!r} {s:.2f}")

print("\n== 3. 「字→学」是否句子相关 ==")
for sentence in ["账号安全设置中心", "字体大小调节界面", "文字输入测试用例", "低对比度环境渲染"]:
    img = Image.new("RGB", (900, 200), (40, 42, 50))
    d = ImageDraw.Draw(img)
    d.text((60, 80), sentence, font=font(44), fill=(100, 102, 110))
    lines = F.recognize(img)
    print(f"  {sentence!r} -> {[t for t, s, q in lines]} {[round(s, 2) for t, s, q in lines]}")
