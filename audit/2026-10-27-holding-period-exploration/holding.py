"""Exploratory holding-period scan on adjusted daily bars (2026-10-27). Hypothesis generation only.

Fixed before running (nothing else is tried):
  Entry: close[d]. Universe U1 (precision proxy): trading value rank top 100 on d and close-to-close return on d >= +3%.
  Benchmark ALL: every stock with trading value >= 1,000,000,000 KRW on d (same entry and exit).
  Exits: OPEN1 = open[d+1] (current best), CLOSE1/CLOSE3/CLOSE5/CLOSE10 = close[d+H] (H trading days of that stock).
  Net with ADR-008 BASE costs (buy 0.095%, sell 0.295%). Unit: day (equal-weight mean of the day's entries).
  Exit dates must be <= 2026-10-22 (no prices from the ADR-010 confirmation sample).
  Statistics per exit and period: U1 mean Net, ALL mean Net, and U1 - ALL (same days).
  95% interval: circular moving-block resampling of the ordered day series, block = max(5, H), 10,000 draws,
  seed 20261027 (overlapping holding periods are serially correlated).
  Periods by entry date: discovery 2026-03-18..07-31, check 2026-08-01..10-21.
  Keep rule (per exit, separately for U1 Net and U1 - ALL): same sign in both periods and the discovery interval
  excludes zero.
Run: python holding.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import re
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
PERIODS = {'discovery': ('2026-03-18', '2026-07-31'), 'check': ('2026-08-01', '2026-10-21')}
LAST_EXIT = dt.date(2026, 10, 22)
BUY, SELL = 0.00095, 0.00295
DRAWS, SEED = 10_000, 20261027
EXITS = {'OPEN1': 1, 'CLOSE1': 1, 'CLOSE3': 3, 'CLOSE5': 5, 'CLOSE10': 10}


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


def load():
    c = connect()
    bars = defaultdict(list)
    for sid, d, o, cl, tv in c.execute(
            """select stock_id, trade_date, open_price, close_price, trading_value from daily_adjusted_candle
                where open_price > 0 and close_price > 0 and trade_date <= %s order by stock_id, trade_date""",
            (LAST_EXIT,)):
        bars[sid].append((d, float(o), float(cl), float(tv or 0)))
    rows = defaultdict(list)  # day -> list of (tv, ret, {exit: net})
    for series in bars.values():
        for i in range(1, len(series)):
            d, _, cl, tv = series[i]
            if not (dt.date.fromisoformat(PERIODS['discovery'][0]) <= d <= dt.date.fromisoformat(PERIODS['check'][1])):
                continue
            ret = cl / series[i - 1][2] - 1
            outcome = {}
            for name, h in EXITS.items():
                if i + h < len(series):
                    nxt = series[i + h]
                    outcome[name] = net(cl, nxt[1] if name == 'OPEN1' else nxt[2])
            rows[d].append((tv, ret, outcome))
    return rows


def main():
    rows = load()
    by_day = {}
    for d, day in rows.items():
        ranked = sorted(day, key=lambda r: -r[0])
        u1 = [r for rank, r in enumerate(ranked, 1) if rank <= 100 and r[1] >= 0.03]
        everyone = [r for r in day if r[0] >= 1_000_000_000]
        by_day[d] = (u1, everyone)
    rng = random.Random(SEED)
    summary = {}
    for period, (start, end) in PERIODS.items():
        s, e = dt.date.fromisoformat(start), dt.date.fromisoformat(end)
        print(f'\n===== {period} entries {start}..{end}')
        for name, h in EXITS.items():
            u_series, a_series, x_series = [], [], []
            for d in sorted(by_day):
                if not s <= d <= e:
                    continue
                u1, everyone = by_day[d]
                u = [r[2][name] for r in u1 if name in r[2]]
                a = [r[2][name] for r in everyone if name in r[2]]
                if not u or not a:
                    continue
                u_series.append(mean(u)); a_series.append(mean(a)); x_series.append(mean(u) - mean(a))
            block = max(5, h)
            u_stat, a_stat, x_stat = (block_ci(v, block, rng) for v in (u_series, a_series, x_series))
            summary[(period, name, 'U1')] = u_stat
            summary[(period, name, 'U1-ALL')] = x_stat
            print(f'  {name:8s} days {len(u_series):3d} | U1 Net {u_stat[0]:+.2f}% [{u_stat[1]:+.2f}, {u_stat[2]:+.2f}] '
                  f'| ALL Net {a_stat[0]:+.2f}% [{a_stat[1]:+.2f}, {a_stat[2]:+.2f}] '
                  f'| U1-ALL {x_stat[0]:+.2f} [{x_stat[1]:+.2f}, {x_stat[2]:+.2f}] | U1 per holding day {u_stat[0] / h:+.3f}%')
    print('\n===== keep rule')
    for name in EXITS:
        for what in ('U1', 'U1-ALL'):
            dsc, chk = summary[('discovery', name, what)], summary[('check', name, what)]
            keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
            print(f"  {'KEEP' if keep else 'drop'}  {name:8s} {what:7s} discovery {dsc[0]:+.2f} [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
                  f"check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")


if __name__ == '__main__':
    main()
