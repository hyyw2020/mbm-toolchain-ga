#!/usr/bin/env bash
# ============================================================================
# MBM v1.0.36 输入法 adb 自动测试：遍历所有可点击功能，逐一真实点击并断言日志。
#
# 前置条件：
#   * adb 已连接设备且已授权
#   * 小企鹅输入法已被设为当前输入法
#   * 设备上已有一个可输入框处于聚焦状态（输入法键盘已显示）
#   * 输入法版本含 MbmDebug（content-desc="mbm:feature:*" 标识）
#
# 用法：
#   ./mbm_adb_test.sh            # 用默认 adb
#   ADB="adb -s <serial>" ./mbm_adb_test.sh
#
# 原理：
#   1) uiautomator dump 抓当前输入法窗口层级
#   2) 解析所有 content-desc 以 "mbm:feature:" 开头的节点，取屏幕中心坐标
#   3) 对每个节点 adb shell input tap 真实点击，随后 logcat -d 断言
#      "MBM-DBG: feature=<id>" 出现 -> PASS，否则 FAIL
#   4) 最后注入一组常用物理按键（键盘键位）
# ============================================================================
set -u
ADB="${ADB:-adb}"
DUMP=/sdcard/mbm_ui.xml
LOCAL=/tmp/mbm_ui.xml
FEATURES=/tmp/mbm_features.txt
PASS=0
FAIL=0
FAILED=()

echo "== [1/4] 确保输入法窗口在前台并 dump 窗口层级 =="
$ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 1
if ! $ADB shell uiautomator dump "$DUMP" 2>/dev/null | grep -q "dumped"; then
  echo "!! uiautomator dump 失败（请确认输入框已聚焦、键盘已显示）"
  exit 1
fi
$ADB pull "$DUMP" "$LOCAL" >/dev/null 2>&1

echo "== [2/4] 解析 mbm:feature 节点 =="
python3 - "$LOCAL" "$FEATURES" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
out = []
for m in re.finditer(r'content-desc="(mbm:feature:[^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    desc = m.group(1)
    x1, y1, x2, y2 = map(int, m.group(2, 3, 4, 5))
    out.append((desc, (x1 + x2) // 2, (y1 + y2) // 2))
with open(sys.argv[2], 'w') as f:
    for d, x, y in out:
        f.write(f"{d}\t{x}\t{y}\n")
print(f"找到 {len(out)} 个可点击节点")
if not out:
    sys.exit(2)
PY
if [ $? -eq 2 ]; then
  echo "!! 未发现 mbm:feature 节点，请确认安装了含 MbmDebug 的 APK"
  exit 2
fi

echo "== [3/4] 逐个真实点击并断言日志 =="
while IFS=$'\t' read -r desc x y; do
  id="${desc#mbm:feature:}"
  $ADB logcat -c 2>/dev/null
  $ADB shell input tap "$x" "$y"
  sleep 0.6
  if $ADB logcat -d -s MBM-DBG 2>/dev/null | grep -q "feature=$id"; then
    echo "PASS  $id  ($x,$y)"
    PASS=$((PASS + 1))
  else
    echo "FAIL  $id  ($x,$y)"
    FAIL=$((FAIL + 1))
    FAILED+=("$id")
  fi
done < "$FEATURES"

echo "== [4/4] 键盘物理按键注入 =="
for k in KEYCODE_DEL KEYCODE_ENTER KEYCODE_SPACE KEYCODE_TAB \
         KEYCODE_DPAD_UP KEYCODE_DPAD_DOWN KEYCODE_DPAD_LEFT KEYCODE_DPAD_RIGHT \
         KEYCODE_CLEAR KEYCODE_MOVE_HOME KEYCODE_MOVE_END; do
  $ADB logcat -c 2>/dev/null
  $ADB shell input keyevent "$k" >/dev/null 2>&1
  sleep 0.3
  if $ADB logcat -d -s MBM-DBG 2>/dev/null | grep -q "feature=key.press"; then
    echo "PASS  keyevent $k (key.press)"
    PASS=$((PASS + 1))
  else
    echo "FAIL  keyevent $k (key.press)"
    FAIL=$((FAIL + 1))
    FAILED+=("keyevent:$k")
  fi
done

echo
echo "========================== 汇总 =========================="
echo "PASS=$PASS   FAIL=$FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "失败项："
  for f in "${FAILED[@]}"; do echo "  $f"; done
  exit 1
fi
echo "全部通过"
exit 0
