# Penelope development prompt & merge log

Record each development prompt, its intended changes, the implementation branch/PR, and whether the code actually reached its target branch. **Writing a prompt is not the same as merging code.**

## Status definitions
- **Prompt drafted** — instructions exist, but code has not been verified.
- **In progress** — implementation is underway; no PR yet.
- **PR open** — code proposed but not merged.
- **Merged** — PR merge verified against the named target branch.
- **Closed (unmerged)** — proposed code was not merged.

## Entries

| Date (AZ) | Prompt / feature | Changes | Target branch | PR | Status | Tests |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-11-25 | Phase 1B (Prompt 2 of 4): Advanced features integrated into DIY (v0.10.0, build 11) | DIY applies saved connection settings (fixes the communication log never recording at DIY); fuel trims, MAP, MAF on DIY adapter card, polling and spoken list; protocol and drivetrain in DIY replies; Diagnostics tab opens from home Results at DIY; custom-settings troubleshooting tip; no voice, personality or Everyday change | `main` | feature/diy-advanced-integration | Merged (#75, squash 7fd796a) with owner approval | Local --release gate 1148 run, 0 failed; voice suite 163/0; device checks pending |
| 2026-11-25 | Phase 1A (Prompt 1 of 4): consolidate three knowledge levels into two (v0.9.0, build 10) | Only Everyday and DIY selectable; stored Advanced migrates to DIY in place; Advanced connection and diagnostic tools reachable from DIY; inventory for Prompt 2 | `main` | feature/two-knowledge-levels | PR #74 merged (squash b556a50) | Local gate; see `docs/QUALITY_SUMMARY.md`; device checks pending |
| 2026-11-22 | Drive (Phase 3): Drive screen with media, communications and future vehicle controls | Shared Drive screen on all levels; media keys/session and volume; read and reply to message notifications and dialer handoff with one voice and button confirmation; capability-only car controls (all unavailable); no new permissions; `docs/IOS_DRIVE_NOTES.md` | `main` | feature/drive-screen | Merged (#60, squash f63b0e1) with owner approval | See `docs/QUALITY_SUMMARY.md`; screenshots in `docs/screenshots/phase3`; device checks pending |
| 2026-11-21 | Phase 1: UI redesign and stabilization | Distinct Everyday / DIY / Advanced (black-and-grey) dashboards; per-level navigation; Drive placeholder; Live Data; More | `main` | feature/phase1-ui-redesign | Merged (#53, squash fbf0a8b) | 667 tests, 0 failed (local release gate); rendered screenshots; device check pending |
| 2026-11-21 | Clear codes (Mode 04) for DIY and Advanced | Confirmed, gated Mode 04 path (`VehicleWriteGate`); engine-off check; re-read and history note; button and two-step dialog; `docs/CLEAR_CODES.md` | `main` | feature/clear-codes | Merged (#58, squash 7b1e933) | 717 tests, 0 failed (release gate); safety review passed with fixes; real-car test pending |
| 2026-11-21 | Gemini personal details: automatic scrubbing | Phone numbers, emails, street addresses, links, VINs and labelled plates removed from service titles and problem descriptions before they reach Gemini; Settings text updated | `main` | feature/gemini-scrub-personal-details | PR open — **not merged** | 570 tests, 0 failed (local gate); device check pending |
| 2026-11-21 | Record device verification (VIN scanner, recalls) and owner tasks | Docs only: mark #41/#45 verified on the S26 Ultra; owner tasks done; Gemini decision noted | `main` | [#50](https://github.com/shelmichael29/Ai-companion-app/pull/50) | **Merged** (squash `5eb3fe9`, verified on `main`) | Owner device checks passed; gate PASS (local), 562 tests, 0 failed |
| 2026-11-21 | Recall accuracy and reporting (v0.7.1, build 8) | One recall service for every car and Penelope; failures never read as "no recalls"; source and date on every result; model-level vs VIN-verified kept apart; eligibility explained, never decided | `main` | [#45](https://github.com/shelmichael29/Ai-companion-app/pull/45) | **Merged** (squash `a842849`, verified on `main`) | 562 tests, 0 failed (local release gate); CI green; device verification pending |
| 2026-11-20 | VIN scanner, decoder and recalls | CameraX + ML Kit scan, guided flow, NHTSA decode and model-level recalls | `main` | [#41](https://github.com/shelmichael29/Ai-companion-app/pull/41), docs [#42](https://github.com/shelmichael29/Ai-companion-app/pull/42) | **Merged** (squash, verified on `main`) | 506 tests, 0 failed (local release gate); CI green; device verification pending |
| 2026-11-20 | Build identity + clear PR summaries | Show the source revision in the app footer and About build information; add a structured PR template and prompt/merge ledger | `main` | [#44](https://github.com/shelmichael29/Ai-companion-app/pull/44) | **Merged** (squash, verified on `main`) | CI green; device verification pending |
| 2026-11-22 | Phase 2: Live Data workspace | Customizable gauges (DIY and Advanced), named layouts per vehicle, demand-driven single poller, honest sensor states, Room v1 to v2 | `main` | feature/live-data-workspace | Merged (#59, squash 069727f) | 799 tests, 0 failed (release gate); device and adapter verification pending |

| 2026-11-24 | Assistant personalization and voice overhaul (v0.8.0, build 9) | Ten assistant profiles (personality + voice), honest voice selection, custom name, redesigned Settings personalization, hardcoded-name fixes; owner decisions 2026-11-24 applied (no hardcoded "Penelope" in user-visible text) | `main` | feature/assistant-personalization | Merged (#72, squash 580ecba) with owner approval; 1072 tests, 0 failed | 
| 2026-11-25 | Remove the Settings voice dropdown; add Online/Offline voice setting | Voice always follows the selected profile (stored non-match choices normalized to match and persisted); new `voice_source` setting (Offline default) that makes Online prefer network voices of the profile's gender with the existing fallback; honest no-online-voice note | `main` | feature/remove-voice-dropdown | In progress (not committed) | See `docs/QUALITY_SUMMARY.md`; screenshots in `docs/screenshots/personalization` |

## Checklist for every new development prompt
1. Add a dated entry when the prompt is created. State the requested changes and intended target branch. Use **Prompt drafted** until implementation is verified.
2. Link the feature branch and PR once created. Use **PR open** until GitHub confirms a merge.
3. Record CI/device testing (passed, failed, pending, or not run).
4. After a merge, verify its target branch and update the status to **Merged**. If closed without merge, record **Closed (unmerged)**.
5. In the assistant's user-facing message, repeat a brief summary: changes, PR link, test status, and **merged: yes/no**.
6. Keep each project/branch distinct. Merging a tester branch is **not** the same as merging into `main`.

This is a manually maintained project ledger. A PR template does not automatically update the ledger when a PR merges.
