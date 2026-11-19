# Vehicle knowledge

**Authoritative:** `docs/BLUETOOTH_CONNECTION.md`, `docs/CONNECTION_RECOVERY.md`, `docs/ARCHITECTURE.md` (OBD-II), and `ReadOnlyCommandPolicy` in code.

## Test car
- **2011 BMW 528i, F10 body.**
- **Engine:** **N52N**, the updated N52 (engine code N52B30), 3.0 L **inline-six**. Never call it a V6. Confirmed by the owner on 2026-11-19.
- **Test fixture, not a rule:** never infer every 528i build from this car or from EPA descriptions; other model years and markets can differ.
- **OBD-II protocol:** expected CAN 11-bit 500 kbit/s (ELM327 `ATSP6`).
- **Symptom seen:** some ELM327 clones fail the automatic protocol search (`ATSP0`) on this car. "The scanner connects, but the car doesn't respond." Penelope now falls back to trying CAN directly and remembers what worked. 🟢 Built, not verified on the car.
- **Manufacturer modules** (ABS, airbag, transmission) aren't reachable with generic OBD-II. BMW-specific access (ENET/ISTA-style) is a separate experiment, not part of Penelope.

## Adapters
- **Supported:** Bluetooth Classic ELM327 over SPP. The owner's adapter is referred to in the docs as the **Panlong** adapter.
- **Pairing PIN:** usually 1234 or 0000.
- **Not supported yet:** Bluetooth Low Energy-only adapters are refused with a clear message. Wi-Fi and USB are 📋 proposed.

## Safety limits (permanent)
- **Never sent** from automatic features or tests: Mode 04 (clear codes), resets, coding, programming, actuator tests or unreviewed raw commands.
- **Automatic recovery** only ever sends read-only commands.
- **Tests** use `FakeElmAdapter`; no automated test talks to a real car.
- **Clearing codes**, if ever built, will be a separate, explicit, confirmed feature.

## Testing on the car (pending)
- **Checklists:** `docs/BLUETOOTH_CONNECTION.md` §5 (adapter) and `docs/CONNECTION_RECOVERY.md` §4 (protocol fallback, recovery, ignition off/on).
- **Capturing evidence:** Advanced → communication log → Copy log, which is redacted. These raw transcripts can become parser test fixtures. Never share VINs or `.logcat` files.
