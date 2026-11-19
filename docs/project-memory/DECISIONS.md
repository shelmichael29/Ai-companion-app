# Decisions

Approved product and technical decisions. Dates are when the change merged into `main` (UTC, from git history), unless noted. Newest first. Superseded decisions are kept and marked.

| Date | Decision | Source |
|---|---|---|
| 2026-11-19 | Model delegation: Haiku for lookups, Sonnet for implementation, Opus for planning and reviews; the Opus agent is read-only | PR #35 |
| 2026-11-18 | Hands-free commands are routed before the AI; the AI never sees contact names or message text; messages and calls will need spoken confirmation; hands-free core stays free of Android imports for iOS | PR #34, `docs/HANDS_FREE.md` |
| 2026-11-17 | Optional fixed CI debug signing key via a repository secret | PR #33 |
| 2026-11-17 | Upgrade to AGP 9; **keep `targetSdk` 35** until a device-tested stage | PR #32 |
| 2026-11-17 | Dependabot must not propose major AGP upgrades automatically | PR #26 |
| 2026-11-16 | Exact Everyday connection messages; an adapter is remembered only after it really connects; bounded retries; no simulation mode | PR #25 |
| 2026-11-16 | "Vehicle connected" only after a real ECU answer; read-only recovery; AI notices never go into Gemini's history | PR #24 |
| 2026-11-16 | **ENET tester moved out of `main`.** It lives only on `experiment/*` branches and isn't part of Penelope unless the owner says so. Supersedes the 2026-11-16 merge of PR #10 into main | PR #17, `CLAUDE.md` |
| 2026-11-16 | Quality gate required at the end of every stage; never weaken tests or analysis; report NOT RUN honestly | PR #16, `CLAUDE.md` |
| 2026-11-16 | Everyday Driver dashboard; **Midnight** is the default color style | PR #15 |
| 2026-11-16 | Knowledge level and home layout are independent settings; Car Health and Extras can't be hidden | PR #13, `CLAUDE.md` |
| 2026-11-15 | Gemini only via Firebase AI Logic with App Check enforced; never embed the API key; fall back safely when configuration is missing | PR #5 |
| 2026-11-15 | OBD-II settings by skill level: Everyday automatic, DIY manual, Advanced full control, all read-only | PR #6 |
| Owner rules | Never merge without the owner's approval; one PR per stage from the latest `main` | `CLAUDE.md` |

## ❓ Waiting for the owner
| Raised | Question | Options |
|---|---|---|
| 2026-11-16 | Gemini and personal details: should symptoms and service titles be scrubbed before they're sent to Gemini? (code scanning, PR #24) | 1) keep as is; 2) automatic scrubbing (recommended); 3) opt-in; 4) both |
