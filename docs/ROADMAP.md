# Roadmap

## Done
- **M1 Foundation** — Compose app, profiles, dashboard, DTC/PID decoding.
- **M2 (Phase 2)** — Vehicle database selection + VIN decode, Room persistence, Penelope
  push-to-talk voice, read-only Bluetooth Classic OBD-II, per-vehicle history.

- **M3 (Phase 3)** — Hands-free continuous conversation, natural voice settings (pitch fix,
  voice/engine choice), Everyday/DIY/Advanced explanations, symptom conversations, Smart
  Diagnostics / SmartFix / Mechanic Pro contracts.

- **M4 (Phase 4)** — Penelope UI 2.0: design system, voice orb, floating navigation, redesigned
  home/chat/garage, vehicle showcase with user photos, beginner-first diagnostic result cards.

## Next (recommended order)
1. **Real-hardware validation** with the Panlong adapter on the 2011 BMW 528i: capture raw
   ELM327 transcripts (add a debug log export) and turn them into parser test fixtures.
2. **OBD-II hardening** — foreground service for long sessions, read VIN via mode 09 PID 02
   and compare with the profile, freeze-frame (mode 02) with codes, adaptive polling.
3. **Mechanic handoff report** — PDF/text via the share sheet: profile, codes with
   origin/time, freeze frame, history, observations (facts vs. hypotheses clearly separated).
4. **AI reasoning provider (opt-in)** — behind `ReasoningProvider`; grounded in
   `VehicleContext`; key handling without bundling secrets.
5. **Voice upgrades** — voice barge-in with echo cancellation (see ARCHITECTURE), ElevenLabs
   `SpeechOutput`, car hands-free microphone routing, steering-wheel/media-button activation.
6. **BLE and Wi-Fi adapters** — `ElmLink` implementations.
7. **Knowledge packs** — vehicle-specific `DtcKnowledgeSource` (e.g. BMW/N52 notes), offline.
8. Predictive maintenance, health trends, trip summaries, proactive spoken warnings.

## Housekeeping
- Add Paparazzi (test-only) screenshot tests to CI once golden images are agreed; Phase 4 used it
  temporarily for visual review.
- AGP/Kotlin/AndroidX upgrades (AGP 9 is a larger migration; do separately).
- Compose UI tests and Room DAO instrumented tests (need an emulator/device in CI).
- Units preference (miles/km, °F/°C); currently U.S. units with metric in parentheses.
