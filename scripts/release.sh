#!/usr/bin/env bash
# MBM 发布脚本（幂等，分阶段）（v1.0.87 工程化）
#
# 用法: scripts/release.sh {test|build|sign|package|publish}
#   各阶段可重复执行；publish 前置依赖 test/build/sign/package 均已通过。
#
# 环境变量（敏感值只从环境读取，绝不回显、绝不写入文件/日志）:
#   KEYSTORE_FILE   签名 keystore 路径（默认 /tmp/mbm_new_key.jks）
#   KEY_ALIAS       签名 alias（默认 mbm_release）
#   KEY_PWD         签名密码（store/key 同密，sign 阶段必需）
#   OUTDIR          产物目录（默认 <repo>/dist/v<版本>）
#   GH_REPO         GitHub 仓库（默认 hyyw2020/mbm-toolchain-ga）
#   GITEE_OWNER     Gitee owner（默认 nlxxai）
#   GITEE_REPO      Gitee 仓库（默认 mixed-bug-method）
#   GITEE_TOKEN     Gitee 访问令牌（publish 阶段必需）
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="$(sed -n 's/.*const val releaseVersionName = "\([^"]*\)".*/\1/p' \
  build-logic/convention/src/main/kotlin/Versions.kt | head -1)"
BASE_CODE="$(sed -n 's/.*const val baseVersionCode = \([0-9]*\).*/\1/p' \
  build-logic/convention/src/main/kotlin/Versions.kt | head -1)"
ARM64_CODE=$((BASE_CODE * 10 + 2))     # abiId: arm64-v8a = 2
TAG="v$VERSION-toolchain-ga"
OUTDIR="${OUTDIR:-$REPO_ROOT/dist/v$VERSION}"

KEYSTORE_FILE="${KEYSTORE_FILE:-/tmp/mbm_new_key.jks}"
KEY_ALIAS="${KEY_ALIAS:-mbm_release}"
GH_REPO="${GH_REPO:-hyyw2020/mbm-toolchain-ga}"
GITEE_OWNER="${GITEE_OWNER:-nlxxai}"
GITEE_REPO="${GITEE_REPO:-mixed-bug-method}"
# 发布必须沿用同一张证书（可覆盖安装 v1.0.80+）；换证书会破坏升级，强制校验。
EXPECTED_CERT_SHA256="db2388887c20c05f11695d06a0dd5bbdefdb1a163faac4bdf69a5b5cb79cb8d0"

APK_NAME="org.fcitx.fcitx5.android-$VERSION-arm64-v8a-release.apk"

cmd="${1:-}"
[ -n "$cmd" ] || { echo "用法: scripts/release.sh {test|build|sign|package|publish}" >&2; exit 1; }

case "$cmd" in
  test)
    echo "==> 全量 JVM 单测"
    ./gradlew :app:testDebugUnitTest --no-daemon
    ;;

  build)
    echo "==> 构建 arm64 release"
    ./gradlew :app:assembleRelease -PBUILD_ABI=arm64-v8a --no-daemon
    ;;

  sign)
    echo "==> zipalign + apksigner 签名（版本 v$VERSION / code $ARM64_CODE）"
    BT="$(ls -d /opt/android-sdk/build-tools/* 2>/dev/null | sort -V | tail -1)"
    [ -n "${KEY_PWD:-}" ] || { echo "FAIL: 需要 KEY_PWD 环境变量" >&2; exit 1; }
    UNSIGNED="$(ls app/build/outputs/apk/release/*arm64-v8a-release-unsigned.apk 2>/dev/null | head -1 || true)"
    [ -n "$UNSIGNED" ] || { echo "FAIL: 找不到未签名 APK（先 build）" >&2; exit 1; }
    mkdir -p "$OUTDIR"
    ALIGNED="$OUTDIR/.aligned.apk"
    SIGNED="$OUTDIR/$APK_NAME"
    "$BT/zipalign" -p 4 "$UNSIGNED" "$ALIGNED"
    "$BT/apksigner" sign --ks "$KEYSTORE_FILE" --ks-key-alias "$KEY_ALIAS" \
      --ks-pass pass:"$KEY_PWD" --key-pass pass:"$KEY_PWD" --min-sdk-version 29 "$ALIGNED"
    cp "$ALIGNED" "$SIGNED"
    rm -f "$ALIGNED"
    echo "==> 校验签名证书 + 版本元数据"
    "$BT/apksigner" verify --print-certs "$SIGNED" | grep -q "$EXPECTED_CERT_SHA256" \
      || { echo "FAIL: 签名证书指纹与历史不符（须沿用同证书）" >&2; exit 1; }
    bash scripts/verify-version-metadata.sh "$SIGNED"
    echo "签名产物: $SIGNED"
    ;;

  package)
    echo "==> 打包可复现源码 + 校验和"
    [ -f "$OUTDIR/$APK_NAME" ] || { echo "FAIL: 先执行 sign" >&2; exit 1; }
    bash scripts/package-source.sh "$VERSION" "$OUTDIR"
    SRC="mbm-src-$VERSION.tar.gz"
    (cd "$OUTDIR" && sha256sum "$APK_NAME" "$SRC" > SHA256SUMS.txt)
    (cd "$OUTDIR" && tar czf "org.fcitx.fcitx5.android-$VERSION-arm64-v8a-release.tar.gz" "$APK_NAME" SHA256SUMS.txt)
    (cd "$OUTDIR" && sha256sum -c SHA256SUMS.txt)
    echo "==> 产物（$OUTDIR）"
    ls -la "$OUTDIR"
    ;;

  publish)
    echo "==> 提交/tag + 双平台发布（tag $TAG）"
    [ -f "$OUTDIR/$APK_NAME" ] || { echo "FAIL: 先执行 sign" >&2; exit 1; }
    [ -f "$OUTDIR/mbm-src-$VERSION.tar.gz" ] || { echo "FAIL: 先执行 package" >&2; exit 1; }
    [ -f "$OUTDIR/RELEASE_NOTES-$VERSION.md" ] || { echo "FAIL: 缺发布说明 $OUTDIR/RELEASE_NOTES-$VERSION.md" >&2; exit 1; }

    # 1) 本地 tag（幂等：已存在则跳过）
    git tag -a "$TAG" -m "$TAG — 工程化加固" HEAD 2>/dev/null || echo "tag 已存在，跳过"

    # 2) 推送到 GitHub
    git -c http.postBuffer=524288000 push origin main
    git -c http.postBuffer=524288000 push origin "$TAG" 2>/dev/null || echo "远端 tag 已存在"

    # 3) GitHub Release（幂等：已存在则只补资产）
    if gh release view "$TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
      echo "GitHub Release 已存在，跳过创建"
    else
      gh release create "$TAG" --repo "$GH_REPO" \
        --title "v$VERSION — 工程化加固（确定性版本元数据 + 可复现源码包 + 发布自动化 + CI 门禁）" \
        --notes-file "$OUTDIR/RELEASE_NOTES-$VERSION.md"
    fi
    gh release upload "$TAG" --repo "$GH_REPO" --clobber \
      "$OUTDIR/$APK_NAME" \
      "$OUTDIR/org.fcitx.fcitx5.android-$VERSION-arm64-v8a-release.tar.gz" \
      "$OUTDIR/mbm-src-$VERSION.tar.gz" \
      "$OUTDIR/SHA256SUMS.txt"

    # 4) Gitee（需要 GITEE_TOKEN）；推送输出脱敏，绝不把 token 回显到日志
    [ -n "${GITEE_TOKEN:-}" ] || { echo "跳过 Gitee：未提供 GITEE_TOKEN" >&2; exit 0; }
    git push "https://${GITEE_OWNER}:${GITEE_TOKEN}@gitee.com/${GITEE_OWNER}/${GITEE_REPO}.git" main:main 2>&1 \
      | sed -E "s/${GITEE_TOKEN}/<REDACTED>/g"

    # Gitee Release 创建 + 上传（源码包/文档/校验和；APK 超 100MB 限制，正文指向 GitHub）
    python3 - "$GITEE_TOKEN" "$VERSION" "$TAG" "$OUTDIR" "$GITEE_OWNER" "$GITEE_REPO" "$(git rev-parse HEAD)" <<'PY'
import sys, json, urllib.request, urllib.parse, os, subprocess
tok, version, tag, outdir, owner, repo, commit = sys.argv[1:8]
body = open(os.path.join(outdir, f'RELEASE_NOTES-gitee-body.md'), encoding='utf-8').read()
data = {'access_token': tok, 'tag_name': tag,
        'name': f'v{version} — 双平台同步（工程化加固）', 'body': body,
        'target_commitish': commit, 'prerelease': False}
base = f'https://gitee.com/api/v5/repos/{owner}/{repo}'
req = urllib.request.Request(f'{base}/releases',
    data=urllib.parse.urlencode(data).encode('utf-8'), method='POST')
try:
    with urllib.request.urlopen(req, timeout=40) as r:
        rid = json.load(r)['id']; print('Gitee Release id=', rid)
except urllib.error.HTTPError as e:
    print('Gitee 创建失败:', e.code, e.read().decode('utf-8','ignore')[:400]); sys.exit(1)
for f in [f'mbm-src-{version}.tar.gz', 'SHA256SUMS.txt',
          f'RELEASE_NOTES-{version}.md', f'升级必读-{version}.md']:
    p = os.path.join(outdir, f)
    if not os.path.exists(p):
        print('跳过缺失资产', f); continue
    subprocess.run(['curl', '-s', '--max-time', '300', '-X', 'POST',
        f'{base}/releases/{rid}/attach_files',
        '-F', f'access_token={tok}', '-F', f'file=@{p}'], check=False)
print('Gitee 上传完成')
PY
    echo "==> 发布完成: $TAG"
    ;;

  *)
    echo "用法: scripts/release.sh {test|build|sign|package|publish}" >&2
    exit 1
    ;;
esac
