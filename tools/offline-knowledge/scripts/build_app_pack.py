#!/usr/bin/env python3
"""Build Penelope's compact, read-only on-device knowledge pack from the downloaded sources.

Run after scripts/download_sources.py (the downloads/ folder is never committed):

    python3 scripts/build_app_pack.py --out ../../app/src/main/assets/knowledge

Produces penelope_knowledge.db.gz (gzip of a SQLite database) and knowledge_manifest.json
(source URLs, licenses, SHA-256 of each input, row counts, build date). Only phone-sized,
redistributable, diagnostically useful data goes in; see docs/OFFLINE_KNOWLEDGE.md in the repo.
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DL = ROOT / 'downloads'
SCHEMA_VERSION = 1
MIN_YEAR = 1996  # OBD-II era; Penelope reads OBD-II cars only

SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE dtc_generic (
  code TEXT PRIMARY KEY, category TEXT, title TEXT NOT NULL, description TEXT,
  causes TEXT, symptoms TEXT, mil INTEGER, limp_mode INTEGER, emissions INTEGER,
  diy INTEGER, difficulty TEXT, reviewed TEXT);
CREATE TABLE dtc_manufacturer (
  code TEXT NOT NULL, make TEXT NOT NULL, description TEXT NOT NULL, PRIMARY KEY (code, make));
CREATE TABLE obd_pid (mode TEXT NOT NULL, pid TEXT NOT NULL, name TEXT, unit TEXT, PRIMARY KEY (mode, pid));
CREATE TABLE epa_vehicle (
  id TEXT PRIMARY KEY, year INTEGER NOT NULL, make TEXT NOT NULL, model TEXT NOT NULL, base_model TEXT,
  displ REAL, cylinders INTEGER, transmission TEXT, drive TEXT, fuel_type TEXT, vclass TEXT,
  turbo INTEGER, supercharger INTEGER, atv_type TEXT, mpg_city INTEGER, mpg_highway INTEGER, mpg_combined INTEGER);
CREATE INDEX idx_epa_ymm ON epa_vehicle(year, make, model);
CREATE TABLE symptom_guide (
  id TEXT PRIMARY KEY, symptom TEXT NOT NULL, priority TEXT, questions TEXT, systems TEXT, evidence TEXT, warning TEXT);
CREATE TABLE recall_campaign (
  campaign TEXT PRIMARY KEY, component TEXT, report_date TEXT, summary TEXT, consequence TEXT, remedy TEXT);
CREATE TABLE recall_vehicle (campaign TEXT NOT NULL, make TEXT NOT NULL, model TEXT NOT NULL, year INTEGER NOT NULL);
CREATE INDEX idx_recall_ymm ON recall_vehicle(make, year, model);
CREATE TABLE safety_rating (
  year INTEGER NOT NULL, make TEXT NOT NULL, model TEXT NOT NULL, body TEXT, drive TEXT,
  overall INTEGER, frontal INTEGER, side INTEGER, rollover INTEGER);
CREATE INDEX idx_safety_ymm ON safety_rating(make, year, model);
"""

# Wal33D manufacturer names → names used by EPA/NHTSA and the app's vehicle profiles.
MAKE_ALIASES = {'CHEVY': 'CHEVROLET', 'MERCEDES': 'MERCEDES-BENZ', 'VOLKSWAGEN': 'VOLKSWAGEN'}


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def en(v):
    return (v.get('en') if isinstance(v, dict) else v) or ''


def clip(text, n):
    text = ' '.join((text or '').split())
    if len(text) <= n:
        return text
    cut = text[:n]
    end = max(cut.rfind('. '), cut.rfind('; '))
    return (cut[:end + 1] if end > n // 2 else cut.rstrip() + '…')


def as_int(s):
    try:
        return int(float(s))
    except (TypeError, ValueError):
        return None


def as_float(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def build_dtc(conn):
    data = json.load(open(DL / 'obdex_generic.min.json', encoding='utf-8'))
    n = 0
    for r in data:
        code = (r.get('code') or '').strip().upper()
        if not code:
            continue
        causes = []
        for c in r.get('common_causes') or []:
            label = en(c.get('label')) if isinstance(c, dict) else en(c)
            if label:
                likelihood = c.get('likelihood', '') if isinstance(c, dict) else ''
                causes.append(f"{likelihood}|{label}")
        symptoms = [en(s) for s in r.get('symptoms') or [] if en(s)]
        flags = r.get('flags') or {}
        repair = r.get('repair') or {}
        conn.execute('INSERT OR REPLACE INTO dtc_generic VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', (
            code, r.get('category'), en(r.get('title')), en(r.get('description')),
            '\n'.join(causes), '\n'.join(symptoms),
            int(bool(flags.get('mil'))), int(bool(flags.get('limp_mode_possible'))), int(bool(flags.get('emissions_relevant'))),
            None if repair.get('diy_possible') is None else int(bool(repair.get('diy_possible'))), repair.get('difficulty'), r.get('reviewed')))
        n += 1
    return n


def build_manufacturer_dtc(conn):
    src = sqlite3.connect(f"file:{DL / 'wal33d_dtc_codes.db'}?mode=ro", uri=True)
    n = 0
    for code, make, desc in src.execute(
        "SELECT code, manufacturer, description FROM dtc_definitions WHERE is_generic = 0 AND locale = 'en' AND manufacturer NOT IN ('GENERIC','OTHER')"):
        make = MAKE_ALIASES.get(make.upper(), make.upper())
        conn.execute('INSERT OR IGNORE INTO dtc_manufacturer VALUES (?,?,?)', (code.strip().upper(), make, clip(desc, 200)))
        n += 1
    src.close()
    return conn.execute('SELECT COUNT(*) FROM dtc_manufacturer').fetchone()[0]


def build_pids(conn):
    n = 0
    for mode, name in (('01', 'obdex_pids_mode01.json'), ('09', 'obdex_pids_mode09.json')):
        data = json.load(open(DL / name, encoding='utf-8'))
        rows = data if isinstance(data, list) else data.get('pids') or list(data.values())
        for r in rows:
            if not isinstance(r, dict):
                continue
            pid = str(r.get('pid') or r.get('id') or r.get('code') or '').upper()
            if not pid:
                continue
            conn.execute('INSERT OR REPLACE INTO obd_pid VALUES (?,?,?,?)',
                         (mode, pid, clip(en(r.get('name') or r.get('title') or r.get('description')), 120), r.get('unit') or r.get('units')))
            n += 1
    return n


def build_epa(conn):
    with zipfile.ZipFile(DL / 'epa_vehicles.csv.zip') as zf, zf.open(zf.namelist()[0]) as raw:
        rows = csv.DictReader(io.TextIOWrapper(raw, encoding='utf-8-sig', errors='replace', newline=''))
        n = 0
        for r in rows:
            year = as_int(r.get('year'))
            if year is None or year < MIN_YEAR:
                continue
            conn.execute('INSERT OR REPLACE INTO epa_vehicle VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', (
                r['id'], year, r['make'].strip(), r['model'].strip(), (r.get('baseModel') or '').strip() or None,
                as_float(r.get('displ')), as_int(r.get('cylinders')), r.get('trany') or None, r.get('drive') or None,
                r.get('fuelType1') or r.get('fuelType') or None, r.get('VClass') or None, int((r.get('tCharger') or '').strip() not in ('', '0')),
                int((r.get('sCharger') or '').strip() not in ('', '0')), r.get('atvType') or None,
                as_int(r.get('city08')), as_int(r.get('highway08')), as_int(r.get('comb08'))))
            n += 1
    return n


def build_symptoms(conn):
    data = json.load(open(ROOT / 'data' / 'symptom_guides.json', encoding='utf-8'))
    guides = data if isinstance(data, list) else data['guides']
    for g in guides:
        conn.execute('INSERT OR REPLACE INTO symptom_guide VALUES (?,?,?,?,?,?,?)', (
            g['id'], g['symptom'], g.get('priority'), '\n'.join(g.get('questions', [])),
            '\n'.join(g.get('possible_systems', [])), '\n'.join(g.get('helpful_evidence', [])), g.get('warning')))
    return len(guides)


def build_recalls(conn):
    """NHTSA recall campaigns for vehicles (not equipment, tires or child seats), model years 1996+.
    Text is clipped: this is a campaign index, not the full notice. VIN-specific status is online-only."""
    seen = set()
    vehicles = 0
    for name in ('nhtsa_recalls_pre2010.zip', 'nhtsa_recalls_post2010.zip'):
        with zipfile.ZipFile(DL / name) as zf, zf.open(zf.namelist()[0]) as raw:
            for line in io.TextIOWrapper(raw, encoding='latin-1', newline=''):
                p = line.rstrip('\r\n').split('\t')
                if len(p) < 22 or p[10] != 'V':
                    continue
                year = as_int(p[4])
                if year is None or year < MIN_YEAR or year > 2100:
                    continue
                campaign, make, model = p[1].strip(), p[2].strip().upper(), p[3].strip().upper()
                if not campaign or not make or not model:
                    continue
                if campaign not in seen:
                    seen.add(campaign)
                    conn.execute('INSERT OR IGNORE INTO recall_campaign VALUES (?,?,?,?,?,?)', (
                        campaign, clip(p[6], 100), p[15][:8] or None, clip(p[19], 260), clip(p[20], 200), clip(p[21], 160)))
                conn.execute('INSERT INTO recall_vehicle VALUES (?,?,?,?)', (campaign, make, model, year))
                vehicles += 1
    # One row per campaign/make/model/year.
    conn.execute('CREATE TABLE rv AS SELECT DISTINCT campaign, make, model, year FROM recall_vehicle')
    conn.execute('DELETE FROM recall_vehicle')
    conn.execute('INSERT INTO recall_vehicle SELECT * FROM rv')
    conn.execute('DROP TABLE rv')
    return len(seen), conn.execute('SELECT COUNT(*) FROM recall_vehicle').fetchone()[0]


def build_safety(conn):
    n = 0
    with open(DL / 'nhtsa_safercar_ratings.csv', encoding='latin-1', newline='') as f:
        for r in csv.DictReader(f):
            year = as_int(r.get('MODEL_YR'))
            stars = {k: as_int(r.get(k)) for k in ('OVERALL_STARS', 'OVERALL_FRNT_STARS', 'OVERALL_SIDE_STARS', 'ROLLOVER_STARS')}
            if year is None or year < MIN_YEAR or not any(stars.values()):
                continue
            conn.execute('INSERT INTO safety_rating VALUES (?,?,?,?,?,?,?,?,?)', (
                year, r['MAKE'].strip().upper(), r['MODEL'].strip().upper(), r.get('BODY_STYLE') or None, r.get('DRIVE_TRAIN') or None,
                stars['OVERALL_STARS'], stars['OVERALL_FRNT_STARS'], stars['OVERALL_SIDE_STARS'], stars['ROLLOVER_STARS']))
            n += 1
    return n


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', required=True, help='folder for penelope_knowledge.db.gz and knowledge_manifest.json')
    args = ap.parse_args()
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    sources = {s['id']: s for s in json.load(open(ROOT / 'data' / 'sources.json', encoding='utf-8'))}
    download = {r['id']: r for r in json.load(open(DL / 'download_manifest.json', encoding='utf-8'))}

    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / 'penelope_knowledge.db'
        conn = sqlite3.connect(db)
        conn.executescript(SCHEMA)
        counts = {}
        counts['dtc_generic'] = build_dtc(conn)
        counts['dtc_manufacturer'] = build_manufacturer_dtc(conn)
        counts['obd_pid'] = build_pids(conn)
        counts['epa_vehicle'] = build_epa(conn)
        counts['symptom_guide'] = build_symptoms(conn)
        counts['recall_campaign'], counts['recall_vehicle'] = build_recalls(conn)
        counts['safety_rating'] = build_safety(conn)
        built = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
        conn.execute('INSERT INTO meta VALUES (?,?)', ('schema_version', str(SCHEMA_VERSION)))
        conn.execute('INSERT INTO meta VALUES (?,?)', ('built_utc', built))
        conn.commit()
        conn.execute('VACUUM')
        conn.close()
        raw_size = db.stat().st_size
        gz = out / 'penelope_knowledge.db.gz'
        with open(db, 'rb') as src, gzip.GzipFile(gz, 'wb', compresslevel=9, mtime=0) as dst:
            shutil.copyfileobj(src, dst)
        db_sha = sha256(db)

    used = ['obdex_generic', 'obdex_mode01', 'obdex_mode09', 'wal33d_dtc', 'epa_vehicles',
            'nhtsa_recall_pre2010', 'nhtsa_recall_post2010', 'nhtsa_safety_ratings']
    manifest = {
        'schema_version': SCHEMA_VERSION, 'built_utc': built, 'database_sha256': db_sha,
        'database_bytes': raw_size, 'compressed_bytes': gz.stat().st_size, 'min_model_year': MIN_YEAR,
        'row_counts': counts,
        'sources': [{
            'id': i, 'name': sources[i].get('name'), 'license': sources[i].get('license'), 'url': sources[i].get('url'),
            'downloaded_utc': download.get(i, {}).get('downloaded_utc'), 'sha256': download.get(i, {}).get('sha256'),
        } for i in used] + [{'id': 'penelope_seed', 'name': 'Penelope symptom guides', 'license': 'Project original'}],
    }
    (out / 'knowledge_manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: manifest[k] for k in ('row_counts', 'database_bytes', 'compressed_bytes')}, indent=2))


if __name__ == '__main__':
    main()
