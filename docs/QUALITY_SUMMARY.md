# Quality summary

One line per stage, newest first. Full reports are attached to each CI run as the `quality-report-<commit>` artifact. See `docs/DEVELOPMENT_QUALITY_GATES.md`.

| Date | Stage | Automated gate | Tests | Lint (err/warn) | Detekt | Device testing | Status |
|---|---|---|---|---|---|---|---|
| 2026-11-23 | Android audit, dependency and lifecycle fixes | GitHub debug gates passed separately ([audit](https://github.com/shelmichael29/Ai-companion-app/actions/runs/45228040564), [Gemini](https://github.com/shelmichael29/Ai-companion-app/actions/runs/45226564887), [lifecycle](https://github.com/shelmichael29/Ai-companion-app/actions/runs/45228505827)) | Per-run reports | See reports | PASS | Pending phone and adapter | NEEDS MANUAL VERIFICATION |
| 2026-11-22 | Drive (Phase 3): media, messages, calls, capability-only car controls | PASS (local, --release) | 949 run, 0 failed | 0 / 2 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-22 | Phase 2: Live Data workspace (gauges, layouts, All sensors, DB v2) | PASS (local, --release) | 799 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-22 | Clear codes (Mode 04 via VehicleWriteGate, DIY/Advanced) | PASS (local, --release) | 717 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-21 | Phase 1 UI redesign: three interfaces (release gate) | PASS (local) | 667 run, 0 failed | 0 / 1 | 0 | NOT RUN (rendered screenshots only; no emulator) | NEEDS MANUAL VERIFICATION |
| 2026-11-21 | Gemini: scrub personal details before sending (owner decision, option 2) | PASS (local) | 570 run, 0 failed | 0 / 1 | 0 | NOT RUN | NEEDS MANUAL VERIFICATION |
| 2026-11-21 | Device verification: VIN scanner, decoder and recalls (#41, #45) (docs only) | PASS (local) | 562 run, 0 failed | 0 / 1 | 0 | PASS (owner, Galaxy S26 Ultra, build 0.7.1 (8): paper and in-car scan, recalls online/offline, Penelope, camera indicator) | VERIFIED |
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
| 2026-11-24 | Assistant personalization and voice overhaul (v0.8.0, build 9) | PASS (local, --release) | 1072 run, 0 failed (incl. owner-decision follow-up) | 0 / 2 | 0 | NOT RUN on device (voice gender, engines, network voices, TalkBack) | NEEDS MANUAL VERIFICATION |
| 2026-11-25 | Phase 1B Prompt 2: Advanced features into DIY (v0.10.0, build 11) | PASS (local: compile, Detekt, Lint, tests; full quality-gate.sh not run) | 1148 run, 0 failed, 0 skipped | Detekt 0, Lint 0 errors | Device checks NOT RUN (0f) |
| 2026-11-25 | Phase 1A Prompt 1: two knowledge levels (v0.9.0, build 10) | PASS (local, --release) | 1114 run, 0 failed | 0 / 0 errors, 2 warnings (Detekt 0, Lint 0 errors / 2 warnings), secret scan clean | 0 | NOT RUN on device (Advanced to DIY migration on an upgraded install, TalkBack, Settings) | NEEDS MANUAL VERIFICATION |
