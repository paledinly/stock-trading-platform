"""Exploratory exit-method comparison on adjusted daily bars (2026-10-23). Hypothesis generation only.

Fixed before running (no other exits, universes or parameters are tried):
  Exits:     E1 NEXT_OPEN (open of d+1), E2 BASELINE (+3%/-2% daily approximation of the current rule; open beyond
             either level fills at the open, both touched -> stop), E3 NEXT_CLOSE (close of d+1), E4 OPEN_D2 (open of d+2).
  Universes on day d: U1 turnover top 100 and close-to-close >= +3% (precision proxy), U2 turnover top 100,
             U3 close-to-close >= +10%, U4 all stocks in daily_adjusted_candle.
  Entry:     close of d (adjusted). Signals use d's close, which a 15:15 evaluation does not know yet; all exits share
             the same entry, so the comparison between exits is the target, not absolute levels.
  Periods:   discovery 2026-03-18..2026-07-31, check 2026-08-01..2026-10-21 (entry dates).
  Costs:     ADR-008 BASE. Unit: day (equal-weight mean of the day), differences vs E2 resampled over days
             (10,000 draws, seed 20261023), 95% intervals.
Run: python exit_methods.py   (reads the repository .env, never prints credentials)
"""
import random
import re
import statistics as st
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
PERIODS = {'discovery': ('2026-03-18', '2026-07-31'), 'check': ('2026-08-01', '2026-10-21')}
BUY, SELL = 0.00095, 0.00295
TARGET, STOP = 0.03, -0.02
DRAWS, SEED = 10_000, 20261023
EXITS = ('E1_NEXT_OPEN', 'E2_BASELINE', 'E3_NEXT_CLOSE', 'E4_OPEN_D2')


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


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def baseline(entry, o, h, l, c):
    if o / entry - 1 <= STOP or o / entry - 1 >= TARGET:
        return o
    up, down = h / entry - 1 >= TARGET, l / entry - 1 <= STOP
    if up and down:
        return entry * (1 + STOP)
    if up:
        return entry * (1 + TARGET)
    if down:
        return entry * (1 + STOP)
    return c


def main():
    c = connect()
    rows = c.execute("""
        with px as (
          select stock_id, trade_date, close_price cl, trading_value tv, lag(close_price) over w prev_close,
                 lead(open_price) over w o1, lead(high_price) over w h1, lead(low_price) over w l1,
                 lead(close_price) over w c1, lead(open_price, 2) over w o2
            from daily_adjusted_candle window w as (partition by stock_id order by trade_date)),
        ranked as (select *, rank() over (partition by trade_date order by tv desc) tv_rank from px
                    where prev_close > 0 and cl > 0)
        select trade_date, tv_rank, cl / prev_close - 1, cl, o1, h1, l1, c1, o2 from ranked
         where o1 > 0 and o2 > 0 and trade_date between %s and %s""",
                     (PERIODS['discovery'][0], PERIODS['check'][1])).fetchall()
    universes = {
        'U1 top100 & >=+3%': lambda rank, ret: rank <= 100 and ret >= 0.03,
        'U2 top100': lambda rank, ret: rank <= 100,
        'U3 >=+10%': lambda rank, ret: ret >= 0.10,
        'U4 all': lambda rank, ret: True,
    }
    # day -> universe -> exit -> list of Net
    data = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    for d, rank, ret, cl, o1, h1, l1, c1, o2 in rows:
        cl, o1, h1, l1, c1, o2, ret = map(float, (cl, o1, h1, l1, c1, o2, ret))
        nets = {'E1_NEXT_OPEN': net(cl, o1), 'E2_BASELINE': net(cl, baseline(cl, o1, h1, l1, c1)),
                'E3_NEXT_CLOSE': net(cl, c1), 'E4_OPEN_D2': net(cl, o2)}
        for name, member in universes.items():
            if member(rank, ret):
                for e, v in nets.items():
                    data[str(d)][name][e].append(v)

    rng = random.Random(SEED)
    for period, (start, end) in PERIODS.items():
        days = [d for d in sorted(data) if start <= d <= end]
        print(f'\n===== {period} {start}..{end} ({len(days)} entry days)')
        for name in universes:
            ds = [d for d in days if data[d][name]['E1_NEXT_OPEN']]
            mean = {e: [st.mean(data[d][name][e]) for d in ds] for e in EXITS}
            events = sum(len(data[d][name]['E1_NEXT_OPEN']) for d in ds)
            line = f'{name:18s} days {len(ds):3d} events {events:6d} | ' + ' '.join(
                f'{e.split("_", 1)[1]} {st.mean(mean[e]):+.2f}%' for e in EXITS)
            print(line)
            for e in ('E1_NEXT_OPEN', 'E3_NEXT_CLOSE', 'E4_OPEN_D2'):
                diffs = [a - b for a, b in zip(mean[e], mean['E2_BASELINE'])]
                draws = sorted(st.mean(diffs[rng.randrange(len(diffs))] for _ in diffs) for _ in range(DRAWS))
                print(f'    {e.split("_", 1)[1]:10s} - BASELINE {st.mean(diffs):+.2f}% '
                      f'[{draws[int(DRAWS * 0.025)]:+.2f}, {draws[int(DRAWS * 0.975) - 1]:+.2f}] '
                      f'winning days {sum(x > 0 for x in diffs) / len(diffs) * 100:.0f}%')


if __name__ == '__main__':
    main()
