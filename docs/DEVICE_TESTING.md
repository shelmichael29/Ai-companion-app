# Installing and testing on the Samsung Galaxy S26 Ultra

## Install
1. On the phone: **Settings → About phone → Software information**, tap **Build number** 7
   times; then **Settings → Developer options → USB debugging** on.
2. Connect USB, accept the debugging prompt, then from the project:
   `./gradlew installDebug` (or Run ▶ in Android Studio). Alternatively copy
   `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it (allow "Install
   unknown apps" for your file manager).
3. Upgrading from v0.1: profiles from v0.1 were in-memory only, so re-add your vehicle once.

## 0b. Conversation screen layout
1. Open Chat. The introduction (suggestions) shows with the large orb.
2. Start talking. The orb shrinks into a compact bar, the introduction disappears and messages fill the screen.
3. After several answers, scroll up and keep the conversation going. The list must not jump. "New messages ↓" appears; tap it to return.
4. Type a message with the keyboard open. The input sits just above the keyboard (no big gap) and above the floating navigation when the keyboard is closed.
5. Phone font size at maximum (Settings → Display → Font size): nothing overlaps, and all buttons are reachable.
6. Check both color styles (Midnight and Amethyst), and with Reduce motion on.
7. With TalkBack: the mic, pause, end, send and "New messages" buttons are announced, the orb isn't read twice, and voice status changes are spoken.
8. Tap Clear. The introduction returns.

## 0a. Gemini as primary intelligence
See `docs/GEMINI_ROUTING.md` §6: check "Last answer came from" in Settings → Connection status for each kind of question.

## 0. Hands-free foundation (Phase H0)
See `docs/HANDS_FREE.md` §4: diagnostics log entries, audio route, phone-call focus loss, and "not yet" replies for music, messages and calls.

## 0c. Phase 1: three interfaces
See `docs/PHASE1_UI.md`, "Manual check on the phone" (level switching keeps data, Easy/Customizable per level, Advanced black-and-grey with header mic, Live Data states, 200% font, TalkBack, light theme).

## 1. Vehicle identification (needs internet)
- Vehicles → **+** → **Search**: 2011 → BMW → 528i. Engine (3.0L 6-cyl) and transmission
  (Automatic, 8-speed) auto-select and the review form fills in.
- In the form set **Cylinder layout = Inline**, **Engine code = N52B30**, trim e.g. "F10",
  your odometer → **Confirm and save**. Dashboard should read "3.0L I6 Gasoline" (never V6).
- **VIN** tab: enter your real VIN → Decode. Expect year/make/model/3.0 L/6 cyl and a list of
  fields NHTSA couldn't determine. Tap "Use these details", review, save.
  (Replaced by the guided flow below; see `docs/VIN_SCANNER.md`.)

## 1c. VIN scanner, decoder and recalls (needs internet, camera)
- **Guide:** Vehicles → **+** → **VIN** → **Scan VIN with the camera**. Three places are explained with drawings;
  "Read this to me" speaks them (and stops if you leave the screen).
- **Permission:** first time, the Android prompt appears. Deny it: the screen explains and offers Allow camera and
  Type my VIN. Deny with "don't ask again": it offers Open phone settings; grant it there and come back (the camera
  should start without reopening the screen). Revoke it while scanning: no crash.
- **Scanning, in real conditions:** the VIN on the windshield plate (through glass, with reflections), the door-jamb
  sticker, a registration card, an insurance card, a photo of a paper. Each: does it find the VIN, how long, is the
  outline over the right text, is the result correct? Try dark (use the flashlight), glare, and a curved or dirty plate.
- **Never auto-picks:** even when the outline turns green, nothing happens until you press **Use this VIN**, and every
  character is editable. Aim at a page with two VINs: expect "more than one possible VIN".
- **Wrong reads:** if the scan misreads a character, correct it by hand; if it changed an I/O/Q it should say so.
- **Results:** the 2011 528i shows 3.0 L / 6-cyl (never V6), "A VIN can't tell me…" (layout, engine code,
  transmission…), manufacturer and plant. Recalls say "for the 2011 BMW 528i", never "your car is affected", with
  campaign number, component, problem, remedy and "Reported to NHTSA <date>". The NHTSA link opens the browser.
- **Recalls, accuracy (v0.7.1):** each result must show "Source: …" and "Checked <date, time>". Turn on airplane mode, then
  tap **Check recalls for this car** on a typed car: it must say "I couldn't check recalls right now… doesn't mean
  there are none" with **Try again**, never "no recalls". Turn the network back on and Try again: it should now show the
  campaigns. A scanned car's results show "Not confirmed for your VIN", never "open" or "outstanding". Add a car with
  **Search** (no VIN): recalls check by themselves, or by the button. Ask Penelope "any recalls on my car?" with and
  without a connection: both answers name their source, and the offline one says it is saved data. For an old car
  (for example a 1990s model) recalls must be listed normally, with the eligibility note and no "ineligible" wording.
  The home label should read "v0.7.1-beta • Build 8 • <8-character revision>" on a debug build.
- **Duplicate:** add the same VIN twice: the second time shows "already added", no second car, and "Open my saved car" works.
- **Offline:** airplane mode: the decode explains it can't reach NHTSA and "Fill in my car's details myself" works.
  Turn airplane mode on after decoding: recalls show the saved snapshot labeled "saved data", or "couldn't check", and
  Save still works.
- **TalkBack:** every step is readable and in a sensible order; scan progress and warnings are announced; flashlight
  and voice buttons say on/off; recall cards expand with "Show details". Also check 200% font.
- **Battery/heat:** scan for two minutes; the phone shouldn't get warm, and the camera light/flashlight must turn off
  when you leave the screen or lock the phone.
- **Privacy:** confirm no photo appears in the gallery or app storage after scanning.
- **Camera lifecycle (not unit-testable):** open the scanner and press Back immediately, 20 times in a row (also
  "Type my VIN instead" right after opening, and lock the phone mid-scan). No crash, and the camera indicator dot and
  flashlight must go off every time, including when the screen closes before the camera finished starting.
- **Rotation:** deny the camera once, rotate the phone: the permission prompt must not appear again by itself. Rotate
  during a scan and on the results screen: nothing resets unexpectedly.
- **Double tap:** tap **Save this car** twice quickly: one car only.
- **Clipboard:** "Copy my VIN" then check the clipboard preview doesn't show the VIN (Android 13+).
- Airplane mode: lists you opened before still appear ("Saved list"); new ones show an
  error with Retry; **Manual** always works.

## 1a. Gemini conversations (Phase 5A)
1. Put `google-services.json` in `app/`, build and install the **debug** variant from Android Studio.
2. Register the App Check debug token (docs/FIREBASE_SETUP.md §3).
3. Settings → Smarter answers: switch on; status should become "Working" after a question.
4. Talk (hands-free) and check:
   - "Is premium fuel really necessary for my car?" → natural answer labeled "AI explanation".
   - Follow-up: "What happens if I use regular?" → answer uses the previous turn.
   - "My car shakes a little at stop lights" → helpful reply, maybe one follow-up question.
   - "Explain that more simply."
   - Safety stays deterministic: "I smell burning" → "Safety first…" (not labeled AI).
   - Data stays deterministic: "What's my coolant temperature?" without the adapter → says it
     isn't connected; never a made-up number.
5. Airplane mode → Penelope answers with built-in logic and says she can't reach smarter answers.
6. Turn the switch off → built-in answers only. Settings → Reset conversation clears memory.

## 1b. Penelope UI 2.0 (Phase 4)
1. Home: greeting, glowing orb, "Talk to Penelope". Tap the orb or button → orb shows ripples
   that react to your voice → thinking arc → speaking waveform. Pause/End buttons appear.
2. Tap "My car is shaking." → opens Chat with Penelope's answer. Try "More ideas" chips.
3. Vehicle showcase: "Add photo" → pick a picture of your car → it appears on Home and in Garage.
   The badge says "Connected to your car" only when the adapter is actually connected.
4. Bottom nav: Home, Garage, Chat (named after your assistant), History. Settings is the gear.
5. Settings → Appearance: try Light, Dark, Match phone; turn on Reduce motion (orb and waves stop).
6. Android Settings → Accessibility → Remove animations: the orb should be still.
7. Large font size (Settings → Display → Font size) and TalkBack: check labels and wrapping.
8. Car health (Home card or History): result cards with plain explanations; details expand.

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

## 3a. Connection settings by level (Phase 4.1)
Change the level under **Settings → Detail level**.
1. **Everyday:** Adapter screen → **Connect my adapter**. It should connect to the Panlong
   without asking. MAC addresses, protocol and firmware must not be shown.
2. **DIY:** you should see **Connection details** (firmware, protocol and, after **Check voltage**,
   the port voltage at about 12.4 V engine off or about 14 V running) and **Troubleshooting** with
   the ignition off. **Reset adapter** should reconnect within a few seconds. **Reconnect**
   should appear after a failure.
3. **Advanced:** **Advanced connection settings**:
   - **Run connection test** with the engine running: every step should be OK or Check.
   - Protocol → *ISO 15765-4 CAN 11-bit 500k*. The adapter reconnects and "Car is answering on"
     stays CAN 11-bit 500k.
   - Protocol → *SAE J1850 PWM* with fallback on: the car should still connect (fallback). With
     fallback off, expect no vehicle response. Then **Restore recommended defaults**.
   - Adaptive timing → Off, adapter wait 200 ms. Live data should keep updating.
   - Polling 0.25 s versus 3 s: the update speed changes visibly.
   - Communication log on: entries appear. Send `0902` in the console (after ticking the
     acknowledgement). The reply is shown on screen, but the log shows "[redacted: vehicle
     identifier]". **Copy log**, paste it somewhere private, and confirm no VIN appears.
   - Console: `04`, `ATSH7E0` and `ATZ` must all be refused.
4. Switch back to **Everyday** with custom settings saved. The connection must return to
   automatic, and the Advanced screen should say the settings are saved but not active.

## Drive (Phase 3): checks on the phone (NOT RUN in automated tests)
1. **Media without notification access:** play music in any app, open Drive, tap Pause, Play, Next, Previous. The music app should respond; Drive says "I asked your media app…". Note which apps ignore the keys.
2. **Media with access:** turn on notification access (Android 13+ sideloaded builds: App info, three dots, Allow restricted settings first). Drive shows the title, artist and app, and the buttons control that app. Turn access off: Drive returns to "Turn on notification access".
3. **Volume:** Volume up/down changes the music volume and shows the system panel.
4. **Read messages:** with access on, receive a message in a messaging app, tap Read it to me or say "read my messages". The text is spoken and does not appear in the chat transcript.
5. **Reply:** say "reply saying I'll be late", check the read-back, say "yes" (or tap Send). The reply appears in the messaging app. Say anything else or tap Cancel: nothing is sent. Dismiss the notification first: Penelope says it can't be replied to any more.
6. **Call someone:** tap Call someone, pick a contact, confirm. The dialer opens with the number; you tap call. Say "call 5 5 5 …" for a number. "Call Mom" explains it can't look up names.
7. **Car controls:** every row says it isn't available, with the reason, on each level. Nothing is sent to the car.
8. **TalkBack, large font, light and dark themes** on each level; two-way voice still works on Drive.
9. **Over car Bluetooth:** Penelope's prompts are audible and the confirmation works by voice.

