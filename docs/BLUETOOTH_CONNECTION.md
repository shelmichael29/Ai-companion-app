# Bluetooth connection foundation

How Penelope finds and connects to a Bluetooth Classic ELM327 OBD-II adapter, and how to test it on a real phone and car.

> **Status:** everything below is covered by automated tests with simulated hardware. **Nothing has been tested with a physical adapter yet.** The checklist in §5 is still to do on the Galaxy S26 Ultra and the 2011 BMW 528i (N52 inline-six).

## 1. Layers

| Layer | Class | Job |
|---|---|---|
| Transport | `diagnostics/bluetooth/AdapterTransport` (interface), `BluetoothGateway` (Android) | Bluetooth availability, permissions, paired devices, time-limited discovery, RFCOMM socket with a hard timeout, typed `ConnectFailure` |
| Setup flow | `connectivity/AdapterConnectionManager` | Choose the adapter, bounded retries, remember the adapter only after success, quiet reconnect on start, cancel |
| ELM327 session | `diagnostics/obd/ObdManager` (unchanged design, Phase 7) | Adapter init, protocol selection, vehicle probe, link-loss recovery |
| Voice | `connectivity/ConnectionAnnouncer` → `VoiceSession.queueStatusNotice` | Short spoken notices between turns, never over the user |
| UI | `ui/adapter/AdapterScreen`, `statusText()` | Everyday wording, or details for DIY/Advanced |

- Diagnostics interpretation, Gemini and the UI don't touch Bluetooth directly.
- New transports (BLE, Wi-Fi, USB) can implement `AdapterTransport`.
- BMW ENET is a different protocol, lives only on the `experiment/*` branches, and is not affected.

## 2. Connect flow ("Connect my adapter")

1. **Bluetooth check.** If Bluetooth is off, permission is missing, or the phone has no Bluetooth, Penelope stops and says so. Nothing is attempted.
2. **Adapter choice:**
   - the adapter that worked last time, if it's still paired;
   - otherwise the only paired device that looks like an OBD-II adapter;
   - several candidates → the user taps one;
   - none paired → a search of about 12 seconds (hard limit 14 s) for nearby Classic devices that look like OBD-II adapters. One found → connect; several → the user chooses; none → "I couldn't find your car adapter…".
3. **Connection:**
   - up to **3 attempts**, waiting 2 s and then 5 s between them;
   - each socket attempt has a **12 s timeout**, trying a secure socket and then an insecure one;
   - only timeouts and an unreachable adapter are retried. Bluetooth off, missing permission and Bluetooth Low Energy-only adapters stop at once.
4. **Success:** the adapter is remembered, and the next app start reconnects to it quietly. That reconnect never searches and never prompts.
5. **Link lost later:** handled by `ObdManager`'s bounded recovery from Phase 7. Penelope says "Your adapter disconnected. I'm reconnecting." and then "Connection restored. We can continue."

"Cancel" or "Disconnect" stops searching, retries and recovery right away. No loops keep running.

### Pairing an adapter found by search
Connecting to an unpaired adapter shows Android's own pairing prompt. The PIN is usually **1234** or **0000**.

## 3. What users see

| Step | Everyday Driver (Easy) | DIY / Advanced |
|---|---|---|
| Searching | Looking for your car adapter. | Same, plus a "Search for nearby adapters" button and the list of devices found |
| Found | Found your adapter. | Same |
| Connecting | Connecting to your vehicle. | Same, plus "Attempt 2 of 3" |
| Connected | You're connected. (then the vehicle stage from Phase 7) | Adapter, protocol and statistics in the details card |
| Failed | I couldn't connect. Let's try again. + one plain reason, and a "Try again" button | Same, plus the Android error text |

Easy mode never shows Bluetooth addresses, protocol settings or error codes. Unnamed devices are shown as "Unnamed Bluetooth device", never by their address.

**Voice:** during a conversation, a connection notice waits until Penelope finishes her current reply. It is spoken before she listens again. Outside a conversation it's only added to the transcript. A notice waiting when the conversation ends is shown, not lost. The same notice is never repeated twice in a row, for example once per retry.

## 4. Permissions

| Android | Requested | Why |
|---|---|---|
| 12+ (S26 Ultra) | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (`neverForLocation`) | Connect, and search for nearby adapters. No location access. |
| 8–11 | `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` (+ legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`) | Android requires location for Bluetooth discovery on these versions |

Without scan permission, paired adapters still work. A search then asks for permission instead of claiming that no adapter was found.

## 5. Hardware test checklist (manual, not yet done)

Use a Bluetooth Classic ELM327 adapter (v1.5 or v2.x clone, or OBDLink/Vgate). **Read-only only:** never clear codes during these tests.

| # | Setup | Expected | Result |
|---|---|---|---|
| 1 | Fresh install, Everyday, tap Connect my adapter | Permission prompt for "Nearby devices"; after allowing, the search starts | NOT RUN |
| 2 | Adapter plugged in, ignition on, **not paired** | "Looking for your car adapter." → "Found your adapter." → Android pairing prompt (1234/0000) → "You're connected." | NOT RUN |
| 3 | Adapter already paired | Connects without searching | NOT RUN |
| 4 | Bluetooth off | "Bluetooth is off…", the Turn on Bluetooth button works, no attempts | NOT RUN |
| 5 | Deny the permission | "I need permission to use Bluetooth…", no attempts; the Allow button asks again | NOT RUN |
| 6 | Adapter unplugged, but paired | At most 3 attempts (usually under 30 s; at most about 80 s if every attempt times out), then "I couldn't connect. Let's try again. …"; no further activity | NOT RUN |
| 7 | Adapter connected to another app (e.g. Torque) | Plain "couldn't reach your adapter" reason; DIY shows the Android error | NOT RUN |
| 8 | Connected, then unplug the adapter | "Your adapter disconnected. I'm reconnecting."; plug back in → "Connection restored." | NOT RUN |
| 9 | Close and reopen the app with the adapter plugged in | Reconnects to the same adapter automatically, with no prompts | NOT RUN |
| 10 | Two adapters nearby or paired | "I found more than one adapter. Tap the one plugged into your car." | NOT RUN |
| 11 | During a voice conversation, unplug the adapter | The notice is spoken after Penelope's current reply, never over the user | NOT RUN |
| 12 | Tap Cancel while connecting or searching | Everything stops right away | NOT RUN |
| 13 | BMW 528i, ignition on, engine off and then running | Vehicle connected (CAN 11/500); "Your car hasn't responded yet" if the ignition is off | NOT RUN |
| 14 | Battery: leave the Adapter screen open for 10 minutes without connecting | No ongoing search (check Settings › Battery) | NOT RUN |

Report any failure with the connection log (Settings › Connection status › Copy log), which is redacted. **Never share VINs or `.logcat` files.**

## 6. Not included yet
- Bluetooth Low Energy adapters (refused with a clear message).
- Wi-Fi and USB adapters.
- Background reconnection while the app is closed. Reconnection happens on app start and while the app is open.
- Clearing trouble codes: a separate feature; nothing here sends Mode 04.
