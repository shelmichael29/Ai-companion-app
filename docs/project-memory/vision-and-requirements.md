# Vision and requirements

## Vision
**Penelope** ("AI Car Companion") is a voice-first assistant for drivers. It helps people with little or no car knowledge understand and look after their car, and is growing into a complete hands-free driving companion: diagnostics, music, messages, calls and, later, navigation.

## Who it's for
- **Everyday drivers** first: plain language, no jargon, one-tap actions.
- **DIY** and **Advanced** users get more detail and controls through the same app.
- **Knowledge level** (Everyday / DIY / Advanced) and **home layout** (Easy / Customizable) are independent settings.

## Non-negotiable requirements
| Requirement | Status |
|---|---|
| Two-way, continuous voice conversation (no placeholders) | 🟡 Built, not verified on the phone in the car |
| Never present fake data as real; sample data is always labeled | ✅ Built |
| Read-only vehicle access: no clearing codes, resets, coding or actuators from automatic features or tests | ✅ Built (`ReadOnlyCommandPolicy`) |
| Plain, honest answers: say "I don't know" or "not available" rather than guess | ✅ Built |
| Privacy: no transcripts, VINs, contacts or keys in logs, crash reports or the AI | ✅ Built (see `docs/HANDS_FREE.md`, `docs/CONNECTION_RECOVERY.md`) |
| Gemini only via Firebase AI Logic with App Check enforced; no API key in the app | 🟡 Built, not verified on the phone |
| Driver safety: voice first; messages and calls need spoken confirmation | 📋 Planned for hands-free phases (rules in `HandsFreeSafety`) |

## Feature areas
| Area | Status | Canonical doc |
|---|---|---|
| Vehicle profiles, VIN decode, catalog lookup | ✅ Built | `ARCHITECTURE.md` |
| Penelope voice (continuous conversation, voice settings) | 🟡 Built, not verified | `ARCHITECTURE.md` |
| Gemini answers with rule-based fallback | 🟡 Built, not verified | `ARCHITECTURE.md`, `CONNECTION_RECOVERY.md` |
| OBD-II over Bluetooth Classic ELM327 | 🟡 Built, not verified with the car | `BLUETOOTH_CONNECTION.md` |
| Everyday Driver dashboard | 🟡 Built, not verified | `ARCHITECTURE.md` |
| DIY and Advanced dashboards | 📋 Planned | `ARCHITECTURE.md` (A3, A4) |
| Hands-free music, messages, calls | 📋 Planned (foundation built) | `HANDS_FREE.md` |
| Navigation | 📋 Planned | `HANDS_FREE.md` |
| iOS app | 📋 Planned | [platforms-android-ios.md](platforms-android-ios.md) |
| Mechanic handoff report, predictive maintenance | 📋 Planned | `ROADMAP.md` |
| Clear trouble codes | 📋 Planned as a separate, explicit feature, never automatic | `CONNECTION_RECOVERY.md` |
