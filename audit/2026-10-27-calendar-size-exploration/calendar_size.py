"""Exploratory calendar and size scan on adjusted daily bars (2026-10-27). Hypothesis generation only.

Fixed before running (nothing else is tried):
  Outcome: NEXT_OPEN Net = open[d+1] vs close[d], ADR-008 BASE costs. Unit: day (equal-weight mean of the day).
  Base universe U1 (precision proxy): trading value rank top 100 on d and close-to-close return on d >= +3%.
  B1 weekday of d (Mon..Fri), and LONG_BREAK (next trading day is more than 1 calendar day later) vs NEXT_DAY.
  B2 calendar (trading days from daily_adjusted_candle): EXPIRY = second Thursday of the month, or the previous
     trading day if it is closed; EXPIRY_EVE = trading day before EXPIRY; MONTH_END = last trading day of the month;
     MONTH_START = first trading day of the month.
  For B1/B2: U1 day means on the group's days minus U1 day means on all other days; two-sample resampling of days
  (10,000 draws, seed 20261027), 95% interval.
  C1 size: L1 = prior 20-day mean trading value top 50 (large/liquid proxy, any return), L2 = L1 with return >= +3%;
  daily mean NEXT_OPEN of L1, L2 and U1, and paired day differences L2 - U1 (days where both exist), resampled.
  Periods: discovery 2026-03-18..07-31, check 2026-08-01..10-21. Keep rule: same sign in both periods and the
  discovery interval excludes zero.
Run: python calendar_size.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import re
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
PERIODS = {'discovery': ('2026-03-18', '2026-07-31'), 'check': ('2026-08-01', '2026-10-21')}
BUY, SELL = 0.00095, 0.00295
DRAWS, SEED = 10_000, 20261027


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


def ci_two(a, b, rng):
    draws = []
    for _ in range(DRAWS):
        draws.append(mean([a[rng.randrange(len(a))] for _ in a]) - mean([b[rng.randrange(len(b))] for _ in b]))
    draws.sort()
    return mean(a) - mean(b), draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]


def ci_paired(diffs, rng):
    draws = sorted(mean([diffs[rng.randrange(len(diffs))] for _ in diffs]) for _ in range(DRAWS))
    return mean(diffs), draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]


def main():
    c = connect()
    rows = c.execute("""
        with px as (
          select stock_id, trade_date, close_price cl, trading_value tv, lag(close_price) over w prev_close,
                 lead(open_price) over w o1,
                 avg(trading_value) over (partition by stock_id order by trade_date rows between 20 preceding and 1 preceding) tv20,
                 count(*) over (partition by stock_id order by trade_date rows between 20 preceding and 1 preceding) n20
            from daily_adjusted_candle window w as (partition by stock_id order by trade_date)),
        ranked as (select *, rank() over (partition by trade_date order by tv desc) tv_rank,
                          rank() over (partition by trade_date order by tv20 desc nulls last) size_rank
                     from px where prev_close > 0 and cl > 0 and n20 >= 20)
        select trade_date, tv_rank, size_rank, cl / prev_close - 1, cl, o1 from ranked
         where o1 > 0 and trade_date between %s and %s""", (PERIODS['discovery'][0], PERIODS['check'][1])).fetchall()
    trading_days = sorted({r[0] for r in c.execute("select distinct trade_date from daily_adjusted_candle "
                                                   "where trade_date between '2026-03-01' and '2026-10-31'")})
    nxt = {d: trading_days[i + 1] for i, d in enumerate(trading_days[:-1])}
    groups = defaultdict(set)
    by_month = defaultdict(list)
    for d in trading_days:
        by_month[(d.year, d.month)].append(d)
        groups['WEEKDAY ' + 'MonTueWedThuFri'[d.weekday() * 3:d.weekday() * 3 + 3]].add(d)
        if d in nxt:
            groups['LONG_BREAK' if (nxt[d] - d).days > 1 else 'NEXT_DAY'].add(d)
    for (y, m), days in by_month.items():
        groups['MONTH_START'].add(days[0])
        groups['MONTH_END'].add(days[-1])
        first = dt.date(y, m, 1)
        second_thu = first + dt.timedelta(days=(3 - first.weekday()) % 7 + 7)
        expiry = max((d for d in days if d <= second_thu), default=None)
        if expiry:
            groups['EXPIRY'].add(expiry)
            i = trading_days.index(expiry)
            if i > 0:
                groups['EXPIRY_EVE'].add(trading_days[i - 1])

    u1, l1, l2 = defaultdict(list), defaultdict(list), defaultdict(list)
    for d, tv_rank, size_rank, ret, cl, o1 in rows:
        net = (float(o1) * (1 - SELL) - float(cl) * (1 + BUY)) / (float(cl) * (1 + BUY)) * 100
        if tv_rank <= 100 and ret >= 0.03:
            u1[d].append(net)
        if size_rank <= 50:
            l1[d].append(net)
            if ret >= 0.03:
                l2[d].append(net)
    rng = random.Random(SEED)
    summary = {}
    for period, (start, end) in PERIODS.items():
        s, e = dt.date.fromisoformat(start), dt.date.fromisoformat(end)
        days = [d for d in sorted(u1) if s <= d <= e]
        daily = {d: mean(u1[d]) for d in days}
        print(f'\n===== {period} {start}..{end} days {len(days)} | U1 NEXT_OPEN day mean {mean(list(daily.values())):+.2f}%')
        for name in sorted(groups):
            inside = [daily[d] for d in days if d in groups[name]]
            outside = [daily[d] for d in days if d not in groups[name]]
            if len(inside) < 3 or len(outside) < 3:
                continue
            diff, lo, hi = ci_two(inside, outside, rng)
            summary[(period, name)] = (diff, lo, hi)
            print(f'  {name:12s} days {len(inside):3d} | U1 NEXT_OPEN {mean(inside):+.2f}% vs other days {mean(outside):+.2f}% '
                  f'| diff {diff:+.2f} [{lo:+.2f}, {hi:+.2f}]')
        for label, universe in (('L1 large any', l1), ('L2 large +3%', l2)):
            ds = [d for d in sorted(universe) if s <= d <= e]
            level = mean([mean(universe[d]) for d in ds])
            paired = [mean(universe[d]) - daily[d] for d in ds if d in daily]
            diff, lo, hi = ci_paired(paired, rng)
            summary[(period, label)] = (diff, lo, hi)
            print(f'  {label:12s} days {len(ds):3d} events {sum(len(universe[d]) for d in ds):5d} | NEXT_OPEN {level:+.2f}% '
                  f'| minus U1 same day {diff:+.2f} [{lo:+.2f}, {hi:+.2f}]')
    print('\n===== keep rule')
    for key in sorted({k[1] for k in summary}):
        dsc, chk = summary.get(('discovery', key)), summary.get(('check', key))
        if not dsc or not chk:
            continue
        keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
        print(f"  {'KEEP' if keep else 'drop'}  {key:12s} discovery {dsc[0]:+.2f} [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
              f"check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")


if __name__ == '__main__':
    main()
