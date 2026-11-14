# Architecture

## Principles

1. **Never present fake data as real.** Readings, codes and records carry a `DataOrigin`
   (`SAMPLE`, `USER_ENTERED`, `OBD_BLUETOOTH`, `OBD_WIFI`); Penelope's replies carry a
   `ReplyBasis` (profile, saved history, live, sample, general knowledge, not available).
2. **Read-only vehicle access.** `ReadOnlyCommandPolicy` is enforced inside `Elm327Session`,
   below every caller, so no feature can send code-clearing, actuator or write commands.
3. **Replaceable parts.** Each subsystem sits behind an interface; implementations are chosen
   only in `AppContainer`.
4. **Facts vs. hypotheses.** Observations are typed (symptom, note, verified finding, possible
   cause). Penelope frames code causes as possibilities until verified.
5. **Lean, free, on-device.** AndroidX, Compose, Navigation, Room. Manual DI. No server, no keys.

## Package map

```
com.aicarcompanion.app
├── AppContainer.kt                 wiring (the only place implementations are chosen)
├── domain/model, domain/repository Vehicle, EngineSpec(+EngineLayout), history models, repo interfaces
├── data/vehicledb                  VehicleCatalogProvider (FuelEconomy.gov), VinDecoder (NHTSA vPIC),
│                                   VinValidator, VinScanner (future camera), HttpClient, ResponseCache
├── data/local                      Room DB (schema in app/schemas), DAOs, mappers, SettingsStore
├── data/memory, data/sample        in-memory repos (tests), labeled demo data
├── assistant/voice                 SpeechInput / SpeechOutput + Android implementations
├── assistant/conversation          CommandParser, ReasoningProvider (rule-based), ConversationManager,
│                                   VehicleContextProvider, DiagnosticCommandHandler
├── assistant/VoiceSession.kt       listen → think → speak loop (app-scoped)
├── diagnostics/obd                 ElmLink, Elm327Session, ReadOnlyCommandPolicy, parser, ObdManager
├── diagnostics/bluetooth           BluetoothGateway (paired devices, SPP sockets), StreamElmLink
├── diagnostics/dtc                 DTC decoding, code format, offline generic catalog (DtcKnowledgeSource)
└── ui/…                            Compose screens: dashboard, assistant, adapter, vehicles, history, settings
```

## Vehicle identification

`VehicleSelectionController` drives dependent selectors: year → make → model → engine →
transmission. Engines and transmissions are derived from FuelEconomy.gov "options"
(e.g. `Auto (S8), 6 cyl, 3.0 L`); single choices are auto-selected, then the record's details
fill a **review form** the user confirms. VIN decoding fills only fields NHTSA actually
determined and lists what it couldn't (trim, engine layout, engine code, transmission…).

Verified API quirks handled: FuelEconomy menus return an object (not array) for one result
and the literal body `null` for none; vPIC returns junk fields for invalid VINs, so fatal
error codes (6, 7, 8, 11, 400) discard the result. Neither source reports inline vs V layout
for the 2011 BMW 528i, so `EngineLayout` defaults to `UNKNOWN` (displayed "6-cyl", never "V6")
until the user confirms it.

Offline: responses are cached in Room (cache-first, 30-day freshness, stale copy used when the
network fails). Manual entry always works. Another provider (proprietary backend, offline
knowledge pack) implements `VehicleCatalogProvider` / `VinDecoder`.

## Penelope (voice)

```
 Mic button ─▶ VoiceSession ─▶ SpeechInput (Android SpeechRecognizer)
                    │                      └─ on network error: one retry with on-device recognizer (API 31+)
                    ▼
            ConversationManager ─▶ ReasoningProvider (RuleBasedReasoningProvider)
                    │                   ├─ CommandParser (intents, spoken DTCs, assistant name)
                    │                   ├─ VehicleContextProvider (profile, last code read, service, OBD state)
                    │                   ├─ DiagnosticCommandHandler (ObdManager: live reads, code reads)
                    │                   └─ DtcKnowledgeSource (GenericDtcCatalog)
                    ▼
            SpeechOutput (Android TextToSpeech, USAGE_ASSISTANT, transient audio focus w/ ducking)
```

- Push-to-talk only. Tap while listening = finish; tap while speaking = interrupt and listen;
  Stop button; losing audio focus (e.g. a phone call) stops speech.
- **ElevenLabs later:** implement `SpeechOutput` (stream audio, honor `stop()`); keep keys out
  of the APK (user-supplied key in encrypted storage or a small backend).
- **AI reasoning later:** implement `ReasoningProvider`; give it the same origin-labeled
  `VehicleContext` and route live reads through `DiagnosticCommandHandler`, never let it
  produce numbers itself.
- **Hands-free / wake word later:** a separate activation component that decides when to call
  `VoiceSession.startListening()`; Android restricts background microphone use, so this would
  need a visible foreground service and explicit user opt-in.
- Assistant name comes from `AppSettings.assistantName` (default "Penelope") and is stripped
  from the start of requests ("Hey Penelope, …").

## OBD-II (read-only)

```
 AdapterScreen / Penelope ─▶ ObdManager (state, reconnect, polling, code reads → history)
                                  └─ Elm327Session (init, mutex, ReadOnlyCommandPolicy, timeouts)
                                        └─ ElmLink ◀─ BluetoothGateway (RFCOMM SPP, secure → insecure)
                                                     (future: BLE GATT link, Wi-Fi TCP link)
```

- Only already-paired devices are listed (no scanning, no location permission). Android's
  reported device type distinguishes Classic / Dual / LE-only; LE-only is refused with an
  explanation until a BLE link exists.
- Init: `ATZ, ATE0, ATL0, ATS1, ATH0, ATAT1, ATSP0`, then `0100` (+`0120`, `0140`…) for
  supported PIDs and `ATDPN` for the protocol. If `0100` fails the adapter is "connected,
  vehicle not responding" (ignition off), with a re-check action.
- Parser handles multiple ECUs, ISO-TP multi-frame CAN, `SEARCHING…`, `BUS INIT`, `NO DATA`.
- Live polling runs only while a screen collects it and only for supported PIDs. Link loss
  → `Reconnecting` immediately → 3 attempts with backoff → `Failed`. User disconnect never
  reconnects.
- Generic OBD-II only: manufacturer modules (BMW DME-specific data, ABS, SRS, transmission
  adaptations) are **not** claimed.

## Persistence

Room schema v1 (`app/schemas/…/1.json`): `vehicles`, `service_records`, `dtc_reads` +
`dtc_records` (a read with zero codes is still recorded), `sensor_readings`, `observations`,
`mileage`, `response_cache`. All history tables cascade-delete with their vehicle. Enum columns
are stored by name and read with safe fallbacks (an unknown observation kind becomes an
unverified note). Future schema changes need a Room migration.
