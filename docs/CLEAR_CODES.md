# Clear codes (Mode 04)

The first and only vehicle-modifying command in Penelope. Owner decision 2026-11-21: DIY and Advanced only, next to "Read codes", only after codes were read in the current connection, only after an explicit confirmation. Never at the Everyday level.

## Safety design

- **Read-only paths are unchanged.** `ReadOnlyCommandPolicy` still rejects `04` (and every other write); the Advanced console, recovery and polling can never send it. `Elm327Session.execute` still throws for `04`.
- **One narrow door.** `Elm327Session.sendClearCodes(permit)` sends exactly `04`, once, never retried. It needs a `ClearCodesPermit`, which only `VehicleWriteGate` creates. A unit test scans the sources so no other file mints permits or calls `sendClearCodes` (besides `ObdManager`).
- **Confirmation token.** The confirmation UI asks for a `ClearCodesToken` when the user ticks "I understand". It is one-shot, expires after 60 seconds, and is bound to the vehicle and to the code read the user saw. Every redeem attempt consumes it. Cancelling, a new connection, or a refusal revokes it. A new code read voids it.
- **Preconditions are enforced in `ObdManager`, not only the UI** (both when issuing the token and when clearing):
  1. level is DIY or Advanced (`clearCodesAllowed`, supplied in `AppContainer`; default is off);
  2. adapter connected and the vehicle responding;
  3. a successful Mode 03 read of this vehicle in this connection (`codeReadSession`; cleared on any connect, disconnect or link loss, and by a failed read);
  4. a valid token for that vehicle and read;
  5. engine off: if the car reports PID 010C, it must be 0 rpm (running or unreadable rpm is refused). Engine off with ignition on is also recommended in the prompt, since OBD can't prove it.
  Only one clear runs at a time.
- **After sending:** the answer is interpreted (positive `44` only), codes are re-read (03/07), the read goes to history, and a note ("cleared by you", codes before and after) is saved as a history observation. The UI says "Codes cleared" only for a positive answer; if codes remain it says so; a negative or missing answer says nothing was (or may not have been) cleared.
- **Log:** `Clear codes sent` / `Clear codes result` / `Clear codes refused` go to the redacted connection log with counts only (no VIN, no vehicle id, no codes).
- **Tests** use `FakeElmAdapter` only. No automated test ever talks to a real car.

## What the user is told
Clears stored and pending codes and turns off the check-engine light; resets readiness monitors, so the car may not pass an emissions or smog test until it has been driven enough (often several days); the fault comes back if it isn't fixed; do it with the engine off and ignition on. Buttons: Cancel (default) and Clear codes, enabled only after "I understand".

## Manual device test (owner, real car; not automated)
1. Level DIY. Connect the adapter, ignition on, engine off. The "Clear codes…" button shows "Read codes first" and is disabled.
2. Switch to Everyday: the button is absent everywhere. Back to DIY.
3. Read codes (Adapter screen). The button enables on the Adapter screen, Diagnostics and the DIY home card.
4. Start the engine and open the dialog, tick the box, confirm: refused ("engine appears to be running"); nothing sent.
5. Engine off, ignition on: Clear codes. Expect "Codes cleared" and a new read with no codes (or the codes that came straight back). Check History for the new read and the "cleared by you" note, and that the light went out.
6. Wait more than 60 seconds after ticking the box: confirm is refused as timed out.
7. Disconnect and reconnect: the button is disabled again until codes are read.
8. Advanced console: `04` is still blocked.
