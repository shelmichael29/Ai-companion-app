# Development status

Snapshot as of **2026-11-21**. Per-stage gate results: `docs/QUALITY_SUMMARY.md`.

## Verified milestones
**Verified on the phone:** the VIN scanner, decoder and recalls (#41, #45) on the Galaxy S26 Ultra, build 0.7.1 (8), 2026-11-21, by the owner (scan from paper and from the 528i, recalls online and offline, Penelope's recall answer, camera indicator). Everything else below passed the automated quality gate and CI but hasn't been checked on the phone, adapter or car yet.

| Milestone | Gate result |
|---|---|
| M1–M4.1, 5A, A1, A2 (v0.7.0) | CI green at merge |
| Phase 6: quality gates | 322 tests, 0 failed |
| Phase 7: connection recovery and Gemini reliability | 333 tests, 0 failed |
| Bluetooth connection foundation | 364 tests, 0 failed |
| AGP 9 upgrade; fixed CI signing key | 364 tests, 0 failed |
| H0: hands-free foundation | 375 tests, 0 failed |
| Model delegation agents (Haiku / Sonnet / Opus) | Config only; CI green |
| Gemini primary routing (#37); offline knowledge pack (#40) | CI green at merge |
| VIN scanner, decoder and recalls (#41) | 506 tests, 0 failed |
| Recall accuracy and reporting (v0.7.1, build 8) (#45) | 562 tests, 0 failed; ✅ verified on the phone |
| Clear codes (#58) | 717 tests, 0 failed; safety review passed with fixes; real-car test pending |
| Phase 2 Live Data workspace (#59) | 799 tests, 0 failed; device and adapter checks pending |
| Drive, Phase 3 (#60, merged with owner approval) | 949 tests, 0 failed; privacy review passed with fixes; device checks pending |
| VIN scanner and recalls, device check | ✅ Galaxy S26 Ultra, build 0.7.1 (8), 2026-11-21, by the owner |

## Active pull requests
| PR | What | State |
|---|---|---|
| — | None open. ENET work moved to the separate `Enet-Development` repository (2026-11-23) | — |

## Outstanding tasks
- **Owner:** done, as reported by the owner on 2026-11-21: the `DEBUG_KEYSTORE_BASE64` secret, the App Check debug token, and the "Fresh" tag.
- **Gemini personal details:** decided on 2026-11-21: option 2, automatic scrubbing before anything is sent to Gemini. Built and merged (#51, `PersonalDetailScrubber`).
- **Device verification:** see below.

## Known issues
- **Lint warning:** `targetSdk` is 35, the only Lint warning; kept on purpose.
- **Gemini on the phone:** each new signing key needs a new App Check debug token, until the fixed key secret is added.
- **Interrupting Penelope:** tap only. Voice barge-in needs echo cancellation.
- **Car hands-free microphone (SCO):** not used yet; needs real-car testing.

## Device tests still required (all NOT RUN)
- Gemini with the App Check token; Crashlytics test crash.
- Everyday dashboard, voice and TalkBack on the S26 Ultra; every screen after the AGP 9 upgrade.
- Adapter checklist: `docs/BLUETOOTH_CONNECTION.md` §5.
- 528i connection recovery and voice auto-resume: `docs/CONNECTION_RECOVERY.md` §4.
- Hands-free foundation: `docs/HANDS_FREE.md` §4.
- Gemini routing and answer sources: `docs/GEMINI_ROUTING.md` §6.
- VIN scanner and recalls, remaining checks: flashlight, camera permission prompts and the settings round trip, rotation, TalkBack, spoken guidance; for recalls, Try again after a failure, a car added by Search or Manual, an old model year (`docs/DEVICE_TESTING.md` §1c). The main flows are verified.

When something is verified, mark it ✅ in [FEATURES.md](FEATURES.md) and `docs/QUALITY_SUMMARY.md`.

**v0.8.0 (9), assistant personalization and voice overhaul:** merged (#72, 2026-11-25); automated tests only. Voice gender, engine lists, network voices and TalkBack are NOT verified on a device.

**v0.11.0 (12), Phase 1C Prompt 3 (DIY dashboard modernization):** PR #79 open, not merged; automated tests and Robolectric renders only (fakes, not device captures). Device checks NOT RUN (`docs/DEVICE_TESTING.md` 0g). STOP: Prompt 4 (Testing and Stabilization) waits for the owner.

**v0.10.0 (11), Phase 1B Prompt 2 (Advanced features into DIY):** merged (#75, 2026-11-25); automated tests only (fakes). Device checks NOT RUN (`docs/DEVICE_TESTING.md` 0f).

**v0.9.0 (10), Phase 1A Prompt 1 (two knowledge levels):** PR open, not merged; automated tests only. Advanced migration, TalkBack and the Settings screen are NOT verified on a device. Merged (PR #74).
