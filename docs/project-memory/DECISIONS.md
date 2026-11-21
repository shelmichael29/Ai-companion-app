# Decisions

Approved product and technical decisions. Dates are when the change merged into `main` (UTC, from git history), unless noted. Newest first. Superseded decisions are kept and marked.

| Date | Decision | Source |
|---|---|---|
| 2026-11-21 | **Recall accuracy:** one `RecallService` for every car and for Penelope's answers. A failed or unreadable check is always "couldn't check", never "no recalls". Every result shows its source and when it was checked. Model-level campaigns and VIN-specific status are separate; only an authoritative VIN source may say a recall is open for a VIN (none exists: NHTSA has no public recalls-by-VIN API). Repair eligibility (age, first retail sale, campaign timing, manufacturer policy) is explained but never decided: no mileage, date or VIN input to recall checks | This PR, `docs/VIN_SCANNER.md` §5 |
| 2026-11-20 | **VIN scanner and recalls:** on-device ML Kit (bundled) + CameraX, never saves images; a scanned VIN is never selected without the user's confirmation; the VIN goes only to NHTSA vPIC (never to the recalls API, Gemini, logs or crash reports; voice says the last four digits only). Recalls are searched by year/make/model and are **never labeled VIN-confirmed or outstanding**; the VIN-specific check is a link (NHTSA, plus Toyota/Lexus, Volkswagen, Mazda pages confirmed reachable). Offline falls back to the saved recall snapshot, labeled. The VIN is sent to vPIC by POST body, never in a URL. Manufacturer links stay a short verified list (owner decided, 2026-11-21) | PR #41, `docs/VIN_SCANNER.md` |
| 2026-11-19 | **Offline knowledge pack** ships in the app (codes, EPA vehicles, recall campaigns, crash ratings, symptom guides). Service bulletins, investigations, manufacturer communications and owner complaints stay off the phone (size, copyright, unverified). Recalls are always "for the model"; VIN check at nhtsa.gov/recalls. Safety stays built-in | PR #40, `docs/OFFLINE_KNOWLEDGE.md` |
| 2026-11-19 | **Gemini is the primary intelligence.** Only safety, conversation controls and connection status stay built-in; data questions are read by the app first, then explained by Gemini; fallbacks are always announced; every answer's source is logged; timeout 12 s via Remote Config (`penelope_ai_timeout_ms`) | PR #37, `docs/GEMINI_ROUTING.md` |
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
