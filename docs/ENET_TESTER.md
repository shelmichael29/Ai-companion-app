# BMW ENET Connection Tester (experimental)

A separate Android app for checking whether a phone can reach a BMW F-series central gateway (ZGW) over ENET (Ethernet), before any ENET feature goes into Penelope.

- **Separate app:** application ID `com.aicarcompanion.enettester` (shown as "ENET Tester (experimental)"), so it installs alongside Penelope.
- **Penelope is unchanged:** the app, Firebase and Bluetooth OBD-II code aren't touched.

For the current module catalog, individual/selected/all scan controls, timing, hardware evidence and change record, see [F10 module coverage](ENET_MODULE_COVERAGE.md).

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

**Safety limits:**
- **Requests:** `SafeRequest` is a fixed enum containing only TesterPresent (`3E 00`) and Read VIN (`22 F1 90`). No code path accepts user-supplied bytes, so there is no raw command entry. There is no coding, programming, actuator activation, routine control, security access or fault-code clearing.
- **Targets:** `TargetPolicy` allows only unicast private or link-local addresses inside the selected interface's subnet. It also refuses an interface with a public address or a network larger than /16. It is checked before any socket opens.
- **Interfaces:** only Ethernet-transport networks are listed. Wi-Fi, mobile data and VPN can't be selected, and sockets are bound with `Network.bindSocket`.
- **User action:** no traffic until a button is pressed. One operation runs at a time, every wait has a timeout, and incoming frames are capped at 4 KB.
- **Logs:** VINs (maker code and last 6 characters kept) and MAC addresses (manufacturer prefix kept) are masked in the log and in exports.

## Engine VIN and module checks
The DME VIN read requires a diagnostic reply from DME `0x12` addressed to tester `0xF4`, the expected `62 F1 90` identifier and a valid 17-character VIN. A gateway ACK does not count. A valid discovery identity is compared in full without logging the VIN.

Build **0.3.0-experimental (3)** adds **Scan All**, **Scan Selected**, and a **Scan** button for each of the 47 candidate F10 module addresses. Requests run one at a time with bounded waits, cancellation and per-module/total timing. Optional equipment is labeled and silence never means confirmed absence. Details and sources: [F10 module coverage](ENET_MODULE_COVERAGE.md).

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

## Path into Penelope
`enet-core` has no Android dependencies, and its transport is an interface (`EnetTransport`). Once a real F10 confirms these steps, Penelope can depend on `:enet-core` and add an ENET transport beside Bluetooth OBD-II.

Penelope's read-only policy would extend to ENET, and only allowlisted, read-only UDS services would be added after review.
