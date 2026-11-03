"""Feasibility of the daily ML model (study B), pre-registered (README.md in this folder, 2026-11-03).

Carries the README definitions over unchanged; committed with the README before the first run.
Implementation details fixed here before running:
  Models: study B's walk() is reproduced exactly for the test years 2025 and 2026 (same cleaned rows, same embargo,
    same PARAMS) using the cached study-B dataset of ../2026-11-03-ml-ranking-preregistration.
  Daily arrays: adjusted daily bars by stock; "d-1" = the stock's previous daily row, which must be the previous
    trading day. Today's volume is divided by the adjustment ratio k (price x k, volume / k keeps value unchanged).
  The knowing-the-close comparison uses study-B rows for the same (stock, day) when they exist (their own universe
    rule), with study-B features and study-B outcome (adjusted close -> adjusted next open).
Run: python feasibility.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import math
import pickle
import random
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-11-03-ml-ranking-preregistration'))
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
import ml_ranking as mlr  # noqa: E402
from volatility import block_ci, connect  # noqa: E402

FROM, TO = dt.date(2025, 11, 3), dt.date(2026, 10, 21)
HALF = dt.date(2026, 4, 30)
SEED = 20261103


def train_models(trading):
    with open(mlr.CACHE / 'datasets.pkl', 'rb') as fh:
        data = pickle.load(fh)
    b, cols = data['B']
    clean = b[~b['outlier']]
    tpos = {d: k for k, d in enumerate(trading)}
    models = {}
    for year in (2025, 2026):
        test = clean[(clean['d'] >= dt.date(year, 1, 1)) & (clean['d'] <= (dt.date(year, 12, 31) if year < 2026 else TO))]
        embargo = trading[tpos[min(test['d'])] - 1]
        train = clean[clean['d'] < embargo]
        _, model = mlr.fit_predict(train, test.iloc[:1], cols)
        models[year] = model
        print(f'  model {year}: train {len(train)} rows (to {embargo})', flush=True)
    oracle = b[(b['d'] >= FROM) & (b['d'] <= TO)].copy()
    return models, cols, oracle


def load_intraday(c, ids):
    lo = dt.datetime.combine(FROM - dt.timedelta(days=10), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    hi = dt.datetime.combine(mlr.LAST_EXIT + dt.timedelta(days=1), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    rows = {}
    ids = sorted(ids)
    for k in range(0, len(ids), 150):
        for r in c.execute(
                """with b as (select stock_id, start_time, open_price, high_price, low_price, close_price, volume, trading_value,
                                     (start_time at time zone 'Asia/Seoul') lt
                                from intraday_research_bar where stock_id = any(%s) and start_time >= %s and start_time < %s)
                   select stock_id, lt::date, min(lt::time), (array_agg(open_price order by start_time))[1],
                          max(close_price) filter (where lt::time = '15:15'),
                          max(high_price) filter (where lt::time <= '15:15'), min(low_price) filter (where lt::time <= '15:15'),
                          sum(trading_value) filter (where lt::time <= '15:15'), sum(volume) filter (where lt::time <= '15:15'),
                          max(lt::time), (array_agg(close_price order by start_time desc))[1]
                     from b group by stock_id, lt::date""", (ids[k:k + 150], lo, hi)):
            rows[(r[0], r[1])] = r[2:]
        print(f'  intraday {min(k + 150, len(ids))}/{len(ids)} stocks', flush=True)
    return rows


def arrays(df):
    out = {}
    for sid, g in df.groupby('sid', sort=False):
        out[sid] = {'d': list(g['d']), 'o': g['o'].to_numpy(), 'h': g['h'].to_numpy(), 'l': g['l'].to_numpy(),
                    'c': g['c'].to_numpy(), 'v': g['v'].to_numpy(), 'val': g['val'].to_numpy()}
        out[sid]['pos'] = {d: k for k, d in enumerate(out[sid]['d'])}
    return out


def clv(o, h, l, c):
    return ((c - l) - (h - c)) / (h - l) if h > l else 0.0


def features(a, i, O, H, L, P, V, VAL):
    """Study-B features at d with today's bar (O, H, L, P, V, VAL) replacing the close of d; i = position of d-1."""
    c, h, l, v, val, o = a['c'], a['h'], a['l'], a['v'], a['val'], a['o']
    prev = c[i]
    rng = H - L
    f = {'RET1': P / prev - 1, 'LOC': (P - L) / rng if rng > 0 else 0.5, 'GAP': O / prev - 1, 'RANGE': rng / prev,
         'UPPER': (H - max(O, P)) / rng if rng > 0 else 0.0, 'LOGVAL': math.log(max(VAL, 1))}
    f['VRATIO'] = VAL / val[i - 19:i + 1].mean()
    f['R5'], f['R20'] = P / c[i - 4] - 1, P / c[i - 19] - 1
    f['R60'] = P / c[i - 59] - 1 if i >= 59 else np.nan
    rets = [c[j] / c[j - 1] - 1 for j in range(i - 18, i + 1)] + [f['RET1']]
    f['VOL20'] = statistics.stdev(rets)
    f['MA20GAP'] = P / ((c[i - 18:i + 1].sum() + P) / 20) - 1
    f['MA60GAP'] = P / ((c[i - 58:i + 1].sum() + P) / 60) - 1 if i >= 58 else np.nan
    f['HIGH52'] = P / max(h[max(0, i - 248):i + 1].max(), H) if i + 1 >= 59 else np.nan
    hi20, lo20 = max(h[i - 18:i + 1].max(), H), min(l[i - 18:i + 1].min(), L)
    f['FLAT'] = float(abs(f['R20']) <= 0.05 and lo20 > 0 and hi20 / lo20 - 1 <= 0.20)
    num = sum(clv(o[j], h[j], l[j], c[j]) * v[j] for j in range(i - 18, i + 1)) + clv(O, H, L, P) * V
    den = v[i - 18:i + 1].sum() + V
    f['CMF20'] = num / den if den > 0 else np.nan
    before = val[i - 78:i - 18].mean() if i >= 78 else np.nan
    f['VOLEXP'] = ((val[i - 18:i + 1].sum() + VAL) / 20) / before if before and before > 0 else np.nan
    wicks = [(min(o[j], c[j]) - l[j]) / (h[j] - l[j]) for j in range(i - 18, i + 1) if h[j] > l[j]]
    if rng > 0:
        wicks.append((min(O, P) - L) / rng)
    f['WICK20'] = sum(wicks) / len(wicks) if len(wicks) >= 15 else np.nan
    f['AVGVAL20'] = (val[i - 18:i + 1].sum() + VAL) / 20
    return f


def build(c, df, trading, codes):
    arr = arrays(df)
    del df
    intra = load_intraday(c, arr.keys())
    days = sorted({d for _, d in intra})
    prv, nxt = dict(zip(days[1:], days)), dict(zip(days, days[1:]))
    tpos = {d: k for k, d in enumerate(trading)}
    rows = []
    skipped = defaultdict(int)
    for (sid, d), r in intra.items():
        if d < FROM or d > TO or d in mlr.SPECIAL or d not in nxt or nxt[d] in mlr.SPECIAL or d not in prv:
            continue
        first_t, O, P, H, L, VAL, V, last_t, last_close = r
        if None in (O, P, H, L, VAL, V, last_close) or str(last_t) != '15:25:00':
            skipped['today incomplete'] += 1; continue
        n1 = intra.get((sid, nxt[d]))
        if not n1 or str(n1[0]) != '09:00:00' or not n1[1]:
            skipped['no next open'] += 1; continue
        p1 = intra.get((sid, prv[d]))
        a = arr.get(sid)
        if not p1 or not p1[8] or a is None or d not in tpos:
            skipped['no previous day'] += 1; continue
        dprev = trading[tpos[d] - 1]
        i = a['pos'].get(dprev)
        if i is None or i < 78:
            skipped['short daily history'] += 1; continue
        k = a['c'][i] / float(p1[8])
        O, H, L, P = (float(x) * k for x in (O, H, L, P))
        f = features(a, i, O, H, L, P, float(V) / k, float(VAL))
        if f['AVGVAL20'] < mlr.MIN_VALUE or f['RET1'] >= 0.29:
            continue
        entry, p_raw, exit_ = float(last_close), float(r[2]), float(n1[1])
        f.update(sid=sid, d=d, entry=entry, p_raw=p_raw, exit=exit_)
        rows.append(f)
    x = pd.DataFrame(rows)
    x['MKT_RET'] = x.groupby('d')['RET1'].transform('mean')
    x['MKT_UP'] = x.groupby('d')['RET1'].transform(lambda s: (s > 0).mean())
    x['RANK_RET'] = x.groupby('d')['RET1'].rank(pct=True)
    x['RANK_VAL'] = x.groupby('d')['LOGVAL'].rank(pct=True)
    x = mlr.add_dart(x.reset_index(drop=True), mlr.dart_index(c, codes))
    x['y'] = mlr.net(x['entry'], x['exit'])
    x['y_p'] = mlr.net(x['p_raw'], x['exit'])
    x['outlier'] = (x['exit'] / x['entry'] - 1).abs() > mlr.OUTLIER
    print(f'feasible rows {len(x)}, outliers {int(x["outlier"].sum())}, skipped {dict(skipped)}', flush=True)
    return x[~x['outlier']]


def top5(frame, col):
    rows = []
    for d, g in frame.groupby('d'):
        if len(g) < 20:
            continue
        g = g.sort_values(['pred', 'sid'], ascending=[False, True])
        rows.append({'d': d, 'top5': g[col].iloc[:mlr.TOP].mean(), 'all': g[col].mean(),
                     'ic': g['pred'].rank().corr(g[col].rank()), 'n': len(g)})
    return pd.DataFrame(rows).sort_values('d')


def show(name, t, verdict):
    ci = block_ci(list(t['top5']), 5, random.Random(SEED))
    ex = block_ci(list(t['top5'] - t['all']), 5, random.Random(SEED))
    first, second = t[t['d'] <= HALF]['top5'].mean(), t[t['d'] > HALF]['top5'].mean()
    print(f"\n-- {name}: days {len(t)}, universe/day {t['n'].mean():.0f}")
    print(f'   TOP5 Net {ci[0]:+.3f} [{ci[1]:+.3f}, {ci[2]:+.3f}] %  | halves {first:+.3f} / {second:+.3f}')
    print(f"   TOP5 - ALL {ex[0]:+.3f} [{ex[1]:+.3f}, {ex[2]:+.3f}] %p | ALL {t['all'].mean():+.3f} | IC {t['ic'].mean():+.4f}")
    if verdict:
        keep = ci[0] > 0 and ci[1] > 0 and first > 0 and second > 0
        print(f"   -> {'KEEP' if keep else 'NOT KEEP'}")


def main():
    c = connect()
    df, trading, codes = mlr.load_daily(c)
    models, cols, oracle = train_models(trading)
    x = build(c, df, trading, codes)
    del df
    x['pred'] = np.nan
    for year, model in models.items():
        mask = x['d'].map(lambda d: d.year == year)
        x.loc[mask, 'pred'] = model.predict(x.loc[mask, cols].astype('float32'))
    print('\n===== Feasible: inputs from bars up to 15:15, buy at the closing auction, sell at the next 09:00 open')
    show('primary (closing auction entry)', top5(x, 'y'), True)
    show('reference: buy at the 15:15 bar close instead', top5(x, 'y_p'), False)
    oracle = oracle[~oracle['outlier']].copy()
    oracle['pred'] = np.nan
    for year, model in models.items():
        mask = oracle['d'].map(lambda d: d.year == year)
        oracle.loc[mask, 'pred'] = model.predict(oracle.loc[mask, cols].astype('float32'))
    keys = set(zip(x['sid'], x['d']))
    same = oracle[[k in keys for k in zip(oracle['sid'], oracle['d'])]]
    print('\n===== Reference: knowing the final close (study-B inputs and entry) on the same stock-days')
    show(f'knowing the close ({len(same)} rows)', top5(same, 'y'), False)


if __name__ == '__main__':
    main()
