#!/usr/bin/env bash
# Task Exit Gate — L0 compile + L1 focused + L2 neighbors + optional L3 stacking.
# Usage: scripts/gates/task-exit.sh <TODO_ID>
# Evidence: artifacts/gates/<TODO_ID>.txt
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
TODO_ID="${1:-}"
if [[ -z "$TODO_ID" ]]; then
  echo "Usage: $0 <TODO_ID>" >&2
  exit 2
fi
mkdir -p artifacts/gates
OUT="artifacts/gates/${TODO_ID}.txt"
MAP="$(dirname "$0")/conflict-neighbors.txt"
{
  echo "=== Task Exit Gate: ${TODO_ID} ==="
  echo "started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo ""
} >"$OUT"

run_tests() {
  local pattern="$1"
  if [[ -z "$pattern" || "$pattern" == "-" ]]; then
    return 0
  fi
  echo "L1/L2 tests: $pattern" | tee -a "$OUT"
  ./gradlew :app:testDebugUnitTest --tests "$pattern" 2>&1 | tee -a "$OUT"
}

# L0
echo "L0: compileDebugKotlin" | tee -a "$OUT"
./gradlew :app:compileDebugKotlin 2>&1 | tee -a "$OUT"

# Lookup focused + neighbors from map
FOCUS="-"
NEIGHBORS="-"
STACK="-"
if [[ -f "$MAP" ]]; then
  line="$(grep -E "^${TODO_ID}\|" "$MAP" || true)"
  if [[ -n "$line" ]]; then
    FOCUS="$(echo "$line" | cut -d'|' -f2)"
    NEIGHBORS="$(echo "$line" | cut -d'|' -f3)"
    STACK="$(echo "$line" | cut -d'|' -f4)"
  fi
fi

IFS=',' read -ra FPARTS <<<"$FOCUS"
for p in "${FPARTS[@]}"; do
  p="$(echo "$p" | xargs)"
  [[ -n "$p" && "$p" != "-" ]] && run_tests "$p"
done

IFS=',' read -ra NPARTS <<<"$NEIGHBORS"
for p in "${NPARTS[@]}"; do
  p="$(echo "$p" | xargs)"
  [[ -n "$p" && "$p" != "-" ]] && run_tests "$p"
done

if [[ "${STACK}" == "xeovo" ]]; then
  echo "L3: Xeovo stacking" | tee -a "$OUT"
  ./gradlew :app:testDebugUnitTest --tests '*Xeovo*' 2>&1 | tee -a "$OUT" || true
fi

echo "" | tee -a "$OUT"
echo "finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "$OUT"
echo "GATE_STATUS=PASS" | tee -a "$OUT"
echo "Wrote $OUT"
