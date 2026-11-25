# Hands-free foundation (Phase H0) and Drive (Phase 3)

Penelope is becoming a voice-first driving assistant: music, messages, calls, vehicle diagnostics and, later, navigation. Phase H0 built the **foundation** (contracts, routing, honest "not yet" replies, diagnostics). **Drive (Phase 3)**, described first below, switched on the parts Android supports. Sections 1 to 4 are the original H0 notes; where they say nothing controls music yet, Drive supersedes them.

## Drive (Phase 3): what works, what is a handoff, what is deferred

Drive is the Drive tab on both selectable levels (one shared implementation; the internal Advanced presentation is kept, see `ui/drive/`). **No new `uses-permission`**: no SMS, call, contacts or phone-state permission and no foreground service (`ManifestPolicyTest` enforces this). The only new manifest entries are the notification listener service (inactive until the user turns on notification access) and `queries` for the dialer, contact picker and media apps.

| Capability | Status | How (and what it is not) |
|---|---|---|
| Play / pause / next / previous | **Works as a request** | Without notification access: separate PLAY, PAUSE, NEXT and PREVIOUS media keys through `AudioManager` (never the PLAY_PAUSE toggle). With access: the active media session's transport controls. Penelope says "I asked your media app to pause", never that it did. Some apps ignore media keys |
| Now playing (title, artist, app) | **Needs notification access** | Android only lets a notification listener read media sessions. Without access Drive says "Turn on notification access to see what's playing" |
| Volume | **Works** | Music stream up/down with the system volume panel; hidden ("Volume is set by your car") when the volume is fixed |
| Compatible media apps | Whatever app has a session | No list of "supported apps" is claimed; Drive never implies universal control |
| Read messages aloud | **Needs notification access** | Message notifications only (conversation or message category), kept in memory (max 20), dropped when dismissed, read only when asked. No `READ_SMS`. The text is spoken but never shown in the transcript, logged or sent to Gemini |
| Reply by voice | **Needs notification access** | Uses the notification's own reply action. Only messages still in the notification shade. Read back, then yes (voice) or Send (button), then "Okay, I sent your reply to <app>". Penelope can't know it was delivered and never says so. No `SEND_SMS` |
| Call someone | **Handoff** | Choose a contact with the system picker (no contacts permission) or say a number. Penelope confirms, then opens the dialer (`ACTION_DIAL`); the user taps call. Never `ACTION_CALL` |
| Call by name ("call Mom") | **Not available** | Needs the contacts permission, which Penelope doesn't request. She says so and points to Call someone |
| Answer / decline / hang up | **Deferred** | Needs call permissions Penelope doesn't request. Honest "can't yet" reply |
| Car controls (climate, seats, locks, windows, remote start) | **Not available, by design** | Capability-only (`vehiclecontrol/`): no command method exists. A standard OBD-II adapter reads data; it can't control the car. A future manufacturer integration needs its own review |
| Wake word, background voice | Not built | Unchanged |

**Confirmation.** One state machine (`HandsFreeDialog`) serves voice and the on-screen card. Only a clear "yes", "yeah", "yep", "send it", "yes send it" or "confirm" (and "call" or "dial" for a call) confirms; anything else cancels; it times out after 30 seconds; the service is called once. What Penelope says never consists of an accepted word alone, so she can't confirm herself by hearing her own prompt.

**Privacy.** Names, numbers, message text and track titles are not logged, stored, or sent to Gemini. Their types print `redacted`. The routing log records only the command kind and capability status.

**Notification access setup.** Settings, Notifications, Notification access (or the button on Drive). On Android 13 and later, apps installed outside Google Play may need App info, three dots, **Allow restricted settings** first. Turn it off any time; Penelope forgets the in-memory messages.

**Still to verify on a phone (NOT RUN):** media keys and sessions with real apps (Spotify, YouTube Music, a podcast app); notification access granted and revoked; message readout and reply with real messaging apps (and their replies actually sending); the dialer handoff and contact picker; behavior over car Bluetooth; TalkBack; large font on the real device.

iOS: see `docs/IOS_DRIVE_NOTES.md` (planning notes only).

## 1. Debugging audit

| Area | Before this phase | Gap | Now |
|---|---|---|---|
| CI quality gate | Security scan, then build, Detekt, unit/Robolectric tests with coverage, Lint, release build, quality report artifact (`android-ci.yml`) | None found | Unchanged |
| Crash reporting | Crashlytics with no user identifiers; debug-only test crash in Settings (`CrashReporting.sendTestCrash`, covered by `CrashReportingTest`) | Handled errors aren't reported (by design: avoids leaking context) | Unchanged; still needs on-phone verification |
| Vehicle / Bluetooth | `ConnectionLog` VEHICLE events: connect stages, failures with reasons, retries, recovery | None | Unchanged |
| Internet / AI | `ConnectionLog` INTERNET and AI events (categories, HTTP status, retry delay) | None | Unchanged |
| Voice session | State machine fully unit-tested; **no runtime trace** | "Penelope stopped listening" couldn't be diagnosed from the phone | **Added:** VOICE events (idle, listening, thinking, speaking, paused, error + missing-permission flag) |
| Audio focus | Request result **ignored**; focus loss handled but not recorded | Silent replies during a phone call couldn't be explained | **Added:** AUDIO events (request granted/delayed/denied, focus changes, release) |
| Audio route | Not observed | Couldn't tell whether Penelope played through the car or the phone | **Added:** AUDIO "output changed" (A2DP, SCO, wired/USB, phone speaker); no device names |
| Permissions | Mic denial shown in UI; Bluetooth handled by the adapter manager | Mic denial not traceable | **Added:** via VOICE error `needsPermission=true`; Bluetooth already logged |
| App lifecycle | Backgrounding pauses voice | Not traceable | **Added:** APP foreground/background events |

All events go to the existing in-memory `ConnectionLog`: 300 events, never written to disk, redacted, visible in **Settings → Connection status** (technical log for DIY/Advanced) and copyable.

**Never logged:** what the user said, partial transcripts, Penelope's replies, error text, message contents, contact names, device names, Bluetooth addresses, VINs, credentials. Hands-free routing records only the command *kind* (`place_call`) and capability status, never who or what. A unit test feeds transcripts into the voice diagnostics and checks they don't appear.

**Overhead:** one small object per state change, capped at 300 entries; no I/O. The audio-route watcher is a system callback with no polling.

## 2. Architecture

```
            Voice (existing): SpeechInput → VoiceSession → ConversationManager → SpeechOutput
                                                         │
                                        HandsFreeReasoningProvider  (new, first in line)
                                           │ VoiceCommandRouter: media / messages / calls / navigation?
                         yes ┌─────────────┴─────────────┐ no
                             ▼                           ▼
          CapabilityProvider.status(capability)    GeminiReasoningProvider → RuleBased (unchanged)
          → service (later) or an honest
            "I can't … yet" reply
```

**Core** (`handsfree/`, no `android.*` imports; a test enforces this so an iOS app can mirror it):
- `HandsFreeCapability`, `CapabilityStatus` (Available / NeedsSetup / NotSupported / NotImplemented), `PlatformRequirement`
- Contracts: `MediaControl`, `MessagingService`, `CallService`, `CarAudioRoute`, `CapabilityProvider`, `ActionResult`
- `VoiceCommandRouter`: narrow command matching. Questions ("how do I play music through my car?") and Penelope's own "pause"/"stop" are **not** intercepted.
- `HandsFreeSafety`: rules every implementation must follow.
- `HandsFreeReasoningProvider`: routes commands before the AI, so Gemini never sees contact names or message text and never pretends to act.

**Android** (`handsfree/android/`): `AndroidHandsFreeCapabilities` (per-capability status, see the Drive table), `AndroidMediaControl`, `AndroidMessagingService` with `PenelopeNotificationListener`, `AndroidCallService`, `AndroidNotificationAccess`, and `AndroidCarAudioRoute`.

**Unchanged:** continuous conversation, half-duplex mic, OBD/diagnostics, Gemini, dashboards, onboarding. No new permissions.

### Safety rules (`HandsFreeSafety`)
- **Confirm before acting on someone else.** Sending a message or calling someone needs Penelope to repeat it and the user to say yes. Implementations must refuse without `confirmed = true`.
- **Voice first.** Results are spoken; nothing requires looking at or tapping the screen while driving.
- **Privacy.** Message text and contact names are never logged, saved to history or sent to the AI.
- **Vehicle warnings first.** Urgent vehicle alerts are never held back for media or messages.

## 3. Platform requirements (for the next phases)

These are planning notes, to be confirmed on real devices before each feature is built. Some features may not be possible, or may work differently, on each platform.

| Capability | Android (likely route) | Needs | iOS (likely route) |
|---|---|---|---|
| Media play/pause/next | `AudioManager.dispatchMediaKeyEvent` to the active session; or `MediaSessionManager` for now-playing info | Media keys: nothing. Now-playing: **notification access** (user turns it on in system settings) | No control of other apps' playback. Apple Music via `MPMusicPlayerController` only; others via Siri/App Intents |
| Read messages | `NotificationListenerService` (works across SMS, WhatsApp etc.) | **Notification access** | Not possible for third-party apps |
| Reply to messages | Notification `RemoteInput` reply action | Notification access; spoken confirmation | `MFMessageComposeViewController`: the user must tap Send |
| Place calls | `ACTION_CALL` intent | `CALL_PHONE` (+ `READ_CONTACTS` for names); spoken confirmation | `tel:` URL; the system asks the user to confirm |
| Answer / end calls | `TelecomManager.acceptRingingCall` / `endCall` | `ANSWER_PHONE_CALLS`; call state needs `READ_PHONE_STATE` | Not possible (CallKit is for the app's own VoIP calls) |
| Navigation | Intent to Google Maps / Waze | Nothing | Apple Maps / Google Maps URL schemes |
| Voice in background | Foreground service of type `microphone` | Must start while the app is visible (Android 14+ rule); persistent notification | Background audio session; limited microphone access |
| Car screen | Android Auto has fixed templates; assistant-style apps aren't a supported category, so it may not be allowed | Google Play review | CarPlay needs an Apple entitlement for the app category |

**Google Play policy:** SMS and call-log permissions (`READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`) are restricted to default handlers, so Penelope should use notifications and intents instead. Becoming the default dialer (`ROLE_DIALER`) isn't planned.

**Bluetooth:** the car's hands-free microphone (SCO) changes recognition quality and needs `AudioManager.setCommunicationDevice`. It's left for real-car testing (`ARCHITECTURE.md`, audio routing).

## 4. Device tests still required (NOT RUN)
1. **Settings → Connection status → technical log** shows Voice, Audio and App events during a conversation, with no spoken words.
2. **Phone on car Bluetooth:** the log shows "Audio output changed: Bluetooth audio (A2DP)". Disconnect: "Phone speaker".
3. **Receive a phone call while Penelope talks:** "Audio focus changed: loss_transient", and voice pauses.
4. **Ask "answer the call":** Penelope says she can't yet, nothing else happens, and she keeps listening. (Music, messages and calls: see the Drive section.)
5. **Ask "how do I play music through my car?":** a normal answer (not intercepted).
6. **Background the app:** "App in background" is logged and voice pauses, as before.

## 5. Recommended next phase (H1: media control)
Lowest risk and no special permission: play/pause/next/previous through media key events, behind `MediaControl`, switched on in `AndroidHandsFreeCapabilities`. Now-playing info (opt-in notification access) can follow. Messages and calls come after, each with spoken confirmation and its own device test plan.
