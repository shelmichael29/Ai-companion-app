# Phase 2: Live Data workspace

Status: implemented on `feature/live-data-workspace`, not merged. Device and adapter checks are still needed (below).

## What the owner gets
- DIY and Advanced only. Everyday has no Live Data (`LevelNavigation.liveDataAvailable`; the route redirects Everyday to Home). Advanced has the Live Data tab; DIY reaches it from the "Live readings" shortcut on its Home dashboard.
- Dashboard of up to 8 gauges per layout. Edit mode: add, remove, move up/down, resize (small, wide, large), choose style (analog dial, digital, numeric, line graph, status) and unit (C/F, km/h or mph, kPa/psi/bar, g/s or lb/min).
- Several named layouts per vehicle (new, rename, delete, pick). A vehicle with none shows a "Default" layout (engine speed, vehicle speed, coolant temperature, module voltage), saved only when edited.
- "All sensors" tab lists every known sensor, supported first, with a Vehicle or SAMPLE origin label.
- Advanced uses `AdvancedTokens` (black and grey). DIY uses the normal theme. Text is in sp, controls are at least 48 dp, every gauge has a spoken description such as "Coolant temperature, 92 degrees Celsius, live".

## Honest states (never a made-up reading)
Live, Stale (older than 10 s, last value shown dimmed and labelled), Not supported, No answer (temporarily unavailable), Disconnected, Paused while clearing codes, and SAMPLE (demo mode only, labelled). Only Live and Sample draw a number or needle.
A missing, unsupported or disconnected sensor is never drawn as 0. The ATRV adapter-voltage fallback is not used for the Live Data gauges, because it is a different measurement from PID 0x42; a car without 0x42 shows "Not supported".

## Poller
`LivePidPoller` (owned by `ObdManager`) is the only live poller. Screens call `request(pids)`; the union of requested PIDs is read and nothing else. With no collector it is not running and sends nothing. It also sends nothing when not connected, when the vehicle is not responding, when link health is not OK, or while Clear codes runs. Reads use `Elm327Session.execute`, which enforces `ReadOnlyCommandPolicy`.
Clear codes: `ObdManager.clearing` is a flow set before the engine-off RPM read. The poller pauses on it, and `execute(skipIf = ...)` re-checks it after the command's turn on the adapter starts, so no Mode 01 poll can slip in. Assistant live reads are also refused during a clear (this was a gap). The older `liveReadings` list (Home tiles, Adapter screen) is now built on the same poller.

## Data
Room database v2 (`MIGRATION_1_2`, SQL copied from `app/schemas/.../2.json`; no destructive fallback). New tables: `dashboard_layouts`, `dashboard_gauges` (primary key layout + slot, unique layout + pid), `dashboard_prefs`. Rows cascade with the vehicle. The 8-gauge limit is enforced in `DashboardLayout` and again in `DashboardDao.saveLayout/replaceGauges`. Layouts are keyed by the app vehicle id, never the VIN; names and values are not logged.

## Tests
`LivePidPollerTest` (requested PIDs only, union and narrowing, nothing after the last collector, unsupported sends nothing, NO DATA, ignition off, read-only policy, and the Clear codes ordering test), `LiveDataStateMapperTest`, `DashboardLayoutTest`, `UnitConversionTest`, `GaugeSemanticsTest`, `LiveDataViewModelTest`, `AppDatabaseMigrationTest` (real v1 database from the committed 1.json), `LiveDataWorkspaceTest`, `LevelNavigationTest`. `Phase2ScreenshotTest` writes `app/build/screenshots/phase2`; copies are in `docs/screenshots/phase2` (Robolectric renders with fixture values, not a car).

## Still needed on real hardware
- Upgrade an installed v1 app on the S26 Ultra and confirm vehicles and history survive.
- With the BMW F10 and adapter: confirm gauges update, a second screen does not slow polling, unsupported PIDs read "Not supported", polling stops when leaving the screen, and no polling occurs during a Clear codes run.
- Read the dashboards outdoors and at the largest system font size.
