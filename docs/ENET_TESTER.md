# BMW ENET Connection Tester (experimental)

A separate Android app for checking whether a phone can reach a BMW F-series central gateway (ZGW) over ENET (Ethernet), before any ENET feature goes into Penelope.

- **Separate app:** application ID `com.aicarcompanion.enettester` (shown as "ENET Tester (experimental)"), so it installs alongside Penelope.
- **Penelope is unchanged:** the app, Firebase and Bluetooth OBD-II code aren't touched.

## Modules
| Module | What it is |
|---|---|
| `:enet-core` | Pure Kotlin/JVM library: IPv4 and address policy, HSFZ and DoIP framing and parsing, discovery, connection checks, redacted log. No Android code, so it is unit-tested on the JVM and can later be used by Penelope. |
| `:enet-tester` | Compose app: Ethernet interface selection, target entry, discovery, connection test, log with Copy/Export. Sockets are bound to the selected Android `Network`. |

## Protocol basis
These are community-documented, not official BMW specifications. They come from the open-source EdiabasLib project (`EdInterfaceEnet.cs`, uholeschak/ediabaslib).

| Item | Value |
|---|---|
| Vehicle identification | UDP to port 6811, request `00 00 00 00 00 11`. The answer starts with the text `DIAGADR10BMWMAC<12 hex>BMWVIN<17>`. |
| Diagnostic transport (HSFZ) | TCP 6801. A 6-byte header (4-byte big-endian length, 2-byte control word), then source and target address and the UDS bytes. |
| Control words | `0x0001` diagnostic message, `0x0002` acknowledgement (echoes the request), `0x00FF` negative acknowledgement, `0x0012` alive check (answered with `00 00 00 02 00 13 00 F4`), `0x0010` ignition status on the control port 6811. |
| Addresses | Tester `0xF4`, central gateway `0x10`. |
| DoIP (ISO 13400-2) | UDP and TCP 13400. Only the vehicle identification request `02 FD 00 01 00 00 00 00` and announcement parsing are implemented. |

**An early F10 (2011) uses HSFZ, not DoIP.** The tester therefore reports "no DoIP answer" or "DoIP port not available" as *Not supported*, not as a failure.

## What it does (and doesn't)
**Discovery (button "Discover gateway"):**
- Sends the HSFZ identification request, and the DoIP one if selected, to:
  - the selected interface's subnet broadcast;
  - `255.255.255.255`;
  - an optional typed target.
- All sends go through a socket bound to that interface.
- Listens for 9 seconds by default (adjustable 1–15 seconds) or up to 16 answers; HSFZ-only by default, with optional DoIP.
- Accepts answers only from private or link-local addresses on that subnet.

**Connection test (button "Test connection"):**
1. **TCP connect to 6801**, with a 5-second timeout (adjustable under **Advanced settings**) and the connect latency measured.
2. **Optionally, one UDS TesterPresent (`3E 00`) to the gateway.** The tester waits up to 3 seconds for the acknowledgement and the `7E 00` response, answers alive checks, and reports HSFZ gateway errors `0x0040`–`0x0045`.
   - A negative response (`7F 3E xx`) still counts as "session reachable".
   - A rejection (NACK) or silence counts as a failure.
3. **Optional, experimental read-only ignition-status query** on control port 6811.
4. **Optional connect-only check of DoIP port 13400.** No DoIP messages are sent.

**Read engine computer VIN (button "Read engine computer VIN", build 0.2.0):**
- Opens TCP 6801 and sends one read-only request, UDS ReadDataByIdentifier `22 F1 90` (the standard VIN identifier), from tester `0xF4` to the DME at diagnostic address `0x12`, through the selected gateway.
- It is a success **only** when a diagnostic message arrives **from `0x12`**, addressed to the tester, as a positive response `62 F1 90` followed by exactly 17 characters that form a valid VIN (A–Z and 0–9 without I, O, Q). The screen then shows the responding module (name, abbreviation, address), the VIN, and whether it **matches**, **does not match** or **can't be compared** with the VIN the gateway announced in discovery.
- **Never a success:** a gateway acknowledgment (it only says "forwarded"), an HSFZ error, a reply from any other address (even with a valid VIN), a negative response from the DME ("Declined": the module is there but no VIN was read), a reply for a different identifier, or a reply that isn't a valid 17-character VIN (nothing is shown as the VIN).
- Run **Discover gateway** first if you want the comparison: discovery keeps only a one-way fingerprint of the gateway's VIN, so the full gateway VIN is never held or logged.

**Scan modules (button "Scan modules", build 0.2.0):**
- Opens one connection and sends one read-only request, TesterPresent `3E 00`, to each module in the table below, one at a time, waiting up to 1.5 s for each (adjustable in code 0.5–3 s; "response pending" from a module extends the wait at most twice). The whole scan is bounded (8 modules, about 12 s if every module is silent).
- Each module shows its plain-English name, BMW abbreviation, address and one of:
  - **Replied**: a positive response from that module's own address.
  - **Replied: declined the request**: an explicit diagnostic rejection (negative response with a code) from that module's own address. It **counts as the module having answered**; some ECUs reject `3E 00` or only answer in another session.
  - **No response**: nothing from that module. **Not** proof the module is absent (asleep, other address, or just doesn't answer this). A gateway acknowledgment, an HSFZ error, or a reply from another address never counts as the module answering; the row says which of those happened.
  - **Not checked**: the scan stopped (cancelled or cable removed) before this module.
- **Coverage is stated on screen and in the log:** which modules were checked and which weren't (and why), so users can tell what "no response" does and doesn't cover.
- **Cancel** stops the scan at once. The connection is closed on completion, cancel, an error, or the Ethernet cable being unplugged (the app watches the selected network). Partial results stay on screen, labeled as incomplete.

**Modules the scan checks** (only addresses found in published sources; addresses are for F-series cars in general, one source from a 2016 car, so a 2011 F10 may not have or may address a module differently):

| Module | Abbr. | Address | Source |
|---|---|---|---|
| Central gateway | ZGW | `0x10` | Quantex ELM327 HSFZ documentation ("typically the ZGW"); EdiabasLib |
| Engine computer | DME | `0x12` | community F-series ECU table (packetpilot/bmw-f); Quantex (engine ECU example); CAN message reference |
| Stability control / ABS | DSC | `0x29` | community F-series ECU table |
| Electric power steering (if fitted) | EPS | `0x30` | community F-series ECU table |
| Car access / body electronics | CAS | `0x40` | community table lists it as part of the combined front electronics module; a 2011 F10 has a separate CAS, so this is the least certain address |
| Instrument cluster | KOMBI | `0x60` | community F-series ECU table |
| Automatic climate control | IHKA | `0x78` | community F-series ECU table |
| Airbag / crash safety | ACSM | `0x01` | community F-series ECU table |

**Not checked (no confirmed address in the sources found):** automatic transmission (EGS), footwell module (FRM) / junction box, parking assistance (PDC), steering column (SZL), iDrive/head unit, and others. Adding one needs a confirmed address first.

**Safety limits:**
- **Requests:** `SafeRequest` is a fixed enum with two members, TesterPresent (`3E 00`) and ReadDataByIdentifier VIN (`22 F1 90`), both read-only; a test pins the list and its bytes, so adding one is a deliberate change. No code path accepts user-supplied bytes, so there is no raw command entry. There is no coding, programming, actuator activation, routine control, security access or fault-code clearing.
- **Targets:** `TargetPolicy` allows only unicast private or link-local addresses inside the selected interface's subnet. It also refuses an interface with a public address or a network larger than /16. It is checked before any socket opens.
- **Interfaces:** only Ethernet-transport networks are listed. Wi-Fi, mobile data and VPN can't be selected, and sockets are bound with `Network.bindSocket`.
- **User action:** no traffic until a button is pressed. One operation runs at a time (a second press is refused, and the buttons are disabled while one runs), every wait has a timeout, and incoming frames are capped at 4 KB.
- **Logs:** VINs (maker code and last 6 characters kept) and MAC addresses (manufacturer prefix kept) are masked in the log and in exports. A positive response to the VIN request is logged as `62 F1 90 + 17 data bytes (not shown)`, never as text or hex, and a test checks the exported log for the full VIN in both forms. The VIN is shown in full on screen only for the engine-computer read.

## Verification status
| Area | Simulation (unit tests) | Needs the car |
|---|---|---|
| IPv4 parsing, subnet math, target policy | Verified | n/a |
| HSFZ framing, stream reassembly, oversize guard | Verified | Byte format against a real ZGW |
| HSFZ ident parsing (DIAGADR, MAC, VIN) | Verified with replies built from EdiabasLib's documented layout | **Yes:** real F10 answer format and timing |
| DoIP request and announcement parsing | Verified | Expected absent on a 2011 F10 |
| Discovery bounds: listen window, response cap, dedupe, foreign and garbage replies ignored | Verified | **Yes:** whether broadcast reaches the ZGW through the Android USB-Ethernet stack |
| TCP connect, TesterPresent, ACK matching, alive check, NACK, timeouts, refusal | Verified with a simulated gateway, and with real sockets on loopback | **Yes:** real ZGW ACK and response behavior on a 2011 F10 |
| Ignition status query | Verified against the EdiabasLib format only | **Yes:** may not be supported; reported as "Not supported" |
| Android Ethernet detection and socket binding | Builds and lints. The check of where the socket was bound is unit-tested, and the real JVM socket reports its bound address and broadcast flag. Not run on a device. | **Yes:** check that the log shows the 169.254/private address and SO_BROADCAST=true with no WARNING lines |
| Engine computer VIN: request bytes, DME-only acceptance, valid / mismatched / no gateway VIN, gateway-only acknowledgment, reply from the wrong module, rejection, malformed VIN, wrong identifier, response pending, timeout, gateway error, cable removed, cancellation, no overlap | Verified with a simulated gateway | **Yes:** whether the real DME answers `22 F1 90` through the F10 gateway (see the checklist) |
| Module scan: positive / rejected / silent / wrong-module / late-reply classification, bounded timeouts, progress, cancellation, cable removed, coverage text | Verified with a simulated gateway | **Yes:** which of the eight modules answer `3E 00` on the real car; the address table is from public sources, not verified on a 2011 F10 |
| TalkBack on the new controls and results | Labels and live regions are set in code; Lint passes | **Yes:** a TalkBack pass on the phone |
| TCP 6801 port check | Verified with a simulated gateway (open, refused, 5-second timeout, no bytes sent) and a real local socket | **Yes** |
| Getting an IPv4 address on the phone's Ethernet | Not simulated | **Yes:** the biggest unknown; see below |

## Physical test on the 2011 F10 (N52, inline-six)
1. **Hardware:**
   - an ENET cable (RJ45 to OBD-II, with pins 8 and 16 bridged through about 510 Ω to activate Ethernet);
   - a USB-C Ethernet adapter;
   - optionally, a small travel router.
2. **Addressing (most likely issue):** the ZGW is a DHCP client and falls back to 169.254.x.x. Android normally only uses DHCP on Ethernet and may not self-assign a 169.254 address.
   - If the tester shows "No IPv4 address assigned", put a small router between the phone and the car to provide DHCP. Both then get, for example, 192.168.8.x, and the tester accepts that private range.
3. **Ignition** on (engine off is fine), and keep the battery healthy.
4. **Run the tester:** select the Ethernet interface → **Discover gateway**. Expect one HSFZ gateway; DoIP shows no answer.
5. **Use as target** → **Test connection** with TesterPresent on. Expect a TCP OK with a latency, and a session check of OK (`7E 00`) or a "declined" negative response.
6. **Export the log** (VIN and MAC are masked) and attach it to the PR for review.

## In-car checklist: the two new buttons (2011 F10)
Do the earlier steps first (Ethernet interface selected, **Discover gateway** found the gateway and its VIN, the TCP 6801 check is OK). Ignition on, engine off. Don't touch anything else on the car; both buttons are read-only.

**Read engine computer VIN**
1. Enter the gateway address (**Use as target**) and tap **Read engine computer VIN**.
2. **Expected, success:** "OK · VIN read from the engine computer", responding module "Engine computer (DME), address 0x12", the VIN, and "Matches the VIN the gateway reported". Compare the VIN with your registration or the windshield plate.
3. **Also useful:** tap it before running discovery. It should read the VIN and say "No gateway VIN to compare".
4. **If it fails, that's a result too:** "Declined" (the DME is there but refuses this request; note the code), "did not answer" (note whether the gateway acknowledged), or "not a valid VIN". Export the log (VINs are masked) and attach it to the PR.
5. Pull the cable while it is running: it should stop, say the connection was lost, and the buttons should come back.

**Scan modules**
1. Tap **Scan modules**. Watch "Checking … (n of 8)", and try **Cancel** once: it should stop immediately with a partial list marked as incomplete.
2. Run it again to the end (about 12 s at most). Write down, for each of the eight modules, **Replied**, **Declined** or **No response**. A "Declined" is fine: it means the module answered.
3. The coverage line must list the modules checked and the ones not checked. Don't read "No response" as "this car doesn't have it".
4. Pull the cable mid-scan: the scan should stop, the remaining modules should say "Not checked", and the buttons should come back.
5. Switch to TalkBack and go through both results: each module should read as name, abbreviation and result.
6. Export the log and attach it. Report the build identifier shown under the title (version, code and revision).

## Path into Penelope
`enet-core` has no Android dependencies, and its transport is an interface (`EnetTransport`). Once a real F10 confirms these steps, Penelope can depend on `:enet-core` and add an ENET transport beside Bluetooth OBD-II.

Penelope's read-only policy would extend to ENET, and only allowlisted, read-only UDS services would be added after review.
