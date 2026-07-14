#!/usr/bin/env bash
# Packs optional native addon binaries (Tor / pluggable transports / ByeDPI) out of
# app/src/main/jniLibs/<abi>/ into per-addon, per-ABI zips meant to be uploaded as assets on a
# GitHub Release — NOT bundled into the APK. This keeps the shipped APK slim while still letting
# NativeBinaryStore/AddonPackManager (data/core/) download + sha256-verify + install them
# on-demand into filesDir/addons/<packId>/<version>/.
#
# Usage:
#   scripts/pack-addons.sh [version] [out_dir]
#
#   version   Release tag written into the manifest/zip names (default: git describe, or "dev")
#   out_dir   Where to write the zips (default: build/addon-packs — already gitignored)
#
# Output:
#   <out_dir>/<addon>-<abi>.zip        one zip per addon per ABI, containing just the renamed
#                                       executable (e.g. `tor`, `lyrebird`, `snowflake`,
#                                       `webtunnel`, `byedpi`) with the exec bit set
#   <out_dir>/SHA256SUMS.txt           sha256 of every zip, for the release notes / resolver
#
# This script only reads from jniLibs and writes to <out_dir>; it never touches
# app/src/main/assets/ or any other path that would ship inside the APK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-$(git describe --tags --always 2>/dev/null || echo dev)}"
OUT_DIR_REL="${2:-build/addon-packs}"
JNI_DIR="app/src/main/jniLibs"

# addon_id:so_filename:binary_name — keep in sync with AddonPackId in data/core/AddonPackManager.kt
#   addon_id    = AddonPackId enum name lowercased → the zip prefix the resolver expects
#                 (`<addon_id>-<abi>.zip`, see AddonPackManager.wantAsset)
#   so_filename = the file under jniLibs/<abi>/ to pack (only exists if a maintainer built it;
#                 the P7 protocol natives are NOT bundled, so those rows no-op until dropped in)
#   binary_name = the name the archive must expose (AddonPackId.binaryFileName)
ADDONS=(
  "tor:libtor.so:tor"
  "lyrebird:liblyrebird.so:lyrebird"
  "snowflake:libsnowflake.so:snowflake"
  "webtunnel:libwebtunnel.so:webtunnel"
  "byedpi:libbyedpi.so:byedpi"
  # Optional protocol packs (on-demand download; skipped if natives are absent)
  "psiphon:libpsiphon.so:psiphon-tunnel-core"
  "dnstunnel:libdnstt.so:dnstt"
)

if [[ ! -d "$JNI_DIR" ]]; then
  echo "error: $JNI_DIR not found (run from repo root)" >&2
  exit 1
fi

rm -rf "$OUT_DIR_REL"
mkdir -p "$OUT_DIR_REL"
OUT_DIR="$(cd "$OUT_DIR_REL" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SHA_FILE="$OUT_DIR/SHA256SUMS.txt"
: >"$SHA_FILE"

packed=0
for abi_dir in "$JNI_DIR"/*/; do
  abi="$(basename "$abi_dir")"
  for entry in "${ADDONS[@]}"; do
    addon_id="${entry%%:*}"
    rest="${entry#*:}"
    so_name="${rest%%:*}"
    bin_name="${rest#*:}"
    src="$abi_dir$so_name"
    [[ -f "$src" ]] || continue

    stage="$WORK/$addon_id-$abi"
    mkdir -p "$stage"
    cp "$src" "$stage/$bin_name"
    chmod +x "$stage/$bin_name"

    zip_name="${addon_id}-${abi}.zip"
    zip_path="$OUT_DIR/$zip_name"
    ( cd "$stage" && zip -q -X "$zip_path" "$bin_name" )

    if command -v shasum >/dev/null 2>&1; then
      sha="$(shasum -a 256 "$zip_path" | awk '{print $1}')"
    else
      sha="$(sha256sum "$zip_path" | awk '{print $1}')"
    fi
    echo "$sha  $zip_name" >>"$SHA_FILE"
    echo "packed $zip_name (sha256 $sha)"
    packed=$((packed + 1))
  done
done

if [[ "$packed" -eq 0 ]]; then
  echo "warning: no addon .so files found under $JNI_DIR — nothing packed" >&2
  exit 1
fi

cat >"$OUT_DIR/RELEASE_NOTES.md" <<EOF
# Addon packs — $VERSION

Upload every \`*.zip\` in this directory (and \`SHA256SUMS.txt\`) as assets on a **GitHub Release**
tagged \`$VERSION\` in this repo (or a dedicated addon-packs repo). Do **not** commit them and do
**not** copy them into \`app/src/main/assets/\` — the app downloads them on demand via
\`AddonPackManager\` (see \`data/core/AddonPackManager.kt\`) into \`filesDir/addons/\`, verifying the
sha256 below plus an ABI/ELF check before anything is marked executable.

| Zip | Contains |
|-----|----------|
$(for f in "$OUT_DIR"/*.zip; do echo "| $(basename "$f") | $(unzip -l "$f" | awk 'NR==4{print $4}') |"; done)
EOF

echo ""
echo "Wrote $packed pack(s) to $OUT_DIR/ (see RELEASE_NOTES.md + SHA256SUMS.txt)"
