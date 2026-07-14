#!/usr/bin/env bash
# I1 gate — every <string name="..."> key in values/strings.xml (the source of truth, English)
# must exist in values-fa and values-ru, and vice versa. Catches keys added to one locale and
# forgotten in the others (a recurring source of runtime fallback-to-English or crashes on
# missing resource lookups). Special-cases wizard_wants_* (welcome wizard interest flags) with a
# clearer failure message since those have regressed before.
#
# Usage: scripts/gates/string-key-parity.sh
# Exit code 0 = full parity, 1 = mismatch (prints every missing/extra key per locale).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

RES_DIR="app/src/main/res"
BASE="$RES_DIR/values/strings.xml"

declare -a LOCALES=("values-fa" "values-ru")

for f in "$BASE" "${LOCALES[@]/#/$RES_DIR/}"; do
  [[ -f "$f/strings.xml" || -f "$f" ]] || true
done

extract_keys() {
  # One key per line, sorted+uniqued. Matches <string name="foo"...> regardless of extra attrs.
  grep -o 'name="[a-zA-Z0-9_]*"' "$1" | sed 's/name="//; s/"//' | sort -u
}

BASE_KEYS="$(mktemp)"
trap 'rm -f "$BASE_KEYS" "${TMP_FILES[@]:-}"' EXIT
extract_keys "$BASE" >"$BASE_KEYS"
BASE_COUNT="$(wc -l <"$BASE_KEYS" | tr -d ' ')"

echo "=== I1 string-key-parity ==="
echo "base (values/strings.xml): $BASE_COUNT keys"

FAILED=0
declare -a TMP_FILES=()

for locale in "${LOCALES[@]}"; do
  locale_file="$RES_DIR/$locale/strings.xml"
  if [[ ! -f "$locale_file" ]]; then
    echo "FAIL: $locale_file does not exist"
    FAILED=1
    continue
  fi

  locale_keys="$(mktemp)"
  TMP_FILES+=("$locale_keys")
  extract_keys "$locale_file" >"$locale_keys"
  locale_count="$(wc -l <"$locale_keys" | tr -d ' ')"

  missing="$(comm -23 "$BASE_KEYS" "$locale_keys")"
  extra="$(comm -13 "$BASE_KEYS" "$locale_keys")"

  echo ""
  echo "-- $locale: $locale_count keys --"

  if [[ -n "$missing" ]]; then
    FAILED=1
    echo "MISSING in $locale (present in base):"
    echo "$missing" | sed 's/^/  - /'
    if echo "$missing" | grep -q '^wizard_wants_'; then
      echo "  -> wizard_wants_* keys missing: the welcome wizard will silently show blank/English"
      echo "     labels for these interest flags in $locale. Add matching <string> entries."
    fi
  fi

  if [[ -n "$extra" ]]; then
    FAILED=1
    echo "EXTRA in $locale (not in base — dead or renamed key):"
    echo "$extra" | sed 's/^/  - /'
  fi

  if [[ -z "$missing" && -z "$extra" ]]; then
    echo "OK: full parity with base"
  fi
done

echo ""
if [[ "$FAILED" -eq 0 ]]; then
  echo "GATE_STATUS=PASS"
  exit 0
else
  echo "GATE_STATUS=FAIL"
  exit 1
fi
