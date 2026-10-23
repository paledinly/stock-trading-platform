"""H-D historical test (pre-registered 2026-10-23, written before any EWY price was looked at).

Rule: for an entry at the close of Korean trading day d with next Korean trading day d',
S = product over EWY (AMEX) sessions with US date X, d <= X < d', of close[X] / open[X], minus 1.
S <= 0 -> exit at the next-day open (NEXT_OPEN); S > 0 -> BASELINE; no such session -> BASELINE.

Proxy universe (same as the H-B exploration): for each d, daily_adjusted_candle stocks in the top 100 by trading
value on d with close-to-close return on d >= +3%. Entry at close[d] (adjusted). Next-day exits from the daily bar
of d' with the simulator order: open <= -2% -> stop at open, open >= +3% -> target at open, both high >= +3% and
low <= -2% -> stop, high >= +3% -> +3%, low <= -2% -> -2%, otherwise close. Costs ADR-008 BASE and HIGH.

Days are the unit. Differences D1 = H-D - BASELINE and D2 = H-D - NEXT_OPEN of the daily mean Net are resampled
over days (10,000 draws, seed 20261023). Two comparisons -> 97.5% two-sided interval (1.25%, 98.75% quantiles).
Run: python h_d_history.py   (reads the repository .env, never prints credentials)
"""
import random
import re
import statistics as st
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
FROM, TO = '2026-03-18', '2026-10-21'
COSTS = {'BASE': (0.00095, 0.00295), 'HIGH': (0.00165, 0.00365)}
TARGET, STOP = 0.03, -0.02
DRAWS, SEED = 10_000, 20261023


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


def net(entry, exit_, cost):
    b, s = cost
    return (exit_ * (1 - s) - entry * (1 + b)) / (entry * (1 + b)) * 100


def baseline_exit(entry, o, h, l, c):
    if o / entry - 1 <= STOP:
        return o
    if o / entry - 1 >= TARGET:
        return o
    hit_target = h / entry - 1 >= TARGET
    hit_stop = l / entry - 1 <= STOP
    if hit_target and hit_stop:
        return entry * (1 + STOP)
    if hit_target:
        return entry * (1 + TARGET)
    if hit_stop:
        return entry * (1 + STOP)
    return c


def ci(diffs, rng):
    means = []
    for _ in range(DRAWS):
        sample = [diffs[rng.randrange(len(diffs))] for _ in diffs]
        means.append(st.mean(sample))
    means.sort()
    return means[int(DRAWS * 0.0125)], means[int(DRAWS * 0.9875) - 1]


def main():
    c = connect()
    events = c.execute("""
        with px as (
          select stock_id, trade_date, close_price cl, trading_value tv, lag(close_price) over w prev_close,
                 lead(trade_date) over w next_date, lead(open_price) over w no, lead(high_price) over w nh,
                 lead(low_price) over w nl, lead(close_price) over w nc
            from daily_adjusted_candle window w as (partition by stock_id order by trade_date)),
        ranked as (select *, rank() over (partition by trade_date order by tv desc) tv_rank from px where prev_close > 0)
        select trade_date, next_date, cl, no, nh, nl, nc from ranked
         where tv_rank <= 100 and cl / prev_close - 1 >= 0.03 and no is not null and no > 0
           and trade_date between %s and %s""", (FROM, TO)).fetchall()
    ewy = {d: (float(o), float(cl)) for d, o, cl in c.execute(
        "select trade_date, open, close from overseas_daily where symbol = 'EWY' and open is not null and open > 0")}
    if not ewy:
        raise SystemExit('overseas_daily has no EWY rows; run the backfill first')

    def signal(d, d_next):
        sessions = [v for x, v in ewy.items() if d <= x < d_next]
        if not sessions:
            return None
        product = 1.0
        for o, cl in sessions:
            product *= cl / o
        return product - 1

    rng = random.Random(SEED)
    for label, cost in COSTS.items():
        by_day = defaultdict(lambda: {'BASELINE': [], 'NEXT_OPEN': [], 'H_D': []})
        signals = {}
        for d, d_next, cl, o, h, l, close_next in events:
            entry, o, h, l, close_next = map(float, (cl, o, h, l, close_next))
            base = net(entry, baseline_exit(entry, o, h, l, close_next), cost)
            next_open = net(entry, o, cost)
            s = signals.setdefault(d, signal(d, d_next))
            row = by_day[d]
            row['BASELINE'].append(base)
            row['NEXT_OPEN'].append(next_open)
            row['H_D'].append(next_open if s is not None and s <= 0 else base)
        days = sorted(by_day)
        mean = {k: [st.mean(by_day[d][k]) for d in days] for k in ('BASELINE', 'NEXT_OPEN', 'H_D')}
        d1 = [h - b for h, b in zip(mean['H_D'], mean['BASELINE'])]
        d2 = [h - n for h, n in zip(mean['H_D'], mean['NEXT_OPEN'])]
        lo1, hi1 = ci(d1, rng)
        lo2, hi2 = ci(d2, rng)
        print(f'[{label}] days {len(days)} events {len(events)} signal<=0 days '
              f'{sum(1 for d in days if signals[d] is not None and signals[d] <= 0)} no-signal days '
              f'{sum(1 for d in days if signals[d] is None)}')
        for k in ('BASELINE', 'NEXT_OPEN', 'H_D'):
            print(f'  {k:9s} dayMean {st.mean(mean[k]):+.3f}%')
        print(f'  D1 H_D-BASELINE  {st.mean(d1):+.3f}%  97.5% [{lo1:+.3f}, {hi1:+.3f}]')
        print(f'  D2 H_D-NEXT_OPEN {st.mean(d2):+.3f}%  97.5% [{lo2:+.3f}, {hi2:+.3f}]')
        if label == 'BASE':
            print('  historical criterion met' if lo1 > 0 and lo2 > 0 else '  historical criterion NOT met')


if __name__ == '__main__':
    main()
