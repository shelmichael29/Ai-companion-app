# Penelope offline automotive data — source and licensing audit

Reviewed **2026-11-18**. This list distinguishes **verified public claims on official/project pages** from **downloads or imports actually performed in this package**. All imports from remote datasets are still pending network access; nothing from those datasets is claimed as loaded today.

## High-impact sources researched

| Source | Verified scope / access | License and reliability | Proposed location |
|---|---|---|---|
| **OBDex** ([project](https://github.com/foerbsnavi/obdex)) | Project reports 9,533 generic SAE J2012 code records, 132 generic Mode 01/09 PIDs, English/German narrative, possible causes, symptoms, review date and source links. Downloads at `foerbsnavi.github.io/OBDex/`. | **CC0 data**, MIT software. Strongly structured but a relatively new community compilation; independently QA a sample before publishing. It admits lower documentation certainty for some rarely used codes. | On-device searchable DTC/PID foundation. |
| **Wal33D dtc-database** ([project](https://github.com/Wal33D/dtc-database)) | Offline SQLite (about 3.1 MB in project README). Dataset table says **18,805 definition rows**, consisting of **9,415 generic** and **9,390 manufacturer-specific**, across **33 manufacturers**. Project headline also says "28,220+" codes; do **not** assume this is the database row count. | Repository says **MIT**. Check included license, true row count, manufacturer mapping, and potential conflicts before merging. | Curated manufacturer-specific pack, with make/year/ECU qualifiers. |
| **DOE/EPA FuelEconomy.gov** ([downloads](https://www.fueleconomy.gov/feg/download.shtml), [field dictionary](https://www.fueleconomy.gov/feg/ws/index.shtml)) | Bulk vehicle and emissions tables covering **model years 1984–2026** on current download page (as of Nov 4 2026); variants have engine, fuel, drivetrain, consumption and EPA identifiers. | High-authority federal reference. Rights to raw data are generally favorable, but verify redistribution terms before shipping a commercial product. | On-device year/make/model and fuel economy lookup; not a definitive engine/VIN decoder. |
| **NHTSA vPIC** ([API](https://vpic.nhtsa.dot.gov/api/), [standalone DB](https://vpic.nhtsa.dot.gov/downloads/)) | VIN decoding via API; November 2026 local backups include PostgreSQL custom **67 MB** and plain **72.8 MB** archives; vPIC says local standalone products are **VIN-decoding only**, not all API functions. | High-authority manufacturer-reporting reference. Confirm actual year-specific decode coverage. | Server VIN resolver or online API, then cache resolved car profile with confidence. |
| **NHTSA recall campaigns** ([catalog](https://www.nhtsa.gov/nhtsa-datasets-and-apis)) | Downloadable pre- and post-2010 bulk snapshots, plus make/model/year recall API. Post-2010 bulk about **14 MB** compressed on Nov 18 2026. | Authoritative for recall **campaigns**, **not** individual VIN applicability, completion, or latest live status. | On-device *historical* recall hints; refresh online for actual VIN-specific status. |
| **NHTSA owner complaints** ([catalog](https://www.nhtsa.gov/nhtsa-datasets-and-apis)) | `FLAT_CMPL.zip` about **360 MB** compressed as of Nov 18 2026; five-year subsets also available. | Official collection of **unverified user allegations**, not proof of defects or ranked failure rates; privacy risk with narratives. | Backend-only research/aggregation, carefully deduplicated. Not safe as repair answer source without review. |
| **NHTSA manufacturer communications/TSBs** ([catalog](https://www.nhtsa.gov/nhtsa-datasets-and-apis)) | Bulk files by received-date range. Current 2025–2026 manufacturer communications about **5 MB** compressed; TSB index about **12 MB**. | Metadata public; **manufacturer-authored documents may remain copyrighted**. Not exhaustive; TSB applicability requires exact make/model/build. | Backend metadata + links and licensed summaries; optional vehicle packs with approved content. |
| **NHTSA NCAP safety ratings** ([catalog](https://www.nhtsa.gov/nhtsa-datasets-and-apis)) | Official SaferCar CSV about 9 MB. | Authoritative controlled crash tests; **not** a diagnostic data source. | Optional vehicle information tab, not the diagnostic response engine. |
| **OBDb** ([project](https://github.com/OBDb)) | Open, community-maintained per-make/model diagnostic signal definitions. | Much OBDb text is **CC BY-SA 4.0**, meaning attribution and share-alike obligations for modifications to the definitions; verify per-repo license. | Optional vehicle-specific PID/signal packs after legal and ECU validation. |
| **driveforgelabs/vehicle-db** ([project](https://github.com/driveforgelabs/vehicle-db)) | Generic and per-make TSV DTCs, source column, module-address labels; notes manufacturer PIDs and UDS identifiers are *future*, not present features. | Project shows MIT; assess quality/redundancy vs Wal33D/OBDex before adding. | Validation source for manufacturer DTC and module name conflicts. |

## Priority tiers

**Tier 1 — app-sized, useful immediately after audit:** generic code title/explanation/cause/symptom lookup; standardized PID units/formulas (with supported-PID detection and parser validation); original symptom triage prompts; selected EPA vehicle configurations. Focus on sub-second local SQLite lookups and on-device compressed assets.

**Tier 2 — vehicle-specific, opt-in download:** licensed manufacturer code maps, make/model/year/build/ECU qualifiers, validated PID request IDs and formulas, canonical warning and maintenance data if rights permit. Ship an **applicable** pack, not a global unqualified manufacturer's table.

**Tier 3 — backend only:** full VIN decoder backup, full complaint archive, broad TSB communications, heavyweight recall history and analytics. Process these into curated summaries with date, source and applicable vehicle identifiers, rather than delivering bulk raw national files to a phone.

## Technical and legal caveats that should be hard-coded

1. **A DTC is not a diagnosis.** An O2/catalyst/fuel-trim code is a monitor condition and may be caused by several systems.
2. **Manufacturer-specific code names can collide.** `P1xxx` is not universally meaningful. Standardized codes can also differ in make-specific context; match ECU, make/model/year and protocol.
3. **No fake live data.** Offline answer may use saved **timestamped** readings and explicitly label those as historical; otherwise use only actual current readings from the adapter.
4. **Safety takes precedence.** Emergencies like red oil-pressure warning, severe overheating, or brake failure require deterministic local warnings; they are not silent AI bypasses for ordinary questions.
5. **Do not collapse service notes into copyright-free text.** Manufacturer PDFs and SAE J1979/J2012/ISO material may be protected; use lawfully published facts and independent explanations without copying protected standard wording.
6. **VIN and recall freshness.** Offline recall data has a snapshot timestamp; confirm applicability/completion through current official VIN-level sources when network returns.
7. **License compliance.** Package original source licenses, changelog, accurate attribution (especially OBDb's CC BY-SA), link and modified-state declaration. Review third-party repo contents and dependencies separately.
8. **No single "failure probability" without evidence.** Complaint frequencies are affected by reporting patterns, sales volume, model-year differences and data quality; don't claim a percentage without a denominator and method.
9. **Version each dataset.** Persist URL, source revision, SHA-256, fetch date, import row counts, QA checks, validation outcomes and migration version.
10. **Avoid bloating offline delivery.** Target small core database and selectively downloaded packs. Keep infrastructure separate from Gemini routing PR and existing agents.

## Suggested QA gates before shipping third-party data

- Verify each source URL, retrieved license file and original dataset revision.
- Run schema checks; reject malformed JSON/CSV, duplicate identifiers and wrong year/make/ECU joins.
- Review at least 100 stratified random DTC samples against recognized sources; double check high-risk codes and conflicting manufacturer definitions.
- Compare 2011 BMW 528i **vehicle profile test** with actual known N52N configuration, but *never infer all early/late builds from EPA descriptions*. This is a **test fixture**, not a global hardcoded rule.
- Verify PID math on recorded **real** adapter frames (units, raw byte lengths, endian-ness, scaling, supported PIDs). Never decode unsupported manufacturer PIDs by guessing.
- Test zero network, high-latency network, Gemini error while online, local database missing, ambiguous vehicle profile, and data corruption without showing false confidence.
- Require independent review of safety warnings and high-voltage EV/hybrid guidance; do not give users unsafe DIY instructions.
- Run Android storage-size, cold-start query speed, FTS recall, migration, and audio-latency tests on the actual device.

## Suggested provenance schema for production

`knowledge_item(id, dataset_version, source_url, source_license, retrieved_utc, reviewed_utc, valid_from_year, valid_to_year, make, model, engine_code, ecu_id, request_address, region, source_confidence, is_community_assertion, requires_live_confirmation, payload_json)`.

Separate vehicle identity, generic diagnostic definitions, make/ECU-specific definitions, live reading snapshots, troubleshooting trees, and recall/TSB metadata. Store VIN/profile and diagnosis logs under user privacy controls, not in the shared public database.

## References checked on this date

- https://www.nhtsa.gov/nhtsa-datasets-and-apis
- https://vpic.nhtsa.dot.gov/downloads/
- https://vpic.nhtsa.dot.gov/api/
- https://www.fueleconomy.gov/feg/download.shtml
- https://www.fueleconomy.gov/feg/ws/index.shtml
- https://github.com/foerbsnavi/obdex
- https://github.com/Wal33D/dtc-database
- https://github.com/OBDb
- https://github.com/driveforgelabs/vehicle-db

**Dataset acquisition note:** the upstream datasets themselves were **not downloaded in this environment**, so their contents, schema and counts have not been independently validated by this package. The tests run only the new starter library and synthetic import fixtures.
