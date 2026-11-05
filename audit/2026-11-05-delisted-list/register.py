"""Register the KOSPI/KOSDAQ delistings of kind_delisted_2017-2026.csv with the backend (README.md).

Skips KONEX, market transfers (the code keeps trading) and SPACs (no research universe includes them).
Run: python register.py [--dry-run]   (talks to http://localhost:8080 only)
"""
import csv
import json
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
URL = 'http://localhost:8080/api/v1/market-data/delisted-history/stocks'


def targets():
    rows = list(csv.DictReader(open(HERE / 'kind_delisted_2017-2026.csv', encoding='utf-8')))
    keep = []
    for r in rows:
        if r['market'] == 'KONEX' or '이전상장' in r['reason'] or r['reason'] == '유가증권시장 상장' or '스팩' in r['stock_name']:
            continue
        keep.append({'stockCode': r['stock_code'], 'stockName': r['stock_name'], 'market': r['market'],
                     'delistedDate': r['delisted_date'], 'reason': r['reason']})
    return keep


def main():
    body = targets()
    print(f'{len(body)} delistings to register')
    if '--dry-run' in sys.argv:
        return
    req = urllib.request.Request(URL, data=json.dumps(body, ensure_ascii=False).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=60) as r:
        print(r.read().decode('utf-8'))


if __name__ == '__main__':
    main()
