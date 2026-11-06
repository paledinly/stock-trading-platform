"""Descriptive metrics before the test stage (README.md > 추가 지표), discovery window only, same loading as
factor_diagnostics.py. Nothing here changes the pre-registered verdict.
  Payoff: buy-side holdings' mean winning Net / mean losing |Net|; excess per holding = Net - day universe mean.
  Benchmarks: equal-weight universe (the study's) and val20-weighted universe (cap-weighted index proxy; the
    market_index_daily table only starts in 2026).
  Liquidity tiers by val20 and slippage stress: extra cost per side added to BASE, flat and tiered.
  Deflated Sharpe (Bailey & Lopez de Prado 2014): per 20-row period, non-overlapping, averaged over the 20 offsets;
    the benchmark SR0 is the expected maximum of N null Sharpe estimates for N trials.
Run: python extra_metrics.py   (reads the repository .env, never prints credentials)
"""
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import kurtosis, norm, skew

import factor_diagnostics as fd

HERE = Path(__file__).resolve().parent
st, fs = fd.st, fd.fs
TIERS = ((1e9, 3e9, '10~30억'), (3e9, 1e10, '30~100억'), (1e10, float('inf'), '100억+'))
FLAT = (0.05, 0.10, 0.20, 0.30)
TIERED = {'10~30억': 0.30, '30~100억': 0.15, '100억+': 0.05}
TRIALS = (4, 25, 50, 100)
EULER = 0.5772156649


def periods(days, col, off):
    return days[col].dropna().to_numpy()[off::st.H] / 100


def dsr(days, col, n_trials):
    """(per-period SR, annualized SR, deflated Sharpe probability) averaged over offsets."""
    srs, probs = [], []
    for off in range(st.H):
        p = periods(days, col, off)
        t = len(p)
        if t < 10:
            continue
        sr = p.mean() / p.std(ddof=1)
        sr0 = 0.0 if n_trials <= 1 else math.sqrt(1 / (t - 1)) * (
            (1 - EULER) * norm.ppf(1 - 1 / n_trials) + EULER * norm.ppf(1 - 1 / (n_trials * math.e)))
        g3, g4 = skew(p), kurtosis(p, fisher=False)
        den = math.sqrt(max(1e-12, 1 - g3 * sr + (g4 - 1) / 4 * sr * sr))
        probs.append(norm.cdf((sr - sr0) * math.sqrt(t - 1) / den))
        srs.append(sr)
    sr = float(np.mean(srs))
    return sr, sr * math.sqrt(250 / st.H), float(np.mean(probs))


def ir(days, col):
    vals = []
    for off in range(st.H):
        p = periods(days, col, off)
        vals.append(p.mean() / p.std(ddof=1) * math.sqrt(250 / st.H))
    return float(np.mean(vals))


def main():
    disc = json.loads(st.DISCOVERY_JSON.read_text(encoding='utf-8'))
    lo, hi = st.DISCOVERY_FROM, st.DISCOVERY_END
    c = fs.connect()
    df, flows, trading, _ = st.load(c, 'discovery')
    df = st.stats(df, trading)
    sigs = fs.signals(df, flows, trading)
    del flows
    fr, _ = st.frames(df, sigs, trading, lo, hi)
    out = [f'extra metrics, discovery window {lo}..{hi}, hold {st.H} rows (test window not loaded)', '']
    result = {}
    for name in st.SIGNALS:
        direction = disc[name]['direction']
        f = fr[name].copy()
        xc = f['xc'].where(f['kind'] != 'STUCK', f['last_c'] * (1 + st.HAIRCUT))
        f['gross'] = (xc / f['o'] - 1) * 100
        f['net'] = fs.net(f['o'], xc)
        f['s'] = direction * f['sig']
        f['p'] = f.groupby('d')['s'].rank(method='first', pct=True)
        f['tier'] = pd.cut(f['val20'], [t[0] for t in TIERS] + [float('inf')], right=False,
                           labels=[t[2] for t in TIERS])
        allm = f.groupby('d')['net'].mean()
        f['w'] = f['val20'] / f.groupby('d')['val20'].transform('sum')
        vw = (f['net'] * f['w']).groupby(f['d']).sum()
        top = f[f['p'] > 0.8].copy()
        top['ex'] = top['net'] - top['d'].map(allm)
        r = {}
        out.append(f'== {name} (buy side = top 20% of {"" if direction > 0 else "-"}{name}) ==')

        win, loss = top.loc[top['net'] > 0, 'net'], top.loc[top['net'] <= 0, 'net']
        ewin, eloss = top.loc[top['ex'] > 0, 'ex'], top.loc[top['ex'] <= 0, 'ex']
        r['payoff_net'] = round(float(win.mean() / -loss.mean()), 3)
        r['payoff_excess'] = round(float(ewin.mean() / -eloss.mean()), 3)
        cut = top['ex'].quantile(0.99)
        trimmed = top[top['ex'] < cut].groupby('d')['ex'].mean().mean()
        out += [f'payoff Net: win rate {len(win) / len(top):.1%}, mean win {win.mean():+.2f}%, mean loss {loss.mean():+.2f}%, '
                f'ratio {r["payoff_net"]:.2f}',
                f'payoff excess: win rate {len(ewin) / len(top):.1%}, mean win {ewin.mean():+.2f}%, '
                f'mean loss {eloss.mean():+.2f}%, ratio {r["payoff_excess"]:.2f}; '
                f'holding skew {skew(top["ex"]):.2f}; excess without the top 1% holdings {trimmed:+.3f}']

        days = pd.DataFrame({'long': top.groupby('d')['net'].mean(), 'ew': allm, 'vw': vw}).dropna()
        days['ex_ew'], days['ex_vw'] = days['long'] - days['ew'], days['long'] - days['vw']
        r['ir_ew'], r['ir_vw'] = round(ir(days, 'ex_ew'), 3), round(ir(days, 'ex_vw'), 3)
        out.append(f'benchmarks per 20 rows: equal-weight {days["ew"].mean():+.3f}, val20-weighted {days["vw"].mean():+.3f}; '
                   f'buy side {days["long"].mean():+.3f}; IR vs EW {r["ir_ew"]:.2f}, vs VW {r["ir_vw"]:.2f} '
                   f'(excess vs VW {days["ex_vw"].mean():+.3f})')

        tiers = []
        for _, _, lbl in TIERS:
            t = top[top['tier'] == lbl]
            tiers.append(f'{lbl} share {len(t) / len(top):.0%} gross {t["gross"].mean():+.3f} Net {t["net"].mean():+.3f} '
                         f'excess {t["ex"].mean():+.3f}')
        out.append('liquidity tiers (buy side): ' + '; '.join(tiers))
        r['tier_share'] = {lbl: round(float((top['tier'] == lbl).mean()), 3) for _, _, lbl in TIERS}

        base_net = top.groupby('d')['net'].mean().mean()
        gross = top.groupby('d')['gross'].mean().mean()
        stress = [f'+{s:.2f}%/side {base_net - 2 * s:+.3f}' for s in FLAT]
        extra = top['tier'].map(TIERED).astype(float) * 2
        tiered = (top['net'] - extra).groupby(top['d']).mean().mean()
        r['breakeven_extra_per_side'] = round(float(base_net / 2), 4)
        r['net_tiered_slippage'] = round(float(tiered), 4)
        out += [f'slippage stress, buy-side Net (BASE {base_net:+.3f}, gross {gross:+.3f}): ' + ', '.join(stress),
                f'  tiered (+0.30 / +0.15 / +0.05 %/side) {tiered:+.3f}; break-even extra slippage {base_net / 2:.3f}%/side']

        ds = []
        for col, lbl in (('long', 'Net'), ('ex_ew', 'excess EW')):
            for n in TRIALS:
                sr, sra, prob = dsr(days, col, n)
                ds.append((lbl, n, sra, prob))
                r[f'dsr_{col}_n{n}'] = round(prob, 4)
        out.append('deflated Sharpe P[true SR > 0]: ' + '; '.join(
            f'{lbl} (SR {sra:.2f}) N={n} {prob:.2f}' for lbl, n, sra, prob in ds))
        out.append('')
        result[name] = r
        print('\n'.join(out[-8:]), flush=True)
    (HERE / 'extra_metrics.txt').write_text('\n'.join(out) + '\n', encoding='utf-8', newline='\n')
    (HERE / 'extra_metrics.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8',
                                             newline='\n')


if __name__ == '__main__':
    main()
