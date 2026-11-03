"""Step 2 of the subscription replay: compare candidate-selection methods on one year of history (README.md).

Pre-registered in README.md (committed with this script before the first run). For every signal day the script
rebuilds the candidate set of each method, then scores the feasible ML model (../2026-11-03-ml-feasibility-
preregistration) inside that set and buys its top 5 at the 15:15 bar close, selling at the next 09:00 open (BASE costs).
Run: python simulate.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import pickle
import random
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-11-03-ml-feasibility-preregistration'))
import feasibility as fz  # noqa: E402
from replay_common import broad_scores, connect, load, rankings  # noqa: E402
from volatility import block_ci  # noqa: E402

FROM, TO = dt.date(2025, 11, 3), dt.date(2026, 10, 21)
HALF = dt.date(2026, 4, 30)
SEED = 20261103
CAPACITY, MIN_HOLD, MARGIN, FREEZE = 41, 3, 10.0, 69     # min hold 15 min = 3 points of 5 min; freeze at 14:50 (p=69)
QUOTE_BUDGET, SCAN_LIMIT, SHORTLIST = 45, 120, 41
SCAN_POINTS = list(range(1, 60, 2)) + list(range(59, 70))  # 09:10..14:00 every 10 minutes, then every 5 minutes
PREMARKET_SIZE = 300


def predictions():
    """Feasible ML predictions for every feasible (stock, day), cached."""
    path = HERE / '.cache' / 'preds.pkl'
    if path.exists():
        return pd.read_pickle(path)
    c = connect()
    df, trading, codes = fz.mlr.load_daily(c)
    models, cols, _ = fz.train_models(trading)
    x = fz.build(c, df, trading, codes)
    del df
    x['pred'] = np.nan
    for year, model in models.items():
        mask = x['d'].map(lambda d: d.year == year)
        x.loc[mask, 'pred'] = model.predict(x.loc[mask, cols].astype('float32'))
    out = x[['sid', 'd', 'pred', 'y', 'y_p']].copy()
    out.to_pickle(path)
    return out


def allocate(data, rows, per_market):
    """Replays the Broad scan + precision allocator for one day. Returns the allocation at the freeze and the Broad
    scores seen at the last scan."""
    alloc = {}            # row index -> [score, added point]
    last_scores = {}
    for p in SCAN_POINTS:
        ranks = rankings(data, rows, p, per_market)
        raw, _ = broad_scores(data, rows, p, ranks, set())
        ordered = sorted(raw, key=lambda i: (-raw[i], int(data['sid'][i])))[:SCAN_LIMIT]
        quote_ok = set(ordered[:QUOTE_BUDGET]) | (set(ordered) & set(alloc))
        _, scores = broad_scores(data, rows, p, {t: [(i, r) for i, r in v if i in set(ordered)] for t, v in ranks.items()},
                                 quote_ok)
        complete = sorted(((s, i) for i, s in scores.items() if s is not None), key=lambda x: (-x[0], int(data['sid'][x[1]])))
        shortlist = complete[:SHORTLIST]
        for s, i in shortlist:
            if i in alloc:
                alloc[i][0] = s
        last_scores = {i: s for s, i in complete}
        if p >= FREEZE:                                         # frozen from 14:50: scores update, no changes
            break
        for s, i in shortlist:                                  # fill
            if len(alloc) >= CAPACITY:
                break
            if i not in alloc:
                alloc[i] = [s, p]
        if len(alloc) >= CAPACITY:                              # one replacement per scan
            held = [(v[0], v[1], i) for i, v in alloc.items() if p - v[1] >= MIN_HOLD]
            if held:
                weakest = min(held)
                for s, i in shortlist:
                    if i not in alloc and s - weakest[0] >= MARGIN:
                        del alloc[weakest[2]]
                        alloc[i] = [s, p]
                        break
    return alloc, last_scores


def premarket(c, data, day_rows, days):
    """Top PREMARKET_SIZE tradable stocks by the share of up-gap days over the 20 days before d (20-day mean trading
    value >= 1bn KRW), from adjusted daily bars."""
    lists = {}
    raw = defaultdict(list)
    for s, d, o, cl, val in c.execute(
            """select stock_id, trade_date, open_price, close_price, trading_value from daily_adjusted_candle
                where trade_date between '2025-09-01' and '2026-10-21' and open_price > 0 and close_price > 0
                order by stock_id, trade_date"""):
        raw[s].append((d.toordinal(), float(o), float(cl), float(val)))
    stats = defaultdict(dict)     # day -> sid -> (win20, value20) known before the open of day
    for s, bars in raw.items():
        for k in range(21, len(bars)):
            window = bars[k - 20:k]
            gaps = [window[j][1] / (bars[k - 20 + j - 1][2]) - 1 > 0 for j in range(20)]
            stats[bars[k][0]][s] = (sum(gaps) / 20, sum(b[3] for b in window) / 20)
    for d in days:
        rows = day_rows.get(d, [])
        cand = [(stats[d][int(data['sid'][i])][0], -int(data['sid'][i]), i) for i in rows
                if int(data['sid'][i]) in stats[d] and stats[d][int(data['sid'][i])][1] >= 1e9]
        lists[d] = {i for _, _, i in sorted(cand, reverse=True)[:PREMARKET_SIZE]}
    return lists


def main():
    preds = predictions()
    c = connect()
    data = load(c)
    tradable = {s for s, m in data['meta'].items() if m[3]}
    day_rows = defaultdict(list)
    for i, (s, d) in enumerate(zip(data['sid'], data['day'])):
        if int(s) in tradable:
            day_rows[int(d)].append(i)
    days = [d for d in sorted(day_rows) if FROM.toordinal() <= d <= TO.toordinal()]
    pm = premarket(c, data, day_rows, days)
    pred_by = {(int(s), d.toordinal()): (p, y) for s, d, p, y in zip(preds['sid'], preds['d'], preds['pred'], preds['y_p'])}
    records = []
    for n, d in enumerate(days):
        rows = np.array(day_rows[d])
        feasible = [(pred_by[(int(data['sid'][i]), d)], int(data['sid'][i]), i) for i in rows
                    if (int(data['sid'][i]), d) in pred_by]
        if len(feasible) < 20:
            continue
        all_set = {i for _, _, i in feasible}
        top_all = [i for _, _, i in sorted(feasible, key=lambda x: (-x[0][0], x[1]))[:5]]
        sets, broad_pick = {}, {}
        for name, per_market in (('CURRENT', False), ('EXPANDED', True)):
            alloc, scores = allocate(data, rows, per_market)
            sets[name] = set(alloc)
            final = {i: (scores[i] if scores.get(i) is not None else alloc[i][0]) for i in alloc}
            broad_pick[name] = sorted(final, key=lambda i: (-final[i], int(data['sid'][i])))
        sets['PREMARKET'] = pm.get(d, set())
        sets['ALL'] = all_set
        rec = {'d': d}
        lookup = {i: v for v, _, i in feasible}
        for name, members in sets.items():
            inside = sorted(((lookup[i], int(data['sid'][i]), i) for i in members if i in lookup), key=lambda x: (-x[0][0], x[1]))
            rec[f'{name}_n'] = len(members)
            rec[f'{name}_eval'] = len(inside)
            rec[f'{name}_top5'] = np.mean([v[1] for v, _, _ in inside[:5]]) if len(inside) >= 5 else np.nan
            rec[f'{name}_mean'] = np.mean([v[1] for v, _, _ in inside]) if inside else np.nan
            rec[f'{name}_cover'] = len(set(top_all) & members) / 5
        for name in ('CURRENT', 'EXPANDED'):
            picks = [i for i in broad_pick[name] if i in lookup][:5]
            rec[f'{name}_broad5'] = np.mean([lookup[i][1] for i in picks]) if len(picks) == 5 else np.nan
        records.append(rec)
        if n % 20 == 0:
            print(f'  {dt.date.fromordinal(d)} done ({n + 1}/{len(days)})', flush=True)
    t = pd.DataFrame(records)
    t.to_csv(HERE / 'simulate_days.csv', index=False)
    report(t)


def show(label, series):
    s = series.dropna()
    ci = block_ci(list(s), 5, random.Random(SEED))
    return f'{label} {ci[0]:+.3f} [{ci[1]:+.3f}, {ci[2]:+.3f}]'


def report(t):
    t = t.dropna(subset=['CURRENT_top5', 'EXPANDED_top5', 'PREMARKET_top5', 'ALL_top5'])
    half = HALF.toordinal()
    print(f'\ndays {len(t)}')
    print('method     set size  evaluable  covers ALL-top5  ML top5 Net %                set mean Net %')
    for m in ('CURRENT', 'EXPANDED', 'PREMARKET', 'ALL'):
        print(f"{m:10s} {t[m + '_n'].mean():8.0f}  {t[m + '_eval'].mean():9.0f}  {t[m + '_cover'].mean() * 100:14.0f}%  "
              f"{show('', t[m + '_top5'])}   {t[m + '_mean'].mean():+.3f}")
        first, second = t[t['d'] <= half][m + '_top5'].mean(), t[t['d'] > half][m + '_top5'].mean()
        print(f'{"":10s} halves {first:+.3f} / {second:+.3f}')
    for m in ('CURRENT', 'EXPANDED'):
        print(f"{m:10s} top-5 by the current Broad score: {show('', t[m + '_broad5'])}")
    print('\npaired differences (ML top5 Net, %p):')
    verdict = {}
    for m in ('EXPANDED', 'PREMARKET', 'ALL'):
        diff = t[m + '_top5'] - t['CURRENT_top5']
        ci = block_ci(list(diff), 5, random.Random(SEED))
        first, second = diff[t['d'] <= half].mean(), diff[t['d'] > half].mean()
        better = ci[0] > 0 and ci[1] > 0 and first > 0 and second > 0
        verdict[m] = better
        print(f'   {m:10s} - CURRENT {ci[0]:+.3f} [{ci[1]:+.3f}, {ci[2]:+.3f}]  halves {first:+.3f} / {second:+.3f}'
              f"  -> {'BETTER' if better else 'not shown better'}")


if __name__ == '__main__':
    main()
