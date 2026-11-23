# Penelope branch audit

Snapshot of 49 remote branches at main `c04d23ac`. Reviewed 66 PR records. No open PRs at inventory time. The new audit branch should be retained until its PR is reviewed.

Deletion requires owner approval and a fresh check of tip, open PR heads and bases. Enable automatic deletion of completed feature branches after merges.

## Safe to delete (38)

| Branch | Tip | Evidence | Related PRs and bases |
|---|---|---|---|
| `chore/dependabot-agp-ignore` | `68e7d607` | Exact tip merged to main through PR #26 | #26 → main |
| `chore/fixed-debug-signing` | `8047530c` | Exact tip merged to main through PR #33 | #33 → main |
| `chore/model-delegation` | `c53b5152` | Exact tip merged to main through PR #35 | #35 → main |
| `chore/move-enet-out-of-main` | `70429557` | Tip reachable from main | #17 → main |
| `dependabot/github_actions/actions-38b5202653` | `e1f8b6a0` | Exact tip merged to main through PR #18 | #18 → main |
| `dependabot/gradle/testing-and-analysis-c10bc27092` | `26750c74` | Exact tip merged to main through PR #22 | #22 → main |
| `docs/enet-moved` | `457ec7ca` | Exact tip merged to main through PR #66 | #66 → main |
| `docs/log-45-merged` | `2f62fadb` | Exact tip merged to main through PR #47 | #47 → main |
| `docs/merge-records` | `2063b385` | Exact tip merged to main through PR #61 | #61 → main |
| `docs/n52n-engine` | `d00a4fd5` | Exact tip merged to main through PR #39 | #39 → main |
| `docs/penelope-development-tracker-20261121` | `4e83f54e` | Exact tip merged to main through PR #46 | #46 → main |
| `docs/project-memory` | `1645b362` | Exact tip merged to main through PR #36 | #36 → main |
| `docs/vin-memory-status` | `77c4774c` | Exact tip merged to main through PR #42 | #42 → main |
| `docs/vin-recall-device-verified` | `b832fc29` | Exact tip merged to main through PR #50 | #50 → main |
| `feature/agp9-upgrade` | `a4441289` | Exact tip merged to main through PR #32 | #32 → main |
| `feature/bluetooth-foundation` | `e4270b34` | Tip reachable from main | #25 → main |
| `feature/chat-layout` | `1b2ab58d` | Exact tip merged to main through PR #38 | #38 → main |
| `feature/ci-regression-tests` | `2122e0ca` | Tip reachable from main | #8 → main |
| `feature/clear-codes` | `5d170a65` | Exact tip merged to main through PR #58 | #58 → main |
| `feature/connection-recovery` | `aa4623ed` | Tip reachable from main | #24 → main |
| `feature/dashboard-foundation` | `406db2fa` | Tip reachable from main | #13 → main |
| `feature/drive-screen` | `da509558` | Exact tip merged to main through PR #60 | #60 → main |
| `feature/everyday-dashboard` | `4f0d25bd` | Tip reachable from main | #15 → main |
| `feature/gemini-primary` | `5b9f0836` | Exact tip merged to main through PR #37 | #37 → main |
| `feature/gemini-scrub-personal-details` | `e9460a66` | Exact tip merged to main through PR #51 | #51 → main |
| `feature/hands-free-foundation` | `8aa8040f` | Exact tip merged to main through PR #34 | #34 → main |
| `feature/live-data-workspace` | `311f6fc5` | Exact tip merged to main through PR #59 | #59 → main |
| `feature/offline-knowledge` | `cd0a7da3` | Exact tip merged to main through PR #40 | #40 → main |
| `feature/phase1-ui-redesign` | `532d3915` | Exact tip merged to main through PR #53 | #53 → main |
| `feature/phase4.1-obd-connection-settings` | `c48b1319` | Tip reachable from main | #6 → main |
| `feature/phase5-gemini-integration` | `093fcca5` | Tip reachable from main | #5 → main |
| `feature/quality-gates` | `1c2d866c` | Tip reachable from main | #16 → main |
| `feature/recall-accuracy` | `2044479d` | Exact tip merged to main through PR #45 | #45 → main |
| `feature/ui-polish-voice-orb` | `04ddaddf` | Tip reachable from main | #12 → main |
| `feature/vin-scanner` | `58c6274c` | Exact tip merged to main through PR #41 | #41 → main |
| `fix/build-identity-and-pr-summaries` | `70929262` | Exact tip merged to main through PR #44 | #44 → main |
| `fix/safety-recognition-ai-guard` | `ebcfddc8` | Tip reachable from main | #9 → main |
| `fix/settings-dark-mode-text` | `03e0b1f3` | Tip reachable from main | #14 → main |

## Keep — active development (1)

| Branch | Tip | Evidence | Related PRs and bases |
|---|---|---|---|
| `main` | `c04d23ac` | Default integration branch | #7 → claude/ai-car-companion-setup-178hlf |

## Requires review (10)

| Branch | Tip | Evidence | Related PRs and bases |
|---|---|---|---|
| `ccr-287aa85e-dsi4kt` | `a78adf87` | Tip not proven integrated into main | — |
| `claude/ai-car-companion-setup-178hlf` | `7f50f1f7` | Tip not proven integrated into main | #4 → main, #3 → main, #2 → main, #1 → main |
| `dependabot/gradle/com.android.application-9.5.1` | `95493064` | Tip not proven integrated into main | #23 → main |
| `dependabot/gradle/gradle-wrapper-9.9.2` | `8617d50b` | Tip not proven integrated into main | #65 → main |
| `dependabot/gradle/kotlin-and-build-45004a4cf0` | `d9627390` | Tip not proven integrated into main | #63 → main |
| `enet2` | `f47c7e4b` | ENET history: verify counterpart in Enet-Development | — |
| `feature/enet-dme-vin-module-scan` | `a0525f04` | ENET history: verify counterpart in Enet-Development | #48 → experiment/bmw-enet-tester |
| `feature/enet-expanded-module-selection` | `2fd141ce` | ENET history: verify counterpart in Enet-Development | #52 → experiment/bmw-enet-tester |
| `fix/audit-privacy-reliability-20261122` | `64a508df` | Tip not proven integrated into main | #56 → main |
| `fix/enet-scan-continuation-20261122` | `eab1b6b1` | ENET history: verify counterpart in Enet-Development | #57 → experiment/bmw-enet-tester |


## Cleanup outcome

Deleted the 38 merged branches and five additional ENET branches from Penelope. Four ENET tips matched the separate repository exactly. The fifth, `ccr-287aa85e-dsi4kt`, added two documentation commits above its transferred tester history. The owner also approved deleting the legacy setup branch, whose tip tree matches its main parent, and the superseded AGP 9.5.1 branch. Neither had an open PR dependency.

The remaining original heads are `main`, the unmerged privacy and reliability audit, and the two closed dependency proposals for Gradle 9.9.2 and AGP 9.5.2. The active audit PRs target main independently.
