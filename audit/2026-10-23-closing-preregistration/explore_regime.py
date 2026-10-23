"""H-B exploration on history: does the evaluation-day KOSPI return relate to the next-session result of a
candidate-like universe? Daily-bar proxy only (close entry, next open / next close exit), BASE costs.

Proxy universe per day d (fixed before looking): daily_adjusted_candle stocks whose trading value is in the top 100
of d and whose close-to-close return on d is >= +3%. Entry at close[d]; NEXT_OPEN exit at open[d+1]; CC exit at close[d+1].
Days are the unit (equal weight per day).
"""
import statistics as st
from collections import defaultdict

from explore import connect, net

c = connect()
rows = c.execute("""
    with px as (
      select stock_id, trade_date, open_price o, close_price cl, trading_value tv,
             lag(close_price) over w prev_close, lead(open_price) over w next_open, lead(close_price) over w next_close,
             lead(trade_date) over w next_date
        from daily_adjusted_candle window w as (partition by stock_id order by trade_date)),
    ranked as (select *, rank() over (partition by trade_date order by tv desc) tv_rank from px where prev_close > 0)
    select trade_date, next_date, cl, next_open, next_close, (cl / prev_close - 1) * 100 ret
      from ranked where tv_rank <= 100 and cl / prev_close - 1 >= 0.03 and next_open is not null
       and trade_date >= '2026-03-18' and trade_date <= '2026-10-21'""").fetchall()
kospi = {d: float(r) for d, r in c.execute("""
    select trade_date, (close / lag(close) over (order by trade_date) - 1) * 100 from market_index_daily
     where index_name = 'KOSPI'""") if r is not None}

by_day = defaultdict(list)
for d, nd, cl, no, nc, ret in rows:
    by_day[d].append((net(float(cl), float(no)), net(float(cl), float(nc))))
days = sorted(d for d in by_day if d in kospi)
print(f'days {len(days)} ({days[0]}..{days[-1]}), events {sum(len(by_day[d]) for d in days)}')
mk = sorted(kospi[d] for d in days)
cuts = [mk[int(len(mk) * q)] for q in (0.2, 0.4, 0.6, 0.8)]
print('KOSPI day-return quintile cuts', [round(x, 2) for x in cuts])


def bucket(x):
    return sum(x > cut for cut in cuts)


groups = defaultdict(list)
for d in days:
    groups[bucket(kospi[d])].append(d)
for q in range(5):
    ds = groups[q]
    no = [st.mean(v[0] for v in by_day[d]) for d in ds]
    cc = [st.mean(v[1] for v in by_day[d]) for d in ds]
    print(f'Q{q + 1} KOSPI {min(kospi[d] for d in ds):+.2f}..{max(kospi[d] for d in ds):+.2f}% days {len(ds):3d} '
          f'events {sum(len(by_day[d]) for d in ds):4d} | NEXT_OPEN dayMean {st.mean(no):+.2f}% win-days '
          f'{sum(x > 0 for x in no) / len(no) * 100:.0f}% | CC dayMean {st.mean(cc):+.2f}%')
allno = [st.mean(v[0] for v in by_day[d]) for d in days]
print(f'ALL NEXT_OPEN dayMean {st.mean(allno):+.2f}% | CC dayMean {st.mean(st.mean(v[1] for v in by_day[d]) for d in days):+.2f}%')
