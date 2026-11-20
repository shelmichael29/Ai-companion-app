# AI Car Companion

A native Android app (Kotlin + Jetpack Compose) with **Penelope**, a voice-first automotive
assistant. It identifies your vehicle from free public databases, talks with you
(push-to-talk speech in, spoken answers out), reads standard OBD-II data from an
ELM327-compatible Bluetooth adapter (read-only), and keeps per-vehicle history.

> **Honesty rule:** the app never presents sample or guessed data as coming from your car.
> Live values are shown/spoken only when read from a connected adapter; every item carries
> its origin (vehicle, saved history, entered by you, sample).

## Features (v0.6)
- **v0.7 Everyday Driver dashboard:**
  - a large glass voice orb with live conversation words;
  - an honest, plain-language car health status;
  - four quick actions.
  - **New "Midnight" navy-and-cyan style:** the default, with Amethyst purple still available in Settings.

| Area | Status |
|---|---|
| Vehicle lookup: year → make → model → engine → transmission (FuelEconomy.gov), searchable | Implemented, unit-tested with captured API responses |
| VIN entry, validation and decoding (NHTSA vPIC) with review/correction | Implemented, unit-tested |
| VIN scanner (CameraX + on-device ML Kit), guided flow, and model-level NHTSA recall campaigns (never VIN-confirmed) | Implemented, unit-tested; needs phone testing (`docs/VIN_SCANNER.md`) |
| Offline: cached lookups, manual entry, profiles stored on-device (Room) | Implemented |
| Penelope hands-free conversation: listens again after each reply; goodbye/pause; tap to interrupt; auto-pause on silence, background or calls | Implemented; state machine unit-tested with fake engines; **needs on-device check** |
| Natural voice: explicit pitch + separate speed, voice/engine choice, sample, reset | Implemented; **needs on-device listening check** |
| Everyday / DIY / Advanced explanations; "explain more simply", "technical explanation", "sensor readings" | Implemented, unit-tested |
| Symptom conversations ("why is my car shaking?") with safety-first guidance and one follow-up question | Implemented (general guidance only), unit-tested |
| Purple-and-black redesign (v0.6): dark-first theme, smoother voice orb with distinct listening/thinking/speaking/paused/error states, AI status under the orb, round quick-access tiles, build-generated version label | Implemented; contrast unit-tested; rendered off-device; **needs on-device check** |
| Penelope UI 2.0: brand design system (light + dark), glowing voice orb driven by real voice state and mic level, floating nav, redesigned Home/Chat/Garage, vehicle showcase with your own photo, car-health result cards | Implemented; rendered off-device for review; **needs on-device check** |
| Suggested questions: warning lights, "can I still drive?", repair-estimate explanations | Implemented (general guidance only), unit-tested |
| Gemini conversations via Firebase AI Logic (App Check, grounded in verified car data, safety logic stays deterministic, offline fallback, on/off switch) | Implemented, unit-tested with a fake model; **needs Firebase config + device test** (see docs/FIREBASE_SETUP.md) |
| Smart Diagnostics engine, SmartFix, Mechanic Pro integration | Contracts/interfaces only |
| Rename assistant, opt-in demo data | Implemented |
| Bluetooth Classic ELM327: paired-device selection, init, status, reconnect, live PIDs, read stored/pending codes | Implemented; tested against a scripted fake adapter; **needs real-adapter verification** |
| OBD-II connection settings by level: Everyday automatic adapter choice; DIY reconnect/reset, details and troubleshooting; Advanced protocol, timeouts, retries, adaptive timing, polling, reconnection, adapter identification, connection test, redacted traffic log, read-only expert console, restore defaults | Implemented (Phase 4.1), unit-tested against a scripted adapter; **needs real-adapter verification** |
| Read-only safety allowlist (no code clearing / actuator / write commands) | Implemented, unit-tested |
| Per-vehicle history: service & repairs, notes (symptom / verified finding / possible cause), code reads, sensor snapshots, mileage | Implemented |
| BLE, Wi-Fi and USB adapters (not offered in settings until implemented), wake word, AI reasoning, ElevenLabs voice, mechanic export | Planned (interfaces in place) |

## Requirements

- Android 8.0 (API 26)+; developed for a Samsung Galaxy S26 Ultra.
- JDK 17+, Android SDK Platform 35 (Android Studio does this for you).
- Optional: an ELM327-compatible **Bluetooth Classic** OBD-II adapter (e.g. Panlong).

No API keys are stored in the app. Gemini is optional and needs your own Firebase project
(`google-services.json`, kept out of git); see [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md).

## Build & run

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # git-ignored
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install on a connected phone
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug
```

See **[docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md)** for installing and testing on the
Galaxy S26 Ultra with a Bluetooth adapter, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md),
[docs/ROADMAP.md](docs/ROADMAP.md).

## Permissions

| Permission | Why | When asked |
|---|---|---|
| `INTERNET` | Vehicle lookups (FuelEconomy.gov, NHTSA) | Never (normal permission) |
| `RECORD_AUDIO` | Push-to-talk with Penelope | First mic tap |
| `BLUETOOTH_CONNECT` ("Nearby devices") | Connect to your **paired** OBD-II adapter | On the Adapter tab |

No location, Bluetooth scanning or background-microphone access is requested.
