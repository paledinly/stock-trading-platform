"""Read-only extract of intraday_research_bar and stock metadata for the AI watch volume study. See PROTOCOL.md.

Run: python extract.py   (reads the repository .env, never prints credentials; writes data/ which is ignored)
"""
import datetime as dt
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
DATA = HERE / 'data'
sys.path.insert(0, str(HERE.parent / '2026-10-23-intraday-features-exploration'))
from intraday import connect  # noqa: E402

START, END = dt.date(2025, 10, 1), dt.date(2026, 11, 11)


def months():
    d = START
    while d < END:
        n = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        yield d, min(n, END)
        d = n


def main():
    DATA.mkdir(exist_ok=True)
    c = connect()
    c.execute('SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY')
    snapshot = str(c.execute('select current_timestamp').fetchone()[0])
    meta = pd.DataFrame(c.execute('select id, stock_code, stock_name, market, is_etf, is_etn from stock').fetchall(),
                        columns=['sid', 'code', 'name', 'market', 'etf', 'etn'])
    meta.to_pickle(DATA / 'meta.pkl')
    total = 0
    for a, b in months():
        out = DATA / f'bars-{a:%Y-%m}.pkl'
        if out.exists():
            continue
        cols = {k: [] for k in ('sid', 'ts', 'o', 'h', 'l', 'c', 'v', 'val')}
        with c.cursor(name=f'bars_{a:%Y%m}') as cur:
            cur.itersize = 200_000
            cur.execute("""select stock_id, extract(epoch from start_time)::bigint, open_price, high_price, low_price,
                                  close_price, volume, trading_value
                             from intraday_research_bar where start_time >= %s and start_time < %s""", (a, b))
            for row in cur:
                for k, v in zip(cols, row):
                    cols[k].append(v)
        c.commit()
        df = pd.DataFrame({'sid': np.array(cols['sid'], dtype=np.int32), 'ts': np.array(cols['ts'], dtype=np.int64),
                           'o': np.array(cols['o'], dtype=np.int64), 'h': np.array(cols['h'], dtype=np.int64),
                           'l': np.array(cols['l'], dtype=np.int64), 'c': np.array(cols['c'], dtype=np.int64),
                           'v': np.array(cols['v'], dtype=np.int64), 'val': np.array(cols['val'], dtype=np.int64)})
        df.to_pickle(out)
        total += len(df)
        print(f'{a:%Y-%m}: {len(df):,} rows', flush=True)
    (DATA / 'snapshot.json').write_text(json.dumps({'at': snapshot, 'read_only': True}), encoding='utf-8')
    print('done', total, flush=True)


if __name__ == '__main__':
    main()
