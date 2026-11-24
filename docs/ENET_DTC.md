# BMW fault codes: read and clear (0.4.0-experimental)

Reads and, behind a confirmation gate, clears fault memory (DTCs) on F-series modules over ENET (HSFZ, TCP 6801). It's part of the standalone ENET tester only. Penelope and `main` are not changed.

The logic lives in `enet-core` package `com.aicarcompanion.enet.dtc` (pure Kotlin/JVM, no Android code), so other BMW apps can reuse it. The tester's **7. BMW Diagnostics** section is only a UI on top of it.

## Sources
- **Request and response layouts:** ISO 14229-1 (UDS) standard formats for `0x19` ReadDTCInformation, `0x14` ClearDiagnosticInformation and `0x3E` TesterPresent. Negative response code names are from ISO 14229-1 Annex A, and DTC status bits from Annex D.
- **HSFZ transport and addresses:** unchanged from the existing tester. Framing is from EdiabasLib, and module addresses come from the sourced routing table in [F10 module coverage](ENET_MODULE_COVERAGE.md).
- **Not included:** no BMW fault descriptions, SGBD data or proprietary decoding. The "BMW DTC Read / Clear" guide referenced in the task was not available in this session, so nothing here comes from it. Review the code against it when you have it.

## What it reads
For each candidate module, in sequence, on one fresh TCP connection per module (physical addressing, target = module address):

| Step | Request | Purpose |
|---|---|---|
| Presence | `3E 00` | A positive answer or any negative answer means the module is present. Silence is "No response", not "absent". |
| Fault count | `19 01 FF` | Count, DTC format and availability mask. |
| Fault list | `19 02 FF` | Every DTC and its status byte. |
| Snapshot list | `19 03` | Which DTCs have snapshot (freeze-frame) records. Only sent when faults exist and details are on. |
| Snapshot | `19 04 <DTC> FF` | Only for DTCs that `19 03` listed. |
| Extended data | `19 06 <DTC> FF` | Stops for that module after the first "not supported" reply. |

- **Display:** each module's name and address; the BMW six-digit hex code (for example `480A12`); and the status as Active (bit 0, test failed), Stored (bit 3, confirmed), Pending (bit 2) and warning lamp (bit 7), always with the raw status byte.
- **Not converted:** codes are never turned into P-codes, and no description is shown unless a verified `FaultDescriptions` source is installed. None is installed today.
- **Kept raw:** every request and response, as hex in the report and under **View Fault Details**. Snapshot and extended record contents stay raw because decoding them needs BMW data we don't have.
- **Detail limit:** details are read for the first 32 faults per module, so one full fault memory can't stall the scan.

## Module coverage
**Scan All Modules** probes all 47 routing-table candidates and reads faults from every one that responds. The number of modules is whatever the car answers; nothing assumes 4, 26 or any other count. One module failing (timeout, gateway error `0x0040`–`0x0045`, connection error) is recorded and the scan continues. **Individual Module Scan** reads one module.

Module identification uses the address and the sourced routing table plus the presence answer. Extra identification reads (for example BMW SVK or ISO `F1xx` identifiers) were not added, because which identifiers F10 modules support isn't verified yet.

## Reliability rules
- **One request at a time:** requests are strictly sequential on a connection.
- **Reply matching:** a reply counts only if it comes from the module's address to tester `0xF4` and has the matching response SID and sub-function (or `7F <SID>`). Replies from other modules, late replies and mismatched replies are logged and ignored.
- **Response pending:** `7F xx 78` extends the wait by 5 s each time, capped at 30 s overall. The normal answer window is 3 s, the same as the existing scan.
- **No blind retries:** after a timeout, connection error or protocol error, that connection gets no further requests ("Not sent"), and the request is never re-sent on it.
- **Keep-alive:** HSFZ alive checks are answered. No session change is made, and everything runs in the default session, so no TesterPresent keep-alive loop is needed.

## Clearing
- **Request:** `14 FF FF FF`, all DTC groups. The expected positive response is `54`. Single-DTC clearing isn't implemented.
- **Options:** **Clear Selected Module** (one module's card) or **Clear All Modules**, which goes through every eligible module in sequence.
- **Eligible modules:** only modules whose fault list was read in the current report and holds at least one fault.
- **The gate (`ClearGate`).** A clear needs a single-use authorization that expires after 2 minutes, and it is issued only when:
  1. the current report has been saved locally (app-private `files/diagnostic-reports/`);
  2. the user confirmed that freeze-frame and extended data may be deleted;
  3. if the engine computer (DME) is included, the user confirmed that emissions readiness may be reset;
  4. the user confirmed that permanent or active faults may remain;
  5. the user confirmed the vehicle is stationary with the ignition on and the engine off (an attestation: the tester can't verify engine state);
  6. the last read wasn't cancelled.
- **No retries:** each module gets exactly one clear request. Negative responses, timeouts and connection errors are reported as they are.

### Verification
After a `54`:
1. Wait 2 s.
2. Open a fresh connection and re-read `19 02 FF`, then `19 15` (permanent DTCs, if supported).
3. Compare before and after, and report faults that were **removed**, **returned immediately** (still active), **still stored**, **new since the clear** and **permanent** (only when the module supports `19 15`).

The overall result says "All … cleared and verified" only when every selected module was cleared and a fresh read found none of its earlier faults. Otherwise it reports how many succeeded. **Verify Clear Results** runs another fresh read of the same modules.

## Not included
ECU coding, flashing, adaptations or resets, routines, actuator tests, SecurityAccess, session changes and raw command entry.

## Verification status
| Area | Simulated tests | Needs the car |
|---|---|---|
| Request bytes, response parsing, status bits, NRCs | Verified | Real response formats from F10 modules |
| Scan of all candidates, partial failures, timeouts, pending, mismatched replies | Verified with simulated ECUs | **Yes:** which modules answer `19 01/02/03/04/06/15`, and timing |
| Clear gate and confirmations | Verified | UI walk-through on the phone |
| Clear `14 FF FF FF` plus verification | Verified with simulated ECUs | **Yes:** start with one non-safety module that has a known, harmless stored fault |

Simulated results are not live-car validation.
