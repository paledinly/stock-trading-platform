"""Shared pieces of the subscription replay: cached snapshots, stock flags, previous close, 52-week high, and the
reconstructed KIS rankings and Broad score (same formulas as BroadCandidateCollector / MarketWideScannerService)."""
import datetime as dt
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
from volatility import connect  # noqa: E402

TYPES = ['TURNOVER', 'VOLUME', 'PRICE_RISE', 'TRADE_STRENGTH', 'HIGH_PROXIMITY']
WEIGHT = {'TURNOVER': 5, 'VOLUME': 3, 'PRICE_RISE': 3, 'TRADE_STRENGTH': 4, 'HIGH_PROXIMITY': 2}
STRUCTURED = ('인버스', '레버리지', '선물', 'ETN', 'ETF', '2X')
TOP = 30


def point_of(t):
    """Snapshot point for a wall-clock time: the last 5-minute bar end at or before t (09:05 -> 0)."""
    minutes = t.hour * 60 + t.minute - (9 * 60 + 5)
    return minutes // 5 if minutes >= 0 else None


def load(c):
    z = np.load(HERE / '.cache' / 'replay.npz')
    sid, day, snap = z['sid'], z['day'], z['snap']
    first_open, last_close = z['first_open'], z['last_close']
    meta = {}
    for s, code, name, market, etf, etn, managed, halted, active in c.execute(
            'select id, stock_code, stock_name, market, is_etf, is_etn, is_managed, is_trading_halted, is_active from stock'):
        upper = (name or '').upper()
        meta[s] = (code, name, str(market).upper(),
                   bool(active and not managed and not halted and not etf and not etn and not any(x in upper for x in STRUCTURED)))
    days = sorted(set(day.tolist()))
    prev_day = dict(zip(days[1:], days))
    index = {(int(a), int(b)): i for i, (a, b) in enumerate(zip(sid, day))}
    prev_close = np.full(len(sid), np.nan, dtype=np.float32)
    for i, (a, b) in enumerate(zip(sid, day)):
        j = index.get((int(a), prev_day.get(int(b), -1)))
        if j is not None:
            prev_close[i] = last_close[j]
    # 52-week high on the raw scale: adjusted max high of the previous 250 daily bars / (adj close d-1 / raw close d-1)
    high52 = np.full(len(sid), np.nan, dtype=np.float32)
    rows_by_sid = defaultdict(list)
    for i, (a, b) in enumerate(zip(sid, day)):
        rows_by_sid[int(a)].append((int(b), i))
    for s, items in rows_by_sid.items():
        bars = c.execute("""select trade_date, high_price, close_price from daily_adjusted_candle
                              where stock_id = %s and trade_date >= '2024-09-01' and trade_date <= '2026-10-22'
                              order by trade_date""", (s,)).fetchall()
        if not bars:
            continue
        dates = [b[0].toordinal() for b in bars]
        highs = np.array([float(b[1]) for b in bars]); closes = np.array([float(b[2]) for b in bars])
        pos = {d: k for k, d in enumerate(dates)}
        for d, i in items:
            k = pos.get(prev_day.get(d, -1))
            if k is None or np.isnan(prev_close[i]) or prev_close[i] <= 0:
                continue
            ratio = closes[k] / float(prev_close[i])
            high52[i] = highs[max(0, k - 249):k + 1].max() / ratio
    return {'sid': sid, 'day': day, 'snap': snap, 'first_open': first_open, 'prev_close': prev_close,
            'high52': high52, 'meta': meta, 'days': days}


def rank_values(data, rows, p):
    """Ranking keys for the given row indices at snapshot point p (higher = better rank)."""
    snap = data['snap'][rows, p, :]
    cum_val, cum_vol, last, hi, lo, pressure = (snap[:, k] for k in range(6))
    prev = data['prev_close'][rows]
    with np.errstate(divide='ignore', invalid='ignore'):
        values = {
            'TURNOVER': cum_val,
            'VOLUME': cum_vol,
            'PRICE_RISE': last / prev - 1,
            'TRADE_STRENGTH': np.where(cum_val > 0, pressure / cum_val, np.nan),   # proxy: up-bar minus down-bar value
            'HIGH_PROXIMITY': last / np.maximum(data['high52'][rows], hi),
        }
    return values, (cum_val, last, hi, lo, prev)


def rankings(data, rows, p, per_market=False):
    """{type: [row index, ...]} top-30 lists; per_market=True takes the top 30 of KOSPI and of KOSDAQ separately."""
    values, _ = rank_values(data, rows, p)
    markets = np.array([data['meta'].get(int(s), ('', '', '', False))[2] for s in data['sid'][rows]])
    out = {}
    for t in TYPES:
        v = values[t]
        ok = ~np.isnan(v) & (data['snap'][rows, p, 0] > 0)
        groups = [ok & (markets == 'KOSPI'), ok & (markets == 'KOSDAQ')] if per_market else [ok]
        picked = []
        for g in groups:
            idx = np.where(g)[0]
            order = idx[np.argsort(-v[idx], kind='stable')][:TOP]
            picked.extend((rows[i], r + 1) for r, i in enumerate(order))
        out[t] = picked
    return out


def broad_scores(data, rows, p, ranks, quote_ok):
    """Broad combined score per row index (0.65 quote + 0.35 ranking/17); rows without a complete quote get None."""
    raw = defaultdict(float)
    for t, picked in ranks.items():
        for i, r in picked:
            raw[i] += WEIGHT[t] * max(1, 101 - r)
    scores = {}
    for i, rs in raw.items():
        ranking = min(rs / 17, 100)
        if i not in quote_ok:
            scores[i] = None
            continue
        cum_val, cum_vol, last, hi, lo, pressure = (float(x) for x in data['snap'][i, p, :])
        prev = float(data['prev_close'][i])
        change = (last / prev - 1) * 100 if prev > 0 else 0.0
        momentum = min(max(change, 0) * 12, 45)
        liquidity = min(cum_val / 1e8, 35)
        position = min(max((last - lo) / (hi - lo) * 20, 0), 20) if hi > lo else 0.0
        scores[i] = 0.65 * min(momentum + liquidity + position, 100) + 0.35 * ranking
    return raw, scores
