#!/usr/bin/env bash
# Device Lab DV1/DV2 — collect a zipped debug bundle (logcat, app + device metadata, dumpsys)
# from the connected device/emulator into artifacts/device-lab/. Bound to the "Collect Debug
# Bundle" shared run configuration under .run/.
#
# Usage:
#   scripts/device-lab/collect-debug-bundle.sh [--serial <id>]
set -euo pipefail

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb || true

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGE="com.v2rayez.app"
OUT_DIR="$ROOT/artifacts/device-lab"
mkdir -p "$OUT_DIR"

ADB_ARGS=()
if [[ "${1:-}" == "--serial" ]]; then
  ADB_ARGS+=(-s "$2")
  shift 2
fi

command -v adb >/dev/null 2>&1 || {
  echo "warn: adb not on PATH — writing host-only dry-run bundle"
  TS="$(date -u +%Y%m%dT%H%M%SZ)"
  ZIP="$OUT_DIR/V2RayEz-debug-bundle-$TS.zip"
  {
    echo "dry_run: true"
    echo "reason: adb missing"
    echo "collected_at_utc: $TS"
    echo "hint: connect an emulator and re-run for full logcat/dumpsys"
  } >"$OUT_DIR/dry-run-meta-$TS.txt"
  (cd "$OUT_DIR" && zip -q "$ZIP" "dry-run-meta-$TS.txt" 2>/dev/null) || cp "$OUT_DIR/dry-run-meta-$TS.txt" "${ZIP%.zip}.txt"
  echo "Wrote $ZIP (or .txt fallback)"
  exit 0
}

TS="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BUNDLE_DIR="$WORK/debug-bundle-$TS"
mkdir -p "$BUNDLE_DIR"

echo "Collecting device debug bundle -> $BUNDLE_DIR"

# ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} (not "${ADB_ARGS[@]}") — macOS ships bash 3.2, where
# expanding an empty array under `set -u` throws "unbound variable"; this is the portable guard.
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} logcat -d >"$BUNDLE_DIR/logcat-full.txt" 2>&1 || true
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell dumpsys package "$PACKAGE" >"$BUNDLE_DIR/dumpsys-package.txt" 2>&1 || true
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell dumpsys activity services "$PACKAGE" >"$BUNDLE_DIR/dumpsys-services.txt" 2>&1 || true
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell dumpsys connectivity >"$BUNDLE_DIR/dumpsys-connectivity.txt" 2>&1 || true
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell getprop >"$BUNDLE_DIR/device-props.txt" 2>&1 || true
adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell pm list packages -f "$PACKAGE" >"$BUNDLE_DIR/apk-path.txt" 2>&1 || true

{
  echo "package: $PACKAGE"
  echo "collected_at_utc: $TS"
  adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell dumpsys package "$PACKAGE" 2>/dev/null | grep -m1 -E "versionName|versionCode" || true
} >"$BUNDLE_DIR/manifest.txt"

ZIP_PATH="$OUT_DIR/debug-bundle-$TS.zip"
( cd "$WORK" && zip -q -r "$ZIP_PATH" "debug-bundle-$TS" )

echo "Wrote $ZIP_PATH"
