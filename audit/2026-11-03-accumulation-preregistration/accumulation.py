"""Quiet accumulation -> 3-4 day hold, pre-registered study (README.md in this folder, 2026-11-03).

This script carries the README definitions over unchanged and was committed before its first run. Implementation
details the README leaves open were fixed here, also before running:
  Trading days = dates with >= 500 stocks in daily_adjusted_candle. Day 1..4 = the next four trading days after d; the
    stock must have a bar on each of them (otherwise the row is skipped as halted / missing).
  d-20, d-19..d, d-79..d-20 are the stock's own previous bars (positions), as in the earlier daily explorations.
  Quintiles inside FLAT: rows sorted by (factor value, stock id); Q1 = first n//5 rows, Q5 = rows from 4n//5. A factor
    that is missing for a stock leaves it out of that factor's quintiles only; the day still needs >= 50 rows.
  Daily series: per signal day, mean Net of Q5 / Q1 / FLAT / ALL for each exit. Q5 - ALL uses ALL of the same day and
    exit. Days without a Q5 row are skipped for that factor.
  Intraday study: ALL / FLAT come from the adjusted daily bars at d; entry and exits use raw 5-minute bars. Day 1 needs a
    first bar starting at the session open (09:00; 10:00 on 2025-11-13 and 2026-01-02), day 4 a last bar starting five
    minutes before the close (15:25; 16:25 on 2025-11-13). Days 2-3 need any bars. When a day's high and low both reach
    target and stop (and the open did not already decide), that day's 5-minute bars are read in order; within one bar
    both = stop. S4 needs intraday bars on each of d-4..d.
  Verdicts: KEEP / MARKET_ONLY / REVERSE / DROP exactly as the README defines them.
Run: python accumulation.py [--smoke]   (reads the repository .env, never prints credentials)
  --smoke loads three stocks and prints only row counts (no returns, no statistics).
"""
import bisect
import datetime as dt
import random
import sys
from array import array
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import block_ci, connect, mean, share  # noqa: E402

BUY, SELL = 0.00095, 0.00295
SEED, BLOCK = 20261103, 10
LAST_EXIT = dt.date(2026, 10, 22)
MIN_VALUE = 1_000_000_000
MIN_FLAT = 50
TARGET, STOP = 0.05, -0.03
OUTLIER = 0.50
DAILY_PERIODS = {'discovery': (dt.date(2018, 4, 1), dt.date(2022, 6, 30)),
                 'check': (dt.date(2022, 7, 1), LAST_EXIT)}
INTRADAY_PERIODS = {'discovery': (dt.date(2025, 11, 6), dt.date(2026, 4, 30)),
                    'check': (dt.date(2026, 5, 1), LAST_EXIT)}
INTRADAY_FROM = dt.date(2025, 10, 30)
SPECIAL = {dt.date(2025, 11, 13): (dt.time(10, 0), dt.time(16, 25)),
           dt.date(2026, 1, 2): (dt.time(10, 0), dt.time(15, 25))}
EXITS = ['E1_HOLD4', 'E2_TARGET5', 'E3_TARGET5_STOP3', 'E4_OPEN_PROFIT']
DAILY_FACTORS = ['S1_VOL_EXPANSION', 'S2_CMF20', 'S3_LOWER_WICK20']
CHUNK = 150


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def session(day):
    """(first bar start, last bar start) on the wall clock."""
    return SPECIAL.get(day, (dt.time(9, 0), dt.time(15, 25)))


def walk(entry, bars, stop):
    """Walks (open, high, low) bars in time order. Returns (price, 'T'|'S') or None. Both in one bar = stop."""
    target, floor = entry * (1 + TARGET), entry * (1 + STOP)
    for o, h, l in bars:
        if o >= target:
            return o, 'T'
        if stop and o <= floor:
            return o, 'S'
        hit_t, hit_s = h >= target, stop and l <= floor
        if hit_s:
            return floor, 'S'
        if hit_t:
            return target, 'T'
    return None


def exits(entry, days, resolver=None):
    """days: four (open, high, low, close). resolver(k) -> that day's 5-minute (open, high, low) bars or None.
    Returns {exit: (price, held_days, reason)}."""
    out = {'E1_HOLD4': (days[3][3], 4, 'END')}
    for name, stop in (('E2_TARGET5', False), ('E3_TARGET5_STOP3', True)):
        result = None
        for k, (o, h, l, c) in enumerate(days):
            target, floor = entry * (1 + TARGET), entry * (1 + STOP)
            first = entry if k == 0 else o
            if first >= target:
                result = (first, k + 1, 'T'); break
            if stop and first <= floor:
                result = (first, k + 1, 'S'); break
            hit_t, hit_s = h >= target, stop and l <= floor
            if hit_t and hit_s:
                bars = resolver(k) if resolver else None
                hit = walk(entry, bars, stop) if bars else (floor, 'S')
                if hit is None:   # the 5-minute path never crossed (aggregate rounding): treat as stop, conservative
                    hit = (floor, 'S')
                result = (hit[0], k + 1, hit[1]); break
            if hit_s:
                result = (floor, k + 1, 'S'); break
            if hit_t:
                result = (target, k + 1, 'T'); break
        out[name] = result or (days[3][3], 4, 'END')
    result = None
    for k in (1, 2, 3):
        o = days[k][0]
        if o * (1 - SELL) > entry * (1 + BUY):
            result = (o, k + 1, 'P'); break
    out['E4_OPEN_PROFIT'] = result or (days[3][3], 4, 'END')
    return out


class Series:
    __slots__ = ('day', 'o', 'h', 'l', 'c', 'v', 'val')

    def __init__(self):
        self.day = array('i')
        self.o, self.h, self.l, self.c = array('d'), array('d'), array('d'), array('d')
        self.v, self.val = array('d'), array('d')


def load_daily(c, only=None):
    names = {sid: (code, name) for sid, code, name in c.execute('select id, stock_code, stock_name from stock')}
    allowed = sorted(sid for sid, (code, name) in names.items() if share(code, name))
    if only:
        allowed = set(allowed[:only])
    else:
        allowed = set(allowed)
    bars = {}
    with c.cursor(name='daily') as cur:
        cur.itersize = 50_000
        cur.execute("""select stock_id, trade_date, open_price, high_price, low_price, close_price, volume, trading_value
                         from daily_adjusted_candle where open_price > 0 and close_price > 0 and trade_date <= %s
                        order by stock_id, trade_date""", (LAST_EXIT,))
        for sid, d, o, h, l, cl, v, tv in cur:
            if sid not in allowed:
                continue
            s = bars.get(sid)
            if s is None:
                s = bars[sid] = Series()
            s.day.append(d.toordinal()); s.o.append(float(o)); s.h.append(float(h)); s.l.append(float(l))
            s.c.append(float(cl)); s.v.append(float(v or 0)); s.val.append(float(tv or 0))
    trading = [d.toordinal() for (d,) in c.execute(
        """select trade_date from daily_adjusted_candle where trade_date <= %s group by trade_date
            having count(*) >= 500 order by trade_date""", (LAST_EXIT,))]
    return bars, trading


def signals(s, i):
    """ALL / FLAT membership and S1-S3 at position i, or None when the stock is not in ALL."""
    if i < 79 or sum(s.val[i - 19:i + 1]) / 20 < MIN_VALUE:
        return None
    if i >= 1 and s.c[i] / s.c[i - 1] - 1 >= 0.29:
        return None
    hi, lo = max(s.h[i - 19:i + 1]), min(s.l[i - 19:i + 1])
    flat = abs(s.c[i] / s.c[i - 20] - 1) <= 0.05 and lo > 0 and hi / lo - 1 <= 0.20
    f = {}
    if flat:
        before = sum(s.val[i - 79:i - 19]) / 60
        f['S1_VOL_EXPANSION'] = (sum(s.val[i - 19:i + 1]) / 20) / before if before > 0 else None
        num = den = 0.0
        wicks = []
        for k in range(i - 19, i + 1):
            rng = s.h[k] - s.l[k]
            clv = ((s.c[k] - s.l[k]) - (s.h[k] - s.c[k])) / rng if rng > 0 else 0.0
            num += clv * s.v[k]; den += s.v[k]
            if rng > 0:
                wicks.append((min(s.o[k], s.c[k]) - s.l[k]) / rng)
        f['S2_CMF20'] = num / den if den > 0 else None
        f['S3_LOWER_WICK20'] = mean(wicks) if len(wicks) >= 15 else None
    return flat, f


def hold_days(s, i, trading, tpos):
    """Positions i+1..i+4 if they are the next four trading days, else None."""
    t = tpos.get(s.day[i])
    if t is None or t + 4 >= len(trading) or i + 4 >= len(s.day):
        return None
    if any(s.day[i + k] != trading[t + k] for k in range(1, 5)):
        return None
    return [i + k for k in range(1, 5)]


def daily_study(bars, trading):
    tpos = {d: k for k, d in enumerate(trading)}
    last_signal = max(d for d in trading if d <= LAST_EXIT.toordinal())
    all_sum = defaultdict(lambda: [0.0] * 4)
    all_n = defaultdict(lambda: [0] * 4)
    flat_rows = defaultdict(list)
    outliers = defaultdict(int)
    start = DAILY_PERIODS['discovery'][0].toordinal()
    for sid, s in bars.items():
        for i in range(79, len(s.day) - 4):
            d = s.day[i]
            if d < start or d > last_signal:
                continue
            hold = hold_days(s, i, trading, tpos)
            if hold is None:
                continue
            sig = signals(s, i)
            if sig is None:
                continue
            entry = s.o[hold[0]]
            if entry >= s.c[i] * 1.29:
                continue
            days = [(s.o[k], s.h[k], s.l[k], s.c[k]) for k in hold]
            res = exits(entry, days)
            nets = []
            for e_idx, e in enumerate(EXITS):
                price = res[e][0]
                if abs(price / entry - 1) > OUTLIER:
                    outliers[e] += 1
                    nets.append(None)
                    continue
                value = net(entry, price)
                nets.append(value)
                all_sum[d][e_idx] += value
                all_n[d][e_idx] += 1
            flat, f = sig
            if flat:
                flat_rows[d].append((sid, f, nets, [res[e][1:] for e in EXITS]))
    all_mean = {d: [all_sum[d][k] / all_n[d][k] if all_n[d][k] else None for k in range(4)] for d in all_sum}
    return flat_rows, all_mean, dict(outliers)


def day_series(flat_rows, all_mean, factor, e_idx):
    """{day: (q5, q1, flat, all, q5 rows)} for one factor and exit."""
    out = {}
    for d, rows in flat_rows.items():
        if len(rows) < MIN_FLAT or all_mean.get(d) is None or all_mean[d][e_idx] is None:
            continue
        items = sorted((r[1][factor], r[0], r[2][e_idx], r[3][e_idx]) for r in rows if r[1].get(factor) is not None)
        if len(items) < MIN_FLAT:
            continue
        n = len(items)
        q5 = [x for x in items[4 * n // 5:] if x[2] is not None]
        q1 = [x for x in items[:n // 5] if x[2] is not None]
        everyone = [r[2][e_idx] for r in rows if r[2][e_idx] is not None]
        if not q5 or not q1:
            continue
        out[d] = (mean([x[2] for x in q5]), mean([x[2] for x in q1]), mean(everyone), all_mean[d][e_idx], q5)
    return out


def report(series, periods, rng, verdict, label):
    stats = {}
    print(f'\n-- {label}')
    for p, (lo, hi) in periods.items():
        ds = sorted(d for d in series if lo.toordinal() <= d <= hi.toordinal())
        if len(ds) < BLOCK * 2:
            print(f'   {p:9s} too few days ({len(ds)})'); stats[p] = None; continue
        q5 = [series[d][0] for d in ds]
        excess = [series[d][0] - series[d][3] for d in ds]
        rows = [x for d in ds for x in series[d][4]]
        held = mean([x[3][0] for x in rows])
        reasons = defaultdict(int)
        for x in rows:
            reasons[x[3][1]] += 1
        mix = ' '.join(f'{k} {v / len(rows) * 100:.0f}%' for k, v in sorted(reasons.items()))
        s = {'q5': block_ci(q5, BLOCK, rng), 'excess': block_ci(excess, BLOCK, rng),
             'spread': mean([series[d][0] - series[d][1] for d in ds]), 'flat': mean([series[d][2] for d in ds]),
             'all': mean([series[d][3] for d in ds])}
        stats[p] = s
        print(f"   {p:9s} days {len(ds):4d} Q5 rows {len(rows):6d} | Q5 Net {s['q5'][0]:+.3f} [{s['q5'][1]:+.3f}, "
              f"{s['q5'][2]:+.3f}] | Q5-ALL {s['excess'][0]:+.3f} [{s['excess'][1]:+.3f}, {s['excess'][2]:+.3f}] %p "
              f"| Q5-Q1 {s['spread']:+.3f} | FLAT {s['flat']:+.3f} ALL {s['all']:+.3f} | held {held:.2f}d {mix}")
    if not verdict:
        return None
    dsc, chk = stats.get('discovery'), stats.get('check')
    if dsc is None or chk is None:
        v = 'DROP (too few days)'
    elif dsc['excess'][0] > 0 and chk['excess'][0] > 0 and dsc['excess'][1] > 0:
        v = 'KEEP' if dsc['q5'][0] > 0 and chk['q5'][0] > 0 else 'MARKET_ONLY'
    elif dsc['excess'][0] < 0 and chk['excess'][0] < 0 and dsc['excess'][2] < 0:
        v = 'REVERSE'
    else:
        v = 'DROP'
    if dsc and chk:
        v += f" (Q5-ALL {dsc['excess'][0]:+.3f} / {chk['excess'][0]:+.3f}, Q5 Net {dsc['q5'][0]:+.3f} / {chk['q5'][0]:+.3f})"
    print(f'   -> {v}')
    return v


def yearly(series):
    by_year = defaultdict(list)
    for d, x in series.items():
        by_year[dt.date.fromordinal(d).year].append(x[0] - x[3])
    return ', '.join(f'{y} {mean(v):+.2f}' for y, v in sorted(by_year.items()))


def load_intraday(c, ids):
    """Per (stock, day): first bar start/open, last bar start/close, high, low, up value, down value, total value."""
    lo = dt.datetime.combine(INTRADAY_FROM, dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    hi = dt.datetime.combine(LAST_EXIT + dt.timedelta(days=1), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    rows = {}
    ids = sorted(ids)
    for k in range(0, len(ids), CHUNK):
        chunk = ids[k:k + CHUNK]
        for r in c.execute(
                """with b as (select stock_id, start_time, open_price, high_price, low_price, close_price, trading_value,
                                     (start_time at time zone 'Asia/Seoul') lt
                                from intraday_research_bar where stock_id = any(%s) and start_time >= %s and start_time < %s)
                   select stock_id, lt::date, min(lt::time), (array_agg(open_price order by start_time))[1],
                          max(lt::time), (array_agg(close_price order by start_time desc))[1], max(high_price), min(low_price),
                          coalesce(sum(trading_value) filter (where close_price > open_price), 0),
                          coalesce(sum(trading_value) filter (where close_price < open_price), 0), sum(trading_value)
                     from b group by stock_id, lt::date""", (chunk, lo, hi)):
            rows[(r[0], r[1].toordinal())] = r[2:]
        print(f'  intraday loaded {min(k + CHUNK, len(ids))}/{len(ids)} stocks, {len(rows)} stock-days', flush=True)
    return rows


def intraday_study(c, bars, trading, intra):
    tpos = {d: k for k, d in enumerate(trading)}
    intraday_days = sorted({d for _, d in intra})
    ipos = {d: k for k, d in enumerate(intraday_days)}
    start = INTRADAY_PERIODS['discovery'][0].toordinal()
    cache = {}

    def bars_of(sid, day):
        key = (sid, day)
        if key not in cache:
            date = dt.date.fromordinal(day)
            lo = dt.datetime.combine(date, dt.time(0), dt.timezone(dt.timedelta(hours=9)))
            cache[key] = [(float(o), float(h), float(l)) for o, h, l in c.execute(
                """select open_price, high_price, low_price from intraday_research_bar
                    where stock_id = %s and start_time >= %s and start_time < %s order by start_time""",
                (sid, lo, lo + dt.timedelta(days=1)))]
        return cache[key]

    all_sum = defaultdict(lambda: [0.0] * 4)
    all_n = defaultdict(lambda: [0] * 4)
    flat_rows = defaultdict(list)
    outliers, missing, resolved = defaultdict(int), 0, 0
    for sid, s in bars.items():
        for i in range(79, len(s.day) - 4):
            d = s.day[i]
            if d < start or d > LAST_EXIT.toordinal():
                continue
            hold = hold_days(s, i, trading, tpos)
            if hold is None:
                continue
            sig = signals(s, i)
            if sig is None:
                continue
            hold_days_ = [s.day[k] for k in hold]
            agg = [intra.get((sid, h)) for h in hold_days_]
            first_ok = agg[0] is not None and agg[0][0] == session(dt.date.fromordinal(hold_days_[0]))[0]
            last_ok = agg[3] is not None and agg[3][2] == session(dt.date.fromordinal(hold_days_[3]))[1]
            if not first_ok or not last_ok or agg[1] is None or agg[2] is None:
                missing += 1
                continue
            entry = float(agg[0][1])
            if entry >= s.c[i] * 1.29:
                continue
            days = [(float(a[1]), float(a[4]), float(a[5]), float(a[3])) for a in agg]

            def resolver(k, sid=sid, hd=hold_days_):
                nonlocal resolved
                resolved += 1
                return bars_of(sid, hd[k])

            res = exits(entry, days, resolver)
            nets = []
            for e_idx, e in enumerate(EXITS):
                price = res[e][0]
                if abs(price / entry - 1) > OUTLIER:
                    outliers[e] += 1; nets.append(None); continue
                value = net(entry, price)
                nets.append(value)
                all_sum[d][e_idx] += value; all_n[d][e_idx] += 1
            flat, f = sig
            if flat:
                f = dict(f)
                k = ipos.get(d)
                pressure = None
                if k is not None and k >= 4:
                    window = [intra.get((sid, intraday_days[j])) for j in range(k - 4, k + 1)]
                    if all(w is not None for w in window) and intraday_days[k - 4] >= INTRADAY_FROM.toordinal():
                        total = sum(float(w[8] or 0) for w in window)
                        if total > 0:
                            pressure = sum(float(w[6]) - float(w[7]) for w in window) / total
                f['S4_BUY_PRESSURE5'] = pressure
                flat_rows[d].append((sid, f, nets, [res[e][1:] for e in EXITS]))
    all_mean = {d: [all_sum[d][k] / all_n[d][k] if all_n[d][k] else None for k in range(4)] for d in all_sum}
    return flat_rows, all_mean, dict(outliers), missing, resolved


def main():
    c = connect()
    if '--smoke' in sys.argv:
        bars, trading = load_daily(c, only=3)
        print(f'smoke: daily stocks {len(bars)}, bars {sum(len(s.day) for s in bars.values())}, trading days {len(trading)}')
        intra = load_intraday(c, bars.keys())
        print(f'smoke: intraday stock-days {len(intra)}, first-bar times '
              f'{sorted({str(v[0]) for v in intra.values()})[:4]}, last-bar times {sorted({str(v[2]) for v in intra.values()})[-4:]}')
        return
    bars, trading = load_daily(c)
    print(f'daily: {len(bars)} stocks, {len(trading)} trading days '
          f'{dt.date.fromordinal(trading[0])}..{dt.date.fromordinal(trading[-1])}', flush=True)

    flat_rows, all_mean, outliers = daily_study(bars, trading)
    print(f'study 1 rows: FLAT {sum(len(v) for v in flat_rows.values())} on {len(flat_rows)} days; outliers {outliers or "none"}')
    print('\n===== Study 1: daily, 8 years (S1-S3 x E1-E4 = 12 comparisons)')
    summary = []
    for factor in DAILY_FACTORS:
        for e_idx, e in enumerate(EXITS):
            series = day_series(flat_rows, all_mean, factor, e_idx)
            v = report(series, DAILY_PERIODS, random.Random(SEED), True, f'{factor} x {e}')
            summary.append((factor, e, v))
            if e == 'E1_HOLD4':
                print(f'   by year Q5-ALL: {yearly(series)}')

    ids = set(bars)
    intra = load_intraday(c, ids)
    i_rows, i_all, i_out, missing, resolved = intraday_study(c, bars, trading, intra)
    print(f'\nstudy 2 rows: FLAT {sum(len(v) for v in i_rows.values())} on {len(i_rows)} days; outliers {i_out or "none"}; '
          f'ALL rows skipped for missing intraday bars {missing}; same-day target/stop resolved from 5-minute bars {resolved}')
    print('\n===== Study 2: 5-minute, 1 year (S4 x E1-E4 = 4 comparisons)')
    for e_idx, e in enumerate(EXITS):
        v = report(day_series(i_rows, i_all, 'S4_BUY_PRESSURE5', e_idx), INTRADAY_PERIODS, random.Random(SEED), True,
                   f'S4_BUY_PRESSURE5 x {e}')
        summary.append(('S4_BUY_PRESSURE5', e, v))

    print('\n===== Summary (16 comparisons)')
    for factor, e, v in summary:
        print(f'   {factor:18s} {e:18s} {v}')

    print('\n===== Reference: S1-S3 on the 1-year window with 5-minute exits (descriptive, no verdict)')
    for factor in DAILY_FACTORS:
        for e_idx, e in enumerate(EXITS):
            report(day_series(i_rows, i_all, factor, e_idx), INTRADAY_PERIODS, random.Random(SEED), False,
                   f'{factor} x {e}')


if __name__ == '__main__':
    main()
