#!/usr/bin/env bash
# Device Lab DV2/DV3 — push a Xeovo subscription onto the connected device/emulator for the
# in-app "Import from file" flow (Servers screen FAB → Import → File), so a tester never has to
# type or paste a raw subscription URL by hand.
#
# SECURITY: this script NEVER hard-codes a subscription body. It only reads one from:
#   1. --file <path>   a local file (gitignored / outside the repo), or
#   2. $XEOVO_SUB_URL  an environment variable set in the tester's own shell
# Nothing this script touches may be committed — do not add real subscription bodies to git.
#
# Usage:
#   XEOVO_SUB_URL="https://.../sub" scripts/device-lab/xeovo-push-sub.sh
#   scripts/device-lab/xeovo-push-sub.sh --file /path/to/local-sub.txt
set -euo pipefail

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb

DEVICE_PATH="/sdcard/Download/xeovo-sub.txt"
SRC_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file) SRC_FILE="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

ADB_ARGS=()
[[ -n "${SERIAL:-}" ]] && ADB_ARGS+=(-s "$SERIAL")

command -v adb >/dev/null 2>&1 || { echo "error: adb not on PATH" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PAYLOAD="$WORK/xeovo-sub.txt"

if [[ -n "$SRC_FILE" ]]; then
  [[ -f "$SRC_FILE" ]] || { echo "error: --file $SRC_FILE not found" >&2; exit 1; }
  cp "$SRC_FILE" "$PAYLOAD"
elif [[ -n "${XEOVO_SUB_URL:-}" ]]; then
  printf '%s\n' "$XEOVO_SUB_URL" >"$PAYLOAD"
else
  echo "error: provide --file <path> or set \$XEOVO_SUB_URL (never commit either)" >&2
  exit 1
fi

# ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} (not "${ADB_ARGS[@]}") — macOS ships bash 3.2, where
# expanding an empty array under `set -u` throws "unbound variable"; this is the portable guard.
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell mkdir -p /sdcard/Download
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} push "$PAYLOAD" "$DEVICE_PATH" >/dev/null
echo "Pushed subscription payload to $DEVICE_PATH on device."
echo ""
echo "Next in the app:"
echo "  1. Servers tab -> FAB (+) -> Import -> File"
echo "  2. Pick Download/xeovo-sub.txt"
echo "  3. Confirm the imported group name matches what you expect, then delete the file:"
echo "       adb ${ADB_ARGS[*]:-} shell rm -f $DEVICE_PATH"
