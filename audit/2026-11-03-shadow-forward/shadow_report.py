"""Shadow Forward report (README.md): days recorded, daily top-5 Net, and the checkpoint statistic once 40 days have
outcomes. Reads records/picks.csv and records/outcomes.csv only. Run: python shadow_report.py
"""
import random
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
from volatility import block_ci  # noqa: E402

CHECKPOINT, EXTENSION, SEED = 40, 80, 20261103


def main():
    picks_path, out_path = HERE / 'records' / 'picks.csv', HERE / 'records' / 'outcomes.csv'
    if not picks_path.exists():
        print('no picks recorded yet')
        return
    picks = pd.read_csv(picks_path)
    out = pd.read_csv(out_path) if out_path.exists() else pd.DataFrame(columns=['signal_day', 'method', 'net_1515_pct'])
    print(f"recorded signal days: {picks['signal_day'].nunique()} ({picks['signal_day'].min()}..{picks['signal_day'].max()})")
    for method in ('PREMARKET', 'ALL'):
        o = out[out['method'] == method]
        if o.empty:
            print(f'{method}: no outcomes yet')
            continue
        daily = o.groupby('signal_day').agg(top5=('net_1515_pct', 'mean'), close=('net_close_pct', 'mean'),
                                            n=('net_1515_pct', 'size')).sort_index()
        days = len(daily)
        print(f'\n== {method}: {days} days with outcomes')
        print(daily.tail(10).round(3).to_string())
        print(f"mean top-5 Net {daily['top5'].mean():+.3f}% (closing-auction entry {daily['close'].mean():+.3f}%), "
              f"positive days {(daily['top5'] > 0).sum()}/{days}, compounded {((1 + daily['top5'] / 100).prod() - 1) * 100:+.2f}%")
        if method == 'PREMARKET' and days >= CHECKPOINT:
            series = list(daily['top5'].iloc[:EXTENSION if days >= EXTENSION else CHECKPOINT])
            ci = block_ci(series, 5, random.Random(SEED))
            half = len(series) // 2
            first, second = sum(series[:half]) / half, sum(series[half:]) / (len(series) - half)
            keep = ci[0] > 0 and ci[1] > 0 and first > 0 and second > 0
            print(f"CHECKPOINT ({len(series)} days): {ci[0]:+.3f} [{ci[1]:+.3f}, {ci[2]:+.3f}] halves {first:+.3f} / "
                  f"{second:+.3f} -> {'KEEP' if keep else 'STOP'}")
        elif method == 'PREMARKET':
            print(f'checkpoint at {CHECKPOINT} days ({CHECKPOINT - days} to go); no verdict before it')


if __name__ == '__main__':
    main()
