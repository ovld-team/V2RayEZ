#!/usr/bin/env bash
# Device Lab DV1 — create (if missing) and boot a reusable API 34 AVD for manual + TUI smoke
# passes. Safe to re-run: reuses the existing AVD/snapshot instead of recreating it.
#
# Usage:
#   scripts/device-lab/avd-up.sh [avd_name]
#
# Requires: ANDROID_HOME/ANDROID_SDK_ROOT with cmdline-tools + platform-tools + emulator on PATH,
# Prefers ANDROID_HOME / ANDROID_SDK_ROOT; falls back to discovering the SDK under common locations.
set -euo pipefail

AVD_NAME="${1:-Xeovo_API34}"
API_LEVEL=34
SYSTEM_IMAGE_TAG="google_apis"

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb

SDK="${ANDROID_HOME}"
CMDLINE_BIN="$(find "$SDK/cmdline-tools" -maxdepth 2 -type d -name bin 2>/dev/null | sort -r | head -n1 || true)"
export PATH="$SDK/platform-tools:$SDK/emulator:${CMDLINE_BIN:-}:$PATH"

command -v avdmanager >/dev/null 2>&1 || { echo "error: avdmanager not on PATH (checked $CMDLINE_BIN)" >&2; exit 1; }
command -v emulator >/dev/null 2>&1 || { echo "error: emulator not on PATH (checked $SDK/emulator)" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || { echo "error: adb not on PATH (checked $SDK/platform-tools)" >&2; exit 1; }

# Apple Silicon vs Intel/other — pick the ABI the host can actually run.
ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" ]]; then
  ABI="arm64-v8a"
else
  ABI="x86_64"
fi
IMAGE="system-images;android-${API_LEVEL};${SYSTEM_IMAGE_TAG};${ABI}"

if ! "$SDK/cmdline-tools"/*/bin/sdkmanager --list_installed 2>/dev/null | grep -q "$IMAGE"; then
  echo "Installing $IMAGE (one-time, may take a while)..."
  yes | sdkmanager --install "$IMAGE" >/dev/null
fi

if ! avdmanager list avd | grep -q "Name: ${AVD_NAME}$"; then
  echo "Creating AVD ${AVD_NAME} (${IMAGE})..."
  echo "no" | avdmanager create avd -n "$AVD_NAME" -k "$IMAGE" -d "pixel_6" --force
else
  echo "AVD ${AVD_NAME} already exists — reusing."
fi

if adb devices | grep -q "emulator-"; then
  echo "An emulator is already running — skipping boot. Use logcat-follow.sh to attach."
  exit 0
fi

echo "Booting ${AVD_NAME}..."
nohup emulator -avd "$AVD_NAME" -no-snapshot-save -no-boot-anim -netdelay none -netspeed full \
  >/tmp/"${AVD_NAME}"-emulator.log 2>&1 &
EMU_PID=$!
echo "emulator pid=$EMU_PID (log: /tmp/${AVD_NAME}-emulator.log)"

echo -n "Waiting for device..."
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  echo -n "."
  sleep 2
done
echo " booted."

# Trim animation flakiness in Compose UI tests / manual smoke passes.
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

echo "Ready: ${AVD_NAME} (API ${API_LEVEL}, ${ABI})."
