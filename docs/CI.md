# Continuous integration (GitHub Actions)

Workflow: `.github/workflows/android-ci.yml`, named **Android CI**.

## When it runs
- **Every push to any branch.**
- **Every pull request**, run against the result of merging it into `main`.
- **Manually:** Actions → Android CI → **Run workflow**.

If you push again before a run finishes, the older run is cancelled.

## What it checks
| Step | Command | Fails the run when |
|---|---|---|
| Debug build | `./gradlew assembleDebug` | The code doesn't compile |
| Unit tests | `./gradlew testDebugUnitTest :enet-core:test` | Any test fails |
| Android lint | `./gradlew lintDebug` | Lint finds an error (warnings are reported but allowed) |

Lint runs even when tests fail, so one run shows both results.

## Versions
The workflow uses the versions the project needs. Java 17, from `app/build.gradle.kts` (`JavaVersion.VERSION_17`, `jvmTarget = "17"`). Android SDK platform 35 (`compileSdk`/`targetSdk = 35`), build-tools 34.0.0 (the AGP 8.7.3 default). Gradle 8.14.3, from the wrapper.

## Caching
`gradle/actions/setup-gradle` caches the Gradle distribution and downloaded dependencies, and also checks the Gradle wrapper is genuine. Only runs on `main` save to the cache; other branches and pull requests reuse it.

## Results
- **Summary:** each run's page shows a table with the build, test and lint results, the test counts, and the names of any failing tests.
- **Artifacts:**
  - `ai-car-companion-<version>-debug-<commit>`: the debug APK, kept 30 days.
  - `reports-<commit>`: HTML and XML test and lint reports, kept 14 days.
  - `build-reports-<commit>`: added only if the build itself fails.

## Firebase configuration (`google-services.json`)
The app does **not** need `google-services.json` to build. Without it, the Google Services plugin is skipped and Gemini answers are turned off; Penelope uses her built-in answers. CI therefore passes without the file.

To include Firebase in CI builds:
1. Open the repository on GitHub → **Settings → Secrets and variables → Actions → New repository secret**.
2. Name it `GOOGLE_SERVICES_JSON` and paste the **entire contents** of your `google-services.json` as the value.

What CI does with it:
- Writes it to `app/google-services.json` with owner-only permissions.
- Checks that it is valid JSON.
- Never prints it. GitHub also hides secret values in logs.
- Deletes it at the end of the run.

Pull requests from forks don't receive secrets, so they build without Firebase.

**Never** put these in the repository, secrets or logs:
- **App Check debug tokens:** register them only in the Firebase console.
- **Service-account keys:** CI doesn't need any.
- **Signing keystores:** release signing isn't done in CI.

Also note:
- **The Gemini API key lives only in Firebase.** The app never contains it.
- **APK artifacts include the Firebase config values.** That's normal for any Android app built with Firebase. This repository is private, so only people with access can download them.

## Tests that guard Penelope's safety (no live AI)
`app/src/test/java/com/aicarcompanion/app/regression/PenelopeSafetyRegressionTest.kt` replaces Gemini with a mock that deliberately gives unsafe advice. It checks that:
- **Urgent symptoms** (smoke or burning, fuel smell, flashing check-engine light, oil pressure, overheating, brakes) are always answered by the built-in safety guidance, at every detail level. Gemini is never asked, including when it's failing, off or not configured.
- **"Can I still drive?"** is always answered by the app.
- **Every Gemini failure type** falls back to the built-in answer, with a one-time notice and the correct status. Penelope recovers when Gemini comes back.
- **AI replies that invent trouble codes or live readings** are replaced with the verified answer.

The experimental ENET tester (`:enet-tester`, `:enet-core`) is built, tested and linted in the same run. Its APK is saved as `enet-tester-debug-<commit>` (see `docs/ENET_TESTER.md`).

## Compose UI tests (Robolectric)

UI tests such as `DashboardUiTest` render real screens on the JVM with Robolectric, inside the normal
`testDebugUnitTest` step.
- **No emulator, device or paid service** is needed.
- **The app's own `Application` isn't started:** tests use `@Config(application = Application::class)`, so Firebase, Bluetooth and the database stay out of them.
- **First run:** Robolectric downloads its Android runtime jar from Maven Central once; CI then caches it.

## Running the same checks locally
```
./gradlew assembleDebug testDebugUnitTest :enet-core:test lintDebug
```
