#!/usr/bin/env bash
# 在 runner 主机上安装固定版本的 open-code-review（ocr）二进制，并校验 sha256。
# 用法：sudo ./install-ocr.sh [安装目录，默认 /opt/x7x24-agent/bin]
set -euo pipefail
VERSION="v1.13.5"
SHA256="f9d918dd8b461719be975430e0f7396b4e642b465ffa490d651ace0d8c989171"
DEST_DIR="${1:-/opt/x7x24-agent/bin}"
DEST="$DEST_DIR/ocr-$VERSION"
URL="https://github.com/alibaba/open-code-review/releases/download/$VERSION/opencodereview-linux-amd64"
[ "$(uname -m)" = "x86_64" ] || { echo "只支持 linux-amd64" >&2; exit 1; }
if [ -x "$DEST" ] && echo "$SHA256  $DEST" | sha256sum -c --status; then echo "已安装：$DEST"; exit 0; fi
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
curl -fsSL --retry 3 -o "$tmp" "$URL"
echo "$SHA256  $tmp" | sha256sum -c --status || { echo "sha256 不匹配，拒绝安装" >&2; exit 1; }
install -d -m 0755 "$DEST_DIR"
install -m 0755 "$tmp" "$DEST"
echo "已安装：$DEST（$VERSION）"
