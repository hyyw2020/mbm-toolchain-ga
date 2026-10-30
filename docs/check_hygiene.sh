#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
#
# check_hygiene.sh — MBM v1.0.26 / P2-8 静态 final 反射禁令 + hook 库清查 + 隐私红线。
#
# 这些是**禁令**，不是建议。禁令必须可执行，否则等于没有：
#   1. JNI 不得读写 static final 字段（Android 17 / API 37 起
#      SetStaticLongField 等对 final 字段直接 abort，见
#      https://developer.android.com/about/versions/17/behavior-changes-all
#      「JNI 对 static final 字段的中止」相关变更）；
#   2. Kotlin/Java 不得用反射改 static final；
#   3. 依赖树里不得出现任何 hook / 插桩 / 动态调试框架
#      （Xposed / Frida / Dobby / bhook / Epic / AndHook ...）；
#   4. 隐私红线：日志与 Intent 里不得出现明文用户输入的提交调用
#      （commitText / setComposingText 的字符串参数直接进日志 = 泄露）。
#
# 用法：./docs/check_hygiene.sh   （在仓库根目录执行）
# 退出码：0 = 全部通过；1 = 有命中项，需人工裁决后放行或修复。

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

FAIL=0
note() { printf '%s\n' "$*"; }
fail() { printf '[FAIL] %s\n' "$*"; FAIL=1; }

SRC_DIRS="app/src lib plugin"
# 排除构建产物与第三方 submodule（它们不是我们的代码，不该按我们的禁令判）
EXCLUDE_BUILD="--glob=!**/build/**"
EXCLUDE_SUB="--glob=!**/cpp/fcitx5/** --glob=!**/cpp/libime/** --glob=!**/cpp/fcitx5-chinese-addons/** --glob=!**/cpp/fcitx5-lua/** --glob=!**/cpp/anthy-cmake/** --glob=!**/cpp/fcitx5-anthy/** --glob=!**/cpp/fcitx5-chewing/** --glob=!**/cpp/fcitx5-hangul/** --glob=!**/cpp/fcitx5-sayura/** --glob=!**/cpp/fcitx5-libthai/** --glob=!**/cpp/fcitx5-unikey/** --glob=!**/cpp/rime-*/** --glob=!**/ClearURLsRules/** --glob=!**/prebuilt/**"

note "== 1) JNI 静态字段读写 =="
if grep -rnE "SetStatic(Byte|Char|Boolean|Short|Int|Long|Float|Double|Object)Field" \
     $SRC_DIRS $EXCLUDE_BUILD $EXCLUDE_SUB 2>/dev/null | head -5 | grep -q .; then
    fail "发现 JNI 写静态字段（API 37 起对 final 字段直接 abort）"
else
    note "OK"
fi

note "== 2) Kotlin/Java 反射改 static final =="
if grep -rnE "setAccessible\(true\)" $SRC_DIRS $EXCLUDE_BUILD $EXCLUDE_SUB 2>/dev/null | head -5 | grep -q .; then
    fail "发现 setAccessible(true)（可改 static final，API 37 起不可靠）"
else
    note "OK"
fi

note "== 3) hook / 插桩 / 动态调试框架 =="
if grep -rniE "xposed|frida|dexposed|substrate|andhook|epic|dobby|bhook|whale|sandhook|pine" \
     gradle/libs.versions.toml app/build.gradle.kts lib/plugin-base/build.gradle.kts \
     plugin/*/build.gradle.kts 2>/dev/null | head -5 | grep -q .; then
    fail "发现 hook/插桩类依赖"
else
    note "OK"
fi

note "== 4) 隐私红线：用户输入不得直接进日志/Intent =="
# 只查「把 commitText/setComposingText 的字符串参数直接喂给 Timber」这类明显形态，
# 正常的输入提交通道（InputConnection.commitText）不算 —— 那是输入法的本职工作。
if grep -rnE 'Timber\.[dviwe]\("[^"]*\$?\{?text' $SRC_DIRS $EXCLUDE_BUILD $EXCLUDE_SUB 2>/dev/null | head -5 | grep -q .; then
    fail "疑似把输入文本直接写进日志"
else
    note "OK"
fi

note ""
if [ "$FAIL" -eq 0 ]; then
    note "全部通过。"
else
    note "存在命中项：逐条确认是误报就地把规则写细（加 Glob），是真问题就改代码。"
fi
exit "$FAIL"
