# Phase 1D (Prompt 4B): final testing, stabilization and regression protection (v0.12.0, build 14)

Owner prompt 4B, 2026-11-26. Branch `stabilization/phase1d-prompt4b`, based on `main` at 7542ca1. This is the last prompt of the knowledge-level overhaul (Prompts 1, 2, 3, then 4A and 4B).

**What this stage is:** testing and stabilization only. Confirmed defects were fixed, each with a regression test that failed first; nothing was redesigned and no feature was added. Everything here ran on the JVM (unit tests, Robolectric renders of the real composables with fixture data, fakes for the adapter, speech and AI). **No device, adapter or car test was run**; those are listed as NOT RUN in `docs/DEVICE_TESTING.md`, section 0h.

## 1. Prerequisites (checked against `git log` on `main`)
| Prompt | PR | State |
|---|---|---|
| Prompt 1: two knowledge levels (v0.9.0) | #74 | Merged |
| Prompt 2: Advanced features into DIY (v0.10.0) | #75 (docs #76) | Merged |
| Voice dropdown removal, Online/Offline voice | #77 (docs #78) | Merged |
| Prompt 3: DIY Overview (v0.11.0) | #79 (docs #80) | Merged |
| 4A / Priority 0: OfflineKnowledgeTest flake (v0.11.1) | #81 (docs #82) | Merged |

The 4A investigation (`docs/OFFLINE_KNOWLEDGE_TEST_FLAKE.md`) was reviewed: test-JVM heap exhaustion under Robolectric, fixed with a 1 GB unit-test heap plus a guard test; installer hardened. No prerequisite was incomplete.

## 2. Baseline (untouched `main`, 1c60be8, before any Phase 1D change)
`scripts/quality-gate.sh "Phase 1D baseline (untouched main 1c60be8)"`: **PASS** (NEEDS MANUAL VERIFICATION). Build PASS; Detekt 0 issues; Lint 0 errors, 2 warnings (the known `OldTargetApi`); tests **1302 run, 1302 passed, 0 failed, 0 skipped** (unit 600, integration 649, regression 36, accessibility 17); secret scan PASS; line coverage 71.5 % (informational). The docs-only #82 that followed changes no code.

## 3. Findings
### Fixed (each with a regression test that failed before the fix)
| # | Severity | Defect | Cause | Fix | Regression test |
|---|---|---|---|---|---|
| F-1 | Low (accessibility) | DIY floating navigation: on a narrow display at a large font, the four unselected tabs were **42.5 dp** wide at 320 dp and 200 % font (46 dp at 344 dp and 150 %), below the 48 dp minimum touch target. Found by the new Phase 1D layout audit. | Above 130 % font only the selected tab shows its label and it always took 2.8 shares of the bar. | The selected tab now gives up width first (`selectedTabWeight`), so every tab keeps 48 dp wherever that fits. At 360 dp and wider nothing changes (every existing screenshot is unchanged, section 6). | `regression/NavBarTouchTargetRegressionTest` (failed with tab widths `[118, 42.5, 42.5, 42.5, 42.5]` and `[128, 46, 46, 46, 46]`) |

Before and after (DIY Overview, 320 dp, 200 % font): `docs/screenshots/phase1d/before-nav-320dp-font200.png`, `docs/screenshots/phase1d/after-nav-320dp-font200.png`. At that size the selected tab's visible label is now ellipsized ("Overvi…") so the other tabs keep 48 dp; TalkBack still reads the full label.

### Remaining (not fixed; reasons given)
| # | Severity | Finding | Why not fixed here | Proposed next step |
|---|---|---|---|---|
| L-1 | Low (performance) | **Home polls readings it no longer shows.** `HomeViewModel` always subscribes to the shared `liveReadings` list: 5 sensors at Everyday, up to 14 at DIY (plus the Overview strip's layout sensors). Since Prompt 3 only the internal Advanced dashboard displays that list. Effect measured with a scripted adapter (100 ms per answer): a 5-reading voice answer took 900 ms instead of 500 ms while 7 sensors were polled. A single reading waits for at most one poll command (verified, section 8). | The same polling is what notices, within seconds on Home, that the ignition went off or the adapter lost power (link health is driven by request failures; there is no Bluetooth disconnect receiver). Removing it would change the Everyday connection status behaviour, which must stay unchanged. This is a product trade-off, not a clear defect. | Owner decision: either keep it, or replace it with a light health check (for example one PID every few seconds) and stop polling unseen readings. |
| L-2 | Low (visual, extreme setting) | At **320 dp width and 200 % font** only: the Chat title wraps inside the assistant's name ("Penelop / e") and the Live Data tab label "Dashboard" is clipped to "Dashboa" (`docs/screenshots/phase1d/chat_small_320x640_light_font200.png`, `diy-livedata_small_320x640_light_font200.png`). At 360 dp and 200 % both are fine. | 320 dp is below the 360 dp the layouts are designed for. The Chat header lives in `ui/assistant/`, which this stage must not touch without owner approval. | Ellipsize both labels (single line). Needs approval for the Chat header. |
| L-3 | Low | Home shows "Add your car" until the database first answers (`HomeUiState.loaded` is not used by Home), so a cold start may flash it for a moment. | Inspection and renders only; whether it is visible on the S26 Ultra needs the phone. Changing it alters Everyday. | Check on the phone (0h, item 11.1); if visible, show nothing until loaded. |
| L-4 | Low (storage) | `KnowledgePackInstaller`: if the app is killed between installing a new pack and deleting the old one, the old ~41 MB pack stays, because a later start reuses the new pack and returns before cleanup. | Found by inspection; needs a process kill in a millisecond window. No functional effect. Kept out to avoid changing the code 4A just hardened. | Run the old-pack cleanup on the reuse path too, with a test. |
| L-5 | Info | Landscape: the Everyday orb fills the first screen; the mic is reached by scrolling. All controls stay reachable and labelled. | Design, and Everyday must not change. | Owner decision if landscape matters. |
| L-6 | Info | 4A measured up to 755 MB heap occupancy in the knowledge tests with the 1 GB test heap. | Not a failure; it passed every run here. | Keep watching CI; raise the test heap only with evidence. |
| — | Info | Lint's 2 warnings are unchanged from the baseline: `OldTargetApi` (targetSdk 35, kept on purpose) and `ComposableNaming` on a helper in the existing `AccessibilityAuditTest` (test code). | Raising targetSdk needs its own stage with device testing; renaming a test helper is cleanup outside this stage. | — |

No critical, high or medium defect was found.

## 4. Voice and personalities (section 3 of the prompt)
- **Not modified:** `git diff` against `main` is empty for `assistant/`, `handsfree/`, `ui/assistant/`, `ai/` (including `PromptBuilder.kt`) and `ui/settings/PersonalizationSection.kt`. No voice, personality, mapping, provider, recognition, TTS or audio change.
- **Verified by tests (run in the final gate):** the voice-related suite (42 classes: every test in `assistant`, `ai` and `handsfree`, plus the personalization, chat, name, Gemini-error, settings-migration, level-independence, voice-during-Live-Data and update-persistence classes) ran **438 tests, 0 failed** (baseline on `main`: 40 classes, 432 tests, 0 failed). They cover: all 10 profiles, voice follows the profile, gender-correct voice selection and honest fallback (`VoiceSelectorTest`, `AssistantProfilesTest`, `VoiceConfigFactoryTest`), saved preferences and speed/pitch clamping (`SettingsMigrationTest`), preview and configure-while-speaking (`VoiceConfigHolderTest`), continuous conversation, interruptions, ending, background release and network recovery (`VoiceSessionTest`, `VoiceResilienceTest`, `VoiceNetworkRecoveryTest`), the custom name everywhere and never hard-coded (`NoHardcodedAssistantNameTest`, `AssistantNamePolicyTest`, `RuleBasedNameTest`), personality instructions byte-identical apart from tone (`PromptPersonaTest`, `GeminiPersonaRoutingTest`), level switching leaves voice untouched (`KnowledgeLevelPersonalityIndependenceTest`), and the Settings screen (`PersonalizationUiTest`, `SettingsViewModelPersonalizationTest`).
- **New in Phase 1D:** `InPlaceUpdatePersistenceTest` writes the real `app_settings` SharedPreferences the way v0.8.0 left them for an Advanced user (profile Ace, custom name, an old voice-dropdown choice, speed 1.2, pitch 0.9, an engine, continuous off, Advanced layout), opens them with the current code and restarts twice: the user lands on DIY with every voice setting kept, the old voice choice follows the profile, and the stored file no longer changes after the first start. A change made after the update survives a restart.
- **Not testable on the JVM (NOT RUN):** real speech recognition and TTS, real voices per profile, audio focus, an actual APK installed over an older one. Device checklist 0h, category 4.

## 5. Migration and persistence (section 6)
Exactly two selectable levels; stored Advanced migrates to DIY in place once (`SettingsMigrationTest`, 26 tests; `KnowledgeLevelPersonalityIndependenceTest`; `KnowledgeLevelPickerTest`). Home layouts per level are independent of the level and of the personality (`DiyLayoutMigrationTest`). Room database v1 to v2 migration from the committed schema (`AppDatabaseMigrationTest`); no destructive fallback is configured. New: the v0.8.0 upgrade path through real SharedPreferences (section 4) and custom connection settings surviving a restart while applying only at DIY.

## 6. Everyday regression (section 4)
Everyday renders from three builds were compared pixel by pixel (Phase1 home, home-full, more and drive, Phase3 drive screens: 23 images):
- **Pre-consolidation v0.8.0 (580ecba, before Prompt 1) vs `main` before Phase 1D:** 22 of 23 byte-identical; `phase1/home-full_everyday_412x1800_dark_font100.png` differs only in the build-label strip (3,564 px in a 401 × 19 px box: "v0.8.0-beta • Build 9" vs "v0.11.1-beta • Build 13").
- **`main` before Phase 1D vs this branch:** all 178 renders the existing screenshot tests produce were compared: **174 byte-identical; the 4 that differ are the tall renders that show the build label, and differ only in that strip** (1,699 px in a 351 × 19 px box: "v0.11.1-beta • Build 13 • 1c60be8b" vs "v0.12.0-beta • Build 14 • …"). Of the 27 Everyday renders, 26 are byte-identical and the 27th is `phase1/home-full_everyday_412x1800_dark_font100.png` (build label only). The nav-bar fix changes no existing render.
- Personalization renders differ from v0.8.0 because #77 removed the voice dropdown (an approved change; Settings, not the Everyday dashboard).
Everyday behaviour (no Live Data tab, recommended connection settings, plain wording) is covered by `Phase1aEverydayUnchangedTest`, `Phase1bEverydayUnchangedTest`, `LevelNavigationTest`, `EverydayDashboardTest`.

## 7. DIY feature completeness (section 5; everything formerly DIY plus Advanced)
Checked against `docs/ADVANCED_FEATURES_INVENTORY.md`: nothing was lost; every row is reachable at DIY or is listed there as Planned.
| Feature | Where at DIY | Evidence (automated, JVM) |
|---|---|---|
| Modernized Overview, compact assistant, expandable conversation | Overview tab | `DiyOverviewTest` (43), `Phase1cScreenshotTest`, Phase 1D renders |
| Live Data, sensors, gauges and graphs, saved layouts, widget customization | Live Data tab | `LiveDataWorkspaceTest`, `LiveDataViewModelTest`, `GaugeSemanticsTest`, `AppDatabaseMigrationTest`, `Phase2ScreenshotTest` |
| Read codes, technical explanations, diagnostic history | Diagnostics tab, Overview health panel | `ObdManagerTest`, `DiyCapabilityMatrixTest`, `ReasoningProviderTest`, `HistoryTest` |
| Confirmed clear codes (Mode 04 only through `VehicleWriteGate`) | Diagnostics, Overview | `ClearCodesSafetyTest`, `ClearCodesUiTest`, `ClearCodesViewModelTest`, `ObdRobustnessTest` (no write from tests) |
| Maintenance and repair history | Overview panel, History | `HistoryTest`, `DiyOverviewTest` |
| Manual adapter selection, advanced connection settings, protocol, polling rate, timeouts, communication log, read-only console | OBD-II adapter and Connection screens (More, Overview) | `BluetoothConnectionFoundationTest`, `ObdConnectionSettingsTest` (24), `AdvancedConnectionAppliesNowTest`, `DiyCommunicationLogRegressionTest`, `NoInaccessibleDiagnosticsTest` |
| Vehicle management | More, Garage; Overview header switcher | `InMemoryVehicleRepositoryTest`, `VehicleSelectionTest`, `VinFlowUiTest` |
| Drive | Drive tab | `DriveUiTest`, `DriveViewModelTest`, `Phase3ScreenshotTest` |
| Home layout (Easy / Customizable) | Settings | `DashboardPlannerTest`, `AccessibilityAuditTest` |
**Planned, not built (documented, not implemented):** freeze frame as a feature (console only today), readiness monitors, Overview panel show/hide/reorder (❓ owner decision).

## 8. Bluetooth and diagnostic reliability (section 8)
- Existing tests cover first connection, automatic reconnect, manual selection, initialization, protocol fallback, timeouts, unexpected disconnects, Bluetooth off, adapter unavailable, BLE refused, degraded-link recovery and read-only recovery commands (`BluetoothConnectionFoundationTest`, `ObdConnectionRecoveryTest`, `ObdRobustnessTest`, `BlockingConnectTest`, `LivePidPollerTest`). Everyday always uses the recommended settings; DIY applies the saved custom settings (owner decision 2026-11-25; `InPlaceUpdatePersistenceTest`, `NoInaccessibleDiagnosticsTest`).
- **Does Live Data polling delay voice answers or congest the adapter?** Investigated in code: every command goes through one fair `Mutex` in `Elm327Session`, the poller takes it one command at a time, reads run on `Dispatchers.IO`, and the poller sends nothing without a screen asking for readings. New `VoiceReadDuringLivePollingTest` pins this down: a voice reading asked while a poll command is on the adapter waits exactly that one command (200 ms with 100 ms answers), never the rest of the round, and polling continues; a 5-reading answer at most doubles (500 ms alone, 900 ms while 7 sensors are polled). The remaining cost is the unseen Home polling (L-1).
- No transport was rewritten; no reading is fabricated (`LiveDataStateMapper` states, `DiyOverviewTest` "every strip state is honest").

## 9. AI and Firebase (section 9)
Covered by `GeminiReliabilityTest`, `GeminiIntegrationTest`, `GeminiPrimaryRoutingTest`, `TemporaryAiErrorsRegressionTest`, `GeminiFalseQuotaRegressionTest`, `InternetReliabilityTest`, `VoiceNetworkRecoveryTest`, `PersonalDetailScrubberTest`, `CrashReportingTest`: offline fallback, timeouts (a late answer is discarded), rate limits, App Check and authentication failures named plainly, no overlapping requests, the microphone resumes after a failure, no identifiers in crash reports. Models, providers, credentials and App Check were not changed. Real Gemini requests need a Firebase build and the App Check token: NOT RUN (0h, category 9).

## 10. APK install and update (section 10)
`applicationId` stays `com.aicarcompanion.app`; versionCode 13 to **14**, versionName 0.11.1 to **0.12.0** (minor bump, as every earlier stage of this overhaul did); `InPlaceUpdatePersistenceTest` asserts both. Signing, Firebase config and `google-services.json` handling are unchanged. The build label and About read the git revision from `BuildConfig.SOURCE_REVISION`. Installing over the previous APK on the phone: NOT RUN (0h, category 1).

## 11. Visual QA (section 11)
New `Phase1dVisualQaTest` renders 16 screens (Everyday home with car, without car, loading, mic error with failed adapter; DIY Overview connected, without car, disconnected; Live Data dashboard, all sensors, editing; More, Drive at both levels; Chat; Clear codes) in 10 configurations not covered before: 150 % font dark and light, 200 % light, 360 dp at 200 %, a 320 dp small display at 100 % and 200 %, landscape at 100 % and 150 %, and an S26 Ultra-like window (412 × 891 dp at 3.5 px/dp) at 100 % and 130 %. That is 160 renders (`app/build/screenshots/phase1d`; a selection is committed in `docs/screenshots/phase1d`). Each render is audited: every tappable element has a TalkBack label and a 48 × 48 dp area, nothing tappable or readable leaves the screen sideways, and no unrelated controls overlap. The audit found F-1; the renders were then reviewed by eye (L-2, L-3, L-5). Contrast stays covered by `UiPolishTest` and `SettingsThemeTextTest`. Real TalkBack, system-bar and keyboard insets on the phone: NOT RUN (0h, category 10).

## 12. Performance and stability (section 12)
Inspected: one poller for the whole app, leases released when screens leave, `collectAsStateWithLifecycle` and `WhileSubscribed` on every screen flow, so nothing polls in the background; the DIY Overview ticks once a second only to age readings (Everyday does not tick). No leak, duplicate poller or lifecycle defect was found; unseen Home polling is L-1. Battery, memory and frame timing need the phone: NOT RUN.

## 13. Offline knowledge regression protection (section 7)
- **20 independent JVM executions** of the whole offline-knowledge package (`OfflineKnowledgeTest`, `KnowledgePackInstallerTest`, `InstallFailureTest`, `RecallAssistantTest`; 44 tests each) with `./gradlew :app:testDebugUnitTest --rerun --tests 'com.aicarcompanion.app.knowledge.*'`: **20 of 20 passed** (each 44 tests: `OfflineKnowledgeTest` 21, `KnowledgePackInstallerTest` 12, `InstallFailureTest` 2, `RecallAssistantTest` 9; 0 failures, 0 errors, 0 skipped; 12 to 19 s each). No retry, skip or suppression.
- The same tests also passed inside every full-suite run of this stage (baseline gate, final gate, PR CI).
- Install, integrity (checksum and size), reuse after restart, replacement after an update, failure reporting by stage, and concurrent installs are covered by the 4A tests. Device re-check of the pack: NOT RUN (0h, category 12).

## 14. Automated results
| Run | Result |
|---|---|
| Baseline gate, untouched `main` | PASS: 1302 run, 0 failed; Detekt 0; Lint 0 errors, 2 warnings |
| First final gate, `--release` (33b06e4) | **BLOCKED** by Detekt: 2 `EmptyElseBlock` findings in the new `Phase1dVisualQaTest` (one-line `while` loops). Everything else passed: build, release build, 1473 tests run 0 failed, Lint 0 errors 2 warnings, secret scan. Fixed in the test code (no rule or baseline change) and the gate was rerun. |
| Final gate, `--release` | PASS (NEEDS MANUAL VERIFICATION) at ed3fce8: build, release build, Detekt 0 issues, Lint 0 errors and 2 warnings (unchanged), secret scan PASS; **1473 run, 1473 passed, 0 failed, 0 skipped** (unit 766, integration 649, regression 41, accessibility 17); line coverage 71.6 % |
| GitHub Actions on the PR | PR #83: see section 19 |

Tests added: `NavBarTouchTargetRegressionTest` (5), `Phase1dVisualQaTest` (160), `InPlaceUpdatePersistenceTest` (4), `VoiceReadDuringLivePollingTest` (2). No test was skipped, ignored or weakened; no Lint or Detekt setting changed; no baseline added.

## 15. Files changed
- `app/src/main/java/com/aicarcompanion/app/ui/navigation/FloatingNavBar.kt` (F-1)
- `app/build.gradle.kts` (version 0.12.0, build 14)
- Tests: `regression/NavBarTouchTargetRegressionTest.kt`, `phase1d/Phase1dVisualQaTest.kt`, `phase1d/InPlaceUpdatePersistenceTest.kt`, `diagnostics/VoiceReadDuringLivePollingTest.kt`
- Docs: this report, `docs/DEVICE_TESTING.md` (0h), `docs/QUALITY_SUMMARY.md`, `docs/DEVELOPMENT_PROMPT_LOG.md`, `docs/PENELOPE_TRACKER.md`, `docs/ROADMAP.md`, `docs/project-memory/DEVELOPMENT_STATUS.md`, `docs/project-memory/FEATURES.md`, `docs/screenshots/phase1d/`

## 16. Device testing still required (all NOT RUN)
The full checklist is `docs/DEVICE_TESTING.md`, section 0h, in 12 categories (install and updates, Everyday, DIY, voice and personalities, Bluetooth, Live Data, diagnostics, dashboard customization, Firebase and Gemini, accessibility, persistence, offline knowledge), each item marked phone-only, adapter or vehicle, with safe conditions for the car. The earlier sections 0d to 0g are still open too.

## 17. Known limitations
- Screens are Robolectric renders with fixture data, not phone captures; fonts and anti-aliasing can differ slightly on the S26 Ultra.
- The adapter and car are simulated; real ELM327 timing, clone quirks and the F10's answers are untested.
- The S26 Ultra configuration is approximated by window size and density; One UI display scaling, cut-outs and edge insets are not simulated.

## 18. Recommended next priorities
1. Owner device pass of `docs/DEVICE_TESTING.md` 0h, starting with category 1 (in-place update) and 4 (voice), then the car (5 to 7).
2. Decide L-1 (unseen Home polling versus connection-status freshness).
3. Approve or decline the small label fixes in L-2 (Chat header is in the protected voice UI folder).
4. L-4 installer cleanup on the reuse path, with a test, in a small follow-up.
5. Then the next roadmap item (owner's choice); Overview panel customization stays ❓ until decided.

## 19. Pull request and CI
PR #83 (`stabilization/phase1d-prompt4b` into `main`), open, **not merged**; STOP after 4B for owner review. GitHub Actions results are reported on the PR.
