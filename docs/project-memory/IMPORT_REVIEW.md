# Imported memory review

Knowledge from outside the repository, such as ChatGPT memory summaries or exported chats, is checked here **before** it goes into any other file. Claude can't read ChatGPT directly; the owner pastes or uploads it.

## Process
1. **Filter first:** drop passwords, keys, tokens, private family information, financial records and anything unrelated to Penelope. They're never written anywhere.
2. **Extract:** list each project-relevant statement, one per row.
3. **Compare** each statement with the code and the authoritative docs, and give it a verdict:
   - **Duplicate:** already recorded. Nothing to add.
   - **Confirmed:** the repo proves it, but it isn't written down yet. Proposed for a file with status 🟢.
   - **Proposed idea:** not in the repo. Proposed with status 📋 or ❓, **never 🟢 or ✅ without code**.
   - **Conflict:** differs from the repo. Both versions are shown and **the owner decides**.
   - **Outdated:** superseded by later work. Not imported; noted why.
4. **Approve:** the owner approves item by item. Approved items are written to their destination file and committed. Rejected items are listed below with the reason.

## Current review
**ChatGPT memory:** not provided yet.

**Offline knowledge research package** (zip uploaded 2026-11-19; documents read, nothing run or downloaded):

| # | Statement (summarized) | Verdict | Repo evidence | Destination | Owner decision |
|---|---|---|---|---|---|
| 1 | The 2011 528i test car has the N52N engine | Conflict (the repo said N52) | `CLAUDE.md`, `VEHICLE_KNOWLEDGE.md` | `CLAUDE.md`, `VEHICLE_KNOWLEDGE.md` | **Approved 2026-11-19: N52N** |
| 2 | Offline knowledge database (24 symptom guides, 13 researched sources; upstream data not downloaded) | Proposed idea | none | `FEATURES.md`, `ROADMAP.md` | Pending |
| 3 | Don't run the database download scripts yet | Owner instruction | chat, 2026-11-19 | `DECISIONS.md` | Pending |
| 4 | License cautions (OBDb CC BY-SA, manufacturer bulletins, NHTSA complaints aren't diagnoses) | New | none | `DECISIONS.md` or a new offline-knowledge doc | Pending |

## Rejected or outdated items
None yet.
