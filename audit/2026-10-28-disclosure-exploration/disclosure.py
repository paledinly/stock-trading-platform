"""Exploratory DART filing-type event scan on three years of filings and adjusted daily bars (2026-10-28).
Hypothesis generation only.

Fixed before running (nothing else is tried):
  Filings: dart_filing (list.json titles only, receipt DATE without time). Titles starting with '[' (corrections,
  issue-term confirmations) are excluded. Event types by title substring:
    BUYBACK      '자기주식취득결정' or '자기주식취득신탁계약체결결정'
    SUPPLY       '단일판매ㆍ공급계약체결' (excluding titles containing '해지')
    EARNINGS     '영업(잠정)실적'
    RIGHTS       '유상증자결정'
    BONUS        '무상증자결정'
    CB           '전환사채권발행결정'
    CANCEL       '주식소각결정'
  One event per stock, type and receipt date.
  Share filter (same as reversal.py) and liquidity: mean trading value of the 20 bars before the entry day
  >= 1,000,000,000 KRW.
  Entry: open of the first trading day after the receipt date (the receipt time is unknown, so the filing may have
  been public during the receipt day). Exits: close of the entry day (H1), of the 5th (H5) and 20th (H20) trading
  day counting the entry day as day 1. ADR-008 BASE costs once. Exits <= 2026-10-22.
  Market: equal-weight return over the same open-to-close window of stocks whose trading value on the day before the
  entry day was >= 1bn KRW. Excess = event gross - market.
  Unit: entry day (mean of that day's events); circular moving-block resampling of days (block max(5, H),
  10,000 draws, seed 20261028), 95% interval.
  Periods by entry date: discovery 2023-11-01..2025-04-30, check 2025-05-01..2026-10-21.
  Keep rule (per type and horizon, for Net and for excess): same sign in both periods and the discovery interval
  excludes zero.
Known limits: titles only (no amounts or earnings numbers); delisted stocks have no bars (their events drop out).
Run: python disclosure.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import LAST_EXIT, block_ci, connect, mean, share  # noqa: E402

BUY, SELL = 0.00095, 0.00295
MIN_VALUE = 1_000_000_000
PERIODS = {'discovery': ('2023-11-01', '2025-04-30'), 'check': ('2025-05-01', '2026-10-21')}
HORIZONS = {'H1': 1, 'H5': 5, 'H20': 20}
TYPES = {
    'BUYBACK': lambda t: '자기주식취득결정' in t or '자기주식취득신탁계약체결결정' in t,
    'SUPPLY': lambda t: '단일판매ㆍ공급계약체결' in t and '해지' not in t,
    'EARNINGS': lambda t: '영업(잠정)실적' in t,
    'RIGHTS': lambda t: '유상증자결정' in t,
    'BONUS': lambda t: '무상증자결정' in t,
    'CB': lambda t: '전환사채권발행결정' in t,
    'CANCEL': lambda t: '주식소각결정' in t,
}
SEED = 20261028


def main():
    c = connect()
    code_to_sid = {}
    for sid, code, name in c.execute('select id, stock_code, stock_name from stock'):
        if share(code, name):
            code_to_sid[code] = sid
    bars = defaultdict(list)
    for sid, d, o, cl, tv in c.execute(
            """select stock_id, trade_date, open_price, close_price, trading_value from daily_adjusted_candle
                where open_price > 0 and close_price > 0 and trade_date <= %s order by stock_id, trade_date""",
            (LAST_EXIT,)):
        bars[sid].append((d, float(o), float(cl), float(tv or 0)))
    days = sorted({b[0] for series in bars.values() for b in series})
    day_index = {d: i for i, d in enumerate(days)}
    index = {sid: {b[0]: i for i, b in enumerate(series)} for sid, series in bars.items()}

    # market: equal-weight open[k] -> close[k + H - 1], eligible by trading value on the previous bar
    market = defaultdict(list)
    for series in bars.values():
        for i in range(1, len(series)):
            if series[i - 1][3] < MIN_VALUE:
                continue
            for h in HORIZONS.values():
                if i + h - 1 < len(series):
                    market[(series[i][0], h)].append(series[i + h - 1][2] / series[i][1] - 1)
    market = {k: mean(v) for k, v in market.items()}

    events = set()
    for code, title, rcept in c.execute('select stock_code, report_nm, rcept_dt from dart_filing'):
        title = (title or '').strip()
        if title.startswith('[') or code not in code_to_sid:
            continue
        for name, match in TYPES.items():
            if match(title):
                events.add((name, code_to_sid[code], rcept))

    rows = defaultdict(list)  # (type, H) -> list of (entry day, net, excess)
    counts = defaultdict(int)
    for name, sid, rcept in events:
        series = bars.get(sid)
        if not series:
            continue
        nxt = next((d for d in days[day_index.get(rcept, 0):] if d > rcept), None) if rcept in day_index else \
            next((d for d in days if d > rcept), None)
        if nxt is None or nxt not in index[sid]:
            continue
        e = index[sid][nxt]
        if e < 20 or mean([b[3] for b in series[e - 20:e]]) < MIN_VALUE:
            continue
        counts[name] += 1
        for label, h in HORIZONS.items():
            if e + h - 1 >= len(series) or (nxt, h) not in market:
                continue
            entry, exit_ = series[e][1], series[e + h - 1][2]
            net = (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100
            excess = (exit_ / entry - 1 - market[(nxt, h)]) * 100
            rows[(name, label)].append((nxt, net, excess))

    rng = random.Random(SEED)
    summary = {}
    print('liquid events per type (entry day found):', dict(sorted(counts.items())))
    for period, (s0, e0) in PERIODS.items():
        s, e = dt.date.fromisoformat(s0), dt.date.fromisoformat(e0)
        print(f'\n===== {period} entries {s0}..{e0}')
        for name in TYPES:
            for label, h in HORIZONS.items():
                by_day = defaultdict(list)
                for d, net, excess in rows[(name, label)]:
                    if s <= d <= e:
                        by_day[d].append((net, excess))
                if len(by_day) < 10:
                    print(f'  {name:8s} {label:3s} days {len(by_day):3d} (too few)')
                    continue
                ds = sorted(by_day)
                n_ci = block_ci([mean([r[0] for r in by_day[d]]) for d in ds], max(5, h), rng)
                x_ci = block_ci([mean([r[1] for r in by_day[d]]) for d in ds], max(5, h), rng)
                summary[(period, name, label, 'Net')] = n_ci
                summary[(period, name, label, 'excess')] = x_ci
                print(f'  {name:8s} {label:3s} events {sum(len(v) for v in by_day.values()):5d} days {len(ds):3d} '
                      f'| Net {n_ci[0]:+.2f}% [{n_ci[1]:+.2f}, {n_ci[2]:+.2f}] | excess {x_ci[0]:+.2f}%p [{x_ci[1]:+.2f}, {x_ci[2]:+.2f}]')
    print('\n===== keep rule')
    for name in TYPES:
        for label in HORIZONS:
            for what in ('Net', 'excess'):
                dsc, chk = summary.get(('discovery', name, label, what)), summary.get(('check', name, label, what))
                if not dsc or not chk:
                    continue
                keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
                print(f"  {'KEEP' if keep else 'drop'}  {name:8s} {label:3s} {what:6s} discovery {dsc[0]:+.2f} "
                      f"[{dsc[1]:+.2f},{dsc[2]:+.2f}] check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")


if __name__ == '__main__':
    main()
