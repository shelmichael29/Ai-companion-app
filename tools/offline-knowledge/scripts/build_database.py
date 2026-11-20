#!/usr/bin/env python3
"""Create a self-contained SQLite knowledge library from *already downloaded* public sources.

Never sends vehicle information anywhere. Requires Python 3.10+, stdlib only.
A minimal database with 24 original symptom guides is created even without downloads.
Third-party proprietary or ambiguously structured flat files are left staged for audit.
"""
import argparse
import csv
import io
import json
from pathlib import Path
import sqlite3
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = """
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS source_provenance (
    source_id TEXT PRIMARY KEY, source_name TEXT NOT NULL,
    license TEXT, source_url TEXT, imported_count INTEGER DEFAULT 0,
    notes TEXT);
CREATE TABLE IF NOT EXISTS generic_dtc (
    code TEXT PRIMARY KEY, category TEXT, title_en TEXT, description_en TEXT,
    details_json TEXT NOT NULL, reviewed_on TEXT, provenance_id TEXT NOT NULL
        REFERENCES source_provenance(source_id));
CREATE INDEX IF NOT EXISTS idx_generic_dtc_category ON generic_dtc(category);
CREATE TABLE IF NOT EXISTS obd_pid (
    mode TEXT NOT NULL, pid TEXT NOT NULL, name TEXT, specification_json TEXT NOT NULL,
    provenance_id TEXT NOT NULL REFERENCES source_provenance(source_id),
    PRIMARY KEY (mode, pid));
CREATE TABLE IF NOT EXISTS epa_vehicle (
    epa_id TEXT PRIMARY KEY, year INTEGER, make TEXT, model TEXT,
    engine_liters REAL, cylinders INTEGER, transmission TEXT, drive TEXT,
    fuel TEXT, city_mpg REAL, highway_mpg REAL, combined_mpg REAL,
    source_id TEXT NOT NULL REFERENCES source_provenance(source_id));
CREATE INDEX IF NOT EXISTS idx_epa_lookup ON epa_vehicle(year,make,model);
CREATE TABLE IF NOT EXISTS symptom_guide (
    guide_id TEXT PRIMARY KEY, symptom TEXT NOT NULL, priority TEXT NOT NULL,
    questions_json TEXT NOT NULL, possible_systems_json TEXT NOT NULL,
    evidence_json TEXT NOT NULL, warning TEXT NOT NULL, source_id TEXT NOT NULL
        REFERENCES source_provenance(source_id));
CREATE VIRTUAL TABLE IF NOT EXISTS symptom_search USING fts5(guide_id UNINDEXED, symptom, possible_systems);
CREATE VIRTUAL TABLE IF NOT EXISTS code_search USING fts5(code UNINDEXED, title, description);
"""

def asfloat(s):
    try:
        return float(s)
    except (ValueError, TypeError):
        return None

def asint(s):
    try:
        return int(s)
    except (ValueError, TypeError):
        return None

def upsert_provenance(conn, id, name, license, url, count=0, notes=''):
    conn.execute('INSERT OR REPLACE INTO source_provenance (source_id,source_name,license,source_url,imported_count,notes) VALUES (?,?,?,?,?,?)', (id,name,license,url,count,notes))

def load_json_records(path):
    data = json.loads(path.read_text(encoding='utf-8'))
    if isinstance(data, list): return data
    if isinstance(data, dict):
        for key in ('codes','pids','data','results','items'):
            if isinstance(data.get(key), list): return data[key]
    raise ValueError(f'Unexpected JSON shape in {path.name}: inspect upstream schema before import')

def import_symptoms(conn):
    src = json.loads((ROOT / 'data' / 'symptom_guides.json').read_text(encoding='utf-8'))
    upsert_provenance(conn,'penelope_seed','Original Penelope starter symptom guides','Original content','local:data/symptom_guides.json',len(src),'Reviewed qualitatively, not a verified diagnostic decision tree')
    for row in src:
        conn.execute('INSERT OR REPLACE INTO symptom_guide VALUES (?,?,?,?,?,?,?,?)',
            (row['id'],row['symptom'],row['priority'],json.dumps(row['questions']),
             json.dumps(row['possible_systems']),json.dumps(row['helpful_evidence']),row['warning'],'penelope_seed'))
        conn.execute('INSERT INTO symptom_search VALUES (?,?,?)',
                     (row['id'],row['symptom'],' '.join(row['possible_systems'])))
    return len(src)

def import_dtc(conn, path):
    rows = load_json_records(path)
    upsert_provenance(conn,'obdex_generic','OBDex generic codes','CC0-1.0','https://github.com/foerbsnavi/obdex',0,'Descriptions are not confirmed failed parts')
    count = 0
    for item in rows:
        if not isinstance(item, dict): continue
        code = item.get('code')
        if not isinstance(code, str) or len(code) != 5: continue
        title = item.get('title')
        desc = item.get('description')
        title = title.get('en') if isinstance(title,dict) else title
        desc = desc.get('en') if isinstance(desc,dict) else desc
        conn.execute('INSERT OR REPLACE INTO generic_dtc VALUES (?,?,?,?,?,?,?)',
                     (code.upper(),item.get('category'),title,desc,json.dumps(item,ensure_ascii=False),
                      item.get('reviewed'),'obdex_generic'))
        conn.execute('INSERT INTO code_search VALUES (?,?,?)',(code.upper(),title or '',desc or ''))
        count += 1
    upsert_provenance(conn,'obdex_generic','OBDex generic codes','CC0-1.0','https://github.com/foerbsnavi/obdex',count,'Descriptions are not confirmed failed parts')
    return count

def import_pids(conn,path,mode):
    rows = load_json_records(path)
    upsert_provenance(conn,'obdex_'+mode,'OBDex '+mode+' PIDs','CC0-1.0','https://github.com/foerbsnavi/obdex',0,'Schema must be verified before implementing formulas')
    count = 0
    for item in rows:
        if not isinstance(item,dict):continue
        raw = item.get('pid',item.get('id',item.get('code')))
        if raw is None: continue
        pid = str(raw)
        label = item.get('name',item.get('title',item.get('description')))
        if isinstance(label,dict): label = label.get('en')
        if label is not None and not isinstance(label,str): label = str(label)
        conn.execute('INSERT OR REPLACE INTO obd_pid VALUES (?,?,?,?,?)',
                     (mode,pid,label,json.dumps(item,ensure_ascii=False),'obdex_'+mode))
        count += 1
    upsert_provenance(conn,'obdex_'+mode,'OBDex '+mode+' PIDs','CC0-1.0',
                      'https://github.com/foerbsnavi/obdex',count,'Schema must be verified before implementing formulas')
    return count

def import_epa(conn,path):
    upsert_provenance(conn,'epa_vehicles','EPA FuelEconomy vehicle records','US federal dataset; verify terms','https://www.fueleconomy.gov/feg/download.shtml',0,'EPA variants not definitive VIN/engine determination')
    with zipfile.ZipFile(path) as zf:
        names = [i.filename for i in zf.infolist() if i.filename.lower().endswith('.csv')]
        if not names: raise ValueError('EPA archive contains no CSV')
        with zf.open(names[0]) as raw, io.TextIOWrapper(raw,encoding='utf-8-sig', errors='replace',newline='') as fp:
            reader = csv.DictReader(fp)
            needed = {'id','year','make','model'}
            if not reader.fieldnames or not needed.issubset(reader.fieldnames):
                raise ValueError('EPA CSV expected columns id/year/make/model; schema changed')
            count=0
            for row in reader:
                key = row['id']
                if not key: continue
                conn.execute('INSERT OR REPLACE INTO epa_vehicle VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',
                    (key,asint(row.get('year')),row.get('make'),row.get('model'),
                     asfloat(row.get('displ')),asint(row.get('cylinders')),row.get('trany'),row.get('drive'),
                     row.get('fuelType'),asfloat(row.get('city08')),asfloat(row.get('highway08')),
                     asfloat(row.get('comb08')),'epa_vehicles'))
                count+=1
    upsert_provenance(conn,'epa_vehicles','EPA FuelEconomy vehicle records','US federal dataset; verify terms','https://www.fueleconomy.gov/feg/download.shtml',count,'EPA variants not definitive VIN/engine determination')
    return count

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--db',default=str(ROOT/'output'/'penelope_offline.sqlite'))
    args=p.parse_args()
    target=Path(args.db)
    target.parent.mkdir(parents=True,exist_ok=True)
    target.unlink(missing_ok=True)
    with sqlite3.connect(target) as conn:
        conn.executescript(SCHEMA)
        print('Imported symptom guides:',import_symptoms(conn))
        d=ROOT/'downloads'
        for label,filename,handler in [
            ('OBDex generic','obdex_generic.min.json',lambda x:import_dtc(conn,x)),
            ('OBDex Mode 01','obdex_pids_mode01.json',lambda x:import_pids(conn,x,'mode01')),
            ('OBDex Mode 09','obdex_pids_mode09.json',lambda x:import_pids(conn,x,'mode09')),
            ('EPA vehicles','epa_vehicles.csv.zip',lambda x:import_epa(conn,x))]:
            path=d/filename
            if not path.exists():
                print(f'NOT PRESENT (skipped): {label}; run scripts/download_sources.py on a networked computer')
                continue
            print(f'Imported {label}:',handler(path))
        conn.commit()
        conn.execute('PRAGMA optimize')
        result=list(conn.execute('SELECT source_id,imported_count FROM source_provenance ORDER BY source_id'))
        print('Final provenance counts:',result)
    print('Created',target,'size:',target.stat().st_size,'bytes')

if __name__=='__main__':
    main()
