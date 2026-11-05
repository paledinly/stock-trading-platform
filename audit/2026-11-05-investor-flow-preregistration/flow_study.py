"""Foreign / institution net buying and the next open (README.md), pre-registered 2026-11-05.

Committed with the README before the first run. Implementation details fixed here before running:
  Flows are read only through ../2026-11-03-investor-flow-seal/sealed.py (the test stage passes this README).
  Trading days: days with daily candles for at least 500 static-attribute stocks. Flow windows run over these days.
  Per-stock statistics (80 previous rows, 20-row mean trading value, d return, 5-day return, 20-row gap-up share,
    exit) use the stock's own previous daily rows, as in ../2026-11-03-point-in-time-replay/pit_replay.py.
  Ranking: rows sorted by stock id, then rank(method='first'), so equal signals rank in id order.
  Q5 = rank percentile > 0.8, Q1 = rank percentile <= 0.2.
  Residual ranking (report): per day, OLS of the signal rank on the 5-day-return rank; quintiles of the residual.
  Pre-market list (report): per day, the 300 universe rows with the highest 20-row gap-up share (ties: lower id first).
  Bootstrap: circular blocks of 5 days, 10,000 draws, random.Random(20261103) per series; one-sided p = share of draw
    means <= 0.
Run: python flow_study.py --stage discovery | test   (reads the repository .env, never prints credentials)
"""
import argparse
import datetime as dt
import json
import random
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent / '2026-11-03-investor-flow-seal'))
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
from sealed import DISCOVERY_END, TEST_END, committed, load_flows  # noqa: E402
from volatility import connect, share  # noqa: E402

PREREG = 'audit/2026-11-05-investor-flow-preregistration/README.md'
DISCOVERY_JSON = HERE / 'result_discovery.json'
DISCOVERY_FROM, TEST_FROM = dt.date(2018, 4, 1), dt.date(2024, 11, 1)
TEST_HALF = dt.date(2025, 10, 31)
SEED, DRAWS, BLOCK = 20261103, 10_000, 5
BUY, SELL = 0.00095, 0.00295
STRUCTURED = ('인버스', '레버리지', '선물', 'ETN', 'ETF', '2X')
MIN_ROWS, MIN_VALUE, MAX_RET, MIN_STOCKS, LIST_SIZE = 80, 1e9, 0.29, 100, 300
HYPOTHESES = {  # name: (flow columns, window in trading days)
    'FOR5': (('foreign_amount',), 5),
    'INS5': (('institution_amount',), 5),
    'FI20': (('foreign_amount', 'institution_amount'), 20),
    'FI1': (('foreign_amount', 'institution_amount'), 1),
}
HOLM_M = len(HYPOTHESES)


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def boot(series):
    """(mean, 2.5%, 97.5%, one-sided p) of a day series in date order."""
    n = len(series)
    rng = random.Random(SEED)
    draws = []
    for _ in range(DRAWS):
        sample = []
        while len(sample) < n:
            start = rng.randrange(n)
            sample.extend(series[(start + k) % n] for k in range(BLOCK))
        draws.append(sum(sample[:n]) / n)
    draws.sort()
    return (sum(series) / n, draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1],
            sum(1 for x in draws if x <= 0) / DRAWS)


def static_ids(c):
    ids = set()
    for s, code, name, etf, etn in c.execute('select id, stock_code, stock_name, is_etf, is_etn from stock'):
        upper = (name or '').upper()
        if share(code, name) and not etf and not etn and not any(x in upper for x in STRUCTURED):
            ids.add(s)
    return ids


def load_daily(c, ids, end):
    cols = {k: [] for k in ('sid', 'd', 'o', 'c', 'v', 'val')}
    with c.cursor(name='daily') as cur:
        cur.itersize = 100_000
        cur.execute("""select stock_id, trade_date, open_price, close_price, volume, trading_value
                         from daily_adjusted_candle where open_price > 0 and close_price > 0 and trade_date <= %s
                        order by stock_id, trade_date""", (end,))
        for sid, d, o, cl, v, tv in cur:
            if sid in ids:
                cols['sid'].append(sid); cols['d'].append(d); cols['o'].append(float(o)); cols['c'].append(float(cl))
                cols['v'].append(float(v or 0)); cols['val'].append(float(tv or 0))
    df = pd.DataFrame(cols)
    trading = sorted(d for d, n in df.groupby('d').size().items() if n >= 500)
    df = df[df['d'].isin(set(trading))].sort_values(['sid', 'd']).reset_index(drop=True)
    return df, trading


def stock_stats(df, trading):
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    df['n_prev'] = g.cumcount()
    df['val20'] = g['val'].transform(lambda s: s.shift(1).rolling(20).mean())
    df['ret_d'] = df['c'] / prev_c - 1
    df['ret5'] = prev_c / g['c'].shift(6) - 1
    gap = (df['o'] / prev_c - 1 > 0).astype(float).where(prev_c.notna())
    df['gap20'] = gap.groupby(df['sid']).transform(lambda s: s.shift(1).rolling(20).mean())
    df['exit_o'] = g['o'].shift(-1)
    df['exit_d'] = g['d'].shift(-1)
    df['net'] = net(df['c'], df['exit_o'])
    nxt = dict(zip(trading, trading[1:]))
    df['late'] = df['exit_d'].notna() & (df['exit_d'] != df['d'].map(nxt))
    return df


def signals(df, flows, trading):
    """{name: Series indexed like df} of net-buy strength over the window ending d-1."""
    dates = pd.Index(trading)
    val = df.pivot(index='d', columns='sid', values='val').reindex(dates).fillna(0.0)
    flows = flows[flows['trade_date'].isin(set(trading)) & flows['stock_id'].isin(set(val.columns))]
    mats = {col: flows.pivot(index='trade_date', columns='stock_id', values=col).astype(float)
            .reindex(index=dates, columns=val.columns) for col in ('foreign_amount', 'institution_amount')}
    key = pd.MultiIndex.from_arrays([df['d'], df['sid']])
    out = {}
    for name, (cols, k) in HYPOTHESES.items():
        amt = sum(mats[col] for col in cols)                      # NaN where any column is missing
        amt_sum = amt.rolling(k, min_periods=k).sum().shift(1)    # NaN if any day of d-k..d-1 is missing
        val_sum = val.rolling(k, min_periods=k).sum().shift(1)
        sig = (amt_sum * 1e6 / val_sum).where(val_sum > 0)
        out[name] = sig.stack().reindex(key).to_numpy()
    return out


def day_rank(frame, col):
    """Rank percentile of `col` within each day; rows are in stock-id order so ties go by id."""
    return frame.groupby('d')[col].rank(method='first', pct=True)


def residual(frame):
    def one(x):
        a, b = x['sr'].to_numpy(), x['zr'].to_numpy()
        b0 = b - b.mean()
        slope = (b0 * (a - a.mean())).sum() / (b0 * b0).sum() if (b0 * b0).sum() > 0 else 0.0
        return pd.Series(a - a.mean() - slope * b0, index=x.index)
    return frame.groupby('d', group_keys=False)[['sr', 'zr']].apply(one)


def evaluate(frame, direction):
    """Day-level series for one hypothesis; frame = universe rows with a signal, sorted by (sid, d)."""
    f = frame.copy()
    f['p'] = day_rank(f, 'sig')
    f['sr'] = f.groupby('d')['sig'].rank(method='first')
    f['zr'] = f.groupby('d')['ret5'].rank(method='first')
    f['res'] = residual(f)
    f['pr'] = day_rank(f, 'res')
    f['in_list'] = f.groupby('d')['gap20'].rank(method='first', ascending=False) <= LIST_SIZE
    f.loc[f['gap20'].isna(), 'in_list'] = False
    lst = f[f['in_list']].copy()
    lst['pl'] = day_rank(lst, 'sig')

    def leg(p):
        return (p > 0.8) if direction > 0 else (p <= 0.2)

    g = f.groupby('d')
    days = pd.DataFrame({
        'all': g['net'].mean(),
        'q5': f[f['p'] > 0.8].groupby('d')['net'].mean(),
        'q1': f[f['p'] <= 0.2].groupby('d')['net'].mean(),
        'long': f[leg(f['p'])].groupby('d')['net'].mean(),
        'long_res': f[leg(f['pr'])].groupby('d')['net'].mean(),
        'list_all': lst.groupby('d')['net'].mean(),
        'list_long': lst[leg(lst['pl'])].groupby('d')['net'].mean(),
        'rho': g.apply(lambda x: x['sr'].corr(x['zr'])),
        'n': g.size(),
    }).sort_index()
    days['ls'] = days['q5'] - days['q1']
    days['excess'] = days['long'] - days['all']
    days['excess_res'] = days['long_res'] - days['all']
    days['excess_list'] = days['list_long'] - days['list_all']
    return days


def fmt(stat):
    m, lo, hi, p = stat
    return f'{m:+.3f} [{lo:+.3f}, {hi:+.3f}] p={p:.4f}'


def holm(pvals):
    """Holm-adjusted p for {name: p} with HOLM_M tests; untested hypotheses count as p = 1."""
    items = sorted(pvals.items(), key=lambda x: x[1]) + [(None, 1.0)] * (HOLM_M - len(pvals))
    adj, running = {}, 0.0
    for i, (name, p) in enumerate(items):
        running = max(running, min(1.0, (HOLM_M - i) * p))
        if name is not None:
            adj[name] = running
    return adj


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stage', choices=('discovery', 'test'), required=True)
    stage = ap.parse_args().stage
    if stage == 'test':
        rel = DISCOVERY_JSON.relative_to(ROOT).as_posix()
        if not committed(rel):
            raise SystemExit(f'{rel} must be committed before the test stage (README.md > 두 단계)')
        disc = json.loads(DISCOVERY_JSON.read_text(encoding='utf-8'))
        names = [n for n in HYPOTHESES if disc[n]['pass']]
        if not names:
            raise SystemExit('no hypothesis passed discovery: the test window stays sealed')
        lo, hi = TEST_FROM, TEST_END
    else:
        disc, names, lo, hi = None, list(HYPOTHESES), DISCOVERY_FROM, DISCOVERY_END

    c = connect()
    ids = static_ids(c)
    df, trading = load_daily(c, ids, hi + dt.timedelta(days=15))
    df = stock_stats(df, trading)
    flows = load_flows(c, prereg=PREREG if stage == 'test' else None,
                       columns=('stock_id', 'trade_date', 'foreign_amount', 'institution_amount'))
    flows = flows[flows['stock_id'].isin(ids)]
    sigs = signals(df, flows, trading)
    del flows

    base = ((df['d'] >= lo) & (df['d'] <= hi) & (df['n_prev'] >= MIN_ROWS) & (df['val20'] >= MIN_VALUE)
            & (df['v'] > 0) & (df['ret_d'] < MAX_RET) & df['exit_o'].notna())
    out = [f'investor flow study, stage {stage}: signal days {lo}..{hi}', '']
    result = {}
    pvals = {}
    for name in names:
        frame = df[base].copy()
        frame['sig'] = sigs[name][base.to_numpy()]
        frame = frame[frame['sig'].notna()]
        counts = frame.groupby('d').size()
        frame = frame[frame['d'].isin(set(counts[counts >= MIN_STOCKS].index))]
        if stage == 'discovery':
            probe = evaluate(frame, +1)
            direction = 1 if probe['ls'].mean() >= 0 else -1
        else:
            direction = disc[name]['direction']
        days = evaluate(frame, direction)
        ex = boot(days['excess'].tolist())
        yearly = days.groupby(days.index.map(lambda d: d.year))['excess'].mean()
        r = {'direction': direction, 'days': len(days), 'stocks_per_day': round(float(days['n'].mean()), 1),
             'excess': [round(x, 4) for x in ex]}
        out += [f'== {name} (direction {"+" if direction > 0 else "-"}, buy side {"Q5" if direction > 0 else "Q1"}) ==',
                f'days {len(days)}, stocks/day {days["n"].mean():.0f}, late exits {int(frame["late"].sum())}, '
                f'|net| > 35%: {int((frame["net"].abs() > 35).sum())}',
                f'PRIMARY buy-side excess   {fmt(ex)}',
                f'long-short Q5-Q1          {fmt(boot(days["ls"].tolist()))}',
                f'Q5 excess {(days["q5"] - days["all"]).mean():+.3f}, Q1 excess {(days["q1"] - days["all"]).mean():+.3f}, '
                f'buy-side Net {days["long"].mean():+.3f}, universe Net {days["all"].mean():+.3f}',
                f'5-day-return residual     {fmt(boot(days["excess_res"].dropna().tolist()))}',
                f'rank corr with 5-day ret  {days["rho"].mean():+.3f}',
                f'pre-market list (300)     {fmt(boot(days["excess_list"].dropna().tolist()))}',
                'by year: ' + ', '.join(f'{y} {v:+.3f}' for y, v in yearly.items())]
        if stage == 'discovery':
            positive = int((yearly > 0).sum())
            r['pass'] = bool(ex[0] > 0 and ex[1] > 0 and positive >= 5)
            r['years_positive'] = positive
            out.append(f'DISCOVERY {"PASS" if r["pass"] else "FAIL"} (mean > 0, CI low > 0, years > 0: {positive}/7 >= 5)')
        else:
            h1 = days[days.index <= TEST_HALF]['excess'].mean()
            h2 = days[days.index > TEST_HALF]['excess'].mean()
            r['halves'] = [round(h1, 4), round(h2, 4)]
            pvals[name] = ex[3]
            out.append(f'halves {h1:+.3f} / {h2:+.3f}')
        result[name] = r
        out.append('')

    if stage == 'test':
        adj = holm(pvals)
        for name in names:
            r = result[name]
            r['holm_p'] = round(adj[name], 4)
            r['verdict'] = ('VERIFIED' if r['excess'][0] > 0 and adj[name] < 0.05 and min(r['halves']) > 0
                            else 'NOT VERIFIED')
            out.append(f'{name}: Holm p {adj[name]:.4f} (m = {HOLM_M}) -> {r["verdict"]}')
    text = '\n'.join(out) + '\n'
    print(text)
    (HERE / f'result_{stage}.txt').write_text(text, encoding='utf-8', newline='\n')
    (HERE / f'result_{stage}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n',
                                               encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
