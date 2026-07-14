#!/usr/bin/env bash
# Device Lab P8-tui-adb — adb-scripted verification for the checks in xeovo-smoke-tui.sh that
# have an objective, adb-observable pass/fail signal (process alive, no crash, nav labels
# present, VPN transport state, orientation survives). xeovo-smoke-tui.sh calls this first and
# pre-fills those answers so the tester only has to eyeball the genuinely subjective checks
# (does the video *play*, does Tor visually bootstrap, does traffic *tick*). Safe to run
# standalone too — prints the same PASS/WARN/FAIL/SKIP lines to stdout.
#
# Usage:
#   scripts/device-lab/xeovo-adb-checks.sh [--serial <id>] [check_id ...]
# With no check_id args, runs every check. Recognized ids: launch nav_tabs rotate always_on
# vpn_active vpn_inactive diagnostics
#
# Each check prints exactly one line: "<id> <PASS|WARN|FAIL|SKIP> <detail>" — xeovo-smoke-tui.sh
# parses this format, so keep it stable if you add checks.
set -uo pipefail

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb || true

PACKAGE="com.v2rayez.app"
ACTIVITY="com.v2rayez.app/.MainActivity"
ADB_ARGS=()

while [[ "${1:-}" == "--serial" ]]; do
  ADB_ARGS+=(-s "$2")
  shift 2
done

REQUESTED=("$@")
# vpn_active/vpn_inactive are context-dependent (meaningful only right after connect, or right
# after disconnect) — only run them when named explicitly, not as part of a bare "run everything".
DEFAULT_CHECKS=(launch nav_tabs rotate always_on diagnostics)

# ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} (not "${ADB_ARGS[@]}") — macOS ships bash 3.2, where
# expanding an empty array under `set -u` throws "unbound variable"; this is the portable guard.
adbx() { adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} "$@"; }

emit() { printf '%s %s %s\n' "$1" "$2" "$3"; }

want() {
  local id="$1"
  if [[ ${#REQUESTED[@]} -eq 0 ]]; then
    for d in "${DEFAULT_CHECKS[@]}"; do [[ "$d" == "$id" ]] && return 0; done
    return 1
  fi
  for r in "${REQUESTED[@]}"; do [[ "$r" == "$id" ]] && return 0; done
  return 1
}

require_adb() {
  command -v adb >/dev/null 2>&1 && adbx get-state >/dev/null 2>&1
}

# ---------------------------------------------------------------- launch
check_launch() {
  want launch || return 0
  if ! require_adb; then emit launch SKIP "no device/adb"; return; fi
  adbx logcat -c
  adbx shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 3
  local pid
  pid="$(adbx shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
  if [[ -z "$pid" ]]; then
    emit launch FAIL "process not running after am start"
    return
  fi
  if adbx logcat -d 2>/dev/null | grep -q "FATAL EXCEPTION"; then
    emit launch FAIL "FATAL EXCEPTION in logcat since launch"
    return
  fi
  emit launch PASS "pid=$pid, no FATAL EXCEPTION"
}

# ---------------------------------------------------------------- nav_tabs
check_nav_tabs() {
  want nav_tabs || return 0
  if ! require_adb; then emit nav_tabs SKIP "no device/adb"; return; fi
  local dump
  dump="$(adbx exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
  if [[ -z "$dump" ]]; then
    emit nav_tabs WARN "uiautomator dump returned nothing (app may not be foregrounded)"
    return
  fi
  local missing=()
  for label in "Home" "Servers" "Tools" "Browser" "Settings"; do
    echo "$dump" | grep -q "text=\"$label\"" || missing+=("$label")
  done
  if [[ ${#missing[@]} -eq 0 ]]; then
    emit nav_tabs PASS "all 5 bottom-bar labels present"
  else
    emit nav_tabs FAIL "missing labels: ${missing[*]}"
  fi
}

# ---------------------------------------------------------------- rotate (fully automated, no
# tester action needed — issues the rotation itself and checks the app survives it)
check_rotate() {
  want rotate || return 0
  if ! require_adb; then emit rotate SKIP "no device/adb"; return; fi
  local pid_before pid_after
  pid_before="$(adbx shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
  if [[ -z "$pid_before" ]]; then emit rotate SKIP "app not running — launch it first"; return; fi
  adbx shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
  adbx shell settings put system user_rotation 1 >/dev/null 2>&1 || true   # landscape
  sleep 1
  adbx shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 1
  adbx shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 1
  adbx shell settings put system user_rotation 0 >/dev/null 2>&1 || true   # back to portrait
  sleep 1
  pid_after="$(adbx shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
  if [[ -z "$pid_after" ]]; then
    emit rotate FAIL "process gone after rotate + background/foreground"
    return
  fi
  if adbx logcat -d 2>/dev/null | grep -q "FATAL EXCEPTION"; then
    emit rotate FAIL "FATAL EXCEPTION in logcat during rotate cycle"
    return
  fi
  emit rotate PASS "process survived rotate + background/foreground (pid $pid_before -> $pid_after)"
}

# ---------------------------------------------------------------- vpn_active / vpn_inactive
# Shared dumpsys probe: is an active NetworkAgent reporting the VPN transport.
vpn_transport_up() {
  adbx shell dumpsys connectivity 2>/dev/null | grep -qi "TRANSPORT_VPN"
}

check_vpn_active() {
  want vpn_active || return 0
  if ! require_adb; then emit vpn_active SKIP "no device/adb"; return; fi
  if vpn_transport_up; then
    emit vpn_active PASS "TRANSPORT_VPN present in dumpsys connectivity"
  else
    emit vpn_active FAIL "no TRANSPORT_VPN network — connect first, then rerun"
  fi
}

check_vpn_inactive() {
  want vpn_inactive || return 0
  if ! require_adb; then emit vpn_inactive SKIP "no device/adb"; return; fi
  if vpn_transport_up; then
    emit vpn_inactive FAIL "TRANSPORT_VPN still present after disconnect"
  else
    emit vpn_inactive PASS "no TRANSPORT_VPN network — tunnel is down"
  fi
}

# ---------------------------------------------------------------- always_on
check_always_on() {
  want always_on || return 0
  if ! require_adb; then emit always_on SKIP "no device/adb"; return; fi
  local app
  app="$(adbx shell settings get global always_on_vpn_app 2>/dev/null | tr -d '\r')"
  if [[ "$app" == "$PACKAGE" ]]; then
    emit always_on PASS "always_on_vpn_app=$PACKAGE"
  elif [[ -z "$app" || "$app" == "null" ]]; then
    emit always_on SKIP "not requested (always_on_vpn_app unset)"
  else
    emit always_on WARN "always_on_vpn_app=$app (expected $PACKAGE)"
  fi
}

# ---------------------------------------------------------------- diagnostics
# Reads the diag_status:<id>:<STATUS> contentDescription markers added to
# DiagnosticCheckRow (ui/screens/tools/ToolScreens.kt) so this works regardless of device
# locale. Requires the Diagnostics screen to already be open with a completed run.
check_diagnostics() {
  want diagnostics || return 0
  if ! require_adb; then emit diagnostics SKIP "no device/adb"; return; fi
  local dump
  dump="$(adbx exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
  if [[ -z "$dump" ]] || ! echo "$dump" | grep -q "diag_status:"; then
    emit diagnostics SKIP "Diagnostics screen not open / no completed run (open Tools -> Diagnostics -> Run)"
    return
  fi
  local fails warns
  fails="$(echo "$dump" | grep -o 'diag_status:[a-z_]*:FAIL' | wc -l | tr -d ' ')"
  warns="$(echo "$dump" | grep -o 'diag_status:[a-z_]*:WARN' | wc -l | tr -d ' ')"
  if [[ "$fails" -gt 0 ]]; then
    emit diagnostics FAIL "$fails check(s) reporting FAIL"
  elif [[ "$warns" -gt 0 ]]; then
    emit diagnostics WARN "$warns check(s) reporting WARN, 0 FAIL"
  else
    emit diagnostics PASS "0 FAIL / 0 WARN across all completed checks"
  fi
}

check_launch
check_nav_tabs
check_rotate
check_vpn_active
check_vpn_inactive
check_always_on
check_diagnostics
