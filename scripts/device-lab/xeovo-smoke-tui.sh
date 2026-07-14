#!/usr/bin/env bash
# Device Lab DV3 — guided terminal checklist for a Xeovo smoke pass. Walks a tester through the
# standard nav + feature checks, records pass/warn/fail/skip per item, and writes a timestamped
# matrix report to artifacts/device-lab/. No live subscription bodies are recorded — only
# pass/fail notes typed by the tester.
#
# P8-tui-adb: checks with an objective adb-observable signal (process alive/no crash, nav-bar
# labels present, VPN transport up/down, always-on setting, Diagnostics screen's own PASS/WARN/
# FAIL tally) are pre-verified by scripts/device-lab/xeovo-adb-checks.sh instead of asked as pure
# human judgment calls — the tester only confirms or overrides. "rotate" is fully scripted (no
# tester action at all: the script rotates the device itself and checks the app survived it).
# Genuinely subjective checks (does the video visibly play, does traffic visibly tick, does Tor
# visually bootstrap, is the LAN-shared proxy usable from a second device) stay pure manual
# prompts — there is no adb signal for "looks right to a human".
#
# Usage:
#   scripts/device-lab/xeovo-smoke-tui.sh [--serial <id>]
set -euo pipefail

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb || true

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT/artifacts/device-lab"
ADB_CHECKS="$ROOT/scripts/device-lab/xeovo-adb-checks.sh"
mkdir -p "$OUT_DIR"

ADB_ARGS=()
SERIAL_ARGS=()
if [[ "${1:-}" == "--serial" ]]; then
  ADB_ARGS+=(-s "$2")
  SERIAL_ARGS=(--serial "$2")
  shift 2
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$OUT_DIR/matrix-$TS.md"

DEVICE_LABEL="unknown"
if command -v adb >/dev/null 2>&1; then
  # ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} (not "${ADB_ARGS[@]}") — macOS ships bash 3.2, where
  # expanding an empty array under `set -u` throws "unbound variable"; this is the portable guard.
  DEVICE_LABEL="$(adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo unknown)"
fi

# id|description|mode|detector|prompt-before-detect
# mode: manual (pure human judgment, unchanged) | assist (adb pre-checks, human confirms/overrides)
#       | auto (fully scripted — no p/w/f/s prompt at all)
# detector: check id passed to xeovo-adb-checks.sh ("-" when mode=manual)
# prompt-before-detect: instruction shown + Enter-to-continue wait *before* running the detector
#       (used when the action is inherently manual — e.g. connecting — but its outcome isn't);
#       empty means run the detector immediately with no extra prompt.
CHECKS=(
  "launch|Cold launch reaches Home without crash|assist|launch|"
  "nav_tabs|Bottom bar switches Home/Servers/Tools/Browser/Settings without stutter|assist|nav_tabs|"
  "import_sub|Import Xeovo subscription (xeovo-push-sub.sh + File import) populates a server group|manual|-|"
  "connect|Connect to a Xeovo server: VPN consent dialog then Connected state + traffic ticking|assist|vpn_active|Connect to a server now, then press Enter."
  "diagnostics|Diagnostics tool: run and confirm connectivity + tunnel sections report PASS/WARN honestly|assist|diagnostics|Open Tools -> Diagnostics, tap Run, wait for it to finish, then press Enter."
  "tor|Tor: enable, bootstraps to 100%, SOCKS/exit-IP checks pass in Diagnostics|manual|-|"
  "hotspot|Hotspot/LAN sharing: enable, confirm reconnect hint, verify a LAN client can use the proxy port|manual|-|"
  "browser|Browser tab: MITM banner/active state correct, page loads, WebView survives backgrounding|manual|-|"
  "always_on|Always-on honesty: toggle deep-links to system VPN settings; state shown matches OS|assist|always_on|"
  "logs_export|Logs screen: filter, export, and clear all work|manual|-|"
  "disconnect|Disconnect cleanly stops the tunnel and notification|assist|vpn_inactive|Disconnect now, then press Enter."
  "rotate|Rotate device / background+foreground app mid-connection without losing state|auto|rotate|"
)

echo "Xeovo smoke TUI — device: $DEVICE_LABEL"
echo "Answer p=pass, w=warn, f=fail, s=skip. 'assist' items show an adb-verified suggestion first"
echo "— bare Enter accepts it. 'auto' items run without any prompt. Notes are optional."
echo ""

{
  echo "# Xeovo smoke matrix — $TS"
  echo ""
  echo "- Device: $DEVICE_LABEL"
  echo "- Started (UTC): $TS"
  echo ""
  echo "| Check | Result | Notes |"
  echo "|-------|--------|-------|"
} >"$REPORT"

pass=0; warn=0; fail=0; skip=0

# Runs xeovo-adb-checks.sh for one detector id and prints "STATUS|detail" (STATUS one of
# PASS/WARN/FAIL/SKIP). Empty output means the detector script itself isn't usable right now.
run_detector() {
  local detector="$1" line
  [[ -x "$ADB_CHECKS" ]] || { echo ""; return; }
  line="$("$ADB_CHECKS" ${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"} "$detector" 2>/dev/null | grep -m1 "^$detector ")" || true
  [[ -z "$line" ]] && { echo ""; return; }
  local status detail
  status="$(awk '{print $2}' <<<"$line")"
  detail="$(cut -d' ' -f3- <<<"$line")"
  echo "$status|$detail"
}

record() {
  local desc="$1" result="$2" notes="$3"
  case "$result" in
    PASS) pass=$((pass+1)) ;;
    WARN) warn=$((warn+1)) ;;
    FAIL) fail=$((fail+1)) ;;
    SKIP) skip=$((skip+1)) ;;
  esac
  echo "| $desc | $result | ${notes//|/;} |" >>"$REPORT"
}

for entry in "${CHECKS[@]}"; do
  IFS='|' read -r id desc mode detector precheck <<<"$entry"

  if [[ "$mode" == "auto" ]]; then
    suggestion="$(run_detector "$detector")"
    status="${suggestion%%|*}"; detail="${suggestion#*|}"
    [[ -z "$status" ]] && { status="SKIP"; detail="detector unavailable"; }
    echo "[$id] $desc -> $status ($detail)"
    record "$desc" "$status" "$detail"
    continue
  fi

  if [[ -n "$precheck" ]]; then
    read -r -p "[$id] $precheck " _ || true
  fi

  default_ans=""
  if [[ "$mode" == "assist" ]]; then
    suggestion="$(run_detector "$detector")"
    if [[ -n "$suggestion" ]]; then
      status="${suggestion%%|*}"; detail="${suggestion#*|}"
      case "$status" in
        PASS) default_ans="p" ;;
        WARN) default_ans="w" ;;
        FAIL) default_ans="f" ;;
        SKIP) default_ans="" ;;
      esac
      echo "  [adb-verified] $status — $detail"
    fi
  fi

  result=""
  while [[ -z "$result" ]]; do
    if [[ -n "$default_ans" ]]; then
      read -r -p "[$id] $desc — (p/w/f/s, Enter=$default_ans): " ans || ans="s"
      [[ -z "$ans" ]] && ans="$default_ans"
    else
      read -r -p "[$id] $desc — (p/w/f/s): " ans || ans="s"
    fi
    case "$ans" in
      p|P) result="PASS" ;;
      w|W) result="WARN" ;;
      f|F) result="FAIL" ;;
      s|S) result="SKIP" ;;
      *) echo "  enter p, w, f, or s" ;;
    esac
  done
  read -r -p "  notes: " notes || notes=""
  record "$desc" "$result" "$notes"
done

{
  echo ""
  echo "## Summary"
  echo ""
  echo "- PASS: $pass"
  echo "- WARN: $warn"
  echo "- FAIL: $fail"
  echo "- SKIP: $skip"
  echo ""
  if [[ "$fail" -gt 0 ]]; then
    echo "**GATE: FAIL** — $fail failing check(s), see table above."
  elif [[ "$warn" -gt 0 ]]; then
    echo "**GATE: PASS WITH WARNINGS**"
  else
    echo "**GATE: PASS**"
  fi
} >>"$REPORT"

echo ""
echo "Wrote $REPORT (PASS=$pass WARN=$warn FAIL=$fail SKIP=$skip)"
