#!/usr/bin/env python3
"""Demonstrate offline local lookups; no cloud/API call."""
import argparse
from pathlib import Path
import sqlite3
import json
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--db',default=str(ROOT/'output'/'penelope_offline.sqlite'))
p.add_argument('search',help='symptom text, exact DTC, or make/model')
args=p.parse_args()
with sqlite3.connect(args.db) as con:
    con.row_factory=sqlite3.Row
    key=args.search.strip()
    dtc=con.execute('SELECT code,title_en,description_en FROM generic_dtc WHERE code=?',(key.upper(),)).fetchone()
    if dtc:
        print('DTC:',dict(dtc))
    entries=con.execute('SELECT symptom,priority,questions_json,warning FROM symptom_guide WHERE lower(symptom) LIKE ? LIMIT 10',('%'+key.lower()+'%',)).fetchall()
    for item in entries:
        r=dict(item);r['questions']=json.loads(r.pop('questions_json'));print('GUIDE:',r)
    records=con.execute('SELECT year,make,model,engine_liters,combined_mpg FROM epa_vehicle WHERE lower(make||" "||model) LIKE ? LIMIT 10',('%'+key.lower()+'%',)).fetchall()
    for r in records: print('EPA:',dict(r))
    if not dtc and not entries and not records:
        print('No exact supported record. Do not invent code meanings or sensor readings.')
