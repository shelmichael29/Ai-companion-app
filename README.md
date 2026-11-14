# AI Car Companion

A native Android app (Kotlin + Jetpack Compose) that acts as an intelligent automotive
assistant: vehicle profiles, a vehicle dashboard, and — in upcoming milestones — OBD-II
diagnostics over Bluetooth/Wi-Fi, plain-language trouble-code explanations, maintenance
tracking, mechanic handoff reports and AI-assisted troubleshooting.

> **Status: foundation (v0.1.0).** The app runs on sample data. **No OBD-II adapter support
> exists yet, and nothing shown in the app comes from a real vehicle.** Every sample item is
> labeled "Sample data" in the UI.

## What works today

- **Vehicle profiles** — add, edit, delete and select vehicles (year, make, model, trim,
  engine displacement/cylinders/fuel/aspiration, optional VIN, odometer, nickname) with
  input validation. Stored in memory only for now (reset when the app process ends).
- **Dashboard** — vehicle summary, OBD-II adapter status (honestly reports "not available
  yet"), sample trouble codes with explanations, a sample sensor snapshot and sample
  service history, under a clear sample-data banner.
- **OBD-II groundwork (pure Kotlin, unit-tested)** — DTC byte decoding (SAE J2012), standard
  mode 01 PID formulas (RPM, coolant temp, speed, voltage, …), ELM327 response parsing
  (including `NO DATA` / error handling), and a small offline catalog of generic codes.

## Requirements

- Android Studio (Ladybug or newer) or the command line with JDK 17+
- Android SDK Platform 35
- A device or emulator running Android 8.0 (API 26) or newer

No accounts, API keys, paid services or credentials are needed.

## Build & run

```bash
# Point Gradle at your Android SDK (or open the project in Android Studio, which does this)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # local.properties is git-ignored

./gradlew assembleDebug          # build the APK -> app/build/outputs/apk/debug/
./gradlew installDebug           # install on a connected device/emulator
./gradlew testDebugUnitTest      # run unit tests
./gradlew lintDebug              # Android lint
```

## Project layout

```
app/src/main/java/com/aicarcompanion/app/
├── AppContainer.kt              Manual dependency wiring (swap sample -> real here)
├── domain/model/                Vehicle, EngineSpec, DTCs, SensorReading, MaintenanceRecord, DataOrigin
├── domain/repository/           Repository interfaces (UI depends only on these)
├── data/memory/                 In-memory VehicleRepository
├── data/sample/                 Labeled sample data + sample repositories
├── diagnostics/obd/             ObdConnection abstraction, PIDs, ELM327 parser
├── diagnostics/dtc/             DTC decoder, code-format helpers, generic code catalog
└── ui/                          Compose theme, navigation, dashboard and vehicle screens
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for design decisions and
[docs/ROADMAP.md](docs/ROADMAP.md) for what comes next.
