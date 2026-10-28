"""Post-hoc breakdown (added after seeing volatility.py part B). Descriptive only, no keep rule.

Where does the OPEN1 result of UP10 come from? Same data, filter, costs and periods as volatility.py part B.
Liquid stocks (trading value on d >= 1bn KRW) split by close-to-close return on d:
  [+3, +5), [+5, +10), [+10, +15), [+15, +20), [+20, +29.5), >= +29.5 (close at or near the +30% limit, often locked).
OPEN1 Net (close[d] -> open[d+1], BASE costs), day-level means, circular block resampling (block 5, 10,000 draws).
Also the share of >= +29.5% events among +10% events.
"""
import datetime as dt
import random
from collections import defaultdict

from volatility import DAY_PERIODS, MIN_VALUE, block_ci, load, mean, net

BUCKETS = [(0.03, 0.05), (0.05, 0.10), (0.10, 0.15), (0.15, 0.20), (0.20, 0.295), (0.295, 9.0)]


def main():
    bars = load()
    rng = random.Random(20261028)
    start = dt.date.fromisoformat(DAY_PERIODS['discovery'][0])
    end = dt.date.fromisoformat(DAY_PERIODS['check'][1])
    by_day = defaultdict(list)
    for series in bars.values():
        for i in range(1, len(series) - 1):
            d, _, cl, tv = series[i]
            if start <= d <= end and tv >= MIN_VALUE:
                by_day[d].append((cl / series[i - 1][2] - 1, net(cl, series[i + 1][1])))
    for period, (s0, e0) in DAY_PERIODS.items():
        s, e = dt.date.fromisoformat(s0), dt.date.fromisoformat(e0)
        days = [d for d in sorted(by_day) if s <= d <= e]
        print(f'\n-- {period} {s0}..{e0}')
        for lo, hi in BUCKETS:
            series = [mean([r[1] for r in by_day[d] if lo <= r[0] < hi]) for d in days
                      if any(lo <= r[0] < hi for r in by_day[d])]
            events = sum(1 for d in days for r in by_day[d] if lo <= r[0] < hi)
            m = block_ci(series, 5, rng)
            label = f'[{lo * 100:+.1f}, {hi * 100:+.1f})' if hi < 1 else f'>= {lo * 100:+.1f}'
            print(f'  {label:16s} events {events:6d} days {len(series):3d} | OPEN1 Net {m[0]:+.2f}% [{m[1]:+.2f}, {m[2]:+.2f}]')
        up10 = sum(1 for d in days for r in by_day[d] if r[0] >= 0.10)
        locked = sum(1 for d in days for r in by_day[d] if r[0] >= 0.295)
        print(f'  >= +29.5% share of +10% events: {locked}/{up10} ({locked / up10 * 100:.1f}%)')


if __name__ == '__main__':
    main()
