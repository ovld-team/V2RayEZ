# V2RayEZ

[![Release](https://img.shields.io/github/v/release/ovld-team/V2RayEZ?include_prereleases)](https://github.com/ovld-team/V2RayEZ/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg)](https://kotlinlang.org/)

**V2RayEZ** is an Android VPN / proxy client. It speaks VLESS, VMESS, Trojan, and Shadowsocks through a vendored Xray core, with optional on-device packs for Tor, pluggable transports, ByeDPI, and more.

| | |
|---|---|
| Platform | Android 8.0+ (`minSdk 26`), `targetSdk 35` |
| Stack | Kotlin, Jetpack Compose, Hilt, Room, DataStore |
| Core | Xray (`libv2ray.aar`) + real `VpnService` |
| UI languages | English · فارسی · Русский |

---

## English

### What you get

- Share-link, QR, file, and subscription import
- Per-app proxy, routing/DNS (incl. Iran bypass when geo assets are installed)
- In-app Browser and MITM / domain-fronting tools (advanced)
- Tor + pluggable transports via [Core manager](#addon-packs) when packs are installed
- Foreground notification, Quick Settings tile, boot auto-connect

### Install

1. Open the latest [Release](https://github.com/ovld-team/V2RayEZ/releases).
2. Download `V2RayEZ-v*-release.apk` and install it (unknown sources allowed).
3. Optional: in the app open **Core manager** and install addon zips from the same Release.

Current release: [`V2RayEZ-v0.9.98`](https://github.com/ovld-team/V2RayEZ/releases/tag/V2RayEZ-v0.9.98) (prerelease until owner gates close).

### Addon packs

Release assets look like `tor-arm64-v8a.zip`, `lyrebird-arm64-v8a.zip`, …  
The app resolves them from `ovld-team/V2RayEZ` at tag `V2RayEZ-v0.9.98` (overridable with Gradle properties `v2rayez.addons.githubRepo` / `v2rayez.addons.releaseTag`). Local dev uses `gradle.properties`; CI release builds pass the publish tag automatically. Checksums are in `SHA256SUMS.txt`.

### Release notes — v0.9.98

- **VPN, Tor, and DNS:** Hardened lifecycle ordering, conflict remediation, DNS routing, reconnect teardown, and per-app tunnel policy.
- **Domain fronting and Browser:** Safer front-address resolution, clearer MITM/fronting failures, and a more reliable proxied Browser flow.
- **Operations:** Sentry-backed scrubbed bug reports, queued Core manager installs, wizard pack mapping, download validation, and expanded diagnostics.
- **Review fixes:** Addressed priority audit findings across config generation, service state, telemetry, logging, localization, lint, and tests.
- **Pack-gated protocols:** Release automation lists Psiphon and DNS tunnel (`dnstt`) vendor sources, but their direct per-ABI URLs/checksums remain placeholders; those packs are not published until pinned upstream artifacts are configured.
- **Tor:** Treat whole-device routing and native Tor/PT packs as **experimental** until maintainer device smoke passes.

### Build from source

```bash
cp app/google-services.json.example app/google-services.json   # then fill Firebase config
# Put sentry.dsn=… in local.properties (gitignored)
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Release signing needs a local `keystore.properties` pointing at your keystore. Neither that file nor `app/google-services.json` is committed.

### Privacy

- **Sentry** is the primary crash / error channel; **Firebase Crashlytics** is a fatal backup.
- Hosts, URIs, bridges, IPs, and certificates are scrubbed before upload.
- Firebase Analytics is opt-in. Session Replay is off.
- DSN and `google-services.json` are supplied at build time (local file or CI secrets), not stored in source.

### License

[MIT](LICENSE)

---

## فارسی

**V2RayEZ** کلاینت VPN / پروکسی اندروید است و پروتکل‌های VLESS، VMESS، Trojan و Shadowsocks را با هسته Xray پشتیبانی می‌کند. بسته‌های اختیاری Tor و ابزارهای مرتبط از Releases نصب می‌شوند.

### نصب

1. از صفحه [Releases](https://github.com/ovld-team/V2RayEZ/releases) فایل `V2RayEZ-v*-release.apk` را بگیرید و نصب کنید.
2. برای بسته‌های افزونه، در اپ به **مدیر هسته** بروید یا زیپ‌های همان Release را نصب کنید.

نسخه فعلی: [`V2RayEZ-v0.9.98`](https://github.com/ovld-team/V2RayEZ/releases/tag/V2RayEZ-v0.9.98).

### ساخت

```bash
cp app/google-services.json.example app/google-services.json
# sentry.dsn را در local.properties بگذارید
./gradlew assembleDebug
```

فایل‌های `keystore.properties` و `google-services.json` در مخزن نیستند.

### حریم خصوصی

گزارش خطا با Sentry و پشتیبان Crashlytics است؛ آدرس‌ها و اطلاعات حساس پاکسازی می‌شوند. Analytics اختیاری است.

### مجوز

[MIT](LICENSE)
