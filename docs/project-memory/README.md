# Penelope project memory

Durable project knowledge for future development sessions. It sits between the rules in `CLAUDE.md` and the detailed technical docs in `docs/`.

## How to read it
Every fact has a **status**, so plans are never mistaken for finished features:

| Status | Meaning |
|---|---|
| ✅ Built | In `main`, with automated tests |
| 🟡 Built, not verified | In `main` and tested with fakes, but not yet checked on the phone, adapter or car |
| 📋 Planned | Agreed direction, not built |
| ❓ Undecided | Needs the owner's decision |

**Source of truth:** the code and the canonical docs listed in each file. If a memory file disagrees with the code, the code wins: fix the memory file in the same PR.

## Files
| File | Covers |
|---|---|
| [vision-and-requirements.md](vision-and-requirements.md) | What Penelope is, who it's for, non-negotiable requirements |
| [architecture-decisions.md](architecture-decisions.md) | Key technical decisions and why |
| [ui-ux-preferences.md](ui-ux-preferences.md) | Visual style, voice behavior, accessibility, wording |
| [platforms-android-ios.md](platforms-android-ios.md) | Android setup, test device, future iOS |
| [vehicle-diagnostics-obd.md](vehicle-diagnostics-obd.md) | OBD-II, adapters, the test car, safety limits |
| [milestones-and-status.md](milestones-and-status.md) | What's done, what's next |
| [known-issues-and-testing.md](known-issues-and-testing.md) | Open bugs and decisions, testing rules, pending device tests |
| [import-review.md](import-review.md) | Review of imported outside memory (e.g. ChatGPT) before it's accepted |

## Rules for this folder
- **No personal data:** no names, emails, phone numbers, VINs, addresses, keys, tokens, or contents of `google-services.json` or keystores.
- **Outside memory goes through review:** knowledge from outside the repo (another AI's memory, chats) is added only after it's checked against the repository in `import-review.md` and approved by the owner.
- **Keep it current:** update the relevant file in the same PR as the change it describes. Mark status changes; don't delete history unless it's wrong.
