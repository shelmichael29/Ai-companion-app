#!/usr/bin/env python3
"""Download reviewed source datasets to local staging. No credentials or extra packages.

This is an opt-in research data collector, NOT an Android runtime downloader.
Run on a laptop/server with internet; verify licensing before redistribution.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = json.loads((ROOT / 'data' / 'sources.json').read_text(encoding='utf-8'))
MAX_BYTES = {"core": 120 * 1024 * 1024, "extended": 120 * 1024 * 1024, "heavy": 600 * 1024 * 1024}


def fetch(source, outdir, force=False):
    target = outdir / source['target']
    if target.exists() and not force:
        print(f"SKIP {source['id']} ({target.stat().st_size:,} bytes already exists)")
        return {'id': source['id'], 'status': 'existing', 'path': str(target.relative_to(ROOT)), 'bytes': target.stat().st_size,
                'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'source_url': source['url']}
    temp = target.with_suffix(target.suffix + '.partial')
    digest = hashlib.sha256()
    length = 0
    req = urllib.request.Request(source['url'], headers={'User-Agent':'PenelopeOfflineResearch/0.1 (research acquisition)'})
    try:
        with urllib.request.urlopen(req, timeout=50) as resp, temp.open('wb') as out:
            while True:
                block = resp.read(1024 * 1024)
                if not block:
                    break
                length += len(block)
                if length > MAX_BYTES[source['tier']]:
                    raise ValueError(f"Download exceeds {MAX_BYTES[source['tier']]//(1024*1024)} MiB safety limit")
                digest.update(block)
                out.write(block)
        if source['target'].endswith('.zip'):
            with zipfile.ZipFile(temp) as zf:
                if zf.testzip() is not None:
                    raise ValueError('Corrupt zip file')
                if sum(i.file_size for i in zf.infolist()) > 3 * 1024 ** 3:
                    raise ValueError('Archive expands beyond 3 GiB inspection limit')
        elif source['target'].endswith('.json'):
            with temp.open(encoding='utf-8') as f:
                json.load(f)
        os.replace(temp, target)
        print(f"OK   {source['id']}: {length:,} bytes")
        return {'id': source['id'], 'status': 'downloaded', 'path': str(target.relative_to(ROOT)),
                'bytes': length, 'sha256': digest.hexdigest(), 'source_url': source['url'],
                'downloaded_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    except (OSError, ValueError, urllib.error.URLError) as exc:
        print(f"FAIL {source['id']}: {type(exc).__name__}: {exc}")
        return {'id': source['id'], 'status': 'failed', 'source_url': source['url'], 'error': str(exc)}
    finally:
        temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tier', choices=['core', 'extended', 'heavy'], default='core',
                        help='core: app-relevant sources; extended: + selected NHTSA files; heavy: + 360 MB allegations dataset')
    parser.add_argument('--agree-heavy', action='store_true', help='acknowledge large download and privacy review')
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--only', nargs='+', help='specific IDs from data/sources.json; ignores tier for selection')
    args = parser.parse_args()
    if (args.tier == 'heavy' or args.only and any(s['tier'] == 'heavy' for s in SOURCES if s['id'] in args.only)) and not args.agree_heavy:
        parser.error('Heavy sources require --agree-heavy and should remain on the backend, not in the APK')
    outdir = ROOT / 'downloads'
    outdir.mkdir(exist_ok=True)
    included = {'core': ['core'], 'extended': ['core','extended'], 'heavy': ['core','extended','heavy']}[args.tier]
    chosen = [s for s in SOURCES if s['id'] in args.only] if args.only else [s for s in SOURCES if s['tier'] in included]
    unknown = set(args.only or ()) - {s['id'] for s in SOURCES}
    if unknown:
        parser.error('Unknown source IDs: ' + ', '.join(sorted(unknown)))
    receipts = [fetch(s, outdir, args.force) for s in chosen]
    (outdir / 'download_manifest.json').write_text(json.dumps(receipts, indent=2) + '\n', encoding='utf-8')
    print(f"\nComplete: {sum(r['status']!='failed' for r in receipts)}/{len(receipts)} datasets available; see downloads/download_manifest.json")
    if any(r['status'] == 'failed' for r in receipts):
        raise SystemExit(1)

if __name__ == '__main__':
    main()
