# Quality summary

One line per stage, newest first. Full reports are attached to each CI run as the `quality-report-<commit>` artifact. See `docs/DEVELOPMENT_QUALITY_GATES.md`.

| Date | Stage | Automated gate | Tests | Lint (err/warn) | Detekt | Device testing | Status |
|---|---|---|---|---|---|---|---|
| 2026-11-21 | Device verification: VIN scanner, decoder and recalls (#41, #45) (docs only) | GATE_RESULT | TESTS_RESULT | LINT_RESULT | DETEKT_RESULT | PASS (owner, Galaxy S26 Ultra, build 0.7.1 (8): paper and in-car scan, recalls online/offline, Penelope, camera indicator) | VERIFIED |
| 2026-11-21 | Recall accuracy and reporting (v0.7.1, build 8; release gate) | PASS (local) | 562 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-21 | Project memory: VIN scanner, Gemini routing and offline pack marked merged (docs only) | PASS (local) | 506 run, 0 failed | 0 / 1 | 0 | NOT RUN (no app change) | PASS |
| 2026-11-20 | VIN scanner, decoder and recalls (release gate) | PASS (local) | 506 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-19 | Offline knowledge pack | PASS (local) | 429 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-19 | Chat screen layout, scrolling and compact voice bar | PASS (local) | 409 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-19 | Gemini primary routing and reliability | PASS (local) | 398 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-19 | Project memory and doc corrections (docs only) | PASS (local) | 375 run, 0 failed | 0 / 1 | 0 | NOT RUN (no app change) | PASS |
| 2026-11-19 | Model delegation agents (config only) | PASS (local) | 364 run, 0 failed | 0 / 1 | 0 | NOT RUN (no app change) | PASS |
| 2026-11-19 | Phase H0: hands-free foundation and debugging audit | PASS (local) | 375 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
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

- **Hands-free foundation (H0):** diagnostics log, routing replies and audio route on the phone (`docs/HANDS_FREE.md` §4). Pending.

- **Gemini on the phone:** answer source, timeout durations and App Check behavior (`docs/GEMINI_ROUTING.md` §6). Pending.

- **Chat screen on the phone:** layout, scrolling, keyboard, 200% font, TalkBack (`docs/DEVICE_TESTING.md` §0b). Pending.

- **VIN scanner and recalls, remaining phone checks:** the main flows are verified (see the table). Still to check: flashlight, camera permission prompts and the settings round trip, rotation, TalkBack, spoken guidance; for recalls, Try again after a failure, a car added by Search or Manual, an old model year (`docs/DEVICE_TESTING.md` §1c). Pending.

- **Offline knowledge on the phone:** first-launch install time, storage, recall/rating answers, adding a car offline (`docs/OFFLINE_KNOWLEDGE.md` §5). Pending.

## Known warnings
- `OldTargetApi`: `targetSdk` is still 35 while compileSdk is 37. Kept on purpose: raising the target changes runtime behavior on the phone and needs its own stage with device testing.
