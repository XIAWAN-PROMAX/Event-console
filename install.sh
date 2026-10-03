#!/usr/bin/env bash
#
# MatchConsole 一键安装脚本（Linux / macOS）
#   - 自动检测 adb
#   - 从最新 Release 下载 APK（或使用本地已构建产物）
#   - 安装到当前通过 USB 连接的所有设备
#
# 用法：
#   ./install.sh                                  下载最新 Release 并安装
#   ./install.sh --apk path/to/app-debug.apk      安装指定 APK
#   ./install.sh --tag v1.0                       指定 Release 版本
#   ./install.sh --uninstall                      卸载已安装的 App
#
set -euo pipefail

REPO="${MATCHCONSOLE_REPO:-XIAWAN-PROMAX/Event-console}"
PKG="com.matchconsole"
APK=""
TAG=""
TMP_DIR=""
UNINSTALL=0

info()  { printf '\033[32m[MatchConsole]\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m[MatchConsole]\033[0m %s\n' "$*"; }
fail()  { printf '\033[31m[MatchConsole]\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() { if [ -n "$TMP_DIR" ]; then rm -rf "$TMP_DIR"; fi; }
trap cleanup EXIT

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)       APK="${2:-}"; shift 2 ;;
    --tag)       TAG="${2:-}"; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help)   usage ;;
    *)           fail "未知参数：$1（用 --help 查看用法）" ;;
  esac
done

# ---------- 1. 检测 adb ----------
if ! command -v adb >/dev/null 2>&1; then
  warn "未检测到 adb，请先安装 Android Platform Tools："
  warn "  https://developer.android.com/tools/releases/platform-tools"
  fail "安装后把 platform-tools 加入 PATH，再重新运行本脚本。"
fi
info "adb：$(command -v adb)"

# ---------- 2. 检测设备 ----------
DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$DEVICES" ]; then
  fail "没有检测到已授权的设备。请连接手机、开启「USB 调试」并在手机上允许调试授权。"
fi
info "检测到设备：$(echo "$DEVICES" | tr '\n' ' ')"

# ---------- 3. 卸载模式 ----------
if [ "$UNINSTALL" = "1" ]; then
  while read -r d; do
    [ -n "$d" ] || continue
    info "卸载 $PKG（设备 $d）..."
    adb -s "$d" uninstall "$PKG" || warn "设备 $d 卸载失败或未安装。"
  done <<< "$DEVICES"
  info "完成。"
  exit 0
fi

# 解析 Release 里 APK 的下载地址（无需 API，避免限流）
resolve_apk_url() {
  local tag="$1" page url
  if [ -z "$tag" ]; then
    page="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest" || true)"
    tag="${page##*/}"
  fi
  [ -n "$tag" ] || return 1
  url="$(curl -fsSL "https://github.com/$REPO/releases/expanded_assets/$tag" \
          | grep -o '/[^"]*\.apk' | head -n1 || true)"
  [ -n "$url" ] || return 1
  printf 'https://github.com%s\n' "$url"
}

# ---------- 4. 准备 APK ----------
if [ -n "$APK" ]; then
  [ -f "$APK" ] || fail "找不到 APK 文件：$APK"
  info "使用本地 APK：$APK"
else
  TMP_DIR="$(mktemp -d)"
  if command -v gh >/dev/null 2>&1; then
    info "通过 gh 下载 Release（${TAG:-latest}）的 APK ..."
    if [ -n "$TAG" ]; then
      gh release download "$TAG" --repo "$REPO" --pattern "*.apk" --dir "$TMP_DIR" --clobber
    else
      gh release download --repo "$REPO" --pattern "*.apk" --dir "$TMP_DIR" --clobber
    fi
    APK="$(find "$TMP_DIR" -name '*.apk' | head -n1 || true)"
  else
    info "解析 Release（${TAG:-latest}）的 APK 下载地址 ..."
    URL="$(resolve_apk_url "$TAG" || true)"
    [ -n "$URL" ] || fail "未能获取 Release APK 下载地址。请检查网络，或用 --apk 指定本地 APK。"
    APK="$TMP_DIR/$(basename "$URL")"
    info "下载：$URL"
    curl -fL --progress-bar "$URL" -o "$APK"
  fi
  if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    fail "APK 下载失败。可改用 --apk 指定本地 APK。"
  fi
fi

# ---------- 5. 安装 ----------
while read -r d; do
  [ -n "$d" ] || continue
  info "安装到设备 $d ..."
  adb -s "$d" install -r "$APK"
done <<< "$DEVICES"

info "全部完成！两台手机分别插上运行一次本脚本即可。"
info "提示：可在同一 Wi-Fi 下打开 App → 接收端 / 发送端 开始使用。"