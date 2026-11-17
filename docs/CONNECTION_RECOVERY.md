# Connection recovery and Gemini reliability (Phase 7)

Three separate systems:
- the **vehicle connection**: Bluetooth ELM327 to the car's OBD-II port;
- the **internet connection**;
- the **AI service**: Gemini through Firebase AI Logic.

Each has its own diagnosis and recovery, but they share one plain-language status and one redacted log (**Settings → Connection status**).

## 1. Root causes found

### Gemini "daily usage exhausted" after nearly every question; restarting helped
These causes were confirmed in code and reproduced in tests, which failed before the fix.

1. **The outage notice was sent back to Gemini.** When a request failed, Penelope's reply started with *"I can't reach my smarter answers right now (today's AI usage limit was reached…)"*.
   - That whole text was stored in the conversation history sent with every later request, so Gemini saw itself saying the limit was reached and could repeat it.
   - Restarting cleared the in-memory history, which is why a restart "fixed" it.
   - Test: `GeminiFalseQuotaRegressionTest`.
2. **No back-off.** During a per-minute rate limit (HTTP 429), every new question went straight back to Gemini, ignoring the server's "retry in N s".
   - Each question failed again and made the limiting worse; a restart took long enough for the window to pass.
   - Test: 4 requests were sent in a 40 s window before the fix, 1 after.
3. **Per-minute limits labeled as the daily quota.** Builds before PR #16 called every 429 the daily quota; fixed in PR #16 (`TemporaryAiErrorsRegressionTest`).

**Not verified (needs device logs):**
- **App Check token fetches:** whether the per-request App Check token fetch (`useLimitedUseAppCheckTokens`) hits its own limit.
- **Status codes:** the exact status codes on the S26 Ultra. The new log records each failure's category, HTTP status and retry delay, which will show this.

### Internet interruptions disrupted Penelope
- **Nothing watched the network.** Offline was only discovered when a request failed. Gemini requests then waited up to 30 s, and slow networks were reported as "no internet" (fixed in #16).
- **Voice stopped for good.** A speech-recognition network error **ended the hands-free conversation**, and nothing resumed it when the internet came back.
- **Routing was already safe, now guarded.** Nothing binds the whole app to one network, so a vehicle network such as ENET (no internet) can't capture Gemini requests. `NetworkRoutingGuard` in `InternetReliabilityTest` keeps it that way.

### BMW 528i: adapter connects, vehicle doesn't respond reliably
- **No protocol fallback.** In automatic mode, Penelope relied only on the adapter's own protocol search (`ATSP0`). When that search fails, as some ELM327 clones do on CAN cars like the F10, she never tried ISO 15765-4 CAN 11-bit 500k (`ATSP6`) explicitly.
- **Stale "connected" status.** Once the car had answered, the status stayed "Connected to your car" even when the car stopped answering. There was no health tracking and no recovery short of a Bluetooth drop.
- **One slow first answer could drop the connection.** This was fixed in PR #16.
- **Not verified:** which of these the F10 actually triggers needs the real car. The technical log now shows every protocol tried and every answer.

## 2. What changed

### Vehicle (`diagnostics/obd`)
- **Stages:** adapter not found, adapter found, pairing required, adapter connected, initializing, waiting for the vehicle, ECU responding, vehicle connected, unstable, recovering, failed.
  - These are defined in `VehicleLinkStage`.
  - **Only a valid ECU answer gives "vehicle connected".** Everyday Driver users see plain sentences.
- **Protocol choice (automatic mode only),** in this order:
  1. the protocol this car answered on last time (stored per vehicle ID, never the VIN);
  2. the adapter's automatic search;
  3. generic fallbacks by model year (2008+: CAN 11/500, then CAN 29/500; older: K-line too).

  Protocols the adapter rejects are skipped. A protocol chosen in Advanced settings is always respected. `VehicleProtocolProfile` is the interface for future vehicle-specific profiles; no BMW profile is hard-coded.
- **Health and recovery:**
  - **Degraded:** 3 vehicle requests in a row without a valid answer mark the link degraded. Live polling then pauses, so it doesn't add traffic.
  - **Recovery steps:** recovery re-checks the car up to 3 times (after 1, 3 and 8 s), then re-initializes the adapter once. If the car still doesn't answer, Penelope says so and stops; there's no endless loop.
  - **Bluetooth link lost:** a lost link uses the existing reconnect path (2, 5 and 10 s).
  - **What stops recovery:** disconnecting stops everything. Only one recovery runs at a time.
  - **Commands:** recovery sends only read-only commands (`ATZ`, `AT…` setup, `ATSPn`, `0100`).
- **ENET is separate.** BMW ENET (HSFZ over Ethernet) uses different procedures. It isn't part of this app and lives on the `experiment/*` branches. ELM327 commands are only ever sent to ELM327 adapters.

### Internet (`connectivity/InternetMonitor`)
- **Watches the default network:** online means Android has *validated* internet. A captive Wi-Fi sign-in page and a no-internet Ethernet both count as offline. The monitor only observes: it never switches networks or asks for permissions (`ACCESS_NETWORK_STATE` is a normal permission with no prompt).
- **Gemini:** isn't contacted while offline. The user hears *"Your internet connection was interrupted. I'll reconnect when it's available."*
- **Voice:**
  - after a network error mid-conversation, Penelope pauses, says the notice once, and **resumes listening by herself** when validated internet returns;
  - at most 2 automatic resumes in a row, so a flaky network can't loop;
  - while offline, speech recognition prefers the phone's on-device model.

### Gemini (`ai/GeminiReasoningProvider`)
- **Notices:** shown to the user, never added to the conversation history (`AiNotices`).
  - **Temporary problem:** *"I'm having trouble reaching the AI service. Your conversation is saved."*
  - **Genuine daily limit:** *"Today's AI usage limit has been reached…"*. This is only shown when the provider says per-day.
  - **Recovery:** *"Connection restored. We can continue."*, only after a real successful reply.
- **Waiting:**
  - A rate limit is respected: no requests until the provider's retry time, or 30 s if none is given.
  - A wait of 3 s or less is waited out once, so the answer still arrives.
  - **Daily limit:** no requests for an hour.
  - **Configuration problems** (App Check, service disabled): 5 minutes.
  - **Settings → Reset conversation** retries immediately.
- **One request at a time,** with no duplicates.
- **Self-repair:** after 2 unexplained failures in a row, the client resets. The conversation is kept.
- **Logs:** category, HTTP status, retry delay and the next attempt. Never what was said.

### Diagnostics (`connectivity/ConnectionLog`)
- **In memory only,** holding the last 300 events.
- **Masked:** VINs (last 4 shown), hardware addresses (last 2 bytes) and key- or token-like strings.
- **Where to find it:** Settings → **Connection status**.
  - **Everyone:** a summary of the car, internet and AI.
  - **DIY and Advanced:** also the technical log, with **Copy log** and **Clear**.

## 3. Tests (simulated adapters and mocked AI; no hardware, no live Gemini)
| Area | Tests |
|---|---|
| Gemini | `GeminiFalseQuotaRegressionTest`, `GeminiReliabilityTest`, `TemporaryAiErrorsRegressionTest`, plus updated safety and integration tests |
| Internet | `InternetReliabilityTest` (validation, captive portal, Wi-Fi↔mobile, ENET plus mobile, routing guard, log redaction) |
| Voice | `VoiceNetworkRecoveryTest` (auto-resume, bounded, one announcement, no overlap, AI failure keeps the mic loop) |
| OBD | `ObdConnectionRecoveryTest`: CAN fallback (the BMW case), memory, unresponsive ECU, rejected protocols, manual settings, stages, degraded→recovered, bounded give-up, user disconnect, link loss, read-only only, ENET isolation |
| UI | `ConnectionStatusUiTest` |

## 4. Verify on the S26 Ultra and the 2011 528i (manual)
Record each result as PASS, FAIL or NOT RUN.

1. **Car connection:**
   - With ignition **on**, connect the adapter. The Adapter screen should go *Adapter initializing → Waiting for the vehicle → Vehicle connected*.
   - In Settings → Connection status → Technical log (Advanced level), note which protocol answered.
2. **Ignition off, or adapter connected with the engine off:** expect *"Your scanner is connected, but your vehicle hasn't responded…"*, never "Connected".
3. **Turn the ignition off while connected:** expect *Reconnecting…*, then *waiting for the car* within about 15 s. Turn it back on and tap **Check vehicle again**.
4. **Gemini:**
   - Ask several questions in a row. If a limit appears, check the log for `category` and `httpStatus`.
   - Questions after the stated wait should work without restarting the app.
5. **Internet:**
   - Mid-conversation, turn on Airplane mode: Penelope says the internet notice once and pauses.
   - Turn Airplane mode off: she resumes listening by herself.
   - Switch from Wi-Fi to mobile data: answers should continue.
