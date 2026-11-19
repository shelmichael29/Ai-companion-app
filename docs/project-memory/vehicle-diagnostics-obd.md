# Vehicle diagnostics and OBD-II

Details: `docs/BLUETOOTH_CONNECTION.md`, `docs/CONNECTION_RECOVERY.md`, `docs/ARCHITECTURE.md` (OBD-II).

## Test car
**2011 BMW 528i (F10)** with the **N52 inline-six** (never call it a V6). CAN 11-bit 500k (ATSP6) is the expected protocol; some ELM327 clones fail the automatic search on it, so Penelope falls back to trying CAN directly.

## What's built
| Feature | Status |
|---|---|
| Bluetooth Classic ELM327 adapters: paired list, nearby search, bounded retries, auto-reconnect to the last working adapter | 🟡 Built, not verified with a real adapter |
| Connection stages: "vehicle connected" only after a real ECU answer | 🟡 Built, not verified on the car |
| Protocol fallback and protocol memory per vehicle | 🟡 Built, not verified on the car |
| Bounded, read-only recovery when the car stops answering | 🟡 Built, not verified on the car |
| Read trouble codes (modes 03/07), live data, freeze-frame not yet | 🟡 Codes and live data built; freeze-frame 📋 planned |
| Generic DTC catalog and explanations by knowledge level | ✅ Built |
| Advanced settings (protocol, timing, polling), redacted traffic log, read-only console | 🟡 Built, not verified |

## Safety limits (permanent)
- **Never** send Mode 04 (clear codes), resets, coding, programming, actuator tests or raw unreviewed commands from automatic features or tests. The allowlist is `ReadOnlyCommandPolicy`.
- Tests use `FakeElmAdapter`; no automated test talks to a real car.
- Clearing codes, if built, will be a separate, explicit, confirmed feature.

## 📋 Planned
- BLE, Wi-Fi and USB adapters (same transport interface).
- Read the VIN via Mode 09 and compare with the profile; freeze-frame data.
- BMW/N52-specific knowledge pack (offline).
- **ENET (BMW Ethernet) is a separate experiment** on `experiment/*` branches only. It's not part of Penelope unless the owner says so.
