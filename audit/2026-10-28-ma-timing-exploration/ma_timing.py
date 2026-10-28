"""Exploratory moving-average entry/exit timing rules on three years of adjusted daily bars (2026-10-28).
Hypothesis generation only. A different strategy from the overnight closing recommendation (multi-day holding).

Fixed before running (nothing else is tried):
  Share filter (same as reversal.py): 6-character code ending in '0', not starting with 'Q' or 'F', name without '스팩'.
  Eligible signal day d: mean trading value of the last 20 bars up to d >= 1,000,000,000 KRW, at least 61 bars up to d.
  Moving averages include day d (MA5, MA20, MA60 of closes up to d).
  Signals are read at close[d]; trades fill at open[d+1] (entry) and open[t+1] after an exit signal at close[t].
  One position per stock at a time; maximum holding 60 trading days (exit at open of the 61st day).
  Rules:
    A  entry: close[d] > MA20[d] and close[d-1] <= MA20[d-1].        exit: close[t] < MA20[t].
    B  entry: MA5[d] > MA20[d] and MA5[d-1] <= MA20[d-1].            exit: MA5[t] < MA20[t].
    C  entry: MA20[d] > MA20[d-1], MA20[d] > MA60[d], low[d] <= 1.02 x MA20[d], close[d] > MA20[d], close[d] > close[d-1].
       exit: close[t] < MA20[t], or close[t] <= 0.90 x highest close since entry.
  Positions still open on 2026-10-22 are closed at that day's close (no prices from the ADR-010 confirmation sample).
  Trade Net = ADR-008 BASE costs once per round trip. Market = equal-weight open-to-open return of all eligible stocks
  (trading value >= 1bn on the previous day; corrected after the first run, see README), compounded over the same
  holding window. Excess = trade gross - market.
  Statistics per rule and period (by entry date): trades, mean holding days, win rate, mean/median Net, mean excess.
  Day-level means (by entry day), circular moving-block resampling of days (block 20, 10,000 draws, seed 20261028).
  Periods: discovery 2024-01-22..2025-04-30, check 2025-05-01..2026-10-21. Descriptive table per calendar year.
  Keep rule (per rule, for Net and for excess): same sign in both periods and the discovery interval excludes zero.
Known bias: delisted stocks are absent; long holding is flattered (losers that were delisted are missing).
Run: python ma_timing.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import random
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import LAST_EXIT, block_ci, connect, mean, share  # noqa: E402

BUY, SELL = 0.00095, 0.00295
PERIODS = {'discovery': ('2024-01-22', '2025-04-30'), 'check': ('2025-05-01', '2026-10-21')}
YEARS = {'2024': ('2024-01-22', '2024-12-31'), '2025': ('2025-01-01', '2025-12-31'), '2026': ('2026-01-01', '2026-10-21')}
MIN_VALUE = 1_000_000_000
MAX_HOLD = 60
SEED = 20261028


def load():
    c = connect()
    allowed = {sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name)}
    bars = defaultdict(list)
    for sid, d, o, h, l, cl, tv in c.execute(
            """select stock_id, trade_date, open_price, high_price, low_price, close_price, trading_value
                 from daily_adjusted_candle where open_price > 0 and close_price > 0 and trade_date <= %s
                order by stock_id, trade_date""", (LAST_EXIT,)):
        if sid in allowed:
            bars[sid].append((d, float(o), float(h), float(l), float(cl), float(tv or 0)))
    return bars


def rolling(values, n):
    out, total = [None] * len(values), 0.0
    for i, v in enumerate(values):
        total += v
        if i >= n:
            total -= values[i - n]
        if i >= n - 1:
            out[i] = total / n
    return out


def market_series(bars):
    """Equal-weight open-to-open return from day k to the next trading day, over stocks with value >= 1bn on k-1.

    Correction after the first run (see README): eligibility used the trading value of day k itself, which is only
    known at day k's close and favours stocks that rose on day k, so the market return was inflated.
    """
    acc = defaultdict(list)
    for series in bars.values():
        for k in range(1, len(series) - 1):
            if series[k - 1][5] >= MIN_VALUE:
                acc[series[k][0]].append(series[k + 1][1] / series[k][1] - 1)
    return {d: mean(v) for d, v in acc.items()}


def trades_for(series, rule):
    dates = [b[0] for b in series]
    o = [b[1] for b in series]
    low = [b[3] for b in series]
    c = [b[4] for b in series]
    tv20 = rolling([b[5] for b in series], 20)
    ma5, ma20, ma60 = rolling(c, 5), rolling(c, 20), rolling(c, 60)
    trades = []
    i = 60
    n = len(series)
    while i < n - 1:
        if tv20[i] is None or tv20[i] < MIN_VALUE or ma20[i - 1] is None:
            i += 1
            continue
        if rule == 'A':
            signal = c[i] > ma20[i] and c[i - 1] <= ma20[i - 1]
        elif rule == 'B':
            signal = ma5[i] > ma20[i] and ma5[i - 1] <= ma20[i - 1]
        else:
            signal = (ma20[i] > ma20[i - 1] and ma20[i] > ma60[i] and low[i] <= 1.02 * ma20[i]
                      and c[i] > ma20[i] and c[i] > c[i - 1])
        if not signal:
            i += 1
            continue
        entry_i = i + 1
        peak = c[entry_i]
        exit_i, exit_price, forced = None, None, False
        for t in range(entry_i, n):
            peak = max(peak, c[t])
            if rule == 'A':
                out = c[t] < ma20[t]
            elif rule == 'B':
                out = ma5[t] < ma20[t]
            else:
                out = c[t] < ma20[t] or c[t] <= 0.90 * peak
            if out or t - entry_i + 1 >= MAX_HOLD:
                if t + 1 < n:
                    exit_i, exit_price = t + 1, o[t + 1]
                else:
                    exit_i, exit_price, forced = t, c[t], True
                break
        if exit_i is None:
            exit_i, exit_price, forced = n - 1, c[n - 1], True
        trades.append((dates[i], dates[entry_i], dates[exit_i], o[entry_i], exit_price, exit_i - entry_i, forced))
        i = exit_i
    return trades


def evaluate(trades, market, trading_days, s, e, rng, label, summary=None):
    pos = {d: k for k, d in enumerate(trading_days)}
    rows = []
    for signal_day, entry_day, exit_day, entry, exit_, hold, forced in trades:
        if not s <= signal_day <= e:
            continue
        gross = exit_ / entry - 1
        net = (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100
        m = 1.0
        for k in range(pos[entry_day], pos[exit_day]):
            m *= 1 + market.get(trading_days[k], 0.0)
        rows.append((signal_day, net, gross * 100 - (m - 1) * 100, hold, forced))
    if not rows:
        print(f'    {label}: no trades')
        return
    by_day = defaultdict(list)
    for r in rows:
        by_day[r[0]].append(r)
    days = sorted(by_day)
    net_s = [mean([r[1] for r in by_day[d]]) for d in days]
    exc_s = [mean([r[2] for r in by_day[d]]) for d in days]
    n_ci, x_ci = block_ci(net_s, 20, rng), block_ci(exc_s, 20, rng)
    nets = [r[1] for r in rows]
    print(f'    {label}: trades {len(rows):6d} (forced close {sum(1 for r in rows if r[4])}) | hold {mean([r[3] for r in rows]):5.1f}d '
          f'| win {sum(1 for v in nets if v > 0) / len(nets) * 100:4.1f}% | median Net {st.median(nets):+.2f}% '
          f'| mean Net {n_ci[0]:+.2f}% [{n_ci[1]:+.2f}, {n_ci[2]:+.2f}] | excess vs market {x_ci[0]:+.2f}%p [{x_ci[1]:+.2f}, {x_ci[2]:+.2f}]')
    if summary is not None:
        summary[(label, 'Net')] = n_ci
        summary[(label, 'excess')] = x_ci


def main():
    bars = load()
    market = market_series(bars)
    trading_days = sorted({b[0] for series in bars.values() for b in series})
    trades = {rule: [t for series in bars.values() for t in trades_for(series, rule)] for rule in ('A', 'B', 'C')}
    rng = random.Random(SEED)
    summary = {}
    for period, (s0, e0) in PERIODS.items():
        print(f'\n===== {period} signals {s0}..{e0}')
        for rule in ('A', 'B', 'C'):
            evaluate(trades[rule], market, trading_days, dt.date.fromisoformat(s0), dt.date.fromisoformat(e0), rng,
                     f'{period}:{rule}', summary)
    print('\n===== keep rule')
    for rule in ('A', 'B', 'C'):
        for what in ('Net', 'excess'):
            dsc, chk = summary[(f'discovery:{rule}', what)], summary[(f'check:{rule}', what)]
            keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
            print(f"  {'KEEP' if keep else 'drop'}  {rule} {what:6s} discovery {dsc[0]:+.2f} [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
                  f"check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")
    print('\n===== descriptive by year of signal (no keep rule)')
    for year, (s0, e0) in YEARS.items():
        print(f'\n-- {year}')
        for rule in ('A', 'B', 'C'):
            evaluate(trades[rule], market, trading_days, dt.date.fromisoformat(s0), dt.date.fromisoformat(e0), rng,
                     f'{year}:{rule}')


if __name__ == '__main__':
    main()
