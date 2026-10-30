# -*- coding: utf-8 -*-
"""A/B 实验：det→NMS/合并 两种顺序，用真实 PP-OCRv6 模型把 30 场景跑两遍。

(a) merge_nms = 现状（Kotlin v1.0.30 顺序）: detect → 同行合并 → NMS
(b) nms_merge = 候选1:                       detect → NMS → 同行合并

输出：
  - 逐场景 pass/fail 对照（点名差异）
  - 每场景 NMS 实际抑制框数（逐场景 + 总计，两种顺序各一份）
判据（用户 Step 1 反馈）：(b)pass >= (a)pass 且 (a)抑制数 > 0 → 候选1 可提 Kotlin；
否则采纳候选2（只改注释，Kotlin 顺序不动）。
抑制数为 0 时专项回答：现状 NMS 对「DB 重叠重复框」是否完全不起作用。

注意：镜像 warpCrop（warp_crop_fixed）本就 clamp，与 Kotlin 一致；候选1 若要
落地 Kotlin，边缘 1px 等细微差异仍需真机复验（用户 Step 1 反馈已注明）。
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS_JSON = os.path.join(HERE, "scan_results.json")


def run_scan(order):
    env = dict(os.environ, OCR_ORDER=order)
    print(f"\n########## 30 场景扫描：OCR_ORDER={order} ##########")
    subprocess.run([sys.executable, os.path.join(HERE, "scan_bugs.py")],
                   cwd=HERE, env=env, check=True)
    with open(RESULTS_JSON, encoding="utf-8") as fp:
        return json.load(fp)


def main():
    a = run_scan("merge_nms")   # 现状
    b = run_scan("nms_merge")   # 候选1
    ia = {(r["cat"], r["name"]): r for r in a}
    ib = {(r["cat"], r["name"]): r for r in b}
    keys = list(ia.keys())
    assert set(ia) == set(ib), "两遍场景集不一致，无法逐条对照"

    print("\n" + "=" * 84)
    print("逐场景对照（sup_a=现状顺序 NMS 抑制数, sup_b=候选顺序 NMS 抑制数）")
    print("=" * 84)
    print(f"{'场景':40s} {'ok_a':5s} {'ok_b':5s} {'sup_a':6s} {'sup_b':6s}")
    diffs = []
    for k in keys:
        ra, rb = ia[k], ib[k]
        oka = "OK" if ra["ok"] else ("FAIL" if ra["ok"] is False else "-")
        okb = "OK" if rb["ok"] else ("FAIL" if rb["ok"] is False else "-")
        flag = ""
        if ra["ok"] != rb["ok"]:
            flag = "  <== pass/fail 差异"
            diffs.append((k, ra, rb))
        print(f"{k[0] + '/' + k[1]:40s} {oka:5s} {okb:5s} "
              f"{ra['nms_suppressed']:<6d} {rb['nms_suppressed']:<6d}{flag}")

    pass_a = sum(1 for k in keys if ia[k]["ok"] is True)
    pass_b = sum(1 for k in keys if ib[k]["ok"] is True)
    sup_a = sum(ia[k]["nms_suppressed"] for k in keys)
    sup_b = sum(ib[k]["nms_suppressed"] for k in keys)
    n = len(keys)

    print("\n" + "=" * 84)
    print("汇总")
    print("=" * 84)
    print(f"  总场景: {n}")
    print(f"  (a) merge_nms 现状: pass {pass_a} / fail {n - pass_a}   "
          f"NMS 总抑制框数 = {sup_a}")
    print(f"  (b) nms_merge 候选: pass {pass_b} / fail {n - pass_b}   "
          f"NMS 总抑制框数 = {sup_b}")
    print(f"  pass 差值 (b - a) = {pass_b - pass_a:+d}")

    print("\n----- pass/fail 差异逐条点名 -----")
    if not diffs:
        print("  （无差异：两顺序 pass/fail 完全一致）")
    else:
        for k, ra, rb in diffs:
            print(f"  {k[0]}/{k[1]}:")
            print(f"      (a) ok={ra['ok']} got={ra['got']}")
            print(f"      (b) ok={rb['ok']} got={rb['got']}")

    print("\n----- 判据 -----")
    cond1 = pass_b >= pass_a
    cond2 = sup_a > 0
    print(f"  条件1 (b)pass({pass_b}) >= (a)pass({pass_a}) : {'满足' if cond1 else '不满足'}")
    print(f"  条件2 (a)NMS 抑制数({sup_a}) > 0            : {'满足' if cond2 else '不满足'}")
    if cond1 and cond2:
        print("  => 候选1（nms→merge）可提到 Kotlin（仍需真机复验）")
    else:
        print("  => 采纳候选2（只改注释，Kotlin 顺序不动）")

    if sup_a == 0:
        print("\n----- 专项回答：现状顺序 NMS 是否完全不起作用 -----")
        print(f"  现状(a) NMS 总抑制框数 = {sup_a}（每场景均抑制 0 框）")
        print("  结论：是——现状顺序下 NMS 抑制了 0 个框，对「DB 重叠重复框」完全不起作用。")
        if sup_b == 0:
            print("  且候选顺序(b) NMS 也抑制 0 个框——即：即便把 NMS 提到 merge 之前，")
            print("  这 30 个合成场景里也没有任何 DB 重叠重复框可抑制。")
            print("  => v1.0.30 引入 NMS 的立论前提（DB 密集处输出重叠框）未被本套件复现；")
            print("     NMS 在本回归套件中未被端到端触发（其 IoU 逻辑有单测覆盖、正确）。")
            print("     真实密集游戏截图是否会触发 DB 重叠框 = 不确定，需真机验证。")
            print("  => 候选1（nms→merge 重排）在此数据下无收益（无可抑制对象），不采纳。")
        else:
            print(f"  对照：候选顺序(b) NMS 抑制 {sup_b} 个框——DB 确实产生了重叠重复框，")
            print("        只是在现状顺序里被 merge 先吃掉，重排到 NMS 先行才有意义。")

    out = os.path.join(HERE, "ab_order_result.json")
    with open(out, "w", encoding="utf-8") as fp:
        json.dump({
            "total": n, "pass_a": pass_a, "pass_b": pass_b,
            "sup_a": sup_a, "sup_b": sup_b,
            "diffs": [{"cat": k[0], "name": k[1],
                       "ok_a": ra["ok"], "ok_b": rb["ok"]} for k, ra, rb in diffs],
        }, fp, ensure_ascii=False, indent=1)
    print(f"\n已存 {out}")


if __name__ == "__main__":
    main()
