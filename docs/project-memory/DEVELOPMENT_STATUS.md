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
| Drive (Phase 3), branch `feature/drive-screen`, not merged | See `docs/QUALITY_SUMMARY.md`; hardware checks pending |
| Recall accuracy and reporting (v0.7.1, build 8) (#45) | 562 tests, 0 failed; ✅ verified on the phone |
| VIN scanner and recalls, device check | ✅ Galaxy S26 Ultra, build 0.7.1 (8), 2026-11-21, by the owner |

## Active pull requests
| PR | What | State |
|---|---|---|
| #11 | ENET tester (experimental) | Kept open and separate on purpose |

## Outstanding tasks
- **Owner:** done, as reported by the owner on 2026-11-21: the `DEBUG_KEYSTORE_BASE64` secret, the App Check debug token, and the "Fresh" tag.
- **Gemini personal details:** decided on 2026-11-21: option 2, automatic scrubbing before anything is sent to Gemini. Being built in its own PR.
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
