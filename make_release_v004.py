#!/usr/bin/env python3
"""MBM Gitee 发布脚本：创建 release 并上传文档附件（大 APK 本地交付，不上传）。"""
import json
import mimetypes
import os
import sys
import uuid
import urllib.request
import urllib.error

REPO = "nlxxai/mixed-bug-method"
# 安全：令牌不得硬编码进仓库。请通过环境变量注入：
#   GITEE_TOKEN=xxxx python3 make_release.py <tag> <name>
TOKEN = os.environ.get("GITEE_TOKEN", "").strip()
if not TOKEN:
    print(
        "ERROR: 未检测到环境变量 GITEE_TOKEN（Gitee 私人令牌）。\n"
        "用法：GITEE_TOKEN=xxxx python3 make_release.py <tag> <name>\n"
        "说明：令牌属敏感凭据，禁止写入源码或文档。"
    )
    sys.exit(1)
BRANCH = "main"
BASE = "https://gitee.com/api/v5"

# 文档相对脚本所在仓库根目录定位（不再硬编码绝对路径，便于跨环境）。
ROOT = os.path.dirname(os.path.abspath(__file__))
TAG = sys.argv[1] if len(sys.argv) > 1 else "v0.0.05"
NAME = sys.argv[2] if len(sys.argv) > 2 else "v0.0.05"
DOCS = [
    ("CLAUDE.md", "CLAUDE.md"),
    ("docs/pitfalls.md", "pitfalls.md"),
    ("构建说明书.txt", "构建说明书.txt"),
]


def api(method, path, payload=None, timeout=120):
    url = f"{BASE}{path}"
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8", "replace")[:500]}


# 1. 创建 release（若已存在则复用）
status, rel = api("POST", f"/repos/{REPO}/releases", {
    "access_token": TOKEN,
    "tag_name": TAG,
    "name": NAME,
    "body": (
        f"MBM {NAME}：修复 4 个真 bug（安全 + 内存）。"
        "① Rime zip 导入分支无 Zip Slip 防护，条目名含 ../ 可逃逸目标目录覆盖 app 数据库/配置（#53）；"
        "② DISPLAY_NAME 未净化，单文件词库导入可被路径分隔符注入（#54）；"
        "③ 设置页手写入口 snapshot() 位图副本每笔泄漏一张全幅位图（#52）；"
        "④ ZipStream 前缀比较缺尾分隔符，兄弟目录被误放行（#53）。"
        "详见附件 pitfalls.md 第 52–54 条与 构建说明书.txt。APK 体积较大（约 104–173MB）不挂附件（Gitee 单文件限制 1GB，APK 走本地分发），请本地安装。"
    ),
    "target_commitish": BRANCH,
})
if status >= 300 and "tag_name" not in str(rel):
    # 可能已存在：查询列表找同 tag
    status, rels = api("GET", f"/repos/{REPO}/releases?access_token={TOKEN}&per_page=100")
    rel = next((r for r in rels if r.get("tag_name") == TAG), None)
    if rel is None:
        print("CREATE FAILED:", status, rel)
        sys.exit(1)
    print("reuse existing release id", rel.get("id"))
else:
    print("release id", rel.get("id"))

rid = rel["id"]

# 2. 上传文档附件
for src, disp in DOCS:
    path = os.path.join(ROOT, src)
    if not os.path.exists(path):
        print("MISSING", path)
        continue
    boundary = uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    ctype = mimetypes.guess_type(path)[0] or "text/plain"
    pre = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="access_token"\r\n\r\n{TOKEN}\r\n'
    ).encode()
    mid = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{disp}"\r\n'
        f"Content-Type: {ctype}\r\n\r\n"
    ).encode()
    end = f"\r\n--{boundary}--\r\n".encode()
    body = pre + mid + content + end
    req = urllib.request.Request(
        f"{BASE}/repos/{REPO}/releases/{rid}/attach_files",
        data=body,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            print("UPLOADED", disp, r.status)
    except urllib.error.HTTPError as e:
        print("UPLOAD FAILED", disp, e.code, e.read().decode("utf-8", "replace")[:300])

print("DONE", TAG)
