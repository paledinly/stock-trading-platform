"""Daily top-5 picks of the feasible daily ML model for the last three months before the ADR-010 embargo.

Descriptive listing of days already evaluated by feasibility.py (no new test): signal days 2026-07-22..2026-10-21,
same models, inputs (bars up to 15:15) and entry (closing auction) -> exit (next 09:00 open), BASE costs.
Writes picks_last3m.csv (one row per pick) and prints monthly and cumulative summaries.
Run: python picks_last3m.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import feasibility as fz  # noqa: E402

FROM = dt.date(2026, 7, 22)


def main():
    c = fz.connect()
    df, trading, codes = fz.mlr.load_daily(c)
    models, cols, _ = fz.train_models(trading)
    x = fz.build(c, df, trading, codes)
    del df
    x = x[x['d'] >= FROM].copy()
    x['pred'] = models[2026].predict(x[cols].astype('float32'))
    names = {sid: (code, name) for sid, code, name in c.execute('select id, stock_code, stock_name from stock')}
    nxt = dict(zip(trading, trading[1:]))
    picks, days = [], []
    for d, g in x.groupby('d'):
        if len(g) < 20:
            continue
        g = g.sort_values(['pred', 'sid'], ascending=[False, True]).head(fz.mlr.TOP)
        for rank, r in enumerate(g.itertuples(), 1):
            code, name = names.get(r.sid, ('?', '?'))
            picks.append((d, nxt.get(d), rank, code, name, r.RET1 * 100, r.entry, r.exit, r.y))
        days.append((d, g['y'].mean(), x[x['d'] == d]['y'].mean()))
    out = HERE / 'picks_last3m.csv'
    with open(out, 'w', encoding='utf-8-sig') as fh:
        fh.write('signal_day,sell_day,rank,code,name,ret_at_1515_pct,buy_close,sell_open,net_pct\n')
        for p in picks:
            fh.write(f'{p[0]},{p[1]},{p[2]},{p[3]},{p[4]},{p[5]:.2f},{p[6]:.0f},{p[7]:.0f},{p[8]:.3f}\n')
    print(f'picks: {len(picks)} on {len(days)} days ({days[0][0]}..{days[-1][0]}) -> {out.name}')
    equity, peak, mdd = 1.0, 1.0, 0.0
    monthly = {}
    for d, top, everyone in days:
        equity *= 1 + top / 100
        peak = max(peak, equity); mdd = min(mdd, equity / peak - 1)
        m = monthly.setdefault(d.strftime('%Y-%m'), [0, 1.0, 1.0, 0])
        m[0] += 1; m[1] *= 1 + top / 100; m[2] *= 1 + everyone / 100; m[3] += top > 0
    nets = [p[8] for p in picks]
    print(f'per pick: mean {np.mean(nets):+.3f}%, median {np.median(nets):+.3f}%, win {np.mean([n > 0 for n in nets]) * 100:.1f}%, '
          f'best {max(nets):+.2f}%, worst {min(nets):+.2f}%')
    print(f'per day (5 equal-weight): mean {np.mean([t for _, t, _ in days]):+.3f}%, positive days '
          f'{sum(t > 0 for _, t, _ in days)}/{len(days)}, compounded {(equity - 1) * 100:+.2f}%, max drawdown {mdd * 100:.2f}%')
    print('month   days  model compounded  everyone compounded  positive days')
    for m, (n, eq, eq_all, pos) in monthly.items():
        print(f'{m}  {n:4d}  {(eq - 1) * 100:+8.2f}%  {(eq_all - 1) * 100:+8.2f}%  {pos}/{n}')
    print('last 10 days:')
    for d, top, everyone in days[-10:]:
        names_ = ', '.join(f'{p[4]}({p[8]:+.1f})' for p in picks if p[0] == d)
        print(f'  {d}  top5 {top:+.2f}%  all {everyone:+.2f}%  | {names_}')


if __name__ == '__main__':
    main()
