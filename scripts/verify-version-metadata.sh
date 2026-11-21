#!/usr/bin/env bash
# MBM 版本元数据一致性校验（v1.0.87 工程化）
#
# 校验构建出的 APK 的 versionName / versionCode 与 Versions.kt 声明一致，
# 防止「忘了 bump」或「bump 错位」。arm64 的 versionCode = baseVersionCode*10 + 2。
#
# 用法: scripts/verify-version-metadata.sh [APK路径]
#   缺省查找 app/build/outputs/apk/release/*arm64-v8a-release-unsigned.apk
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

APK="${1:-}"
if [ -z "$APK" ]; then
  APK="$(ls "$REPO_ROOT"/app/build/outputs/apk/release/*arm64-v8a-release-unsigned.apk 2>/dev/null | head -1 || true)"
fi
[ -n "$APK" ] && [ -f "$APK" ] || { echo "FAIL: 找不到 APK（请先 build/sign）: ${APK:-<none>}" >&2; exit 1; }

BASE_CODE="$(sed -n 's/.*const val baseVersionCode = \([0-9]*\).*/\1/p' \
  "$REPO_ROOT/build-logic/convention/src/main/kotlin/Versions.kt" | head -1)"
REL_NAME="$(sed -n 's/.*const val releaseVersionName = "\([^"]*\)".*/\1/p' \
  "$REPO_ROOT/build-logic/convention/src/main/kotlin/Versions.kt" | head -1)"
EXPECTED_CODE=$((BASE_CODE * 10 + 2))   # arm64-v8a abiId = 2

AAPT="$(find /opt/android-sdk/build-tools -name aapt 2>/dev/null | sort -V | tail -1)"
[ -z "$AAPT" ] && AAPT="$(find "$ANDROID_HOME" -name aapt 2>/dev/null | sort -V | tail -1)"
[ -z "$AAPT" ] && { echo "FAIL: 找不到 aapt" >&2; exit 1; }

INFO="$("$AAPT" dump badging "$APK" 2>/dev/null | grep '^package:' | head -1)"
ACTUAL_CODE="$(printf '%s' "$INFO" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
ACTUAL_NAME="$(printf '%s' "$INFO" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"

echo "APK      : $APK"
echo "声明     : baseVersionCode=$BASE_CODE  releaseVersionName=$REL_NAME"
echo "期望     : versionCode=$EXPECTED_CODE  versionName=$REL_NAME"
echo "实际     : versionCode=$ACTUAL_CODE  versionName=$ACTUAL_NAME"

[ "$ACTUAL_CODE" = "$EXPECTED_CODE" ] || { echo "FAIL: versionCode 不一致（$ACTUAL_CODE != $EXPECTED_CODE）" >&2; exit 1; }
[ "$ACTUAL_NAME" = "$REL_NAME" ] || { echo "FAIL: versionName 不一致（$ACTUAL_NAME != $REL_NAME）" >&2; exit 1; }
echo "OK: 版本元数据一致"
