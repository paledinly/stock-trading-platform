"""Monthly fundamental / price factor study (README.md), pre-registered 2026-11-09.

Committed with the README before the first discovery run. Implementation details fixed here before running:
  Prices: survivors from flow_study.static_ids + load_daily (adjusted daily candles); delisted from
    delisted_daily_history, codes passing share() and not among the survivors' codes, negative stock id in code order.
    Rows with volume 0 are dropped. Trading days = days with at least 500 rows.
  Accounts: data/cache/acnt (collect_dart.py). Per (stock code, business year, report) the CFS rows if the CFS has
    equity, else the OFS rows. Account names with spaces removed: equity '자본총계', assets '자산총계', operating income
    = first name starting with '영업이익', net income = first name containing '순이익' that has no '법인세' and no
    '지배' and is not '총포괄'. Amounts: commas removed, '-' or empty -> missing. Currency other than KRW -> skipped.
    Period end = first date in the equity row's thstrm_dt. Filing date = rcept_no[:8].
  Shares: data/cache/shares (year-end report). Row se == '보통주': distb_stock_co, else istc_totqy - tesstk_co.
    Period end = stlm_dt. Shares older than 18 months at the signal date are not used.
  Factor: survivors only (stock ids > 0), from investor_daily_flow as in the README; delisted ids use 1.
  Signal date D = first trading day of each month. Entry row i = the stock's row on D.
  Exit row j = the stock's first row on or after the next month's first trading day; none -> delisted code: last row
    (DELIST); stock's last row before the last trading day: last close x (1 + HAIRCUT) (STUCK); else no exit (dropped).
  Corporate-action jump: as final_study amendment 1, in rows i+1..j.
  Ranking: rows in stock id order, rank(method='first', pct=True); Q5 = pct > 0.8, Q1 = pct <= 0.2.
  Bootstrap: circular blocks of BLOCK months, DRAWS draws, numpy default_rng(SEED).
Run: python fundamental_study.py --stage check | discovery | test   (reads the repository .env, never prints credentials)
"""
import argparse
import datetime as dt
import glob
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent / '2026-11-05-investor-flow-preregistration'))
sys.path.insert(0, str(HERE.parent / '2026-11-03-investor-flow-seal'))
import flow_study as fs  # noqa: E402
from sealed import committed  # noqa: E402

PREREG = 'audit/2026-11-09-fundamental-study/README.md'
SCRIPT = 'audit/2026-11-09-fundamental-study/fundamental_study.py'
DATA = HERE / 'data'
STAGES = {'discovery': (dt.date(2019, 1, 1), dt.date(2022, 12, 31)),
          'test': (dt.date(2023, 1, 1), dt.date(2026, 10, 31))}
TEST_HALF = dt.date(2025, 1, 1)
MIN_ROWS, MIN_VALUE, LOW_VALUE, LIMIT_UP, MIN_STOCKS = 80, 1e9, 3e8, 0.29, 100
JUMP, TAIL, HAIRCUT = 0.31, 10, -0.5
BUY, SELL, HIGH_BUY, HIGH_SELL = 0.00095, 0.00295, 0.00165, 0.00365
STALE_ACCOUNTS, STALE_SHARES = 456, 548
DEADLINE_FY, DEADLINE_Q = 90, 45  # statutory filing deadlines (days after period end)
SHARE_TYPO = 30  # a share count this many times off the company's median is a DART typo
FACTOR_ROWS, FACTOR_WIDE, FACTOR_MIN_DAYS, FACTOR_MIN_AMOUNT = 20, 60, 5, 30  # amount in million won
MOM_FAR, MOM_NEAR = 253, 22
SIGNALS = ('BM', 'EP', 'OPA', 'MOM', 'VQM', 'SV')
HOLM_M = len(SIGNALS)
BLOCK, DRAWS, SEED = 3, 10_000, 20261109
TOP = 30
PERIOD_MONTHS = {'11013': 3, '11012': 6, '11014': 9, '11011': 12}


def num(x):
    if x is None:
        return np.nan
    x = str(x).replace(',', '').strip()
    if x in ('', '-'):
        return np.nan
    try:
        return float(x)
    except ValueError:
        return np.nan


def ymd(s):
    s = (s or '').strip()[:10].replace('.', '-')
    try:
        return dt.date(int(s[:4]), int(s[5:7]), int(s[8:10]))
    except ValueError:
        return None


# ---------------------------------------------------------------- data

def load_prices(c):
    ids = fs.static_ids(c)
    df, _ = fs.load_daily(c, ids, dt.date(2100, 1, 1))
    codes = dict(c.execute('select id, stock_code from stock').fetchall())
    df['code'] = df['sid'].map(codes)
    survivor_codes = set(df['code'].unique())
    meta = c.execute('select stock_code, stock_name from delisted_stock').fetchall()
    delisted_codes = {code for code, _ in meta}
    keep = sorted(code for code, name in meta if fs.share(code, name) and code not in survivor_codes)
    sid_of = {code: -(i + 1) for i, code in enumerate(keep)}
    raw = pd.DataFrame(c.execute("""select stock_code, trade_date, open_price, close_price, volume, trading_value
                                      from delisted_daily_history where open_price > 0 and close_price > 0
                                     order by stock_code, trade_date""").fetchall(),
                       columns=['code', 'd', 'o', 'c', 'v', 'val'])
    raw = raw[raw['code'].isin(sid_of)]
    dl = pd.DataFrame({'sid': raw['code'].map(sid_of).astype(int), 'd': raw['d'], 'o': raw['o'].astype(float),
                       'c': raw['c'].astype(float), 'v': raw['v'].fillna(0).astype(float),
                       'val': raw['val'].fillna(0).astype(float), 'code': raw['code']})
    df = pd.concat([df, dl], ignore_index=True)
    df = df[df['v'] > 0]
    trading = sorted(d for d, n in df.groupby('d').size().items() if n >= 500)
    df = df[df['d'].isin(set(trading))].sort_values(['sid', 'd']).reset_index(drop=True)
    df['delisted'] = df['code'].isin(delisted_codes)
    return df, trading


def load_implied(c):
    """(stock id, date) -> implied unadjusted price from the KIS investor flow amounts and quantities."""
    rows = c.execute(f"""
        select stock_id, trade_date,
               (abs(foreign_amount) + abs(institution_amount) + abs(individual_amount)) * 1e6
                 / nullif(abs(foreign_qty) + abs(institution_qty) + abs(individual_qty), 0)
          from investor_daily_flow
         where abs(foreign_amount) + abs(institution_amount) + abs(individual_amount) >= {FACTOR_MIN_AMOUNT}
           and abs(foreign_qty) + abs(institution_qty) + abs(individual_qty) > 0""").fetchall()
    out = pd.DataFrame(rows, columns=['sid', 'd', 'implied'])
    out['implied'] = out['implied'].astype(float)
    return out


def corp_to_code():
    rows = [line.rstrip('\n').split(',') for line in open(DATA / 'corp.csv', encoding='utf-8')][1:]
    return {r[0]: r[2] for r in rows if r[2]}


def parse_accounts():
    raw = {}
    for f in sorted(glob.glob(str(DATA / 'cache' / 'acnt' / '*.json'))):
        body = json.loads(Path(f).read_text(encoding='utf-8'))
        if body.get('status') != '000':
            continue
        for x in body['list']:
            key = (x['stock_code'], int(x['bsns_year']), x['reprt_code'], x['fs_div'])
            r = raw.setdefault(key, {'file': x['rcept_no'][:8], 'cur': x.get('currency') or 'KRW'})
            name = x['account_nm'].replace(' ', '')
            if name == '자본총계':
                field = 'equity'
            elif name == '자산총계':
                field = 'assets'
            elif name.startswith('영업이익'):
                field = 'op'
            elif '순이익' in name and '법인세' not in name and '지배' not in name and not name.startswith('총포괄'):
                field = 'ni'
            else:
                continue
            if field in r:
                continue
            r[field] = (num(x.get('thstrm_amount')), num(x.get('thstrm_add_amount')),
                        num(x.get('frmtrm_amount')), num(x.get('frmtrm_add_amount')), x.get('thstrm_dt') or '')
    chosen = {}
    for (code, year, rc, div), r in raw.items():
        k = (code, year, rc)
        if div == 'CFS' and 'equity' in r:
            chosen[k] = r
        elif k not in chosen:
            chosen[k] = r
    fy = {(code, year): r for (code, year, rc), r in chosen.items() if rc == '11011'}
    recs, skipped = [], {'currency': 0, 'no_equity': 0}
    for (code, year, rc), r in chosen.items():
        if r['cur'] != 'KRW':
            skipped['currency'] += 1
            continue
        if 'equity' not in r:
            skipped['no_equity'] += 1
            continue
        end = ymd(r['equity'][4])
        if end is None:
            skipped['no_end'] = skipped.get('no_end', 0) + 1
            continue
        out = {'code': code, 'year': year, 'reprt': rc, 'file': dt.date(int(r['file'][:4]), int(r['file'][4:6]),
                                                                       int(r['file'][6:8])),
               'end': end, 'equity': r['equity'][0],
               'assets': r['assets'][0] if 'assets' in r else np.nan}
        for field in ('ni', 'op'):
            v = r.get(field)
            if v is None:
                out[field] = np.nan
            elif rc == '11011':
                out[field] = v[0]
            else:
                cum = v[0] if rc == '11013' else v[1]
                prev_cum = v[2] if rc == '11013' else v[3]
                prev_fy = fy.get((code, year - 1), {}).get(field)
                out[field] = cum + prev_fy[0] - prev_cum if prev_fy is not None else np.nan
        recs.append(out)
    out = pd.DataFrame(recs)
    out['avail'] = available(out['file'], out['end'], np.where(out['reprt'] == '11011', DEADLINE_FY, DEADLINE_Q))
    return out, skipped


def available(file, end, deadline_days):
    """DART returns the latest (possibly amended) filing; the original was public by the statutory deadline."""
    limit = pd.to_datetime(end) + pd.to_timedelta(deadline_days, unit='D')
    return np.minimum(pd.to_datetime(file), limit).dt.date


def parse_shares():
    to_code = corp_to_code()
    recs = []
    for f in sorted(glob.glob(str(DATA / 'cache' / 'shares' / '*' / '*.json'))):
        body = json.loads(Path(f).read_text(encoding='utf-8'))
        if body.get('status') != '000':
            continue
        code = to_code.get(Path(f).stem)
        for x in body['list']:
            if x.get('se') != '보통주':
                continue
            n = num(x.get('distb_stock_co'))
            if not n > 0:
                n = num(x.get('istc_totqy')) - (0 if np.isnan(num(x.get('tesstk_co'))) else num(x.get('tesstk_co')))
            if n > 0 and ymd(x.get('stlm_dt')) is not None:
                f8 = x['rcept_no'][:8]
                recs.append({'code': code, 'end': ymd(x['stlm_dt']), 'file': dt.date(int(f8[:4]), int(f8[4:6]),
                                                                                     int(f8[6:8])), 'shares': n})
            break
    out = pd.DataFrame(recs)
    out['avail'] = available(out['file'], out['end'], DEADLINE_FY)
    return out


def share_factors(shares, df, implied, sid_of_code):
    """Adjusted-basis shares per report: shares x median(implied unadjusted price / adjusted close)."""
    px = df.loc[df['sid'] > 0, ['sid', 'd', 'c']].merge(implied, on=['sid', 'd'], how='left')
    px['ratio'] = px['implied'] / px['c']
    by_sid = {sid: (g['d'].to_numpy(), g['ratio'].to_numpy()) for sid, g in px.groupby('sid')}
    factor, how = [], []
    for code, end in zip(shares['code'], shares['end']):
        sid = sid_of_code.get(code)
        if sid is None:
            factor.append(np.nan)
            how.append('NO_PRICE')
            continue
        if sid < 0:
            factor.append(1.0)
            how.append('DELISTED_1')
            continue
        dates, ratio = by_sid[sid]
        k = int(np.searchsorted(dates, end, side='right'))
        result, tag = np.nan, 'NO_FLOW'
        for lo, hi, t in ((max(0, k - FACTOR_ROWS), k, 'BEFORE'), (max(0, k - FACTOR_WIDE), k + FACTOR_WIDE, 'WIDE')):
            r = ratio[lo:hi]
            r = r[~np.isnan(r)]
            if len(r) >= FACTOR_MIN_DAYS:
                result, tag = float(np.median(r)), t
                break
        factor.append(result)
        how.append(tag)
    shares = shares.copy()
    shares['factor'], shares['how'] = factor, how
    shares['adj_shares'] = shares['shares'] * shares['factor']
    ratio = shares['adj_shares'] / shares.groupby('code')['adj_shares'].transform('median')
    typo = (ratio > SHARE_TYPO) | (ratio < 1 / SHARE_TYPO)
    shares.loc[typo, 'how'] = 'TYPO'
    shares.loc[typo, 'adj_shares'] = np.nan
    return shares


def latest_as_of(reports, cols, dates, stale_days):
    """For each signal date, per code the latest report (by period end, then filing) available before the date."""
    reports = reports.dropna(subset=['code']).sort_values(['code', 'end', 'avail']).reset_index(drop=True)
    end = pd.to_datetime(reports['end']).to_numpy()
    filed = pd.to_datetime(reports['avail']).to_numpy()
    out = []
    for d in dates:
        d64 = np.datetime64(d)
        ok = (filed < d64) & ((d64 - end) <= np.timedelta64(stale_days, 'D'))
        r = reports[ok].groupby('code').tail(1)
        out.append(r[['code'] + cols].assign(D=d))
    return pd.concat(out, ignore_index=True)


# ---------------------------------------------------------------- panel

def month_starts(trading):
    starts, seen = [], set()
    for d in trading:
        if (d.year, d.month) not in seen:
            seen.add((d.year, d.month))
            starts.append(d)
    return starts


def build_panel(df, trading, accounts, shares):
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    df['n_prev'] = g.cumcount()
    df['val20'] = g['val'].transform(lambda s: s.shift(1).rolling(20).mean())
    df['prev_c'] = prev_c
    df['mom'] = g['c'].shift(MOM_NEAR) / g['c'].shift(MOM_FAR) - 1
    pos = {d: i for i, d in enumerate(trading)}
    df['pos'] = df['d'].map(pos)
    rows_left = g['d'].transform('size') - df['n_prev'] - 1
    any_jump = ((df['c'] / prev_c - 1).abs() > JUMP) | ((df['o'] / prev_c - 1).abs() > JUMP)
    up_jump = ((df['c'] / prev_c - 1) > JUMP) | ((df['o'] / prev_c - 1) > JUMP)
    jump = (any_jump & (rows_left >= TAIL)) | (up_jump & (rows_left < TAIL))
    df['cj'] = jump.astype(int).groupby(df['sid']).cumsum()

    starts = month_starts(trading)
    nxt = dict(zip(starts[:-1], starts[1:]))
    entries = df[df['d'].isin(set(nxt))].copy()
    entries['D_next'] = entries['d'].map(nxt)
    sids = df['sid'].to_numpy()
    sid_rank = pd.factorize(sids)[0]
    key = sid_rank.astype(np.int64) * 100_000 + df['pos'].to_numpy()
    e_idx = entries.index.to_numpy()
    target = sid_rank[e_idx].astype(np.int64) * 100_000 + entries['D_next'].map(pos).to_numpy()
    j = np.searchsorted(key, target, side='left')
    ok = (j < len(df)) & (sid_rank[np.minimum(j, len(df) - 1)] == sid_rank[e_idx])
    last_idx = g.cumcount(ascending=False).to_numpy()
    last_row = (np.arange(len(df)) + last_idx)  # index of the stock's last row
    c_arr, cj_arr, pos_arr = df['c'].to_numpy(), df['cj'].to_numpy(), df['pos'].to_numpy()
    lr = last_row[e_idx]
    jx = np.where(ok, j, lr)
    entries['exit_c'] = c_arr[jx]
    entries['kind'] = np.where(ok, 'NORMAL', 'NONE')
    delist = ~ok & entries['delisted'].to_numpy()
    stuck = ~ok & ~entries['delisted'].to_numpy() & (pos_arr[lr] < len(trading) - 1)
    entries.loc[delist, 'kind'] = 'DELIST'
    entries.loc[stuck, 'kind'] = 'STUCK'
    entries.loc[stuck, 'exit_c'] = c_arr[lr][stuck] * (1 + HAIRCUT)
    entries['stuck_last_c'] = np.where(stuck, c_arr[lr], np.nan)
    entries['late'] = ok & (pos_arr[jx] != entries['D_next'].map(pos).to_numpy())
    entries['jump'] = (cj_arr[jx] - entries['cj'].to_numpy()) > 0
    entries['gross'] = entries['exit_c'] / entries['c'] - 1

    acc = latest_as_of(accounts, ['equity', 'assets', 'ni', 'op'], starts, STALE_ACCOUNTS)
    sh = latest_as_of(shares.dropna(subset=['adj_shares']), ['adj_shares'], starts, STALE_SHARES)
    entries = entries.merge(acc, left_on=['code', 'd'], right_on=['code', 'D'], how='left').drop(columns='D')
    entries = entries.merge(sh, left_on=['code', 'd'], right_on=['code', 'D'], how='left').drop(columns='D')
    entries['mktcap'] = entries['prev_c'] * entries['adj_shares']
    entries['BM'] = entries['equity'] / entries['mktcap']
    entries['EP'] = entries['ni'] / entries['mktcap']
    entries['OPA'] = np.where(entries['assets'] > 0, entries['op'] / entries['assets'], np.nan)
    entries['MOM'] = entries['mom']
    entries['SIZE'] = -entries['mktcap']
    return entries.sort_values(['d', 'sid']).reset_index(drop=True), starts


def universe(entries, lo, hi, min_value=MIN_VALUE, drop_delisted=False):
    base = ((entries['d'] >= lo) & (entries['d'] <= hi) & (entries['n_prev'] >= MIN_ROWS)
            & (entries['val20'] >= min_value) & (entries['c'] / entries['prev_c'] - 1 < LIMIT_UP)
            & (entries['kind'] != 'NONE'))
    if drop_delisted:
        base &= ~entries['delisted']
    jumps = int((base & entries['jump']).sum())
    return entries[base & ~entries['jump']].copy(), jumps


def signal_frame(u, name):
    if name in ('VQM', 'SV'):
        parts = ('BM', 'EP', 'OPA', 'MOM') if name == 'VQM' else ('BM', 'EP', 'SIZE')
        f = u.dropna(subset=list(parts)).copy()
        f['sig'] = sum(f.groupby('d')[k].rank(method='first', pct=True) for k in parts) / len(parts)
    else:
        f = u[u[name].notna() & np.isfinite(u[name])].copy()
        f['sig'] = f[name]
    counts = f.groupby('d').size()
    f = f[f['d'].isin(set(counts[counts >= MIN_STOCKS].index))].copy()
    f['pct'] = f.groupby('d')['sig'].rank(method='first', pct=True)
    f['q'] = np.ceil(f['pct'] * 5).clip(1, 5).astype(int)
    f['top'] = f.groupby('d')['sig'].rank(method='first', ascending=False) <= TOP
    return f


# ---------------------------------------------------------------- statistics

def boot(series):
    x = np.asarray(series, dtype=float)
    n = len(x)
    rng = np.random.default_rng(SEED)
    k = -(-n // BLOCK)
    starts = rng.integers(0, n, size=(DRAWS, k))
    idx = (starts[:, :, None] + np.arange(BLOCK)[None, None, :]).reshape(DRAWS, -1)[:, :n] % n
    means = x[idx].mean(axis=1)
    return (float(x.mean()), float(np.quantile(means, 0.025)), float(np.quantile(means, 0.975)),
            float((means <= 0).mean()))


def turnover_net(f, members, months, buy, sell):
    """Per month mean of (1 + gross)(1 - sell_i)/(1 + buy_i) - 1 over the members, costs by membership change."""
    held = {d: set(f.loc[members & (f['d'] == d), 'sid']) for d in months}
    out, turn = {}, {}
    for k, d in enumerate(months):
        prev = held[months[k - 1]] if k > 0 else set()
        nxt = held[months[k + 1]] if k + 1 < len(months) else set()
        rows = f[members & (f['d'] == d)]
        if rows.empty:
            continue
        b = np.where(rows['sid'].isin(prev), 0.0, buy)
        s = np.where(rows['sid'].isin(nxt), 0.0, sell)
        out[d] = float(((1 + rows['gross']) * (1 - s) / (1 + b) - 1).mean() * 100)
        turn[d] = float((~rows['sid'].isin(prev)).mean())
    return out, turn


def mdd(monthly_pct):
    w = np.cumprod(1 + np.asarray(monthly_pct) / 100)
    return float((w / np.maximum.accumulate(w) - 1).min() * 100)


def evaluate(f, stage_months):
    months = [d for d in stage_months if d in set(f['d'])]
    by = f.groupby('d')
    uni = by['gross'].mean() * 100
    q = f.groupby(['d', 'q'])['gross'].mean().unstack() * 100
    excess_q = q.sub(uni, axis=0)
    q5 = f['q'] == 5
    net, turn = turnover_net(f, q5, months, BUY, SELL)
    net_high, _ = turnover_net(f, q5, months, HIGH_BUY, HIGH_SELL)
    top_net, _ = turnover_net(f, f['top'], months, BUY, SELL)
    ex = excess_q[5].reindex(months)
    netser = pd.Series(net).reindex(months)
    res = {
        'months': len(months),
        'excess': boot(ex.to_numpy()),
        'net': boot(netser.to_numpy()),
        'net_high': float(pd.Series(net_high).mean()),
        'top30_net': float(pd.Series(top_net).mean()),
        'universe': float(uni.reindex(months).mean()),
        'q_excess': {int(k): float(v) for k, v in excess_q.reindex(months).mean().items()},
        'q5_minus_q1': float((q[5] - q[1]).reindex(months).mean()),
        'hit_excess': float((ex > 0).mean()),
        'hit_net': float((netser > 0).mean()),
        'mdd_net': mdd(netser.to_numpy()),
        'annual_net': float(netser.mean() * 12),
        'turnover': float(pd.Series(turn).mean()),
        'stocks_per_month': float(by.size().reindex(months).mean()),
        'q5_delisted_rows': int((q5 & f['delisted']).sum()),
        'q5_kinds': {k: int(v) for k, v in f.loc[q5, 'kind'].value_counts().items()},
        'q5_late': int((q5 & f['late']).sum()),
    }
    years = {}
    for y in sorted({d.year for d in months}):
        m = [d for d in months if d.year == y]
        years[y] = (float(ex.reindex(m).mean()), float(netser.reindex(m).mean()))
    res['years'] = years
    stuck = q5 & (f['kind'] == 'STUCK')
    if stuck.any():
        sub = f[stuck]
        alt = {}
        for h in (0.0, -1.0):
            g2 = f['gross'].copy()
            g2[stuck] = sub['stuck_last_c'] * (1 + h) / sub['c'] - 1
            alt[str(h)] = float(((g2[q5].groupby(f.loc[q5, 'd']).mean() * 100).reindex(months) - uni.reindex(months))
                                .mean())
        res['stuck_excess_alt'] = alt
    return res, ex, netser


def holm(pvals):
    items = sorted(pvals.items(), key=lambda x: x[1]) + [(None, 1.0)] * (HOLM_M - len(pvals))
    adj, running = {}, 0.0
    for i, (key, p) in enumerate(items):
        running = max(running, min(1.0, (HOLM_M - i) * p))
        if key is not None:
            adj[key] = running
    return adj


def fmt(s):
    return f'{s[0]:+.3f} [{s[1]:+.3f}, {s[2]:+.3f}] p={s[3]:.4f}'


# ---------------------------------------------------------------- main

def load_all():
    c = fs.connect()
    df, trading = load_prices(c)
    implied = load_implied(c)
    c.close()
    accounts, skipped = parse_accounts()
    shares = parse_shares()
    sid_of_code = df.groupby('code')['sid'].first().to_dict()
    shares = share_factors(shares, df, implied, sid_of_code)
    return df, trading, accounts, skipped, shares


def guard(stage):
    if stage == 'check':
        return
    for path in (PREREG, SCRIPT):
        if not committed(path):
            raise SystemExit(f'{path} must be committed before the {stage} stage')
    if stage == 'test':
        if not committed('audit/2026-11-09-fundamental-study/result_discovery.json'):
            raise SystemExit('commit result_discovery.json before the test stage')
        done = HERE / 'result_test.json'
        seen = subprocess.run(['git', 'log', '--all', '--format=%h', '--', done.relative_to(ROOT).as_posix()],
                              cwd=ROOT, capture_output=True, text=True).stdout.strip()
        if done.exists() or seen:
            raise SystemExit('the test window was already judged for this study (result_test.json exists)')


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('--stage', choices=('check', 'discovery', 'test'), required=True)
    stage = ap.parse_args().stage
    guard(stage)
    df, trading, accounts, skipped, shares = load_all()
    entries, starts = build_panel(df, trading, accounts, shares)
    lines = [f'fundamental study, stage {stage}']

    if stage == 'check':
        lines.append(f'price rows {len(df):,}, stocks {df["sid"].nunique():,} (delisted ids {int((df.groupby("sid").size().index < 0).sum())}), '
                     f'trading days {len(trading)} ({trading[0]} .. {trading[-1]})')
        lines.append(f'account reports {len(accounts):,} (codes {accounts["code"].nunique():,}); skipped {skipped}; '
                     f'with ni {accounts["ni"].notna().mean():.1%}, op {accounts["op"].notna().mean():.1%}, '
                     f'assets {accounts["assets"].notna().mean():.1%}')
        lines.append(f'account reports by year: {accounts.groupby("year").size().to_dict()}')
        for label, rep in (('accounts', accounts), ('shares', shares)):
            lines.append(f'{label}: filing after the statutory deadline (amended or late) {int((rep["avail"] < rep["file"]).sum()):,} '
                         f'of {len(rep):,}')
        lines.append(f'share reports {len(shares):,}; factor source {shares["how"].value_counts().to_dict()}')
        fac = shares.loc[shares['how'].isin(['BEFORE', 'WIDE']), 'factor']
        lines.append(f'factor quantiles 1/5/50/95/99%: {[round(float(x), 3) for x in fac.quantile([.01, .05, .5, .95, .99])]}; '
                     f'|log factor| > log 1.5: {int((np.abs(np.log(fac)) > np.log(1.5)).sum())}')
        for st, (lo, hi) in STAGES.items():
            u, jumps = universe(entries, lo, hi)
            lines.append(f'{st}: months {u["d"].nunique()}, universe rows {len(u):,} (per month {len(u) / max(1, u["d"].nunique()):.0f}), '
                         f'jump drops {jumps}, kinds {u["kind"].value_counts().to_dict()}, late {int(u["late"].sum())}')
            cap = u.groupby('d')['mktcap'].sum() / 1e12
            lines.append(f'  universe market cap per month (trillion won) min {cap.min():,.0f} median {cap.median():,.0f} '
                         f'max {cap.max():,.0f}; largest single {u["mktcap"].max() / 1e12:,.0f}')
            for name in ('mktcap', 'BM', 'EP', 'OPA', 'MOM'):
                lines.append(f'  {name} available {u[name].notna().mean():.1%}')
            for name in SIGNALS:
                f = signal_frame(u, name)
                lines.append(f'  {name}: months {f["d"].nunique()}, per month {len(f) / max(1, f["d"].nunique()):.0f}')
        print('\n'.join(lines))
        return

    lo, hi = STAGES[stage]
    months = [d for d in starts if lo <= d <= hi]
    u, jumps = universe(entries, lo, hi)
    lines.append(f'signal months {months[0]} .. {months[-1]} ({len(months)}), universe rows {len(u):,}, jump drops {jumps}')
    lines.append(f'costs BASE buy {BUY:.3%} sell {SELL:.3%}, HIGH buy {HIGH_BUY:.3%} sell {HIGH_SELL:.3%}; per month, %')
    names = SIGNALS
    if stage == 'test':
        prev = json.loads((HERE / 'result_discovery.json').read_text(encoding='utf-8'))
        names = [n for n in SIGNALS if prev['signals'][n]['pass']]
        lines.append(f'signals passed stage 1: {names}')
    out = {'stage': stage, 'months': [str(m) for m in months], 'signals': {}}
    pvals = {}
    for name in SIGNALS:
        if name not in names:
            continue
        f = signal_frame(u, name)
        res, ex, netser = evaluate(f, months)
        lines.append(f'\n== {name} ==  months {res["months"]}, stocks per month {res["stocks_per_month"]:.0f}')
        lines.append(f'excess {fmt(res["excess"])}')
        lines.append(f'net    {fmt(res["net"])}  HIGH {res["net_high"]:+.3f}  top{TOP} net {res["top30_net"]:+.3f}')
        lines.append(f'universe {res["universe"]:+.3f}; Q1..Q5 excess {[round(res["q_excess"].get(k, np.nan), 3) for k in range(1, 6)]}; '
                     f'Q5-Q1 {res["q5_minus_q1"]:+.3f}')
        lines.append(f'hit excess {res["hit_excess"]:.0%}, hit net {res["hit_net"]:.0%}, MDD net {res["mdd_net"]:.1f}%, '
                     f'annual net {res["annual_net"]:+.1f}%, turnover {res["turnover"]:.0%}')
        lines.append('by year excess/net: ' + ', '.join(f'{y} {a:+.3f}/{b:+.3f}' for y, (a, b) in res['years'].items()))
        lines.append(f'Q5 delisted rows {res["q5_delisted_rows"]}, kinds {res["q5_kinds"]}, late {res["q5_late"]}'
                     + (f', stuck alt excess {res["stuck_excess_alt"]}' if 'stuck_excess_alt' in res else ''))
        if stage == 'discovery':
            pos_years = sum(1 for a, _ in res['years'].values() if a > 0)
            res['pass'] = bool(res['excess'][0] > 0 and res['excess'][3] < 0.05 and pos_years >= 3 and res['net'][0] > 0)
            lines.append(f'stage 1: excess>0 {res["excess"][0] > 0}, p<0.05 {res["excess"][3] < 0.05}, '
                         f'years+ {pos_years}/4, net>0 {res["net"][0] > 0} -> {"PASS" if res["pass"] else "FAIL"}')
            diag = {}
            for tag, kw in (('no_delisted', {'drop_delisted': True}), ('value_3e8', {'min_value': LOW_VALUE})):
                u2, _ = universe(entries, lo, hi, **kw)
                r2, _, _ = evaluate(signal_frame(u2, name), months)
                diag[tag] = {'excess': r2['excess'][0], 'net': r2['net'][0], 'top30_net': r2['top30_net'],
                             'stocks_per_month': r2['stocks_per_month']}
                lines.append(f'  diag {tag}: excess {r2["excess"][0]:+.3f}, net {r2["net"][0]:+.3f}, '
                             f'top{TOP} net {r2["top30_net"]:+.3f}, stocks/month {r2["stocks_per_month"]:.0f}')
            res['diag'] = diag
        else:
            halves = (float(ex[[d < TEST_HALF for d in ex.index]].mean()), float(ex[[d >= TEST_HALF for d in ex.index]].mean()))
            res['halves'] = halves
            pvals[name] = res['excess'][3]
        res['excess'] = list(res['excess'])
        res['net'] = list(res['net'])
        res['years'] = {str(k): v for k, v in res['years'].items()}
        out['signals'][name] = res
    if stage == 'test':
        adj = holm(pvals)
        lines.append('\n== verdict ==')
        for name in names:
            r = out['signals'][name]
            ok = r['excess'][0] > 0 and adj[name] < 0.05 and min(r['halves']) > 0 and r['net'][0] > 0
            r['holm_p'], r['verdict'] = adj[name], 'VERIFIED' if ok else 'NOT VERIFIED'
            lines.append(f'{name}: excess {r["excess"][0]:+.3f}, Holm p {adj[name]:.4f}, halves '
                         f'{r["halves"][0]:+.3f}/{r["halves"][1]:+.3f}, net {r["net"][0]:+.3f} -> {r["verdict"]}')
    text = '\n'.join(lines)
    print(text)
    (HERE / f'result_{stage}.txt').write_text(text + '\n', encoding='utf-8')
    (HERE / f'result_{stage}.json').write_text(json.dumps(out, ensure_ascii=False, indent=1, default=str),
                                               encoding='utf-8')


if __name__ == '__main__':
    main()
