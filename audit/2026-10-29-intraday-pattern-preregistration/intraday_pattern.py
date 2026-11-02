"""Intraday pattern at 15:15 -> next-day outcome, pre-registered study (README.md in this folder, 2026-10-29).

This script carries the README definitions over unchanged and was committed before its first run. Implementation
details the README leaves open were fixed here, also before running:
  Times are session-relative. On a day whose close is moved (2025-11-13, 10:00-16:30 by MARKET_SPECIAL_SESSIONS) every
    clock time below is shifted by the close shift (+60 minutes), as operations do. 2026-01-02 (10:00-15:30) keeps the
    close times; its first bar starts at 10:00.
  "First bar" (F2, NEXT_OPEN) = the earliest bar of the day. NEXT_OPEN additionally requires that bar to start at the
    session open (09:00, or 10:00 on late-open days); otherwise the outcome is missing.
  d-1 / d+1 = previous / next trading day present in the collected data. Nothing after LAST_EXIT (2026-10-22) is read,
    so the last evaluation day is 2026-10-21 and no ADR-010 confirmation-sample price is used.
  A factor whose inputs are missing (no 14:25 or 14:55 bar, high == low for F6, zero volume for F5) is missing for that
    stock only. Per factor x outcome, a day needs >= 50 stocks with both values present.
  Quintiles: rows sorted by (factor value, stock id); Q1 = first n//5 rows, Q5 = rows from 4n//5 (same cut as before).
  Outliers: outcome ratio exit / entry - 1 outside +/-35% is dropped and counted per outcome.
  Verdict per factor x outcome: KEEP = rule 1 and rule 2 hold; AVOID = rule 1 holds but the favoured quintile's Net
    is not > 0 in both periods (recorded as a criterion to avoid); DROP = rule 1 fails.
  Reference A (F1 >= +3%): same quintile table, no verdict, a day needs >= 10 such stocks.
  Reference B (precision): days >= 2026-09-30; a stock counts as subscribed on d when a precision_subscription_session
    of d was activated before d 15:15 (shifted). Mean Net of subscribed vs not, no verdict.
  Coverage guard: the run stops unless every trading day 2025-10-29..2026-10-22 (days in daily_adjusted_candle with
    >= 500 stocks) is in intraday_research_state and the collector has started a day after 2026-10-22 (it walks oldest
    first, so 2026-10-22 is then complete).
  --smoke loads three stocks and prints only how many inputs are present (no returns, no statistics), to check the
    query before the real run without looking at results.
Run: python intraday_pattern.py [--smoke]   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import block_ci, connect, mean, share  # noqa: E402

BUY, SELL = 0.00095, 0.00295
SEED, BLOCK = 20261029, 5
LAST_EXIT = dt.date(2026, 10, 22)
FIRST_DAY = dt.date(2025, 10, 29)
PERIODS = {'discovery': (dt.date(2025, 10, 29), dt.date(2026, 4, 30)),
           'check': (dt.date(2026, 5, 1), dt.date(2026, 10, 22))}
# MARKET_SPECIAL_SESSIONS default inside the window: date -> (open, close).
SPECIAL = {dt.date(2025, 11, 13): (dt.time(10, 0), dt.time(16, 30)),
           dt.date(2026, 1, 2): (dt.time(10, 0), dt.time(15, 30))}
MIN_VALUE_1515 = 1_000_000_000
LIMIT_LOCK = 1.295
OUTLIER = 0.35
MIN_STOCKS, MIN_STOCKS_REF = 50, 10
PRECISION_FROM = dt.date(2026, 9, 30)
FACTORS = ['F1_DAY_RET', 'F2_OPEN_TO_NOW', 'F3_LAST15', 'F4_LAST45', 'F5_VWAP_DIST', 'F6_RANGE_POS', 'F7_FROM_HIGH',
           'F8_LATE_VALUE_SHARE']
OUTCOMES = ['NEXT_OPEN', 'NEXT_CLOSE']
CHUNK = 150


def shift_minutes(day):
    session = SPECIAL.get(day)
    if session is None:
        return 0
    return (session[1].hour * 60 + session[1].minute) - (15 * 60 + 30)


def open_time(day):
    """Session open on the shifted clock (09:00 normally, 10:00 on a late-open day that keeps its close)."""
    session = SPECIAL.get(day)
    if session is None:
        return dt.time(9, 0)
    minutes = session[0].hour * 60 + session[0].minute - shift_minutes(day)
    return dt.time(minutes // 60, minutes % 60)


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def coverage(c):
    trading = [d for (d,) in c.execute(
        """select trade_date from daily_adjusted_candle where trade_date between %s and %s
            group by trade_date having count(*) >= 500 order by trade_date""", (FIRST_DAY, LAST_EXIT))]
    state = {d for (d,) in c.execute('select distinct trade_date from intraday_research_state')}
    missing = [d for d in trading if d not in state]
    later = any(d > LAST_EXIT for d in state)
    print(f'coverage: trading days {trading[0]}..{trading[-1]} = {len(trading)}, missing in research data {len(missing)}'
          f"{' (' + ', '.join(map(str, missing[:5])) + (' ...' if len(missing) > 5 else '') + ')' if missing else ''}, "
          f"collector past {LAST_EXIT}: {'yes' if later else 'no'}")
    if missing or not later:
        sys.exit('stop: research data is not complete through 2026-10-22 (rerun after the collector passes it)')


def load(c, only=None):
    """Per (stock, day) aggregates on the shifted clock, read in stock-id chunks through the primary-key index."""
    late_close = [d for d in SPECIAL if shift_minutes(d)]
    ids = [sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name)]
    ids.sort()
    if only:
        ids = ids[:only]
    lo = dt.datetime.combine(FIRST_DAY - dt.timedelta(days=7), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    hi = dt.datetime.combine(LAST_EXIT + dt.timedelta(days=1), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    rows = {}
    for k in range(0, len(ids), CHUNK):
        chunk = ids[k:k + CHUNK]
        for r in c.execute(
                """with b as (
                     select stock_id, start_time, open_price, high_price, low_price, close_price, volume, trading_value,
                            (start_time at time zone 'Asia/Seoul')::date d,
                            ((start_time at time zone 'Asia/Seoul')
                              - case when (start_time at time zone 'Asia/Seoul')::date = any(%s) then interval '60 minutes'
                                     else interval '0 minutes' end)::time t
                       from intraday_research_bar
                      where stock_id = any(%s) and start_time >= %s and start_time < %s)
                   select stock_id, d,
                          (array_agg(open_price order by start_time))[1], min(t),
                          max(close_price) filter (where t = '15:10'), max(close_price) filter (where t = '15:15'),
                          max(close_price) filter (where t = '14:55'), max(close_price) filter (where t = '14:25'),
                          max(close_price) filter (where t = '15:25'),
                          max(high_price) filter (where t <= '15:10'), min(low_price) filter (where t <= '15:10'),
                          sum(trading_value) filter (where t <= '15:10'), sum(volume) filter (where t <= '15:10'),
                          sum(trading_value) filter (where t >= '14:30' and t <= '15:10')
                     from b group by stock_id, d""", (late_close, chunk, lo, hi)):
            rows[(r[0], r[1])] = r[2:]
        print(f'  loaded {min(k + CHUNK, len(ids))}/{len(ids)} stocks, {len(rows)} stock-days', flush=True)
    return rows


def build(rows):
    days = sorted({d for _, d in rows})
    prev_day = dict(zip(days[1:], days))
    next_day = dict(zip(days, days[1:]))
    sample, outliers = [], defaultdict(int)
    for (sid, d), (first_open, first_t, p0, entry, c1455, c1425, close_bar, hi, lo, value, volume, late) in rows.items():
        if d < FIRST_DAY or d not in prev_day or d not in next_day:
            continue
        if p0 is None or entry is None or not value or value < MIN_VALUE_1515:
            continue
        prev = rows.get((sid, prev_day[d]))
        prev_close = prev[6] if prev else None
        if not prev_close or p0 >= prev_close * LIMIT_LOCK:
            continue
        f = {'F1_DAY_RET': p0 / prev_close - 1,
             'F2_OPEN_TO_NOW': p0 / first_open - 1 if first_open else None,
             'F3_LAST15': p0 / c1455 - 1 if c1455 else None,
             'F4_LAST45': p0 / c1425 - 1 if c1425 else None,
             'F5_VWAP_DIST': p0 / (value / volume) - 1 if volume else None,
             'F6_RANGE_POS': (p0 - lo) / (hi - lo) if hi is not None and lo is not None and hi > lo else None,
             'F7_FROM_HIGH': p0 / hi - 1 if hi else None,
             'F8_LATE_VALUE_SHARE': (late or 0) / value}
        nxt = rows.get((sid, next_day[d]))
        out = {}
        if nxt is not None:
            if nxt[0] and nxt[1] == open_time(next_day[d]):
                out['NEXT_OPEN'] = nxt[0]
            if nxt[6]:
                out['NEXT_CLOSE'] = nxt[6]
        nets = {}
        for name, price in out.items():
            if abs(price / entry - 1) > OUTLIER:
                outliers[name] += 1
            else:
                nets[name] = net(entry, price)
        sample.append((d, sid, f, nets))
    return sample, outliers


def quintile_days(sample, factor, outcome, minimum):
    by_day = defaultdict(list)
    for d, sid, f, nets in sample:
        if f[factor] is not None and outcome in nets:
            by_day[d].append((f[factor], sid, nets[outcome]))
    result = {}
    for d, items in by_day.items():
        if len(items) < minimum:
            continue
        items.sort()
        n = len(items)
        q1 = mean([x[2] for x in items[:n // 5]])
        q5 = mean([x[2] for x in items[4 * n // 5:]])
        result[d] = (q1, q5, n)
    return result


def period_stats(days, period, rng):
    lo, hi = PERIODS[period]
    ds = sorted(d for d in days if lo <= d <= hi)
    if len(ds) < BLOCK * 2:
        return None
    q1 = [days[d][0] for d in ds]
    q5 = [days[d][1] for d in ds]
    return {'days': len(ds), 'n': mean([days[d][2] for d in ds]), 'q1': block_ci(q1, BLOCK, rng),
            'q5': block_ci(q5, BLOCK, rng), 'spread': block_ci([b - a for a, b in zip(q1, q5)], BLOCK, rng)}


def fmt(ci):
    return f'{ci[0]:+.3f} [{ci[1]:+.3f}, {ci[2]:+.3f}]'


def table(sample, minimum, rng, verdicts):
    summary = []
    for factor in FACTORS:
        for outcome in OUTCOMES:
            days = quintile_days(sample, factor, outcome, minimum)
            stats = {p: period_stats(days, p, rng) for p in PERIODS}
            print(f'\n-- {factor} x {outcome}')
            for p, s in stats.items():
                if s is None:
                    print(f'   {p:9s} too few days')
                    continue
                print(f"   {p:9s} days {s['days']:3d} n/day {s['n']:6.0f} | Q1 Net {fmt(s['q1'])} | Q5 Net {fmt(s['q5'])} "
                      f"| Q5-Q1 {fmt(s['spread'])} %p")
            if not verdicts:
                continue
            dsc, chk = stats['discovery'], stats['check']
            if dsc is None or chk is None:
                verdict = 'DROP (too few days)'
            else:
                rule1 = (dsc['spread'][0] > 0) == (chk['spread'][0] > 0) and (dsc['spread'][1] > 0 or dsc['spread'][2] < 0)
                side = 'q5' if dsc['spread'][0] > 0 else 'q1'
                rule2 = dsc[side][0] > 0 and chk[side][0] > 0
                verdict = ('KEEP' if rule2 else 'AVOID') if rule1 else 'DROP'
                verdict += f" (favoured {side.upper()}, Net {dsc[side][0]:+.3f} / {chk[side][0]:+.3f})"
            print(f'   -> {verdict}')
            summary.append((factor, outcome, verdict))
    return summary


def precision_reference(c, sample):
    subscribed = set()
    codes = {code: sid for sid, code in c.execute('select id, stock_code from stock')}
    for code, d, activated in c.execute(
            """select stock_code, session_date, activated_at from precision_subscription_session
                where activated_at is not null and session_date between %s and %s""", (PRECISION_FROM, LAST_EXIT)):
        cut = dt.datetime.combine(d, dt.time(15, 15), dt.timezone(dt.timedelta(hours=9))) \
            + dt.timedelta(minutes=shift_minutes(d))
        if activated < cut and code in codes:
            subscribed.add((codes[code], d))
    print(f'\n===== Reference B: precision subscription (days >= {PRECISION_FROM}, descriptive only)')
    for label, rows in (('all', sample), ('F1>=+3%', [r for r in sample if r[2]['F1_DAY_RET'] >= 0.03])):
        for outcome in OUTCOMES:
            groups = defaultdict(list)
            for d, sid, f, nets in rows:
                if d >= PRECISION_FROM and outcome in nets:
                    groups[(sid, d) in subscribed].append(nets[outcome])
            parts = [f"{'subscribed' if k else 'not':10s} n {len(v):6d} Net {mean(v):+.3f}%" for k, v in
                     sorted(groups.items(), reverse=True) if v]
            print(f'   {label:8s} {outcome:10s} | ' + ' | '.join(parts))


def main():
    c = connect()
    if '--smoke' in sys.argv:
        rows = load(c, only=3)
        names = ['first_open', 'first_t', 'p0', 'entry', 'c1455', 'c1425', 'close_bar', 'hi', 'lo', 'value', 'volume',
                 'late']
        print('present per input: ' + ', '.join(f'{n} {sum(1 for v in rows.values() if v[i] is not None)}'
                                                for i, n in enumerate(names)))
        print('first_t values: ' + str(sorted({str(v[1]) for v in rows.values()})))
        return
    coverage(c)
    rows = load(c)
    sample, outliers = build(rows)
    eval_days = sorted({r[0] for r in sample})
    print(f'\nsample: {len(sample)} stock-days on {len(eval_days)} evaluation days {eval_days[0]}..{eval_days[-1]}; '
          f"outliers dropped: {dict(outliers) or 'none'}")
    for outcome in OUTCOMES:
        print(f"   {outcome}: {sum(1 for r in sample if outcome in r[3])} rows with an outcome")
    print('\n===== Main table (keep rule applies; 8 factors x 2 outcomes = 16 comparisons)')
    summary = table(sample, MIN_STOCKS, random.Random(SEED), True)
    print('\n===== Summary')
    for factor, outcome, verdict in summary:
        print(f'   {factor:20s} {outcome:10s} {verdict}')
    print('\n===== Reference A: F1_DAY_RET >= +3% only (descriptive, no verdict, >= 10 stocks per day)')
    table([r for r in sample if r[2]['F1_DAY_RET'] >= 0.03], MIN_STOCKS_REF, random.Random(SEED), False)
    precision_reference(c, sample)


if __name__ == '__main__':
    main()
