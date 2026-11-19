# Penelope / AI Car Companion — Offline Knowledge Research Package

**Research date:** 2026-11-18  
**Status:** Usable *starter* offline SQLite database + vetted acquisition manifest + import tools + Claude handoff. This is **not** a production-ready, comprehensive car diagnostics database.

## What this package actually contains today

- `output/penelope_offline.sqlite`: an **offline SQLite starter** with **24 original automotive symptom-triage guides**, local search indexes and source provenance. It contains **zero upstream OBDex/EPA/NHTSA/Wal33D rows** until downloaded/imported on a networked computer.
- `data/sources.json`: **13 researched download targets** and associated licenses, cautions, priorities and references.
- `scripts/download_sources.py`: bandwidth-conscious, integrity-checked downloader (opt-in, no secrets, no dependencies).
- `scripts/build_database.py`: validated import for OBDex generic DTCs and PIDs + EPA vehicle data. **NHTSA, Wal33D and EPA emissions are staged, not blindly imported.**
- `data/symptom_guides.json`: a foundation for better offline questions, urgency warnings, and what available readings would help.
- `tests/test_offline_package.py`: automated tests that exercise database creation and **synthetic sample imports**, but cannot validate real upstream datasets until they are downloaded.
- `docs/RESEARCH_AUDIT.md`: sources, quality, licensing, prioritization and caveats.
- `docs/CLAUDE_HANDOFF.md`: concise integration instructions to avoid repeating the research in Claude.

**Important constraint:** This session's execution environment could view authoritative source pages but could not fetch the external binary/JSON datasets into the file workspace. The live dataset download therefore remains a **one-time local laptop step when Wi-Fi is available**. The source registry does **not** imply those remote files are already inside the ZIP. Do not tell users 9,533 records are installed yet.

## Windows laptop quick start (after downloading ZIP)

Open PowerShell in the unzipped `penelope_offline_knowledge` folder:

```powershell
py -3 scripts/download_sources.py --tier core
py -3 scripts/build_database.py
py -3 scripts/query_demo.py "engine"
py -3 -m unittest discover -s tests -v
```

`--tier core` attempts seven downloads: OBDex generic codes, two OBDex PID sets, Wal33D DTC SQLite, EPA vehicle and emissions CSV files, NHTSA post-2010 recall snapshot. Some remote URLs or upstream schemas may change. Failures are **logged**, never hidden. If a download fails, refer to the source's official landing page in `data/sources.json` before changing it. The build command still produces a useful offline starter if optional sources are missing.

Add selected smaller NHTSA metadata:

```powershell
py -3 scripts/download_sources.py --tier extended
```

Full national complaints data is massive, user-reported, and unsuitable as a direct diagnostic knowledge base. **Don't use `--tier heavy` unless a backend ingestion and privacy review are planned.**

If mobile downloading is troublesome, use the laptop browser for this ZIP and hand the *unpacked folder* to Claude Code later. The scripts are optional to run before the Gemini routing fix; Gemini remains the highest-priority runtime task.

## Database relationships (implemented)

`generic_dtc` has a row per generic code with title, English explanation, reviewed date, full raw JSON, and source ID.  
`obd_pid` stores raw OBDex PID specification JSON **not yet executable formula logic**.  
`epa_vehicle` stores year/make/model/drive/engine and MPG figures for EPA-listed variants.  
`symptom_guide` stores urgent warnings, clarifying questions, candidate **systems** (not a claim that a specific part failed), and helpful evidence.  
`source_provenance` tracks dataset identities and imported record counts; FTS5 supports local search.

## Recommended integration order

1. Import the **project memory file** (separate task).
2. Resolve **Gemini-first routing and explicit timeouts/fallback errors** (separate PR), then phone test.
3. Download and run the offline knowledge scripts **without putting giant zip files into Git**.
4. Run source audits, confirm every download count and schema, compare to app's existing tables, and import **only approved, versioned** data into Android Room/SQLite.
5. Keep a larger backend for bulk VIN/recall/complaint/TSB research; serve curated, versioned, opt-in vehicle-specific packs to phones.

## Privacy, correctness and safety requirements

- Treat DTCs as clues; don't conclude that a component is definitely defective from code alone. Don't invent OBD readings or pretend VIN identification resolves trim/engine exactly.
- Preserve safety warnings locally even with internet. Hazardous conditions (oil pressure, overheating, braking) should never depend on Gemini availability.
- Do not treat complaints as proven diagnoses, TSBs as recalls, or recall campaigns as proof a **specific VIN** has an unresolved recall. When connected, verify on the official VIN-specific recall lookup; offline disclose last checked date.
- Distribute only content with reviewed rights. Do not scrape paywalled OEM manuals or reproduce copyrighted SAE/ISO standard text or manufacturer service manuals.
- Record source URL, license, downloaded hash/date, applicable manufacturer/model/engine/ECU/build range, confidence, last-reviewed time and conflicts.
- Store sensitive VIN, locations, raw diagnostic logs and account identifiers locally by default and request consent before upload.
- Make all vehicle-data statements provenance-aware; when no data exists, say so instead of fabricating an answer.

This starter is designed to avoid Claude research token expenditure, not to eliminate the need for careful integration testing.
