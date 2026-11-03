"""Point-in-time replay of the pre-market list + ML top 5 (README.md), pre-registered 2026-11-03.

Carries the README definitions over unchanged; committed with the README before the first run.
Implementation details fixed here before running:
  Models: feasibility.train_models (walk-forward 2025 / 2026 models of study B).
  Market days: days with intraday research bars for at least 500 stocks. prv/nxt follow these days.
  OLD reproduces ../2026-11-03-subscription-replay/simulate.py + feasibility.py: market inputs over the share rows that
    have the auction bar and the next 09:00 bar (outliers included, as in feasibility.build); evaluation rows drop the
    +-35% outliers and keep stocks tradable by the current flags; the list is built over current-tradable stocks with
    bars on d.
  PIT: market inputs and evaluation over the static-attribute rows that have the cutoff bar; the list over
    static-attribute stocks with bars on d.
  Adjustment of PIT exits: entry x (adjusted close d / raw last bar close d), exit x (adjusted open e / raw first bar
    open e); factor 1 where the adjusted daily row is missing.
  Pre-market statistics as in simulate.premarket: the stock's own previous N daily rows (gap = open / previous row's
    close - 1 > 0) and the mean trading value of its previous 20 rows; a stock needs N + 1 previous rows.
Run: python pit_replay.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-11-03-ml-feasibility-preregistration'))
sys.path.insert(0, str(HERE.parent / '2026-11-03-ml-ranking-preregistration'))
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
import feasibility as fz  # noqa: E402
import ml_ranking as mlr  # noqa: E402
from volatility import block_ci, connect, share  # noqa: E402

FROM, TO = fz.FROM, fz.TO
HALF = dt.date(2026, 4, 30)
SEED = 20261103
BUY, SELL = 0.00095, 0.00295
HIGH_BUY, HIGH_SELL = 0.00165, 0.00365
STRESS = -50.0
STRUCTURED = ('인버스', '레버리지', '선물', 'ETN', 'ETF', '2X')
CUTS = ('15:15', '15:10')
WINDOWS, SIZES = (10, 20, 40, 60), (150, 300, 500)
DART_KEYS = ('관리종목지정', '관리종목 지정', '상장폐지 사유', '상장적격성', '개선기간', '투자주의 환기', '투자주의환기')
DART_SKIP = ('해소', '해제')
SEOUL = dt.timezone(dt.timedelta(hours=9))
SHADOW_REFERENCE = HERE.parent / '2026-11-03-shadow-forward' / 'reference.json'


def net(entry, exit_, buy=BUY, sell=SELL):
    return (exit_ * (1 - sell) - entry * (1 + buy)) / (entry * (1 + buy)) * 100


def load_meta(c):
    meta = {}
    for s, code, name, etf, etn, managed, halted, active in c.execute(
            'select id, stock_code, stock_name, is_etf, is_etn, is_managed, is_trading_halted, is_active from stock'):
        upper = (name or '').upper()
        static = bool(share(code, name) and not etf and not etn and not any(x in upper for x in STRUCTURED))
        meta[s] = {'static': static, 'current': static and bool(active and not managed and not halted),
                   'flagged_now': bool(managed or halted or not active)}
    return meta


def load_intraday(c, ids):
    """(sid, day) -> (first_t, first open, [P, H, L, VAL, V] for 15:10, same for 15:15, last_t, last close)."""
    lo = dt.datetime.combine(FROM - dt.timedelta(days=10), dt.time(0), SEOUL)
    rows = {}
    ids = sorted(ids)
    for k in range(0, len(ids), 150):
        for r in c.execute(
                """with b as (select stock_id, start_time, open_price, high_price, low_price, close_price, volume, trading_value,
                                     (start_time at time zone 'Asia/Seoul') lt
                                from intraday_research_bar where stock_id = any(%s) and start_time >= %s)
                   select stock_id, lt::date, min(lt::time), (array_agg(open_price order by start_time))[1],
                          max(close_price) filter (where lt::time = '15:10'), max(high_price) filter (where lt::time <= '15:10'),
                          min(low_price) filter (where lt::time <= '15:10'), sum(trading_value) filter (where lt::time <= '15:10'),
                          sum(volume) filter (where lt::time <= '15:10'),
                          max(close_price) filter (where lt::time = '15:15'), max(high_price) filter (where lt::time <= '15:15'),
                          min(low_price) filter (where lt::time <= '15:15'), sum(trading_value) filter (where lt::time <= '15:15'),
                          sum(volume) filter (where lt::time <= '15:15'),
                          max(lt::time), (array_agg(close_price order by start_time desc))[1]
                     from b group by stock_id, lt::date""", (ids[k:k + 150], lo)):
            f = [None if v is None else float(v) for v in r[3:5]]
            cut10 = [None if v is None else float(v) for v in r[4:9]]
            cut15 = [None if v is None else float(v) for v in r[9:14]]
            rows[(r[0], r[1])] = (str(r[2]), f[0], cut10, cut15, str(r[14]), None if r[15] is None else float(r[15]))
        print(f'  intraday {min(k + 150, len(ids))}/{len(ids)} stocks', flush=True)
    return rows


def load_adjusted(c):
    out = {}
    for s, d, o, cl in c.execute("""select stock_id, trade_date, open_price, close_price from daily_adjusted_candle
                                      where trade_date >= %s and open_price > 0 and close_price > 0""",
                                 (FROM - dt.timedelta(days=10),)):
        out[(s, d)] = (float(o), float(cl))
    return out


def build(intra, arr, trading, meta, adjusted, cut):
    """One row per (stock, d) with features at the cutoff and every price the eight combinations need."""
    counts = defaultdict(int)
    for (_, d) in intra:
        counts[d] += 1
    days = sorted(d for d, n in counts.items() if n >= 500)
    prv, nxt = dict(zip(days[1:], days)), dict(zip(days, days[1:]))
    by_sid = defaultdict(list)
    for (s, d) in intra:
        by_sid[s].append(d)
    later = {}
    for s, ds in by_sid.items():
        ds.sort()
        for a, b in zip(ds, ds[1:]):
            later[(s, a)] = b
    tpos = {d: k for k, d in enumerate(trading)}
    k_cut = 2 if cut == '15:10' else 3
    rows = []
    for (sid, d), r in intra.items():
        if d < FROM or d > TO or d in mlr.SPECIAL or d not in nxt or nxt[d] in mlr.SPECIAL or d not in prv:
            continue
        first_t, O, cutvals, last_t, last_close = r[0], r[1], r[k_cut], r[4], r[5]
        P, H, L, VAL, V = cutvals
        if None in (O, P, H, L, VAL, V) or sid not in arr or d not in tpos:
            continue
        p1 = intra.get((sid, prv[d]))
        if not p1 or not p1[5]:
            continue
        a = arr[sid]
        i = a['pos'].get(trading[tpos[d] - 1])
        if i is None or i < 78:
            continue
        k = a['c'][i] / p1[5]
        f = fz.features(a, i, O * k, H * k, L * k, P * k, V / k, VAL)
        if f['AVGVAL20'] < mlr.MIN_VALUE or f['RET1'] >= 0.29:
            continue
        auction = last_close if last_t == '15:25:00' and last_close else None
        n1 = intra.get((sid, nxt[d]))
        old_exit = n1[1] if n1 and n1[0] == '09:00:00' and n1[1] else None
        e = later.get((sid, d))
        pit_exit, exit_day, exit_t = (intra[(sid, e)][1], e, intra[(sid, e)][0]) if e else (None, None, None)
        adj_d, adj_e = adjusted.get((sid, d)), adjusted.get((sid, e)) if e else None
        kd = adj_d[1] / last_close if adj_d and last_close else 1.0
        ke = adj_e[0] / pit_exit if adj_e and pit_exit else 1.0
        f.update(sid=sid, d=d, P=P, auction=auction, old_exit=old_exit, pit_exit=pit_exit, exit_day=exit_day,
                 exit_t=exit_t, kd=kd, ke=ke, static=meta.get(sid, {}).get('static', False),
                 current=meta.get(sid, {}).get('current', False))
        rows.append(f)
    x = pd.DataFrame(rows)
    print(f'  cut {cut}: rows {len(x)}', flush=True)
    return x


def outcomes(x):
    """Net per row for each entry under OLD and PIT exits (NaN where not defined)."""
    y = pd.DataFrame(index=x.index)
    ok = x['old_exit'].notna()
    y['old_cut'] = np.where(ok, net(x['P'], x['old_exit'].fillna(1)), np.nan)
    ok_a = ok & x['auction'].notna()
    y['old_auction'] = np.where(ok_a, net(x['auction'].fillna(1), x['old_exit'].fillna(1)), np.nan)
    gone = x['pit_exit'].isna()
    exit_adj = x['pit_exit'].fillna(1) * x['ke']
    y['pit_cut'] = np.where(gone, STRESS, net(x['P'] * x['kd'], exit_adj))
    has = x['auction'].notna()
    y['pit_auction'] = np.where(~has, np.nan, np.where(gone, STRESS, net(x['auction'].fillna(1) * x['kd'], exit_adj)))
    y['pit_auction_high'] = np.where(~has, np.nan, np.where(gone, STRESS, net(x['auction'].fillna(1) * x['kd'], exit_adj,
                                                                            HIGH_BUY, HIGH_SELL)))
    return y


def predict(frame, models, cols, by):
    frame = frame.copy()
    frame['MKT_RET'] = frame.groupby('d')['RET1'].transform('mean')
    frame['MKT_UP'] = frame.groupby('d')['RET1'].transform(lambda s: (s > 0).mean())
    frame['RANK_RET'] = frame.groupby('d')['RET1'].rank(pct=True)
    frame['RANK_VAL'] = frame.groupby('d')['LOGVAL'].rank(pct=True)
    frame = mlr.add_dart(frame.reset_index(drop=True), by)
    frame['pred'] = np.nan
    for year, model in models.items():
        mask = frame['d'].map(lambda d: d.year == year)
        frame.loc[mask, 'pred'] = model.predict(frame.loc[mask, cols].astype('float32'))
    return frame


def premarket_stats(df):
    """{N: {(sid, d): (gap share over N rows before d, mean value of 20 rows before d)}} for d in the period."""
    out = {n: {} for n in WINDOWS}
    lo = FROM - dt.timedelta(days=10)
    for sid, g in df[df['d'] >= dt.date(2025, 4, 1)].groupby('sid', sort=False):
        o, c, val, ds = g['o'].to_numpy(), g['c'].to_numpy(), g['val'].to_numpy(), list(g['d'])
        up = np.zeros(len(o))
        up[1:] = (o[1:] / c[:-1] - 1) > 0
        cu, cv = np.concatenate([[0], np.cumsum(up)]), np.concatenate([[0], np.cumsum(val)])
        for k in range(21, len(ds)):
            if ds[k] < lo:
                continue
            value20 = (cv[k] - cv[k - 20]) / 20
            for n in WINDOWS:
                if k >= n + 1:
                    out[n][(sid, ds[k])] = ((cu[k] - cu[k - n]) / n, value20)
    return out


def lists(stats, candidates, n, size):
    """d -> set of sids: top `size` candidates on d by gap share (value20 >= 1bn), ties by smaller sid."""
    out = {}
    for d, sids in candidates.items():
        cand = [(stats[n][(s, d)][0], -s, s) for s in sids if (s, d) in stats[n] and stats[n][(s, d)][1] >= 1e9]
        out[d] = {s for _, _, s in sorted(cand, reverse=True)[:size]}
    return out


def top5_days(frame, ycol, listed, exclude=None):
    """Per day: mean Net of the top-5 picks inside the list (filled picks only) and pick counts."""
    recs = []
    for d, g in frame.groupby('d'):
        if len(g) < 20:
            continue
        inside = g[g['sid'].isin(listed.get(d, set()))]
        if exclude is not None:
            inside = inside[~inside['sid'].isin(exclude.get(d, set()))]
        if len(inside) < 5:
            continue
        top = inside.sort_values(['pred', 'sid'], ascending=[False, True]).head(5)
        vals = top[ycol].dropna()
        recs.append({'d': d, 'net': vals.mean() if len(vals) else np.nan, 'filled': len(vals),
                     'sids': list(top['sid']), 'rows': list(top.index)})
    return pd.DataFrame(recs)


def stats(days):
    s = days.dropna(subset=['net'])
    mean, lo, hi = block_ci(list(s['net']), 5, random.Random(SEED))
    first = s[s['d'] <= HALF]['net'].mean()
    second = s[s['d'] > HALF]['net'].mean()
    trimmed = s['net'].drop(s['net'].nlargest(3).index).mean()
    return {'days': len(s), 'mean': mean, 'lo': lo, 'hi': hi, 'first': first, 'second': second, 'trimmed': trimmed}


def fmt(st):
    return (f"{st['mean']:+.3f} [{st['lo']:+.3f}, {st['hi']:+.3f}]  halves {st['first']:+.3f} / {st['second']:+.3f}"
            f"  days {st['days']}")


def dart_flagged(c, codes, days):
    """d -> set of sids with a matching exchange notice in d-365..d-1 (PIT-DART sensitivity)."""
    notices = defaultdict(list)
    for code, title, d in c.execute("select stock_code, report_nm, rcept_dt from dart_filing where rcept_dt >= '2024-10-01'"):
        title = ' '.join((title or '').split())
        sid = codes.get(code)
        if sid is not None and any(k in title for k in DART_KEYS) and not any(k in title for k in DART_SKIP):
            notices[sid].append(d)
    out = defaultdict(set)
    for d in days:
        for sid, ds in notices.items():
            if any(d - dt.timedelta(days=365) <= x < d for x in ds):
                out[d].add(sid)
    return out


def main():
    c = connect()
    meta = load_meta(c)
    df, trading, codes = mlr.load_daily(c)
    models, cols, _ = fz.train_models(trading)
    pm = premarket_stats(df)
    arr = fz.arrays(df)
    del df
    intra = load_intraday(c, arr.keys())
    adjusted = load_adjusted(c)
    by = mlr.dart_index(c, codes)
    bars_on = defaultdict(set)
    for (s, d) in intra:
        bars_on[d].add(s)
    results, series = {}, {}
    primary = None
    for cut in CUTS:
        x = build(intra, arr, trading, meta, adjusted, cut)
        x = pd.concat([x, outcomes(x)], axis=1)
        old_base = x[x['auction'].notna() & x['old_exit'].notna()]
        old = predict(old_base, models, cols, by)
        old = old[((old['old_exit'] / old['auction'] - 1).abs() <= mlr.OUTLIER) & old['current']]
        pit = predict(x[x['static']], models, cols, by)
        days = sorted(set(pit['d']))
        old_list = lists(pm, {d: {s for s in bars_on[d] if meta.get(s, {}).get('current')} for d in days}, 20, 300)
        pit_cands = {d: {s for s in bars_on[d] if meta.get(s, {}).get('static')} for d in days}
        pit_list = lists(pm, pit_cands, 20, 300)
        for entry in ('cut', 'auction'):
            for uni, frame, listed in (('OLD', old, old_list), ('PIT', pit, pit_list)):
                t = top5_days(frame, f'{uni.lower()}_{entry}', listed)
                results[(cut, entry, uni)] = stats(t)
                series[f'{cut}_{entry}_{uni}'] = t.set_index('d')['net']
        if cut == '15:10':
            primary = (pit, pit_cands, days)
    out = []
    say = lambda line='': (print(line), out.append(line))  # noqa: E731
    say('== 2. Leak decomposition: PREMARKET (N=20, K=300) ML top-5 Net %, block 95% interval')
    for key, st in results.items():
        say(f'   input {key[0]}  entry {key[1]:8s} universe {key[2]}  {fmt(st)}')
    for axis, (a, b) in (('input 15:15 -> 15:10', (0, ('15:15', '15:10'))), ('entry cut -> auction', (1, ('cut', 'auction'))),
                         ('universe OLD -> PIT', (2, ('OLD', 'PIT')))):
        diffs = [results[k]['mean'] - results[tuple(b[1][0] if j == b[0] else k[j] for j in range(3))]['mean']
                 for k in results if k[b[0]] == b[1][1]]
        say(f'   {axis}: mean change {np.mean(diffs):+.3f}%p (average over the other two axes)')

    pit, pit_cands, days = primary
    listed = lists(pm, pit_cands, 20, 300)
    t = top5_days(pit, 'pit_auction', listed)
    st = stats(t)
    picks = pit.loc[[r for rows in t['rows'] for r in rows]]
    high = top5_days(pit, 'pit_auction_high', listed)['net'].mean()
    missing = int(picks['pit_auction'].isna().sum())
    gone = int((picks['pit_exit'].isna() & picks['auction'].notna()).sum())
    late = int(((picks['exit_t'] != '09:00:00') & picks['pit_exit'].notna()).sum())
    keep = st['mean'] > 0 and st['lo'] > 0 and st['first'] > 0 and st['second'] > 0
    say('\n== 1. Primary: input 15:10, closing-auction entry, PIT universe, N=20, K=300')
    say(f"   Net {fmt(st)}")
    say(f'   HIGH cost {high:+.3f} | without the top 3 days {st["trimmed"]:+.3f} | picks {len(picks)}, missing auction '
        f'{missing}, never traded again {gone} (Net {STRESS:.0f}%), exits after 09:00 {late}')
    say(f"   -> {'MAINTAINED' if keep else 'WITHDRAWN'}")
    SHADOW_REFERENCE.write_text(json.dumps({
        'value': round(st['mean'], 4), 'days': st['days'],
        'source': 'audit/2026-11-03-point-in-time-replay primary (15:10, closing auction, PIT, N=20, K=300), '
                  '2025-11-03..2026-10-21',
        'computed_at': dt.datetime.now(SEOUL).isoformat(timespec='seconds')}, ensure_ascii=False, indent=2) + '\n',
        encoding='utf-8')
    say(f'   reference written: {SHADOW_REFERENCE.name} = {st["mean"]:+.4f}')

    say('\n== 3. Parameter grid (primary definition): mean Net % by gap window N x list size K')
    grid = {}
    for n in WINDOWS:
        for size in SIZES:
            g = stats(top5_days(pit, 'pit_auction', lists(pm, pit_cands, n, size)))
            grid[(n, size)] = g['mean']
            say(f'   N={n:2d} K={size:3d}  {fmt(g)}')
    means = sorted(grid.values(), reverse=True)
    positive = sum(v > 0 for v in means)
    stable = positive >= 9 and float(np.median(means)) > 0
    say(f'   positive cells {positive}/12, median {np.median(means):+.3f}, (20, 300) rank {means.index(grid[(20, 300)]) + 1}/12'
        f" -> {'STABLE' if stable else 'UNSTABLE'}")

    say('\n== 4. Sensitivity')
    flagged = dart_flagged(c, codes, days)
    sd = stats(top5_days(pit, 'pit_auction', lists(pm, {d: s - flagged.get(d, set()) for d, s in pit_cands.items()}, 20, 300),
                         flagged))
    say(f'   PIT-DART (exchange-notice proxy excluded): {fmt(sd)}')
    now = picks[[meta.get(s, {}).get('flagged_now', False) for s in picks['sid']]]
    say(f"   primary picks flagged managed/halted now: {len(now)}/{len(picks)}, their mean Net "
        f"{now['pit_auction'].mean() if len(now) else float('nan'):+.3f}")
    pd.DataFrame(series).sort_index().to_csv(HERE / 'pit_days.csv')
    (HERE / 'result.txt').write_text('\n'.join(out) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
