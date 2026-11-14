# Installing and testing on the Samsung Galaxy S26 Ultra

## Install
1. On the phone: **Settings → About phone → Software information**, tap **Build number** 7
   times; then **Settings → Developer options → USB debugging** on.
2. Connect USB, accept the debugging prompt, then from the project:
   `./gradlew installDebug` (or Run ▶ in Android Studio). Alternatively copy
   `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it (allow "Install
   unknown apps" for your file manager).
3. Upgrading from v0.1: profiles from v0.1 were in-memory only, so re-add your vehicle once.

## 1. Vehicle identification (needs internet)
- Vehicles → **+** → **Search**: 2011 → BMW → 528i. Engine (3.0L 6-cyl) and transmission
  (Automatic, 8-speed) auto-select and the review form fills in.
- In the form set **Cylinder layout = Inline**, **Engine code = N52B30**, trim e.g. "F10",
  your odometer → **Confirm and save**. Dashboard should read "3.0L I6 Gasoline" (never V6).
- **VIN** tab: enter your real VIN → Decode. Expect year/make/model/3.0 L/6 cyl and a list of
  fields NHTSA couldn't determine. Tap "Use these details", review, save.
- Airplane mode: lists you opened before still appear ("Saved list"); new ones show an
  error with Retry; **Manual** always works.

## 2. Penelope — hands-free conversation (Phase 3)
1. Settings → Penelope's voice → **Reset to natural voice**, then **Hear a sample**. If it still
   sounds high, try lowering pitch, or switch **Speech engine** to Google and pick a voice that
   "works offline".
2. Assistant tab → tap the mic once. Ask "Why is my car shaking?" → she answers and asks a
   follow-up → **without tapping**, answer "when I'm stopped" → she replies → keeps listening.
3. "Explain P0171" → "explain that more simply" → "give me the technical explanation".
4. Stay silent twice → she says she'll pause; the pill shows **Paused**; mic is off (Android's
   green mic privacy dot disappears).
5. Say "goodbye" → conversation ends; the mic dot disappears.
6. While she's talking, tap the mic → she stops and listens. Tap **End conversation** anytime.
7. Press Home mid-conversation → it pauses; the mic indicator turns off.
8. Play music, start a conversation → music pauses and resumes after "goodbye".
9. On car Bluetooth: her voice should come through the car speakers. Note whether the phone
   or car microphone is used and how well she hears you.
10. Settings → **How much detail?** → try each level on the dashboard and with "explain P0300".
11. TalkBack on: controls are announced; status changes are spoken.

## 2b. Penelope (earlier checks)
- Assistant tab → tap the mic → allow microphone. Try: "Penelope, what vehicle am I driving?",
  "Tell me about my engine", "Read my trouble codes", "Explain P0420",
  "What's my coolant temperature?", "What can you do?"
- Expect spoken answers plus written transcript. Without an adapter, the coolant question must
  say no adapter is connected (no number).
- Tap the mic while Penelope talks → she stops and listens. "Stop" button stops speech.
- Deny the mic permission once → message + typed fallback; "Open app settings" link.
- Settings → rename to e.g. "Ada" → "Hey Ada, tell me about my engine" works.
- TalkBack: Settings → Accessibility → TalkBack on; mic button announces "Talk to Penelope",
  status changes are announced.
- Offline recognition: airplane mode + mic. Samsung/Google may still recognize on-device if
  the language pack is installed; otherwise you'll get a clear "needs internet" message.

## 3. Bluetooth OBD-II (Panlong adapter)
1. Plug the adapter into the OBD-II port (under the dash, driver's side). Ignition ON
   (engine running or accessory/ignition mode).
2. Phone **Settings → Connections → Bluetooth** → pair "OBDII" (PIN usually 1234 or 0000).
3. App → **Adapter** tab → allow "Nearby devices" → the adapter appears (should say
   "Bluetooth Classic") → **Connect**.
4. Expect: "Connected to OBDII", protocol "ISO 15765-4 CAN (11-bit, 500 kbaud)" on the 528i,
   live RPM/coolant/speed/voltage updating about once per second.
5. **Read trouble codes** → result saved; check Dashboard and History. Ask Penelope
   "what's my coolant temperature?" and "read my trouble codes".
6. Ignition off → status "vehicle isn't responding"; ignition on → "Check vehicle again".
7. Unplug the adapter while connected → "Reconnecting (1 of 3)…" → reconnects when plugged
   back in, or "Lost connection" after 3 tries.
8. Relaunch the app → it reconnects to the last adapter automatically.

Please report: adapter name and radio type shown, protocol, which live values appear, any
error text, and whether codes match another scan tool.
