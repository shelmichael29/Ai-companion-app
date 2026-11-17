# Quality summary

One line per stage, newest first. Full reports are attached to each CI run as the `quality-report-<commit>` artifact. See `docs/DEVELOPMENT_QUALITY_GATES.md`.

| Date | Stage | Automated gate | Tests | Lint (err/warn) | Detekt | Device testing | Status |
|---|---|---|---|---|---|---|---|
| 2026-11-17 | Phase 7: connection recovery and Gemini reliability | PASS (local) | 333 run, 0 failed | 0 / 0 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-17 | Move ENET tester out of main | PASS (local) | 293 run, 0 failed | 0 / 0 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-17 | Phase 6: automated quality gates | PASS (local) | 322 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |

## Open manual verification
- **Gemini on the phone:** register the App Check debug token. Pending.
- **Crashlytics test crash:** verify on the phone. Pending.
- **Everyday dashboard, voice and TalkBack:** check on the S26 Ultra. Pending.
- **OBD-II with a real adapter and the 528i:** pending, including the Phase 7 checks in `docs/CONNECTION_RECOVERY.md` §4 (protocol fallback, recovery, stages).
- **Gemini limits, internet drop and voice auto-resume on the phone:** pending (`docs/CONNECTION_RECOVERY.md` §4).

## Known warnings
- None in the Penelope app (Lint 0 warnings).
