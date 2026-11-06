"""Survivor corporate-action jumps (README.md > 생존 종목 점프). The pre-registration drops holdings that span a >31%
jump only for the delisted source (raw prices); daily_adjusted_candle also has unadjusted jumps (e.g. a 20:1 reverse
split). This applies the same rule to every stock:
  discovery window: the discovery result with those holdings dropped (flows sealed as usual);
  test window: the number of holdings that would be dropped, counted on prices only (no flow, no return).
Run: python survivor_jumps.py   (reads the repository .env, never prints credentials)
"""
import json
from pathlib import Path

import numpy as np

import factor_diagnostics as fd

HERE = Path(__file__).resolve().parent
st, fs = fd.st, fd.fs


def all_jumps(df):
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    rows_left = g['d'].transform('size') - df['n_prev'] - 1
    jump = (((df['c'] / prev_c - 1).abs() > st.JUMP) | (df['gap'].abs() > st.JUMP)) & (rows_left >= st.TAIL)
    cj = jump.astype(int).groupby(df['sid']).cumsum()
    cj_exit = cj.groupby(df['sid']).shift(-(st.H - 1))
    tail = df['kind'].isin(['DELIST', 'STUCK'])
    cj_exit[tail] = cj.groupby(df['sid']).transform('last')[tail]
    return jump, (cj_exit - cj).fillna(0) > 0


def main():
    disc = json.loads(st.DISCOVERY_JSON.read_text(encoding='utf-8'))
    c = fs.connect()
    df, flows, trading, _ = st.load(c, 'discovery')
    df = st.stats(df, trading)
    jump, span = all_jumps(df)
    out = ['survivor jumps: the pre-registered >31% jump rule applied to every stock', '',
           f'jump rows: delisted source {int((jump & (df["src"] == "D")).sum())}, '
           f'survivor source {int((jump & (df["src"] == "S")).sum())}']

    # test window, prices only: holdings that pass the price/liquidity filters and span a survivor jump
    end_pos = max(i for i, d in enumerate(trading) if d <= st.TEST_END)
    base = ((df['d'] >= st.TEST_FROM) & (df['d'] <= st.TEST_END) & (df['n_prev'] >= st.MIN_ROWS)
            & (df['val20'] >= st.MIN_VALUE) & (df['gap'] < st.MAX_GAP) & (df['pos'] + st.H - 1 <= end_pos)
            & (df['kind'] != 'NONE'))
    new = base & span & ~df['jump']
    out.append(f'test window (prices only): universe holdings {int(base.sum())}, spanning a survivor jump {int(new.sum())} '
               f'({df.loc[new, "sid"].nunique()} stocks, dates {df.loc[new, "d"].min()}..{df.loc[new, "d"].max()})')

    sigs = fs.signals(df, flows, trading)
    del flows
    lo, hi = st.DISCOVERY_FROM, st.DISCOVERY_END
    d_end = max(i for i, d in enumerate(trading) if d <= hi)
    dbase = ((df['d'] >= lo) & (df['d'] <= hi) & (df['n_prev'] >= st.MIN_ROWS) & (df['val20'] >= st.MIN_VALUE)
             & (df['gap'] < st.MAX_GAP) & (df['pos'] + st.H - 1 <= d_end) & (df['kind'] != 'NONE'))
    dnew = dbase & span & ~df['jump']
    out.append(f'discovery window: universe holdings spanning a survivor jump {int(dnew.sum())} '
               f'({df.loc[dnew, "sid"].nunique()} stocks)')
    fr0, _ = st.frames(df, sigs, trading, lo, hi)
    df['jump'] = df['jump'] | span
    fr1, _ = st.frames(df, sigs, trading, lo, hi)
    out.append('')
    for name in st.SIGNALS:
        direction = disc[name]['direction']
        res = []
        for lbl, fr in (('as registered', fr0), ('all-stock jump rule', fr1)):
            days, leg = st.evaluate(fr[name].copy(), direction)
            ex, lng = st.boot(days['excess'].tolist()), st.boot(days['long'].tolist())
            buy = fr[name][leg.to_numpy()]
            res.append(f'{lbl}: excess {st.fmt(ex)}, Net {lng[0]:+.3f}, max buy-side |Net| {np.abs(fs.net(buy["o"], buy["xc"])).max():.0f}%')
        out.append(f'{name}: ' + ' | '.join(res))
    text = '\n'.join(out) + '\n'
    print(text)
    (HERE / 'survivor_jumps.txt').write_text(text, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
