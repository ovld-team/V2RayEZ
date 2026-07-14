# Native binaries (`jniLibs`)

These are vendored native binaries extracted at install time to the app's
`nativeLibraryDir` with exec permission (`android.packaging.jniLibs.useLegacyPackaging = true`
in `app/build.gradle.kts`). Only files matching `*.so` are packaged by AGP — five optional
binaries are **excluded from the APK** (see Packaging section); Tor + lyrebird ship bundled.

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

## Packaging: what ships in the APK vs download-only (v0.9.71+)

`app/build.gradle.kts` `packaging.jniLibs.excludes` strips **five** optional binaries from the
packaged APK. Source `.so` stay committed here for `scripts/pack-addons.sh` release zips.

```kotlin
excludes += listOf(
    "**/libsnowflake.so",
    "**/libwebtunnel.so",
    "**/libbyedpi.so",
    "**/libsingbox.so",
    "**/libmihomo.so"
)
```

| Binary | In APK | Resolution |
|--------|--------|------------|
| `libv2ray.aar` (`app/libs/`) | yes | in-process Xray |
| `libhev-socks5-tunnel.so` | yes | TUN→SOCKS bridge |
| `libtor.so` | yes | bundled Tor daemon |
| `liblyrebird.so` | yes | bundled obfs4/meek PT |
| `libsnowflake.so` | **no** | `AddonPackManager` → GH Release zip |
| `libwebtunnel.so` | **no** | same |
| `libbyedpi.so` | **no** | same |
| `libsingbox.so` | **no** | `CoreBinaryManager` → upstream release |
| `libmihomo.so` | **no** | same |
| `libpsiphon.so` / `libdnstt.so` | **no** | P7 addon packs; not in jniLibs until maintainer drops them in |

At runtime: downloaded `filesDir/addons/` or `filesDir/cores/` first, then bundled `nativeLibraryDir`
(where present). Missing packs surface Core manager CTAs.

`scripts/pack-addons.sh` zips tor/lyrebird/snowflake/webtunnel/byedpi (+ optional psiphon/dnstt when
present) per ABI into `build/addon-packs/` with `SHA256SUMS.txt` for GitHub Release assets — never
`app/src/main/assets/`.

## Desync

| File            | Provides        |
|-----------------|-----------------|
| `libbyedpi.so`  | byedpi SOCKS    |

Do **not** vendor Orbot/IPtProxy `libgojni.so` — it conflicts with `libv2ray.aar` (gomobile `go.Seq`).
