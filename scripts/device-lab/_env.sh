#!/usr/bin/env bash
# Shared Device Lab env: resolve Android SDK + put platform-tools (adb) on PATH.
# Source from sibling scripts:
#   # shellcheck source=./_env.sh
#   source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
#   device_lab_ensure_adb

device_lab_repo_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd
}

device_lab_sdk_root() {
  local candidate props sdk
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
      "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate/platform-tools" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  props="$(device_lab_repo_root)/local.properties"
  if [[ -f "$props" ]]; then
    sdk="$(grep -E '^[[:space:]]*sdk\.dir=' "$props" | head -1 | cut -d= -f2- | tr -d '\r' \
      | sed 's|\\\\|/|g; s|\\:|:|g')"
    if [[ -n "$sdk" && -d "$sdk/platform-tools" ]]; then
      echo "$sdk"
      return 0
    fi
  fi
  return 1
}

# Export PATH so `adb` / `emulator` resolve without relying on the agent shell.
device_lab_ensure_adb() {
  local sdk cmdline
  sdk="$(device_lab_sdk_root)" || {
    echo "error: Android SDK not found (ANDROID_HOME / local.properties sdk.dir)" >&2
    return 1
  }
  cmdline="$(find "$sdk/cmdline-tools" -maxdepth 2 -type d -name bin 2>/dev/null | sort -r | head -n1 || true)"
  # Overwrite unconditionally: device_lab_sdk_root already rejected a pre-set ANDROID_HOME
  # without platform-tools, so keeping the stale value would point consumers (avd-up.sh) at
  # a broken SDK while PATH resolves the good one.
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  export PATH="$sdk/platform-tools:$sdk/emulator:${cmdline:-}:$PATH"
  command -v adb >/dev/null 2>&1 || {
    echo "error: adb missing under $sdk/platform-tools" >&2
    return 1
  }
}
