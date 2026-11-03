"""Shadow Forward, nightly (README.md). Records the PREMARKET and ALL top-5 picks of each new signal day, then fills
the outcomes of earlier picks once the next day's 09:00 bar exists. Changes nothing in operations.

Definitions are those of ../2026-11-03-ml-feasibility-preregistration/feasibility.py (inputs from 5-minute bars up to
15:15 and adjusted daily bars up to d-1) and ../2026-11-03-subscription-replay/simulate.py (pre-market list); the
code is copied here so the nightly job does not depend on other folders.
Run: python shadow_daily.py   (scheduled 21:30 daily; reads the repository .env, never prints credentials)
"""
import datetime as dt
import json
import math
import statistics
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-10-28-volatility-exploration'))
from volatility import connect, share  # noqa: E402

FIRST_SIGNAL = dt.date(2026, 11, 4)
BUY, SELL = 0.00095, 0.00295
MIN_VALUE = 1_000_000_000
PREMARKET_SIZE, TOP = 300, 5
STRUCTURED = ('인버스', '레버리지', '선물', 'ETN', 'ETF', '2X')
# MARKET_SPECIAL_SESSIONS: date -> (open, close); every clock time shifts with the close.
SPECIAL = {dt.date(2026, 11, 19): (dt.time(10, 0), dt.time(16, 30))}
DART_FLAGS = {'D_BUYBACK': '자기주식취득', 'D_SUPPLY': '공급계약', 'D_EARN': '잠정)실적', 'D_RIGHTS': '유상증자결정',
              'D_CB': '전환사채권발행결정'}
RECORDS = HERE / 'records'
STATUS_URL = 'http://localhost:8080/api/v1/market-data/intraday-research/status'
SEOUL = dt.timezone(dt.timedelta(hours=9))


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def shift(day):
    s = SPECIAL.get(day)
    return 0 if s is None else (s[1].hour * 60 + s[1].minute) - (15 * 60 + 30)


def clock(day, hhmm):
    """Wall-clock time of a regular-session time on this day (shifted on special days)."""
    minutes = int(hhmm[:2]) * 60 + int(hhmm[3:]) + shift(day)
    return dt.time(minutes // 60, minutes % 60)


def session_open(day):
    return SPECIAL.get(day, (dt.time(9, 0), None))[0]


def collected_through():
    """Last trading day the intraday research collector has finished, or None while it runs / is unreachable."""
    try:
        with urllib.request.urlopen(STATUS_URL, timeout=10) as r:
            status = json.loads(r.read().decode('utf-8'))
    except Exception:
        return None
    if status.get('running') or not status.get('lastDate'):
        return None
    return dt.date.fromisoformat(status['lastDate'])


def intraday(c, ids, day):
    """Per stock on `day`: first bar start/open, 15:15 close, high/low/value/volume of bars up to 15:15, last bar
    start/close (wall clock, shifted on special days)."""
    lo = dt.datetime.combine(day, dt.time(0), SEOUL)
    t1515 = clock(day, '15:15')
    out = {}
    ids = sorted(ids)
    for k in range(0, len(ids), 300):
        for r in c.execute(
                """with b as (select stock_id, start_time, open_price, high_price, low_price, close_price, volume, trading_value,
                                     (start_time at time zone 'Asia/Seoul')::time t
                                from intraday_research_bar where stock_id = any(%s) and start_time >= %s and start_time < %s)
                   select stock_id, min(t), (array_agg(open_price order by start_time))[1],
                          max(close_price) filter (where t = %s), max(high_price) filter (where t <= %s),
                          min(low_price) filter (where t <= %s), sum(trading_value) filter (where t <= %s),
                          sum(volume) filter (where t <= %s), max(t), (array_agg(close_price order by start_time desc))[1]
                     from b group by stock_id""",
                (ids[k:k + 300], lo, lo + dt.timedelta(days=1), t1515, t1515, t1515, t1515, t1515)):
            out[r[0]] = r[1:]
    return out


def daily_arrays(c, ids, upto):
    rows = defaultdict(list)
    for s, d, o, h, l, cl, v, val in c.execute(
            """select stock_id, trade_date, open_price, high_price, low_price, close_price, volume, trading_value
                 from daily_adjusted_candle where stock_id = any(%s) and trade_date between %s and %s
                  and open_price > 0 and close_price > 0 order by stock_id, trade_date""",
            (sorted(ids), upto - dt.timedelta(days=420), upto)):
        rows[s].append((d, float(o), float(h), float(l), float(cl), float(v or 0), float(val or 0)))
    out = {}
    for s, bars in rows.items():
        out[s] = {'d': [b[0] for b in bars], 'o': np.array([b[1] for b in bars]), 'h': np.array([b[2] for b in bars]),
                  'l': np.array([b[3] for b in bars]), 'c': np.array([b[4] for b in bars]),
                  'v': np.array([b[5] for b in bars]), 'val': np.array([b[6] for b in bars])}
    return out


def clv(o, h, l, c):
    return ((c - l) - (h - c)) / (h - l) if h > l else 0.0


def features(a, i, O, H, L, P, V, VAL):
    """Study-B inputs at d with today's values from bars up to 15:15; i = position of d-1 (copied from feasibility.py)."""
    c, h, l, v, val, o = a['c'], a['h'], a['l'], a['v'], a['val'], a['o']
    prev = c[i]
    rng = H - L
    f = {'RET1': P / prev - 1, 'LOC': (P - L) / rng if rng > 0 else 0.5, 'GAP': O / prev - 1, 'RANGE': rng / prev,
         'UPPER': (H - max(O, P)) / rng if rng > 0 else 0.0, 'LOGVAL': math.log(max(VAL, 1))}
    f['VRATIO'] = VAL / val[i - 19:i + 1].mean()
    f['R5'], f['R20'] = P / c[i - 4] - 1, P / c[i - 19] - 1
    f['R60'] = P / c[i - 59] - 1 if i >= 59 else np.nan
    rets = [c[j] / c[j - 1] - 1 for j in range(i - 18, i + 1)] + [f['RET1']]
    f['VOL20'] = statistics.stdev(rets)
    f['MA20GAP'] = P / ((c[i - 18:i + 1].sum() + P) / 20) - 1
    f['MA60GAP'] = P / ((c[i - 58:i + 1].sum() + P) / 60) - 1 if i >= 58 else np.nan
    f['HIGH52'] = P / max(h[max(0, i - 248):i + 1].max(), H) if i + 1 >= 59 else np.nan
    hi20, lo20 = max(h[i - 18:i + 1].max(), H), min(l[i - 18:i + 1].min(), L)
    f['FLAT'] = float(abs(f['R20']) <= 0.05 and lo20 > 0 and hi20 / lo20 - 1 <= 0.20)
    num = sum(clv(o[j], h[j], l[j], c[j]) * v[j] for j in range(i - 18, i + 1)) + clv(O, H, L, P) * V
    den = v[i - 18:i + 1].sum() + V
    f['CMF20'] = num / den if den > 0 else np.nan
    before = val[i - 78:i - 18].mean() if i >= 78 else np.nan
    f['VOLEXP'] = ((val[i - 18:i + 1].sum() + VAL) / 20) / before if before and before > 0 else np.nan
    wicks = [(min(o[j], c[j]) - l[j]) / (h[j] - l[j]) for j in range(i - 18, i + 1) if h[j] > l[j]]
    if rng > 0:
        wicks.append((min(O, P) - L) / rng)
    f['WICK20'] = sum(wicks) / len(wicks) if len(wicks) >= 15 else np.nan
    f['AVGVAL20'] = (val[i - 18:i + 1].sum() + VAL) / 20
    return f


def dart_flags(c, codes, day):
    flags = defaultdict(lambda: [0] * (len(DART_FLAGS) + 1))
    for code, title in c.execute("select stock_code, report_nm from dart_filing where rcept_dt between %s and %s",
                                 (day - dt.timedelta(days=7), day - dt.timedelta(days=1))):
        title = (title or '').strip()
        sid = codes.get(code)
        if sid is None or title.startswith('[') or '자회사' in title:
            continue
        row = flags[sid]
        for k, (name, key) in enumerate(DART_FLAGS.items()):
            if key in title and not (name == 'D_BUYBACK' and '처분' in title):
                row[k] = 1
        row[-1] += 1
    return flags


def premarket(a, i):
    """(up-gap share of the 20 days up to d-1, 20-day mean trading value) from adjusted bars; i = position of d-1."""
    if i < 20:
        return None
    gaps = [a['o'][j] / a['c'][j - 1] - 1 > 0 for j in range(i - 19, i + 1)]
    return sum(gaps) / 20, a['val'][i - 19:i + 1].mean()


def record_day(c, day, prev_day, model, cols, meta, codes):
    ids = [s for s, m in meta.items() if m[2]]
    today, before = intraday(c, ids, day), intraday(c, ids, prev_day)
    arrays = daily_arrays(c, list(today), prev_day)
    rows, pm = [], []
    for sid, r in today.items():
        first_t, O, P, H, L, VAL, V, last_t, last_close = r
        a = arrays.get(sid)
        if a is None or not a['d'] or a['d'][-1] != prev_day or None in (O, P, H, L, VAL, V):
            continue
        i = len(a['d']) - 1
        p = premarket(a, i)
        if p and p[1] >= MIN_VALUE and meta[sid][3]:
            pm.append((p[0], -sid, sid))
        b = before.get(sid)
        if i < 78 or not b or not b[8]:
            continue
        k = a['c'][i] / float(b[8])
        Oa, Ha, La, Pa = (float(x) * k for x in (O, H, L, P))
        f = features(a, i, Oa, Ha, La, Pa, float(V) / k, float(VAL))
        if f['AVGVAL20'] < MIN_VALUE or f['RET1'] >= 0.29:
            continue
        auction = last_close and str(last_t) == clock(day, '15:25').strftime('%H:%M:%S')
        f.update(sid=sid, entry_1515=float(P), entry_close=float(last_close) if auction else None)
        rows.append(f)
    x = pd.DataFrame(rows)
    x['MKT_RET'] = x['RET1'].mean()
    x['MKT_UP'] = (x['RET1'] > 0).mean()
    x['RANK_RET'] = x['RET1'].rank(pct=True)
    x['RANK_VAL'] = x['LOGVAL'].rank(pct=True)
    flags = dart_flags(c, codes, day)
    for k, name in enumerate(list(DART_FLAGS) + ['D_COUNT']):
        x[name] = [flags[s][k] if s in flags else 0 for s in x['sid']]
    x['pred'] = model.predict(x[cols].astype('float32').to_numpy())
    listed = {sid for _, _, sid in sorted(pm, reverse=True)[:PREMARKET_SIZE]}
    picks = []
    for method, frame in (('PREMARKET', x[x['sid'].isin(listed)]), ('ALL', x)):
        top = frame.sort_values(['pred', 'sid'], ascending=[False, True]).head(TOP)
        for rank, r in enumerate(top.itertuples(), 1):
            picks.append({'signal_day': day.isoformat(), 'method': method, 'rank': rank, 'code': meta[r.sid][0],
                          'name': meta[r.sid][1], 'pred': round(float(r.pred), 5), 'ret_1515_pct': round(r.RET1 * 100, 2),
                          'entry_1515': r.entry_1515, 'entry_close': r.entry_close,
                          'universe': len(x), 'premarket_listed': len(listed),
                          'recorded_at': dt.datetime.now(SEOUL).isoformat(timespec='seconds')})
    return picks


def fill_outcomes(c, picks, outcomes, days):
    done = {(o['signal_day'], o['method'], o['code']) for o in outcomes}
    code_to_sid = {code: s for s, code in c.execute('select id, stock_code from stock')}
    nxt = dict(zip(days, days[1:]))
    new = []
    for p in picks:
        key = (p['signal_day'], p['method'], p['code'])
        d = dt.date.fromisoformat(p['signal_day'])
        if key in done or d not in nxt:
            continue
        d1 = nxt[d]
        lo = dt.datetime.combine(d1, session_open(d1), SEOUL)
        row = c.execute("""select open_price from intraday_research_bar where stock_id = %s and start_time = %s""",
                        (code_to_sid[p['code']], lo)).fetchone()
        if not row:
            continue
        exit_open = float(row[0])
        e1 = float(p['entry_1515'])
        ec = float(p['entry_close']) if p['entry_close'] not in (None, '', 'None') else None
        new.append({'signal_day': p['signal_day'], 'method': p['method'], 'rank': p['rank'], 'code': p['code'],
                    'name': p['name'], 'sell_day': d1.isoformat(), 'exit_open': exit_open,
                    'net_1515_pct': round(net(e1, exit_open), 4),
                    'net_close_pct': round(net(ec, exit_open), 4) if ec else None,
                    'filled_at': dt.datetime.now(SEOUL).isoformat(timespec='seconds')})
    return new


def read(path):
    return pd.read_csv(path, dtype=str).to_dict('records') if path.exists() else []


def append(path, rows):
    if rows:
        pd.DataFrame(rows).to_csv(path, mode='a', header=not path.exists(), index=False, encoding='utf-8')


def main():
    RECORDS.mkdir(exist_ok=True)
    log = open(RECORDS / 'run.log', 'a', encoding='utf-8')
    stamp = dt.datetime.now(SEOUL).isoformat(timespec='seconds')
    c = connect()
    through = collected_through()
    days = sorted({d for (d,) in c.execute(
        "select trade_date from intraday_research_state where trade_date >= %s group by trade_date having count(*) >= 500",
        (FIRST_SIGNAL - dt.timedelta(days=10),))})
    meta = {}
    for s, code, name, active, managed, halted, etf, etn in c.execute(
            'select id, stock_code, stock_name, is_active, is_managed, is_trading_halted, is_etf, is_etn from stock'):
        upper = (name or '').upper()
        meta[s] = (code, name, share(code, name),
                   bool(active and not managed and not halted and not etf and not etn and not any(x in upper for x in STRUCTURED)))
    codes = {m[0]: s for s, m in meta.items()}
    picks = read(RECORDS / 'picks.csv')
    recorded = {p['signal_day'] for p in picks}
    booster = lgb.Booster(model_file=str(HERE / 'model.txt'))
    cols = json.loads((HERE / 'features.json').read_text(encoding='utf-8'))
    added = []
    if through is None:
        log.write(f'{stamp} collector running or unreachable; picks skipped\n')
    else:
        for prev_day, day in zip(days, days[1:]):
            if day < FIRST_SIGNAL or day > through or day.isoformat() in recorded:
                continue
            day_picks = record_day(c, day, prev_day, booster, cols, meta, codes)
            append(RECORDS / 'picks.csv', day_picks)
            added.extend(day_picks)
            log.write(f'{stamp} recorded {day}: {len(day_picks)} picks\n')
    picks = read(RECORDS / 'picks.csv')
    outcomes = read(RECORDS / 'outcomes.csv')
    new = fill_outcomes(c, picks, outcomes, days)
    append(RECORDS / 'outcomes.csv', new)
    log.write(f'{stamp} outcomes filled: {len(new)}\n')
    log.close()
    print(f'picks added {len(added)}, outcomes filled {len(new)}')


if __name__ == '__main__':
    main()
