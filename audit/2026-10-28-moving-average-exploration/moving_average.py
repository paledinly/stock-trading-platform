"""Exploratory check of the closing score's daily moving-average components on three years of adjusted daily bars
(2026-10-28). Hypothesis generation only.

Fixed before running (nothing else is tried):
  Share filter (same as reversal.py): 6-character code ending in '0', not starting with 'Q' or 'F', name without '스팩'.
  Candidates on d: trading-value rank top 100 among filtered shares and close-to-close return on d in [+3%, +29.5%).
    Closes at or near the +30% limit are excluded: they are usually locked and not buyable (volatility exploration).
  Moving averages as in DailyMovingAverageService: bars up to d-1 only; lastClose = close[d-1];
    MA5, MA20 need 20 bars, MA60 needs 60, MA120 needs 120 (new, not in the score); MA20 rising = MA20(d-1) > MA20(d-2).
  Factors (true vs false), with the direction the score expects:
    G1 DAILY_TREND_PASS  closeAboveMa20 and ma20Rising (gate DAILY_TREND_WEAK when false)   expected better
    G2 OVEREXTENDED      close[d] > 1.12 x MA20 (gate OVEREXTENDED; close[d] stands in for the signal price) expected worse
    S1 ALIGNMENT         close > MA20, MA5 > MA20, MA20 > MA60 (+8 points)                   expected better
    S2 MA20_RISING       (+5 points)                                                           expected better
    S3 CLOSE_ABOVE_MA20  (+4 points)                                                           expected better
    S4 MA5_ABOVE_MA20    (part of the trend-weakness penalty)                                  expected better
    N1 ABOVE_MA120       close[d-1] > MA120 (new candidate factor)                             expected better
  Entry close[d]. Outcomes: NEXT_OPEN = open[d+1]; BASELINE daily approximation of +3% / -2% on d+1 (open gap beyond
  either level exits at the open; both levels touched counts as the stop; otherwise close[d+1]). ADR-008 BASE costs.
  Exit dates <= 2026-10-22 (no prices from the ADR-010 confirmation sample).
  Comparison: per day, mean Net of true minus mean Net of false (days with both); circular moving-block resampling
  of days (block 5, 10,000 draws, seed 20261028), 95% interval.
  Periods by entry date: discovery 2023-11-21..2025-04-30, check 2025-05-01..2026-10-21.
  Keep rule (per factor and outcome): same sign in both periods and the discovery interval excludes zero.
Run: python moving_average.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import sys
from collections import defaultdict
from pathlib import Path

# Shared loader and statistics from the volatility exploration (same filter, costs and resampling).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import block_ci, load, mean, net

PERIODS = {'discovery': ('2023-11-21', '2025-04-30'), 'check': ('2025-05-01', '2026-10-21')}
FACTORS = {'G1 DAILY_TREND_PASS': 'better', 'G2 OVEREXTENDED': 'worse', 'S1 ALIGNMENT': 'better',
           'S2 MA20_RISING': 'better', 'S3 CLOSE_ABOVE_MA20': 'better', 'S4 MA5_ABOVE_MA20': 'better',
           'N1 ABOVE_MA120': 'better'}
SEED = 20261028


def ma(closes, n, end):
    """Mean of closes[end-n+1 .. end] (inclusive), or None without enough history."""
    return mean(closes[end - n + 1:end + 1]) if end - n + 1 >= 0 else None


def baseline(entry, o, h, l, c):
    target, stop = entry * 1.03, entry * 0.98
    if o >= target or o <= stop:
        exit_ = o
    elif l <= stop:
        exit_ = stop
    elif h >= target:
        exit_ = target
    else:
        exit_ = c
    return net(entry, exit_)


def load_hl():
    """High/low are not in volatility.load(); read them for the BASELINE approximation."""
    from volatility import connect, share, LAST_EXIT
    c = connect()
    allowed = {sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name)}
    hl = {}
    for sid, d, h, l in c.execute(
            """select stock_id, trade_date, high_price, low_price from daily_adjusted_candle
                where open_price > 0 and close_price > 0 and trade_date <= %s""", (LAST_EXIT,)):
        if sid in allowed:
            hl[(sid, d)] = (float(h), float(l))
    return hl


def main():
    bars = load()
    hl = load_hl()
    start = dt.date.fromisoformat(PERIODS['discovery'][0])
    end = dt.date.fromisoformat(PERIODS['check'][1])
    by_day = defaultdict(list)
    for sid, series in bars.items():
        closes = [b[2] for b in series]
        for i in range(2, len(series) - 1):
            d, _, cl, tv = series[i]
            if not start <= d <= end:
                continue
            ret = cl / closes[i - 1] - 1
            p = i - 1  # moving averages use bars up to d-1
            ma5, ma20, ma20_prev = ma(closes, 5, p), ma(closes, 20, p), ma(closes, 20, p - 1)
            if ma20 is None or ma20_prev is None:
                continue
            ma60, ma120 = ma(closes, 60, p), ma(closes, 120, p)
            last = closes[p]
            factors = {
                'G1 DAILY_TREND_PASS': last > ma20 and ma20 > ma20_prev,
                'G2 OVEREXTENDED': cl > ma20 * 1.12,
                'S1 ALIGNMENT': None if ma60 is None else (last > ma20 and ma5 > ma20 and ma20 > ma60),
                'S2 MA20_RISING': ma20 > ma20_prev,
                'S3 CLOSE_ABOVE_MA20': last > ma20,
                'S4 MA5_ABOVE_MA20': ma5 > ma20,
                'N1 ABOVE_MA120': None if ma120 is None else last > ma120,
            }
            nxt = series[i + 1]
            h, l = hl[(sid, nxt[0])]
            by_day[d].append((tv, ret, factors, {'NEXT_OPEN': net(cl, nxt[1]),
                                                  'BASELINE': baseline(cl, nxt[1], h, l, nxt[2])}))
    candidates = {}
    for d, day in by_day.items():
        ranked = sorted(day, key=lambda r: -r[0])
        candidates[d] = [r for rank, r in enumerate(ranked, 1) if rank <= 100 and 0.03 <= r[1] < 0.295]
    rng = random.Random(SEED)
    summary = {}
    for period, (s0, e0) in PERIODS.items():
        s, e = dt.date.fromisoformat(s0), dt.date.fromisoformat(e0)
        days = [d for d in sorted(candidates) if s <= d <= e and candidates[d]]
        events = sum(len(candidates[d]) for d in days)
        print(f'\n===== {period} {s0}..{e0}: days {len(days)}, candidates {events}')
        for outcome in ('NEXT_OPEN', 'BASELINE'):
            level = mean([mean([r[3][outcome] for r in candidates[d]]) for d in days])
            print(f'  -- {outcome} (candidate day mean {level:+.2f}%)')
            for factor in FACTORS:
                diffs, n_true, n_all = [], 0, 0
                for d in days:
                    known = [r for r in candidates[d] if r[2][factor] is not None]
                    yes = [r[3][outcome] for r in known if r[2][factor]]
                    no = [r[3][outcome] for r in known if not r[2][factor]]
                    n_true += len(yes); n_all += len(known)
                    if yes and no:
                        diffs.append(mean(yes) - mean(no))
                m = block_ci(diffs, 5, rng)
                summary[(period, outcome, factor)] = m
                print(f'    {factor:22s} true {n_true:5d}/{n_all:5d} ({n_true / n_all * 100:4.1f}%) days {len(diffs):3d} '
                      f'| true - false {m[0]:+.2f} [{m[1]:+.2f}, {m[2]:+.2f}]')
    print('\n===== keep rule (direction vs the score\'s expectation)')
    for outcome in ('NEXT_OPEN', 'BASELINE'):
        for factor, expected in FACTORS.items():
            dsc, chk = summary[('discovery', outcome, factor)], summary[('check', outcome, factor)]
            keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
            agrees = (dsc[0] > 0) == (expected == 'better')
            verdict = 'KEEP' if keep else 'drop'
            note = ('as expected' if agrees else 'OPPOSITE to the score') if keep else ''
            print(f"  {verdict}  {outcome:9s} {factor:22s} discovery {dsc[0]:+.2f} [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
                  f"check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}] {note}")


if __name__ == '__main__':
    main()
