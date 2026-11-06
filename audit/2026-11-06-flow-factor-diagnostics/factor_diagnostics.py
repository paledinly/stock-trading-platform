"""Factor-validity diagnostics for the final flow study's four signals, discovery window only (README.md).

Reuses ../2026-11-06-final-flow-preregistration/final_study.py load/stats/frames with stage 'discovery', so the test
window stays sealed (sealed.load_flows / load_delisted without prereg). Nothing here changes the pre-registered verdict.
  Signed signal s = direction * sig (direction from result_discovery.json), so higher s = the buy side.
  Rank IC: day Spearman of s with the 20-row Net; decay uses gross open-to-close returns at other horizons (rows that
    have the h-th row; no delisting exit).
  Controls at entry day i, from closes up to row i-1: rev5, ret20, mom (row i-121..i-21), vol20, log val20, log price.
  Fama-MacBeth: per day OLS of the day-demeaned Net on centred rank pcts (s alone, then s + controls).
  Portfolio: buy side Q5 of s, 20 equal tranches (one per day), non-overlapping 20-day rebalance per offset.
  Bootstrap: numpy circular date blocks of 20, 10,000 draws, seed 20261103 (vectorized; not the prereg's loop).
Run: python factor_diagnostics.py   (reads the repository .env, never prints credentials)
"""
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-11-06-final-flow-preregistration'))
import final_study as st  # noqa: E402
fs = st.fs

SEED, DRAWS, BLOCK = 20261103, 10_000, 20
HORIZONS = (1, 5, 10, 20, 40, 60)
CONTROLS = ('rev5', 'ret20', 'mom', 'vol20', 'liq', 'price')


def boot(x):
    """(mean, 2.5%, 97.5%) with circular date blocks."""
    x = np.asarray(pd.Series(x).dropna(), dtype=float)
    n = len(x)
    rng = np.random.default_rng(SEED)
    starts = rng.integers(0, n, size=(DRAWS, -(-n // BLOCK)))
    idx = ((starts[:, :, None] + np.arange(BLOCK)) % n).reshape(DRAWS, -1)[:, :n]
    means = np.sort(x[idx].mean(axis=1))
    return x.mean(), means[int(DRAWS * 0.025)], means[int(DRAWS * 0.975) - 1]


def fmt(stat, digits=3):
    m, lo, hi = stat
    return f'{m:+.{digits}f} [{lo:+.{digits}f}, {hi:+.{digits}f}]'


def day_spearman(f, a, b):
    ra = f.groupby('d')[a].rank(method='first')
    rb = f.groupby('d')[b].rank(method='first')
    tmp = pd.DataFrame({'d': f['d'], 'a': ra, 'b': rb}).dropna()
    return tmp.groupby('d').apply(lambda x: np.corrcoef(x['a'], x['b'])[0, 1] if len(x) > 2 else np.nan).dropna()


def add_features(df):
    g = df.groupby('sid', sort=False)
    c1 = g['c'].shift(1)
    lag = lambda k: g['c'].shift(k)  # noqa: E731
    df['rev5'] = c1 / lag(6) - 1
    df['ret20'] = c1 / lag(21) - 1
    df['mom'] = lag(21) / lag(121) - 1
    r = df['c'] / c1 - 1
    df['vol20'] = r.groupby(df['sid']).transform(lambda s: s.shift(1).rolling(20).std())
    df['liq'] = np.log(df['val20'])
    df['price'] = np.log(c1)
    for h in HORIZONS:
        df[f'g{h}'] = (g['c'].shift(-(h - 1)) / df['o'] - 1) * 100
        df[f'gp{h}'] = g['pos'].shift(-(h - 1))
    return df


def fama_macbeth(f, cols):
    def one(x):
        y = (x['net'] - x['net'].mean()).to_numpy()
        X = np.column_stack([np.ones(len(x))] + [x[k].to_numpy() for k in cols])
        if len(x) < 3 * len(cols) + 10:
            return pd.Series(np.nan, index=cols)
        beta = np.linalg.lstsq(X, y, rcond=None)[0]
        return pd.Series(beta[1:], index=cols)
    return f.groupby('d')[['net'] + list(cols)].apply(one)


def portfolio(days, col):
    """Non-overlapping 20-day returns per offset: (annualized Sharpe mean over offsets, worst MDD, mean per period)."""
    s = days[col].dropna().to_numpy() / 100
    sharpes, mdds = [], []
    for off in range(st.H):
        p = s[off::st.H]
        if len(p) < 10:
            continue
        sharpes.append(p.mean() / p.std(ddof=1) * np.sqrt(250 / st.H))
        wealth = np.cumprod(1 + p)
        mdds.append((wealth / np.maximum.accumulate(wealth) - 1).min())
    return float(np.mean(sharpes)), float(np.min(mdds)), float(np.median(mdds))


def main():
    disc = json.loads(st.DISCOVERY_JSON.read_text(encoding='utf-8'))
    lo, hi = st.DISCOVERY_FROM, st.DISCOVERY_END
    c = fs.connect()
    df, flows, trading, _ = st.load(c, 'discovery')
    df = st.stats(df, trading)
    sigs = fs.signals(df, flows, trading)
    del flows
    df = add_features(df)
    fr, _ = st.frames(df, sigs, trading, lo, hi)
    end_pos = max(i for i, d in enumerate(trading) if d <= hi)

    out = [f'factor diagnostics, discovery window {lo}..{hi}, hold {st.H} rows (test window not loaded)', '']
    summary = {}
    # market regime per day (ex ante): equal-weight daily return of all stocks, trailing 60-row sum and 20-row vol
    daily = (df['c'] / df.groupby('sid')['c'].shift(1) - 1).groupby(df['d']).mean().sort_index()
    trail = daily.rolling(60).sum().shift(1)
    tvol = daily.rolling(20).std().shift(1)

    for name in st.SIGNALS:
        direction = disc[name]['direction']
        f = fr[name].copy()
        f['s'] = direction * f['sig']
        f['net'] = fs.net(f['o'], f['xc'].where(f['kind'] != 'STUCK', f['last_c'] * (1 + st.HAIRCUT)))
        f['gross'] = (f['xc'].where(f['kind'] != 'STUCK', f['last_c'] * (1 + st.HAIRCUT)) / f['o'] - 1) * 100
        f['p'] = f.groupby('d')['s'].rank(method='first', pct=True)
        f['q'] = np.ceil(f['p'] * 5).clip(1, 5).astype(int)
        r = {}
        out.append(f'== {name} (direction {"+" if direction > 0 else "-"}; s = {"" if direction > 0 else "-"}{name}, '
                   f'buy side = top 20% of s) ==')

        # 1. predictive power
        ic = day_spearman(f, 's', 'net')
        icir = ic.mean() / ic.std()
        r['rank_ic'] = [round(float(x), 4) for x in boot(ic)]
        r['icir_daily'] = round(float(icir), 3)
        out += [f'rank IC (Net, 20 rows)   {fmt(boot(ic), 4)}  ICIR(day) {icir:.3f}  '
                f'ICIR x sqrt(250/20) {icir * np.sqrt(250 / st.H):.2f}  IC>0 days {(ic > 0).mean():.1%}']
        decay = []
        for h in HORIZONS:
            col = f'g{h}'
            ok = f[col].notna() & (f[f'gp{h}'] <= end_pos)
            ich = day_spearman(f[ok], 's', col)
            decay.append(f'h{h} {ich.mean():+.4f}')
            r[f'ic_h{h}'] = round(float(ich.mean()), 4)
        out.append('IC decay (gross, open->close of row h): ' + ', '.join(decay))

        # 2. quintile structure
        qd = f.groupby(['d', 'q'])['net'].mean().unstack()
        allm = f.groupby('d')['net'].mean()
        qx = qd.sub(allm, axis=0).mean()
        mono = all(qx.iloc[k] < qx.iloc[k + 1] for k in range(4))
        r['quintile_excess'] = [round(float(x), 4) for x in qx]
        out.append('quintile excess Q1..Q5 of s: ' + ' '.join(f'{x:+.3f}' for x in qx) +
                   f'  monotonic {"yes" if mono else "no"}')
        top = f[f['q'] == 5]
        hit = (top['net'] > top['d'].map(allm)).mean()
        hit_abs = (top['net'] > 0).mean()
        r['hit_vs_universe'], r['hit_positive'] = round(float(hit), 4), round(float(hit_abs), 4)
        out.append(f'hit rate (buy side): beats day universe {hit:.1%}, Net > 0 {hit_abs:.1%}')

        # 3. regimes (ex ante)
        days = pd.DataFrame({'long': top.groupby('d')['net'].mean(), 'all': allm,
                             'gross': top.groupby('d')['gross'].mean()}).dropna()
        days['excess'] = days['long'] - days['all']
        up = trail.reindex(days.index) > 0
        hv = tvol.reindex(days.index) > tvol.reindex(days.index).median()
        reg = {'mkt60 up': up, 'mkt60 down': ~up, 'vol high': hv, 'vol low': ~hv}
        out.append('regime excess: ' + ', '.join(f'{k} {fmt(boot(days.loc[m, "excess"]))} (n {int(m.sum())})'
                                                 for k, m in reg.items()))
        r['regime_excess'] = {k: round(float(days.loc[m, 'excess'].mean()), 4) for k, m in reg.items()}

        # 4. overlap with known characteristics
        corr = {k: float(day_spearman(f, 's', k).mean()) for k in CONTROLS}
        r['corr'] = {k: round(v, 3) for k, v in corr.items()}
        out.append('mean day rank corr of s with: ' + ', '.join(f'{k} {v:+.3f}' for k, v in corr.items()))
        g = f.dropna(subset=list(CONTROLS)).copy()
        for k in ('s',) + CONTROLS:
            g[k + '_r'] = g.groupby('d')[k].rank(method='first', pct=True) - 0.5
        fm1 = fama_macbeth(g, ['s_r'])
        fm2 = fama_macbeth(g, ['s_r'] + [k + '_r' for k in CONTROLS])
        r['fm_alone'] = [round(float(x), 4) for x in boot(fm1['s_r'])]
        r['fm_controls'] = [round(float(x), 4) for x in boot(fm2['s_r'])]
        out += [f'Fama-MacBeth slope of s (top-bottom Net, %): alone {fmt(boot(fm1["s_r"]))}, '
                f'with controls {fmt(boot(fm2["s_r"]))}',
                '  control slopes: ' + ', '.join(f'{k} {fm2[k + "_r"].mean():+.3f}' for k in CONTROLS)]

        # 5. implementability
        members = top.groupby('d')['sid'].apply(set).sort_index()
        prev = members.shift(1)
        turn = pd.Series([1 - len(a & b) / len(a) for a, b in zip(members[1:], prev[1:])], index=members.index[1:])
        piv = f.pivot(index='d', columns='sid', values='p').sort_index()
        ac1 = piv.corrwith(piv.shift(1), axis=1).mean()
        ac20 = piv.corrwith(piv.shift(st.H), axis=1).mean()
        gross = days['gross'].mean()
        cost = (fs.BUY + fs.SELL) * 100
        r['turnover_daily'], r['rank_autocorr'] = round(float(turn.mean()), 4), [round(float(ac1), 3), round(float(ac20), 3)]
        r['gross'], r['breakeven_roundtrip_pct'] = round(float(gross), 4), round(float(gross), 4)
        sh_l, mdd_l, mdd_lm = portfolio(days, 'long')
        sh_e, mdd_e, mdd_em = portfolio(days, 'excess')
        r['sharpe_long'], r['sharpe_excess'], r['mdd_long_worst'] = round(sh_l, 3), round(sh_e, 3), round(mdd_l, 4)
        out += [f'buy-side daily name turnover {turn.mean():.1%}; rank autocorr lag1 {ac1:.3f}, lag{st.H} {ac20:.3f}',
                f'buy-side gross {gross:+.3f}% per 20 rows -> break-even round trip {gross:.3f}% '
                f'(BASE {cost:.2f}%, Net {days["long"].mean():+.3f}%)',
                f'20-row rebalance: Sharpe Net {sh_l:.2f}, MDD worst {mdd_l:.1%} / median {mdd_lm:.1%}; '
                f'Sharpe excess {sh_e:.2f}, MDD worst {mdd_e:.1%}', '']
        summary[name] = r
        print('\n'.join(out[-14:]), flush=True)

    text = '\n'.join(out) + '\n'
    (HERE / 'result.txt').write_text(text, encoding='utf-8', newline='\n')
    (HERE / 'result.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8',
                                      newline='\n')


if __name__ == '__main__':
    main()
