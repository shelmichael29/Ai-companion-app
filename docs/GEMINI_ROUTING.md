# Gemini as Penelope's primary intelligence

Why Penelope gave canned answers while online, what changed, and how to check it on the phone.

## 1. Root causes (confirmed in code)
Each of these sent a question to the built-in rule-based answers instead of Gemini, or hid that it had.

| # | Cause | Kind | Where |
|---|---|---|---|
| 1 | Every question mentioning a trouble code went to built-in logic ("what does P0171 mean?") | Routing | `AiRouting`: `ExplainCode → DETERMINISTIC` |
| 2 | "My check engine light is on…" and anything with "any", "check", "my … codes" went to built-in logic | Routing | `CODE_READ` regex |
| 3 | Questions naming a sensor plus a common word ("what is", "how is", "tell me", "check", "level") went to built-in logic, e.g. "what is the coolant for?" | Routing | `DATA_REQUEST` regex |
| 4 | Any sentence containing "help" ("can you help me with my brakes?") got the canned help list | Routing | `Help → DETERMINISTIC` (the parser checks "help" before symptoms) |
| 5 | Anything mentioning Bluetooth, adapter, OBD or "connected" got the canned connection status | Routing | `ConnectionStatus → DETERMINISTIC` |
| 6 | After the first failure, later fallback answers had **no notice**, so canned answers looked like AI answers | Fallback | `announcedOutage` was set once per outage |
| 7 | When Gemini's reply failed verification, the built-in answer replaced it **silently** | Validation | `ReplyGuard` path |
| 8 | An App Check or authentication error paused Gemini for **5 minutes** (all built-in answers, only the first announced) | App Check | `configCooldownSeconds` |
| 9 | Timeout was 30 s overall (15 s per attempt) and not adjustable | Timeout | `aiTimeoutMillis` |
| 10 | Nothing recorded *which* source produced an answer, so it couldn't be checked on the phone | Diagnostics | none |

**Likely on the phone too, not verifiable here:** an unregistered App Check debug token. Firebase then rejects every request with 401/403 (→ cause 8). Builds without `google-services.json` always use built-in answers (status "Not set up").

**Safety gap found during review:** "my brakes stopped working", "my brakes failed", "no brakes" and similar weren't recognized as emergencies, and "can I drive with P0300?" was treated as a code question. Both are fixed: safety now wins over a code in the same sentence.

## 2. New routing (`AiRouting`)
| Route | Questions | What happens |
|---|---|---|
| **Built-in, always** | Emergencies (smoke, fuel smell, overheating, brakes, oil pressure, flashing check-engine light), "can I still drive?", goodbye/pause/repeat, connection status | Immediate built-in answer. Never waits for Gemini; works offline |
| **App reads data, then Gemini explains** | Read codes / check engine light, "what's my coolant temperature?" and other current values, sensor readings, help | The app reads the real data through its own services first. Gemini gets that result marked as verified and explains it; it can't change numbers or codes |
| **Gemini** | Everything else: code explanations, how-things-work questions, non-urgent symptoms, follow-ups ("tell me more", "simpler"), greetings, vehicle questions | Gemini with the full conversation history and the verified vehicle facts |

Hands-free commands (music, messages, calls) are still handled first, before any of this.

## 3. Timeout and slow answers
- **Timeout:** default **12 s**, adjustable without a new build through the Remote Config parameter `penelope_ai_timeout_ms`, clamped to 6–30 s.
  - Every answer's duration is logged.
  - If the log shows `AI_TIMEOUT` on a good connection, or normal Gemini answers taking 9–12 s, raise it to 15000–20000 in the Firebase console. Lowering it without that evidence isn't recommended.
- **Thinking status:** shown immediately. After 4 s the screen says **"Still thinking…"**. It's never spoken, so it can't overlap Penelope's voice.
- **One request at a time:** a late answer after a timeout is discarded, so there's never a second reply.
- **Recovery:** after a timeout, the next question tries Gemini again. After an App Check or authentication error, Penelope waits **1 minute** (was 5), so a newly registered token works quickly. **Settings → Reset conversation** retries at once.

## 4. Fallbacks
- **Never silent.** The first fallback in an outage explains why ("…isn't verified with Firebase App Check yet…"). Every later one starts with "My smarter answers are still unavailable, so here's my basic answer." Offline, it says "Still offline…".
- **Unverifiable Gemini reply** (an invented code or reading): "I couldn't double-check the AI's answer, so here's what I know for sure." and then the verified answer.
- **Still useful offline:** data questions still give the real reading or codes from the car.
- **Notices never go into the history Gemini sees.**

## 5. Response-source diagnostics
Every answer writes one line to the diagnostic log: `AI: Answer (source=…, ms=…, model=…, category=…)`.

| `source` | Meaning |
|---|---|
| `GEMINI` | Gemini answered |
| `GEMINI_WITH_VEHICLE_DATA` | The app read the data, Gemini explained it |
| `SAFETY` | Built-in safety answer |
| `CONVERSATION_CONTROL` | goodbye / pause / repeat |
| `DEVICE_COMMAND` | Hands-free command or connection status |
| `OFFLINE_FALLBACK` | No internet |
| `AI_TIMEOUT` | Gemini didn't answer in time |
| `AUTH_ERROR` | App Check or API-key rejection |
| `RATE_LIMIT` / `QUOTA` | Per-minute limit / daily quota |
| `INVALID_RESPONSE` | Gemini's reply failed verification, or was empty |
| `AI_SERVICE_ERROR` | Other Gemini error |
| `AI_OFF` / `NOT_CONFIGURED` | Smarter answers off / Firebase not in this build |

- **What's never logged:** questions, answers, names, tokens or keys.
- **Where to see it:**
  - **Everyone:** Settings → Connection status → **"Last answer came from"**.
  - **DIY and Advanced:** the full technical log.

## 5a. Personal details (owner decision 2026-11-21)
Owner-entered text in Gemini's background facts (the last 5 service titles and the last 3 problem descriptions) goes through `PersonalDetailScrubber` first: phone numbers, email addresses, street addresses, web links, VIN-like codes and licence plates written after "plate"/"tag"/"registration" become "[removed]". Trouble codes, engine codes, sizes, mileage and dates stay. It runs before the 160-character cut, so no partial detail slips through. Limits: people's names aren't detected, and phone numbers are matched in North American formats. The user's own question is sent as asked. Tests: `PersonalDetailScrubberTest`.

## 6. Testing on the Galaxy S26 Ultra
1. **Install** the CI APK from this PR. Make sure **App Check** is set up for its signing key:
   - with the `DEBUG_KEYSTORE_BASE64` secret set, it's a one-time step;
   - without it, register the new debug token from Logcat (`DebugAppCheckProvider`) in Firebase → App Check → Manage debug tokens.
2. Settings: **Smarter answers on**; level **DIY** (so the technical log is visible).
3. **Ask:** "Is a BMW 528i expensive to maintain?"
   - **Expected:** a natural answer.
   - **Last answer came from:** "Gemini (model, x.x s)".
4. **Ask:** "What does P0171 mean?" → Gemini.
5. **Follow up:** "Is that serious?" → Gemini answers about P0171, which shows the context carried over.
6. **With the adapter connected and the ignition on, ask:** "My check engine light is on, what does it mean?"
   - **Expected:** codes are read, then explained.
   - **Source:** "Gemini, explaining verified vehicle data".
7. **Ask:** "I smell gas" → an immediate safety answer; source **Built-in safety answer**.
8. **Airplane mode, then ask anything** → the internet notice, then the basic answer; source **no internet**. Turn airplane mode off and ask again → "Connection restored", then Gemini.
9. **If you get built-in answers while online:** read the source line.
   - **App Check or authentication error:** register the token (step 1) and wait a minute, or use Reset conversation.
   - **Gemini timed out:** note the durations in the log and raise `penelope_ai_timeout_ms`.
   - **Daily quota / rate limit:** a Firebase plan limit.
10. **Check the log:** DIY → Connection status → Technical log → **Copy log** if you want to share it. It contains no conversation text.

## 7. Not verified here
- **Real Gemini latency:** the 12 s default is a starting point, so check the logged `ms` values on the phone.
- **The exact App Check or Firebase error text** your build receives: the classifier is tested against known message formats only.
