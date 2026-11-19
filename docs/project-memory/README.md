# Penelope project memory

Durable project knowledge, so future sessions don't need things re-explained. It sits between the rules in `CLAUDE.md` and the detailed technical docs in `docs/`.

## Load only what the task needs
| Working on… | Read |
|---|---|
| What to build next, priorities | [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md), [ROADMAP.md](ROADMAP.md) |
| Whether something exists already | [FEATURES.md](FEATURES.md) |
| Code structure or a technical choice | [ARCHITECTURE.md](ARCHITECTURE.md), [DECISIONS.md](DECISIONS.md) |
| Screens, wording, voice behavior | [DESIGN_PREFERENCES.md](DESIGN_PREFERENCES.md) |
| OBD, adapters, the test car | [VEHICLE_KNOWLEDGE.md](VEHICLE_KNOWLEDGE.md) |
| Product direction, target users | [PROJECT_VISION.md](PROJECT_VISION.md) |
| Bringing in outside memory (e.g. ChatGPT) | [IMPORT_REVIEW.md](IMPORT_REVIEW.md) |

Don't read the whole folder for a narrow task. Each file is short, and each links to the authoritative doc for details.

## Status labels
| Label | Meaning |
|---|---|
| ✅ Verified | In `main`, automated tests pass, **and** checked on the phone or car |
| 🟢 Built | In `main` with automated tests; not yet checked on a real device |
| 🔵 In progress | Open PR or active branch |
| 📋 Proposed | Planned, not built |
| ⏸ Deferred | Deliberately postponed |
| ❓ Undecided | Waiting for the owner's decision |

A plan is never a feature: nothing is 🟢 or ✅ without code in `main`.

## Source of truth (highest first)
1. **The code in `main`.**
2. **Authoritative docs:** `CLAUDE.md` (rules), `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, `docs/HANDS_FREE.md`, `docs/BLUETOOTH_CONNECTION.md`, `docs/CONNECTION_RECOVERY.md`, `docs/CI.md`, `docs/DEVELOPMENT_QUALITY_GATES.md`, `docs/QUALITY_SUMMARY.md`, `docs/DEVICE_TESTING.md`.
3. **This folder** (summaries and decisions).
4. **Outside memory** (e.g. ChatGPT): never authoritative until reviewed in `IMPORT_REVIEW.md` and approved by the owner.

If a memory file disagrees with the code or an authoritative doc, fix the memory file in the same PR.

## Rules for this folder
- **No private information:** no names, family, finances, contact details, VINs, addresses, passwords, keys, tokens, or contents of `google-services.json` or keystores.
- **Keep it current:** update the relevant file in the same PR as the change it describes. Change the status; don't silently delete history.
