# Quality summary

One line per stage, newest first. Full reports are attached to each CI run as the `quality-report-<commit>` artifact. See `docs/DEVELOPMENT_QUALITY_GATES.md`.

| Date | Stage | Automated gate | Tests | Lint (err/warn) | Detekt | Device testing | Status |
|---|---|---|---|---|---|---|---|
| 2026-11-17 | Fixed CI debug signing key | PASS (local) | 364 run, 0 failed | 0 / 0 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-17 | AGP 9 upgrade (AGP 9.5.1, Gradle 9.6, Kotlin 2.4, Compose BOM 2026.09, Room 2.8) | PASS (local) | 364 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-17 | Bluetooth connection foundation | PASS (local) | 364 run, 0 failed | 0 / 0 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-17 | Phase 7: connection recovery and Gemini reliability | PASS (local) | 333 run, 0 failed | 0 / 0 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-17 | Move ENET tester out of main | PASS (local) | 293 run, 0 failed | 0 / 0 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-17 | Phase 6: automated quality gates | PASS (local) | 322 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |

## Open manual verification
- **Gemini on the phone:** register the App Check debug token. Pending.
- **Crashlytics test crash:** verify on the phone. Pending.
- **Everyday dashboard, voice and TalkBack:** check on the S26 Ultra. Pending.
- **OBD-II with a real adapter and the 528i:** pending, including the Phase 7 checks in `docs/CONNECTION_RECOVERY.md` §4 (protocol fallback, recovery, stages).
- **Bluetooth adapter discovery, pairing, retries and reconnection with a real adapter:** pending (`docs/BLUETOOTH_CONNECTION.md` §5).
- **Gemini limits, internet drop and voice auto-resume on the phone:** pending (`docs/CONNECTION_RECOVERY.md` §4).

## Known warnings
- `OldTargetApi`: `targetSdk` is still 35 while compileSdk is 37. Kept on purpose: raising the target changes runtime behavior on the phone and needs its own stage with device testing.
