# Firebase AI Logic setup (Gemini for Penelope)

Penelope uses Google's Gemini through **Firebase AI Logic** (Gemini Developer API). The Gemini
API key stays inside your Firebase project; the app never contains it. Requests are protected
by **Firebase App Check** using single-use tokens.

Without Firebase configuration the app still builds and works: Penelope uses her built-in answers
and Settings shows "Not set up".

## 1. `google-services.json` (never commit it)
`.gitignore` excludes `google-services.json` and `app/google-services.json`.

- **Android Studio (local):** copy your downloaded file to `app/google-services.json`, then sync
  Gradle. The build applies the Google Services plugin only when this file exists.
- **GitHub Actions:** repository **Settings → Secrets and variables → Actions → New repository
  secret**, name `GOOGLE_SERVICES_JSON`, value = the full file contents. CI writes it to
  `app/google-services.json` before building (the contents are never printed). If the secret is
  missing, CI still builds an APK without Gemini and says so in the log.

The Firebase API key inside this file identifies your project; it isn't your Gemini key. For
extra protection, restrict it in Google Cloud Console → APIs & Services → Credentials to your
Android app (package `com.aicarcompanion.app` + SHA-1) and the Firebase APIs you use.

## 2. Firebase AI Logic
Firebase console → AI Services → AI Logic: **Gemini Developer API** enabled (done). Keep App
Check enforcement **on**; the app is built for it. Don't disable enforcement to work around
setup problems.

## 3. App Check
| Build | Provider | Where it's defined |
|---|---|---|
| Debug (Android Studio, CI artifacts) | Debug provider | `app/src/debug/.../AppCheckInstaller.kt` |
| Release (Google Play) | Play Integrity | `app/src/release/.../AppCheckInstaller.kt` |

The debug provider library is a `debugImplementation` dependency, so it can't be in a release APK.

### Register your debug token (private, per phone install)
1. Install and open a **debug** build that includes `google-services.json`.
2. Ask Penelope anything conversational (e.g. "Is premium fuel really necessary?").
3. Find the token in Logcat:
   - **Android Studio:** Logcat tab → select your phone and the `com.aicarcompanion.app` process →
     filter: `tag:DebugAppCheckProvider`.
   - **Windows terminal:** `adb logcat -s DebugAppCheckProvider`
   - The line reads: *"Enter this debug secret into the allow list in the Firebase Console for
     your project: xxxxxxxx-xxxx-…"*.
4. Firebase console → **App Check → Apps → AI Car Companion Android → ⋮ → Manage debug tokens →
   Add debug token**. Name it (e.g. "Galaxy S26 Ultra debug") and paste the value **there only**.
5. Ask Penelope again. Settings → Smarter answers should show **Working (Gemini, …)**.

Treat the debug token like a password: anyone with it can call your AI quota. Never commit it,
paste it in chats, issues or screenshots. If exposed, delete it in the console. The token
belongs to one installation; uninstalling the app (including installing a CI APK, which is
signed with a different key) creates a new one that must be registered again.

### Play Integrity ("An error occurred when accepting terms")
Required only for Google Play builds. Things to try:
1. Google Cloud Console (same project) → **APIs & Services → Library → Play Integrity API →
   Enable**, accepting any terms there, then retry in Firebase.
2. Make sure the signed-in account is an **Owner** of the project (IAM), and that only that
   account is active in the browser.
3. Allow third-party cookies for `firebase.google.com` and `console.cloud.google.com`.
4. For launch, create the app in Play Console, use Play App Signing, add the **app signing key
   SHA-256** in Firebase project settings, and link the Cloud project under Play Console → App
   integrity → Play Integrity API.

Until this works, release builds can't pass App Check, so they fall back to built-in answers.

## 4. Model
- Default: **`gemini-3.8-flash`** (stable). Fallback if unavailable: `gemini-3.5-flash-lite`.
- Change without an app update: Firebase **Remote Config** parameter `penelope_model` (string).
  The app accepts only stable Flash names (`gemini-<version>-flash` or `-flash-lite`), never
  preview, `-latest` or retired models; anything else falls back to the default.
- Check model status before launch: https://firebase.google.com/docs/ai-logic/models

## 5. Data and billing
What's sent to Gemini and what never is: see Settings → Smarter answers → "What is sent to
Google?" (also `PromptBuilder.kt`). Under Google's Gemini API terms, on the **free (Spark)**
tier Google may use prompts and responses to improve its products and humans may review them;
on a **paid (Blaze)** project they aren't used for that. Recommended before public launch: move
to Blaze with a **budget alert**, and describe this in the Play **Data safety** form and privacy
policy.

## 6. What the status messages mean
| Settings status | Meaning / fix |
|---|---|
| Not set up | Build has no `google-services.json` (local file or CI secret). |
| On / Working (Gemini, model) | Configured / last request succeeded. |
| Unavailable: not verified with App Check | Register the debug token (debug) or finish Play Integrity (release). |
| Unavailable: Firebase AI Logic isn't enabled | Enable AI Logic / Gemini Developer API in the console. |
| Unavailable: usage limit reached | Free-tier quota; wait or move to Blaze. |
| Unavailable: no internet / took too long | Network; Penelope uses built-in answers meanwhile. |
| Off | You turned it off in Settings. |


For how CI uses the `GOOGLE_SERVICES_JSON` secret, see `docs/CI.md`.
