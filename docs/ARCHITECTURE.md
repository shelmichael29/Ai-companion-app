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

## Visual design system (Phase 4, "Penelope UI 2.0")

All UI is Jetpack Compose. `ui/theme/Theme.kt` defines a fixed brand theme (the earlier
wallpaper-based dynamic color is gone) with separate light ("pearl") and dark ("night") palettes
built from #397EBC blue, #35C9F4 cyan, #8655F5 violet, #E9DEFF lavender, #F6F7FF pearl and
#1C254A ink. Extra tokens (gradients, glass fill/border, glow colors, semantic success/warning/danger,
`reducedMotion`) live in `LocalPenelope`. Typography uses the system sans-serif (One UI Sans on
Galaxy, Roboto elsewhere) in sp units, so it follows the user's font size.

Reusable components (`ui/design/`): `PenelopeBackground` (gradient, glows, optional flowing waves),
`GlassPanel`, `GradientButton`, `GlassIconButton`, `SuggestionPill`, `StatusBadge`,
`ExpandableSection`, `PenelopeTopBar`, `BrandWordmark`, `LogoMark`, `PenelopeIcons` (line icons
from path data), and `VoiceOrb`.

**Voice orb.** Canvas-drawn; `OrbMode.from(VoiceState)` maps only real session states. While
listening, ripples follow the recognizer's real `onRmsChanged` level (`VoiceSession.inputLevel`).
Android TTS doesn't expose output amplitude, so the speaking waveform animates while the state is
`Speaking` rather than tracking loudness. Infinite animations exist only while the orb is
composed, pause with the frame clock in the background, and are replaced by static per-state
visuals when "Reduce motion" (in-app) or Android's "Remove animations" is on.

**Glass.** Compose has no backdrop-blur for content behind a surface (`Modifier.blur` blurs the
element itself; `RenderEffect` applies to its own layer), so glass is a translucent fill plus a
luminous gradient border and tinted shadow. That's the closest performant native alternative.

**Navigation.** A floating translucent pill with four tabs (Home, Garage, Chat, History).
Settings, OBD-II adapter, car editing and Car health are pushed routes reachable from those tabs.
Screens fade/slide (disabled under reduced motion). Gesture-navigation insets are respected.

**Vehicle images.** No stock car images are bundled (licensing). Users can add their own photo
via the system photo picker (no storage permission); it's downscaled and stored in private app
storage. Otherwise an illustrated placeholder is drawn.

**Diagnostic results.** `DiagnosticPresentation` (pure, tested) turns a trouble code into: a short
headline, what's happening, urgency (icon + words + color), next steps, and expandable technical
details. Sample data is badged.

## Gemini conversations (Phase 5A)

```
VoiceSession ─▶ ConversationManager ─▶ GeminiReasoningProvider (ReasoningProvider)
                                         ├─ AiRouting: DETERMINISTIC ─▶ RuleBasedReasoningProvider (unchanged)
                                         │    end/pause/repeat · urgent safety symptoms · "can I still drive"
                                         │    live readings & sensor lists (direct requests) · code reads
                                         │    code definitions · adapter status · help
                                         └─ AI ─▶ AiChatClient (FirebaseAiChatClient → Firebase AI Logic → Gemini)
                                              system instruction = PromptBuilder (persona, rules, level,
                                              verified VEHICLE FACTS) + last 12 turns of history
                                              ─▶ ReplyGuard (clean for speech; reject invented codes or
                                                 unverified readings → deterministic answer instead)
```

- AI replies carry `ReplyBasis.AI_EXPLANATION`; vehicle data and safety warnings never come from
  the model. History includes deterministic turns, so follow-ups ("what should I do about it?")
  have context. `ConversationManager.clear()` resets it.
- Failures (offline, timeout, App Check, quota, disabled service, model unavailable) never throw:
  the deterministic answer is returned with a one-time notice, and `AiStatus` shows the reason.
- Model: Remote Config `penelope_model`, validated by `ModelSelection` (stable Flash only).
- Security: no Gemini key in the app; App Check (Play Integrity in release, debug provider only
  in `src/debug`); limited-use tokens; `google-services.json` git-ignored, CI uses a secret.
  Setup: `docs/FIREBASE_SETUP.md`.
- Future: function calling for live data, response streaming to TTS for lower latency, and an
  ElevenLabs `SpeechOutput`; none require changes to this routing.

- **Safety questions:** about 25 everyday phrasings (`CommandParser.CAN_I_DRIVE_PHRASES`) are recognized as "can I still drive?". Examples: "can I keep driving", "should I pull over", "do I need to stop", "is it dangerous to drive". They're checked before every other topic except urgent symptoms, so they always get the built-in, offline answer immediately, never Gemini. Urgent symptoms such as smoke or oil pressure still take priority, with their specific advice.
- **Guard around the AI client:** `GeminiReasoningProvider.generateSafely` turns any exception from the client, a `LinkageError` (for example, a missing SDK class), or no answer within 30 s into an `AiResult.Failure`. Penelope then gives her built-in answer and never crashes or goes silent. Cancellation, such as the user ending the conversation, still stops the request.

## Penelope (voice) — hands-free conversation

```
 Mic button ─▶ VoiceSession (state machine, app-scoped)
                 │  Idle → Listening → Processing → Speaking → (auto) Listening → …
                 │  Paused / Error: microphone off
                 ├─ SpeechInput   (AndroidSpeechRecognizer; one on-device retry after a network error)
                 ├─ ConversationManager ─▶ ReasoningProvider (RuleBasedReasoningProvider)
                 │                           ├─ CommandParser (intents, spoken DTCs, symptoms, assistant name)
                 │                           ├─ VehicleContextProvider / DiagnosticCommandHandler / DtcKnowledgeSource
                 │                           └─ follow-up memory (last topic, pending question)
                 ├─ SpeechOutput  (AndroidSpeechOutput: rate + pitch + engine + voice; serialized utterances)
                 └─ AudioFocusController (one ref-counted GAIN_TRANSIENT request, USAGE_ASSISTANT)
```

**Continuous conversation.** `VoiceSession.start()` begins a conversation when "Keep listening
after Penelope answers" is on (default). After each reply finishes, listening resumes
automatically. It ends on "goodbye / that's all / stop" (`ConversationAction.END`), pauses on
"pause", or via the End / mic buttons.

**Safety rails (all unit-tested with fake engines):**
- *Half-duplex.* `listen()` stops collecting at the final result, which closes the recognizer
  before Penelope thinks or speaks; listening resumes `resumeDelayMillis` (450 ms) after speech
  ends so speaker audio can die away. `EchoGuard` drops transcripts that match what she just said.
- *No runaway loops.* Two silent turns → pause ("I'll pause for now"). Recognizer-busy retries
  are capped (2). Network, audio, language, and permission errors stop the session with a clear
  message.
- *Microphone never left on.* The mic is open only in `Listening`. `onAppBackgrounded()`
  (Activity `ON_STOP`) pauses; losing audio focus to another app (e.g. a call) while speaking
  or thinking pauses.
- *No overlapping speech.* `AndroidSpeechOutput.speak` is mutex-serialized and flushes the queue.

**Audio routing.** Speech uses `USAGE_ASSISTANT` attributes and transient audio focus, so it
plays on the current media route (car speakers when the phone is on car Bluetooth; other
media pauses during the conversation). The microphone is whatever the system recognizer
chooses, normally the phone's mic. Using the car's hands-free (HFP/SCO) microphone would need
`AudioManager.setCommunicationDevice` and changes recognition quality, so it's left for
real-car testing.

**Barge-in (voice interruption) path.** Today: tap to interrupt (mic button or Stop), which is
reliable. Android's `SpeechRecognizer` can't safely run while TTS plays: it would hear Penelope.
True voice barge-in needs echo cancellation: capture with `AudioRecord` using
`MediaRecorder.AudioSource.VOICE_COMMUNICATION` (hardware AEC), run a small voice-activity
detector during speech, and on detected user speech call `VoiceSession.interrupt()`; or move to
a streaming voice service with built-in echo cancellation behind `SpeechInput`/`SpeechOutput`.
`VoiceSession.interrupt()` is already the single entry point for that.

**Voice quality.** The old build never set pitch, so the phone's system TTS pitch applied (and
the speed slider allowed 1.6×). Now rate (0.5–1.5×) and pitch (0.75–1.25) are separate settings,
both default 1.0 and always applied, clamped on read. Users can choose the speech engine
(e.g. Samsung vs Google) and a voice (filtered to their language, offline and higher-quality
first), preview it, and reset to natural. A failed engine falls back to the phone default.

**ElevenLabs later:** implement `SpeechOutput` (stream audio, honor `stop()`, report `Completed`
only after playback ends so auto-listening stays half-duplex). Keep keys out of the APK.

**AI reasoning later:** implement `ReasoningProvider`; it receives `AssistantRequest`
(utterance, name, level) and must use `VehicleContextProvider`/`DiagnosticCommandHandler` for
facts and readings, never generating numbers itself.

**TalkBack.** Penelope's voice and TalkBack are independent. Controls have content
descriptions and state descriptions; the status line is a polite live region.

## Beginner-first explanations

`ExplanationLevel` = Everyday (default) / DIY / Advanced, stored in settings and passed in every
`AssistantRequest`. The reasoning is identical across levels; only rendering changes:
- `DtcInfo` carries `simple`, `diyChecks` and `technical` text plus a plain `severity.advice`.
- `ObdPid.friendlyName` and `minLevel` control which live readings beginners see, and live
  polling only requests readings visible at the current level.
- Conversational overrides: "explain that more simply" (Everyday), "give me the technical
  explanation" (Advanced), "tell me more" (one level up), "show me the sensor readings".

## Smart Diagnostics (foundation only)

Package `diagnostics/smart`:
- `SymptomClassifier` and `SymptomGuide`: recognize everyday descriptions ("it's shaking", "a
  noise when I turn", "using so much gas", "losing power", "won't start"); urgent categories
  (overheating, smoke or burning, brakes, fuel smell, flashing check-engine light, oil pressure)
  get safety guidance first. Penelope asks one simple follow-up question, gives general
  guidance framed as possibilities, and saves the description and answer as a `SYMPTOM`
  observation in the car's history.
- Data contracts for the future engine: `SymptomReport`, `DiagnosticEvidence` (always with
  origin and time), `PossibleCause` (cannot be `CONFIRMED` without a recorded test result),
  `DiagnosticAssessment` (urgency, next steps, explicit `missingData`), `SmartDiagnosticsEngine`.
- `DiagnosticDataPlan`: which readings matter per symptom (fuel trims, MAF, MAP, misfire
  counters, freeze frame…), flagging what needs manufacturer-specific support.
- New standard PIDs: short- and long-term fuel trims (banks 1 and 2), MAF, MAP.

## SmartFix and Mechanic Pro (contracts only)

- `smartfix/`: `RepairKnowledgeSource` → `RemedyLookup` with up to three `RemedyCandidate`s.
  Each must cite sources (TSB, documented failure, repair outcomes); numeric success rates exist
  only as `SourcedFigure`. Confirmatory tests come before part replacement. Shipped default:
  `NoRepairKnowledge`.
- `mechanic/`: `MechanicNetwork` (search, send request), `MechanicHandoffReport`,
  `ShareConsent` (nothing is shared without it), `MechanicProfile`. Shipped default:
  `MechanicNetworkNotAvailable`. The contract lives here so the two apps stay loosely coupled
  (a future HTTPS API, not shared code).

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

### Connection settings by skill level (Phase 4.1)

The automotive skill level (Everyday / DIY / Advanced, `AppSettings.explanationLevel`) decides
how much of the connection is exposed. It is independent of any future home-screen layout
setting (the planned Easy Mode and Customizable layouts): layout must not change connection
behavior, and the skill level must not change the home layout.

| Level | Adapter screen |
|---|---|
| Everyday | One **Connect my adapter** button. `AdapterAutoSelector` picks the last-used adapter, else the only paired device that looks like an OBD-II adapter, and otherwise asks. It never guesses an unrelated device such as headphones. Protocol detection, timing and reconnection are automatic. MAC addresses, radio types and protocols are hidden. |
| DIY | Full paired-device list with technical details. Also: **Reconnect**, **Reset adapter** (full `ATZ` re-initialization), and **Connection details** (transport, firmware, protocol, port voltage, requests/timeouts, average latency). Plain-language **Troubleshooting** comes from `ConnectionTroubleshooter`. |
| Advanced | Everything above plus **Advanced connection settings** (`AdvancedConnectionScreen`). |

`ObdConnectionConfig` (persisted by `ObdConfigStore` in its own `obd_settings` preferences file):

| Setting | ELM327 effect | Applies |
|---|---|---|
| Protocol: automatic or one of 1–9 | `ATSP0`, `ATSPAn` (try n, then search automatically: the default for manual), or strict `ATSPn`. If the adapter rejects it, the app falls back to `ATSP0` and records the rejection. | Re-initializes |
| Response timeout 1–10 s and retries 0–3 | App-side wait for the `>` prompt. A timeout is retried, but `ATZ`/`ATWS`/`ATD` are never retried. | Immediately |
| Adaptive timing: normal, aggressive or off | `ATAT1` / `ATAT2` / `ATAT0` plus `ATSTxx`, with an adapter timeout of 50–1000 ms in 4.096 ms units | Re-initializes |
| Live-data polling 0.25–5 s | Pause between polling rounds | Immediately |
| Automatic reconnection and 1–5 attempts | Backoff of 2 s, 5 s, then 10 s. When off, the app reports `Failed` instead of retrying. | Immediately |
| Compact responses (expert) | `ATS0`; the parser accepts both formats | Re-initializes |
| Communication log | Redacted in-memory `TrafficLog` | Immediately |

- **Advanced settings apply only at the Advanced level.** `ObdConnectionConfig.effectiveFor(level, stored)`
  returns `RECOMMENDED` for Everyday and DIY, so changing the level can never leave a beginner
  with a manual protocol or aggressive timing. The recommended config sends exactly the
  pre-4.1 init sequence (covered by a test).
- **Settings only appear when they're supported.** Settings are listed only for implemented
  transports (`TransportSupport`: Bluetooth Classic today). During init, the session records what
  the adapter rejected (`AdapterCapabilities`), and the screen flags those options rather than
  pretending they work.
- **Adapter identification:** firmware comes from `ATZ`. `AT@1` (description) and `ATRV`
  (voltage) are read on demand. Firmware "v2.1" is flagged as a likely clone.
- **Connection test** (`runDiagnostics`) checks the link, adapter response time (`ATI`), port
  voltage, vehicle response (`0100` + protocol) and the timeout rate. Each check reports
  pass, check, problem or skipped, with a plain-language next step.
- **Privacy-conscious logging:** the log is off by default, kept in memory only, and cleared
  when turned off. Responses to mode 09 info requests (VIN, calibration IDs, CVNs, ECU name)
  are replaced with a redaction marker, as is any 17-character VIN-shaped token. Long hex
  data is left alone. Device names and Bluetooth addresses are never logged. **Copy log**
  exports the redacted text.
- **Expert console:** commands are checked twice. They must pass `ReadOnlyCommandPolicy`,
  and also a narrower console list: `ATI`, `AT@1`, `ATRV`, `ATDP(N)` and modes 01, 02, 03, 06, 07,
  09 and 0A. Commands that change adapter state, such as echo, headers, protocol or resets,
  are refused there because they would break the app's parsing mid-session; those settings are
  changed through the settings, which re-initialize safely. Clearing codes (04), UDS
  write/reset/routine/security services and custom headers stay blocked everywhere.
- **Safety:** none of these settings changes how safety answers are routed, or turns missing
  data into values. A reading that times out is reported as missing.

## Persistence

Room schema v1 (unchanged in Phase 3) (`app/schemas/…/1.json`): `vehicles`, `service_records`, `dtc_reads` +
`dtc_records` (a read with zero codes is still recorded), `sensor_readings`, `observations`,
`mileage`, `response_cache`. All history tables cascade-delete with their vehicle. Enum columns
are stored by name and read with safe fallbacks (an unknown observation kind becomes an
unverified note). Future schema changes need a Room migration.
