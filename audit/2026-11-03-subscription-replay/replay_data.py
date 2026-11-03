"""Replay data for the subscription-selection simulator (2026-11-03).

Builds, from the 1-year 5-minute research bars, a cumulative intraday snapshot for every (stock, day) at each 5-minute
bar end from 09:05 to 14:50 (70 points; a point at 09:05 + 5k includes bars starting before it). Special session days
(2025-11-13, 2026-01-02) are skipped. Values are raw (unadjusted) prices.
  per point: cumulative trading value, cumulative volume, last price, day high, day low, (up-bar value - down-bar value)
  per stock-day: first open, last bar close of the day (for the next day's previous close)
Cache: .cache/replay.npz (git-ignored). Reads the repository .env, never prints credentials.
Run: python replay_data.py
"""
import datetime as dt
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
from volatility import connect  # noqa: E402

FROM, TO = dt.date(2025, 10, 30), dt.date(2026, 10, 22)
SPECIAL = {dt.date(2025, 11, 13), dt.date(2026, 1, 2)}
POINTS = 70          # bar ends 09:05 .. 14:50
SLOTS = 78           # 09:00 .. 15:25 bar starts
CHUNK = 40
CACHE = HERE / '.cache'


def main():
    c = connect()
    ids = sorted(sid for (sid,) in c.execute('select distinct stock_id from intraday_research_state'))
    lo = dt.datetime.combine(FROM, dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    hi = dt.datetime.combine(TO + dt.timedelta(days=1), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    keys_sid, keys_day, snaps, first_open, last_close = [], [], [], [], []
    for k in range(0, len(ids), CHUNK):
        rows = c.execute(
            """select stock_id, (start_time at time zone 'Asia/Seoul') lt, open_price, high_price, low_price, close_price,
                      volume, trading_value
                 from intraday_research_bar where stock_id = any(%s) and start_time >= %s and start_time < %s""",
            (ids[k:k + CHUNK], lo, hi)).fetchall()
        if not rows:
            continue
        sid = np.array([r[0] for r in rows], dtype=np.int64)
        day = np.array([r[1].date().toordinal() for r in rows], dtype=np.int64)
        minute = np.array([r[1].hour * 60 + r[1].minute for r in rows], dtype=np.int64)
        o, h, l, cl = (np.array([float(r[j]) for r in rows]) for j in (2, 3, 4, 5))
        vol = np.array([float(r[6] or 0) for r in rows]); val = np.array([float(r[7] or 0) for r in rows])
        special = np.isin(day, [d.toordinal() for d in SPECIAL])
        slot = (minute - 9 * 60) // 5
        keep = ~special & (slot >= 0) & (slot < SLOTS)
        sid, day, slot, o, h, l, cl, vol, val = (a[keep] for a in (sid, day, slot, o, h, l, cl, vol, val))
        key = sid * 1_000_000 + day
        uniq, inv = np.unique(key, return_inverse=True)
        n = len(uniq)
        dense = {name: np.full((n, SLOTS), np.nan) for name in ('o', 'h', 'l', 'c', 'v', 'val')}
        for name, arr in (('o', o), ('h', h), ('l', l), ('c', cl), ('v', vol), ('val', val)):
            dense[name][inv, slot] = arr
        v0 = np.nan_to_num(dense['v']); val0 = np.nan_to_num(dense['val'])
        cum_val = np.cumsum(val0, axis=1); cum_vol = np.cumsum(v0, axis=1)
        last = dense['c'].copy()
        for s in range(1, SLOTS):                       # forward fill the last price
            miss = np.isnan(last[:, s]); last[miss, s] = last[miss, s - 1]
        run_hi = np.fmax.accumulate(dense['h'], axis=1); run_lo = np.fmin.accumulate(dense['l'], axis=1)
        sign = np.sign(np.nan_to_num(dense['c'] - dense['o']))
        cum_pressure = np.cumsum(val0 * sign, axis=1)
        # point p (0..69) = bar end 09:05 + 5p -> bars with slot <= p
        pts = np.arange(POINTS)
        snap = np.stack([cum_val[:, pts], cum_vol[:, pts], last[:, pts], run_hi[:, pts], run_lo[:, pts],
                         cum_pressure[:, pts]], axis=2).astype(np.float32)
        fo = np.array([row[~np.isnan(row)][0] if (~np.isnan(row)).any() else np.nan for row in dense['o']])
        lc = np.array([row[~np.isnan(row)][-1] if (~np.isnan(row)).any() else np.nan for row in dense['c']])
        keys_sid.append(uniq // 1_000_000); keys_day.append(uniq % 1_000_000)
        snaps.append(snap); first_open.append(fo.astype(np.float32)); last_close.append(lc.astype(np.float32))
        print(f'  {min(k + CHUNK, len(ids))}/{len(ids)} stocks, stock-days {sum(len(x) for x in keys_sid)}', flush=True)
    CACHE.mkdir(exist_ok=True)
    np.savez(CACHE / 'replay.npz', sid=np.concatenate(keys_sid), day=np.concatenate(keys_day),
             snap=np.concatenate(snaps), first_open=np.concatenate(first_open), last_close=np.concatenate(last_close))
    print('saved', CACHE / 'replay.npz')


if __name__ == '__main__':
    main()
