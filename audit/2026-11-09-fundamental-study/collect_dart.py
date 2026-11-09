"""Collect DART financial statements and share counts for the fundamental study (README.md).

Research-only collector. Writes local files under data/ (gitignored), never the database.
  corp.csv          corpCode.xml: corp_code, corp_name, stock_code, modify_date (every company DART lists)
  cache/acnt/...    fnlttMultiAcnt.json (main accounts, up to 100 companies per call) per year x report
  cache/shares/...  stockTotqySttus.json (issued / treasury shares) per company x business year, year-end report
Every response is cached as received, so a rerun only asks for what is missing. Status 013 (no data) is cached too.
Stops at status 020 (daily limit) and resumes on the next run.
Targets: stock codes of `stock` (share() filter, not ETF/ETN) plus delisted_stock, matched to DART corp codes by
stock code. Share counts: year-end report only, for the years a company has prices (from the year before its first
price row, not before --from, to its last price year, not after --to), oldest year first.
Run: python collect_dart.py --part accounts [--from 2016] [--to 2026]
     python collect_dart.py --part shares --from 2017 --to 2025 [--workers 3]
(reads the repository .env, never prints the key)
"""
import argparse
import io
import json
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
from volatility import connect, share  # noqa: E402

DATA = HERE / 'data'
API = 'https://opendart.fss.or.kr/api/'
REPORTS = {'11013': 'Q1', '11012': 'H1', '11014': 'Q3', '11011': 'FY'}
PAUSE = 0.15


class DailyLimit(Exception):
    pass


def api_key():
    for line in open(ROOT / '.env', encoding='utf-8'):
        line = line.strip()
        if line.startswith('DART_API_KEY='):
            return line.split('=', 1)[1].strip().strip('"').strip("'")
    raise SystemExit('DART_API_KEY is not set in .env')


def get(key, name, params):
    for attempt in range(4):
        try:
            r = requests.get(API + name, params={'crtfc_key': key, **params}, timeout=60)
            r.raise_for_status()
            return r
        except requests.RequestException as e:
            # the exception text holds the URL with the key; print the type only
            print(f'  {name} retry {attempt + 1}: {type(e).__name__}', flush=True)
            time.sleep(5 * (attempt + 1))
    raise SystemExit(f'{name} failed after retries')


def cached_json(key, name, params, path):
    if path.exists():
        try:
            return json.loads(path.read_text(encoding='utf-8')), False
        except ValueError:
            pass  # a file cut short by an interrupted run is fetched again
    body = get(key, name, params).json()
    status = body.get('status')
    if status == '020':
        raise DailyLimit()
    if status not in ('000', '013'):
        raise SystemExit(f'{name} status {status}: {body.get("message")}')
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(body, ensure_ascii=False), encoding='utf-8')
    tmp.replace(path)
    time.sleep(PAUSE)
    return body, True


def corp_codes(key):
    out = DATA / 'corp.csv'
    if not out.exists():
        z = zipfile.ZipFile(io.BytesIO(get(key, 'corpCode.xml', {}).content))
        root = ET.fromstring(z.read(z.namelist()[0]))
        rows = [(e.findtext('corp_code').strip(), e.findtext('corp_name').strip(),
                 (e.findtext('stock_code') or '').strip(), (e.findtext('modify_date') or '').strip())
                for e in root.iter('list')]
        DATA.mkdir(parents=True, exist_ok=True)
        with open(out, 'w', encoding='utf-8') as f:
            f.write('corp_code,corp_name,stock_code,modify_date\n')
            for r in rows:
                f.write(','.join(x.replace(',', ' ') for x in r) + '\n')
    rows = [line.rstrip('\n').split(',') for line in open(out, encoding='utf-8')][1:]
    return {r[2]: r[0] for r in rows if r[2]}


def targets(by_stock):
    c = connect()
    live = {code for code, name, etf, etn in c.execute('select stock_code, stock_name, is_etf, is_etn from stock')
            if share(code, name) and not etf and not etn}
    dead = {code for code, name in c.execute('select stock_code, stock_name from delisted_stock') if share(code, name)}
    span = {code: (a.year, b.year) for code, a, b in c.execute(
        '''select s.stock_code, min(d.trade_date), max(d.trade_date) from daily_adjusted_candle d
           join stock s on s.id = d.stock_id group by s.stock_code''')}
    span.update({code: (a.year, b.year) for code, a, b in c.execute(
        'select stock_code, min(trade_date), max(trade_date) from delisted_daily_history group by stock_code')})
    c.close()
    wanted = live | dead
    found = sorted({by_stock[code] for code in wanted if code in by_stock})
    missing = sorted(wanted - set(by_stock))
    print(f'targets: listed {len(live)}, delisted {len(dead)}, matched corp codes {len(found)}, '
          f'no DART code {len(missing)} (listed {len(missing_live := [m for m in missing if m in live])}, '
          f'delisted {len(missing) - len(missing_live)})', flush=True)
    years = {by_stock[code]: span[code] for code in wanted if code in by_stock and code in span}
    return found, years


def accounts(key, corps, y0, y1):
    calls = 0
    for year in range(y0, y1 + 1):
        for rc, rn in REPORTS.items():
            for i in range(0, len(corps), 100):
                batch = corps[i:i + 100]
                path = DATA / 'cache' / 'acnt' / f'{year}_{rn}_{i // 100:03d}.json'
                _, new = cached_json(key, 'fnlttMultiAcnt.json',
                                     {'corp_code': ','.join(batch), 'bsns_year': year, 'reprt_code': rc}, path)
                calls += new
        print(f'accounts {year} done (calls so far {calls})', flush=True)
    return calls


def shares(key, corps, years, y0, y1, workers):
    calls, stop, lock = [0], threading.Event(), threading.Lock()

    def one(corp, year):
        if stop.is_set():
            return
        path = DATA / 'cache' / 'shares' / f'{year}_FY' / f'{corp}.json'
        try:
            _, new = cached_json(key, 'stockTotqySttus.json',
                                 {'corp_code': corp, 'bsns_year': year, 'reprt_code': '11011'}, path)
        except DailyLimit:
            stop.set()
            return
        with lock:
            calls[0] += new

    for year in range(y0, y1 + 1):
        todo = [corp for corp in corps if corp in years and max(y0, years[corp][0] - 1) <= year <= years[corp][1]]
        with ThreadPoolExecutor(workers) as pool:
            list(pool.map(lambda corp: one(corp, year), todo))
        if stop.is_set():
            raise DailyLimit()
        print(f'shares {year} done ({len(todo)} companies, calls so far {calls[0]})', flush=True)
    return calls[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--part', choices=('accounts', 'shares'), required=True)
    ap.add_argument('--from', dest='y0', type=int, default=2016)
    ap.add_argument('--to', dest='y1', type=int, default=2026)
    ap.add_argument('--workers', type=int, default=3)
    args = ap.parse_args()
    key = api_key()
    corps, years = targets(corp_codes(key))
    try:
        if args.part == 'accounts':
            calls = accounts(key, corps, args.y0, args.y1)
        else:
            calls = shares(key, corps, years, args.y0, args.y1, args.workers)
    except DailyLimit:
        print('DART daily limit reached; run again tomorrow to resume', flush=True)
        return
    print(f'complete: {calls} new calls', flush=True)


if __name__ == '__main__':
    main()
