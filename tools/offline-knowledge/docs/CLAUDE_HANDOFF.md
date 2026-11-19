# Paste into Claude Code — Penelope offline knowledge handoff

You are receiving an *externally researched*, source-linked offline automotive knowledge package for the AI Car Companion project. It contains a validated 24-guide SQLite starter library, research audit, source registry, local downloader, compiler and tests. **It does not yet contain downloaded OBDex, EPA, NHTSA or manufacturer datasets.** Do not spend tokens rediscovering the referenced sources.

Follow the project sequencing: memory integration first; Gemini-first routing and explicit error/timeout behavior second; offline knowledge database work third and **separate** from Gemini work. Do not modify or merge any existing PR without approval.

When this offline knowledge task begins:

1. Inspect this package and the existing app's database/fallback interfaces; **do not replace working code or duplicate existing schema**.
2. Run `py -3 -m unittest discover -s tests -v`, `py -3 scripts/download_sources.py --tier core`, and `py -3 scripts/build_database.py` in the package folder on a networked laptop; report failed downloads or changed upstream formats. Do not assert imported row counts without querying built SQLite.
3. Audit licenses and sample accuracy. Review manufacturer-specific DTC conflicts. Keep NHTSA reports as metadata/history with current VIN-dependent checks separate.
4. Propose a compact read-only Room/SQLite integration with source/version/confidence and vehicle/ECU scoping. No unverified data used for safety decisions.
5. Preserve **Gemini primary whenever online/available**; the offline library is used for real network loss (or explicit, truthful service-failure UX), not a silent canned alternative to normal Gemini conversation. Preserve local emergency safety handling.
6. Keep raw national archives off the phone, and do not commit bulk downloads or private vehicle logs to Git. Suggest optional vehicle-specific update packs later.
7. Show intended diffs and test results; use a **separate PR** for offline knowledge. No merge/commit/push without the owner's approval.

Deliver a brief report: actual per-source downloaded row counts, failed/changed sources, licensing issues, database size, query performance, Gemini-versus-offline routing behavior, and remaining work. Avoid lengthy restatement of the research audit.
