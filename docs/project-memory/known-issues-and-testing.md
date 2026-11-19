# Known issues, testing and open items

## Testing rules (from `CLAUDE.md` and `docs/DEVELOPMENT_QUALITY_GATES.md`)
- Run `scripts/quality-gate.sh "<stage>"` at the end of every stage (`--release` before releases).
- Never skip, `@Ignore` or weaken tests; never loosen Lint or Detekt or add baselines to get green.
- Every bug fix gets a regression test that failed before the fix.
- Report anything not run as **NOT RUN**. Compiling is not testing.
- Fakes only (`FakeElmAdapter`, fake speech, fake AI); never a real car, phone call or message.

## Open items
| Item | Status |
|---|---|
| Gemini and personal details (scrub before sending?) | ❓ Owner's decision pending |
| `targetSdk` still 35 (one known Lint warning) | 📋 Own stage with device testing |
| App Check debug token must be registered for each new signing key | 🟡 Add the `DEBUG_KEYSTORE_BASE64` secret to make it a one-time step |
| "Fresh" tag on the merge after PR #22 | 📋 Owner creates it on the GitHub releases page (sessions can't push tags) |
| Voice barge-in (interrupt Penelope by speaking) | 📋 Needs echo cancellation; tap to interrupt works |
| Car hands-free microphone (SCO) | 📋 Needs real-car testing |

## Device tests still pending (none run yet)
- Gemini on the phone (App Check token), Crashlytics test crash.
- Everyday dashboard, voice and TalkBack on the S26 Ultra.
- After the AGP 9 upgrade: every screen, especially the vehicle edit form.
- Bluetooth adapter checklist: `docs/BLUETOOTH_CONNECTION.md` §5.
- Connection recovery on the 528i and voice auto-resume after internet loss: `docs/CONNECTION_RECOVERY.md` §4.
- Hands-free foundation log entries and replies: `docs/HANDS_FREE.md` §4.

Check off items here, and in `docs/QUALITY_SUMMARY.md`, as they're verified.
