#!/usr/bin/env bash
# Device Lab P8-conn-helper — quick connectivity/reachability smoke for the lab device *before*
# (or alongside) running xeovo-smoke-tui.sh / xeovo-adb-checks.sh / Maestro flows. Complements —
# does not replace — the in-app Diagnostics tool (Tools -> Diagnostics), which does the
# authoritative per-connection leak/latency checks from inside the app process. This script runs
# entirely from the host + adb, so it also catches "device has no network at all" before you
# waste a Maestro run on it.
#
# Usage:
#   scripts/device-lab/conn-helper.sh [--serial <id>] [--youtube] \
#       [--socks-port <n>] [--http-port <n>] [--pkg <applicationId>]
#
# Exit code: 0 if no FAIL, 1 if any FAIL (WARN doesn't fail the run).
set -uo pipefail

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb || true

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT/artifacts/device-lab"
mkdir -p "$OUT_DIR"

PACKAGE="com.v2rayez.app"
SOCKS_PORT=10808
HTTP_PORT=10809
CHECK_YOUTUBE=0
ADB_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) ADB_ARGS+=(-s "$2"); shift 2 ;;
    --youtube) CHECK_YOUTUBE=1; shift ;;
    --socks-port) SOCKS_PORT="$2"; shift 2 ;;
    --http-port) HTTP_PORT="$2"; shift 2 ;;
    --pkg) PACKAGE="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

TS="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$OUT_DIR/conn-helper-$TS.md"
pass=0; warn=0; fail=0

row() {
  # row <name> <status: PASS|WARN|FAIL> <detail>
  local name="$1" status="$2" detail="$3"
  case "$status" in
    PASS) pass=$((pass+1)); printf '  [PASS] %-28s %s\n' "$name" "$detail" ;;
    WARN) warn=$((warn+1)); printf '  [WARN] %-28s %s\n' "$name" "$detail" ;;
    FAIL) fail=$((fail+1)); printf '  [FAIL] %-28s %s\n' "$name" "$detail" ;;
  esac
  echo "| $name | $status | ${detail//|/;} |" >>"$REPORT"
}

{
  echo "# Connectivity helper — $TS"
  echo ""
  echo "| Check | Result | Detail |"
  echo "|-------|--------|--------|"
} >"$REPORT"

echo "Connectivity helper — $TS"

if ! command -v adb >/dev/null 2>&1; then
  row "adb-on-path" "FAIL" "adb not found (Android SDK / local.properties sdk.dir)"
  {
    echo ""
    echo "## Summary"
    echo "PASS=$pass WARN=$warn FAIL=$fail"
  } >>"$REPORT"
  echo "Wrote $REPORT (dry — adb missing)"
  exit 1
fi

if ! adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} get-state >/dev/null 2>&1; then
  row "device-attached" "FAIL" "no device/emulator responding to adb get-state"
  {
    echo ""
    echo "## Summary"
    echo "PASS=$pass WARN=$warn FAIL=$fail"
  } >>"$REPORT"
  echo "Wrote $REPORT (no device attached)"
  exit 1
fi
row "device-attached" "PASS" "$(adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

# ---- device raw internet reachability (ICMP to a well-known IP; independent of DNS) ----
if adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell ping -c 3 -W 3 8.8.8.8 >/dev/null 2>&1; then
  row "device-internet-icmp" "PASS" "8.8.8.8 replied"
else
  row "device-internet-icmp" "WARN" "no ICMP reply from 8.8.8.8 (may be policy-blocked, not necessarily offline)"
fi

# ---- device DNS resolution (ping by name; fails distinctly from the IP case above) ----
if adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell ping -c 2 -W 3 www.google.com >/dev/null 2>&1; then
  row "device-dns" "PASS" "www.google.com resolved + replied"
else
  row "device-dns" "WARN" "www.google.com did not resolve/reply over ICMP"
fi

# ---- app installed ----
if adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell pm list packages "$PACKAGE" 2>/dev/null | grep -q "$PACKAGE"; then
  row "app-installed" "PASS" "$PACKAGE present"
else
  row "app-installed" "FAIL" "$PACKAGE not installed — run ./gradlew :app:installDebug"
fi

# ---- MITM local proxy ports (only meaningful once the app has "Run local MITM proxy" ON) ----
check_local_port() {
  local label="$1" device_port="$2" host_port
  host_port=$((17000 + device_port % 1000))
  if adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} forward "tcp:$host_port" "tcp:$device_port" >/dev/null 2>&1; then
    if (exec 3<>"/dev/tcp/127.0.0.1/$host_port") 2>/dev/null; then
      exec 3>&- 3<&-
      row "$label" "PASS" "device:$device_port accepting connections"
    else
      row "$label" "WARN" "device:$device_port not accepting (proxy likely not running — start it from MITM Domain Fronting)"
    fi
    adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} forward --remove "tcp:$host_port" >/dev/null 2>&1 || true
  else
    row "$label" "WARN" "could not adb forward tcp:$host_port -> tcp:$device_port"
  fi
}
check_local_port "mitm-socks-port" "$SOCKS_PORT"
check_local_port "mitm-http-port" "$HTTP_PORT"

# ---- optional YouTube smoke (weak signal: DNS+ICMP only, see maestro/browser-youtube-mitm.yaml
# for the actual playback flow and MITM_SMOKE_CHECKLIST.md for the full manual verification) ----
if [[ "$CHECK_YOUTUBE" -eq 1 ]]; then
  if adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell ping -c 2 -W 3 www.youtube.com >/dev/null 2>&1; then
    row "youtube-icmp" "PASS" "www.youtube.com resolved + replied"
  else
    row "youtube-icmp" "WARN" "no ICMP reply (device policy or ICMP-only block — HTTP/TLS may still work; use the Browser flow to confirm)"
  fi
fi

{
  echo ""
  echo "## Summary"
  echo ""
  echo "- PASS: $pass"
  echo "- WARN: $warn"
  echo "- FAIL: $fail"
} >>"$REPORT"

echo ""
echo "PASS=$pass WARN=$warn FAIL=$fail"
echo "Wrote $REPORT"

[[ "$fail" -eq 0 ]]
