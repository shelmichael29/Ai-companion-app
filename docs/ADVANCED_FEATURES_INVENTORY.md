# Advanced-only features: inventory for the DIY redesign (Phase 1A, Prompt 1)

Since v0.9.0 only Everyday Driver and DIY Enthusiast are selectable. `ExplanationLevel.ADVANCED` stays in the code as an internal-only value (a stored Advanced level is migrated to DIY on startup). Nothing below was deleted. "Access after 1A" says how a DIY user reaches it now. Prompt 2 decides what moves into the DIY design.

| Feature | Where | Access before | Access after 1A | Prompt 2 notes |
|---|---|---|---|---|
| Telemetry home (AdvancedDashboard) | `ui/home/AdvancedDashboard.kt`, `dashboard/Dashboard.kt` | Advanced home | Not shown (DIY keeps the DIY home). Code kept and tested | Candidate DIY home content |
| Advanced design tokens, plain nav bar, LiveColors | `AdvancedTokens`, `FloatingNavBar(plain)`, `ui/livedata/LiveColors.kt` | Advanced | Unused unless level is the internal Advanced | Decide whether DIY adopts them |
| Advanced tab set (Overview, Live Data, Diagnostics, Drive, More) | `ui/navigation/LevelNavigation.kt` | Advanced | DIY keeps its own tabs; Live Data via home shortcut and `liveDataAvailable` | Decide DIY tab set |
| Advanced Drive style | `ui/drive/DriveSections.kt` | Advanced | Not shown | Optional |
| Advanced Connection screen (type, protocol, timing, poll, reconnection, adapter ID, adapter test, communication log, expert ELM options, read-only console) | `ui/adapter/AdvancedConnectionScreen.kt` | Advanced (Adapter button, More row, home shortcut) | DIY: Adapter button, More "Connection" row, optional home shortcut | Fully reachable; consider placing in DIY dashboard |
| Custom connection config applying | `ObdConnectionConfig.effectiveFor` | Advanced only | Applies at DIY (Everyday always recommended). Owner sign-off needed: DIY users with saved settings now get them applied | Confirm |
| Console-only queries (Mode 02 freeze frame, 06, 09, ATRV, ATDPN) | Connection screen console, `ReadOnlyCommandPolicy` | Advanced | DIY via Connection screen. Mode 04 and writes still refused | Possibly surface as DIY tools |
| Advanced-only PIDs (fuel trims, MAP, MAF) on home strip, adapter card, spoken list | `ObdPid.kt` minLevel ADVANCED | Advanced | Still in Live Data workspace for DIY; not on DIY home strip or spoken list | Decide DIY PID set |
| Technical reply tier ("give me the technical explanation") | `RuleBasedReasoningProvider` | Advanced replies | Still available on request; DIY replies use the DIY tier | Review wording |
| Gemini ADVANCED detail line | `ai/PromptBuilder.kt` | Advanced | Not used (settings only yield Everyday or DIY); branch kept | Possibly merge into DIY |
| Advanced default shortcuts and saved layout | `DashboardPlanner.defaults`, `home_layout_advanced` keys | Advanced | Saved Advanced layout copied to DIY once if DIY had none; Advanced keys left in storage | Review copied layouts |
| Clear codes (Mode 04, confirmed gate) | `ClearCodesControl`, `VehicleWriteGate` | DIY and Advanced | DIY unchanged | None |
