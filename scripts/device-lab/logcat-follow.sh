#!/usr/bin/env bash
# Device Lab DV2/DV3 — tail logcat scoped to this app's process + the tags we actually care about,
# instead of firehosing the whole device log.
#
# Usage:
#   scripts/device-lab/logcat-follow.sh [--serial <id>] [extra logcat args...]
set -euo pipefail

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb

PACKAGE="com.v2rayez.app"
ADB_ARGS=()

if [[ "${1:-}" == "--serial" ]]; then
  ADB_ARGS+=(-s "$2")
  shift 2
fi

command -v adb >/dev/null 2>&1 || { echo "error: adb not on PATH" >&2; exit 1; }

# ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} (not "${ADB_ARGS[@]}") — macOS ships bash 3.2, where
# expanding an empty array under `set -u` throws "unbound variable"; this is the portable guard.
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} logcat -c

PID="$(adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [[ -n "$PID" ]]; then
  echo "Following logcat for $PACKAGE (pid $PID)..."
  exec adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} logcat --pid="$PID" "$@"
fi

echo "App not running yet — falling back to tag filter (V2RayEz*, Tor*, AddonPackManager, ByeDpi, hev*)."
exec adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} logcat \
  "V2RayVpnService:V" "TorController:V" "NativeCTorEngine:V" "TorBridgeProber:V" \
  "AddonPackManager:V" "ByeDpiEngine:V" "MitmProxyService:V" "hev-socks5-tunnel:V" \
  "AndroidRuntime:E" "*:S" "$@"
