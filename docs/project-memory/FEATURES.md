# Features

Status labels are explained in [README.md](README.md). Only the features under **Verified** have been confirmed on real hardware; everything under **Built** still needs the phone, adapter or car.

## Verified (✅ in `main`, automated tests, and checked on real hardware)
| Feature | Since | Verified on | Details |
|---|---|---|---|
| VIN scanner (CameraX + on-device ML Kit), guided Add Vehicle flow, NHTSA decode, model-level recall campaigns (never labeled VIN-confirmed); VIN sent by POST body | #41 | Galaxy S26 Ultra, build 0.7.1 (8), 2026-11-21, by the owner: VIN scanned from paper and from the 528i (windshield plate and door sticker), confirmed and saved; camera indicator turns off every time. Still to check: flashlight, camera permission prompts and the settings round trip, rotation, TalkBack, spoken guidance | `docs/VIN_SCANNER.md` |
| Recall accuracy: one recall service for scanned, typed, searched and manual cars and for Penelope; failures never read as "no recalls"; source and date checked on every result; model-level vs VIN-verified kept apart; eligibility explained, never decided (v0.7.1) | #45 | Galaxy S26 Ultra, build 0.7.1 (8), 2026-11-21, by the owner: recall results with source and date; airplane mode says it couldn't check (never "no recalls"); Penelope's recall answer. Still to check: Try again after a failure, a car added by Search or Manual, an old model year | `docs/VIN_SCANNER.md` §5 |

## Built (🟢 in `main`, automated tests, not device-verified)
| Feature | Since | Details |
|---|---|---|
| Vehicle profiles, VIN decode (NHTSA), catalog (FuelEconomy.gov), offline cache | M2 | `docs/ARCHITECTURE.md` |
| Penelope continuous voice conversation; voice, rate and pitch settings | M3 | `docs/ARCHITECTURE.md` |
| Everyday / DIY / Advanced explanations | M3 | |
| Read-only Bluetooth Classic OBD-II: codes (modes 03/07), live data, history | M2–M4.1 | |
| OBD connection settings by level; redacted traffic log; read-only console | M4.1 | |
| Penelope UI 2.0: orb, floating navigation, garage, results, user car photo | M4 | |
| Gemini answers via Firebase AI Logic + App Check, rule-based fallback | 5A | `docs/FIREBASE_SETUP.md` |
| Home layout per level (Easy / Customizable), Extras | A1 | |
| Everyday Driver dashboard; Midnight and Amethyst styles (v0.7.0) | A2 | |
| Quality gates, secret scan, Crashlytics (no user IDs) | Phase 6 | `docs/DEVELOPMENT_QUALITY_GATES.md` |
| Connection stages, protocol fallback, bounded recovery; Gemini reliability fixes | Phase 7 | `docs/CONNECTION_RECOVERY.md` |
| Adapter discovery, bounded connect, auto-reconnect, voice announcements | Bluetooth foundation | `docs/BLUETOOTH_CONNECTION.md` |
| Hands-free foundation: routing, honest "not yet" replies, voice/audio diagnostics | H0 | `docs/HANDS_FREE.md` |
| Offline knowledge pack: 9,533 generic + 6,368 make-specific codes, 35,869 EPA vehicles, 20,128 recall campaigns, 8,432 crash ratings, 24 symptom guides | #40 | `docs/OFFLINE_KNOWLEDGE.md` |
| Claude Code model delegation agents (developer tooling, not an app feature) | #35 | `CLAUDE.md`, `.claude/agents/` |

## In progress (🔵)
| Item | Where |
|---|---|
| Phase 1 UI redesign: distinct Everyday / DIY / Advanced (black-and-grey) dashboards, per-level navigation, Drive placeholder, Live Data, More (`docs/PHASE1_UI.md`) | branch `feature/phase1-ui-redesign` (PR open) |
| ChatGPT memory import | waiting for the owner to provide it (`IMPORT_REVIEW.md`) |

## Proposed (📋)
- **Hands-free:** media control (H1), read messages (H2), reply (H3), calls (H4), background voice and car microphone (H5), navigation.
- **Dashboards:** DIY (A3) and Advanced (A4).
- **Diagnostics:** freeze-frame, VIN via Mode 09, foreground service for long OBD sessions, mechanic handoff report, BMW/N52 knowledge pack, predictive maintenance.
- **Adapters:** BLE, Wi-Fi and USB.
- **Voice:** barge-in with echo cancellation, ElevenLabs voice, steering-wheel button activation.
- **Platform:** iOS app; `targetSdk` 37.

## Deferred (⏸) or experimental
- **BMW ENET tester:** experimental, only on `experiment/*` branches (PR #11). Not part of Penelope.
- **Clear trouble codes:** only ever as a separate, explicit, confirmed feature. Never automatic.
- **Vehicle simulation mode:** excluded from recent phases by the owner.
- **Becoming the phone's default dialer:** not planned (`docs/HANDS_FREE.md`).
