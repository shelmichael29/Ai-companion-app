# Platforms: Android now, iOS later

## Android (current)
- **Owner's test device:** Samsung Galaxy S26 Ultra.
- **App:** `com.aicarcompanion.app`, minSdk 26, targetSdk 35, compileSdk 37. Version 0.7.0.
- **Builds:** CI produces a debug APK per run (artifact `ai-car-companion-<version>-debug-<sha>`), plus an unsigned release APK.
- **Debug signing:** an optional `DEBUG_KEYSTORE_BASE64` repository secret keeps the same key across CI builds, so the App Check debug token stays valid (`docs/CI.md`). Status: 🟡 built; the owner still has to add the secret.
- **Firebase:** `google-services.json` comes from the `GOOGLE_SERVICES_JSON` secret in CI and is never committed. App Check debug tokens are registered in the Firebase console and never shared.
- **Android Studio:** work on `main`; AGP 9.5 needs a recent Android Studio.
- **Permissions in use:** microphone, Bluetooth connect/scan (scan is "never for location"; location only on Android 8–11), network state, internet. Hands-free features will each need their own (`docs/HANDS_FREE.md` §3).

## iOS (📋 planned)
- **A native iOS app**, not a port. Keep platform-neutral logic free of Android imports (`handsfree/` is test-enforced).
- **Capabilities differ:** for example, iOS doesn't let third-party apps read messages or control other apps' music; sending a message or placing a call needs the user to confirm in system UI. See `docs/HANDS_FREE.md` §3.
- **Not started:** no iOS code, project or decisions on language or UI framework yet.
