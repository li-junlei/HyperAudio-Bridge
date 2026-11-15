# Third-party components

The Windows receiver is implemented in Rust with GPUI, CPAL, rustls, mdns-sd, qrcode and opus-decoder. Full resolved dependency metadata and license texts are included in `RUST_DEPENDENCIES.md` and `licenses/` in the distribution. Opus decoding uses the pure Rust `opus-decoder` implementation. Windows native APIs are used for DPAPI, existing certificate import, tray and audio device integration.

- scrcpy 5.0.1 — Genymobile / Romain Vimont — Apache-2.0. Official unmodified server bundled in Android assets. License: `third_party/scrcpy/LICENSE`.
- Miuix 0.9.2 — compose-miuix-ui contributors — Apache-2.0 — https://github.com/compose-miuix-ui/miuix
- AndroidX / Jetpack Compose — Android Open Source Project — Apache-2.0 — https://android.googlesource.com/platform/frameworks/support/
- Kotlin / kotlinx.coroutines — JetBrains contributors — Apache-2.0 — https://github.com/JetBrains/kotlin
- ZXing Android Embedded 4.3.0 — JourneyApps contributors — Apache-2.0 — https://github.com/journeyapps/zxing-android-embedded
- Xposed API 82 (compile-time only, supplied by framework at runtime) — rovo89 contributors — Apache-2.0 — https://github.com/rovo89/XposedBridge

Transitive dependencies remain under their respective licenses. The Windows installer includes `RUST_DEPENDENCIES.md` and dependency license texts under `licenses/`. The original Android and Rust source files are licensed under the repository LICENSE; third-party files retain their upstream licenses.
