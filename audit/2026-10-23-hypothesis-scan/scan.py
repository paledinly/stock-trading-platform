"""Exploratory hypothesis scan on adjusted daily bars (2026-10-23). Hypothesis generation only, not a judgment.

Fixed before running (nothing else is tried):
  Base universe on day d: daily_adjusted_candle stocks with trading value >= 1,000,000,000 KRW on d
  (the operating minimum daily trading value), at least 21 prior bars, positive prices.
  Families and buckets (computed from d's daily bar and earlier bars):
    H1 day return:            [0,3)%, [3,10)%, [10,20)%, [20,29)%, >=29%   (negative days are outside the scan)
    H2 close location (C-L)/(H-L): <0.2, [0.2,0.8], >0.8   (H == L excluded)
    H3 volume / 20-day mean (prior 20 days): <1, [1,3), >=3
    H4 close / MA20 (prior 20 closes incl. d) - 1: <=0, (0,12]%, >12%
    H5 intraday close/open - 1: <=-3%, (-3,3)%, >=+3%
    H6 consecutive up closes through d: >=3, otherwise
    H7 trading value rank: 1..100, 101..500
  Exits: NEXT_OPEN (primary), BASELINE (+3%/-2% daily approximation, both touched -> stop). ADR-008 BASE costs.
  Entry: close of d (15 minutes and the closing auction later than a 15:15 evaluation; noted in the README).
  Periods: discovery 2026-03-18..2026-07-31, check 2026-08-01..2026-10-21 (entry dates).
  Statistic: day-equal mean of a bucket, and its difference from the base universe on the same days, resampled
  over days (10,000 draws, seed 20261023), 95% interval.
  Keep rule: same sign of the difference in both periods AND the discovery interval excludes zero
  (reported as KEEP; everything else is DROP).
Run: python scan.py   (reads the repository .env, never prints credentials)
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


def buckets(r):
    ret, clv, vr, ma, intra, streak, rank = r['ret'], r['clv'], r['vr'], r['ma'], r['intra'], r['streak'], r['rank']
    out = []
    if ret >= 0:
        out.append(('H1 day return', '[0,3)%' if ret < 3 else '[3,10)%' if ret < 10 else '[10,20)%' if ret < 20
                    else '[20,29)%' if ret < 29 else '>=29%'))
    if clv is not None:
        out.append(('H2 close location', '<0.2' if clv < 0.2 else '>0.8' if clv > 0.8 else '[0.2,0.8]'))
    if vr is not None:
        out.append(('H3 volume/20d', '<1' if vr < 1 else '[1,3)' if vr < 3 else '>=3'))
    if ma is not None:
        out.append(('H4 close/MA20', '<=0' if ma <= 0 else '(0,12]%' if ma <= 12 else '>12%'))
    out.append(('H5 close/open', '<=-3%' if intra <= -3 else '>=+3%' if intra >= 3 else '(-3,3)%'))
    out.append(('H6 up streak', '>=3' if streak >= 3 else '<3'))
    if rank <= 500:
        out.append(('H7 value rank', '1..100' if rank <= 100 else '101..500'))
    return out


def main():
    c = connect()
    rows = c.execute("""
        with px as (
          select stock_id, trade_date, open_price o, high_price h, low_price l, close_price cl, volume v, trading_value tv,
                 lag(close_price) over w prev_close,
                 avg(volume) over (partition by stock_id order by trade_date rows between 20 preceding and 1 preceding) vol20,
                 avg(close_price) over (partition by stock_id order by trade_date rows between 19 preceding and current row) ma20,
                 count(*) over (partition by stock_id order by trade_date rows between 20 preceding and 1 preceding) prior_n,
                 lead(open_price) over w o1, lead(high_price) over w h1, lead(low_price) over w l1, lead(close_price) over w c1,
                 (close_price > lag(close_price) over w)::int up0,
                 (lag(close_price) over w > lag(close_price, 2) over w)::int up1,
                 (lag(close_price, 2) over w > lag(close_price, 3) over w)::int up2
            from daily_adjusted_candle window w as (partition by stock_id order by trade_date)),
        ranked as (select *, rank() over (partition by trade_date order by tv desc) rnk from px
                    where prev_close > 0 and cl > 0 and o > 0 and tv >= 1000000000 and prior_n >= 20)
        select trade_date, rnk, o, h, l, cl, v, prev_close, vol20, ma20, o1, h1, l1, c1, up0, up1, up2 from ranked
         where o1 > 0 and trade_date between %s and %s""", (PERIODS['discovery'][0], PERIODS['check'][1])).fetchall()

    per_day = defaultdict(lambda: defaultdict(lambda: {'open': [], 'base': []}))
    for d, rnk, o, h, l, cl, v, pc, vol20, ma20, o1, h1, l1, c1, up0, up1, up2 in rows:
        o, h, l, cl, pc, o1, h1, l1, c1 = map(float, (o, h, l, cl, pc, o1, h1, l1, c1))
        r = dict(ret=(cl / pc - 1) * 100, clv=(cl - l) / (h - l) if h > l else None,
                 vr=float(v) / float(vol20) if vol20 else None, ma=(cl / float(ma20) - 1) * 100 if ma20 else None,
                 intra=(cl / o - 1) * 100, streak=(up0 or 0) + ((up0 or 0) and (up1 or 0)) + ((up0 or 0) and (up1 or 0) and (up2 or 0)),
                 rank=rnk)
        open_net, base_net = net(cl, o1), net(cl, baseline(cl, o1, h1, l1, c1))
        day = per_day[str(d)]
        day[('BASE', 'all')]['open'].append(open_net)
        day[('BASE', 'all')]['base'].append(base_net)
        for key in buckets(r):
            day[key]['open'].append(open_net)
            day[key]['base'].append(base_net)

    rng = random.Random(SEED)
    keys = sorted({k for d in per_day.values() for k in d if k[0] != 'BASE'})
    stats = {}
    for period, (start, end) in PERIODS.items():
        days = [d for d in sorted(per_day) if start <= d <= end]
        base_open = {d: st.mean(per_day[d][('BASE', 'all')]['open']) for d in days}
        base_base = {d: st.mean(per_day[d][('BASE', 'all')]['base']) for d in days}
        print(f'\n===== {period} {start}..{end} days {len(days)} | base universe NEXT_OPEN '
              f'{st.mean(base_open.values()):+.2f}% BASELINE {st.mean(base_base.values()):+.2f}%')
        for key in keys:
            ds = [d for d in days if per_day[d][key]['open']]
            if len(ds) < 10:
                continue
            m_open = [st.mean(per_day[d][key]['open']) for d in ds]
            m_base = [st.mean(per_day[d][key]['base']) for d in ds]
            diff = [m - base_open[d] for m, d in zip(m_open, ds)]
            draws = sorted(st.mean(diff[rng.randrange(len(diff))] for _ in diff) for _ in range(DRAWS))
            lo, hi = draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]
            events = sum(len(per_day[d][key]['open']) for d in ds)
            stats[(period, key)] = (st.mean(diff), lo, hi, st.mean(m_open))
            print(f'  {key[0]:17s} {key[1]:10s} days {len(ds):3d} events {events:6d} | NEXT_OPEN {st.mean(m_open):+.2f}% '
                  f'BASELINE {st.mean(m_base):+.2f}% | vs base {st.mean(diff):+.2f}% [{lo:+.2f}, {hi:+.2f}]')

    print('\n===== keep rule: same sign in both periods and discovery interval excludes zero')
    for key in keys:
        dsc, chk = stats.get(('discovery', key)), stats.get(('check', key))
        if not dsc or not chk:
            continue
        keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
        print(f"  {'KEEP' if keep else 'drop'}  {key[0]:17s} {key[1]:10s} discovery {dsc[0]:+.2f}% [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
              f"check {chk[0]:+.2f}% [{chk[1]:+.2f},{chk[2]:+.2f}] | NEXT_OPEN level {dsc[3]:+.2f}% / {chk[3]:+.2f}%")


if __name__ == '__main__':
    main()
