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

## 0d. Assistant personalization (v0.8.0, build 9) (NOT RUN in automated tests)
Galaxy S26 Ultra. Automated tests use fake voices, so everything here needs the phone.
- Settings, Assistant: all 10 profiles appear; tap each, the tile highlights and the summary line changes.
- Tap each Preview button (10). Note which voices sound female or male. **Samsung vs Google engines label gender differently**: confirm by ear that "Female voice N" and "Male voice N" are right. The Google voice-code table in `VoiceSelector` is a hypothesis.
- Different profiles should sound different where the phone has enough voices; "Shares a voice with another profile" appears otherwise.
- Remove or lack a male voice pack: male tiles say "Uses the phone's default voice" and the notice appears. Never a fake male pitch.
- There is no Voice dropdown; the tiles are the only voice choice. Set Voice to Online: previews use network voices of the profile's gender if installed, else the note "This phone has no online voices installed; using offline voices." appears. With Online chosen, go offline, preview and speak: it falls back to the default voice instead of silence, and returns to the online voice when internet is back. Offline (default) never uses a network voice while an offline one exists.
- Rename to a custom name; say "hey <name>"; check Home, Drive, Chat and More show it. A name like "Janay-Latrice" is recognised when spoken.
- Start a continuous conversation, change profile or the Online/Offline setting mid-session: current reply is not cut off, the next reply uses the new voice, listening continues, tap-to-interrupt still works.
- Ask the same question as Ace and as Luna (Gemini on): wording and length differ; safety advice and facts do not. Knowledge level is unchanged by profile.
- Reset to default (confirm dialog); close and reopen the app: settings persist. Upgrading from 0.7.1 keeps a previously chosen name; a previously chosen separate voice now follows the selected profile.
- Font 100% and 200%, light and dark: no clipped or overlapping text. TalkBack: tiles read as radio buttons, previews as "Preview <name>'s voice".

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
3. **DIY: Advanced connection settings** (custom settings are switched off at DIY; the screen says they are saved but not active):
   - **Run connection test** with the engine running: every step should be OK or Check.
   - Changing protocol, timing or polling is saved but does not change the connection (still automatic).
   - Communication log on: entries appear. Send `0902` in the console (after ticking the
     acknowledgement). The reply is shown on screen, but the log shows "[redacted: vehicle
     identifier]". **Copy log**, paste it somewhere private, and confirm no VIN appears.
   - Console: `04`, `ATSH7E0` and `ATZ` must all be refused.
4. Everyday has no Connection screen entry and always connects automatically.

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


## 0h. Phase 1D: final stabilization checklist (v0.12.0, build 14) (NOT RUN: every item needs the owner's hardware)
Galaxy S26 Ultra and the 2011 BMW 528i F10 (N52N, engine code N52B30, inline-six). Automated tests use fakes (`FakeElmAdapter`, fake speech, fake AI), so nothing below has been run. Record each item as PASS, FAIL or NOT RUN with the date and build. Report: `docs/PHASE1D_STABILIZATION.md`.

Where each item runs: **[Phone]** the phone alone; **[Adapter]** phone plus the OBD-II adapter (a 12 V supply or the car with the ignition on); **[Vehicle]** phone, adapter and the 528i.

**Safe conditions for every [Vehicle] item:** parked on level ground, parking brake on, transmission in P. Ignition on with the engine off unless an item says to run the engine; run the engine only outdoors. Never test while driving; a second person handles the phone if the car must move. Never use **Clear codes** unless you really want the codes cleared: it keeps its confirmation, its engine-off check and its re-read, and no step below needs it.

### 1. Install and updates
1. [Phone] Install v0.12.0 (14) **over** v0.11.1 (13) without uninstalling. Android installs it as an update; the app opens without asking to sign in again or re-add anything. (Needs the fixed `DEBUG_KEYSTORE_BASE64` signing key; a build signed with another key is refused as an update.)
2. [Phone] Settings, About: "v0.12.0-beta • Build 14" and a revision matching the PR head. The home build label (Everyday, at the bottom) shows the same.
3. [Phone] After the update: vehicles, history, maintenance records, Live Data layouts, knowledge level, home layout, personality, custom name, speech speed and pitch, Online/Offline voice and continuous conversation are all as before.
4. [Phone] If an older build with Advanced selected is still available: install it, pick Advanced, then update straight to 0.12.0. The app opens at DIY with its layouts and personality intact.
5. [Phone] Firebase build only: Gemini answers after the update without registering a new App Check debug token (same signing key).

### 2. Everyday Driver
1. [Phone] Home, Drive, My Vehicle and More look as in `docs/screenshots/phase1/1-everyday-home.png` and the Phase 1C Everyday renders (nothing new, no technical terms).
2. [Phone] Large orb and mic start a conversation; suggestions open Chat; Extras and the layout grid icon still work.
3. [Adapter] Everyday connects automatically with the recommended settings even if DIY has custom ones saved (change the DIY poll interval first, then switch to Everyday and connect).
4. [Vehicle] Vehicle health in plain words after Read codes; recalls and VIN scan flows still open from My Vehicle.
5. [Phone] Profiles: add, edit and switch vehicles; maintenance history entries save and show.

### 3. DIY Enthusiast
1. [Phone] Tabs read Overview, Live Data, Diagnostics, Drive, More. More lists Garage, History, OBD-II adapter, Connection, Settings, Chat, Extras.
2. [Vehicle] Overview: connection steps, health counts after Read codes, the live strip matches the Live Data tab, history and maintenance panel, shortcuts.
3. [Adapter] Connection screen: protocol, timing, poll interval, reconnection, adapter test, communication log, read-only console. The console refuses `04` and any write. Restore recommended works.
4. [Vehicle] Diagnostics tab: read codes, technical explanations, "Tell me more"; "Clear codes…" shows its confirmation (cancel it).
5. [Phone] Manual adapter selection from the OBD-II adapter screen lists paired devices; choosing one connects to it.

### 4. Voice and personalities (critical)
1. [Phone] Each of the 10 profiles: select, preview, then talk. The voice matches the profile; personalities sound different where the phone has enough voices. The custom name appears in Home, Chat, Drive and More and is recognised when spoken.
2. [Phone] Continuous conversation, tap-to-interrupt, Pause and End, at both levels.
3. [Phone] Switch Everyday, DIY, Everyday: profile, voice, speed, pitch and Online/Offline do not change.
4. [Phone] Force-stop the app and reopen: same profile and voice. Repeat after the in-place update in 1.1.
5. [Phone] Online/Offline: Offline (default) never uses a network voice; Online prefers network voices and falls back without going silent when the phone goes offline.
6. [Vehicle] Voice during Live Data: DIY, Live Data tab open and polling, ask "what's my coolant temperature?" and "show me sensor readings". The answers arrive without a noticeably longer wait than with Live Data closed (expected: at most one extra adapter answer per reading). Note the times.

### 5. Bluetooth
1. [Adapter] First connection, then automatic reconnect when the app is reopened.
2. [Adapter] Turn Bluetooth off while connected: the status changes from connected to reconnecting or not connected within a few seconds on Home (Everyday and DIY); turn it back on and reconnect.
3. [Adapter] Unplug the adapter or cut its power while connected: the same; no frozen screen, no "connected" left showing.
4. [Phone] Adapter unavailable (switched off or out of range): a plain-language message, no endless spinner.
5. [Adapter] Pairing prompt and Bluetooth permission prompt appear once and are explained.

### 6. Live Data
1. [Vehicle] Gauges update about once a second; unsupported sensors read "Not supported"; values are never invented (ignition off: "Vehicle not responding", not zeros).
2. [Vehicle] A second screen with readings (Overview strip plus Live Data tab) does not slow updates.
3. [Vehicle] Leave Live Data and the Overview: the communication log shows polling stops on screens without readings (note: Home still polls a few readings in the background to keep the connection status honest; see `docs/PHASE1D_STABILIZATION.md`, finding L-1).
4. [Vehicle] Saved layouts: create, rename, switch, delete; gauge styles (analog, digital, line graph, numeric, status) and units.

### 7. Diagnostics
1. [Vehicle] Read codes: stored and pending counts match the Diagnostics tab and the check-engine wording.
2. [Vehicle] With the ignition off, Read codes says the vehicle is not responding instead of "no codes".
3. [Vehicle] Diagnostic history keeps each read with its time; no VIN appears in logs or shared reports.
4. [Vehicle] Clear codes (only if you want codes cleared, engine off): confirmation, engine-off check, re-read and history note all happen. Never from automated tests.

### 8. Dashboard customization
1. [Phone] Easy and Customizable home layouts at each level are independent of the knowledge level and of each other.
2. [Phone] Customizable: add, remove and move shortcuts; Car Health and Extras cannot be hidden; restart keeps the choice.
3. [Phone] Overview panel show, hide and reorder is **not built** (owner decision pending); nothing should offer it.

### 9. Firebase and Gemini
1. [Phone] Firebase build with the App Check token: Settings, Connection status shows Gemini working; a real answer arrives.
2. [Phone] Airplane mode mid-question: a plain-language fallback answer within the timeout; the app never freezes; answers return when online again.
3. [Phone] Personality wording differs between two profiles; facts and safety advice do not.
4. [Phone] Debug build only: Settings, About, Send test crash report; the report appears in Crashlytics without any user identifier.

### 10. Accessibility
1. [Phone] TalkBack on Home (both levels), Live Data, Diagnostics, Drive, More, Settings and Chat: every control is announced; the orb is not read twice; status changes are spoken once.
2. [Phone] Font size 150% and maximum, display size default and largest: no clipped or overlapping text; every button reachable; the floating navigation tabs stay easy to tap (Phase 1D fix for narrow displays at large font).
3. [Phone] Light and dark, Midnight and Amethyst; Reduce motion.
4. [Phone] Landscape: Home, Live Data and Chat scroll and stay usable (the Everyday orb fills the first screen; scroll for the mic).

### 11. Persistence
1. [Phone] Force-stop and reopen; then reboot the phone and reopen: level, layouts, personality, voice settings, connection settings, vehicles and Live Data layouts are kept.
2. [Phone] Switch level with the Live Data tab open, then back: no data lost, back navigation sensible.

### 12. Offline knowledge
1. [Phone] First launch after the update: the offline pack installs once (Settings, Connection status log: the "Offline knowledge" entry says available, with no failure stage); later launches reuse it.
2. [Phone] Airplane mode: ask about a trouble code and a recall; offline answers arrive and say they are offline.
3. [Phone] Storage (Settings, Apps, Penelope, Storage) shows one pack, not two, after the update.

## 0g. Phase 1C: DIY Overview (v0.11.0, build 12) (NOT RUN in automated tests)
1. Upgrade in place from v0.10.0 at DIY: vehicles, history, personality, voice, home layout and Live Data layouts intact. The tab bar reads Overview, Live Data, Diagnostics, Drive, More; Garage is under More.
2. Galaxy S26 Ultra, light and dark, normal and 200% font, TalkBack on: the pinned vehicle header and voice bar never hide the first panel; the voice bar stays one short row; nothing overlaps or is cut off; the nav bar clears the last panel.
3. TalkBack order: header (vehicle), voice bar (mic, chat, conversation details), then Connection, Vehicle health, Live data, History and maintenance, Tools and shortcuts. Status text and the connection sentence are announced when they change. The decorative orb is skipped. Panel titles are headings.
4. Real adapter, ignition on: the Connection steps go Bluetooth, then Adapter, then Vehicle ECU one at a time. With the adapter plugged in and the ignition off, it stops at "Adapter ready" and says the vehicle is not responding (never "Vehicle responding"). Turn the ignition on: it advances. Pull the adapter's power while connected: readings go "Stale" with their age, then the connection recovers or shows a problem in plain words.
5. Live data strip: values match the Live Data tab; unsupported sensors say "Not supported"; stale values are labeled with their age; sample data (Settings, Data) is labeled "Sample data" and the connection still says not connected. Changing the gauges on the Live Data tab changes the strip. No extra OBD traffic: the communication log shows no new PIDs.
6. Voice from the bar: tap the mic and talk; continuous conversation keeps going; Pause and End work; expanding the bar shows the last three turns, "Personality: ..." opens Settings, and "Open full conversation" opens the chat. Denying the microphone shows "Allow the microphone", which opens the app's settings. Every personality is still selectable and keeps its voice after switching levels.
7. Health: after Read codes, the counts and the check-engine wording match the Diagnostics tab. "Clear codes..." still needs its confirmation (never run it on the car without wanting to).
8. Level switching: at DIY open the Live Data tab, then Overview gear, Settings, switch to Everyday, Back: you land on Home with no Live Data tab. Switch back to DIY: Live Data is a tab again. Repeat from the DIY Garage screen (opened from More or the Overview header, with a back arrow) switching to Everyday, and from Everyday My Vehicle switching to DIY (Garage then shows with a back arrow under More). Everyday home, tabs, Drive and More look as before.
9. Switch levels several times between Garage and Settings: back navigation never shows two Garage screens in a row.
10. More (DIY): Garage, History, OBD-II adapter, Connection, Settings, Chat, Extras are all reachable; Settings shows Personality at the top.

## 0f. Phase 1B: Advanced features in DIY (v0.10.0, build 11) (NOT RUN in automated tests)
1. Upgrade in place from v0.9.0: vehicles, history, personality, voice and layouts intact. Before upgrading, on v0.9.0 DIY save a manual protocol (e.g. CAN 29-bit 250k with fallback off). After upgrading to 0.10.0 the Connection screen says the settings are active; if the car is silent the Troubleshooting tip appears, and Restore recommended recovers the connection.
2. DIY, Advanced connection settings: the screen says the settings are active. Change the poll interval or turn on the communication log, reconnect: the log records redacted traffic.
3. DIY, connected to the car: the adapter card lists the supported readings (fuel trims, MAP, MAF) and omits any the car doesn't report.
4. DIY: "are you connected?" mentions the protocol; "tell me about my engine" mentions the drivetrain. Everyday: neither.
5. DIY home, Results shortcut opens the Diagnostics tab; back behaves sensibly.
6. DIY with custom settings and the car not answering: Troubleshooting shows the "Restore recommended" tip; tapping Restore recommended fixes it.
7. Everyday: home, tabs, adapter flow unchanged. Every personality is selectable at both levels and keeps its voice after switching levels. TalkBack and 200% font on the new tip and readings.

## 0e. Phase 1A: two knowledge levels (v0.9.0, build 10) (NOT RUN in automated tests)
1. Upgrade in place (no uninstall) from a build that had Advanced selected: the app opens at DIY; vehicles, history, adapter and Live Data layouts are intact.
2. Settings, "How much detail?" shows exactly two choices (Everyday driver, DIY enthusiast); none is named Advanced. Restart the app: the choice persists.
3. Everyday: home, tabs, Drive and More look as before (no new technical info).
4. DIY: Adapter screen shows "Advanced connection settings"; More lists Connection; the screen says custom settings are switched off for now and the recommended settings are used; the read-only console refuses Mode 04.
5. TalkBack: each level is read as one radio button with its description; font size 200% leaves no overlap.
