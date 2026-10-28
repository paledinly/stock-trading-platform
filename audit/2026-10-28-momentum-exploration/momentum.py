"""Exploratory intermediate momentum and 52-week-high scan on three years of adjusted daily bars (2026-10-28).
Hypothesis generation only. Monthly holding, a different horizon from the overnight closing recommendation.

Fixed before running (nothing else is tried):
  Share filter (same as reversal.py): 6-character code ending in '0', not starting with 'Q' or 'F', name without '스팩'.
  Formation date f = last trading day of each month. Universe at f: mean trading value of the last 20 bars up to f
  >= 1,000,000,000 KRW and enough history for the factor.
  Factors (bars counted per stock, f = index of the formation bar):
    M6   close[f-21] / close[f-126] - 1   (six-month return skipping the most recent month), needs 127 bars
    M12  close[f-21] / close[f-252] - 1   (twelve-month return skipping the most recent month), needs 253 bars
    H52  close[f] / max(high[f-251..f])   (closeness to the 52-week high), needs 252 bars
  Quintiles by factor within the universe at f (Q1 lowest, Q5 highest). Buy close[f], sell at the next formation
  date's close (or the stock's last close on or before it). Equal weight. Full turnover each month (BASE costs every
  month). Exits <= 2026-10-22 (last formation 2026-08, exit 2026-09-30); no ADR-010 confirmation-sample prices.
  Statistics per factor: monthly Net of Q1..Q5 and ALL, Q5 - Q1, Q5 - ALL; month resampling (10,000 draws,
  seed 20261028), 95% interval; positive months.
  Periods per factor: its available formation months split into two consecutive halves; the first half is discovery,
  the second half (one more month when odd) is check.
  Keep rule (Q5 - Q1 and Q5 - ALL): same sign in both halves and the discovery interval excludes zero.
Known bias: delisted stocks are absent; past losers (Q1) look better than they were, which shrinks Q5 - Q1.
Run: python momentum.py   (reads the repository .env, never prints credentials)
"""
import random
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import LAST_EXIT, connect, iid_ci, mean, net, share  # noqa: E402

MIN_VALUE = 1_000_000_000
LAST_FORMATION = '2026-08'
SEED = 20261028
NEEDS = {'M6': 127, 'M12': 253, 'H52': 252}


def load():
    c = connect()
    allowed = {sid for sid, code, name in c.execute('select id, stock_code, stock_name from stock') if share(code, name)}
    bars = defaultdict(list)
    for sid, d, h, cl, tv in c.execute(
            """select stock_id, trade_date, high_price, close_price, trading_value from daily_adjusted_candle
                where open_price > 0 and close_price > 0 and trade_date <= %s order by stock_id, trade_date""",
            (LAST_EXIT,)):
        if sid in allowed:
            bars[sid].append((d, float(h), float(cl), float(tv or 0)))
    return bars


def factor_value(series, i, factor):
    if i + 1 < NEEDS[factor]:
        return None
    if factor == 'M6':
        return series[i - 21][2] / series[i - 126][2] - 1
    if factor == 'M12':
        return series[i - 21][2] / series[i - 252][2] - 1
    return series[i][2] / max(b[1] for b in series[i - 251:i + 1])


def main():
    bars = load()
    days = sorted({b[0] for series in bars.values() for b in series})
    month_end = {}
    for d in days:
        month_end[f'{d.year}-{d.month:02d}'] = d
    labels = sorted(month_end)
    index = {sid: {b[0]: i for i, b in enumerate(series)} for sid, series in bars.items()}
    rng = random.Random(SEED)
    for factor in ('M6', 'M12', 'H52'):
        rows = []
        for label, nxt in zip(labels, labels[1:]):
            if label > LAST_FORMATION:
                break
            f, exit_day = month_end[label], month_end[nxt]
            universe = []
            for sid, series in bars.items():
                i = index[sid].get(f)
                if i is None or i < 19 or mean([b[3] for b in series[i - 19:i + 1]]) < MIN_VALUE:
                    continue
                value = factor_value(series, i, factor)
                if value is None:
                    continue
                exit_close = None
                for b in series[i + 1:]:
                    if b[0] > exit_day:
                        break
                    exit_close = b[2]
                if exit_close is not None:
                    universe.append((value, net(series[i][2], exit_close)))
            if len(universe) < 100:
                continue
            universe.sort(key=lambda r: r[0])
            n = len(universe)
            q = {f'Q{k + 1}': mean([r[1] for r in universe[k * n // 5:(k + 1) * n // 5]]) for k in range(5)}
            rows.append((label, q, mean([r[1] for r in universe]), n))
        half = len(rows) // 2
        parts = {'discovery': rows[:half], 'check': rows[half:]}
        print(f'\n===== {factor}: formations {rows[0][0]}..{rows[-1][0]} ({len(rows)} months)')
        summary = {}
        for period, months in parts.items():
            print(f'-- {period} {months[0][0]}..{months[-1][0]}: months {len(months)}, universe '
                  f'{min(r[3] for r in months)}..{max(r[3] for r in months)}')
            line = []
            for qname in ('Q1', 'Q2', 'Q3', 'Q4', 'Q5'):
                line.append(f'{qname} {mean([r[1][qname] for r in months]):+.2f}%')
            line.append(f'ALL {mean([r[2] for r in months]):+.2f}%')
            print('   ' + ' | '.join(line))
            for label, series in (('Q5-Q1', [r[1]['Q5'] - r[1]['Q1'] for r in months]),
                                  ('Q5-ALL', [r[1]['Q5'] - r[2] for r in months])):
                summary[(period, label)] = iid_ci(series, rng)
                s = summary[(period, label)]
                print(f'   {label:6s} {s[0]:+.2f}%p [{s[1]:+.2f}, {s[2]:+.2f}] | positive months '
                      f'{sum(1 for v in series if v > 0)}/{len(series)}')
        print('   month-by-month Q5, Q1, ALL:')
        for label, q, a, n in rows:
            print(f'     {label} Q5 {q["Q5"]:+7.2f} Q1 {q["Q1"]:+7.2f} ALL {a:+7.2f} (n={n})')
        for label in ('Q5-Q1', 'Q5-ALL'):
            dsc, chk = summary[('discovery', label)], summary[('check', label)]
            keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
            print(f"   {'KEEP' if keep else 'drop'}  {factor} {label:6s} discovery {dsc[0]:+.2f} [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
                  f"check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")


if __name__ == '__main__':
    main()
