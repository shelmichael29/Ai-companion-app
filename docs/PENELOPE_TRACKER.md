# Penelope — Development Notes & To-Dos

**Updated:** 2026-11-21  
**Purpose:** Active checklist, testing notes, decisions, and PR handoffs.  
**Scope:** Penelope app (`main`); experimental ENET tester is separate.

> Coordination notes only. Code, `CLAUDE.md`, authoritative `docs/`, and `docs/project-memory/` remain the sources of truth. Statuses here are not claims of completed quality gates.

## Now / high priority
- [x] **Recall result consistency:** verify auto-launch after VIN scan; distinguish model-level campaigns from VIN-specific outstanding recalls; show source/check date and make API errors distinct from zero results. Add regression tests.
- [ ] **Bluetooth transport expansion:** inspect existing ELM327 Classic support, add BLE GATT transport without breaking Classic; support compatible Bluetooth 5.x / 6.x radios (Bluetooth 6 is a version, not a separate ELM protocol).
- [ ] **Bluetooth diagnostics:** independently show adapter discovery, Bluetooth link, ELM initialization, ECU response, and useful failure cause.
- [x] **Build/update experience:** verify build number/version label changes each build and that app updates install over older builds without uninstalling; document signing and versionCode requirements.
- [ ] **PR summaries:** every implementation prompt/PR documents changes, tests, PR number/link, and whether MERGED or NOT MERGED.

## Device testing / open observations
- [x] VIN scanner verified on the S26 Ultra, build 0.7.1 (8), 2026-11-21, (owner): paper and in-car scan, recalls online and offline, Penelope's recall answer, camera indicator. Still to check from `docs/DEVICE_TESTING.md` §1c: flashlight, camera permission prompts and the settings round trip, rotation, TalkBack, spoken guidance; and for recalls, Try again after a failure, a car added by Search or Manual, an old model year.
- [x] Verify decoded year/make/model, including whether vehicle profile autofills.
- [x] Verify recall lookup automatically appears after confirmed VIN.
- [ ] Earlier year/make/model entry showed several recall campaigns; later VIN-scanned workflow showed none. **Different input scope; not yet confirmed bug.** Never conflate campaign counts with VIN-specific outstanding repairs.
- [ ] Test existing low-cost Bluetooth ELM327 clone with a second car; failure on BMW alone doesn't prove app failure.
- [ ] Test adapter discovery, link, ELM initialization, and ECU communication separately.
- [ ] Verify BLE on Android; later test iOS via CoreBluetooth, with real BLE-capable hardware.
- [ ] Check potential low-cost hardware kit adapters for reliable Android **and** iOS interoperability, consistent sourcing, and vehicle coverage.
- [x] Verify phone voice, TalkBack, and reconnect behavior on real hardware; refer to `docs/DEVICE_TESTING.md`.

## Bluetooth implementation notes
- Existing implementation is **Bluetooth Classic / RFCOMM ELM327**, with transport interface and read-only OBD commands. See `docs/BLUETOOTH_CONNECTION.md` and `docs/CONNECTION_RECOVERY.md`.
- Proposed BLE layer: device discovery; service/characteristic selection based on documented adapter profile; notify/indicate subscription; write queue; MTU/fragmented response assembly; prompt-delimited ELM replies; lifecycle and cancellation; bounded retries; permission handling; redacted diagnostics.
- Treat **BLE** and **Classic SPP** as separate transports under the existing transport abstraction. Do not assume an adapter supports both.
- Bluetooth 6.x BLE adapters should work through standard OS-supported BLE APIs where compatible. No special “Bluetooth 6 transport” is required; OS/hardware support governs newer features.
- iOS generally cannot use arbitrary Classic SPP adapters through CoreBluetooth; favor BLE-compatible adapters for cross-platform starter kits.
- Adapter identification and communication capability must be verified per SKU/revision; an Amazon listing or customer experience alone does not prove firmware consistency.
- Never send code-clear (Mode 04), coding, adaptations, or any vehicle-modifying commands during these tests.


## BMW / cheap ELM327 research — imported notes (review pending)
Source: user-provided `bmw-cheap-elm327-clones-guide.md` (prepared 2026-11-21). These are **research leads**, not independently hardware-verified results or approved architecture decisions. Preserve explicit REPORTED-BUT-UNVERIFIED labels. See `docs/project-memory/IMPORT_REVIEW.md` before promoting outside findings to authoritative memory.

**Core distinction:** a low-cost clone may read *generic emissions OBD-II* from a BMW F10 even when BMW-specific tools cannot connect. A successful Bluetooth pairing is not proof that ELM AT commands, the vehicle protocol, or module-level diagnostics work.

- [ ] **Clone capability probe (read-only):** identify responses to `ATI`, `AT@1`, `ATDP`/`ATDPN`, `ATRV`; check support for documented `ATAL`, `ATPP` and `ATPPS` without assuming the reported version string is trustworthy. Verify any actual command syntax against the ELM327 datasheet before implementing. `?` indicates unsupported syntax/command.
- [ ] **Generic ECU probe:** start at automatic protocol detection (`ATSP 0`), test 11-bit 500 kbps ISO 15765-4 CAN (`ATSP 6`) only as a documented fallback, and report actual selected protocol; a 29-bit 500 kbps alternative (`ATSP 7`) is a *diagnostic experiment*, not a known F10 default.
- [ ] **Multi-frame integrity:** test Mode 09 PID 02 VIN read without recording the VIN; detect truncated/invalid messages, `NO DATA`, `BUFFER FULL`, timeouts and disconnects distinctly. Do not assume reported clone corruption percentages apply to every device.
- [ ] **OBD data validation:** supported-PID bitmap before optional oil-temp PID `01 5C`; log which Mode 01 / Mode 03 / Mode 07 / Mode 09 functions actually work. Generic functionality does not imply access to ABS, airbag, CAS or other BMW modules.
- [ ] **Driver-friendly recovery:** detect adapter-paired-but-no-ECU; offer power-cycle, close competing OBD apps, verify ignition, and test protocol fallback with strict bounded attempts. Never mislabel an unsupported BMW diagnostic function as a Bluetooth failure.
- [ ] **Cross-platform hardware matrix:** record exact product SKU/revision, Bluetooth Classic SPP vs BLE GATT vs Wi-Fi, Android/iOS app outcomes, adapter AT capabilities, vehicle model, and repeated reconnection reliability. Vgate vLinker BM+ and Veepeak OBDCheck BLE/BLE+ are candidate references in the guide, not yet Penelope-certified.
- [ ] **BMW baseline:** compare the same generic read-only data on the F10 using a known-good adapter. ENET/ISTA full BMW diagnostics remain separate from generic ELM327; ENET tester stays experimental.
- [ ] **Unverified research follow-up:** inspect EdiabasLib/Deep OBD stock PIC18F25K80 D-CAN support and replacement firmware before any architectural reuse. Do **not** propose reflashing customer hardware as an onboarding fix; no writing, coding or adaptation resets through cheap clones.
- [ ] **Research review:** independently check primary sources for clone firmware claims, specific AT behavior, vendor support lists and replacement-firmware limitations. The uploaded guide includes single-source figures and unresolved conflicting reports.

**Safety:** all app and automated diagnostic tests remain read-only. The guide discusses Mode 04 and reflashing as background; neither is permission to add vehicle-modifying commands to Penelope.

## Experimental BMW ENET — separate track
- Primary tester branch: `experiment/bmw-enet-tester`.
- Reliability improvements merged into that branch (HSFZ-first discovery, 9-second default, gateway errors); **not** merged into Penelope `main`.
- [ ] Test on 2011 BMW 528i, capture sanitized diagnostics; do not mix ENET changes into app release.

## Later backlog
- [ ] Live diagnostics expansion and symptom-guided interpretation after a reliable physical adapter test.
- [ ] iOS app using shared communication concepts, not Android-only APIs.
- [ ] Mechanic handoff report; freeze-frame and extended vehicle diagnostics.
- [x] Choose validated adapter for eventual Penelope starter kit.
- [ ] Vehicle simulation mode remains deferred.

## Development log — update with every prompt / PR
| Date | Work item | PR / branch | Tests | Status |
|---|---|---|---|---|
| 2026-11-21 | Initial tracker | `docs/penelope-development-tracker-20261121` | Documentation only | PR pending |
| 2026-11-20 | ENET HSFZ reliability | `experiment/bmw-enet-tester`, #43 | See PR CI | Merged to **experimental** branch |
| 2026-11-21 | Phase 1 UI redesign | `feature/phase1-ui-redesign` | 667 tests, 0 failed | PR open, not merged |
| 2026-11-21 | Gemini personal details: automatic scrubbing | `feature/gemini-scrub-personal-details` | 570 tests, 0 failed | PR open, not merged |
| 2026-11-21 | Device verification recorded: VIN scanner and recalls | #50 | Owner device checks passed; docs only | Merged to `main` |
| 2026-11-21 | Recall accuracy and reporting | #45 | 562 tests, 0 failed; verified on the phone | Merged to `main` |
| Earlier | VIN camera/decoder/recall implementation | #41 | See `docs/QUALITY_SUMMARY.md` | In `main`; ✅ verified on the phone |

## How to maintain
1. Add each newly agreed task with an unchecked box and priority.
2. Mark boxes checked only when the requested scope is actually completed; distinguish CI from phone/car verification.
3. Each PR: record its URL, short change summary, executed test results, and merge state.
4. When an implementation changes a product decision/status, update the appropriate `docs/project-memory/` file **in the same PR**.
5. Keep secret tokens, full VINs, Bluetooth addresses, and private identifiers out of this tracker.
- [ ] Assistant personalization (v0.8.0, build 9): ten profiles, honest voice selection, custom name. Device checks pending: `docs/DEVICE_TESTING.md` section 0d.
- [ ] Phase 1B, Prompt 2 of 4 (v0.10.0, build 11): Advanced features integrated into DIY (connection settings apply at DIY, trims/MAP/MAF, protocol and drivetrain replies, Diagnostics from home Results, custom-settings tip). Voice and Everyday untouched. PR not merged; STOP after Prompt 2: no DIY visual redesign until the owner approves. Device checks: `docs/DEVICE_TESTING.md` section 0f.
- [ ] Phase 1A, Prompt 1 of 4 (v0.9.0, build 10): two knowledge levels (Everyday, DIY); Advanced migrates to DIY; inventory in `docs/ADVANCED_FEATURES_INVENTORY.md`. Merged (PR #74, squash b556a50). Device checks pending: `docs/DEVICE_TESTING.md` section 0e.
