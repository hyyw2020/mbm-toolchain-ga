#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
#
# check_elf_alignment.sh — 校验 APK 内全部 .so 的 ELF PT_LOAD 段是否 16 KB 对齐
#                         （MBM v1.0.26 / P0-1「16KB 页对齐」的唯一验收手段）
#
# 背景：Android 15 起设备可选 16 KB 页大小。若 .so 的 LOAD 段只按 4 KB 对齐，
#       在 16 KB 设备上 dlopen 直接失败（EINVAL），表现为「装上就打不开/白屏」。
#       构建侧的 flag 只是「尽量对齐」，真正的验收必须落到产物上逐个段看。
#
# 用法：
#   ./docs/check_elf_alignment.sh <apk-or-dir> [more apks/dirs...]
#
# 判定：每个 LOAD 段的 p_align 必须 >= 0x4000（16384）。
#
# 依赖：python3（解析 ELF 程序头，不依赖 llvm-readelf；用 zipfile 直接读 APK）。
#
# 退出码：0 = 全部通过；1 = 存在未对齐段或文件无法解析。

set -u

if [ $# -lt 1 ]; then
    echo "用法: $0 <apk|目录> [...]" >&2
    exit 2
fi

python3 - "$@" <<'PY'
import sys, os, struct, zipfile, io, fnmatch

ALIGN = 0x4000  # 16 KB

def elf_load_alignments(blob: bytes):
    """返回 (is_elf, [p_align, ...])；非 ELF 返回 (False, [])。"""
    if len(blob) < 64 or blob[:4] != b"\x7fELF":
        return False, []
    ei_class, ei_data = blob[4], blob[5]
    end = "<" if ei_data == 1 else ">"
    if ei_class == 2:      # ELF64
        e_phoff, = struct.unpack_from(end + "Q", blob, 0x20)
        e_phentsize, e_phnum = struct.unpack_from(end + "HH", blob, 0x36)
        ph_fmt = end + "IIQQQQQQ"
    elif ei_class == 1:    # ELF32
        e_phoff, = struct.unpack_from(end + "I", blob, 0x1C)
        e_phentsize, e_phnum = struct.unpack_from(end + "HH", blob, 0x2A)
        ph_fmt = end + "IIIIIIII"
    else:
        return False, []
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + e_phentsize > len(blob):
            return True, []
        p_type = struct.unpack_from(end + "I", blob, off)[0]
        if p_type == 1:  # PT_LOAD
            if ei_class == 2:
                p_align, = struct.unpack_from(end + "Q", blob, off + 0x30)
            else:
                p_align, = struct.unpack_from(end + "I", blob, off + 0x1C)
            aligns.append(p_align)
    return True, aligns

def iter_targets(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, files in os.walk(p):
                for f in files:
                    yield os.path.join(root, f)
        else:
            yield p

def check_apk(apk_path):
    """逐个条目读 APK 里的 lib/<abi>/*.so。"""
    results = []
    with zipfile.ZipFile(apk_path) as z:
        names = [n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")]
        for n in sorted(names):
            with z.open(n) as fh:
                blob = fh.read()
            results.append((apk_path + "!" + n, blob))
    return results

checked = 0
bad = []
errors = []

for target in iter_targets(sys.argv[1:]):
    if zipfile.is_zipfile(target) and target.endswith((".apk", ".aab")):
        try:
            items = check_apk(target)
        except Exception as e:                       # noqa: BLE001
            errors.append(f"{target}: 读取失败 {e}")
            continue
    else:
        items = [(target, open(target, "rb").read())]

    for name, blob in items:
        if not name.endswith(".so"):
            continue
        is_elf, aligns = elf_load_alignments(blob)
        checked += 1
        if not is_elf:
            errors.append(f"{name}: 不是合法 ELF")
            continue
        if not aligns:
            errors.append(f"{name}: 未解析到 PT_LOAD 段")
            continue
        worst = min(aligns)
        if worst < ALIGN:
            bad.append((name, aligns))

for name, aligns in bad:
    pretty = " ".join(f"0x{a:x}" for a in aligns)
    print(f"[FAIL] {name}\n       LOAD p_align = {pretty}（存在 < 0x{ALIGN:x}）")
for e in errors:
    print(f"[ERR ] {e}")

print(f"\n共校验 {checked} 个 .so；未对齐 {len(bad)} 个；解析错误 {len(errors)} 个")
if bad or errors:
    print("结论：存在 16 KB 页不兼容的原生库，禁止随包发布。")
    print("排查：1) 确认 ndkVersion >= r26（本工程为 r28，默认 16 KB 对齐）")
    print("      2) 确认 NativeBaseConventionPlugin 里的 -Wl,-z,max-page-size=16384 生效")
    print("      3) prebuilt/*.so 必须用同一套参数重新出库后替换")
    sys.exit(1)
print("结论：全部 .so 均满足 16 KB ELF 段对齐。")
PY
