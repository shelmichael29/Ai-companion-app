# Phase 1: UI redesign (three knowledge levels)

Each knowledge level gets its own dashboard and navigation, becoming plainer, denser and faster to scan as the level rises. The two settings stay independent and persistent: **knowledge level** (Everyday Driver, DIY Enthusiast, Advanced / Professional; the owner calls them Beginner, Advanced and Pro) and **home layout** (Easy or Customizable, kept per level). Switching either never touches vehicles, history, maintenance or saved layouts.

## The three interfaces
| Level | Look | Home | Navigation |
|---|---|---|---|
| Everyday Driver | Penelope style, large animated orb, voice first | Unchanged Everyday dashboard: big orb, plain-language health, large buttons | Home · Drive · My Vehicle · More |
| DIY Enthusiast | Penelope style, medium orb | Orb row with mic and up to 3 suggestions; vehicle health card (plain + technical, Read codes); Diagnostics / Live readings / Adapter shortcuts; battery and coolant tiles (real values only); last service; customizable shortcuts | Home · Diagnostics · Drive · Garage · More |
| Advanced / Professional | **Black and grey, no-nonsense** (owner decision): no orb, no suggestions, no gradients; colour only for status (green OK, amber pending, red fault) | Compact header with Penelope's mic and chat buttons (two-way voice kept); vehicle line; link line; status strip (check engine, codes, readiness, battery); up to 6 live tiles with PIDs; trouble codes; compact shortcuts | Overview · Live Data · Diagnostics · Drive · More |

New destinations: **Drive** (honest placeholder; music, calls and messages come in Phase 3, for every level), **Live Data** (real readings only, with live, disconnected, waiting, stale and sample states; customizable gauges come in Phase 2), **Diagnostics** (the trouble-code screen as a tab) and **More** (chat, history, settings, adapter, connection, extras, garage where it isn't a tab). Chat and History are plain routes with a back arrow.

## Architecture
- `ui/navigation/LevelNavigation.kt`: a pure Kotlin model (no Compose types) of each level's tabs and routes, ready for a future Kotlin Multiplatform move. The floating nav bar renders whatever it returns.
- `ui/navigation/LiveDataStatus.kt`: pure, unit-tested state logic shared by Live Data and the dashboards. Nothing is shown as a live value unless it is one; sample data is always labelled.
- `ui/home/DiyDashboard.kt`, `ui/home/AdvancedDashboard.kt` (+ `AdvancedTokens.kt`, `HomeLive.kt`): separate compositions over the same `HomeUiState`, services and actions. No duplicated business logic.
- Existing voice, Gemini, Bluetooth, Firebase, VIN and recall code is untouched.

## Visual QA
`Phase1ScreenshotTest` renders the real composables (Robolectric native graphics) for every level at 360×800 dp (small phone) and 412×915 dp (Galaxy S26 Ultra class), dark and light, at 100% and 200% font, plus Live Data, Drive and More. There is no Android emulator in the cloud build environment (no hardware virtualization), so these are **rendered** screenshots, not device captures. Defects found and fixed: codes cell wrapping, monospace link line wrapping, purple nav bar on Advanced, nav labels truncated at 200% font. Representative images: `docs/screenshots/phase1/`.

## Known limitations
- Readiness shows "—" (the app doesn't read readiness monitors yet). Latency isn't shown (no source reports it).
- Live Data is a list in Phase 1; customizable gauges and the "All sensors" list come in Phase 2 (DIY and Advanced).
- Drive is a placeholder until Phase 3.
- "Clear codes" (DIY and Advanced, next to Read codes, only after codes are read, with a confirmation) is approved by the owner and will be its own PR with a safety review, because it is the app's first command that changes something in the car.
- Not checked on a device: real layout, TalkBack, animation, the S26 Ultra's actual insets.

## Manual check on the phone
1. Settings → knowledge level: switch Everyday → DIY → Advanced. Each shows its own home and navigation; your car, history and layouts are unchanged; the setting survives an app restart.
2. Settings → home layout: Easy vs Customizable on each level; customize one level and switch away and back (kept).
3. Advanced: black and grey; tap the header mic and talk to Penelope; Live Data tab shows real readings with the adapter connected, and "Connect the adapter" without; Diagnostics tab lists codes.
4. Phone font size at maximum and display size larger: nothing overlaps or is cut off; the nav shows the selected label.
5. TalkBack: every tab, tile and button reads a clear name; the status strip reads "Codes: 0 stored, 1 pending, 0 permanent".
6. Light theme (Settings → appearance) on each level.
