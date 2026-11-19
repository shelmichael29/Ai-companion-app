# Offline knowledge pack

Penelope's bundled, read-only reference data: trouble codes, EPA vehicles, NHTSA recall campaigns and crash ratings, and symptom triage guides. Built from the owner's research package (`tools/offline-knowledge/`, researched 2026-11-18).

**Status:** 🟢 built and tested with the real data on the JVM. Not yet checked on the phone.

## 1. What was downloaded (2026-11-19)
All 12 core and extended sources downloaded successfully. Each file's SHA-256 is in `app/src/main/assets/knowledge/knowledge_manifest.json`.

| Source | Downloaded | License (as stated by the source) | In the app? |
|---|---|---|---|
| OBDex generic codes | 31.7 MB JSON, 9,533 codes | CC0 data, MIT code | **Yes**: English title, explanation, causes, symptoms, flags |
| OBDex PIDs (modes 01, 09) | 132 PIDs | CC0 | **Yes**: names and units, reference only (no feature uses them yet) |
| Wal33D dtc-database | 3.3 MB SQLite, 18,805 rows | MIT | **Yes**: 6,368 manufacturer-specific meanings for 31 makes. Generic rows duplicate OBDex; the 2,620 "OTHER" rows have no make and "GM" isn't a make in the vehicle list, so both are skipped. |
| EPA vehicles (FuelEconomy.gov) | 2.2 MB zip, 50,647 vehicles | U.S. government data | **Yes**: 35,869 vehicles, model years 1996+ |
| NHTSA recalls, pre-2010 and post-2010 | 22 MB zip, 398 MB text | Federal publication | **Yes**: 20,128 vehicle campaigns, model years 1996+, clipped summaries, with 130,007 year/make/model links. Malformed rows are rejected, and the build fails if more than 1% are. |
| NHTSA crash ratings (NCAP) | 9.6 MB CSV | Federal publication | **Yes**: 8,432 rated vehicles |
| Symptom guides | 24 guides | Project original | **Yes** |
| EPA emissions | 0.3 MB zip | U.S. government data | No: smog scores don't help diagnose a car |
| NHTSA service bulletins (2025–26) | 13 MB zip, **431 MB** text | Index public; bulletins themselves are manufacturers' copyrighted documents | **No**: too big for a phone and only an index. Needs a server |
| NHTSA manufacturer communications (2025–26) | 5.8 MB zip, 93 MB CSV | Same as bulletins | **No**: same reasons |
| NHTSA defect investigations | 4.3 MB zip, **391 MB** text | Federal publication | **No**: too big; investigations aren't confirmed defects |
| NHTSA owner complaints (heavy tier) | not downloaded (360 MB zip) | Unverified owner reports with privacy risk | **No**: the package itself restricts it to a server after a privacy review |

**In the app:** `assets/knowledge/penelope_knowledge.db` is about 10 MB in the APK. The first launch unpacks it to about 41 MB of app-private storage, excluded from backups.

## 2. How Penelope uses it
- **Trouble codes** (`KnowledgeDtcSource`), in this order:
  1. Penelope's own beginner text, for the codes she already explains;
  2. the **selected car's make** for manufacturer-specific codes (P1xxx, P30–P33xx, B/C/U 1xxx–2xxx);
  3. the OBDex catalog.

  Answers from the catalog say where they came from ("OBDex community catalog") and that a code is a clue, not a diagnosis.
  - **Severity:** community data **never** says a code is minor. Limp mode or a safety system (airbags, brakes, steering, oil pressure, overheating, fuel leaks, stability control) → "Get it checked promptly"; everything else → "Not sure how serious". Only Penelope's own reviewed text uses milder levels.
  - **Manufacturer-range codes** use only the selected car's make. With no car selected, or no entry for that make, there's no answer rather than a guess.
- **Gemini** gets the meaning labeled "reference meaning (community data)", never "verified", so "What does P2096 mean?" is answered from the catalog, not from memory.
- **Recalls** ("any recalls on my car?"): a **built-in answer**. Gemini isn't involved, because the reply guard can't check campaign numbers or star counts.
  - The answer always says these cover the **model, not necessarily this car**, and to check the VIN at **nhtsa.gov/recalls**.
  - The **exact** model name is matched first. A longer name is used only when there's just one ("F-150"), so a Mustang never gets the Mustang Mach-E's recalls.
  - With no match, Penelope says that doesn't mean there are none.
  - The 2011 BMW 528i has 4 campaigns on record.
- **Crash ratings** ("what's my car's crash test rating?"): a built-in answer with NCAP stars by year, make and model. When versions differ, Penelope gives the range and asks which one you have. The 2011 528i sedan: 5 stars overall.
- **Symptom guides:** for a described symptom, Gemini gets the matching guide's questions and systems to consider, as possibilities to ask about, never a diagnosis. Offline, the built-in answer asks the guide's first question when it has no question of its own.
- **Adding a car offline:** the vehicle list uses FuelEconomy.gov online first. When that can't be reached, the offline EPA copy answers with the **same IDs**, so a car added offline matches one added online.

## 3. What it never does
- **Safety answers** (emergencies, "can I still drive?") stay built-in and never depend on this data.
- **Gemini stays the primary intelligence** when online. This data grounds answers and backs up when there's no connection.
- **Nothing is uploaded, written or logged.** Lookups use only a code or a year, make and model.
- **It's not proof of a specific car's condition:** codes are clues, recalls are campaigns for a model, and crash ratings are per model.

## 4. Rebuilding or updating
On a computer with internet, in `tools/offline-knowledge/`:
```
python3 scripts/download_sources.py --tier extended     # never --tier heavy for the app
python3 scripts/build_app_pack.py --out ../../app/src/main/assets/knowledge
python3 -m unittest discover -s tests
```
Then run the quality gate. The downloads are never committed (`.gitignore`). The manifest records every input's SHA-256, the row counts and the build date. A changed pack installs automatically on the next launch, because the file name includes its checksum. The old pack is removed only after the new one is verified, and a failed install leaves no partial file and is retried on the next launch.

## 5. Still to do
- **Check on the phone:** first-launch install time, storage use, answers to "any recalls?" and "what does P2096 mean?", and adding a car in airplane mode.
- **Review sample accuracy:** about 100 random OBDex codes and the BMW-specific Wal33D meanings, before relying on them for anything beyond explanations.
- **Production licensing:** the EPA and NHTSA terms are stated by the sources but not legally reviewed.
- **Server-side only:** service bulletins, investigations, manufacturer communications and complaints.
- **Not done:** VIN-specific recall status, which needs the online NHTSA API.
