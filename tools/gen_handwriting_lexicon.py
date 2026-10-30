#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
"""
生成手写候选增强数据（供 HandwritingWindow 显示拼音与关联词，对齐主流半屏手写布局）：

  1. handwriting_pinyin.tsv —— charset 内每个单字的普通话拼音（无声调，ü→v）
  2. handwriting_words.tsv —— 每个单字的关联词（每字最多 4 个，2~4 字词）

数据来源（二选一，按优先级）：
  A. jieba 大词典 dict.txt.big（https://github.com/fxsjy/jieba ，MIT）：
     "词语 词频 词性"，按词频取每字 top 词 —— 质量最高（岁月/岁数/中国/时间这类高频词）
     下载：curl -o /tmp/jieba_full.txt https://raw.githubusercontent.com/fxsjy/jieba/master/extra_dict/dict.txt.big
  B. pypinyin 短语表（pip install pypinyin，MIT）：无词频，按 2 字词优先的启发式取词

拼音一律来自 pypinyin（选最常用读音，无声调）。

用法：
  pip install pypinyin
  python3 tools/gen_handwriting_lexicon.py [jieba_dict_path]

产出直接写入 lib/handwritten/src/main/assets/，随 APK 打包（库模块 assets 会合并进主 app）。
"""
import json
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHARSET = ROOT / "lib/handwritten/src/main/assets/charset.json"
OUT_DIR = ROOT / "lib/handwritten/src/main/assets"

MAX_WORDS_PER_CHAR = 4
MIN_WORD_LEN = 2
MAX_WORD_LEN = 4


def is_cjk(ch: str) -> bool:
    return "\u4e00" <= ch <= "\u9fff"


def load_charset_chars() -> set:
    data = json.loads(CHARSET.read_text(encoding="utf-8"))
    chars = set(data["char_to_idx"].keys())
    assert len(chars) == data["num_classes"], "charset.json 结构异常"
    return chars


def build_pinyin(chars: set) -> dict:
    """char -> 无声调拼音（pypinyin 选最常用读音，ü→v）。"""
    from pypinyin import lazy_pinyin, Style

    table = {}
    for ch in sorted(chars):
        py = lazy_pinyin(ch, style=Style.NORMAL)
        if py and py[0]:
            table[ch] = py[0]
    return table


def words_from_jieba(path: Path, chars: set) -> dict:
    """jieba dict.txt.big: 'word freq pos'，按词频降序取每字 top 词。"""
    buckets = defaultdict(list)
    with open(path, encoding="utf-8", errors="ignore") as fp:
        for line in fp:
            parts = line.split()
            if len(parts) < 2:
                continue
            word = parts[0]
            if not (MIN_WORD_LEN <= len(word) <= MAX_WORD_LEN):
                continue
            if word[0] not in chars:
                continue
            if not all(is_cjk(c) for c in word):
                continue
            try:
                freq = int(parts[1])
            except ValueError:
                continue
            buckets[word[0]].append((freq, word))
    out = {}
    for ch, items in buckets.items():
        items.sort(key=lambda t: -t[0])
        out[ch] = [w for _, w in items[:MAX_WORDS_PER_CHAR]]
    return out


def words_from_pypinyin(chars: set) -> dict:
    """pypinyin 短语表：无词频，按「2 字词优先、再 3 字、再 4 字」启发式取词。"""
    from pypinyin.phrases_dict import phrases_dict

    buckets = defaultdict(list)
    for word in phrases_dict:
        if not (MIN_WORD_LEN <= len(word) <= MAX_WORD_LEN):
            continue
        if word[0] not in chars:
            continue
        if not all(is_cjk(c) for c in word):
            continue
        buckets[word[0]].append(word)
    out = {}
    for ch, items in buckets.items():
        picked = []
        for ln in (2, 3, 4):
            picked += [w for w in items if len(w) == ln]
            if len(picked) >= MAX_WORDS_PER_CHAR:
                break
        out[ch] = picked[:MAX_WORDS_PER_CHAR]
    return out


def main() -> None:
    jieba_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/jieba_full.txt")
    chars = load_charset_chars()

    pinyin = build_pinyin(chars)
    if jieba_path.is_file() and jieba_path.stat().st_size > 1_000_000:
        words = words_from_jieba(jieba_path, chars)
        source = f"jieba {jieba_path.name}"
    else:
        words = words_from_pypinyin(chars)
        source = "pypinyin phrases"

    pinyin_file = OUT_DIR / "handwriting_pinyin.tsv"
    words_file = OUT_DIR / "handwriting_words.tsv"
    with open(pinyin_file, "w", encoding="utf-8") as fp:
        for ch, py in sorted(pinyin.items()):
            fp.write(f"{ch}\t{py}\n")
    with open(words_file, "w", encoding="utf-8") as fp:
        for ch in sorted(words):
            fp.write(f"{ch}\t{' '.join(words[ch])}\n")

    covered = sum(1 for c in chars if c in words)
    print(f"source={source}")
    print(f"pinyin: {len(pinyin)}/{len(chars)} -> {pinyin_file}")
    print(f"words : {covered}/{len(chars)} -> {words_file}")


if __name__ == "__main__":
    main()
