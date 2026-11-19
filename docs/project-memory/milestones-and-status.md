# Milestones and status

Details and the full plan: `docs/ROADMAP.md`. Per-stage results: `docs/QUALITY_SUMMARY.md`.

## Done (in `main`)
| Milestone | Notes |
|---|---|
| M1–M3 | Foundation, vehicle database, Room, voice, read-only OBD-II, history, continuous conversation, knowledge levels |
| M4 / 4.1 | Penelope UI 2.0; OBD connection settings by level |
| 5A | Gemini via Firebase AI Logic with App Check |
| A1 / A2 (v0.7.0) | Dashboard groundwork; Everyday Driver dashboard; Midnight/Amethyst styles |
| Phase 6 | Quality gates: Detekt, Lint, coverage, secret scan, Crashlytics, CI report |
| Phase 7 | Connection recovery; Gemini reliability (false "daily limit" fix) |
| Bluetooth foundation | Discovery, bounded connect, plain status, voice announcements |
| Build upgrade | AGP 9.5, Gradle 9.9, Kotlin 2.4, Compose BOM 2026.09, Room 2.8 |
| CI signing | Optional fixed debug key |
| H0 | Hands-free foundation and debugging audit |

Almost everything above still needs **device verification** (see [known-issues-and-testing.md](known-issues-and-testing.md)).

## Next (📋)
1. **Device verification** on the S26 Ultra and the 528i (the biggest gap).
2. **H1: media control** (play, pause, skip via media keys).
3. **A3/A4:** DIY and Advanced dashboards.
4. OBD hardening: freeze-frame, VIN via Mode 09, foreground service for long sessions.
5. Mechanic handoff report.
6. Later hands-free phases: read messages (H2), reply (H3), calls (H4), background voice (H5), navigation; then the iOS app.
