#!/usr/bin/env python3
"""Native symbol-level API compatibility audit.

For each .so in an APK: extract GLOBAL undefined symbols; for each, determine
the earliest Android API level at which it can be resolved, using:
  - the APK's own packaged libraries (their exported dynsyms), and
  - the NDK's per-API-level stub libraries (authoritative "available at API N"
    oracle for NDK-surface symbols; verified level-accurate, not cumulative).

WEAK undefined symbols never block dlopen; they are reported separately.
"""
import subprocess, sys, os, re, zipfile, tempfile
from collections import defaultdict

NDK = "/opt/android-sdk/ndk/28.0.13004108"
SYSROOT = f"{NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
ABIS = {"arm64-v8a": "aarch64-linux-android",
        "armeabi-v7a": "arm-linux-androideabi",
        "x86_64": "x86_64-linux-android",
        "x86": "i686-linux-android"}
APIS = list(range(29, 38))  # minSdk 29 .. targetSdk 37

def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout

def dynsyms(path):
    """Return list of (name, bind, ndx) from readelf -W --dyn-syms."""
    out = run(f"readelf -W --dyn-syms {path}")
    res = []
    for line in out.splitlines():
        # Num: Value Size Type Bind Vis Ndx Name[@ver]
        m = re.match(r"\s*\d+:\s+\S+\s+\d+\s+(\S+)\s+(\S+)\s+\S+\s+(\S+)\s+(\S+)", line)
        if not m:
            continue
        typ, bind, ndx, name = m.groups()
        name = name.split("@")[0]
        res.append((name, typ, bind, ndx))
    return res

def stub_symbols(api, libname, triple):
    """Exported symbol names of a system stub lib at a given API."""
    p = f"{SYSROOT}/{triple}/{api}/{libname}"
    if not os.path.exists(p):
        return set()
    out = run(f"nm -D --defined-only {p}")
    s = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 3:
            s.add(parts[2].split("@")[0])
    return s

def main(apk_path, label):
    tmp = tempfile.mkdtemp(prefix="audit_")
    apk = zipfile.ZipFile(apk_path)
    # collect per-ABI lib dirs present in the APK
    abi_dirs = defaultdict(list)
    for n in apk.namelist():
        m = re.match(r"lib/([^/]+)/([^/]+\.so)$", n)
        if m and m.group(1) in ABIS:
            abi_dirs[m.group(1)].append(m.group(2))
    print(f"\n{'='*78}\nAPK: {label}  ({apk_path})\n{'='*78}")
    findings = []
    for abi, libs in sorted(abi_dirs.items()):
        triple = ABIS[abi]
        # extract this ABI's libs
        d = os.path.join(tmp, abi)
        os.makedirs(d, exist_ok=True)
        for lib in libs:
            with open(os.path.join(d, lib), "wb") as f:
                f.write(apk.read(f"lib/{abi}/{lib}"))
        # APK's own exported symbols (all libs of this ABI, union)
        own = set()
        for lib in libs:
            for name, typ, bind, ndx in dynsyms(os.path.join(d, lib)):
                if ndx != "UND":
                    own.add(name)
        # system stubs per API
        sys_libs = ["libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
                    "libz.so", "libjnigraphics.so", "libOpenSLES.so", "libGLESv2.so",
                    "libEGL.so", "libaaudio.so", "libamidi.so", "libmediandk.so",
                    "libbinder_ndk.so", "libcamera2ndk.so", "libneuralnetworks.so",
                    "libnativewindow.so", "libsync.so", "libvulkan.so", "libstdc++.so"]
        stubs = {}
        for api in APIS:
            s = set()
            for sl in sys_libs:
                s |= stub_symbols(api, sl, triple)
            stubs[api] = s
        print(f"\n--- ABI {abi}: {len(libs)} .so ---")
        for lib in sorted(libs):
            p = os.path.join(d, lib)
            glob_und, weak_und = [], []
            for name, typ, bind, ndx in dynsyms(p):
                if ndx != "UND":
                    continue
                if typ == "NOTYPE" and bind == "WEAK":
                    weak_und.append(name)
                elif bind == "GLOBAL" or bind == "WEAK":
                    glob_und.append(name)
            # earliest API per symbol
            bad, unknown = [], []
            for name in sorted(set(glob_und)):
                if name in own:
                    continue
                earliest = None
                for api in APIS:
                    if name in stubs[api]:
                        earliest = api
                        break
                if earliest is None:
                    unknown.append(name)
                elif earliest > 29:
                    bad.append((name, earliest))
            status = "OK" if not bad and not unknown else "REVIEW"
            print(f"  [{status}] {lib}: global-undef={len(set(glob_und))} weak-undef={len(set(weak_und))}")
            for name, api in bad:
                print(f"      !! {name}  earliest API {api}  (> minSdk 29)")
            for name in unknown:
                print(f"      ?? {name}  not in stubs or APK libs (manual review)")
            findings.append((abi, lib, bad, unknown))
    # summary
    print(f"\n--- SUMMARY {label} ---")
    nbad = sum(len(b) for _,_,b,_ in findings)
    nunk = sum(len(u) for _,_,_,u in findings)
    print(f"libraries audited: {len(findings)}; above-minSdk symbols: {nbad}; unknown: {nunk}")
    for abi, lib, bad, unknown in findings:
        if bad or unknown:
            print(f"  {abi}/{lib}: bad={bad} unknown={unknown}")
    return findings

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(f"usage: {sys.argv[0]} <apk> [apk ...]")
    # 逐个 APK 审计并各自打汇总标签。注意：曾把第二个参数当 label 用时，
    # 传两个 APK 只会审第一个（其余被静吞），导致 recall 包被漏审——
    # 现改为显式遍历所有入参。
    for apk_path in sys.argv[1:]:
        main(apk_path, apk_path)
