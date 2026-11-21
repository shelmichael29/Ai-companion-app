# Penelope — Development Notes & To-Dos

**Updated:** 2026-11-21  
**Purpose:** Active checklist, testing notes, decisions, and PR handoffs.  
**Scope:** Penelope app (`main`); experimental ENET tester is separate.

> Coordination notes only. Code, `CLAUDE.md`, authoritative `docs/`, and `docs/project-memory/` remain the sources of truth. Statuses here are not claims of completed quality gates.

## Now / high priority
- [ ] **Recall result consistency:** verify auto-launch after VIN scan; distinguish model-level campaigns from VIN-specific outstanding recalls; show source/check date and make API errors distinct from zero results. Add regression tests.
- [ ] **Bluetooth transport expansion:** inspect existing ELM327 Classic support, add BLE GATT transport without breaking Classic; support compatible Bluetooth 5.x / 6.x radios (Bluetooth 6 is a version, not a separate ELM protocol).
- [ ] **Bluetooth diagnostics:** independently show adapter discovery, Bluetooth link, ELM initialization, ECU response, and useful failure cause.
- [ ] **Build/update experience:** verify build number/version label changes each build and that app updates install over older builds without uninstalling; document signing and versionCode requirements.
- [ ] **PR summaries:** every implementation prompt/PR documents changes, tests, PR number/link, and whether MERGED or NOT MERGED.

## Device testing / open observations
- [x] VIN scanner camera reportedly works on Android phone (owner observation; not yet independently confirmed against formal hardware checklist).
- [ ] Verify decoded year/make/model, including whether vehicle profile autofills.
- [ ] Verify recall lookup automatically appears after confirmed VIN.
- [ ] Earlier year/make/model entry showed several recall campaigns; later VIN-scanned workflow showed none. **Different input scope; not yet confirmed bug.** Never conflate campaign counts with VIN-specific outstanding repairs.
- [ ] Test existing low-cost Bluetooth ELM327 clone with a second car; failure on BMW alone doesn't prove app failure.
- [ ] Test adapter discovery, link, ELM initialization, and ECU communication separately.
- [ ] Verify BLE on Android; later test iOS via CoreBluetooth, with real BLE-capable hardware.
- [ ] Check potential low-cost hardware kit adapters for reliable Android **and** iOS interoperability, consistent sourcing, and vehicle coverage.
- [ ] Verify phone voice, TalkBack, and reconnect behavior on real hardware; refer to `docs/DEVICE_TESTING.md`.

## Bluetooth implementation notes
- Existing implementation is **Bluetooth Classic / RFCOMM ELM327**, with transport interface and read-only OBD commands. See `docs/BLUETOOTH_CONNECTION.md` and `docs/CONNECTION_RECOVERY.md`.
- Proposed BLE layer: device discovery; service/characteristic selection based on documented adapter profile; notify/indicate subscription; write queue; MTU/fragmented response assembly; prompt-delimited ELM replies; lifecycle and cancellation; bounded retries; permission handling; redacted diagnostics.
- Treat **BLE** and **Classic SPP** as separate transports under the existing transport abstraction. Do not assume an adapter supports both.
- Bluetooth 6.x BLE adapters should work through standard OS-supported BLE APIs where compatible. No special “Bluetooth 6 transport” is required; OS/hardware support governs newer features.
- iOS generally cannot use arbitrary Classic SPP adapters through CoreBluetooth; favor BLE-compatible adapters for cross-platform starter kits.
- Adapter identification and communication capability must be verified per SKU/revision; an Amazon listing or customer experience alone does not prove firmware consistency.
- Never send code-clear (Mode 04), coding, adaptations, or any vehicle-modifying commands during these tests.

## Experimental BMW ENET — separate track
- Primary tester branch: `experiment/bmw-enet-tester`.
- Reliability improvements merged into that branch (HSFZ-first discovery, 9-second default, gateway errors); **not** merged into Penelope `main`.
- [ ] Test on 2011 BMW 528i, capture sanitized diagnostics; do not mix ENET changes into app release.

## Later backlog
- [ ] Live diagnostics expansion and symptom-guided interpretation after a reliable physical adapter test.
- [ ] iOS app using shared communication concepts, not Android-only APIs.
- [ ] Mechanic handoff report; freeze-frame and extended vehicle diagnostics.
- [ ] Choose validated adapter for eventual Penelope starter kit.
- [ ] Vehicle simulation mode remains deferred.

## Development log — update with every prompt / PR
| Date | Work item | PR / branch | Tests | Status |
|---|---|---|---|---|
| 2026-11-21 | Initial tracker | `docs/penelope-development-tracker-20261121` | Documentation only | PR pending |
| 2026-11-20 | ENET HSFZ reliability | `experiment/bmw-enet-tester`, #43 | See PR CI | Merged to **experimental** branch |
| Earlier | VIN camera/decoder/recall implementation | #41 | See `docs/QUALITY_SUMMARY.md` | In `main`; device validation partial |

## How to maintain
1. Add each newly agreed task with an unchecked box and priority.
2. Mark boxes checked only when the requested scope is actually completed; distinguish CI from phone/car verification.
3. Each PR: record its URL, short change summary, executed test results, and merge state.
4. When an implementation changes a product decision/status, update the appropriate `docs/project-memory/` file **in the same PR**.
5. Keep secret tokens, full VINs, Bluetooth addresses, and private identifiers out of this tracker.
