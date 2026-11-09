"""Execution study for the verified flow signals (README.md), exploratory. Flow-conditioned parts use the discovery
window only (sealed.load_flows without prereg); the intraday part (2025-10-30 ~) is price-only and not conditioned on
flows. Nothing here is a verdict: a changed definition is checked by Forward only.

  1. Entry timing: the registered open entry (row i open -> row i+19 close) vs the app's timing, entry in the closing
     auction of the signal day (row i close -> row i+20 close). Flows end at d-1 either way, so both are known by 15:15.
  2. Execution cost: half-tick floor by liquidity tier for the buy side (discovery); from intraday 5M bars, closing-
     auction value (15:25 bar), missing closing prints and the Abdi-Ranaldo spread by liquidity tier (all stocks).
  3. U-shape as an exclusion filter: quintile excess of each signal for the app's horizons (closing entry -> next open,
     -> next close) and the 20-row closing-entry horizon; what removing the middle quintile does to the universe.
Run: python execution_study.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-11-06-final-flow-preregistration'))
import final_study as st  # noqa: E402
fs = st.fs

TIERS = ((1e9, 3e9, '10~30억'), (3e9, 1e10, '30~100억'), (1e10, float('inf'), '100억+'))
SIGNALS = ('INS5', 'FI20', 'COMB')
INTRADAY_FROM = dt.date(2025, 10, 30)
SPREAD_DAYS = 24


def tick(price):
    """KRX tick size (unified table since 2023-01-25)."""
    p = np.asarray(price, dtype=float)
    return np.select([p < 2000, p < 5000, p < 20000, p < 50000, p < 200000, p < 500000],
                     [1, 5, 10, 50, 100, 500], 1000)


def tier_of(val20):
    return pd.cut(val20, [t[0] for t in TIERS] + [float('inf')], right=False, labels=[t[2] for t in TIERS])


def add_close_entry(df, trading):
    """Columns for closing-auction entry: 20-row exit (xc_c), next open / next close, jump spans."""
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    rows_left = g['d'].transform('size') - df['n_prev'] - 1
    any_j = ((df['c'] / prev_c - 1).abs() > st.JUMP) | (df['gap'].abs() > st.JUMP)
    up_j = ((df['c'] / prev_c - 1) > st.JUMP) | (df['gap'] > st.JUMP)
    jump = (any_j & (rows_left >= st.TAIL)) | (up_j & (rows_left < st.TAIL))
    cj = jump.astype(int).groupby(df['sid']).cumsum()
    has = rows_left >= st.H
    df['xc_c'] = g['c'].shift(-st.H)
    df['xp_c'] = g['pos'].shift(-st.H)
    cj_exit = cj.groupby(df['sid']).shift(-st.H)
    last_c, last_pos = g['c'].transform('last'), g['pos'].transform('last')
    dl = ~has & df['delisted']
    df.loc[dl, 'xc_c'], df.loc[dl, 'xp_c'] = last_c[dl], last_pos[dl]
    stuck = ~has & ~df['delisted'] & (last_pos < len(trading) - 1)
    df.loc[stuck, 'xc_c'], df.loc[stuck, 'xp_c'] = last_c[stuck] * (1 + st.HAIRCUT), last_pos[stuck]
    last_cj = cj.groupby(df['sid']).transform('last')
    cj_exit[dl | stuck] = last_cj[dl | stuck]
    df['jump_c'] = (cj_exit - cj).fillna(0) > 0
    df['no1'] = g['o'].shift(-1)
    df['nc1'] = g['c'].shift(-1)
    df['jump1'] = jump.groupby(df['sid']).shift(-1).fillna(False).astype(bool)
    return df


def day_excess(f, col, leg):
    allm = f.groupby('d')[col].mean()
    return (f[leg].groupby('d')[col].mean() - allm).dropna()


def part1_and_3(df, sigs, trading, disc, out, result):
    lo, hi = st.DISCOVERY_FROM, st.DISCOVERY_END
    end_pos = max(i for i, d in enumerate(trading) if d <= hi)
    fr, _ = st.frames(df, sigs, trading, lo, hi)
    out += ['== 1. entry timing (discovery, buy side = Q1, BASE cost) ==',
            'open entry = registered (row i open -> row i+19 close); close entry = row i close -> row i+20 close', '']
    for name in SIGNALS:
        direction = disc[name]['direction']
        f = fr[name].copy()
        f['p'] = f.groupby('d')['sig'].rank(method='first', pct=True)
        leg = (f['p'] <= 0.2) if direction < 0 else (f['p'] > 0.8)
        xo = f['xc'].where(f['kind'] != 'STUCK', f['last_c'] * (1 + st.HAIRCUT))
        f['net_o'] = fs.net(f['o'], xo)
        okc = f['xc_c'].notna() & ~f['jump_c'] & (f['pos'] + st.H <= end_pos)
        fc = f[okc].copy()
        fc['net_c'] = fs.net(fc['c'], fc['xc_c'])
        legc = leg[okc]
        r = {}
        for lbl, ff, col, lg in (('open', f, 'net_o', leg), ('close', fc, 'net_c', legc)):
            ex = day_excess(ff, col, lg)
            net = ff[lg].groupby('d')[col].mean()
            yearly = ex.groupby(ex.index.map(lambda d: d.year)).mean()
            bx, bn = st.boot(ex.tolist()), st.boot(net.tolist())
            r[lbl] = {'excess': [round(x, 4) for x in bx], 'net': [round(x, 4) for x in bn],
                      'years_positive': int((yearly > 0).sum())}
            out.append(f'{name} {lbl:5s} entry: excess {st.fmt(bx)}, Net {st.fmt(bn)}, years {int((yearly > 0).sum())}/7, '
                       f'days {len(ex)}')
        gap = f[leg]
        out.append(f'{name} buy side, mean same-day open->close move {((gap["c"] / gap["o"] - 1) * 100).mean():+.3f}% '
                   f'(universe {((f["c"] / f["o"] - 1) * 100).mean():+.3f}%)')
        out.append('')
        result.setdefault('timing', {})[name] = r

    out += ['== 3. U-shape as an exclusion filter (discovery, closing entry, gross excess vs universe, %) ==',
            'quintiles of the signal (Q1 = most net selling, Q5 = most net buying)', '']
    for name in SIGNALS:
        f = fr[name].copy()
        f['p'] = f.groupby('d')['sig'].rank(method='first', pct=True)
        f['q'] = np.ceil(f['p'] * 5).clip(1, 5).astype(int)
        r = {}
        for lbl, num, den, ok in (('overnight (close -> next open)', 'no1', 'c', f['no1'].notna() & ~f['jump1']),
                                  ('1 day (close -> next close)', 'nc1', 'c', f['nc1'].notna() & ~f['jump1']),
                                  ('20 rows (close entry)', 'xc_c', 'c', f['xc_c'].notna() & ~f['jump_c']
                                   & (f['pos'] + st.H <= end_pos))):
            ff = f[ok].copy()
            ff['ret'] = (ff[num] / ff[den] - 1) * 100
            allm = ff.groupby('d')['ret'].mean()
            qx = ff.groupby(['d', 'q'])['ret'].mean().unstack().sub(allm, axis=0)
            mid = qx[3].dropna()
            keep = ff[ff['q'] != 3].groupby('d')['ret'].mean() - allm
            bm, bk = st.boot(mid.tolist()), st.boot(keep.dropna().tolist())
            r[lbl] = {'quintiles': [round(float(x), 4) for x in qx.mean()], 'q3': [round(x, 4) for x in bm],
                      'drop_q3': [round(x, 4) for x in bk]}
            out.append(f'{name} {lbl}: Q1..Q5 ' + ' '.join(f'{x:+.3f}' for x in qx.mean())
                       + f' | Q3 {st.fmt(bm)} | universe without Q3 {st.fmt(bk)}')
        out.append('')
        result.setdefault('ushape', {})[name] = r


def part2_discovery(df, sigs, trading, disc, out, result):
    lo, hi = st.DISCOVERY_FROM, st.DISCOVERY_END
    fr, _ = st.frames(df, sigs, trading, lo, hi)
    out += ['== 2a. half-tick floor (discovery buy side, % per side at the entry price) ==']
    for name in SIGNALS:
        f = fr[name].copy()
        f['p'] = f.groupby('d')['sig'].rank(method='first', pct=True)
        b = f[f['p'] <= 0.2].copy() if disc[name]['direction'] < 0 else f[f['p'] > 0.8].copy()
        b['ht'] = tick(b['c']) / 2 / b['c'] * 100
        b['tier'] = tier_of(b['val20'])
        parts = [f'{lbl} median {b.loc[b["tier"] == lbl, "ht"].median():.3f}' for _, _, lbl in TIERS]
        out.append(f'{name}: all median {b["ht"].median():.3f}, mean {b["ht"].mean():.3f}; ' + ', '.join(parts)
                   + f'; share of buy side priced < 2,000 won {(b["c"] < 2000).mean():.0%}')
        result.setdefault('half_tick', {})[name] = round(float(b['ht'].mean()), 4)
    out.append('')


def part2_intraday(c, out, result):
    """Price-only, all stocks in intraday_research_bar since 2025-10-30 (not conditioned on flows)."""
    val = pd.DataFrame(c.execute("""select stock_id, trade_date, trading_value from daily_adjusted_candle
                                     where trade_date >= %s order by stock_id, trade_date""",
                                 (INTRADAY_FROM - dt.timedelta(days=45),)).fetchall(), columns=['sid', 'd', 'val'])
    val['val'] = val['val'].astype(float)
    val['val20'] = val.groupby('sid')['val'].transform(lambda s: s.shift(1).rolling(20).mean())
    val20 = val.set_index(['sid', 'd'])['val20']

    bars = pd.DataFrame(c.execute("""select stock_id, (start_time at time zone 'Asia/Seoul')::date,
                                            to_char(start_time at time zone 'Asia/Seoul', 'HH24:MI'), close_price, volume,
                                            trading_value
                                       from intraday_research_bar
                                      where start_time >= %s and to_char(start_time at time zone 'Asia/Seoul', 'HH24:MI')
                                            in ('15:15', '15:25')""", (INTRADAY_FROM,)).fetchall(),
                        columns=['sid', 'd', 'hm', 'c', 'v', 'val'])
    bars[['c', 'v', 'val']] = bars[['c', 'v', 'val']].astype(float)
    p = bars.pivot_table(index=['sid', 'd'], columns='hm', values=['c', 'v', 'val'], aggfunc='first')
    p.columns = [f'{a}_{b}' for a, b in p.columns]
    p['val20'] = val20.reindex(p.index).to_numpy()
    p = p[p['val20'] >= 1e9].copy()
    p['tier'] = tier_of(p['val20'])
    has_close = p.get('v_15:25', pd.Series(np.nan, index=p.index)).fillna(0) > 0
    p['drift'] = (p['c_15:25'] / p['c_15:15'] - 1) * 100
    out += [f'== 2b. intraday, all stocks with val20 >= 10억, {INTRADAY_FROM} ~ (price only) ==',
            f'stock-days {len(p)}, stocks {p.index.get_level_values(0).nunique()}']
    r = {}
    for _, _, lbl in TIERS:
        t = p[p['tier'] == lbl]
        hc = has_close[p['tier'] == lbl]
        auc = t.loc[hc, 'val_15:25']
        r[lbl] = {'closing_auction_value_median': float(auc.median()), 'no_closing_print': float(1 - hc.mean()),
                  'abs_drift_median': float(t['drift'].abs().median())}
        out.append(f'{lbl}: stock-days {len(t)}, no closing print {1 - hc.mean():.1%}, closing-auction value median '
                   f'{auc.median() / 1e6:,.0f}백만 (p25 {auc.quantile(0.25) / 1e6:,.0f}, p75 {auc.quantile(0.75) / 1e6:,.0f}), '
                   f'|15:20 -> close| median {t["drift"].abs().median():.3f}%')

    days = sorted(p.index.get_level_values(1).unique())
    pick = [days[int(i)] for i in np.linspace(0, len(days) - 1, SPREAD_DAYS)]
    rows = c.execute("""select stock_id, (start_time at time zone 'Asia/Seoul')::date, start_time, high_price, low_price,
                               close_price from intraday_research_bar
                         where (start_time at time zone 'Asia/Seoul')::date = any(%s) and volume > 0
                         order by stock_id, start_time""", (pick,)).fetchall()
    b = pd.DataFrame(rows, columns=['sid', 'd', 't', 'h', 'l', 'c'])
    for k in ('h', 'l', 'c'):
        b[k] = np.log(b[k].astype(float))
    b['eta'] = (b['h'] + b['l']) / 2
    gb = b.groupby(['sid', 'd'])
    b['eta_n'] = gb['eta'].shift(-1)
    b['x'] = (b['c'] - b['eta']) * (b['c'] - b['eta_n'])
    s2 = 4 * b.groupby(['sid', 'd'])['x'].mean()
    spread = (np.sqrt(s2.clip(lower=0)) * 100).rename('spread').to_frame()
    spread['val20'] = val20.reindex(spread.index).to_numpy()
    spread = spread[spread['val20'] >= 1e9]
    spread['tier'] = tier_of(spread['val20'])
    out.append(f'Abdi-Ranaldo spread from 5M bars ({SPREAD_DAYS} days spread over the period, full round trip %):')
    for _, _, lbl in TIERS:
        s = spread.loc[spread['tier'] == lbl, 'spread']
        r[lbl]['ar_spread_median'] = float(s.median())
        out.append(f'  {lbl}: median {s.median():.3f}, p75 {s.quantile(0.75):.3f} (stock-days {len(s)})')
    out.append('')
    result['intraday'] = r


def main():
    disc = json.loads(st.DISCOVERY_JSON.read_text(encoding='utf-8'))
    c = fs.connect()
    out = ['flow execution study (exploratory; flow-conditioned parts on the discovery window only)', '']
    result = {}
    part2_intraday(c, out, result)
    print('\n'.join(out), flush=True)
    df, flows, trading, _ = st.load(c, 'discovery')
    df = st.stats(df, trading)
    df = add_close_entry(df, trading)
    sigs = fs.signals(df, flows, trading)
    del flows
    n0 = len(out)
    part1_and_3(df, sigs, trading, disc, out, result)
    part2_discovery(df, sigs, trading, disc, out, result)
    print('\n'.join(out[n0:]), flush=True)
    (HERE / 'result.txt').write_text('\n'.join(out) + '\n', encoding='utf-8', newline='\n')
    (HERE / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2, default=float) + '\n',
                                      encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
