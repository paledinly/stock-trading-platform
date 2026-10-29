"""Replication of the two surviving exploration results on unseen history (2018 onward), 2026-10-29.

Fixed before running (nothing else is tried):
  Hypotheses carried over unchanged from the earlier explorations:
    MOM  (2026-10-28 momentum.py): top quintile (Q5) of M12 (12-month return skipping the last month) and of H52
         (close / 52-week high) beats the liquid universe (Q5 - ALL > 0), monthly, buy month-end close, sell next
         month-end close, full turnover, BASE costs. M6 is reported too (it failed before).
    BUY  (2026-10-28 disclosure.py): BUYBACK filings ('자기주식취득결정' or '자기주식취득신탁계약체결결정', titles not
         starting with '['), entry at the open of the first trading day after the receipt date, exits at the close of
         trading day 1 / 5 / 20 (entry day = day 1); excess over the equal-weight market of the same window (eligibility
         by the previous day's trading value) > 0. Liquidity: mean trading value of the 20 bars before entry >= 1bn KRW.
  Same share filter, liquidity threshold (1bn KRW) and costs (buy 0.095%, sell 0.295%) as before.
  Only data NOT used by the earlier explorations decides replication:
    MOM formations 2018-12..2024-10 (earlier: 2024-11..2026-08 for M12/H52, 2024-04..2026-08 for M6 - M6 new window
        is 2018-06..2024-03).
    BUY entries 2018-01-02..2023-10-31 (earlier: 2023-11-01..2026-10-21).
  Replication succeeds when the new-window estimate is positive and its 95% interval excludes zero.
  The new window is also split into two consecutive halves (reported, not part of the criterion).
  Intervals: MOM month resampling (iid, 10,000 draws); BUY circular block resampling of entry days
  (block max(5, H), 10,000 draws); seed 20261029.
  Memory: bars are streamed with a server-side cursor into per-stock column arrays (no full-row lists).
Known bias: delisted stocks are absent and the 2018 universe is the survivors; this shrinks Q5 - ALL and drops
  events of later-delisted companies.
Run: python recheck.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import sys
from array import array
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import LAST_EXIT, block_ci, connect, iid_ci, mean, share  # noqa: E402

BUY, SELL = 0.00095, 0.00295
MIN_VALUE = 1_000_000_000
SEED = 20261029
MOM_WINDOWS = {'M6': ('2018-06', '2024-03'), 'M12': ('2018-12', '2024-10'), 'H52': ('2018-12', '2024-10')}
NEEDS = {'M6': 127, 'M12': 253, 'H52': 252}
BUY_WINDOW = ('2018-01-02', '2023-10-31')
HORIZONS = {'H1': 1, 'H5': 5, 'H20': 20}


class Series:
    __slots__ = ('day', 'open', 'high', 'close', 'value')

    def __init__(self):
        self.day, self.open, self.high = array('i'), array('d'), array('d')
        self.close, self.value = array('d'), array('d')


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def load():
    c = connect()
    allowed = {sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name)}
    codes = {code: sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if sid in allowed}
    bars = {}
    with c.cursor(name='bars') as cur:
        cur.itersize = 50_000
        cur.execute("""select stock_id, trade_date, open_price, high_price, close_price, trading_value
                         from daily_adjusted_candle where open_price > 0 and close_price > 0 and trade_date <= %s
                        order by stock_id, trade_date""", (LAST_EXIT,))
        for sid, d, o, h, cl, tv in cur:
            if sid not in allowed:
                continue
            s = bars.get(sid)
            if s is None:
                s = bars[sid] = Series()
            s.day.append(d.toordinal()); s.open.append(float(o)); s.high.append(float(h))
            s.close.append(float(cl)); s.value.append(float(tv or 0))
    filings = c.execute("select stock_code, report_nm, rcept_dt from dart_filing").fetchall()
    return bars, codes, filings


def label(ordinal):
    d = dt.date.fromordinal(ordinal)
    return f'{d.year}-{d.month:02d}'


def momentum(bars, rng):
    days = sorted({d for s in bars.values() for d in s.day})
    month_end = {}
    for d in days:
        month_end[label(d)] = d
    labels = sorted(month_end)
    position = {sid: {d: i for i, d in enumerate(s.day)} for sid, s in bars.items()}
    print('===== MOM replication (monthly Net %, Q5 - ALL)')
    for factor, (start, end) in MOM_WINDOWS.items():
        rows = []
        for lab, nxt in zip(labels, labels[1:]):
            if not start <= lab <= end:
                continue
            f, exit_day = month_end[lab], month_end[nxt]
            universe = []
            for sid, s in bars.items():
                i = position[sid].get(f)
                if i is None or i + 1 < NEEDS[factor] or i < 19:
                    continue
                if sum(s.value[i - 19:i + 1]) / 20 < MIN_VALUE:
                    continue
                if factor == 'M6':
                    value = s.close[i - 21] / s.close[i - 126] - 1
                elif factor == 'M12':
                    value = s.close[i - 21] / s.close[i - 252] - 1
                else:
                    value = s.close[i] / max(s.high[i - 251:i + 1])
                exit_close = None
                k = i + 1
                while k < len(s.day) and s.day[k] <= exit_day:
                    exit_close = s.close[k]
                    k += 1
                if exit_close is not None:
                    universe.append((value, net(s.close[i], exit_close)))
            if len(universe) < 100:
                continue
            universe.sort(key=lambda r: r[0])
            n = len(universe)
            q5 = mean([r[1] for r in universe[4 * n // 5:]])
            q1 = mean([r[1] for r in universe[:n // 5]])
            everyone = mean([r[1] for r in universe])
            rows.append((lab, q5, q1, everyone, n))
        half = len(rows) // 2
        print(f'\n-- {factor} formations {rows[0][0]}..{rows[-1][0]}: {len(rows)} months, universe '
              f'{min(r[4] for r in rows)}..{max(r[4] for r in rows)}')
        for name, part in (('new window', rows), ('first half', rows[:half]), ('second half', rows[half:])):
            x = iid_ci([r[1] - r[3] for r in part], rng)
            spread = iid_ci([r[1] - r[2] for r in part], rng)
            print(f'   {name:11s} {part[0][0]}..{part[-1][0]} ({len(part):2d} mo) | Q5 {mean([r[1] for r in part]):+.2f}% '
                  f'ALL {mean([r[3] for r in part]):+.2f}% | Q5-ALL {x[0]:+.2f}%p [{x[1]:+.2f}, {x[2]:+.2f}] '
                  f'pos {sum(1 for r in part if r[1] > r[3])}/{len(part)} | Q5-Q1 {spread[0]:+.2f}%p [{spread[1]:+.2f}, {spread[2]:+.2f}]')
            if name == 'new window':
                ok = x[0] > 0 and x[1] > 0
                verdict = ('REPLICATED' if ok else 'NOT replicated') if factor != 'M6' else '(M6 reference only)'
                print(f'   -> {factor}: {verdict}')
        by_year = defaultdict(list)
        for r in rows:
            by_year[r[0][:4]].append(r[1] - r[3])
        print('   by year Q5-ALL: ' + ', '.join(f'{y} {mean(v):+.2f}' for y, v in sorted(by_year.items())))


def buyback(bars, codes, filings, rng):
    start, end = (dt.date.fromisoformat(x).toordinal() for x in BUY_WINDOW)
    position = {sid: {d: i for i, d in enumerate(s.day)} for sid, s in bars.items()}
    days = sorted({d for s in bars.values() for d in s.day})
    market = defaultdict(list)
    for s in bars.values():
        n = len(s.day)
        for i in range(1, n):
            if not start <= s.day[i] <= end + 40 or s.value[i - 1] < MIN_VALUE:
                continue
            for h in HORIZONS.values():
                if i + h - 1 < n:
                    market[(s.day[i], h)].append(s.close[i + h - 1] / s.open[i] - 1)
    market = {k: mean(v) for k, v in market.items()}
    events = set()
    for code, title, rcept in filings:
        title = (title or '').strip()
        if title.startswith('[') or code not in codes:
            continue
        if '자기주식취득결정' in title or '자기주식취득신탁계약체결결정' in title:
            events.add((codes[code], rcept.toordinal()))
    rows = defaultdict(list)
    import bisect
    for sid, rcept in events:
        s = bars.get(sid)
        if s is None:
            continue
        k = bisect.bisect_right(days, rcept)
        if k >= len(days):
            continue
        entry_day = days[k]
        if not start <= entry_day <= end:
            continue
        e = position[sid].get(entry_day)
        if e is None or e < 20 or sum(s.value[e - 20:e]) / 20 < MIN_VALUE:
            continue
        for lab, h in HORIZONS.items():
            if e + h - 1 >= len(s.day) or (entry_day, h) not in market:
                continue
            entry, exit_ = s.open[e], s.close[e + h - 1]
            rows[lab].append((entry_day, net(entry, exit_), (exit_ / entry - 1 - market[(entry_day, h)]) * 100))
    print('\n===== BUY replication (BUYBACK filings, entry next trading day open)')
    mid = (start + end) // 2
    for lab, h in HORIZONS.items():
        for name, lo, hi in (('new window', start, end), ('first half', start, mid), ('second half', mid + 1, end)):
            by_day = defaultdict(list)
            for d, n_, x in rows[lab]:
                if lo <= d <= hi:
                    by_day[d].append((n_, x))
            ds = sorted(by_day)
            n_ci = block_ci([mean([r[0] for r in by_day[d]]) for d in ds], max(5, h), rng)
            x_ci = block_ci([mean([r[1] for r in by_day[d]]) for d in ds], max(5, h), rng)
            print(f'   {lab:3s} {name:11s} {dt.date.fromordinal(lo)}..{dt.date.fromordinal(hi)} events '
                  f'{sum(len(v) for v in by_day.values()):5d} days {len(ds):4d} | Net {n_ci[0]:+.2f}% [{n_ci[1]:+.2f}, {n_ci[2]:+.2f}] '
                  f'| excess {x_ci[0]:+.2f}%p [{x_ci[1]:+.2f}, {x_ci[2]:+.2f}]')
            if name == 'new window':
                print(f"   -> BUYBACK {lab}: {'REPLICATED' if x_ci[0] > 0 and x_ci[1] > 0 else 'NOT replicated'}")


def main():
    bars, codes, filings = load()
    rng = random.Random(SEED)
    momentum(bars, rng)
    buyback(bars, codes, filings, rng)


if __name__ == '__main__':
    main()
