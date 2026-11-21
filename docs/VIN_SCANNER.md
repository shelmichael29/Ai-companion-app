# VIN scanner, decoder and recall check

Add a car by scanning or typing its VIN. Penelope decodes it with NHTSA, fills the car's profile, and shows recall campaigns for that model. Gemini is **not** involved anywhere in this feature.

## 1. The flow
Add a car → **VIN** tab → **Scan VIN with the camera** or **Enter VIN**:

1. **Guide:** where the VIN is (dashboard through the windshield, driver's door jamb sticker, registration or insurance papers), with simple drawings. "Read this to me" speaks it in Penelope's voice.
2. **Scan** (or type): CameraX preview with an aiming frame. A recognized VIN is outlined in yellow, then green once it's steady. Flashlight toggle (only when the phone has one) and a voice-guidance toggle. "Type my VIN instead" is always on screen.
3. **Confirm:** the VIN is shown in groups and is editable. The user presses **Use this VIN**, then **Look up my car**. Nothing is looked up before that.
4. **Results:** the decoded car, what the VIN can't tell, and recalls. **Save this car** or **Review or correct the details first** (back to the normal form, already filled in).

The guided steps replace the Add Vehicle form while open (so touch and TalkBack only ever see one screen) and hand back to it when done. Back goes one step at a time.

## 2. Reading the VIN
Code: `vin/VinScanning.kt` (platform-neutral), `vin/android/VinCameraView.kt`.

- CameraX `Preview` + `ImageAnalysis` (16:9, keep-only-latest, about 5 frames a second), ML Kit **bundled** text recognition (works offline, no Google Play services download; adds to APK size).
- Only whole 17-character tokens (or a whole printed line) count. Random 17-character windows inside longer text would pass the check digit by chance about 1 time in 11.
- **I, O and Q never appear in a VIN**, so they're always misreads: corrected to 1, 0, 0 and the user is told how many characters were changed.
- A failed check digit does **not** reject the VIN (some non-U.S. VINs don't use it) but shows a warning to compare every character.
- **Never silently selected:** a VIN is highlighted as steady only after 3 sightings in the last 8 frames with no competitor. Two competing VINs show "I'm seeing more than one possible VIN." and select nothing. Even a steady VIN only fills the confirm box: the user must press **Use this VIN**.
- Validation (`VinValidator`): 17 characters, no I/O/Q, check digit when it applies.

## 3. Camera permission
Asked only when the scanner opens (the guide screen already explained why). If it's denied the screen says why and offers **Allow camera** (or **Open phone settings** when Android won't ask again) and **Type my VIN instead**. Returning from settings re-checks the permission. A camera that fails to start also falls back to typing. The `CAMERA` permission and the camera `uses-feature` entries are **not required**: phones without a camera can still install the app and type a VIN.

## 4. Decoding (NHTSA vPIC)
`VinDecoder` / `NhtsaVinDecoder` (already in the app) → vPIC `DecodeVINValuesBatch` with one VIN in a POST form body (`HttpFormPoster`). Checked against the live service on 2026-11-21: results are identical to the single-VIN `DecodeVinValues` for valid, wrong-check-digit and invalid VINs. Extracted: year, make, model, series, trim, engine (size, cylinders, layout, code), fuel type, aspiration, transmission, drivetrain, body class, **manufacturer name, plant city and country**. Anything NHTSA didn't report stays blank and is listed as "A VIN can't tell me…". Engine layout stays *Unknown* (shown "6-cyl", never "V6") until the user confirms it.

- The profile is filled in but never saved automatically; the review form stays editable.
- **No duplicates:** if a saved car has the same VIN, the results say so and offer **Open my saved car**; saving a second copy is blocked (also from the normal form's Save).
- A decode failure (offline, timeout, NHTSA error, unknown VIN) returns to the confirm step with a plain explanation and **Fill in my car's details myself**. Saving a car never needs any lookup.

## 5. Recalls (NHTSA recalls API)
`vin/Recalls.kt`. Right after a successful decode: `https://api.nhtsa.gov/recalls/recallsByVehicle?make=…&model=…&modelYear=…`.

### The rule: this is a search by model, not by VIN
The public API lists recall campaigns for a **year, make and model**. It does **not** say whether a specific VIN is affected or whether its repair was done. So Penelope:
- never says a recall is "confirmed", "outstanding", "open" or "unrepaired" for the user's car;
- says "recall campaigns found for the 2011 BMW 528i", that NHTSA's public search can't tell whether this car is included or repaired, and links to the VIN-specific check;
- with no campaigns returned: "That doesn't guarantee your car has no recalls…";
- when NHTSA can't be reached: "I couldn't check recalls right now. You can still save your car."

Each campaign shows: campaign number, affected component (made readable), problem, what could happen, the manufacturer's remedy, and **"Reported to NHTSA <date>"** (the date NHTSA received the manufacturer's report; not a repair or completion date). NHTSA's `parkIt` / `parkOutSide` flags are shown as advisories ("Manufacturer advises not driving some affected cars"), never as a statement about this car.

### Where the user checks their VIN
- Always: **nhtsa.gov/recalls** (works for every make with the VIN).
- Plus the manufacturer's own page for Toyota/Lexus, Volkswagen and Mazda. Only addresses that were confirmed reachable on 2026-11-20 are listed; many other manufacturers' pages block automated checks or move often, so they are not linked rather than risk a dead link. **Owner decision (2026-11-21): keep this short verified list;** NHTSA's lookup covers every make. Add a manufacturer only after confirming its page in a browser. The links are fixed addresses: **the VIN is never put in a URL.** A **Copy my VIN** button lets the user paste it there.

### Offline
If NHTSA can't be reached, the already-installed offline knowledge pack's recall snapshot (`docs/OFFLINE_KNOWLEDGE.md`) is shown, labeled "saved data from <date>, which may be out of date". An empty snapshot is **not** shown as "no recalls" (the model name may simply not match); the normal "couldn't check" message appears instead.

## 6. Privacy
- **Camera:** frames are analyzed in memory and closed immediately. No picture or video is saved, cached or sent anywhere. The camera is on only while the scan screen is open and is unbound when it closes (which also turns the flashlight off).
- **VIN:** sent only to NHTSA vPIC (to decode), **in the request body (POST), never in the URL**, so it isn't in anyone's access logs as part of an address (the response doesn't echo it either). **Never** sent to the recalls API (only year, make, model), **never** to Gemini, **never** logged (the connection log records the result category and the count), and **never spoken in full**: voice guidance says only "a VIN ending in 0 0 0 1". It isn't put in crash reports.
- VIN processing is entirely independent of Gemini; no AI call is made.

## 7. Voice and accessibility
- Spoken guidance goes through the existing speech system (`AppContainer.speakGuidance`). It's started by the user ("Read this to me", or the voice-guidance toggle on the scan screen) and is skipped while a voice conversation is active so the two never talk over each other.
- TalkBack: the guide's drawings are decorative (text carries all the information); live regions announce scan progress, warnings, recall loading and results; every control is at least 48 dp; the flashlight and voice toggles describe their state; recall cards announce "Details shown / hidden".

## 8. Tests
`VinScanningTest` (extraction, I/O/Q, check digit, tracker, geometry), `RecallsTest` (parser, errors, fallback, wording, links, log privacy), `VinFlowViewModelTest` (decode, duplicates, offline, recall outage, never-auto-select, voice never speaks the full VIN), `VinFlowUiTest` (Robolectric Compose: guide, scanner states, permission states, results, recall wording, duplicate banner).

## 9. Not covered by automated tests: check on the phone
See `docs/DEVICE_TESTING.md` §1: real camera preview and focus, recognition on a real windshield/door sticker/paper, highlight alignment, flashlight, permission prompt and settings round-trip, CameraX/ML Kit performance and battery, TalkBack, spoken guidance, and the NHTSA calls over real mobile data.
