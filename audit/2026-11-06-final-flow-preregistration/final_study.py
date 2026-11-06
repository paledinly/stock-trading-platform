"""Final investor-flow study with delisted stocks (README.md), pre-registered 2026-11-06.

Committed with the README before the first discovery run. Implementation details fixed here before running:
  Survivors: ../2026-11-05-investor-flow-preregistration/flow_study.py static_ids + load_daily (adjusted daily candles),
    flows through sealed.load_flows. Delisted: sealed.load_delisted (raw prices), codes passing flow_study's share()
    and not already among the survivors' codes; each gets a negative stock id in code order.
  Rows with volume 0 are dropped before any statistic. Trading days = days with at least 500 rows.
  Exit row j = the stock's (H-1)-th traded row after entry row i. If the stock has fewer rows left:
    code in delisted_stock -> its last traded row (DELIST); otherwise, if its last row is before the last trading day
    in the data -> last close x (1 + HAIRCUT) (STUCK); otherwise no exit (row dropped).
  Window: kept when the market's (H-1)-th trading day after d is <= the stage end; a later actual exit (halt) is kept.
  Corporate actions (delisted source only, raw prices): a traded row t is a jump when |c_t / c_t-1 - 1| > JUMP or
    |o_t / c_t-1 - 1| > JUMP, except the stock's last TAIL traded rows (정리매매). A holding with a jump in rows i+1..j
    is dropped and counted.
  COMB = mean of the day rank percentiles of INS5, FI20 and FI1 over rows that have all three.
  Ranking: rows in (sid, d) order, rank(method='first'); Q5 = pct > 0.8, Q1 = pct <= 0.2.
  Sector group = stock.sector_medium_code, else sector_large_code, else 'NA' (delisted stocks are 'NA').
  Bootstrap: circular date blocks of BLOCK days, 10,000 draws, random.Random(20261103) per series.
Run: python final_study.py --stage check | discovery | test   (reads the repository .env, never prints credentials)
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
sys.path.insert(0, str(HERE.parent / '2026-11-05-investor-flow-preregistration'))
import flow_study as fs  # noqa: E402
from sealed import DISCOVERY_END, TEST_END, committed, load_delisted, load_flows  # noqa: E402

PREREG = 'audit/2026-11-06-final-flow-preregistration/README.md'
DISCOVERY_JSON = HERE / 'result_discovery.json'
DISCOVERY_FROM, TEST_FROM = dt.date(2018, 4, 1), dt.date(2024, 11, 1)
TEST_HALF = dt.date(2025, 10, 31)
H, BLOCK, TOP = 20, 20, 20
MIN_ROWS, MIN_VALUE, MAX_GAP, MIN_STOCKS = 80, 1e9, 0.29, 100
JUMP, TAIL, HAIRCUT, STRESS = 0.31, 10, -0.5, (0.0, -1.0)
HIGH_BUY, HIGH_SELL = 0.00165, 0.00365
SIGNALS = ('INS5', 'FI20', 'FI1', 'COMB')
HOLM_M = len(SIGNALS)


def boot(series, block=BLOCK):
    """(mean, 2.5%, 97.5%, one-sided p) with circular date blocks."""
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


def fmt(stat):
    m, lo, hi, p = stat
    return f'{m:+.3f} [{lo:+.3f}, {hi:+.3f}] p={p:.4f}'


def net_high(entry, exit_):
    return (exit_ * (1 - HIGH_SELL) - entry * (1 + HIGH_BUY)) / (entry * (1 + HIGH_BUY)) * 100


def load(c, stage):
    prereg = PREREG if stage == 'test' else None
    ids = fs.static_ids(c)
    df, _ = fs.load_daily(c, ids, dt.date(2100, 1, 1))
    codes = dict(c.execute('select id, stock_code from stock').fetchall())
    sectors = {sid: (m or l or 'NA') for sid, l, m in
               c.execute('select id, sector_large_code, sector_medium_code from stock').fetchall()}
    survivor_codes = {codes[s] for s in df['sid'].unique()}
    meta = pd.DataFrame(c.execute('select stock_code, stock_name, delisted_date, reason from delisted_stock').fetchall(),
                        columns=['code', 'name', 'delisted', 'reason'])
    delisted_codes = set(meta['code'])
    keep = sorted(code for code, name in zip(meta['code'], meta['name'])
                  if fs.share(code, name) and code not in survivor_codes)
    sid_of = {code: -(i + 1) for i, code in enumerate(keep)}
    raw = load_delisted(c, prereg)
    raw = raw[raw['stock_code'].isin(sid_of)]
    raw = raw[(raw['open_price'] > 0) & (raw['close_price'] > 0)]
    dl = pd.DataFrame({'sid': raw['stock_code'].map(sid_of).astype(int), 'd': raw['trade_date'],
                       'o': raw['open_price'].astype(float), 'c': raw['close_price'].astype(float),
                       'v': raw['volume'].fillna(0).astype(float), 'val': raw['trading_value'].fillna(0).astype(float)})
    dflows = pd.DataFrame({'stock_id': raw['stock_code'].map(sid_of).astype(int), 'trade_date': raw['trade_date'],
                           'foreign_amount': raw['foreign_amount'].astype(float),
                           'institution_amount': raw['institution_amount'].astype(float)})
    df['src'], dl['src'] = 'S', 'D'
    df = pd.concat([df, dl], ignore_index=True)
    df = df[df['v'] > 0]
    trading = sorted(d for d, n in df.groupby('d').size().items() if n >= 500)
    df = df[df['d'].isin(set(trading))].sort_values(['sid', 'd']).reset_index(drop=True)
    flows = load_flows(c, prereg=prereg, columns=('stock_id', 'trade_date', 'foreign_amount', 'institution_amount'))
    flows = pd.concat([flows[flows['stock_id'].isin(ids)], dflows], ignore_index=True)
    is_delisted = {s: codes[s] in delisted_codes for s in df['sid'].unique() if s > 0}
    is_delisted.update({s: True for s in sid_of.values()})
    df['delisted'] = df['sid'].map(is_delisted).astype(bool)
    df['sector'] = df['sid'].map(sectors).fillna('NA')
    return df, flows, trading, len(sid_of)


def stats(df, trading):
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    df['n_prev'] = g.cumcount()
    df['val20'] = g['val'].transform(lambda s: s.shift(1).rolling(20).mean())
    df['gap'] = df['o'] / prev_c - 1
    pos = {d: i for i, d in enumerate(trading)}
    df['pos'] = df['d'].map(pos)
    rows_left = g['d'].transform('size') - df['n_prev'] - 1
    has = rows_left >= H - 1
    last_c, last_d, last_pos = g['c'].transform('last'), g['d'].transform('last'), g['pos'].transform('last')
    jump = (((df['c'] / prev_c - 1).abs() > JUMP) | (df['gap'].abs() > JUMP)) & (df['src'] == 'D') & (rows_left >= TAIL)
    cj = jump.astype(int).groupby(df['sid']).cumsum()
    df['xc'] = g['c'].shift(-(H - 1))
    df['xd'] = g['d'].shift(-(H - 1))
    df['xp'] = g['pos'].shift(-(H - 1))
    cj_exit = cj.groupby(df['sid']).shift(-(H - 1))
    df['kind'] = np.where(has, 'NORMAL', 'NONE')
    dl = ~has & df['delisted']
    df.loc[dl, 'xc'], df.loc[dl, 'xd'], df.loc[dl, 'xp'] = last_c[dl], last_d[dl], last_pos[dl]
    cj_exit[dl] = cj.groupby(df['sid']).transform('last')[dl]
    df.loc[dl, 'kind'] = 'DELIST'
    stuck = ~has & ~df['delisted'] & (last_pos < len(trading) - 1)
    df.loc[stuck, 'xc'] = last_c[stuck] * (1 + HAIRCUT)
    df.loc[stuck, 'xd'], df.loc[stuck, 'xp'] = last_d[stuck], last_pos[stuck]
    cj_exit[stuck] = cj.groupby(df['sid']).transform('last')[stuck]
    df.loc[stuck, 'kind'] = 'STUCK'
    df['last_c'] = last_c
    df['jump'] = (cj_exit - cj).fillna(0) > 0
    df['late'] = df['kind'].eq('NORMAL') & (df['xp'] - df['pos'] != H - 1)
    return df


def frames(df, sigs, trading, lo, hi):
    end_pos = max(i for i, d in enumerate(trading) if d <= hi)
    base = ((df['d'] >= lo) & (df['d'] <= hi) & (df['n_prev'] >= MIN_ROWS) & (df['val20'] >= MIN_VALUE)
            & (df['gap'] < MAX_GAP) & (df['pos'] + H - 1 <= end_pos) & (df['kind'] != 'NONE'))
    dropped_jump = int((base & df['jump']).sum())
    base &= ~df['jump']
    f0 = df[base].copy()
    for name in ('INS5', 'FI20', 'FI1'):
        f0[name] = sigs[name][base.to_numpy()]
    out = {}
    for name in ('INS5', 'FI20', 'FI1', 'COMB'):
        if name == 'COMB':
            f = f0.dropna(subset=['INS5', 'FI20', 'FI1']).copy()
            f['sig'] = sum(f.groupby('d')[k].rank(method='first', pct=True) for k in ('INS5', 'FI20', 'FI1')) / 3
        else:
            f = f0[f0[name].notna()].copy()
            f['sig'] = f[name]
        counts = f.groupby('d').size()
        out[name] = f[f['d'].isin(set(counts[counts >= MIN_STOCKS].index))].copy()
    return out, dropped_jump


def evaluate(frame, direction, haircut=HAIRCUT):
    f = frame
    xc = f['xc'].where(f['kind'] != 'STUCK', f['last_c'] * (1 + haircut))
    f['net'] = fs.net(f['o'], xc)
    f['net_h'] = net_high(f['o'], xc)
    f['p'] = f.groupby('d')['sig'].rank(method='first', pct=True)
    strength = f['sig'] if direction > 0 else -f['sig']
    f['k'] = strength.groupby(f['d']).rank(method='first', ascending=False)
    leg = (f['p'] > 0.8) if direction > 0 else (f['p'] <= 0.2)
    g = f.groupby('d')
    days = pd.DataFrame({
        'all': g['net'].mean(), 'all_h': g['net_h'].mean(),
        'q5': f[f['p'] > 0.8].groupby('d')['net'].mean(),
        'q1': f[f['p'] <= 0.2].groupby('d')['net'].mean(),
        'long': f[leg].groupby('d')['net'].mean(),
        'long_h': f[leg].groupby('d')['net_h'].mean(),
        'top': f[f['k'] <= TOP].groupby('d')['net'].mean(),
        'n': g.size(),
    }).sort_index()
    days['ls'] = days['q5'] - days['q1']
    days['excess'] = days['long'] - days['all']
    return days, leg


def sector_neutral(f, direction):
    """Day series: buy-side mean of Net demeaned within (day, sector group), quintiles within the group."""
    f = f.copy()
    size = f.groupby(['d', 'sector'])['sid'].transform('size')
    f['grp'] = f['sector'].where(size >= 5, 'POOLED')
    f['dm'] = f['net'] - f.groupby(['d', 'grp'])['net'].transform('mean')
    p = f.groupby(['d', 'grp'])['sig'].rank(method='first', pct=True)
    leg = (p > 0.8) if direction > 0 else (p <= 0.2)
    return f[leg].groupby('d')['dm'].mean().sort_index()


def sector_flow(f):
    """Diagnostic: rank stocks by their sector's val20-weighted mean FI20 (sectors other than NA)."""
    f = f[(f['sector'] != 'NA') & f['FI20'].notna()].copy()
    w = f['val20'] * f['FI20']
    f['ssig'] = w.groupby([f['d'], f['sector']]).transform('sum') / f.groupby(['d', 'sector'])['val20'].transform('sum')
    f['p'] = f.groupby('d')['ssig'].rank(method='first', pct=True)
    all_ = f.groupby('d')['net'].mean()
    q5 = f[f['p'] > 0.8].groupby('d')['net'].mean() - all_
    q1 = f[f['p'] <= 0.2].groupby('d')['net'].mean() - all_
    return q5.dropna().sort_index(), q1.dropna().sort_index(), (q5 - q1).dropna().sort_index()


def holm(pvals):
    items = sorted(pvals.items(), key=lambda x: x[1]) + [(None, 1.0)] * (HOLM_M - len(pvals))
    adj, running = {}, 0.0
    for i, (key, p) in enumerate(items):
        running = max(running, min(1.0, (HOLM_M - i) * p))
        if key is not None:
            adj[key] = running
    return adj


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stage', choices=('check', 'discovery', 'test'), required=True)
    stage = ap.parse_args().stage
    if stage == 'test':
        rel = DISCOVERY_JSON.relative_to(ROOT).as_posix()
        if not committed(rel):
            raise SystemExit(f'{rel} must be committed before the test stage (README.md > 두 단계)')
        disc = json.loads(DISCOVERY_JSON.read_text(encoding='utf-8'))
        names = [n for n in SIGNALS if disc[n]['pass']]
        if not names:
            raise SystemExit('no signal passed discovery: the test window stays sealed')
        lo, hi = TEST_FROM, TEST_END
    else:
        disc, names, lo, hi = None, list(SIGNALS), DISCOVERY_FROM, DISCOVERY_END

    c = fs.connect()
    df, flows, trading, n_delisted = load(c, stage)
    df = stats(df, trading)
    sigs = fs.signals(df, flows, trading)
    del flows
    fr, dropped_jump = frames(df, sigs, trading, lo, hi)

    out = [f'final flow study, stage {stage}: signal days {lo}..{hi}, hold {H} traded rows', '']
    any_f = fr['INS5']
    out += [f'delisted stocks loaded {n_delisted}; universe rows (INS5) {len(any_f)}, of which delisted '
            f'{int(any_f["delisted"].sum())} ({any_f[any_f["delisted"]]["sid"].nunique()} stocks)',
            f'exit kinds (INS5): ' + ', '.join(f'{k} {v}' for k, v in any_f['kind'].value_counts().items()),
            f'late exits (halt) {int(any_f["late"].sum())}, dropped for corporate-action jumps {dropped_jump}',
            'rows per signal: ' + ', '.join(f'{k} {len(v)}' for k, v in fr.items()), '']
    if stage == 'check':
        print('\n'.join(out))
        return

    result, pvals = {}, {}
    for name in names:
        f = fr[name]
        if stage == 'discovery':
            probe, _ = evaluate(f, +1)
            direction = 1 if probe['ls'].mean() >= 0 else -1
        else:
            direction = disc[name]['direction']
        days, leg = evaluate(f, direction)
        ex, lng = boot(days['excess'].tolist()), boot(days['long'].tolist())
        yearly = days.groupby(days.index.map(lambda d: d.year))[['excess', 'long']].mean()
        buy = f[leg]
        r = {'direction': direction, 'days': len(days), 'stocks_per_day': round(float(days['n'].mean()), 1),
             'excess': [round(x, 4) for x in ex], 'net': [round(x, 4) for x in lng]}
        out += [f'== {name} h={H} (direction {"+" if direction > 0 else "-"}, buy side {"Q5" if direction > 0 else "Q1"}) ==',
                f'days {len(days)}, stocks/day {days["n"].mean():.0f}, buy-side rows {len(buy)}, of which delisted '
                f'{int(buy["delisted"].sum())}, DELIST exits {int((buy["kind"] == "DELIST").sum())}, '
                f'STUCK {int((buy["kind"] == "STUCK").sum())}, |net| > 35%: {int((f["net"].abs() > 35).sum())}',
                f'PRIMARY buy-side excess   {fmt(ex)}',
                f'buy-side Net (BASE)       {fmt(lng)}',
                f'buy-side Net (HIGH cost)  {fmt(boot(days["long_h"].tolist()))}',
                f'long-short Q5-Q1          {fmt(boot(days["ls"].tolist()))}',
                f'Q5 excess {(days["q5"] - days["all"]).mean():+.3f}, Q1 excess {(days["q1"] - days["all"]).mean():+.3f}, '
                f'universe Net {days["all"].mean():+.3f}, top {TOP} Net {days["top"].mean():+.3f}',
                'by year excess/Net: ' + ', '.join(f'{y} {e:+.3f}/{n:+.3f}' for y, (e, n) in yearly.iterrows())]
        stress = []
        for hc in STRESS:
            sd, _ = evaluate(f.copy(), direction, hc)
            stress.append(f'haircut {hc:+.0%}: excess {sd["excess"].mean():+.3f}, Net {sd["long"].mean():+.3f}')
        out.append('STUCK stress: ' + '; '.join(stress))
        if stage == 'discovery':
            surv, _ = evaluate(f[~f['delisted']].copy(), direction)
            sn = sector_neutral(f, direction)
            out += [f'diag survivors only excess   {fmt(boot(surv["excess"].tolist()))}',
                    f'diag sector-neutral excess   {fmt(boot(sn.dropna().tolist()))}']
            positive = int((yearly['excess'] > 0).sum())
            r['pass'] = bool(ex[0] > 0 and ex[1] > 0 and positive >= 5 and lng[0] > 0)
            r['years_positive'] = positive
            out.append(f'DISCOVERY {"PASS" if r["pass"] else "FAIL"} (excess > 0, CI low > 0, years {positive}/7 >= 5, '
                       f'Net > 0)')
        else:
            h1 = days[days.index <= TEST_HALF]['excess'].mean()
            h2 = days[days.index > TEST_HALF]['excess'].mean()
            r['halves'] = [round(h1, 4), round(h2, 4)]
            pvals[name] = ex[3]
            out.append(f'halves {h1:+.3f} / {h2:+.3f}')
        result[name] = r
        out.append('')

    if stage == 'discovery':
        q5, q1, ls = sector_flow(fr['FI20'])
        out += ['== diag sector flow (sector val20-weighted FI20, sectors other than NA) ==',
                f'Q5 excess {fmt(boot(q5.tolist()))}', f'Q1 excess {fmt(boot(q1.tolist()))}',
                f'Q5-Q1     {fmt(boot(ls.tolist()))}', '']
        out.append('summary: ' + ', '.join(f'{k} {"PASS" if r["pass"] else "fail"}' for k, r in result.items()))
    else:
        adj = holm(pvals)
        for key, r in result.items():
            r['holm_p'] = round(adj[key], 4)
            r['verdict'] = ('VERIFIED' if r['excess'][0] > 0 and adj[key] < 0.05 and min(r['halves']) > 0
                            and r['net'][0] > 0 else 'NOT VERIFIED')
            out.append(f'{key}: Holm p {adj[key]:.4f} (m = {HOLM_M}) -> {r["verdict"]}')
    text = '\n'.join(out) + '\n'
    print(text)
    (HERE / f'result_{stage}.txt').write_text(text, encoding='utf-8', newline='\n')
    (HERE / f'result_{stage}.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n',
                                               encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
