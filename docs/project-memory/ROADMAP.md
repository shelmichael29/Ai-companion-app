# Roadmap summary

**Authoritative:** `docs/ROADMAP.md`. This is the short version; keep both in step.

## Next phases (recommended order)
1. **Device verification** on the S26 Ultra, the adapter and the 528i. This is the biggest gap: nothing is device-verified yet.
2. **H1: media control.** Play, pause and skip through media keys; no special permission.
3. **A3 / A4:** DIY dashboard (A4, the Advanced dashboard, is superseded by the two-level decision of 2026-11-25; its pieces are inventoried in `docs/ADVANCED_FEATURES_INVENTORY.md`).
4. **Offline knowledge follow-ups:** sample-accuracy review of the code data; VIN-specific recall status (not possible with NHTSA's public API: needs a manufacturer or NHTSA VIN source; today it's a link); server-side bulletins and investigations.
5. **OBD hardening:** freeze-frame, VIN via Mode 09, foreground service for long sessions.
6. **Mechanic handoff report:** shareable PDF or text.
7. **Hands-free:** H2 read messages → H3 reply → H4 calls → H5 background voice and car microphone.

## Longer term
- Navigation (hands-free, via map apps).
- Native iOS app on the same product rules.
- BLE, Wi-Fi and USB adapters; BMW/N52 knowledge pack.
- Voice barge-in, ElevenLabs voice, steering-wheel activation.
- Predictive maintenance, health trends, trip summaries.
- `targetSdk` 37 (its own stage, with device testing).

Order and scope change only with the owner's approval. Record the decision in [DECISIONS.md](DECISIONS.md).
