# Hands-free foundation (Phase H0)

Penelope is becoming a voice-first driving assistant: music, messages, calls, vehicle diagnostics and, later, navigation. This phase builds the **foundation only**. Nothing here controls music, reads messages or places calls yet.

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

**Android** (`handsfree/android/`): `AndroidHandsFreeCapabilities` (all NotImplemented today) and `AndroidCarAudioRoute`.

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
4. **Ask "play some music", "call Mom", "read my messages":** Penelope says she can't yet, nothing else happens, and she keeps listening.
5. **Ask "how do I play music through my car?":** a normal answer (not intercepted).
6. **Background the app:** "App in background" is logged and voice pauses, as before.

## 5. Recommended next phase (H1: media control)
Lowest risk and no special permission: play/pause/next/previous through media key events, behind `MediaControl`, switched on in `AndroidHandsFreeCapabilities`. Now-playing info (opt-in notification access) can follow. Messages and calls come after, each with spoken confirmation and its own device test plan.
