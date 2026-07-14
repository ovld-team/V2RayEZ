#!/usr/bin/env bash
# Device Lab P8-maestro — run the Maestro flows under maestro/ against a connected
# device/emulator, writing per-flow logs (+ JUnit XML when supported) into
# artifacts/device-lab/. Dry-runs gracefully (like collect-debug-bundle.sh) when adb or the
# maestro CLI aren't available, instead of hard-failing.
#
# Usage:
#   scripts/device-lab/maestro-run.sh                 # run every flow in maestro/
#   scripts/device-lab/maestro-run.sh nav-smoke        # run one flow by basename (no .yaml)
#   scripts/device-lab/maestro-run.sh --serial <id> [flow ...]
#
# XEOVO_SUB_URL (env var, never hard-coded — see xeovo-push-sub.sh) is forwarded to Maestro for
# maestro/xeovo-import-connect.yaml; other flows ignore it.
set -euo pipefail

# shellcheck source=./_env.sh
source "$(cd "$(dirname "$0")" && pwd)/_env.sh"
device_lab_ensure_adb || true

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FLOWS_DIR="$ROOT/maestro"
OUT_DIR="$ROOT/artifacts/device-lab"
mkdir -p "$OUT_DIR"

ADB_ARGS=()
if [[ "${1:-}" == "--serial" ]]; then
  ADB_ARGS+=(-s "$2")
  shift 2
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"

if ! command -v maestro >/dev/null 2>&1 && [[ -x "$HOME/.maestro/bin/maestro" ]]; then
  export PATH="$HOME/.maestro/bin:$PATH"
fi

if ! command -v maestro >/dev/null 2>&1; then
  echo "warn: maestro CLI not on PATH — writing dry-run meta instead of failing the lab run"
  {
    echo "dry_run: true"
    echo "reason: maestro CLI missing"
    echo "collected_at_utc: $TS"
    echo "hint: curl -Ls \"https://get.maestro.mobile.dev\" | bash; export PATH=\"\$HOME/.maestro/bin:\$PATH\""
  } >"$OUT_DIR/maestro-dry-run-$TS.txt"
  echo "Wrote $OUT_DIR/maestro-dry-run-$TS.txt"
  exit 0
fi

# ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} (not "${ADB_ARGS[@]}") — macOS ships bash 3.2, where
# expanding an empty array under `set -u` throws "unbound variable"; this is the portable guard.
if ! command -v adb >/dev/null 2>&1 || ! adb ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} get-state >/dev/null 2>&1; then
  echo "warn: no device/emulator attached — writing dry-run meta instead of failing the lab run"
  {
    echo "dry_run: true"
    echo "reason: no device/emulator attached"
    echo "collected_at_utc: $TS"
    echo "hint: scripts/device-lab/avd-up.sh, then ./gradlew :app:installDebug, then re-run"
  } >"$OUT_DIR/maestro-dry-run-$TS.txt"
  echo "Wrote $OUT_DIR/maestro-dry-run-$TS.txt"
  exit 0
fi

FLOWS=()
for arg in "$@"; do
  FLOWS+=("$FLOWS_DIR/$arg.yaml")
done
if [[ ${#FLOWS[@]} -eq 0 ]]; then
  while IFS= read -r -d '' f; do
    # Skip helper subflows
    case "$(basename "$f")" in helpers) continue ;; esac
    [[ "$f" == *"/helpers/"* ]] && continue
    FLOWS+=("$f")
  done < <(find "$FLOWS_DIR" -maxdepth 1 -name '*.yaml' -print0 | sort -z)
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  : # already set by caller
elif [[ ${#ADB_ARGS[@]} -gt 0 ]]; then
  export ANDROID_SERIAL="${ADB_ARGS[1]}"
fi

OVERALL_STATUS=0
for flow in ${FLOWS[@]+"${FLOWS[@]}"}; do
  [[ -f "$flow" ]] || { echo "warn: flow not found: $flow" >&2; OVERALL_STATUS=1; continue; }
  name="$(basename "$flow" .yaml)"
  if [[ "$name" == "xeovo-import-connect" && -z "${XEOVO_SUB_URL:-}" ]]; then
    echo "SKIP: $name (set XEOVO_SUB_URL to run this flow)"
    continue
  fi
  LOG="$OUT_DIR/maestro-$name-$TS.log"
  echo "== maestro test $name =="
  ENV_ARGS=()
  if [[ -n "${XEOVO_SUB_URL:-}" ]]; then
    ENV_ARGS+=(-e "XEOVO_SUB_URL=$XEOVO_SUB_URL")
  fi
  if maestro test ${ENV_ARGS[@]+"${ENV_ARGS[@]}"} --format junit --output "$OUT_DIR/maestro-$name-$TS.xml" "$flow" 2>&1 | tee "$LOG"; then
    echo "PASS: $name (log: $LOG)"
  else
    echo "FAIL: $name (log: $LOG)"
    OVERALL_STATUS=1
  fi
done

exit "$OVERALL_STATUS"
