import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

class KnowledgePackageTest(unittest.TestCase):
    def test_sources_are_unique_and_safe(self):
        rows=json.loads((ROOT/'data'/'sources.json').read_text(encoding='utf-8'))
        self.assertGreaterEqual(len(rows),10)
        self.assertEqual(len({row['id'] for row in rows}),len(rows))
        self.assertTrue(all(row['url'].startswith('https://') for row in rows))
    def test_symptom_guides_complete(self):
        rows=json.loads((ROOT/'data'/'symptom_guides.json').read_text(encoding='utf-8'))
        self.assertGreaterEqual(len(rows),20)
        self.assertEqual(len({row['id'] for row in rows}),len(rows))
        for row in rows:
            for prop in ('symptom','priority','questions','possible_systems','helpful_evidence','warning'):
                self.assertTrue(row[prop], (row['id'],prop))
    def test_seed_build_and_query(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'knowledge.sqlite'
            subprocess.run([sys.executable,str(ROOT/'scripts'/'build_database.py'),'--db',str(path)],check=True,capture_output=True,text=True)
            with sqlite3.connect(path) as db:
                guides=db.execute('SELECT COUNT(*) FROM symptom_guide').fetchone()[0]
                self.assertGreaterEqual(guides,20)
                warnings=db.execute("SELECT warning FROM symptom_guide WHERE guide_id='engine_overheat'").fetchone()
                self.assertIn('Never open',warnings[0])
                self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
    def test_importers_with_offline_fixtures(self):
        import importlib.util
        import zipfile
        spec=importlib.util.spec_from_file_location('kb_builder',ROOT/'scripts'/'build_database.py')
        m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
        with tempfile.TemporaryDirectory() as tmp:
            base=Path(tmp)
            generic=base/'generic.json'
            generic.write_text(json.dumps([{'code':'P0420','title':{'en':'Catalyst efficiency below threshold'},
                'description':{'en':'Generic OBD catalyst monitoring issue'},'category':'powertrain'}]))
            pid=base/'pids.json'
            pid.write_text(json.dumps([{'pid':'0C','name':'Engine RPM','unit':'rpm'}]))
            epazip=base/'vehicles.csv.zip'
            with zipfile.ZipFile(epazip,'w') as zf:
                zf.writestr('vehicles.csv','id,year,make,model,displ,cylinders,trany,drive,fuelType,city08,highway08,comb08\n90001,2011,BMW,528i,3.0,6,Automatic,Rear-Wheel Drive,Premium,18,27,21\n')
            with sqlite3.connect(':memory:') as db:
                db.executescript(m.SCHEMA)
                self.assertEqual(m.import_dtc(db,generic),1)
                self.assertEqual(m.import_pids(db,pid,'mode01'),1)
                self.assertEqual(m.import_epa(db,epazip),1)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM generic_dtc').fetchone()[0],1)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM obd_pid').fetchone()[0],1)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM epa_vehicle').fetchone()[0],1)
                self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(),[])

    def test_code_compiles(self):
        for name in ('download_sources.py','build_database.py','query_demo.py'):
            source=(ROOT/'scripts'/name).read_text()
            compile(source,name,'exec')

if __name__=='__main__':
    unittest.main()
