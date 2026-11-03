"""Machine-learning ranking of weak signals, pre-registered study (README.md in this folder, 2026-11-03).

Carries the README definitions over unchanged; committed together with the README before the first run.
Implementation details fixed here before running:
  Trading days = dates with >= 500 stocks in daily_adjusted_candle. "d-1" for study A = the previous trading day; the
    daily features of study A are the study-B daily features computed at that day.
  Daily features use the stock's own previous rows (rolling windows over its bars). 52-week high uses up to 250 bars
    (at least 60). Volatility = standard deviation of the last 20 daily returns.
  Intraday aggregates use the session clock (special days are excluded anyway, see README).
  Market features are computed over the day's rows of the study's universe (before the outlier filter).
  Rows whose outcome breaks the +/-35% outlier rule are removed from training and from evaluation (counted).
  Top-5 ties are broken by stock id. Days with fewer than 20 rows in the universe are skipped in evaluation.
  Datasets are cached under the scratch directory given by ML_CACHE (default: this folder's .cache, git-ignored);
    the cache holds only features and outcomes.
Run: python ml_ranking.py [--build-only]   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import math
import os
import pickle
import random
import sys
from collections import defaultdict
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / '2026-10-28-volatility-exploration'))
from volatility import block_ci, connect, share  # noqa: E402

BUY, SELL = 0.00095, 0.00295
SEED = 20261103
LAST_EXIT = dt.date(2026, 10, 22)
DAILY_FROM = dt.date(2018, 4, 1)
INTRADAY_FROM = dt.date(2025, 10, 30)
SPECIAL = {dt.date(2025, 11, 13), dt.date(2026, 1, 2)}
MIN_VALUE = 1_000_000_000
OUTLIER = 0.35
TOP = 5
CACHE = Path(os.environ.get('ML_CACHE', Path(__file__).resolve().parent / '.cache'))
PARAMS = dict(n_estimators=400, learning_rate=0.03, num_leaves=15, min_child_samples=300, subsample=0.8,
              subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0, random_state=SEED, n_jobs=8, verbose=-1)
A_MONTHS = ['2026-03', '2026-04', '2026-05', '2026-06', '2026-07', '2026-08', '2026-09', '2026-10']
B_YEARS = [2020, 2021, 2022, 2023, 2024, 2025, 2026]
DART_FLAGS = {'D_BUYBACK': '자기주식취득', 'D_SUPPLY': '공급계약', 'D_EARN': '잠정)실적', 'D_RIGHTS': '유상증자결정',
              'D_CB': '전환사채권발행결정'}


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


# ---------------------------------------------------------------- data

def load_daily(c):
    names = {sid: (code, name) for sid, code, name in c.execute('select id, stock_code, stock_name from stock')}
    allowed = {sid for sid, (code, name) in names.items() if share(code, name)}
    cols = defaultdict(list)
    with c.cursor(name='daily') as cur:
        cur.itersize = 100_000
        cur.execute("""select stock_id, trade_date, open_price, high_price, low_price, close_price, volume, trading_value
                         from daily_adjusted_candle where open_price > 0 and close_price > 0 and trade_date <= %s
                        order by stock_id, trade_date""", (LAST_EXIT,))
        for sid, d, o, h, l, cl, v, tv in cur:
            if sid in allowed:
                cols['sid'].append(sid); cols['d'].append(d); cols['o'].append(float(o)); cols['h'].append(float(h))
                cols['l'].append(float(l)); cols['c'].append(float(cl)); cols['v'].append(float(v or 0))
                cols['val'].append(float(tv or 0))
    df = pd.DataFrame(cols)
    del cols
    trading = sorted(d for d, n in df.groupby('d').size().items() if n >= 500)
    df = df[df['d'].isin(set(trading))].reset_index(drop=True)
    codes = {code: sid for sid, (code, name) in names.items() if sid in allowed}
    return df, trading, codes


def daily_features(df, trading):
    g = df.groupby('sid', sort=False)
    out = pd.DataFrame({'sid': df['sid'], 'd': df['d']})
    prev_c = g['c'].shift(1)
    rng = df['h'] - df['l']
    out['RET1'] = df['c'] / prev_c - 1
    out['LOC'] = np.where(rng > 0, (df['c'] - df['l']) / rng.where(rng > 0, 1), 0.5)
    out['GAP'] = df['o'] / prev_c - 1
    out['RANGE'] = rng / prev_c
    out['UPPER'] = np.where(rng > 0, (df['h'] - df[['o', 'c']].max(axis=1)) / rng.where(rng > 0, 1), 0.0)
    out['LOGVAL'] = np.log(df['val'].clip(lower=1))
    val_prev20 = g['val'].transform(lambda s: s.shift(1).rolling(20).mean())
    out['VRATIO'] = df['val'] / val_prev20
    for k in (5, 20, 60):
        out[f'R{k}'] = df['c'] / g['c'].shift(k) - 1
    out['VOL20'] = out.groupby('sid', sort=False)['RET1'].transform(lambda s: s.rolling(20).std())
    out['MA20GAP'] = df['c'] / g['c'].transform(lambda s: s.rolling(20).mean()) - 1
    out['MA60GAP'] = df['c'] / g['c'].transform(lambda s: s.rolling(60).mean()) - 1
    out['HIGH52'] = df['c'] / g['h'].transform(lambda s: s.rolling(250, min_periods=60).max())
    hi20 = g['h'].transform(lambda s: s.rolling(20).max())
    lo20 = g['l'].transform(lambda s: s.rolling(20).min())
    out['FLAT'] = ((out['R20'].abs() <= 0.05) & (lo20 > 0) & (hi20 / lo20 - 1 <= 0.20)).astype('float32')
    clv = np.where(rng > 0, ((df['c'] - df['l']) - (df['h'] - df['c'])) / rng.where(rng > 0, 1), 0.0)
    tmp = pd.DataFrame({'sid': df['sid'], 'cv': clv * df['v'], 'v': df['v']})
    gt = tmp.groupby('sid', sort=False)
    out['CMF20'] = gt['cv'].transform(lambda s: s.rolling(20).sum()) / gt['v'].transform(lambda s: s.rolling(20).sum())
    out['VOLEXP'] = g['val'].transform(lambda s: s.rolling(20).mean() / s.shift(20).rolling(60).mean())
    wick = pd.Series(np.where(rng > 0, (df[['o', 'c']].min(axis=1) - df['l']) / rng.where(rng > 0, 1), np.nan))
    out['WICK20'] = wick.groupby(df['sid']).transform(lambda s: s.rolling(20, min_periods=15).mean())
    out['AVGVAL20'] = g['val'].transform(lambda s: s.rolling(20).mean())
    out['NBARS'] = g.cumcount() + 1
    out['c'] = df['c']
    nxt = dict(zip(trading, trading[1:]))
    out['next_d'] = g['d'].shift(-1)
    out['next_o'] = g['o'].shift(-1)
    out['cal_next'] = df['d'].map(nxt)
    return out


def dart_index(c, codes):
    by = defaultdict(lambda: defaultdict(lambda: [0] * (len(DART_FLAGS) + 1)))
    for code, title, d in c.execute(
            "select stock_code, report_nm, rcept_dt from dart_filing where rcept_dt >= '2017-12-01'"):
        title = (title or '').strip()
        sid = codes.get(code)
        if sid is None or title.startswith('[') or '자회사' in title:
            continue
        row = by[sid][d]
        for k, (name, key) in enumerate(DART_FLAGS.items()):
            if key in title and not (name == 'D_BUYBACK' and '처분' in title):
                row[k] = 1
        row[-1] += 1
    return by


def add_dart(frame, by):
    flags = np.zeros((len(frame), len(DART_FLAGS) + 1), dtype='float32')
    for idx, (sid, d) in enumerate(zip(frame['sid'].to_numpy(), frame['d'].to_numpy())):
        days = by.get(sid)
        if not days:
            continue
        for back in range(1, 8):
            row = days.get(d - dt.timedelta(days=back))
            if row:
                flags[idx, :-1] = np.maximum(flags[idx, :-1], row[:-1])
                flags[idx, -1] += row[-1]
    for k, name in enumerate(list(DART_FLAGS) + ['D_COUNT']):
        frame[name] = flags[:, k]
    return frame


DAILY_COLS = ['R5', 'R20', 'R60', 'VOL20', 'MA20GAP', 'MA60GAP', 'HIGH52', 'FLAT', 'CMF20', 'VOLEXP', 'WICK20']


def build_b(feat, by):
    f = feat[(feat['d'] >= DAILY_FROM) & (feat['NBARS'] >= 80) & (feat['AVGVAL20'] >= MIN_VALUE)
             & (feat['RET1'] < 0.29) & (feat['next_d'] == feat['cal_next']) & feat['next_o'].notna()].copy()
    f['y'] = net(f['c'], f['next_o'])
    f['MKT_RET'] = f.groupby('d')['RET1'].transform('mean')
    f['MKT_UP'] = f.groupby('d')['RET1'].transform(lambda s: (s > 0).mean())
    f['RANK_RET'] = f.groupby('d')['RET1'].rank(pct=True)
    f['RANK_VAL'] = f.groupby('d')['LOGVAL'].rank(pct=True)
    f = add_dart(f.reset_index(drop=True), by)
    f['RULE'] = ((f['RET1'] >= 0.03) & (f['LOC'] <= 0.5)).astype('int8')
    f['outlier'] = (f['next_o'] / f['c'] - 1).abs() > OUTLIER
    cols = ['RET1', 'LOC', 'GAP', 'RANGE', 'UPPER', 'LOGVAL', 'VRATIO'] + DAILY_COLS + \
           ['MKT_RET', 'MKT_UP', 'RANK_RET', 'RANK_VAL'] + list(DART_FLAGS) + ['D_COUNT']
    return f[['sid', 'd', 'y', 'RULE', 'outlier'] + cols], cols


def load_intraday(c, ids):
    lo = dt.datetime.combine(INTRADAY_FROM, dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    hi = dt.datetime.combine(LAST_EXIT + dt.timedelta(days=1), dt.time(0), dt.timezone(dt.timedelta(hours=9)))
    rows = []
    ids = sorted(ids)
    for k in range(0, len(ids), 150):
        rows.extend(c.execute(
            """with b as (select stock_id, start_time, open_price, high_price, low_price, close_price, volume, trading_value,
                                 (start_time at time zone 'Asia/Seoul') lt
                            from intraday_research_bar where stock_id = any(%s) and start_time >= %s and start_time < %s)
               select stock_id, lt::date, min(lt::time), (array_agg(open_price order by start_time))[1],
                      max(close_price) filter (where lt::time = '15:10'), max(close_price) filter (where lt::time = '15:15'),
                      max(close_price) filter (where lt::time = '14:55'), max(close_price) filter (where lt::time = '14:25'),
                      max(close_price) filter (where lt::time = '15:25'),
                      max(high_price) filter (where lt::time <= '15:10'), min(low_price) filter (where lt::time <= '15:10'),
                      sum(trading_value) filter (where lt::time <= '15:10'), sum(volume) filter (where lt::time <= '15:10'),
                      sum(trading_value) filter (where lt::time >= '14:30' and lt::time <= '15:10')
                 from b group by stock_id, lt::date""", (ids[k:k + 150], lo, hi)).fetchall())
        print(f'  intraday {min(k + 150, len(ids))}/{len(ids)} stocks', flush=True)
    cols = ['sid', 'd', 'first_t', 'first_o', 'p0', 'entry', 'c1455', 'c1425', 'close', 'hi', 'lo', 'value', 'volume',
            'late']
    x = pd.DataFrame(rows, columns=cols)
    for col in cols[3:]:
        x[col] = x[col].astype('float64')
    return x


def build_a(intra, feat, trading, by):
    days = sorted(x for x in intra['d'].unique())
    prv, nxt = dict(zip(days[1:], days)), dict(zip(days, days[1:]))
    x = intra.copy()
    key = x.set_index(['sid', 'd'])
    x['prev_d'] = x['d'].map(prv)
    x['next_d'] = x['d'].map(nxt)
    x['prev_close'] = key['close'].reindex(list(zip(x['sid'], x['prev_d']))).to_numpy()
    nk = key[['first_t', 'first_o']].reindex(list(zip(x['sid'], x['next_d'])))
    x['next_t'] = nk['first_t'].to_numpy()
    x['next_o'] = nk['first_o'].to_numpy()
    x = x[(x['d'] >= dt.date(2025, 10, 31)) & ~x['d'].isin(SPECIAL) & ~x['next_d'].isin(SPECIAL)
          & x['next_d'].notna() & x['p0'].notna() & x['entry'].notna() & (x['value'] >= MIN_VALUE)
          & x['prev_close'].notna() & (x['p0'] < x['prev_close'] * 1.295)
          & (x['next_t'].astype(str) == '09:00:00') & x['next_o'].notna()].copy()
    x['F1'] = x['p0'] / x['prev_close'] - 1
    x['F2'] = x['p0'] / x['first_o'] - 1
    x['F3'] = x['p0'] / x['c1455'] - 1
    x['F4'] = x['p0'] / x['c1425'] - 1
    x['F5'] = np.where(x['volume'] > 0, x['p0'] / (x['value'] / x['volume'].where(x['volume'] > 0, 1)) - 1, np.nan)
    rng = x['hi'] - x['lo']
    x['F6'] = np.where(rng > 0, (x['p0'] - x['lo']) / rng.where(rng > 0, 1), np.nan)
    x['F7'] = x['p0'] / x['hi'] - 1
    x['F8'] = x['late'].fillna(0) / x['value']
    x['GAP'] = x['first_o'] / x['prev_close'] - 1
    x['RANGE'] = rng / x['prev_close']
    x['LOGVAL'] = np.log(x['value'])
    # daily features at d-1 (the previous trading day)
    tpos = {d: k for k, d in enumerate(trading)}
    x['dprev'] = [trading[tpos[d] - 1] if d in tpos and tpos[d] > 0 else None for d in x['d']]
    daily = feat.set_index(['sid', 'd'])[DAILY_COLS + ['AVGVAL20']]
    dv = daily.reindex(list(zip(x['sid'], x['dprev'])))
    for col in DAILY_COLS:
        x[col] = dv[col].to_numpy()
    x['VRATIO'] = x['value'] / dv['AVGVAL20'].to_numpy()
    x['MKT_F1'] = x.groupby('d')['F1'].transform('mean')
    x['MKT_UP'] = x.groupby('d')['F1'].transform(lambda s: (s > 0).mean())
    x['MKT_F3'] = x.groupby('d')['F3'].transform('mean')
    x['RANK_F1'] = x.groupby('d')['F1'].rank(pct=True)
    x['RANK_VAL'] = x.groupby('d')['LOGVAL'].rank(pct=True)
    x = add_dart(x.reset_index(drop=True), by)
    x['y'] = net(x['entry'], x['next_o'])
    x['outlier'] = (x['next_o'] / x['entry'] - 1).abs() > OUTLIER
    x['RULE'] = ((x['F1'] >= 0.03) & (x['p0'] < x['hi'] * 0.97)).astype('int8')
    cols = ['F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'GAP', 'RANGE', 'LOGVAL', 'VRATIO'] + DAILY_COLS + \
           ['MKT_F1', 'MKT_UP', 'MKT_F3', 'RANK_F1', 'RANK_VAL'] + list(DART_FLAGS) + ['D_COUNT']
    return x[['sid', 'd', 'y', 'RULE', 'outlier'] + cols], cols


def build():
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / 'datasets.pkl'
    if path.exists():
        with open(path, 'rb') as fh:
            return pickle.load(fh)
    c = connect()
    df, trading, codes = load_daily(c)
    print(f'daily rows {len(df)}, trading days {len(trading)}', flush=True)
    feat = daily_features(df, trading)
    del df
    by = dart_index(c, codes)
    b, b_cols = build_b(feat, by)
    print(f'study B rows {len(b)}', flush=True)
    intra = load_intraday(c, set(feat['sid'].unique()))
    a, a_cols = build_a(intra, feat, trading, by)
    print(f'study A rows {len(a)}', flush=True)
    data = {'A': (a, a_cols), 'B': (b, b_cols), 'trading': trading}
    with open(path, 'wb') as fh:
        pickle.dump(data, fh)
    return data


# ---------------------------------------------------------------- walk-forward

def fit_predict(train, test, cols):
    target = (train['y'] - train.groupby('d')['y'].transform('mean')).clip(-10, 10)
    model = lgb.LGBMRegressor(**PARAMS)
    model.fit(train[cols].astype('float32'), target)
    return model.predict(test[cols].astype('float32')), model


def walk(frame, cols, periods, trading):
    tpos = {d: k for k, d in enumerate(trading)}
    clean = frame[~frame['outlier']]
    preds, importance = [], defaultdict(float)
    for label, lo, hi in periods:
        test = clean[(clean['d'] >= lo) & (clean['d'] <= hi)]
        if test.empty:
            continue
        embargo = trading[tpos[min(test['d'])] - 1]
        train = clean[clean['d'] < embargo]
        p, model = fit_predict(train, test, cols)
        part = test[['sid', 'd', 'y', 'RULE']].copy()
        part['pred'] = p
        part['period'] = label
        preds.append(part)
        for name, gain in zip(cols, model.booster_.feature_importance('gain')):
            importance[name] += gain
        print(f'  {label}: train {len(train)} rows (to {embargo}), test {len(test)} rows', flush=True)
    return pd.concat(preds), importance


def daily_table(pred):
    rows = []
    for d, g in pred.groupby('d'):
        if len(g) < 20:
            continue
        g = g.sort_values(['pred', 'sid'], ascending=[False, True])
        n = len(g)
        rule = g[g['RULE'] == 1]['y']
        ic = g['pred'].rank().corr(g['y'].rank())
        rows.append({'d': d, 'period': g['period'].iloc[0], 'top5': g['y'].iloc[:TOP].mean(),
                     'top10': g['y'].iloc[:10].mean(), 'topdec': g['y'].iloc[:max(1, n // 10)].mean(),
                     'all': g['y'].mean(), 'rule': rule.mean() if len(rule) else np.nan, 'ic': ic, 'n': n})
    return pd.DataFrame(rows).sort_values('d')


def ci(series):
    values = [float(v) for v in series if not (isinstance(v, float) and math.isnan(v))]
    return block_ci(values, 5, random.Random(SEED))


def report(name, table, importance, halves):
    print(f'\n===== {name}')
    print(f"evaluation days {len(table)} ({table['d'].min()}..{table['d'].max()}), universe/day {table['n'].mean():.0f}")
    t5 = ci(table['top5'])
    print(f'TOP5 Net {t5[0]:+.3f} [{t5[1]:+.3f}, {t5[2]:+.3f}] %   (primary)')
    ex = ci(table['top5'] - table['all'])
    print(f'TOP5 - ALL {ex[0]:+.3f} [{ex[1]:+.3f}, {ex[2]:+.3f}] %p')
    for col in ('top10', 'topdec', 'all', 'rule'):
        print(f'{col:7s} Net {table[col].mean():+.3f} %')
    print(f"IC (daily Spearman) mean {table['ic'].mean():+.4f}, positive days {(table['ic'] > 0).mean() * 100:.0f}%")
    print('by period (TOP5 / ALL / RULE Net %):')
    by_period = table.groupby('period')
    for label, g in by_period:
        print(f"   {label}: {g['top5'].mean():+.3f} / {g['all'].mean():+.3f} / {g['rule'].mean():+.3f}   days {len(g)}")
    print('top 15 features by gain:')
    total = sum(importance.values()) or 1
    for k, v in sorted(importance.items(), key=lambda kv: -kv[1])[:15]:
        print(f'   {k:12s} {v / total * 100:5.1f}%')
    return t5, by_period


def main():
    data = build()
    if '--build-only' in sys.argv:
        print('built (no model run)')
        return
    trading = data['trading']
    a, a_cols = data['A']
    print(f"A rows {len(a)}, outliers {int(a['outlier'].sum())}; B rows {len(data['B'][0])}, "
          f"outliers {int(data['B'][0]['outlier'].sum())}")
    periods_a = []
    for m in A_MONTHS:
        y, mo = map(int, m.split('-'))
        lo = dt.date(y, mo, 1)
        hi = (dt.date(y + (mo == 12), mo % 12 + 1, 1) - dt.timedelta(days=1)) if m != '2026-10' else dt.date(2026, 10, 21)
        periods_a.append((m, lo, hi))
    pred_a, imp_a = walk(a, a_cols, periods_a, trading)
    table_a = daily_table(pred_a)
    t5, _ = report('Study A: 5-minute, 15:15 -> next 09:00 open', table_a, imp_a, True)
    first = table_a[table_a['period'] <= '2026-06']['top5'].mean()
    second = table_a[table_a['period'] >= '2026-07']['top5'].mean()
    keep_a = t5[0] > 0 and t5[1] > 0 and first > 0 and second > 0
    print(f"halves TOP5 {first:+.3f} / {second:+.3f} -> Study A {'KEEP' if keep_a else 'NOT KEEP'}")

    b, b_cols = data['B']
    periods_b = [(str(y), dt.date(y, 1, 1), dt.date(y, 12, 31) if y < 2026 else dt.date(2026, 10, 21)) for y in B_YEARS]
    pred_b, imp_b = walk(b, b_cols, periods_b, trading)
    table_b = daily_table(pred_b)
    t5, by_period = report('Study B: daily 8 years, close -> next open', table_b, imp_b, False)
    positive_years = sum(1 for _, g in by_period if g['top5'].mean() > 0)
    keep_b = t5[0] > 0 and t5[1] > 0 and positive_years >= 5
    print(f"positive years {positive_years}/{len(B_YEARS)} -> Study B {'KEEP' if keep_b else 'NOT KEEP'}")


if __name__ == '__main__':
    main()
