"""Exploratory intraday-feature scan with 5-minute bars (2026-10-23). Hypothesis generation only.

Fixed before running:
  Sample: every stock with REALTIME final 5M bars on day d covering 09:00..15:10 (subscribed stocks), entry days
          2026-09-03..2026-10-21 (before the ADR-010 confirmation sample).
  Entry P: close of the 15:10 bar (the price known at the 15:15 evaluation).
  Features (bars with start <= 15:10 only):
    F1 last 15 min   P / close(14:55 bar) - 1
    F2 last 45 min   P / close(14:25 bar) - 1
    F3 open to 15:15 P / open(09:00 bar) - 1
    F4 range position (P - low) / (high - low) over 09:00..15:10
    F5 VWAP distance  P / (sum trading value / sum volume) - 1
    F6 late volume share  volume 14:30..15:10 / volume 09:00..15:10
  Outcomes: NEXT_OPEN = next trading day open from daily_adjusted_candle vs P (Net, ADR-008 BASE costs);
            TO_CLOSE = adjusted close of d vs P (gross %, closing drift, reference only).
            Rows where the adjusted close of d differs from the 15:10 close by more than 15% are dropped.
  Comparison: within-day terciles of each feature, mean within-day excess Net, day-resampled 95% interval
              (10,000 draws, seed 20261023). Periods: discovery 2026-09-03..09-30, check 2026-10-01..10-21.
  Keep rule: top-minus-bottom tercile difference has the same sign in both periods and its discovery interval
             excludes zero.
Run: python intraday.py   (reads the repository .env, never prints credentials)
"""
import random
import re
import statistics as st
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
PERIODS = {'discovery': ('2026-09-03', '2026-09-30'), 'check': ('2026-10-01', '2026-10-21')}
BUY, SELL = 0.00095, 0.00295
DRAWS, SEED = 10_000, 20261023
FEATURES = ('F1_last15', 'F2_last45', 'F3_open_to_1515', 'F4_range_pos', 'F5_vwap_dist', 'F6_late_vol_share')


def connect():
    env = {}
    for line in open(ENV, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    m = re.match(r'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)', env['DB_URL'])
    c = psycopg.connect(host=m[1], port=int(m[2] or 5432), dbname=m[3], user=env['DB_USERNAME'],
                        password=env['DB_PASSWORD'], sslmode='require')
    c.execute("set time zone 'Asia/Seoul'")
    return c


def load():
    c = connect()
    bars = c.execute("""
        select stock_id, (start_time at time zone 'Asia/Seoul')::date d, to_char(start_time at time zone 'Asia/Seoul', 'HH24:MI') t,
               open, high, low, close, volume, trading_value
          from stock_candle where timeframe = '5M' and source = 'REALTIME' and is_final
           and start_time >= '2026-09-03' and start_time < '2026-10-22'
           and (start_time at time zone 'Asia/Seoul')::time <= '15:10'""").fetchall()
    by = defaultdict(dict)
    for sid, d, t, o, h, l, cl, v, tv in bars:
        by[(sid, d)][t] = (float(o), float(h), float(l), float(cl), int(v or 0), float(tv or 0))
    daily = {}
    for sid, d, cl, no in c.execute("""
        select stock_id, trade_date, close_price, lead(open_price) over (partition by stock_id order by trade_date)
          from daily_adjusted_candle where trade_date between '2026-09-01' and '2026-10-23'"""):
        daily[(sid, d)] = (float(cl), float(no) if no else None)
    rows = []
    for (sid, d), b in by.items():
        if not all(t in b for t in ('09:00', '14:25', '14:55', '15:10')) or (sid, d) not in daily:
            continue
        adj_close, next_open = daily[(sid, d)]
        p = b['15:10'][3]
        if not next_open or p <= 0 or abs(adj_close / p - 1) > 0.15:
            continue
        hi = max(x[1] for x in b.values())
        lo = min(x[2] for x in b.values())
        vol = sum(x[4] for x in b.values())
        val = sum(x[5] for x in b.values())
        late = sum(x[4] for t, x in b.items() if t >= '14:30')
        if vol <= 0 or val <= 0 or hi <= lo:
            continue
        rows.append(dict(day=str(d),
                         F1_last15=p / b['14:55'][3] - 1, F2_last45=p / b['14:25'][3] - 1,
                         F3_open_to_1515=p / b['09:00'][0] - 1, F4_range_pos=(p - lo) / (hi - lo),
                         F5_vwap_dist=p / (val / vol) - 1, F6_late_vol_share=late / vol,
                         next_open=(next_open * (1 - SELL) - p * (1 + BUY)) / (p * (1 + BUY)) * 100,
                         to_close=(adj_close / p - 1) * 100))
    return rows


def main():
    rows = load()
    by_day = defaultdict(list)
    for r in rows:
        by_day[r['day']].append(r)
    for day in by_day.values():
        m_open, m_close = st.mean(r['next_open'] for r in day), st.mean(r['to_close'] for r in day)
        for r in day:
            r['x_open'], r['x_close'] = r['next_open'] - m_open, r['to_close'] - m_close
    rng = random.Random(SEED)
    print(f'rows {len(rows)} days {len(by_day)}')
    summary = {}
    for period, (start, end) in PERIODS.items():
        days = [d for d in sorted(by_day) if start <= d <= end and len(by_day[d]) >= 9]
        print(f'\n===== {period} {start}..{end} days {len(days)} rows {sum(len(by_day[d]) for d in days)} | '
              f'NEXT_OPEN day mean {st.mean(st.mean(r["next_open"] for r in by_day[d]) for d in days):+.2f}%')
        for f in FEATURES:
            terc = {}
            for d in days:
                day = sorted(by_day[d], key=lambda r: r[f])
                n = len(day)
                terc[d] = {'low': day[:n // 3], 'high': day[n - n // 3:]}
            diff = [st.mean(r['x_open'] for r in terc[d]['high']) - st.mean(r['x_open'] for r in terc[d]['low']) for d in days]
            diff_close = [st.mean(r['x_close'] for r in terc[d]['high']) - st.mean(r['x_close'] for r in terc[d]['low']) for d in days]
            draws = sorted(st.mean(diff[rng.randrange(len(diff))] for _ in diff) for _ in range(DRAWS))
            lo, hi = draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]
            top = st.mean(st.mean(r['next_open'] for r in terc[d]['high']) for d in days)
            summary[(period, f)] = (st.mean(diff), lo, hi, top)
            print(f'  {f:18s} high-low NEXT_OPEN {st.mean(diff):+.2f}% [{lo:+.2f}, {hi:+.2f}] | top tercile level {top:+.2f}% '
                  f'| high-low TO_CLOSE {st.mean(diff_close):+.2f}%')
    print('\n===== keep rule')
    for f in FEATURES:
        dsc, chk = summary[('discovery', f)], summary[('check', f)]
        keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
        print(f"  {'KEEP' if keep else 'drop'}  {f:18s} discovery {dsc[0]:+.2f}% [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
              f"check {chk[0]:+.2f}% [{chk[1]:+.2f},{chk[2]:+.2f}] | top tercile NEXT_OPEN {dsc[3]:+.2f}% / {chk[3]:+.2f}%")


if __name__ == '__main__':
    main()
