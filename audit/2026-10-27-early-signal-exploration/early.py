"""Exploratory pre-market signal scan on adjusted daily bars (2026-10-27). Hypothesis generation only.

Fixed before running (nothing else is tried):
  Base universe for day d: daily_adjusted_candle stocks whose trading value on d-1 >= 1,000,000,000 KRW and that
  have at least 60 bars before d. Every signal uses bars up to d-1 only (known before d's open).
    S1 volatility contraction: mean (H-L)/C over d-5..d-1 <= 0.5 x mean over d-20..d-1
    S2 volume dry-up:          mean volume over d-5..d-1 <= 0.5 x mean over d-60..d-1
    S3 near resistance:        close[d-1] >= 0.97 x max(high d-20..d-2) and close[d-1] < that max
    S4 S1 and S3
    S5 quiet accumulation:     return[d-1] in [0, 3)%, close location[d-1] > 0.8, volume[d-1] >= 1.5 x mean(d-20..d-1)
  Outcomes on d: P(ret >= 5%), P(ret >= 10%), P(strong candidate = trading-value rank top 100 on d and ret >= 3%),
  mean open-to-close of d (gross %). Overnight: among strong candidates of d, Net of close[d] -> open[d+1]
  (ADR-008 BASE costs), signal vs no signal.
  Unit: day. For each signal, daily rate (or mean) minus the base universe's on the same day, resampled over days
  (10,000 draws, seed 20261027), 95% interval. Periods: discovery 2026-03-18..07-31, check 2026-08-01..10-21.
  Keep rule (per outcome): same sign in both periods and the discovery interval excludes zero.
Run: python early.py   (reads the repository .env, never prints credentials)
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
DRAWS, SEED = 10_000, 20261027
SIGNALS = ('S1_contraction', 'S2_dryup', 'S3_near_high', 'S4_S1_and_S3', 'S5_quiet_accum')


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


def load():
    c = connect()
    series = defaultdict(list)
    for sid, d, o, h, l, cl, v, tv in c.execute(
            """select stock_id, trade_date, open_price, high_price, low_price, close_price, volume, trading_value
                 from daily_adjusted_candle where open_price > 0 and close_price > 0 and high_price > 0 and low_price > 0
                order by stock_id, trade_date"""):
        series[sid].append((str(d), float(o), float(h), float(l), float(cl), float(v or 0), float(tv or 0)))
    rows = []
    for bars in series.values():
        for i in range(60, len(bars) - 1):
            d, o, h, l, cl, v, tv = bars[i]
            if not (PERIODS['discovery'][0] <= d <= PERIODS['check'][1]):
                continue
            p = bars[i - 1]
            if p[6] < 1_000_000_000:
                continue
            prev = bars[i - 60:i]
            rng = [(b[2] - b[3]) / b[4] for b in prev]
            vol = [b[5] for b in prev]
            ret_prev = p[4] / bars[i - 2][4] - 1
            clv_prev = (p[4] - p[3]) / (p[2] - p[3]) if p[2] > p[3] else None
            high20 = max(b[2] for b in bars[i - 20:i - 1])
            s1 = mean(rng[-5:]) <= 0.5 * mean(rng[-20:])
            s3 = p[4] >= 0.97 * high20 and p[4] < high20
            sig = {'S1_contraction': s1,
                   'S2_dryup': mean(vol[-5:]) <= 0.5 * mean(vol) if mean(vol) > 0 else False,
                   'S3_near_high': s3, 'S4_S1_and_S3': s1 and s3,
                   'S5_quiet_accum': 0 <= ret_prev < 0.03 and clv_prev is not None and clv_prev > 0.8
                   and p[5] >= 1.5 * mean(vol[-20:])}
            nxt = bars[i + 1]
            ret = cl / p[4] - 1
            rows.append(dict(day=d, tv=tv, ret=ret, intraday=(cl / o - 1) * 100,
                             overnight=(nxt[1] * (1 - SELL) - cl * (1 + BUY)) / (cl * (1 + BUY)) * 100, sig=sig))
    # trading-value rank on d among the base universe
    by_day = defaultdict(list)
    for r in rows:
        by_day[r['day']].append(r)
    for day in by_day.values():
        for rank, r in enumerate(sorted(day, key=lambda r: -r['tv']), 1):
            r['strong'] = rank <= 100 and r['ret'] >= 0.03
    return by_day


def main():
    by_day = load()
    rng = random.Random(SEED)
    outcomes = {
        'P(ret>=5%)': lambda r: 100.0 if r['ret'] >= 0.05 else 0.0,
        'P(ret>=10%)': lambda r: 100.0 if r['ret'] >= 0.10 else 0.0,
        'P(strong cand)': lambda r: 100.0 if r['strong'] else 0.0,
        'open->close %': lambda r: r['intraday'],
    }
    summary = {}
    for period, (start, end) in PERIODS.items():
        days = [d for d in sorted(by_day) if start <= d <= end]
        print(f'\n===== {period} {start}..{end} days {len(days)} base rows {sum(len(by_day[d]) for d in days)}')
        base = {name: mean([mean([f(r) for r in by_day[d]]) for d in days]) for name, f in outcomes.items()}
        print('  base ' + ' | '.join(f'{k} {v:.2f}' for k, v in base.items()))
        for s in SIGNALS:
            parts = []
            for name, f in outcomes.items():
                diffs = []
                for d in days:
                    members = [r for r in by_day[d] if r['sig'][s]]
                    if members:
                        diffs.append(mean([f(r) for r in members]) - mean([f(r) for r in by_day[d]]))
                draws = sorted(mean([diffs[rng.randrange(len(diffs))] for _ in diffs]) for _ in range(DRAWS))
                lo, hi = draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]
                summary[(period, s, name)] = (mean(diffs), lo, hi)
                parts.append(f'{name} {mean(diffs):+.2f} [{lo:+.2f},{hi:+.2f}]')
            n = sum(1 for d in days for r in by_day[d] if r['sig'][s])
            print(f'  {s:16s} n={n:6d} | ' + ' | '.join(parts))
            # overnight among strong candidates
            on_sig, on_no = [], []
            for d in days:
                sig = [r['overnight'] for r in by_day[d] if r['strong'] and r['sig'][s]]
                no = [r['overnight'] for r in by_day[d] if r['strong'] and not r['sig'][s]]
                if sig and no:
                    on_sig.append(mean(sig))
                    on_no.append(mean(no))
            if len(on_sig) >= 5:
                diffs = [a - b for a, b in zip(on_sig, on_no)]
                draws = sorted(mean([diffs[rng.randrange(len(diffs))] for _ in diffs]) for _ in range(DRAWS))
                summary[(period, s, 'overnight strong')] = (mean(diffs), draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1])
                print(f'  {"":16s} strong candidates overnight Net: signal {mean(on_sig):+.2f}% vs no signal {mean(on_no):+.2f}% '
                      f'diff {mean(diffs):+.2f} [{draws[int(DRAWS * 0.025)]:+.2f},{draws[int(DRAWS * 0.975) - 1]:+.2f}] days {len(on_sig)}')
    print('\n===== keep rule')
    for s in SIGNALS:
        for name in list(outcomes) + ['overnight strong']:
            dsc, chk = summary.get(('discovery', s, name)), summary.get(('check', s, name))
            if not dsc or not chk:
                continue
            keep = (dsc[0] > 0) == (chk[0] > 0) and (dsc[1] > 0 or dsc[2] < 0)
            print(f"  {'KEEP' if keep else 'drop'}  {s:16s} {name:18s} discovery {dsc[0]:+.2f} [{dsc[1]:+.2f},{dsc[2]:+.2f}] "
                  f"check {chk[0]:+.2f} [{chk[1]:+.2f},{chk[2]:+.2f}]")


if __name__ == '__main__':
    main()
