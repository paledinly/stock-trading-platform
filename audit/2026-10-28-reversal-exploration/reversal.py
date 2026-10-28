"""Exploratory short-term reversal scan on three years of adjusted daily bars (2026-10-28). Hypothesis generation only.

Fixed before running (nothing else is tried):
  Share filter: stock code of 6 characters ending in '0', not starting with 'Q' or 'F', name without '스팩' (SPAC).
    This drops preferred shares, SPACs and non-share products. ETF/ETN are not in daily_adjusted_candle.
  Liquid set ALL on day d: trading value on d >= 1,000,000,000 KRW and a previous close exists.
  Reversal candidates on d (subset of ALL): R5 = close-to-close return on d <= -5%, R10 = <= -10%.
  Entry close[d]. Exits: OPEN1 = open[d+1] (reference), CLOSE1/CLOSE3/CLOSE5/CLOSE10 = close[d+H] of that stock.
  Net with ADR-008 BASE costs (buy 0.095%, sell 0.295%). Exit dates must be <= 2026-10-22 (no prices from the
  ADR-010 confirmation sample). Unit: day (equal-weight mean of the day's events); days without events are skipped.
  Statistics per group, exit and period: group mean Net, and group - ALL on the same days.
  95% interval: circular moving-block resampling of the ordered day series, block = max(5, H), 10,000 draws,
  seed 20261028.
  Periods by entry date: discovery 2023-11-21..2025-04-30, check 2025-05-01..2026-10-21.
  Keep rule (per group, exit, and statistic): same sign in both periods and the discovery interval excludes zero.
  Descriptive only (no keep rule): the same table per calendar year of entry.
Known bias: delisted stocks are absent from the stock master, so losers that were later delisted are missing.
This inflates reversal returns.
Run: python reversal.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import re
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
PERIODS = {'discovery': ('2023-11-21', '2025-04-30'), 'check': ('2025-05-01', '2026-10-21')}
YEARS = {'2023-24': ('2023-11-21', '2024-12-31'), '2025': ('2025-01-01', '2025-12-31'), '2026': ('2026-01-01', '2026-10-21')}
LAST_EXIT = dt.date(2026, 10, 22)
BUY, SELL = 0.00095, 0.00295
DRAWS, SEED = 10_000, 20261028
EXITS = {'OPEN1': 1, 'CLOSE1': 1, 'CLOSE3': 3, 'CLOSE5': 5, 'CLOSE10': 10}
GROUPS = {'R5': -0.05, 'R10': -0.10}
MIN_VALUE = 1_000_000_000


def connect():
    env = {}
    for line in open(ENV, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    m = re.match(r'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)', env['DB_URL'])
    return psycopg.connect(host=m[1], port=int(m[2] or 5432), dbname=m[3], user=env['DB_USERNAME'],
                           password=env['DB_PASSWORD'], sslmode='require')


def mean(v):
    return sum(v) / len(v)


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def block_ci(series, block, rng):
    n = len(series)
    draws = []
    for _ in range(DRAWS):
        sample = []
        while len(sample) < n:
            start = rng.randrange(n)
            sample.extend(series[(start + k) % n] for k in range(block))
        draws.append(mean(sample[:n]))
    draws.sort()
    return mean(series), draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]


def share(code, name):
    return len(code) == 6 and code.endswith('0') and code[0] not in 'QF' and '스팩' not in (name or '')


def load():
    c = connect()
    allowed = {sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name)}
    bars = defaultdict(list)
    for sid, d, o, cl, tv in c.execute(
            """select stock_id, trade_date, open_price, close_price, trading_value from daily_adjusted_candle
                where open_price > 0 and close_price > 0 and trade_date <= %s order by stock_id, trade_date""",
            (LAST_EXIT,)):
        if sid in allowed:
            bars[sid].append((d, float(o), float(cl), float(tv or 0)))
    start = dt.date.fromisoformat(PERIODS['discovery'][0])
    end = dt.date.fromisoformat(PERIODS['check'][1])
    by_day = defaultdict(list)  # day -> list of (ret, {exit: net})
    for series in bars.values():
        for i in range(1, len(series)):
            d, _, cl, tv = series[i]
            if not start <= d <= end or tv < MIN_VALUE:
                continue
            outcome = {}
            for name, h in EXITS.items():
                if i + h < len(series):
                    nxt = series[i + h]
                    outcome[name] = net(cl, nxt[1] if name == 'OPEN1' else nxt[2])
            by_day[d].append((cl / series[i - 1][2] - 1, outcome))
    return by_day


def table(by_day, s, e, rng, summary=None, label=''):
    for group, cut in GROUPS.items():
        events = sum(1 for d in by_day if s <= d <= e for r in by_day[d] if r[0] <= cut)
        print(f'  [{group}] events {events}')
        for name, h in EXITS.items():
            g_series, a_series, x_series = [], [], []
            for d in sorted(by_day):
                if not s <= d <= e:
                    continue
                g = [r[1][name] for r in by_day[d] if r[0] <= cut and name in r[1]]
                a = [r[1][name] for r in by_day[d] if name in r[1]]
                if not g or not a:
                    continue
                g_series.append(mean(g)); a_series.append(mean(a)); x_series.append(mean(g) - mean(a))
            if len(g_series) < 10:
                print(f'    {name:8s} days {len(g_series)} (too few)')
                continue
            block = max(5, h)
            gs, as_, xs = (block_ci(v, block, rng) for v in (g_series, a_series, x_series))
            if summary is not None:
                summary[(label, group, name, 'Net')] = gs
                summary[(label, group, name, '-ALL')] = xs
            print(f'    {name:8s} days {len(g_series):3d} | {group} Net {gs[0]:+.2f}% [{gs[1]:+.2f}, {gs[2]:+.2f}] '
                  f'| ALL Net {as_[0]:+.2f}% | {group}-ALL {xs[0]:+.2f} [{xs[1]:+.2f}, {xs[2]:+.2f}]')


def main():
    by_day = load()
    rng = random.Random(SEED)
    summary = {}
    for period, (start, end) in PERIODS.items():
        print(f'\n===== {period} entries {start}..{end}')
        table(by_day, dt.date.fromisoformat(start), dt.date.fromisoformat(end), rng, summary, period)
    print('\n===== keep rule')
    for group in GROUPS:
        for name in EXITS:
            for what in ('Net', '-ALL'):
                dsc, chk = summary.get(('discovery', group, name, what)), summary.get(('check', group, name, what))
                if not dsc or not chk:
                    continue
                keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
                print(f"  {'KEEP' if keep else 'drop'}  {group:4s} {name:8s} {what:5s} discovery {dsc[0]:+.2f} "
                      f"[{dsc[1]:+.2f},{dsc[2]:+.2f}] check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")
    print('\n===== descriptive by year of entry (no keep rule)')
    for year, (start, end) in YEARS.items():
        print(f'\n-- {year} {start}..{end}')
        table(by_day, dt.date.fromisoformat(start), dt.date.fromisoformat(end), rng)


if __name__ == '__main__':
    main()
