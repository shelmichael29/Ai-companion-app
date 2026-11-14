# Roadmap

Milestones are ordered so each one ships something usable and testable.

## M1 — Foundation ✅ (this change)
Compose app shell, vehicle profiles (in-memory), dashboard with clearly labeled sample data,
OBD-II/DTC decoding library with unit tests, CI.

## M2 — Persistence & maintenance log
- Room database for vehicles and maintenance records (with migrations + tests).
- Add/edit/delete maintenance records (`USER_ENTERED`), service reminders by date/mileage.
- Remove sample maintenance data once real records can be entered; keep a "demo mode" toggle.

## M3 — Bluetooth OBD-II (ELM327)
- Runtime permission flow, device picker, Bluetooth classic SPP transport.
- ELM327 init sequence, protocol auto-detect, timeouts, reconnect.
- Read/clear DTCs (modes 03/07/0A/04 — clearing behind an explicit confirmation).
- Live data screen for supported PIDs (query mode 01 PID 00/20/40 support bitmaps).
- Test against a real adapter and an ELM327 emulator; record raw transcripts as test fixtures.

## M4 — Wi-Fi OBD-II & BLE adapters
- TCP socket transport; BLE GATT transport for common BLE dongles.

## M5 — Mechanic handoff
- Report with vehicle profile, DTCs (with origin/time captured), freeze-frame data,
  maintenance history; export as PDF/plain text via the share sheet. No server required.

## M6 — AI-assisted troubleshooting (optional)
- Decide provider strategy (on-device vs. user-supplied cloud key); opt-in only.
- Ground answers in origin-tagged vehicle facts; clearly mark AI suggestions as non-authoritative.

## M7 — Vehicle-specific knowledge base (optional)
- Pluggable `DtcKnowledgeSource` backed by a local, licensable dataset (TSBs, manufacturer codes).

## Housekeeping
- Upgrade AGP/Kotlin/AndroidX to current versions (lint reports newer releases; AGP 9 is a
  major migration and deserves its own change).
- Add Compose UI tests and screenshot tests once screens stabilize.
- Units preference (miles/km, °F/°C).
