"""Entry / exit / holding-days map, exploration (2026-11-03). Hypothesis generation only - no keep rule.

Question: with the collected data, when to buy (close vs next open), when to sell (open or close of day 1..10, or a
time of day 1), how many days it takes and how much is left after costs, for the signal groups found so far.

Fixed before running:
  Part A - adjusted daily bars 2018-04-01..(day 10 <= 2026-10-22). Share filter, >= 80 bars, mean trading value of the
    last 20 bars >= 1bn KRW (ALL). Groups at signal day d (information up to the close of d):
      ALL      every row
      ACC      FLAT (|20-day return| <= 5%, 20-day high/low - 1 <= 20%) and CMF20 in the top fifth of FLAT that day
               (same definitions as the 2026-11-03 accumulation pre-registration; days with < 50 FLAT rows have no ACC)
      STRONG   +3% <= day return < +29%
      STRONG_H STRONG and close location (close - low) / (high - low) >= 0.8 (closed near the high)
      STRONG_P STRONG and close location <= 0.5 (pulled back from the high)
      DROP5    day return <= -5%
    Entries: C0 = close of d (bought into the close), O1 = open of d+1 (skipped when >= close d x 1.29).
    Exits: Ok = open of day d+k, Ck = close of day d+k, k = 1..10 (exit must be after entry; O1 entry has no O1 exit).
    Days d+1..d+k must be the stock's consecutive trading days. Net with ADR-008 BASE costs (buy 0.095%, sell 0.295%).
    Per cell: mean of daily group means (days with >= 5 rows in the group), win rate (Net > 0), and for C0 entries the
    share of rows whose high reached +5% / +10% and whose low reached -5% within 5 days.
    Periods: first 2018-04-01..2022-06-30, second 2022-07-01..end. 95% intervals (day blocks of 10, 10,000 draws,
    seed 20261103) only for the best cell of each group and entry.
  Part B - 5-minute bars, signal days 2025-10-31..2026-10-21 (no special session days as d or d+1).
    Groups from 15:15 information: ALL = 15:15 cumulative value >= 1bn KRW (same universe as the 2026-10-29 study);
    STRONG = 15:15 price / previous close - 1 >= 3% (below +29.5%); STRONG_H / STRONG_P = STRONG and 15:15 price within
    1% of the day high / more than 3% below it.
    Entry: close of the 15:15 bar (operational entry). Exits on day 1: 09:00 open, 09:30, 10:00, 11:00, 13:00, 15:15
    (close of the 09:25 / 09:55 / 10:55 / 12:55 / 15:10 bars), close (15:25 bar); day 2 open, day 2 close.
    Periods: halves at 2026-04-30.
Known bias: delisted stocks are absent; results look better than reality, most for DROP5 and small caps.
Run: python horizon.py   (reads the repository .env, never prints credentials)
"""
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
START = dt.date(2018, 4, 1)
SPLIT = dt.date(2022, 6, 30)
MIN_VALUE = 1_000_000_000
H = 10
GROUPS = ['ALL', 'ACC', 'STRONG', 'STRONG_H', 'STRONG_P', 'DROP5']
SPECIAL = {dt.date(2025, 11, 13), dt.date(2026, 1, 2)}
CHUNK = 150


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


class Series:
    __slots__ = ('day', 'o', 'h', 'l', 'c', 'v', 'val')

    def __init__(self):
        self.day = array('i')
        self.o, self.h, self.l, self.c = array('d'), array('d'), array('d'), array('d')
        self.v, self.val = array('d'), array('d')


def load_daily(c):
    allowed = {sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name)}
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


def cells():
    out = []
    for entry in ('C0', 'O1'):
        for k in range(1, H + 1):
            for kind in ('O', 'C'):
                if entry == 'O1' and kind == 'O' and k == 1:
                    continue
                out.append((entry, f'{kind}{k}'))
    return out


CELLS = cells()


NC = len(CELLS)


def row_nets(s, i):
    c0, o1 = s.c[i], s.o[i + 1]
    out = []
    for entry, ex in CELLS:
        k = int(ex[1:])
        price = s.o[i + k] if ex[0] == 'O' else s.c[i + k]
        if entry == 'C0':
            out.append(net(c0, price))
        else:
            out.append(net(o1, price) if o1 < c0 * 1.29 else None)
    hi5, lo5 = max(s.h[i + 1:i + 6]), min(s.l[i + 1:i + 6])
    return out, (hi5 >= c0 * 1.05, hi5 >= c0 * 1.10, lo5 <= c0 * 0.95)


def add(acc, reach, g, d, period, nets, hits):
    a = acc.get((g, d))
    if a is None:
        a = acc[(g, d)] = [[0.0] * NC, [0] * NC, [0] * NC]
    sums, counts, wins = a
    for ci, v in enumerate(nets):
        if v is not None:
            sums[ci] += v; counts[ci] += 1
            if v > 0:
                wins[ci] += 1
    r = reach[(g, period)]
    r[0] += 1; r[1] += hits[0]; r[2] += hits[1]; r[3] += hits[2]


def part_a(bars, trading):
    tpos = {d: k for k, d in enumerate(trading)}
    start, split = START.toordinal(), SPLIT.toordinal()
    acc = {}
    reach = defaultdict(lambda: [0, 0, 0, 0])
    flat_day = defaultdict(list)
    for sid, s in bars.items():
        n = len(s.day)
        for i in range(79, n - H):
            d = s.day[i]
            if d < start:
                continue
            t = tpos.get(d)
            if t is None or t + H >= len(trading) or any(s.day[i + k] != trading[t + k] for k in range(1, H + 1)):
                continue
            if sum(s.val[i - 19:i + 1]) / 20 < MIN_VALUE:
                continue
            ret = s.c[i] / s.c[i - 1] - 1
            if ret >= 0.29:
                continue
            rng = s.h[i] - s.l[i]
            loc = (s.c[i] - s.l[i]) / rng if rng > 0 else 0.5
            groups = ['ALL']
            if ret >= 0.03:
                groups.append('STRONG')
                if loc >= 0.8:
                    groups.append('STRONG_H')
                if loc <= 0.5:
                    groups.append('STRONG_P')
            if ret <= -0.05:
                groups.append('DROP5')
            nets, hits = row_nets(s, i)
            period = 'first' if d <= split else 'second'
            for g in groups:
                add(acc, reach, g, d, period, nets, hits)
            hi20, lo20 = max(s.h[i - 19:i + 1]), min(s.l[i - 19:i + 1])
            if abs(s.c[i] / s.c[i - 20] - 1) <= 0.05 and lo20 > 0 and hi20 / lo20 - 1 <= 0.20:
                num = den = 0.0
                for k in range(i - 19, i + 1):
                    r_ = s.h[k] - s.l[k]
                    num += (((s.c[k] - s.l[k]) - (s.h[k] - s.c[k])) / r_ if r_ > 0 else 0.0) * s.v[k]
                    den += s.v[k]
                if den > 0:
                    flat_day[d].append((num / den, sid, i))
    for d, rows in flat_day.items():
        if len(rows) < 50:
            continue
        rows.sort()
        period = 'first' if d <= split else 'second'
        for _, sid, i in rows[4 * len(rows) // 5:]:
            nets, hits = row_nets(bars[sid], i)
            add(acc, reach, 'ACC', d, period, nets, hits)
    return acc, reach


def summarize(acc, groups, cells, split, min_rows=5):
    """{(group, cell): {period: (mean of daily means, win rate, days, daily series)}}"""
    by = defaultdict(lambda: defaultdict(list))
    wins = defaultdict(lambda: defaultdict(lambda: [0, 0]))
    flat = []
    for key, v in acc.items():
        if len(key) == 2:
            g, d = key
            flat.extend(((g, ci, d), (v[0][ci], v[1][ci], v[2][ci])) for ci in range(len(v[0])))
        else:
            flat.append((key, tuple(v)))
    for (g, ci, d), (s, n, w) in flat:
        if n < min_rows:
            continue
        p = 'first' if d <= split else 'second'
        by[(g, ci)][p].append((d, s / n))
        wins[(g, ci)][p][0] += w; wins[(g, ci)][p][1] += n
    out = {}
    for key, periods in by.items():
        out[key] = {}
        for p, rows in periods.items():
            rows.sort()
            w = wins[key][p]
            out[key][p] = (mean([r[1] for r in rows]), w[0] / w[1], len(rows), [r[1] for r in rows])
    return out


def print_grid(title, summary, groups, cells_, labels):
    print(f'\n===== {title}')
    for g in groups:
        for entry in sorted({c[0] for c in cells_}):
            idx = [ci for ci, c in enumerate(cells_) if c[0] == entry]
            rows = []
            for ci in idx:
                x = summary.get((g, ci))
                if not x or 'first' not in x or 'second' not in x:
                    continue
                rows.append((ci, x['first'], x['second']))
            if not rows:
                continue
            print(f'\n-- {g} entry {entry}  (Net % first / second half, win rate %)')
            for ci, a, b in rows:
                print(f'   {labels(cells_[ci]):10s} {a[0]:+7.3f} / {b[0]:+7.3f}   win {a[1] * 100:4.1f} / {b[1] * 100:4.1f}'
                      f'   days {a[2]} / {b[2]}')
            best = max(rows, key=lambda r: min(r[1][0], r[2][0]))
            ci_a = block_ci(best[1][3], BLOCK, random.Random(SEED))
            ci_b = block_ci(best[2][3], BLOCK, random.Random(SEED))
            print(f'   best (by the worse half): {labels(cells_[best[0]])}  first {ci_a[0]:+.3f} [{ci_a[1]:+.3f}, {ci_a[2]:+.3f}]'
                  f'  second {ci_b[0]:+.3f} [{ci_b[1]:+.3f}, {ci_b[2]:+.3f}]')


def part_b(c):
    lo = dt.datetime(2025, 10, 29, tzinfo=dt.timezone(dt.timedelta(hours=9)))
    hi = dt.datetime.combine(LAST_EXIT + dt.timedelta(days=1), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    ids = sorted(sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name))
    rows = {}
    for k in range(0, len(ids), CHUNK):
        for r in c.execute(
                """with b as (select stock_id, start_time, open_price, high_price, low_price, close_price, trading_value,
                                     (start_time at time zone 'Asia/Seoul') lt
                                from intraday_research_bar where stock_id = any(%s) and start_time >= %s and start_time < %s)
                   select stock_id, lt::date, min(lt::time), (array_agg(open_price order by start_time))[1],
                          max(close_price) filter (where lt::time = '09:25'), max(close_price) filter (where lt::time = '09:55'),
                          max(close_price) filter (where lt::time = '10:55'), max(close_price) filter (where lt::time = '12:55'),
                          max(close_price) filter (where lt::time = '15:10'), max(close_price) filter (where lt::time = '15:15'),
                          max(close_price) filter (where lt::time = '15:25'),
                          max(high_price) filter (where lt::time <= '15:10'),
                          sum(trading_value) filter (where lt::time <= '15:10')
                     from b group by stock_id, lt::date""", (ids[k:k + CHUNK], lo, hi)):
            rows[(r[0], r[1])] = r[2:]
        print(f'  intraday loaded {min(k + CHUNK, len(ids))}/{len(ids)} stocks', flush=True)
    days = sorted({d for _, d in rows})
    nxt = dict(zip(days, days[1:]))
    nxt2 = dict(zip(days, days[2:]))
    prv = dict(zip(days[1:], days))
    labels_b = ['D1_0900', 'D1_0930', 'D1_1000', 'D1_1100', 'D1_1300', 'D1_1515', 'D1_CLOSE', 'D2_OPEN', 'D2_CLOSE']
    acc = defaultdict(lambda: [0.0, 0, 0])
    for (sid, d), r in rows.items():
        if d not in nxt or d not in prv or d in SPECIAL or nxt[d] in SPECIAL or d < dt.date(2025, 10, 31):
            continue
        first_t, first_o, c925, c955, c1055, c1255, p0, entry, close, high, value = r
        prev = rows.get((sid, prv[d]))
        if not entry or not p0 or not value or value < MIN_VALUE or not prev or not prev[8]:
            continue
        ret = p0 / prev[8] - 1
        if ret >= 0.295:
            continue
        groups = ['ALL']
        if ret >= 0.03:
            groups.append('STRONG')
            if high and p0 >= high * 0.99:
                groups.append('STRONG_H')
            if high and p0 < high * 0.97:
                groups.append('STRONG_P')
        n1 = rows.get((sid, nxt[d]))
        n2 = rows.get((sid, nxt2[d])) if d in nxt2 else None
        if not n1 or str(n1[0]) != '09:00:00':
            continue
        prices = [n1[1], n1[2], n1[3], n1[4], n1[5], n1[6], n1[8],
                  n2[1] if n2 and str(n2[0]) == '09:00:00' else None, n2[8] if n2 else None]
        nets = [net(float(entry), float(p)) if p and abs(float(p) / float(entry) - 1) <= 0.35 else None for p in prices]
        for g in groups:
            for ci, v in enumerate(nets):
                if v is not None:
                    a = acc[(g, ci, d)]; a[0] += v; a[1] += 1; a[2] += v > 0
    acc_ord = {(g, ci, d.toordinal()): v for (g, ci, d), v in acc.items()}
    return acc_ord, labels_b


def main():
    c = connect()
    bars, trading = load_daily(c)
    print(f'daily: {len(bars)} stocks, {len(trading)} trading days', flush=True)
    acc, reach = part_a(bars, trading)
    summary = summarize(acc, GROUPS, CELLS, SPLIT.toordinal())
    print('\n===== Part A reach within 5 days after a close entry (share of rows, first / second half)')
    for g in GROUPS:
        a, b = reach.get((g, 'first')), reach.get((g, 'second'))
        if a and b and a[0] and b[0]:
            print(f'   {g:9s} rows {a[0]:8d} / {b[0]:8d} | high +5% {a[1] / a[0] * 100:4.1f} / {b[1] / b[0] * 100:4.1f} '
                  f'| high +10% {a[2] / a[0] * 100:4.1f} / {b[2] / b[0] * 100:4.1f} | low -5% {a[3] / a[0] * 100:4.1f} / '
                  f'{b[3] / b[0] * 100:4.1f}')
    print_grid('Part A: daily 8 years, entry x exit (Ok = open of day k, Ck = close of day k)', summary, GROUPS, CELLS,
               lambda cell: cell[1])
    acc_b, labels_b = part_b(c)
    cells_b = [('C1515', lab) for lab in labels_b]
    summary_b = summarize(acc_b, ['ALL', 'STRONG', 'STRONG_H', 'STRONG_P'], cells_b,
                          dt.date(2026, 4, 30).toordinal())
    print_grid('Part B: 5-minute 1 year, entry 15:15 bar close, exit time on day 1 / day 2', summary_b,
               ['ALL', 'STRONG', 'STRONG_H', 'STRONG_P'], cells_b, lambda cell: cell[1])


if __name__ == '__main__':
    main()
