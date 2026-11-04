"""Shadow Forward report (README.md, corrected 2026-11-03 before the first signal day): days recorded, daily top-5 Net,
and the checkpoint verdict once 40 (extension 80) days have outcomes. Reads records/picks.csv, records/outcomes.csv and
reference.json (the backtest value fixed for the divergence rule). Run: python shadow_report.py
"""
import json
import random
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
from volatility import block_ci  # noqa: E402

CHECKPOINT, EXTENSION, SEED = 40, 80, 20261103
MAX_MISSING = 0.20


def reference():
    path = HERE / 'reference.json'
    return json.loads(path.read_text(encoding='utf-8')) if path.exists() else None


def verdict(daily, missing_share, ref, final):
    """daily: per signal day, mean Net of the filled picks (BASE 'top5', HIGH 'high'); returns the printed lines."""
    series = list(daily['top5'])
    n = len(series)
    mean, lo, hi = block_ci(series, 5, random.Random(SEED))
    half = n // 2
    first, second = sum(series[:half]) / half, sum(series[half:]) / (n - half)
    high = daily['high'].mean()
    trimmed = daily['top5'].drop(daily['top5'].nlargest(3).index).mean()
    lines = [f'CHECKPOINT ({n} days): mean {mean:+.3f} [{lo:+.3f}, {hi:+.3f}], halves {first:+.3f} / {second:+.3f}, '
             f'HIGH cost {high:+.3f}, without the top 3 days {trimmed:+.3f}, missing picks {missing_share:.0%}']
    if ref is None:
        gap = 'NO REFERENCE'
    elif ref['value'] > hi:
        gap = 'OVERSTATED'
    elif ref['value'] < lo:
        gap = 'UNDERSTATED'
    else:
        gap = 'REPRODUCED'
    if ref is not None:
        lines.append(f"divergence: Forward {mean:+.3f} - backtest {ref['value']:+.3f} = {mean - ref['value']:+.3f}%p -> {gap}")
    if missing_share > MAX_MISSING:
        lines.append(f'-> NO VERDICT (missing > {MAX_MISSING:.0%}); judge at the next checkpoint')
        return lines
    if mean <= 0:
        result = 'STOP'
    elif lo > 0 and first > 0 and second > 0 and high > 0 and trimmed > 0 and gap in ('REPRODUCED', 'UNDERSTATED'):
        result = 'KEEP'
    else:
        result = 'STOP (still CONTINUE after the extension)' if final else 'CONTINUE (to the extension checkpoint)'
    lines.append(f'-> {result}')
    return lines


def main():
    picks_path, out_path = HERE / 'records' / 'picks.csv', HERE / 'records' / 'outcomes.csv'
    if not picks_path.exists():
        print('no picks recorded yet')
        return
    picks = pd.read_csv(picks_path)
    out = pd.read_csv(out_path) if out_path.exists() else pd.DataFrame(
        columns=['signal_day', 'method', 'code', 'net_close_pct', 'net_close_high_pct', 'net_1510_pct', 'exit_time'])
    print(f"recorded signal days: {picks['signal_day'].nunique()} ({picks['signal_day'].min()}..{picks['signal_day'].max()})")
    ref = reference()
    print(f"backtest reference: {ref['value']:+.3f}% ({ref['source']})" if ref else 'backtest reference: not fixed yet')
    for method in ('PREMARKET', 'ALL'):
        p = picks[picks['method'] == method]
        o = out[out['method'] == method]
        merged = p.merge(o, on=['signal_day', 'method', 'code'], how='left', suffixes=('', '_o'))
        days_with_exit = sorted(set(o['signal_day']))
        if not days_with_exit:
            print(f'{method}: no outcomes yet')
            continue
        judged = merged[merged['signal_day'].isin(days_with_exit)]
        missing = judged['net_close_pct'].isna()
        late = (o['exit_time'].astype(str) != '09:00').sum()
        daily = judged.groupby('signal_day').agg(top5=('net_close_pct', 'mean'), high=('net_close_high_pct', 'mean'),
                                                 at1510=('net_1510_pct', 'mean'), n=('net_close_pct', 'count')).sort_index()
        daily = daily.dropna(subset=['top5'])
        days = len(daily)
        print(f'\n== {method}: {days} days with outcomes, missing picks {int(missing.sum())}/{len(judged)}, '
              f'exits after the 09:00 bar {late}')
        print(daily.tail(10).round(3).to_string())
        print(f"mean top-5 Net (closing auction) {daily['top5'].mean():+.3f}%, HIGH {daily['high'].mean():+.3f}%, "
              f"15:10 bar entry {daily['at1510'].mean():+.3f}%, positive days {(daily['top5'] > 0).sum()}/{days}, "
              f"compounded {((1 + daily['top5'] / 100).prod() - 1) * 100:+.2f}%")
        if method != 'PREMARKET':
            continue
        if days >= CHECKPOINT:
            size = EXTENSION if days >= EXTENSION else CHECKPOINT
            window = daily.iloc[:size]
            share = float(judged[judged['signal_day'].isin(window.index)]['net_close_pct'].isna().mean())
            for line in verdict(window, share, ref, size == EXTENSION):
                print(line)
        else:
            print(f'checkpoint at {CHECKPOINT} days ({CHECKPOINT - days} to go); no verdict before it')


if __name__ == '__main__':
    main()
