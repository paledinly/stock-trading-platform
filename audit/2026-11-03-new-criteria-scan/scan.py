"""New selection and timing criteria for the one-night trade, exploration (2026-11-03). Hypothesis generation.

Trade (fixed by the earlier horizon map and the ML feasibility check): buy at the close of d, sell at the next open.
Universe and outcome: exactly study B of ../2026-11-03-ml-ranking-preregistration (cached dataset rows, outlier rows
dropped): common shares, >= 80 bars, 20-day mean trading value >= 1bn KRW, day return < +29%, next open on the next
trading day. Net after ADR-008 BASE costs (buy 0.095%, sell 0.295%).

Fixed before running (nothing else is tried):
  Stock criteria (computed with data up to the close of d, per stock; quintiles inside each day, Q5 = highest):
    K1 ON_MOM20   mean overnight return (open / previous close - 1) over the last 20 days (incl. d)
    K2 ON_MOM60   same over 60 days
    K3 ID_MOM20   mean intraday return (close / open - 1) over the last 20 days
    K4 TUG20      K1 - K3 (overnight winners that lose intraday)
    K5 ON_WIN20   share of the last 20 days with a positive overnight return
    K6 MAX20      largest daily return of the last 20 days (lottery-like)
    K7 ABN_VAL    trading value of d / mean of d-20..d-1
    K8 GAP_TODAY  overnight return of d (open of d / close of d-1 - 1)
    K9 ID_TODAY   intraday return of d (close / open - 1)
    K10 LOG_PRICE log of the close of d
  Group: KOSPI vs KOSDAQ (stock.market) - descriptive.
  Day filters (whole market, information up to the close of d; computed over the universe of d):
    G1 MKT_GAP    mean overnight return of d > 0 (the market opened up today)
    G2 MKT_ID     mean intraday return of d > 0 (the market rose during the day)
    G3 MKT_RET    mean day return of d > 0
    G4 MKT_TREND  equal-weight index (cumulated daily mean return) above its value 20 days earlier
    G5 MKT_ON20   mean of the market's overnight return over the last 20 days > 0
  Statistics per criterion and period: daily means of Q5, Q1 and ALL; Q5 - Q1 and Q5 - ALL with 95% intervals
    (circular blocks of 5 days, 10,000 draws, seed 20261103). Day filters: ALL Net on filter-true vs filter-false days
    (each with its interval).
  Periods: discovery 2018-04-01..2022-06-30, check 2022-07-01..2026-10-21.
  Keep rule (stock criteria): Q5 - Q1 has the same sign in both periods and the discovery interval excludes zero;
    practical: the favoured quintile's Net > 0 in both periods. Day filters: the true-day ALL Net minus the false-day
    ALL Net has the same sign in both periods and the discovery intervals of the two do not overlap.
  15 criteria x 1 outcome; a single pass can be luck. Survivors go to a new pre-registration and Forward.
Known limits: delisted stocks are absent; close-based inputs assume the close is known when buying (the feasibility
  check found this small for the ML model, not checked for each criterion); the daily data were used by earlier
  explorations.
Run: python scan.py   (needs the study-B cache; reads the repository .env, never prints credentials)
"""
import datetime as dt
import pickle
import random
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-11-03-ml-ranking-preregistration'))
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
import ml_ranking as mlr  # noqa: E402
from volatility import block_ci, connect  # noqa: E402

SEED = 20261103
PERIODS = {'discovery': (dt.date(2018, 4, 1), dt.date(2022, 6, 30)), 'check': (dt.date(2022, 7, 1), dt.date(2026, 10, 21))}
CRITERIA = ['ON_MOM20', 'ON_MOM60', 'ID_MOM20', 'TUG20', 'ON_WIN20', 'MAX20', 'ABN_VAL', 'GAP_TODAY', 'ID_TODAY',
            'LOG_PRICE']
FILTERS = ['MKT_GAP', 'MKT_ID', 'MKT_RET', 'MKT_TREND', 'MKT_ON20']


def ci(values):
    return block_ci([float(v) for v in values], 5, random.Random(SEED))


def fmt(x):
    return f'{x[0]:+.3f} [{x[1]:+.3f}, {x[2]:+.3f}]'


def main():
    c = connect()
    df, trading, _ = mlr.load_daily(c)
    market = {sid: m for sid, m in c.execute('select id, market from stock')}
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    f = pd.DataFrame({'sid': df['sid'], 'd': df['d']})
    f['ON'] = df['o'] / prev_c - 1
    f['ID'] = df['c'] / df['o'] - 1
    f['RET'] = df['c'] / prev_c - 1
    gf = f.groupby('sid', sort=False)
    f['ON_MOM20'] = gf['ON'].transform(lambda s: s.rolling(20).mean())
    f['ON_MOM60'] = gf['ON'].transform(lambda s: s.rolling(60).mean())
    f['ID_MOM20'] = gf['ID'].transform(lambda s: s.rolling(20).mean())
    f['TUG20'] = f['ON_MOM20'] - f['ID_MOM20']
    f['ON_WIN20'] = gf['ON'].transform(lambda s: (s > 0).astype(float).rolling(20).mean())
    f['MAX20'] = gf['RET'].transform(lambda s: s.rolling(20).max())
    f['ABN_VAL'] = df['val'] / g['val'].transform(lambda s: s.shift(1).rolling(20).mean())
    f['GAP_TODAY'] = f['ON']
    f['ID_TODAY'] = f['ID']
    f['LOG_PRICE'] = np.log(df['c'])
    del df
    with open(mlr.CACHE / 'datasets.pkl', 'rb') as fh:
        b = pickle.load(fh)['B'][0]
    b = b[~b['outlier']][['sid', 'd', 'y']]
    x = b.merge(f, on=['sid', 'd'], how='left')
    del f, b
    x['KOSDAQ'] = x['sid'].map(lambda s: 'KOSDAQ' in str(market.get(s, '')).upper())
    print(f'rows {len(x)}, days {x["d"].nunique()}, KOSDAQ share {x["KOSDAQ"].mean():.2f}', flush=True)

    # day-level market series
    day = x.groupby('d').agg(ALL=('y', 'mean'), MKT_GAP=('ON', 'mean'), MKT_ID=('ID', 'mean'), MKT_RET=('RET', 'mean'))
    day = day.sort_index()
    idx = (1 + day['MKT_RET'].fillna(0)).cumprod()
    day['MKT_TREND'] = idx / idx.shift(20) - 1
    day['MKT_ON20'] = day['MKT_GAP'].rolling(20).mean()

    def period_rows(frame, p):
        lo, hi = PERIODS[p]
        return frame[(frame.index >= lo) & (frame.index <= hi)]

    print('\n===== Day filters: ALL Net (close -> next open) on filter-true vs filter-false days')
    summary = []
    for name in FILTERS:
        print(f'\n-- {name} > 0')
        res = {}
        for p in PERIODS:
            dd = period_rows(day.dropna(subset=[name]), p)
            on, off = dd[dd[name] > 0]['ALL'], dd[dd[name] <= 0]['ALL']
            a, b_ = ci(on), ci(off)
            res[p] = (a, b_)
            print(f'   {p:9s} true {len(on):4d} days Net {fmt(a)} | false {len(off):4d} days Net {fmt(b_)} | diff {a[0] - b_[0]:+.3f}')
        (a1, b1), (a2, b2) = res['discovery'], res['check']
        same = (a1[0] - b1[0]) * (a2[0] - b2[0]) > 0
        apart = a1[1] > b1[2] or b1[1] > a1[2]
        v = 'KEEP' if same and apart else 'DROP'
        print(f'   -> {v}')
        summary.append((name, 'filter', v, f'true-day Net {a1[0]:+.3f} / {a2[0]:+.3f}, false-day {b1[0]:+.3f} / {b2[0]:+.3f}'))

    print('\n===== Stock criteria: quintiles inside each day (Q5 = highest), Net close -> next open')
    x['q'] = 0
    for name in CRITERIA:
        sub = x.dropna(subset=[name])
        pct = sub.groupby('d')[name].rank(method='first', pct=True)
        q = np.ceil(pct * 5).clip(1, 5)
        daily = pd.DataFrame({'d': sub['d'], 'q': q, 'y': sub['y']})
        means = daily.groupby(['d', 'q'])['y'].mean().unstack()
        means['ALL'] = daily.groupby('d')['y'].mean()
        means = means.dropna()
        print(f'\n-- {name}')
        res = {}
        for p in PERIODS:
            m = period_rows(means, p)
            spread, excess = ci(m[5.0] - m[1.0]), ci(m[5.0] - m['ALL'])
            res[p] = (spread, m[5.0].mean(), m[1.0].mean())
            print(f'   {p:9s} days {len(m):4d} | Q1 {m[1.0].mean():+.3f} Q2 {m[2.0].mean():+.3f} Q3 {m[3.0].mean():+.3f} '
                  f'Q4 {m[4.0].mean():+.3f} Q5 {m[5.0].mean():+.3f} ALL {m["ALL"].mean():+.3f} | Q5-Q1 {fmt(spread)} '
                  f'| Q5-ALL {fmt(excess)}')
        (s1, q5a, q1a), (s2, q5b, q1b) = res['discovery'], res['check']
        rule1 = s1[0] * s2[0] > 0 and (s1[1] > 0 or s1[2] < 0)
        fav = 'Q5' if s1[0] > 0 else 'Q1'
        na, nb = (q5a, q5b) if fav == 'Q5' else (q1a, q1b)
        v = ('KEEP' if na > 0 and nb > 0 else 'AVOID') if rule1 else 'DROP'
        print(f'   -> {v} (favoured {fav}, Net {na:+.3f} / {nb:+.3f})')
        summary.append((name, 'stock', v, f'favoured {fav} Net {na:+.3f} / {nb:+.3f}, Q5-Q1 {s1[0]:+.3f} / {s2[0]:+.3f}'))

    print('\n===== KOSPI vs KOSDAQ (descriptive)')
    for p in PERIODS:
        lo, hi = PERIODS[p]
        sub = x[(x['d'] >= lo) & (x['d'] <= hi)]
        k = sub.groupby(['d', 'KOSDAQ'])['y'].mean().unstack().dropna()
        print(f'   {p:9s} KOSPI {k[False].mean():+.3f}  KOSDAQ {k[True].mean():+.3f}  diff {fmt(ci(k[True] - k[False]))}')

    print('\n===== Summary (15 criteria)')
    for name, kind, v, note in summary:
        print(f'   {name:10s} {kind:6s} {v:5s} {note}')


if __name__ == '__main__':
    main()
