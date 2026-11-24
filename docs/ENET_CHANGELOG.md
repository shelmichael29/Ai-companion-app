# ENET tester development changelog

Standalone BMW ENET tester only (`enet-core`, `enet-tester`). Not part of Penelope or `main`.

| Version (code) | Date | Changes | Tests | Known issues | Commit | PR | Merge status |
|---|---|---|---|---|---|---|---|
| 0.2.0-experimental (2) | 2026-11-21 | DME VIN read and four-module diagnostics | Unit tests; CI PASS | — | `eedf4d7` | #48 | Merged into `experiment/bmw-enet-tester` with owner approval |
| 0.3.0-experimental (3) | 2026-11-21 | 47-candidate F10 module selection, timing, cancellation; then per-module transport error resilience and ENET-only CI | Unit tests; CI PASS | 47-candidate scan not yet run on the car | `4a27959`, `fc5065f` | #52 | Merged into `experiment/bmw-enet-tester` with owner approval |
| 0.4.0-experimental (4) | 2026-11-24 | BMW fault memory: UDS 19 read (count, list, snapshots, extended, permanent), raw report save, guarded clear `14 FF FF FF` with fresh-read verification, BMW Diagnostics section, automatic build ID | 85 `enet-core` unit tests pass locally (30 new, simulated). APK build and lint: see PR | No live-car fault read or clear yet. No verified fault descriptions. The DTC guide named in the task wasn't available | See PR | See PR | **Open, awaiting owner approval** |

Every PR updates this table: version and build, date, changes, tests run, known issues, commit, PR number and merge status.
The app shows `versionName (versionCode) · build <short commit>`. The build ID comes from git automatically at build time.
