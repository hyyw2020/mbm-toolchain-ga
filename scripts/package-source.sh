#!/usr/bin/env bash
# MBM 可复现源码包脚本（v1.0.87 工程化）
#
# 取代历史「手工复制旧清单」的做法：以 `git ls-files`（权威追踪集）为准，
# 排除第三方组件（.gitmodules 声明的子模块树 + 未登记子模块的 vendored 第三方
# + 第三方模型二进制），再用确定性的 tar/gzip 参数产出**逐字节可复现**的源码包。
#
# 用法: scripts/package-source.sh [VERSION] [OUTDIR]
#   VERSION  缺省取 Versions.kt 的 releaseVersionName
#   OUTDIR   缺省为仓库根目录
# 产物:
#   OUTDIR/mbm-src-VERSION.tar.gz     确定性源码包（MBM 自研源码 + 第三方清单 + 源码说明）
#   OUTDIR/THIRD_PARTY_SOURCES.md     第三方依赖清单（可复现性说明）
#   OUTDIR/SOURCE_NOTICE.md           源码快照说明
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="${1:-}"
[ -z "$VERSION" ] && VERSION="$(sed -n 's/.*const val releaseVersionName = "\([^"]*\)".*/\1/p' \
    build-logic/convention/src/main/kotlin/Versions.kt | head -1)"
OUTDIR="${2:-$REPO_ROOT}"
mkdir -p "$OUTDIR"

BASE_CODE="$(sed -n 's/.*const val baseVersionCode = \([0-9]*\).*/\1/p' \
    build-logic/convention/src/main/kotlin/Versions.kt | head -1)"
COMMIT="$(git rev-parse HEAD)"
ARM64_CODE=$((BASE_CODE * 10 + 2))   # abiId: arm64-v8a = 2

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
TOP="mbm-src-$VERSION"
mkdir -p "$STAGE/$TOP"

echo "==> 采集 git 追踪文件集（权威源）"
# 未提交的改动/新文件不会进入 git ls-files —— 打包前必须先 commit（发布流程保证）。
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  echo "警告: 工作区有未提交改动，源码包将只含已提交内容。请先 commit 再打包。"
fi
# -z：以 NUL 分隔输出原始字节路径，避免 core.quotePath 对非 ASCII 文件名做引号+八进制
# 转义（否则 cp 会拿到带字面引号的错误路径）。本仓库无含换行的路径，tr 还原安全。
git ls-files -z | tr '\0' '\n' > "$STAGE/all.txt"

echo "==> 构造排除集（第三方组件）"
: > "$STAGE/excludes.txt"
if [ -f .gitmodules ]; then
  sed -n 's/^[[:space:]]*path[[:space:]]*=[[:space:]]*//p' .gitmodules >> "$STAGE/excludes.txt"
fi
# 未登记为子模块、但属于第三方 vendored 树
echo "lib/handwritten/src/main/cpp/ncnn" >> "$STAGE/excludes.txt"

# 生成保留文件清单：排除目录前缀命中；再排除第三方 .onnx 模型二进制（保留 MBM 自研
# 的 ocr_corrections.txt / ppocrv6_dict.txt）
awk '
  NR==FNR { ex[$0]=1; next }
  {
    keep=1
    for (e in ex) { if ($0 == e || index($0, e "/") == 1) { keep=0; break } }
    if (keep && $0 !~ /\.onnx$/) print $0
  }
' "$STAGE/excludes.txt" "$STAGE/all.txt" > "$STAGE/files.txt"

echo "==> 复制文件到暂存区（$(wc -l < "$STAGE/files.txt") 个）"
while IFS= read -r rel; do
  [ -z "$rel" ] && continue
  mkdir -p "$STAGE/$TOP/$(dirname "$rel")"
  cp -p "$rel" "$STAGE/$TOP/$rel"
done < "$STAGE/files.txt"

echo "==> 生成第三方依赖清单与源码说明"
{
  echo "# MBM v$VERSION 第三方依赖清单（可复现性说明）"
  echo ""
  echo "本源码包仅含 MBM 自研源码；下列第三方组件未打包，需从其上游获取以完整重建。"
  echo ""
  echo "## Git 子模块（第三方上游）"
  echo ""
  echo "| 路径 | 上游 |"
  echo "| --- | --- |"
  if [ -f .gitmodules ]; then
    awk '
      /^[[:space:]]*path = / { sub(/^[[:space:]]*path = /, ""); p=$0 }
      /^[[:space:]]*url = /  { sub(/^[[:space:]]*url = /, ""); print "| " p " | " $0 " |" }
    ' .gitmodules
  fi
  echo ""
  echo "## 未登记子模块的 vendored 第三方"
  echo ""
  echo "- \`lib/handwritten/src/main/cpp/ncnn\`（NCNN 推理库，上游 <https://github.com/Tencent/ncnn>）"
  echo ""
  echo "## 第三方模型二进制（未打包，可从上游重新获取）"
  echo ""
  echo "- \`app/src/main/assets/ocr/*.onnx\`（PP-OCR 识别模型，PaddleOCR / RapidOCR 上游）"
  echo ""
  echo "## 完整重建"
  echo ""
  echo "本仓库以普通文件形式 vendor 了全部第三方依赖（这也是完整 git archive 约 1GB 的原因）。"
  echo "完整可重建源码 = \`git clone\` 本仓库后按构建说明书构建；本包仅用于 AGPL 源码交付。"
  echo "生成提交：$COMMIT"
} > "$STAGE/$TOP/THIRD_PARTY_SOURCES.md"

{
  echo "# MBM v$VERSION 源码快照说明"
  echo ""
  echo "由 \`scripts/package-source.sh\` 确定性生成（以 \`git ls-files\` 为权威集，排除第三方组件）。"
  echo ""
  echo "- 版本：v$VERSION"
  echo "- versionCode（arm64-v8a）：$ARM64_CODE"
  echo "- 提交：$COMMIT"
  echo "- 许可：见 \`LICENSE\` / \`NOTICE\`；第三方许可与来源见 \`THIRD_PARTY_SOURCES.md\`"
} > "$STAGE/$TOP/SOURCE_NOTICE.md"

echo "==> 确定性打包（--sort=name --mtime=@0 --owner=0 + gzip -n）"
{
  printf '%s\n' "$TOP/THIRD_PARTY_SOURCES.md"
  printf '%s\n' "$TOP/SOURCE_NOTICE.md"
  while IFS= read -r rel; do [ -z "$rel" ] && continue; printf '%s\n' "$TOP/$rel"; done < "$STAGE/files.txt"
} | sort > "$STAGE/tarlist.txt"
(
  cd "$STAGE"
  tar cf - --no-recursion --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    -T tarlist.txt | gzip -n -9 > "$OUTDIR/mbm-src-$VERSION.tar.gz"
)

cp -p "$STAGE/$TOP/THIRD_PARTY_SOURCES.md" "$OUTDIR/THIRD_PARTY_SOURCES.md"
cp -p "$STAGE/$TOP/SOURCE_NOTICE.md" "$OUTDIR/SOURCE_NOTICE.md"

echo "==> 校验"
echo "文件数（条目）: $(tar tzf "$OUTDIR/mbm-src-$VERSION.tar.gz" | wc -l)"
echo "大小: $(du -h "$OUTDIR/mbm-src-$VERSION.tar.gz" | cut -f1)"
echo "目录条目数: $(tar tvzf "$OUTDIR/mbm-src-$VERSION.tar.gz" | grep -c '^d' || true)"
echo "版本代码(arm64) 声明: $ARM64_CODE"
echo "产物:"
echo "  $OUTDIR/mbm-src-$VERSION.tar.gz"
echo "  $OUTDIR/THIRD_PARTY_SOURCES.md"
echo "  $OUTDIR/SOURCE_NOTICE.md"
