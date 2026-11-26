# Roadmap

## Done
- **M1 Foundation** — Compose app, profiles, dashboard, DTC/PID decoding.
- **M2 (Phase 2)** — Vehicle database selection + VIN decode, Room persistence, Penelope
  push-to-talk voice (continuous conversation came in M3), read-only Bluetooth Classic OBD-II, per-vehicle history.

- **M3 (Phase 3)** — Hands-free continuous conversation, natural voice settings (pitch fix,
  voice/engine choice), Everyday/DIY explanations, symptom conversations, Smart
  Diagnostics / SmartFix / Mechanic Pro contracts.

- **M4 (Phase 4)** — Penelope UI 2.0: design system, voice orb, floating navigation, redesigned
  home/chat/garage, vehicle showcase with user photos, beginner-first diagnostic result cards.

- **M4.1 (Phase 4.1)**: OBD-II connection settings by skill level. Everyday is automatic;
  DIY adds manual selection, reconnect/reset, details and troubleshooting; Advanced adds the
  protocol, timing, retry, polling and reconnection settings, adapter identification, a
  connection test, a redacted traffic log, a read-only expert console and restore defaults.

- **5A**: Gemini answers through Firebase AI Logic with App Check, with a rule-based fallback.
- **A1 / A2 (v0.7.0)**: per-level home layout (Easy / Customizable, Extras); Everyday Driver
  dashboard; Midnight and Amethyst color styles.
- **Phase 6**: automated quality gates (Detekt, Lint, coverage, secret scan, Crashlytics, CI report).
- **Phase 7**: connection stages, protocol fallback and bounded recovery; Gemini reliability.
- **Bluetooth foundation**: adapter discovery, bounded connect, auto-reconnect, voice announcements.
- **Build upgrade**: AGP 9.5, Gradle 9.9, Kotlin 2.4, Compose BOM 2026.09, Room 2.8; optional fixed CI debug signing key.
- **H0: Hands-free foundation**: contracts for media, messages and calls, command routing, safety rules, iOS-ready core, and voice/audio/lifecycle diagnostics (`docs/HANDS_FREE.md`).

All of these passed the automated gate; none is device-verified yet (`docs/QUALITY_SUMMARY.md`).

### Phase 4.1 remaining work (documented, not yet built)
- **BLE adapters**: a GATT `ElmLink` (service/characteristic discovery for common FFE0/FFF0
  clones, MTU handling) plus scanning and its permission. Not shown in settings until built.
- **Wi-Fi adapters**: a TCP `ElmLink` (default 192.168.0.10:35000). It needs the socket bound
  to the adapter's Wi-Fi network through `ConnectivityManager`, because Android otherwise
  routes over mobile data when that Wi-Fi has no internet. It also needs host/port settings.
- **USB adapters**: USB host serial (FTDI/CH340/CP210x drivers) and a device-attach permission flow.
- **Adapter-specific extensions**: STN/OBDLink `ST` commands (faster CAN, larger buffers),
  detected from `STI`, with each command added to the allowlist only after review.
- **Log export to a file**: the copy-to-clipboard export is enough for now.

## Hands-free track
1. **H1: Media control**: play/pause/next/previous via media key events (no special permission); now-playing info later with opt-in notification access.
2. **H2: Read messages**: notification access, read on request only, never logged or sent to the AI.
3. **H3: Reply to messages**: notification reply actions, with spoken read-back and confirmation.
4. **H4: Calls**: place (with confirmation), answer, decline and hang up; contacts lookup.
5. **H5: Background voice**: microphone foreground service, car hands-free microphone routing.
6. **Navigation and the iOS app**: separate tracks, using the same `handsfree` contracts.

## Next (recommended order)
0. **Knowledge-level overhaul (4 prompts):** Prompt 1 (two levels, v0.9.0) merged; Prompt 2 (Advanced features into DIY, v0.10.0) merged (#75). Prompt 3 (DIY dashboard modernization, v0.11.0) built, PR #79 open and not merged. **STOP after Prompt 3:** Prompt 4 (Testing and Stabilization) does not start until the owner approves. Panel-level Overview customization (show, hide, reorder panels; choosing strip gauges separately from Live Data; several saved home configurations) needs separate owner approval and is not built.
1. **Real-hardware validation** with the Panlong adapter on the 2011 BMW 528i: capture raw
   ELM327 transcripts (DIY → Advanced connection settings → communication log → Copy log) and turn them into parser test fixtures.
2. **OBD-II hardening** — foreground service for long sessions, read VIN via mode 09 PID 02
   and compare with the profile, freeze-frame (mode 02) with codes, adaptive polling.
3. **Mechanic handoff report** — PDF/text via the share sheet: profile, codes with
   origin/time, freeze frame, history, observations (facts vs. hypotheses clearly separated).
4. ~~AI reasoning provider (opt-in)~~: done in 5A (Gemini via Firebase AI Logic).
5. **Voice upgrades** — voice barge-in with echo cancellation (see ARCHITECTURE), ElevenLabs
   `SpeechOutput`, car hands-free microphone routing, steering-wheel/media-button activation.
6. **BLE, Wi-Fi and USB adapters**: `ElmLink` implementations (see Phase 4.1 remaining work).
7. **Knowledge packs** — vehicle-specific `DtcKnowledgeSource` (e.g. BMW/N52 notes), offline.
8. Predictive maintenance, health trends, trip summaries, proactive spoken warnings.

## Housekeeping
- Add Paparazzi (test-only) screenshot tests to CI once golden images are agreed; Phase 4 used it
  temporarily for visual review.
- `targetSdk` 35 → 37: changes runtime behavior; needs its own stage with device testing.
- Compose UI tests and Room DAO instrumented tests (need an emulator/device in CI).
- Units preference (miles/km, °F/°C); currently U.S. units with metric in parentheses.
