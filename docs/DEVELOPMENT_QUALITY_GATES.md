# Development quality gates

**Permanent rule:** every development stage, feature or bug fix ends with the quality gate below. A stage is not complete while a critical check fails.

This document states the policy. Enforcement comes from GitHub Actions and branch protection; follow the setup at the end of this page.

## 1. The gate: required after every stage

| # | Check | How | Critical? |
|---|---|---|---|
| 1 | **Compile** the app (all modules) | `./gradlew assembleDebug` | Yes |
| 2 | **Static analysis**: Detekt | `./gradlew detekt` | Yes |
| 3 | **Static analysis**: Android Lint, including accessibility, security and lifecycle checks | `./gradlew lintDebug` | Yes (errors) |
| 4 | **Unit tests** | `./gradlew testDebugUnitTest` | Yes |
| 5 | **Integration and regression tests** | same command; see categories below | Yes |
| 6 | **Security**: secret scan of files and git history; Dependabot alerts | `python3 scripts/secret_scan.py --history` | Yes |
| 7 | **Accessibility**: TalkBack labels, 48 dp targets, contrast, 200% font | `AccessibilityAuditTest`, `UiPolishTest`, `SettingsThemeTextTest`, Lint | Yes |
| 8 | **Review** every warning and failure | report, Lint and Detekt HTML | Yes |
| 9 | **Quality report** | written automatically | Yes |
| 10 | **Device testing** on the phone, adapter and car | manual (section 6) | When the change touches it |

**All at once:** `scripts/quality-gate.sh "Stage name"`, adding `--release` to also build the release APK. It writes `build/quality/QUALITY_REPORT.md` and exits non-zero if a critical check fails.

### When a check fails
1. **Find the root cause.** "Flaky" isn't a cause.
2. **Fix it**, adding a regression test (section 4).
3. **Rerun** the affected tests, then the whole gate.
4. **Report** anything still unresolved, plainly, in the stage report.

**Never:**
- skip, disable or `@Ignore` a test, or weaken an assertion, to get green;
- lower a Lint or Detekt threshold, or add a baseline entry, to hide a new problem;
- report a check as **PASS** unless it actually ran and passed.

A check that couldn't run is **NOT RUN**, with the reason and the next step to verify it.

**Compiling is not testing.** A successful build says nothing about behavior.

### Changing a test's expectation
A test's expectation may change only when the intended behavior changes, never to make a failing build pass. Say so in the commit and the report, with the reason.

## 2. How CI runs it

**Workflow:** `.github/workflows/android-ci.yml`, a single workflow with no duplicates.

| Trigger | Runs |
|---|---|
| Pull request (any branch) | full gate |
| Push to `main` | full gate (and refreshes the build cache) |
| Tag `v*` or published release | full gate **plus release APK** |
| Manual (Actions → Android CI → Run workflow) | full gate; tick "Also build the release APK" for a release build |

**Behavior:**
- **Overlap:** a newer push to the same PR or branch cancels the older run.
- **Caching:** Gradle dependencies and the Robolectric runtime are cached. Only `main` writes the Gradle cache.

**Jobs:**
1. **Security scan** (about 30 s): secret scan of all files and the full history. Findings show file, line and kind, never the value.
2. **Build, analysis and tests:**
   - the steps: build → Detekt → tests with coverage → Lint → (release build) → **quality report**;
   - after a failure, later steps still run, so one report shows everything;
   - the job fails if any critical step failed.

**Results:**
- **Run page:** the quality report appears in the run's **Summary** tab.
- **Artifact `quality-report-<commit>`:** the report plus the test, Lint, Detekt and coverage HTML. Kept for 30 days.
- **Artifact `ai-car-companion-<version>-debug-<commit>`:** the installable test APK.

## 3. Reading the report

| Result | Meaning |
|---|---|
| PASS | The check ran and passed. |
| FAIL | The check ran and found problems. This blocks the stage. |
| NOT RUN | The check didn't run (an earlier failure, or it needs hardware). It never counts as passing. |

**Overall stage status:**
- **BLOCKED:** a critical check failed or didn't run.
- **NEEDS MANUAL VERIFICATION:** every automated check passed, and device, adapter or vehicle checks remain. This is the normal CI result.
- **PASS:** every automated check passed *and* any device testing the change needs was done and recorded.

### Test categories (by naming convention)
- **Regression:** package `regression`, or a class name containing `Regression`.
- **Integration:** rendered screens (Robolectric, packages `ui` and `quality`) and multi-component tests with simulated hardware. Class names contain `Manager`, `Session`, `Robustness`, `Resilience`, `Settings`, `Integration`, `Loopback`, `UiTest`, `DashboardTest` or `Reporting`.
- **Accessibility:** `AccessibilityAuditTest`, `UiPolishTest` (contrast) and `SettingsThemeTextTest` (dark-mode text).
- **Unit:** everything else.

## 4. Adding a regression test for every fixed bug
1. **Reproduce** the bug in a test that fails *because of* the defect. Show the failure.
2. **Fix** the code.
3. **Confirm** the new test passes and the whole suite still passes.
4. **Keep the test**, in the `regression` package or named `…RegressionTest`. Its KDoc says what broke and when.

Examples from Phase 6:
- `TemporaryAiErrorsRegressionTest`:
  - a per-minute rate limit was shown as the daily quota;
  - a socket timeout was shown as "offline";
  - a 503 was a generic error;
  - the home chip always said "Offline".
- `ObdRobustnessTest` › "a slow first vehicle answer keeps the adapter connected": one timeout used to drop the whole connection.
- `AccessibilityAuditTest` › home layout editor: the Move buttons were 40 dp.

## 5. Static analysis settings
- **Detekt:** rules in `config/detekt/detekt.yml`.
  - **What it catches:** likely bugs, coroutine misuse, swallowed cancellation and unsafe casts. Pure size and style rules are off.
  - **Baselines:** none at the moment, since every finding was fixed. If a baseline is ever needed (`./gradlew detektBaseline`), it lives in `config/detekt/baseline-<module>.xml` and must be justified in the PR.
- **Android Lint:**
  - errors fail the build;
  - accessibility, security, correctness and lifecycle checks stay on;
  - only "newer version available" notices are off, because Dependabot handles versions.
- **Kotlin compiler warnings:** currently 0. New warnings must be fixed or explained.
- **Coverage (Kover):** informational and not a gate. HTML: `app/build/reports/kover/htmlDebug`.
- **Duplicate code:** Detekt flags common copy-paste mistakes. Larger duplication is reviewed in PRs rather than gated, to avoid false positives.
- **Dependencies:**
  - **Dependabot** (`.github/dependabot.yml`) opens grouped weekly PRs for Gradle and GitHub Actions, and each one runs the full gate.
  - **The Gradle wrapper** is validated on every CI run.

## 6. Tests that need real hardware
Automated tests use simulated adapters (`FakeElmAdapter`, fake speech, fake AI). **No automated test may send clear-code, reset, coding or any vehicle-modifying command.** `ObdRobustnessTest` checks that across a full session.

Record manual results in the stage report as PASS, FAIL or NOT RUN.

| Area | Needs | What to check |
|---|---|---|
| Voice | Phone | Two-way conversation, auto-listen, interrupt, permission prompt |
| TalkBack | Phone | Focus order and labels on Home, Settings, Extras, Chat |
| Display | Phone | Dark/light, Midnight/Amethyst, 200% font, orb smoothness |
| Gemini | Phone + Firebase build + registered App Check token | Status "Working", a real answer |
| Crashlytics | Phone + Firebase build | Settings → About → "Send test crash report" (debug only), reopen the app, report appears in Firebase within minutes |
| OBD-II | Adapter + 2011 528i | Pair, connect, protocol, live data, read codes |

### Crashlytics
**Setup:**
- **When it's active:** Crashlytics is added when `google-services.json` is present (local file or the CI secret). Otherwise it's a no-op.
- **What a report contains:** stack trace, device model, Android version, app version and build type.
- **What is never sent:** user ID, VIN, location, car data or conversations. `CrashReportingTest` fails the build if code ever attaches identifiers.

**To verify on the phone (required before claiming it works):**
1. Install a debug build made with the Firebase config.
2. Go to Settings → About → **Send test crash report** → **Crash now**.
3. Reopen the app and wait a few minutes.
4. In the Firebase console → **Crashlytics**, a `TestCrashException` should appear.

## 7. What blocks merging into `main`
Once branch protection is on, a PR can merge only when **Security scan** and **Build, analysis and tests** have both passed.

**Turn it on (repository owner, one time):**
1. GitHub → repository **Settings → Rules → Rulesets → New ruleset → New branch ruleset**, or **Settings → Branches → Add branch protection rule** on older layouts.
2. **Name:** `main quality gate`. **Enforcement status:** Active. **Target branches:** *Include default branch*.
3. Enable **Require a pull request before merging**.
4. Enable **Require status checks to pass**, then add both checks: `Security scan` and `Build, analysis and tests`. They appear in the search box after the workflow has run once on a PR.
5. Optionally, enable **Block force pushes**.
6. Click **Create**.

Also turn on, under **Settings → Code security**:
- the **Dependency graph**;
- **Dependabot alerts**;
- **Dependabot security updates**;
- **Secret scanning** and **Push protection**, if your plan offers them for private repositories.

## 8. Stage report format
Every stage ends with this report, built by `scripts/quality_report.py`:

```
Penelope Development Quality Report
Stage / Branch / Commit
| Check | Result |  (build, Lint, Detekt, unit, integration, regression, security, accessibility, device)
Tests executed, passed, failed · problems found and fixed · remaining warnings
Device / adapter / vehicle checks needed · Overall: PASS / BLOCKED / NEEDS MANUAL VERIFICATION
```

Add the stage's line to `docs/QUALITY_SUMMARY.md`.
