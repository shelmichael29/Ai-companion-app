# Architecture summary

**Authoritative:** `docs/ARCHITECTURE.md` (full design), `docs/HANDS_FREE.md`, `docs/BLUETOOTH_CONNECTION.md`, `docs/CONNECTION_RECOVERY.md`, `docs/CI.md`. This page is the map; read those for detail.

## Android app
- **Stack:** Kotlin 2.4 (compiled by AGP 9.5), Jetpack Compose, Gradle 9.9, Room, coroutines and StateFlow.
- **SDK levels:** minSdk 26, compileSdk 37, **targetSdk 35** (held back on purpose).
- **Wiring:** manual DI in `AppContainer.kt`, the only place implementations are chosen. Every subsystem sits behind an interface.
- **Main packages:**
  - `assistant/`: voice session and conversation;
  - `ai/`: Gemini;
  - `diagnostics/`: OBD, Bluetooth and DTCs;
  - `connectivity/`: connection log, internet monitor, adapter manager, voice diagnostics;
  - `handsfree/`: hands-free contracts;
  - `dashboard/`, `ui/`, `data/`.

## Voice
- **Loop:** `VoiceSession` is an app-scoped state machine: listen → think → speak → listen again.
- **Mic safety:** half-duplex, so the mic is never open while Penelope speaks. Silence, busy and network errors have bounded retries. The session pauses when the app goes to the background or audio focus is lost.
- **Recognition:** Android's `SpeechRecognizer`, with an on-device retry when offline.
- **Speech:** Android TTS through `SpeechOutput`. A future engine such as ElevenLabs would implement the same interface.
- **Status notices:** queued between turns, never spoken over the user.

## AI (Gemini)
- **Connection:** Firebase AI Logic (`GenerativeBackend.googleAI()`). App Check is enforced, using the debug provider in debug builds and Play Integrity in release, with limited-use tokens. **No API key in the app.**
- **Routing:** `GeminiReasoningProvider` sits in front of a rule-based provider that always works.
  - Facts and readings come from app data, never from the AI.
  - Requests are single-flight, with cooldowns for busy, quota and configuration errors. Penelope never calls Gemini while offline.
- **Hands-free first:** `HandsFreeReasoningProvider` runs before Gemini, so music, message and call commands never reach the AI.

## Vehicle (OBD-II)
- **Layers:** transport (`AdapterTransport`; `BluetoothGateway` for Bluetooth Classic SPP) → `ObdManager` (ELM327 session, protocol fallback, read-only recovery) → diagnostics and UI.
- **Read-only:** `ReadOnlyCommandPolicy` is enforced inside the ELM327 session, beneath every caller.

## Diagnostics and quality
- **`ConnectionLog`:** in memory only, redacted, last 300 events. Areas: vehicle, internet, AI, voice, audio, app.
- **Crash reports:** Crashlytics with no user identifiers.
- **CI quality gate:** security scan, build, Detekt, tests with coverage, Lint, release build, and a report.

## iOS (📋 proposed)
- **A native app, not a port.**
- **Shared rules:** platform-neutral contracts and rules stay free of Android imports. `handsfree/` is test-enforced as the template.
- **Different platform limits:** iOS doesn't let third-party apps read messages or control other apps' music. See `docs/HANDS_FREE.md` §3.
- **Not started:** no iOS code, language or UI-framework decision yet.
