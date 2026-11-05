"""Size-controlled diagnostic of the four passing 20-day cells (README.md > 규모 통제 진단), discovery window only.

Committed with the README section before the first run. Implementation details fixed here:
  Universe, signals, entry/exit and direction exactly as flow_horizons.py (discovery stage, exits <= 2024-10-31).
  Size buckets: per day, rank(method='first', pct) of val20 in stock-id order, bucket = ceil(pct * 5).
  Within (day, bucket): signal quintile = ceil(rank pct * 5); activity = |signal| rank pct > 0.8.
  Day value = equal-weight mean over the buckets present that day of (group mean Net - bucket mean Net).
Run: python size_control.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import flow_horizons as fh  # noqa: E402

fs = fh.fs
H, BLOCK = 20, 20


def neutral(f, mask):
    """Day series: equal-weight mean over size buckets of (mean Net of rows in mask - bucket mean Net)."""
    bm = f.groupby(['d', 'b'])['net'].mean()
    gm = f[mask].groupby(['d', 'b'])['net'].mean()
    return (gm - bm.reindex(gm.index)).groupby(level='d').mean().sort_index()


def raw(f, mask):
    am = f.groupby('d')['net'].mean()
    gm = f[mask].groupby('d')['net'].mean()
    return (gm - am.reindex(gm.index)).sort_index()


def main():
    disc = json.loads((HERE / 'result_discovery.json').read_text(encoding='utf-8'))
    cells = [(n, h) for n, h in fh.CELLS if h == H and disc[f'{n}/{h}']['pass']]
    lo, hi = fh.DISCOVERY_FROM, fh.DISCOVERY_END
    c = fs.connect()
    ids = fs.static_ids(c)
    df, _ = fs.load_daily(c, ids, hi + dt.timedelta(days=45))
    df = df[df['v'] > 0].reset_index(drop=True)
    trading = sorted(d for d, n in df.groupby('d').size().items() if n >= 500)
    df = df[df['d'].isin(set(trading))].reset_index(drop=True)
    df = fh.stock_stats(df, trading)
    flows = fh.load_flows(c, columns=('stock_id', 'trade_date', 'foreign_amount', 'institution_amount'))
    flows = flows[flows['stock_id'].isin(ids)]
    sigs = fs.signals(df, flows, trading)
    del flows
    base = ((df['d'] >= lo) & (df['d'] <= hi) & (df['n_prev'] >= fh.MIN_ROWS) & (df['val20'] >= fh.MIN_VALUE)
            & (df['gap'] < fh.MAX_GAP))
    out = [f'size-controlled diagnostic, discovery {lo}..{hi}, h={H}', '']
    for name, h in cells:
        direction = disc[f'{name}/{h}']['direction']
        ok = base & df[f'xc{h}'].notna() & (df[f'xd{h}'] <= hi)
        f = df[ok].copy()
        f['sig'] = sigs[name][ok.to_numpy()]
        f = f[f['sig'].notna()]
        counts = f.groupby('d').size()
        f = f[f['d'].isin(set(counts[counts >= fh.MIN_STOCKS].index))].copy()
        f['net'] = fs.net(f['o'], f[f'xc{h}'])
        f['b'] = np.ceil(f.groupby('d')['val20'].rank(method='first', pct=True) * 5).astype(int)
        f['q'] = np.ceil(f.groupby(['d', 'b'])['sig'].rank(method='first', pct=True) * 5).astype(int)
        f['qa'] = np.ceil(f.groupby('d')['sig'].rank(method='first', pct=True) * 5).astype(int)
        f['act'] = f['sig'].abs().groupby([f['d'], f['b']]).rank(method='first', pct=True) > 0.8
        buy, opp = (1, 5) if direction < 0 else (5, 1)
        a = neutral(f, f['q'] == buy)
        bdiff = (a - neutral(f, f['q'] == opp)).dropna()
        act = neutral(f, f['act'])
        out += [f'== {name} h={h} (buy side Q{buy}) ==',
                f'A size-neutral buy-side excess  {fh.fmt(fh.boot(a.dropna().tolist(), BLOCK))}',
                f'B size-neutral Q{buy} - Q{opp}          {fh.fmt(fh.boot(bdiff.tolist(), BLOCK))}',
                f'C size-neutral activity (|sig| top 20%) {fh.fmt(fh.boot(act.dropna().tolist(), BLOCK))}',
                'quintile excess raw:          ' + ', '.join(f'Q{q} {raw(f, f["qa"] == q).mean():+.3f}' for q in range(1, 6)),
                'quintile excess size-neutral: ' + ', '.join(f'Q{q} {neutral(f, f["q"] == q).mean():+.3f}' for q in range(1, 6))]
        per = []
        for b in range(1, 6):
            fb = f[f['b'] == b]
            per.append(f'B{b} {raw(fb, fb["q"] == buy).mean():+.3f}')
        out.append('A by size bucket (B1 smallest): ' + ', '.join(per))
        al, bl = fh.boot(a.dropna().tolist(), BLOCK)[1], fh.boot(bdiff.tolist(), BLOCK)[1]
        reading = ('DIRECTION effect survives size' if al > 0 and bl > 0 else
                   'ACTIVITY (extremes) effect, not direction' if al > 0 else 'SIZE effect')
        out += [f'reading: {reading}', '']
    text = '\n'.join(out) + '\n'
    print(text)
    (HERE / 'result_size_control.txt').write_text(text, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
