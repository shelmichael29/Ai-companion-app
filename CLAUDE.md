# Instructions for Claude Code sessions: Penelope (AI Car Companion)

Android app (Kotlin, Jetpack Compose, Gradle), Firebase (AI Logic/Gemini, App Check, Remote Config, Crashlytics). Owner's test device: Galaxy S26 Ultra. Test car: 2011 BMW 528i F10 with the N52 **inline-six** (never "V6").

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
- **Vehicle commands:** never send clear-code (Mode 04), reset, coding, actuator or any vehicle-modifying command from automated tests. Tests use `FakeElmAdapter`. The read-only allowlist is `ReadOnlyCommandPolicy`.
- **Secrets and personal data:** never print or commit API keys, tokens, `google-services.json`, keystores, App Check debug tokens, VINs or saved `.logcat` files.
- **Crash reports:** never attach user identifiers to them.
- **Gemini:** never embed the API key in the app. Gemini goes through Firebase AI Logic with App Check enforced; don't bypass enforcement.
- **Voice:** two-way voice conversation is non-negotiable; don't replace it with placeholders.

## Workflow
- **Branches:** work on a feature branch from the latest `main`, one PR per stage.
- **Merging:** never merge without the owner's explicit approval.
- **Integration base:** `main`, which contains only the Penelope app.
- **ENET tester is separate:** the experimental BMW ENET tester is not part of main and not part of Penelope. It lives only on the `experiment/*` branches (`experiment/bmw-enet-tester`, PR #11). Never merge it into main, add it to Penelope's build, CI or quality gate, or mix it into Penelope work, unless the owner explicitly asks.
- **Settings:** knowledge level (Everyday / DIY / Advanced) and home layout (Easy / Customizable) are independent settings.

## Project memory
Durable project knowledge (vision, decisions, UI preferences, platforms, OBD, milestones, open issues) is in `docs/project-memory/`; start with its `README.md`. Each fact is marked Built / Built-not-verified / Planned / Undecided: never treat a plan as a finished feature. The code wins over memory; fix the memory file in the same PR. Outside memory (e.g. from ChatGPT) is added only after review in `docs/project-memory/import-review.md` and the owner's approval.
