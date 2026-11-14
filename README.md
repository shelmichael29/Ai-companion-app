# AI Car Companion

A native Android app (Kotlin + Jetpack Compose) with **Penelope**, a voice-first automotive
assistant. It identifies your vehicle from free public databases, talks with you
(push-to-talk speech in, spoken answers out), reads standard OBD-II data from an
ELM327-compatible Bluetooth adapter (read-only), and keeps per-vehicle history.

> **Honesty rule:** the app never presents sample or guessed data as coming from your car.
> Live values are shown/spoken only when read from a connected adapter; every item carries
> its origin (vehicle, saved history, entered by you, sample).

## Features (v0.2)

| Area | Status |
|---|---|
| Vehicle lookup: year → make → model → engine → transmission (FuelEconomy.gov), searchable | Implemented, unit-tested with captured API responses |
| VIN entry, validation and decoding (NHTSA vPIC) with review/correction | Implemented, unit-tested |
| Offline: cached lookups, manual entry, profiles stored on-device (Room) | Implemented |
| Penelope: push-to-talk speech recognition + spoken replies, stop/interrupt, transcript, typed fallback | Implemented; conversation logic unit-tested; speech engines need on-device check |
| Rename assistant, speaking speed, opt-in demo data | Implemented |
| Bluetooth Classic ELM327: paired-device selection, init, status, reconnect, live PIDs, read stored/pending codes | Implemented; tested against a scripted fake adapter; **needs real-adapter verification** |
| Read-only safety allowlist (no code clearing / actuator / write commands) | Implemented, unit-tested |
| Per-vehicle history: service & repairs, notes (symptom / verified finding / possible cause), code reads, sensor snapshots, mileage | Implemented |
| BLE adapters, Wi-Fi adapters, wake word, AI reasoning, ElevenLabs voice, mechanic export | Planned (interfaces in place) |

## Requirements

- Android 8.0 (API 26)+; developed for a Samsung Galaxy S26 Ultra.
- JDK 17+, Android SDK Platform 35 (Android Studio does this for you).
- Optional: an ELM327-compatible **Bluetooth Classic** OBD-II adapter (e.g. Panlong).

No accounts, API keys, paid services or credentials.

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
