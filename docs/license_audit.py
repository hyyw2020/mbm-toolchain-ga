#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
"""
license_audit.py — 许可审计与 SBOM 生成（MBM v1.0.26 / P2-9、P2-10）

做三件事：

1) **自有代码 SPDX 覆盖率**：app/src、lib/*/src、plugin/*/src 下每个
   Kotlin/Java/C/C++/Gradle 源文件都必须带 SPDX-License-Identifier 头。
   缺一个就是 AGPL 合规漏洞 —— 别人 fork 时无法判断这个文件能不能改。

2) **引入组件逐文件许可台账**：vendored submodule（fcitx5 / libime /
   fcitx5-chinese-addons / 各输入法插件）按「声明了哪个 SPDX 标识」逐文件
   统计。AGPL-3.0 主程序里混入 GPL-2.0-only、GPL-3.0-only 这类
   **不兼容许可**必须在发布前发现，不能等别人来问。

3) **输出 SBOM**：write 一份 CycloneDX 1.5 JSON（组件级）+ 一份人读的
   license 台账文本。SBOM 不依赖网络，纯本地解析。

用法：
    python3 docs/license_audit.py                 # 审计 + 写 docs/sbom/
    python3 docs/license_audit.py --check         # 只审计，缺 SPDX 头即非零退出
"""

from __future__ import annotations

import json
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs" / "sbom"

SOURCE_EXTS = {".kt", ".java", ".cpp", ".h", ".c", ".hpp", ".kts", ".cmake"}
SPDX_RE = re.compile(r"SPDX-License-Identifier:\s*([^\s*/]+)")
COPYRIGHT_RE = re.compile(r"SPDX-FileCopyrightText:\s*(.+)")

# 第一方代码根（必须 100% 带 SPDX 头）。
# "" = 仓库根（只取显式列出的根构建脚本，不整棵 rglob）。
FIRST_PARTY = ["", "app/src", "build-logic", "lib", "plugin"]
ROOT_FILES = ["build.gradle.kts", "settings.gradle.kts"]

# vendored submodule 目录名 → 上游项目名。
# 判定方式：源文件相对路径里**任一目录段**命中这里，即视为第三方代码
# （不强制补 SPDX——那是上游的文件，改它反而污染 submodule），
# 转而进入「逐文件许可台账」统计。
VENDORED_DIRS = {
    "fcitx5": "fcitx5",
    "prebuilt": "fcitx5-android/prebuilt",
    "libime": "libime",
    "fcitx5-lua": "fcitx5-lua",
    "fcitx5-chinese-addons": "fcitx5-chinese-addons",
    "ncnn": "ncnn (Tencent)",
    "anthy-cmake": "anthy",
    "fcitx5-anthy": "fcitx5-anthy",
    "fcitx5-chewing": "fcitx5-chewing",
    "fcitx5-hangul": "fcitx5-hangul",
    "libime-jyutping": "libime-jyutping",
    "fcitx5-rime": "fcitx5-rime",
    "rime-essay": "rime-essay",
    "rime-luna-pinyin": "rime-luna-pinyin",
    "rime-prelude": "rime-prelude",
    "rime-stroke": "rime-stroke",
    "fcitx5-sayura": "fcitx5-sayura",
    "fcitx5-libthai": "fcitx5-libthai",
    "fcitx5-unikey": "fcitx5-unikey",
}

# 构建产物/工具生成目录：不是人写的源码，不参与任何统计
BUILD_ARTIFACT_SEGMENTS = {".cxx", ".gradle", "build"}

# vendored 里出现即**不兼容**的许可：-only 结尾 = 禁止升级到 GPL-3.0 系，
# 无法与 AGPL-3.0 主程序合法组合，必须换实现或移除。
INCOMPATIBLE = {
    "GPL-1.0-only",
    "GPL-2.0-only",
    "LGPL-2.0-only",
    "LGPL-2.1-only",
}

# vendored 里出现需**记录升级路径**的许可：-or-later 可经升级条款走到
# GPL-3.0 / LGPL-3.0，再经 GPL-3.0 §13 与 AGPL-3.0 组合（fcitx5 生态
# 既成事实：fcitx5 主体 LGPL-2.1-or-later、fcitx5-anthy 等 GPL-2.0-or-later）。
UPGRADABLE = {
    "GPL-2.0-or-later",
    "LGPL-2.0-or-later",
    "LGPL-2.1-or-later",
}

# 第一方文件允许出现的 SPDX 许可：
#   AGPL-3.0-or-later —— MBM 新写/新改文件
#   LGPL-2.1-or-later / GPL-3.0-or-later —— 继承上游 fcitx5-android 的文件头，
#      与 AGPL-3.0 经 GPL-3.0 §13 链接条款组合（见 NOTICE 第五节）
# 出现集合外许可（尤其 INCOMPATIBLE 里的）→ 检查不通过。
FIRST_PARTY_ALLOWED = {
    "AGPL-3.0-or-later",
    "LGPL-2.1-or-later",
    "GPL-3.0-or-later",
}

# 允许的宽松许可（无需组合审查）
PERMISSIVE = {
    "MIT", "MIT-0", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause",
    "BSD-3-Clause-Clear", "ISC", "Zlib", "BSL-1.0", "CC0-1.0", "Unlicense",
    "Python-2.0", "OpenSSL",
}


def is_build_artifact(rel: str) -> bool:
    return any(seg in BUILD_ARTIFACT_SEGMENTS for seg in rel.split("/"))


def vendored_project_of(rel: str) -> str | None:
    """src/main/cpp/ 之后的目录段命中 vendored submodule 名则返回项目名。

    必须限定在 cpp/ 之后：Java 包路径同样会撞名
    （app/src/main/java/org/fcitx/fcitx5/android/ 里的 fcitx5 段），
    用 src/ 之后匹配会把第一方主代码树误判成 vendored。
    vendored 源码约定俗成都在 src/main/cpp/<name>/ 下。
    """
    segs = rel.split("/")
    if "cpp" not in segs:
        return None
    for seg in segs[segs.index("cpp") + 1:]:
        if seg in VENDORED_DIRS:
            return VENDORED_DIRS[seg]
    return None


def first_party_files() -> list[Path]:
    out: list[Path] = []
    for base in FIRST_PARTY:
        p = ROOT / base if base else ROOT
        if not p.is_dir():
            continue
        # 仓库根只取显式列出的构建脚本，避免整棵树重复扫描
        files = [ROOT / f for f in ROOT_FILES] if base == "" else sorted(p.rglob("*"))
        for f in files:
            if not f.is_file() or f.suffix not in SOURCE_EXTS:
                continue
            rel = f.relative_to(ROOT).as_posix()
            if is_build_artifact(rel):
                continue
            # 第三方 vendored 代码不算第一方（它们有自己的许可头，且不修改）
            if vendored_project_of(rel):
                continue
            out.append(f)
    return sorted(set(out))


def read_head(path: Path, n: int = 600) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")[:n]
    except OSError:
        return ""


def audit_first_party() -> tuple[list[Path], list[Path]]:
    ok, bad = [], []
    for f in first_party_files():
        head = read_head(f)
        (ok if SPDX_RE.search(head) else bad).append(f)
    return ok, bad


def license_of(path: Path) -> str | None:
    m = SPDX_RE.search(read_head(path))
    return m.group(1) if m else None


def disallowed_first_party() -> list[tuple[Path, str]]:
    """第一方文件里许可不在允许集合的（缺 SPDX 的由 audit_first_party 管）。"""
    out = []
    for f in first_party_files():
        lic = license_of(f)
        if lic and lic not in FIRST_PARTY_ALLOWED:
            out.append((f, lic))
    return out


def audit_vendored() -> dict[str, Counter]:
    """按上游项目统计各 SPDX 许可的文件数。submodule 未初始化时为空。"""
    result: dict[str, Counter] = defaultdict(Counter)
    for base in ("app/src", "lib", "plugin"):
        p = ROOT / base
        if not p.is_dir():
            continue
        for f in p.rglob("*"):
            if not f.is_file() or f.suffix not in SOURCE_EXTS:
                continue
            rel = f.relative_to(ROOT).as_posix()
            if is_build_artifact(rel):
                continue
            project = vendored_project_of(rel)
            if not project:
                continue
            head = read_head(f)
            m = SPDX_RE.search(head)
            if m:
                result[project][m.group(1)] += 1
            else:
                # 没头的文件按「COPYING/许可说明」推断，统一记 unknown，
                # 由人工在 NOTICE 里补（我们不猜）。
                result[project]["<no-spdx-header>"] += 1
    return result


def write_outputs(ok: list[Path], bad: list[Path], vendored: dict[str, Counter],
                  disallowed: list[tuple[Path, str]]) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # --- 人读台账 ---
    lines: list[str] = []
    lines.append("MBM 许可台账 / License Ledger")
    lines.append("生成者：docs/license_audit.py（纯本地解析，不联网）")
    lines.append("")
    lines.append(f"第一方代码：{len(ok)} 个文件带 SPDX 头，{len(bad)} 个缺失")
    if bad:
        lines.append("缺失 SPDX 头的文件（必须补齐）：")
        for f in bad:
            lines.append(f"  - {f.relative_to(ROOT).as_posix()}")
    dist = Counter(license_of(f) or "<none>" for f in first_party_files())
    lines.append("")
    lines.append("第一方许可分布（LGPL-2.1-or-later = 继承上游 fcitx5-android 的文件头，")
    lines.append("AGPL-3.0-or-later = MBM 新写/新改；组合分发依据见 NOTICE 第五节）：")
    for lic, n in dist.most_common():
        lines.append(f"    {lic}: {n} 个文件")
    if disallowed:
        lines.append("")
        lines.append("!!! 以下第一方文件使用了允许集合之外的许可，需人工裁定：")
        for f, lic in disallowed:
            lines.append(f"  - {f.relative_to(ROOT).as_posix()}: {lic}")
    lines.append("")
    lines.append("引入组件逐文件许可统计（submodule 未检出时该项为空）：")
    if not vendored:
        lines.append("  （未检测到任何 vendored 源码 —— submodule 未检出，")
        lines.append("   请在完整检出后重跑；结论以 NOTICE 第五节为准）")
    for project, counts in sorted(vendored.items()):
        lines.append(f"  {project}:")
        for lic, n in counts.most_common():
            lines.append(f"      {lic}: {n} 个文件")
    (OUT_DIR / "license_ledger.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # --- CycloneDX 风格 SBOM ---
    components = []
    for f in ok:
        head = read_head(f)
        m = SPDX_RE.search(head)
        lic = m.group(1) if m else "NOASSERTION"
        components.append({
            "type": "file",
            "name": f.relative_to(ROOT).as_posix(),
            "licenses": [{"license": {"id": lic if lic.startswith(("GPL", "LGPL", "AGPL")) else "LicenseRef-" + lic,
                                      "name": lic}}],
        })
    for project, counts in vendored.items():
        for lic, n in counts.most_common():
            components.append({
                "type": "library",
                "name": project,
                "version": "vendored-submodule",
                "licenses": [{"license": {"id": lic}}],
                "properties": [{"name": "fileCount", "value": str(n)}],
            })

    # Maven/Android 侧依赖从 Gradle 锁/版本目录里抓，避免重复实现解析器
    dep_components = []
    toml = (ROOT / "gradle" / "libs.versions.toml")
    if toml.exists():
        for line in toml.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r'^([A-Za-z0-9._-]+)\s*=\s*\{\s*(.+?)\s*\}\s*$', line.strip())
            if not m:
                continue
            name, body = m.group(1), m.group(2)
            coord = re.search(r'(module|group)\s*=\s*"([^"]+)"', body)
            ver = re.search(r'version(?:\.ref)?\s*=\s*"([^"]+)"', body)
            if not coord:
                continue
            gav = coord.group(2) if coord.group(1) == "module" else (
                coord.group(2) + ":" + (re.search(r'name\s*=\s*"([^"]+)"', body).group(1)
                                        if re.search(r'name\s*=\s*"([^"]+)"', body) else "")
            )
            dep_components.append({
                "type": "library",
                "name": gav,
                "version": ver.group(1) if ver else "unspecified",
                "licenses": [{"license": {"id": "LicenseRef-gradle-version-catalog"}}],
            })

    sbom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "name": "Mixed Bug Method (MBM)",
                "version": "see gradle/libs.versions.toml + git describe",
                # 事实：MBM 是 fcitx5-android 的衍生作品——主体文件继承上游
                # LGPL-2.1-or-later 头，MBM 新写部分为 AGPL-3.0-or-later。
                # SBOM 同时列两者，不宣称单一许可（见 NOTICE 第五节）。
                "licenses": [
                    {"license": {"id": "AGPL-3.0-or-later"}},
                    {"license": {"id": "LGPL-2.1-or-later"}},
                ],
            },
            "tools": [{"name": "docs/license_audit.py", "vendor": "MBM"}],
        },
        "components": components + dep_components,
    }
    (OUT_DIR / "sbom.cdx.json").write_text(
        json.dumps(sbom, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def main() -> int:
    check_only = "--check" in sys.argv
    ok, bad = audit_first_party()
    vendored = audit_vendored()
    disallowed = disallowed_first_party()

    print(f"第一方代码：{len(ok)} 个文件带 SPDX 头，{len(bad)} 个缺失")
    for f in bad:
        print(f"  [MISS] {f.relative_to(ROOT).as_posix()}")
    dist = Counter(license_of(f) or "<none>" for f in first_party_files())
    print("第一方许可分布：" + ", ".join(f"{lic}={n}" for lic, n in dist.most_common()))
    if disallowed:
        print("\n!!! 允许集合之外的第一方许可（需人工裁定）：")
        for f, lic in disallowed:
            print(f"  [LIC] {f.relative_to(ROOT).as_posix()}: {lic}")
    if vendored:
        print("\n引入组件逐文件许可：")
        for project, counts in sorted(vendored.items()):
            print(f"  {project}: " + ", ".join(f"{lic}={n}" for lic, n in counts.most_common()))
            for lic in counts:
                if lic in INCOMPATIBLE:
                    print(f"    !!! {project} 含 {lic} —— -only 许可无法升级，与 AGPL 主程序不兼容，必须替换")
                elif lic in UPGRADABLE:
                    print(f"    (i) {project} 含 {lic} —— 经升级条款至 GPL-3.0 系后可与 AGPL-3.0 §13 组合")
    else:
        print("\n引入组件许可：submodule 未检出，跳过（结论见 NOTICE）")

    if not check_only:
        write_outputs(ok, bad, vendored, disallowed)
        print(f"\n已写出 {OUT_DIR.relative_to(ROOT)}/license_ledger.txt 与 sbom.cdx.json")

    if bad:
        print("\n结论：存在缺 SPDX 头的第一方文件，AGPL 合规检查不通过。")
        return 1
    if disallowed:
        print("\n结论：第一方出现允许集合之外的许可，需人工裁定后重跑。")
        return 1
    print("\n结论：第一方 SPDX 覆盖率 100%，许可全部在允许集合内。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
