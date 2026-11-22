# iOS considerations for Drive (planning notes only)

Drive (Phase 3) is Android only. **No iOS code exists or was started.** These notes record what a future iOS app can and can't mirror, so nobody assumes Android behavior carries over. Items marked *verify* must be checked against Apple's current documentation before any iOS work starts.

The shared parts are written without Android types and can be mirrored: `handsfree/` contracts (`MediaControl`, `MessagingService`, `CallService`, `HandsFreeDialog`, `HandsFreeSafety`, `VoiceCommandRouter`), `vehiclecontrol/`, and `ui/drive/DriveState.kt`. Everything in `handsfree/android/` and the Compose UI is platform-specific.

| Area | Android (built) | iOS (what to expect) |
|---|---|---|
| Media playback | Media keys through `AudioManager`; with notification access, the active `MediaSession` (now playing, transport controls) | An app can't control other apps' playback. `MPMusicPlayerController` controls **Apple Music** only. Spotify and others need their own SDKs or Siri/App Intents. No "what's playing in another app" |
| Calling | Hands the number to the dialer (`ACTION_DIAL`); the user taps call | `tel:` URL opens the system call prompt; the user confirms. CallKit is for an app's own VoIP calls, not for cellular calls |
| Answering/ending calls | Deferred on Android (needs call permissions) | Not possible for third-party apps (cellular) |
| Reading messages | Notification access (`NotificationListenerService`) | Not possible: no access to SMS, iMessage or other apps' notifications |
| Replying | The notification's own reply action, after spoken and on-screen confirmation | `MFMessageComposeViewController` shows a compose sheet; the user must tap Send. No silent sending |
| Contacts | System picker, one contact at a time, no permission | `CNContactPickerViewController` works similarly without a permission prompt *(verify)* |
| Bluetooth to the OBD adapter | Bluetooth Classic (SPP) ELM327 | **CoreBluetooth supports BLE only.** Classic ELM327 adapters won't connect; a BLE adapter (or Wi-Fi adapter) would be needed |
| Siri | Not used | Siri integration only through **App Intents / App Shortcuts**. There is no wake-word hook for a custom assistant, and Penelope's "two-way voice" would run inside the app *(verify what runs in the background)* |
| CarPlay | Not used | Needs an Apple **CarPlay entitlement** for a supported app category, and apps may only use Apple's templates (no custom Compose-style screens). The list of categories changes; *verify the current list* before planning. A diagnostics companion may not fit any category |
| Vehicle controls | Capability-only, all unavailable | Same rule: no control path exists via an OBD adapter. A manufacturer's own SDK would be needed |

## Principles to keep
- Don't promise iOS parity in the UI copy or the store listing. Each iOS limit above becomes an honest "not available on iPhone" state, the same way Android shows setup or handoff states.
- Keep confirmation before sending or dialing. On iOS the system adds its own confirmation, which is acceptable and should not be bypassed.
- Keep privacy rules: message text and contact names never go to logs, history or the AI.
