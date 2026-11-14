# Architecture

## Principles

1. **Never present fake data as real.** Every diagnostic reading, trouble code and maintenance
   record carries a `DataOrigin` (`SAMPLE`, `USER_ENTERED`, `OBD_BLUETOOTH`, `OBD_WIFI`). The UI
   shows the origin, and anything built later (mechanic exports, AI prompts) must carry it too.
2. **Interfaces at the seams.** Screens talk to ViewModels; ViewModels talk to repository
   interfaces in `domain/repository`. Implementations are chosen in one place, `AppContainer`.
3. **Pure logic stays pure.** Protocol decoding (DTCs, PIDs, ELM327 text) and form validation have
   no Android dependencies, so they're covered by fast JVM unit tests.
4. **Lean dependencies.** AndroidX + Compose + Navigation only. Manual DI instead of Hilt until the
   object graph justifies it. No network, analytics or paid services.
5. **Phone-centric, offline-first.** The app runs on the user's phone and must work without
   connectivity; it only reads the car while the phone is on and connected to an adapter.

## Layers

```
 UI (Compose screens)  ──observes──▶  ViewModels (StateFlow<UiState>)
                                           │
                                           ▼
                          Repository interfaces (domain/repository)
                 ┌─────────────────────────┼──────────────────────────┐
                 ▼                         ▼                          ▼
     VehicleRepository          DiagnosticsRepository        MaintenanceRepository
     today: in-memory           today: fixed SAMPLE data     today: fixed SAMPLE data
     next:  Room                next:  ObdDiagnosticsRepo    next:  Room
                                       │
                                       ▼
                          ObdConnection (diagnostics/obd)
                 ┌─────────────────────┼─────────────────────┐
           Bluetooth classic        BLE GATT             Wi-Fi TCP socket
           (RFCOMM/SPP)                                  (typ. 192.168.0.10:35000)
```

## OBD-II design

- **Transport**: `ObdConnection` is a text command/response pipe (`send("010C")`). Each transport
  implements only that; ELM327 init (`ATZ`, `ATE0`, `ATL0`, `ATH0`, `ATSP0`), command
  sequencing, timeouts and polling live above it and are shared.
- **Parsing**: `Elm327ResponseParser` turns raw text into bytes or `NoData`/`Error`;
  `ObdPid` holds SAE J1979 formulas; `DtcDecoder` converts byte pairs to codes like `P0420`.
  Current assumptions: headers off, single responding ECU. Multi-ECU and multi-frame (ISO-TP)
  responses are a planned extension.
- **Placeholder**: `UnavailableObdConnection` reports `Unavailable` and never produces data.
- **Permissions**: none are declared yet. Bluetooth (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on API
  31+, location on older) and Wi-Fi/network access will be added with the transports and
  requested at the moment the user chooses to connect.
- **Background reading**: when added, live polling should run in a foreground service with a
  visible notification, and stop when the adapter disconnects or the phone is off.

## Planned extension points

| Feature | Seam |
|---|---|
| Persistence | Room implementations of `VehicleRepository`, `MaintenanceRepository` |
| Live diagnostics | `ObdDiagnosticsRepository` using `ObdConnection` + parser |
| Code explanations | `DtcKnowledgeSource` (built-in `GenericDtcCatalog` today; vehicle-specific DB later) |
| Mechanic handoff | A `MechanicReport` assembled from repositories, exported as PDF/text via Android share sheet; includes `DataOrigin` per item |
| AI troubleshooting | An `AssistantService` interface; must receive origin-tagged facts and be optional/off by default. Provider choice (on-device vs. cloud) is an open decision; no keys are bundled in the app |

## Tech stack

Kotlin 2.0, Jetpack Compose (Material 3), Navigation Compose, AndroidX Lifecycle/ViewModel,
coroutines/Flow. AGP 8.7, compileSdk/targetSdk 35, minSdk 26, JVM target 17.
