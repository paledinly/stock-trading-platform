"""Foreign / institution net buying and short holds from the open (README.md), pre-registered 2026-11-05.

Committed with the README before the first run. Implementation details fixed here before running:
  Signals, static universe, daily loading and the bootstrap procedure come from
    ../2026-11-05-investor-flow-preregistration/flow_study.py (flows only through sealed.py).
  Zero-volume daily rows are dropped right after loading, before any per-stock statistic or signal.
  Per-stock statistics use the stock's own previous traded rows. Exit = close of the stock's (h-1)-th traded row after
    d; late = that date is not the (h-1)-th market trading day after d.
  Ranking: rows in stock-id order, rank(method='first'); Q5 = percentile > 0.8, Q1 = percentile <= 0.2; top 20 =
    the 20 strongest in the buy direction (ties: lower id first).
Run: python flow_horizons.py --stage discovery | test   (reads the repository .env, never prints credentials)
"""
import argparse
import datetime as dt
import json
import random
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent / '2026-11-05-investor-flow-preregistration'))
import flow_study as fs  # noqa: E402
from sealed import DISCOVERY_END, TEST_END, committed, load_flows  # noqa: E402

PREREG = 'audit/2026-11-05-investor-flow-horizons/README.md'
DISCOVERY_JSON = HERE / 'result_discovery.json'
DISCOVERY_FROM, TEST_FROM = dt.date(2018, 4, 1), dt.date(2024, 11, 1)
TEST_HALF = dt.date(2025, 10, 31)
HORIZONS = (1, 3, 5, 10, 20)
MIN_ROWS, MIN_VALUE, MAX_GAP, MIN_STOCKS, TOP = 80, 1e9, 0.29, 100, 20
CELLS = [(name, h) for name in fs.HYPOTHESES for h in HORIZONS]
HOLM_M = len(CELLS)


def boot(series, block):
    """(mean, 2.5%, 97.5%, one-sided p) with circular date blocks of `block` days."""
    n = len(series)
    rng = random.Random(fs.SEED)
    draws = []
    for _ in range(fs.DRAWS):
        sample = []
        while len(sample) < n:
            start = rng.randrange(n)
            sample.extend(series[(start + k) % n] for k in range(block))
        draws.append(sum(sample[:n]) / n)
    draws.sort()
    return (sum(series) / n, draws[int(fs.DRAWS * 0.025)], draws[int(fs.DRAWS * 0.975) - 1],
            sum(1 for x in draws if x <= 0) / fs.DRAWS)


def stock_stats(df, trading):
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    df['n_prev'] = g.cumcount()
    df['val20'] = g['val'].transform(lambda s: s.shift(1).rolling(20).mean())
    df['gap'] = df['o'] / prev_c - 1
    pos = {d: i for i, d in enumerate(trading)}
    df['pos'] = df['d'].map(pos)
    for h in HORIZONS:
        df[f'xc{h}'] = g['c'].shift(-(h - 1))
        df[f'xd{h}'] = g['d'].shift(-(h - 1))
        df[f'xp{h}'] = g['pos'].shift(-(h - 1))
    return df


def evaluate(frame, h, direction):
    f = frame.copy()
    f['net'] = fs.net(f['o'], f[f'xc{h}'])
    f['p'] = f.groupby('d')['sig'].rank(method='first', pct=True)
    strength = f['sig'] if direction > 0 else -f['sig']
    f['k'] = strength.groupby(f['d']).rank(method='first', ascending=False)
    leg = (f['p'] > 0.8) if direction > 0 else (f['p'] <= 0.2)
    g = f.groupby('d')
    days = pd.DataFrame({
        'all': g['net'].mean(),
        'q5': f[f['p'] > 0.8].groupby('d')['net'].mean(),
        'q1': f[f['p'] <= 0.2].groupby('d')['net'].mean(),
        'long': f[leg].groupby('d')['net'].mean(),
        'top': f[f['k'] <= TOP].groupby('d')['net'].mean(),
        'n': g.size(),
    }).sort_index()
    days['ls'] = days['q5'] - days['q1']
    days['excess'] = days['long'] - days['all']
    extra = {'late': int((f[f'xp{h}'] - f['pos'] != h - 1).sum()), 'big': int((f['net'].abs() > 35).sum())}
    return days, extra


def holm(pvals):
    items = sorted(pvals.items(), key=lambda x: x[1]) + [(None, 1.0)] * (HOLM_M - len(pvals))
    adj, running = {}, 0.0
    for i, (key, p) in enumerate(items):
        running = max(running, min(1.0, (HOLM_M - i) * p))
        if key is not None:
            adj[key] = running
    return adj


def fmt(stat):
    m, lo, hi, p = stat
    return f'{m:+.3f} [{lo:+.3f}, {hi:+.3f}] p={p:.4f}'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stage', choices=('discovery', 'test'), required=True)
    stage = ap.parse_args().stage
    if stage == 'test':
        rel = DISCOVERY_JSON.relative_to(ROOT).as_posix()
        if not committed(rel):
            raise SystemExit(f'{rel} must be committed before the test stage (README.md > 두 단계)')
        disc = json.loads(DISCOVERY_JSON.read_text(encoding='utf-8'))
        cells = [(n, h) for n, h in CELLS if disc[f'{n}/{h}']['pass']]
        if not cells:
            raise SystemExit('no cell passed discovery: the test window stays sealed')
        lo, hi = TEST_FROM, TEST_END
    else:
        disc, cells, lo, hi = None, CELLS, DISCOVERY_FROM, DISCOVERY_END

    c = fs.connect()
    ids = fs.static_ids(c)
    df, _ = fs.load_daily(c, ids, hi + dt.timedelta(days=45))
    df = df[df['v'] > 0].reset_index(drop=True)
    trading = sorted(d for d, n in df.groupby('d').size().items() if n >= 500)
    df = df[df['d'].isin(set(trading))].reset_index(drop=True)
    df = stock_stats(df, trading)
    flows = load_flows(c, prereg=PREREG if stage == 'test' else None,
                       columns=('stock_id', 'trade_date', 'foreign_amount', 'institution_amount'))
    flows = flows[flows['stock_id'].isin(ids)]
    sigs = fs.signals(df, flows, trading)
    del flows

    base = ((df['d'] >= lo) & (df['d'] <= hi) & (df['n_prev'] >= MIN_ROWS) & (df['val20'] >= MIN_VALUE)
            & (df['gap'] < MAX_GAP))
    out = [f'investor flow horizons, stage {stage}: signal days {lo}..{hi}, exits <= {hi}', '']
    result, pvals = {}, {}
    for name, h in cells:
        ok = base & df[f'xc{h}'].notna() & (df[f'xd{h}'] <= hi)
        frame = df[ok].copy()
        frame['sig'] = sigs[name][ok.to_numpy()]
        frame = frame[frame['sig'].notna()]
        counts = frame.groupby('d').size()
        frame = frame[frame['d'].isin(set(counts[counts >= MIN_STOCKS].index))]
        block = max(5, h)
        if stage == 'discovery':
            probe, _ = evaluate(frame, h, +1)
            direction = 1 if probe['ls'].mean() >= 0 else -1
        else:
            direction = disc[f'{name}/{h}']['direction']
        days, extra = evaluate(frame, h, direction)
        ex = boot(days['excess'].tolist(), block)
        lng = boot(days['long'].tolist(), block)
        yearly = days.groupby(days.index.map(lambda d: d.year))[['excess', 'long']].mean()
        r = {'direction': direction, 'days': len(days), 'stocks_per_day': round(float(days['n'].mean()), 1),
             'excess': [round(x, 4) for x in ex], 'net': [round(x, 4) for x in lng]}
        out += [f'== {name} h={h} (direction {"+" if direction > 0 else "-"}, buy side {"Q5" if direction > 0 else "Q1"}) ==',
                f'days {len(days)}, stocks/day {days["n"].mean():.0f}, late exits {extra["late"]}, |net| > 35%: {extra["big"]}',
                f'PRIMARY buy-side excess   {fmt(ex)}',
                f'buy-side Net              {fmt(lng)}',
                f'long-short Q5-Q1          {fmt(boot(days["ls"].tolist(), block))}',
                f'Q5 excess {(days["q5"] - days["all"]).mean():+.3f}, Q1 excess {(days["q1"] - days["all"]).mean():+.3f}, '
                f'universe Net {days["all"].mean():+.3f}, top {TOP} Net {days["top"].mean():+.3f}',
                'by year excess/Net: ' + ', '.join(f'{y} {e:+.3f}/{n:+.3f}' for y, (e, n) in yearly.iterrows())]
        if stage == 'discovery':
            positive = int((yearly['excess'] > 0).sum())
            r['pass'] = bool(ex[0] > 0 and ex[1] > 0 and positive >= 5 and lng[0] > 0)
            r['years_positive'] = positive
            out.append(f'DISCOVERY {"PASS" if r["pass"] else "FAIL"} (excess > 0, CI low > 0, years {positive}/7 >= 5, '
                       f'Net > 0)')
        else:
            h1 = days[days.index <= TEST_HALF]['excess'].mean()
            h2 = days[days.index > TEST_HALF]['excess'].mean()
            r['halves'] = [round(h1, 4), round(h2, 4)]
            pvals[f'{name}/{h}'] = ex[3]
            out.append(f'halves {h1:+.3f} / {h2:+.3f}')
        result[f'{name}/{h}'] = r
        out.append('')

    if stage == 'test':
        adj = holm(pvals)
        for key, r in result.items():
            r['holm_p'] = round(adj[key], 4)
            r['verdict'] = ('VERIFIED' if r['excess'][0] > 0 and adj[key] < 0.05 and min(r['halves']) > 0
                            and r['net'][0] > 0 else 'NOT VERIFIED')
            out.append(f'{key}: Holm p {adj[key]:.4f} (m = {HOLM_M}) -> {r["verdict"]}')
    else:
        out.append('summary: ' + ', '.join(f'{k} {"PASS" if r["pass"] else "fail"}' for k, r in result.items()))
    text = '\n'.join(out) + '\n'
    print(text)
    (HERE / f'result_{stage}.txt').write_text(text, encoding='utf-8', newline='\n')
    (HERE / f'result_{stage}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n',
                                               encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
