# Instructions for Claude Code sessions: Penelope (AI Car Companion)

Android app (Kotlin, Jetpack Compose, Gradle), Firebase (AI Logic/Gemini, App Check, Remote Config, Crashlytics). Owner's test device: Galaxy S26 Ultra. Test car: 2011 BMW 528i F10 with the N52N (updated N52, engine code N52B30) **inline-six** (never "V6").

## Quality gate: required at the end of EVERY stage, feature or bug fix
Follow `docs/DEVELOPMENT_QUALITY_GATES.md`. In short:
1. **Run the gate:** `scripts/quality-gate.sh "<stage name>"` (add `--release` before releases). It compiles, runs Detekt, all tests with coverage, Lint, the secret scan, and writes `build/quality/QUALITY_REPORT.md`.
2. **Don't call a stage complete** while a critical check fails.
   - Fix the root cause.
   - Add a regression test that failed before the fix.
   - Rerun.
3. **Never:**
   - skip, `@Ignore` or weaken a test;
   - loosen Lint or Detekt;
   - add a baseline entry to get green;
   - report PASS for anything that didn't actually run. Use **NOT RUN** with the reason.
4. **End each stage with the Penelope Development Quality Report** (format in the doc). List the device, adapter and vehicle checks still needed, and add a line to `docs/QUALITY_SUMMARY.md`.
5. **Compiling is not testing.**

## Safety rules
- **Vehicle commands:** never send clear-code (Mode 04), reset, coding, actuator or any vehicle-modifying command from automated tests. Tests use `FakeElmAdapter`. The read-only allowlist is `ReadOnlyCommandPolicy`. Mode 04 is allowed solely through the confirmed Clear-codes gate (`VehicleWriteGate`, owner decision 2026-11-21, `docs/CLEAR_CODES.md`) and never from automated tests against real hardware.
- **Secrets and personal data:** never print or commit API keys, tokens, `google-services.json`, keystores, App Check debug tokens, VINs or saved `.logcat` files.
- **Crash reports:** never attach user identifiers to them.
- **Gemini:** never embed the API key in the app. Gemini goes through Firebase AI Logic with App Check enforced; don't bypass enforcement.
- **Voice:** two-way voice conversation is non-negotiable; don't replace it with placeholders.

## Workflow
- **Branches:** work on a feature branch from the latest `main`, one PR per stage.
- **Merging:** never merge without the owner's explicit approval.
- **Integration base:** `main`, which contains only the Penelope app.
- **ENET is separate:** the experimental BMW ENET tester is not part of Penelope. It lives only in the separate `shelmichael29/Enet-Development` repository (branches `experiment/*`). Never add ENET code, dependencies, build, CI or quality-gate steps to this repository, and never merge ENET work here, until the owner explicitly clears it. Standing merge approval never covers ENET.
- **Settings:** knowledge level (Everyday / DIY) and home layout (Easy / Customizable) are independent settings.

## Model delegation
Use the least expensive model that will give a reliable result. Escalate when a task proves harder than expected. Agents are defined in `.claude/agents/`.

| Task | Model | Agent |
|---|---|---|
| Finding files, call sites, settings or docs; summarizing existing code; simple repetitive lookups | Haiku | `penelope-explorer` (read-only) |
| Well-defined Kotlin, Compose, Room, Firebase, Gradle or test work; routine fixes with a known cause; Lint/Detekt fixes | Sonnet | `penelope-implementer` |
| Architecture and phase planning; complex or intermittent bugs; security, privacy and vehicle-safety decisions; major migrations; final review of risky changes | Opus | `penelope-architect` (read-only: plans and reviews) |

- **Plan with Opus, build with Sonnet:** for a substantial stage, get a plan from `penelope-architect`, implement it with `penelope-implementer`, and send risky diffs back to `penelope-architect` for review.
- **Escalate:** if `penelope-implementer` hits an unclear root cause or a security, privacy or safety question, hand it to `penelope-architect`. If `penelope-explorer` can't find something after a focused search, do the search in the main session instead.
- **Don't over-delegate:** small edits and questions already answered in the conversation are done directly. Subagents start without the conversation's context, so give them the goal, the relevant files and the constraints.
- **Same rules everywhere:** every agent follows this file, including the quality gate, safety and privacy rules. Delegating never skips a check.

## Project memory
Durable project knowledge is in `docs/project-memory/`. Read its `README.md` table and open **only** the file the task needs (status, features, architecture, decisions, design, vehicle, roadmap).
- **Status labels matter:** never treat 📋 Proposed or ❓ Undecided items as built.
- **The code and the authoritative docs win** over memory; fix the memory file in the same PR.
- **Record approved decisions** in `DECISIONS.md` and status changes in `FEATURES.md` / `DEVELOPMENT_STATUS.md`.
- **Outside memory** (e.g. from ChatGPT) goes through `IMPORT_REVIEW.md` and the owner's approval first.
