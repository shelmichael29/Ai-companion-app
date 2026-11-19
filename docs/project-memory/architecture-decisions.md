# Architecture and technical decisions

Details: `docs/ARCHITECTURE.md`. Each decision below is in `main` unless marked.

| Decision | Why |
|---|---|
| Kotlin + Jetpack Compose, manual DI in `AppContainer` (the only place implementations are chosen) | Simple, explicit wiring; every subsystem behind an interface |
| Room for persistence; settings in `SettingsStore` | Offline first |
| Gemini via **Firebase AI Logic**, App Check (debug provider in debug builds, Play Integrity in release), limited-use tokens | No API key in the app; abuse protection |
| Rule-based `ReasoningProvider` always available; Gemini is optional and falls back | The app works offline and without Firebase |
| Facts come from app data, not the AI: Gemini never invents readings or codes | Safety and honesty |
| AI notices ("I'm having trouble…") are never added to the history Gemini sees | Fixed a false "daily limit" loop (Phase 7) |
| `ConnectionLog`: in-memory, redacted, 300 events, areas VEHICLE, INTERNET, AI, VOICE, AUDIO, APP | Diagnosable on the phone without leaking data |
| Bluetooth transport (`AdapterTransport`) separate from the ELM327 session, diagnostics, AI and UI | Future BLE, Wi-Fi and USB adapters plug in |
| `handsfree/` core has no Android imports (test-enforced); Android code in `handsfree/android/` | A future iOS app can mirror the contracts |
| `HandsFreeReasoningProvider` routes music/message/call commands before the AI | Keeps names and message text away from the AI; no pretend actions |
| Crashlytics with no user identifiers; debug-only test crash | Privacy |
| CI quality gate (`scripts/quality-gate.sh`, `.github/workflows/android-ci.yml`) required for every stage | Quality |
| Build: AGP 9.5, Gradle 9.9, Kotlin 2.4 (built into AGP), compileSdk 37, **targetSdk 35** | Up to date; `targetSdk` held back until device-tested |
| BMW **ENET** tester kept out of `main` (only `experiment/*` branches, PR #11) | It's a separate experiment, not part of Penelope |

## ❓ Open decisions
- **Gemini and personal details:** whether to automatically scrub symptoms and service titles before sending them to Gemini (raised by code scanning on PR #24). Options: keep as is, automatic scrubbing (recommended), opt-in, or both. Waiting on the owner.
- **`targetSdk` 35 → 37:** needs its own stage with device testing.
