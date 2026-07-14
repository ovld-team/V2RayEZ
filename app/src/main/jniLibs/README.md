# Native binaries (`jniLibs`)

These are vendored native binaries extracted at install time to the app's
`nativeLibraryDir` with exec permission (`android.packaging.jniLibs.useLegacyPackaging = true`
in `app/build.gradle.kts`). Only files matching `*.so` are packaged by AGP — and the 5 optional
addon binaries below are further **excluded from the APK** (W6, see that section).

Layout — one copy per ABI:

```
jniLibs/
  arm64-v8a/
  armeabi-v7a/
  x86/
  x86_64/
```

## Tor

| File            | Provides                                   | Used by                          |
|-----------------|--------------------------------------------|----------------------------------|
| `libtor.so`     | Classic C `tor` daemon                      | `data/tor/NativeCTorEngine.kt`   |
| `liblyrebird.so`| obfs4 / meek_lite (PIE `exec`, lyrebird 0.4.0) | `PluggableTransports`         |
| `libsnowflake.so` | snowflake client (PIE `exec`, v2.9.2)     | `PluggableTransports`            |
| `libwebtunnel.so` | webtunnel client (PIE `exec`, v0.0.5)     | `PluggableTransports`            |

## Proxy cores (multi-core)

| File              | Version / source                                      | Role |
|-------------------|-------------------------------------------------------|------|
| `libv2ray.aar`    | AndroidLibXrayLite (see `app/libs/`)                  | Built-in Xray (in-process TUN) |
| `libsingbox.so`   | sing-box **v1.13.14** Android PIE                     | Process core |
| `libmihomo.so`    | mihomo **v1.19.28** Android PIE                       | Clash Meta process core |
| `libhev-socks5-tunnel.so` | hev-socks5-tunnel **2.15.0** (NDK shared + JNI) | TUN→SOCKS bridge for process cores |

`libhev-socks5-tunnel.so` is a **shared library** with JNI (`hev.htproxy.TProxyService`).
`libsingbox.so` / `libmihomo.so` are **PIE executables** renamed to `lib*.so` for packaging.
`libsingbox` must be linked with `-Wl,-z,max-page-size=16384` (Android 15+ 16 KB pages).
`libtor.so` is taken from a 16 KB–aligned Orbot/tor-android build for the same reason.

Users can download other versions at runtime via **Core manager** (`CoreBinaryManager`);
selected versions live under `filesDir/cores/<type>/<version>/`.

## Packaging addon binaries for release (no APK assets)

`libtor.so` / `liblyrebird.so` / `libsnowflake.so` / `libwebtunnel.so` / `libbyedpi.so` are meant
to become **on-demand downloads** (see `data/core/AddonPackManager.kt` +
`data/core/NativeBinaryStore.kt`), not permanent APK weight. `scripts/pack-addons.sh` zips each of
them (per ABI) out of this directory into `build/addon-packs/` (gitignored) — one zip per
addon+ABI, plus a `SHA256SUMS.txt`. Upload those zips as assets on a **GitHub Release**; never
commit them and never copy them into `app/src/main/assets/` (that would defeat the slim-APK goal
of this on-demand-packs effort). `AddonPackManager` downloads + sha256-verifies + ABI/ELF-checks
them into `filesDir/addons/<packId>/<version>/` at runtime.

### These 5 are STRIPPED FROM THE PACKAGED APK (W6)

`app/build.gradle.kts` excludes them via `packaging.jniLibs.excludes` so they are **not** packaged
into the APK even though the source `.so` files stay committed here for `pack-addons.sh`:

```kotlin
excludes += listOf("**/libtor.so", "**/liblyrebird.so", "**/libsnowflake.so",
                   "**/libwebtunnel.so", "**/libbyedpi.so")
```

Size check (source `.so` in this dir, all four ABIs): tor ≈ 28 MB, lyrebird ≈ 34 MB,
snowflake ≈ 60 MB, webtunnel ≈ 19 MB, byedpi ≈ 0.3 MB → **≈ 143 MB total stripped** from the
universal APK (≈ 35–42 MB per per-ABI split APK). At runtime these resolve
`filesDir/addons/<packId>/…` first, then the (now absent) bundled PIE, so the app shows a
"download in Core manager" CTA until the pack is installed. **`libhev-socks5-tunnel.so`,
`libsingbox.so`, `libmihomo.so`, and `libv2ray.aar` are KEPT** — required cores / TUN bridge.

> The source binaries are intentionally left committed (deleting ~143 MB of already-committed
> blobs is destructive and would break `pack-addons.sh`). The APK slim-down is enforced by the
> AGP exclude above, not by removing the files.

## Desync

| File            | Provides        |
|-----------------|-----------------|
| `libbyedpi.so`  | byedpi SOCKS    |

Do **not** vendor Orbot/IPtProxy `libgojni.so` — it conflicts with `libv2ray.aar` (gomobile `go.Seq`).
