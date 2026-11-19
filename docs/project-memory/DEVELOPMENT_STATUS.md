# Development status

Snapshot as of **2026-11-19**. Per-stage gate results: `docs/QUALITY_SUMMARY.md`.

## Verified milestones
**None yet on real hardware.** Everything below passed the automated quality gate and CI, but hasn't been checked on the phone, adapter or car.

| Milestone | Gate result |
|---|---|
| M1–M4.1, 5A, A1, A2 (v0.7.0) | CI green at merge |
| Phase 6: quality gates | 322 tests, 0 failed |
| Phase 7: connection recovery and Gemini reliability | 333 tests, 0 failed |
| Bluetooth connection foundation | 364 tests, 0 failed |
| AGP 9 upgrade; fixed CI signing key | 364 tests, 0 failed |
| H0: hands-free foundation | 375 tests, 0 failed |
| Model delegation agents (Haiku / Sonnet / Opus) | Config only; CI green |

## Active pull requests
| PR | What | State |
|---|---|---|
| Gemini primary routing | Make Gemini the primary intelligence; honest fallbacks; answer-source log | Open, waiting for the owner's review |
| #11 | ENET tester (experimental) | Kept open and separate on purpose |

## Outstanding tasks
- **Owner:**
  - add the `DEBUG_KEYSTORE_BASE64` secret;
  - register the App Check debug token;
  - create the "Fresh" tag on the releases page;
  - decide the Gemini personal-details question (see [DECISIONS.md](DECISIONS.md)).
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

When something is verified, mark it ✅ in [FEATURES.md](FEATURES.md) and `docs/QUALITY_SUMMARY.md`.
