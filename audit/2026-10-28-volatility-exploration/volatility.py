"""Exploratory volatility and big-mover scan on three years of adjusted daily bars (2026-10-28). Hypothesis generation only.

Fixed before running (nothing else is tried):
  Share filter (same as reversal.py): 6-character code ending in '0', not starting with 'Q' or 'F', name without '스팩'.
  Costs: ADR-008 BASE (buy 0.095%, sell 0.295%). No price from the ADR-010 confirmation sample (all exits <= 2026-10-22).

  A. Volatility quintiles, monthly.
    Formation date f = last trading day of each month. Universe at f: share filter, at least 21 bars up to f, and mean
    trading value over the last 20 bars (up to f) >= 1,000,000,000 KRW.
    Volatility = sample standard deviation of the last 20 close-to-close returns up to f.
    Quintiles within the universe at f (Q1 lowest). Buy close[f], sell at the next formation date's close (or the
    stock's last close on or before it). Equal weight. Full turnover assumed each month (costs charged every month).
    Statistics per period: mean monthly Net of Q1..Q5 and ALL (universe), Q1 - Q5, Q1 - ALL; month resampling
    (10,000 draws, seed 20261028), 95% interval.
    Periods by formation month: discovery 2023-11..2025-03, check 2025-04..2026-08.
    Keep rule (Q1 - Q5 and Q1 - ALL): same sign in both periods and the discovery interval excludes zero.

  B. Big up-movers, daily (same method as reversal.py).
    Liquid set ALL on d: trading value on d >= 1,000,000,000 KRW. Groups: U1 = trading-value rank top 100 (within the
    filtered shares) and return on d >= +3%; UP10 = ALL with return on d >= +10%.
    Entry close[d]; exits OPEN1, CLOSE1, CLOSE3, CLOSE5, CLOSE10; group Net and group - ALL; circular moving-block
    resampling of days, block = max(5, H). Periods: discovery 2023-11-21..2025-04-30, check 2025-05-01..2026-10-21.
    Keep rule as above.
Known bias: delisted stocks are absent. It flatters high-volatility and loser portfolios.
Run: python volatility.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import re
import statistics as st
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
LAST_EXIT = dt.date(2026, 10, 22)
BUY, SELL = 0.00095, 0.00295
DRAWS, SEED = 10_000, 20261028
MIN_VALUE = 1_000_000_000
MONTH_PERIODS = {'discovery': ('2023-11', '2025-03'), 'check': ('2025-04', '2026-08')}
DAY_PERIODS = {'discovery': ('2023-11-21', '2025-04-30'), 'check': ('2025-05-01', '2026-10-21')}
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


def iid_ci(series, rng):
    draws = sorted(mean([series[rng.randrange(len(series))] for _ in series]) for _ in range(DRAWS))
    return mean(series), draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]


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
    return bars


def keep(dsc, chk):
    return (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)


def part_a(bars, rng):
    days = sorted({b[0] for series in bars.values() for b in series})
    month_end = {}
    for d in days:
        month_end[(d.year, d.month)] = d
    formations = sorted(month_end.values())
    index = {sid: {b[0]: i for i, b in enumerate(series)} for sid, series in bars.items()}
    rows = []  # (month label, {Q: net}, all_net)
    for f, nxt in zip(formations, formations[1:]):
        universe = []
        for sid, series in bars.items():
            i = index[sid].get(f)
            if i is None or i < 20:
                continue
            window = series[i - 20:i + 1]
            if mean([b[3] for b in window[1:]]) < MIN_VALUE:
                continue
            rets = [window[k][2] / window[k - 1][2] - 1 for k in range(1, 21)]
            exit_close = None
            for b in series[i + 1:]:
                if b[0] > nxt:
                    break
                exit_close = b[2]
            if exit_close is None:
                continue
            universe.append((st.stdev(rets), net(series[i][2], exit_close)))
        if len(universe) < 50:
            continue
        universe.sort(key=lambda r: r[0])
        n = len(universe)
        q = {f'Q{k + 1}': mean([r[1] for r in universe[k * n // 5:(k + 1) * n // 5]]) for k in range(5)}
        rows.append((f'{f.year}-{f.month:02d}', q, mean([r[1] for r in universe]), n))
    summary = {}
    print('===== A. volatility quintiles (monthly Net %, buy at month-end close, hold one month)')
    for period, (start, end) in MONTH_PERIODS.items():
        months = [r for r in rows if start <= r[0] <= end]
        print(f'\n-- {period} formation {start}..{end}: months {len(months)}, universe {min(r[3] for r in months)}..{max(r[3] for r in months)} stocks')
        for qname in ('Q1', 'Q2', 'Q3', 'Q4', 'Q5'):
            m = iid_ci([r[1][qname] for r in months], rng)
            print(f'  {qname} {m[0]:+.2f}% [{m[1]:+.2f}, {m[2]:+.2f}]')
        a = iid_ci([r[2] for r in months], rng)
        print(f'  ALL {a[0]:+.2f}% [{a[1]:+.2f}, {a[2]:+.2f}]')
        for label, series in (('Q1-Q5', [r[1]['Q1'] - r[1]['Q5'] for r in months]),
                              ('Q1-ALL', [r[1]['Q1'] - r[2] for r in months])):
            summary[(period, label)] = iid_ci(series, rng)
            s = summary[(period, label)]
            wins = sum(1 for v in series if v > 0)
            print(f'  {label:6s} {s[0]:+.2f}%p [{s[1]:+.2f}, {s[2]:+.2f}] | positive months {wins}/{len(series)}')
    print('\n  month-by-month (Q1, Q5, ALL):')
    for label, q, a, n in rows:
        if label <= MONTH_PERIODS['check'][1]:
            print(f'    {label} Q1 {q["Q1"]:+6.2f} Q5 {q["Q5"]:+7.2f} ALL {a:+6.2f} (n={n})')
    print('\n  keep rule:')
    for label in ('Q1-Q5', 'Q1-ALL'):
        dsc, chk = summary[('discovery', label)], summary[('check', label)]
        print(f"    {'KEEP' if keep(dsc, chk) else 'drop'}  {label:6s} discovery {dsc[0]:+.2f} [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
              f"check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")


def part_b(bars, rng):
    start = dt.date.fromisoformat(DAY_PERIODS['discovery'][0])
    end = dt.date.fromisoformat(DAY_PERIODS['check'][1])
    by_day = defaultdict(list)
    for series in bars.values():
        for i in range(1, len(series)):
            d, _, cl, tv = series[i]
            if not start <= d <= end:
                continue
            outcome = {}
            for name, h in EXITS.items():
                if i + h < len(series):
                    nxt = series[i + h]
                    outcome[name] = net(cl, nxt[1] if name == 'OPEN1' else nxt[2])
            by_day[d].append((tv, cl / series[i - 1][2] - 1, outcome))
    groups = {}
    for d, day in by_day.items():
        ranked = sorted(day, key=lambda r: -r[0])
        liquid = [r for r in day if r[0] >= MIN_VALUE]
        groups[d] = {'ALL': liquid,
                     'U1': [r for rank, r in enumerate(ranked, 1) if rank <= 100 and r[1] >= 0.03],
                     'UP10': [r for r in liquid if r[1] >= 0.10]}
    summary = {}
    print('\n===== B. big up-movers (daily, entry close)')
    for period, (s0, e0) in DAY_PERIODS.items():
        s, e = dt.date.fromisoformat(s0), dt.date.fromisoformat(e0)
        print(f'\n-- {period} entries {s0}..{e0}')
        for group in ('U1', 'UP10'):
            print(f'  [{group}] events {sum(len(groups[d][group]) for d in groups if s <= d <= e)}')
            for name, h in EXITS.items():
                g_series, x_series = [], []
                for d in sorted(groups):
                    if not s <= d <= e:
                        continue
                    g = [r[2][name] for r in groups[d][group] if name in r[2]]
                    a = [r[2][name] for r in groups[d]['ALL'] if name in r[2]]
                    if g and a:
                        g_series.append(mean(g)); x_series.append(mean(g) - mean(a))
                block = max(5, h)
                gs, xs = block_ci(g_series, block, rng), block_ci(x_series, block, rng)
                summary[(period, group, name, 'Net')] = gs
                summary[(period, group, name, '-ALL')] = xs
                print(f'    {name:8s} days {len(g_series):3d} | Net {gs[0]:+.2f}% [{gs[1]:+.2f}, {gs[2]:+.2f}] '
                      f'| -ALL {xs[0]:+.2f} [{xs[1]:+.2f}, {xs[2]:+.2f}]')
    print('\n  keep rule:')
    for group in ('U1', 'UP10'):
        for name in EXITS:
            for what in ('Net', '-ALL'):
                dsc, chk = summary[('discovery', group, name, what)], summary[('check', group, name, what)]
                print(f"    {'KEEP' if keep(dsc, chk) else 'drop'}  {group:4s} {name:8s} {what:5s} discovery {dsc[0]:+.2f} "
                      f"[{dsc[1]:+.2f},{dsc[2]:+.2f}] check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")


def main():
    bars = load()
    rng = random.Random(SEED)
    part_a(bars, rng)
    part_b(bars, rng)


if __name__ == '__main__':
    main()
