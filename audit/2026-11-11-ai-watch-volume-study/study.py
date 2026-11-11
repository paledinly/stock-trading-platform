"""AI watch rule P volume-condition comparison on research 5-minute bars (2026-11-11). See PROTOCOL.md.

Run: python extract.py && python study.py
"""
import datetime as dt
import json
from collections import defaultdict
from pathlib import Path
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
DATA = HERE / 'data'
KST = ZoneInfo('Asia/Seoul')
SLOTS = 78                      # 09:00 .. 15:25
FIRST_EVAL, LAST_EVAL, EXIT_SLOT = 3, 65, 75   # bar starts 09:15 .. 14:25 (ends 14:30), exit on the 15:15 bar
PERIODS = {'discovery': (dt.date(2025, 11, 13), dt.date(2026, 6, 30)),
           'check': (dt.date(2026, 7, 1), dt.date(2026, 11, 9))}
HALF_SPREAD, BUY_COST, SELL_COST = 0.0006, 0.00095, 0.00295
VARIANTS = {'NONE': None, 'R1.0': ('R', 1.0), 'R1.5': ('R', 1.5), 'R2.0': ('R', 2.0), 'R3.0': ('R', 3.0),
            'C1.0': ('C', 1.0), 'C1.5': ('C', 1.5), 'C2.0': ('C', 2.0)}
DRAWS, SEED = 10_000, 20261111


def load():
    frames = [pd.read_pickle(p) for p in sorted(DATA.glob('bars-*.pkl'))]
    bars = pd.concat(frames, ignore_index=True)
    t = pd.to_datetime(bars.ts, unit='s', utc=True).dt.tz_convert(KST)
    bars['d'] = t.dt.date
    bars['slot'] = ((t.dt.hour * 60 + t.dt.minute) - 540) // 5
    bars = bars[(bars.slot >= 0) & (bars.slot < SLOTS)]
    meta = pd.read_pickle(DATA / 'meta.pkl')
    return bars, meta


def day_matrices(g):
    """Stock x slot matrices for one day (NaN where no bar)."""
    sids = np.sort(g.sid.unique())
    row = np.searchsorted(sids, g.sid.values)
    out = {}
    for k in ('o', 'h', 'l', 'c', 'v', 'val'):
        m = np.full((len(sids), SLOTS), np.nan)
        m[row, g.slot.values] = g[k].values.astype(float)
        out[k] = m
    return sids, out


def first_true(mask):
    any_ = mask.any(axis=1)
    return np.where(any_, mask.argmax(axis=1), -1)


def simulate(m, i, k, stop):
    """Entry on the next existing bar's open after slot k; returns entry, E1 net, E2 net or None."""
    o, h, l, c = m['o'][i], m['h'][i], m['l'][i], m['c'][i]
    j = k + 1
    while j <= EXIT_SLOT and np.isnan(o[j]):
        j += 1
    if j > EXIT_SLOT:
        return None
    entry = o[j] * (1 + HALF_SPREAD)
    take, floor = entry * 1.03, entry * 0.98
    e1 = e2 = None
    last = c[j]
    for s in range(j, EXIT_SLOT + 1):
        if np.isnan(o[s]):
            continue
        last = c[s]
        if e1 is None:
            if o[s] <= stop and s > j:
                e1 = o[s]
            elif l[s] <= stop:
                e1 = min(stop, o[s])
        if e2 is None:
            if s > j and o[s] <= floor:
                e2 = o[s]
            elif s > j and o[s] >= take:
                e2 = o[s]
            elif l[s] <= floor:
                e2 = floor
            elif h[s] >= take:
                e2 = take
        if e1 is not None and e2 is not None:
            break
    e1 = last if e1 is None else e1
    e2 = last if e2 is None else e2
    net = lambda x: x * (1 - SELL_COST) / (entry * (1 + BUY_COST)) - 1
    return entry, net(e1), net(e2)


def run():
    bars, meta = load()
    market_of = dict(zip(meta.sid, meta.market))
    counts = bars.groupby('d').sid.nunique()
    days = sorted(d for d, n in counts.items() if n >= 500)
    by_day = {d: g for d, g in bars[bars.d.isin(days)].groupby('d')}
    vol_hist, val_hist = {}, {}
    trades, quality = [], defaultdict(int)
    for n, d in enumerate(days):
        sids, m = day_matrices(by_day[d])
        vol = np.nan_to_num(m['v'])
        vol_hist[d] = pd.DataFrame(vol, index=sids)
        val_hist[d] = pd.Series(np.nansum(m['val'], axis=1), index=sids)
        prior = days[max(0, n - 20):n]
        if not (PERIODS['discovery'][0] <= d <= PERIODS['check'][1]) or len(prior) < 10:
            continue
        present = sum(vol_hist[p].index.to_series().reindex(sids).notna().astype(int).values for p in prior)
        slot_sum = sum(vol_hist[p].reindex(sids).fillna(0).values for p in prior)
        cum_sum = sum(np.cumsum(vol_hist[p].reindex(sids).fillna(0).values, axis=1) for p in prior)
        val_sum = sum(val_hist[p].reindex(sids).fillna(0).values for p in prior)
        with np.errstate(invalid='ignore', divide='ignore'):
            avg = slot_sum / present[:, None]
            cum_avg = cum_sum / present[:, None]
            avg_value = np.where(present > 0, val_sum / present, 0)
        rank = (-avg_value).argsort().argsort() + 1
        tier = np.where(rank <= 100, 'LARGE', np.where(rank <= 400, 'MID', 'SMALL'))

        o, h, l, c, v, val = (m[k] for k in ('o', 'h', 'l', 'c', 'v', 'val'))
        exists = ~np.isnan(c)
        day_open = o[:, 0]
        ok = (present >= 10) & ~np.isnan(day_open) & exists[:, :3].all(axis=1)
        quality['stock_days'] += len(sids)
        quality['eligible'] += int(ok.sum())
        or_h, or_l = np.nanmax(h[:, :3], axis=1), np.nanmin(l[:, :3], axis=1)
        cfill = pd.DataFrame(c).ffill(axis=1).values
        cumv, cumval = np.nancumsum(v, axis=1), np.nancumsum(val, axis=1)
        with np.errstate(invalid='ignore', divide='ignore'):
            vwap = cumval / cumv
            atr = pd.DataFrame((h - l).T).rolling(12, min_periods=3).mean().T.values
            rvol = v / avg
            crvol = cumv / cum_avg
            from_open = cfill / day_open[:, None] - 1

        # Market proxy: value-weighted from-open move of LARGE+MID stocks per market.
        mkt = np.array([market_of.get(s) for s in sids])
        market_ok = np.ones((len(sids), SLOTS), dtype=bool)
        for name in ('KOSPI', 'KOSDAQ'):
            pick = (rank <= 400) & (mkt == name) & ~np.isnan(day_open)
            if pick.sum() < 5:
                quality[f'market_proxy_missing_{name}'] += 1
                continue
            fo = np.nan_to_num(from_open[pick], nan=0.0)
            w = avg_value[pick]
            index = (fo * w[:, None]).sum(axis=0) / w.sum()
            market_ok &= (index >= -0.005)[None, :]

        with np.errstate(invalid='ignore'):
            stop = np.maximum(or_l[:, None], vwap - 0.5 * atr)
            base = (exists & (c > or_h[:, None]) & (c > vwap) & (c / day_open[:, None] - 1 <= 0.08)
                    & (c / o - 1 < 0.02) & (c / or_h[:, None] - 1 <= 0.02)
                    & (c - stop >= atr) & (c - stop >= 0.008 * c) & ~np.isnan(atr))
            below = exists & (c < or_l[:, None])
        below[:, :2] = False
        invalid = np.cumsum(below, axis=1) > 0
        window = np.zeros(SLOTS, dtype=bool)
        window[FIRST_EVAL:LAST_EVAL + 1] = True
        base &= ~invalid & window[None, :] & ok[:, None]
        with np.errstate(invalid='ignore'):
            vol_ok = {'R': lambda x: rvol >= x, 'C': lambda x: crvol >= x}
            open_net = cfill[:, EXIT_SLOT] * (1 - SELL_COST) / (day_open * (1 + HALF_SPREAD) * (1 + BUY_COST)) - 1
        period = 'discovery' if d <= PERIODS['discovery'][1] else 'check'
        for market_filter in (True, False):
            gate = base & market_ok if market_filter else base
            for name, spec in VARIANTS.items():
                mask = gate if spec is None else gate & vol_ok[spec[0]](spec[1])
                ks = first_true(mask)
                for i in np.nonzero(ks >= 0)[0]:
                    k = ks[i]
                    r = simulate(m, i, k, stop[i, k])
                    if r is None:
                        quality['no_entry_bar'] += 1
                        continue
                    trades.append({'d': d, 'period': period, 'variant': name, 'market': market_filter,
                                   'sid': int(sids[i]), 'tier': tier[i], 'slot': int(k),
                                   'rvol': float(rvol[i, k]), 'crvol': float(crvol[i, k]),
                                   'e1': r[1], 'e2': r[2], 'open_buy': float(open_net[i])})
        quality['days'] += 1
        if n % 20 == 0:
            print(d, len(trades), flush=True)
    t = pd.DataFrame(trades)
    t.to_pickle(DATA / 'trades.pkl')
    (HERE / 'quality.json').write_text(json.dumps(dict(quality), indent=2), encoding='utf-8')
    return t


def clock(slot):
    """End time of a 5-minute slot, which is when the rule judges it."""
    minutes = 540 + 5 * slot + 5
    return f'{minutes // 60:02d}:{minutes % 60:02d}'


def boot_mean(df, col, rng):
    g = df.groupby('d')[col].agg(['sum', 'count'])
    s, n = g['sum'].values, g['count'].values
    idx = rng.integers(0, len(g), (DRAWS, len(g)))
    means = s[idx].sum(axis=1) / n[idx].sum(axis=1)
    return np.percentile(means, [2.5, 97.5])


def boot_diff(a, b, col, rng):
    days = sorted(set(a.d) | set(b.d))
    ga = a.groupby('d')[col].agg(['sum', 'count']).reindex(days, fill_value=0)
    gb = b.groupby('d')[col].agg(['sum', 'count']).reindex(days, fill_value=0)
    idx = rng.integers(0, len(days), (DRAWS, len(days)))
    with np.errstate(invalid='ignore', divide='ignore'):
        diff = (ga['sum'].values[idx].sum(1) / ga['count'].values[idx].sum(1)
                - gb['sum'].values[idx].sum(1) / gb['count'].values[idx].sum(1))
    return np.nanpercentile(diff, [2.5, 97.5])


def report(t):
    rng = np.random.default_rng(SEED)
    rows, diffs = [], []
    n_days = {p: t[t.period == p].d.nunique() for p in PERIODS}
    for market in (True, False):
        for period in PERIODS:
            for tier in ('ALL', 'LARGE', 'MID', 'SMALL'):
                sub = t[(t.market == market) & (t.period == period)]
                if tier != 'ALL':
                    sub = sub[sub.tier == tier]
                none = sub[sub.variant == 'NONE']
                for name in VARIANTS:
                    x = sub[sub.variant == name]
                    if len(x) == 0:
                        rows.append({'market_filter': market, 'period': period, 'tier': tier, 'variant': name, 'trades': 0})
                        continue
                    x = x.assign(excess=x.e1 - x.open_buy)
                    lo, hi = boot_mean(x, 'e1', rng) if len(x) >= 10 else (np.nan, np.nan)
                    row = {'market_filter': market, 'period': period, 'tier': tier, 'variant': name,
                           'trades': len(x), 'per_day': len(x) / n_days[period],
                           'e1_net': x.e1.mean() * 100, 'e1_lo': lo * 100, 'e1_hi': hi * 100,
                           'e1_win': (x.e1 > 0).mean() * 100, 'e2_net': x.e2.mean() * 100,
                           'excess_vs_open': x.excess.mean() * 100,
                           'median_signal_at': clock(int(x.slot.median()))}
                    rows.append(row)
                    if name != 'NONE' and len(none) >= 10 and len(x) >= 10:
                        dlo, dhi = boot_diff(x, none, 'e1', rng)
                        diffs.append({'market_filter': market, 'period': period, 'tier': tier, 'variant': name,
                                      'minus_none': (x.e1.mean() - none.e1.mean()) * 100,
                                      'lo': dlo * 100, 'hi': dhi * 100})
    res = pd.DataFrame(rows)
    dif = pd.DataFrame(diffs)
    res.to_csv(HERE / 'comparison.csv', index=False, float_format='%.4f')
    dif.to_csv(HERE / 'vs_none.csv', index=False, float_format='%.4f')
    return res, dif, n_days


if __name__ == '__main__':
    trades = run()
    res, dif, n_days = report(trades)
    pd.set_option('display.width', 220, 'display.max_rows', 400)
    print(n_days)
    print(res.round(3).to_string())
    print(dif.round(3).to_string())
