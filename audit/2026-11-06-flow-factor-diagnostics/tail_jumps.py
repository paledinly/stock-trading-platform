"""Corporate actions hidden in the 정리매매 exemption (README.md > 정리매매 예외의 구멍). The pre-registered jump rule
skips a stock's last TAIL traded rows so that 정리매매 crashes are kept; a capital reduction or reverse split during a
long halt then shows as an UPWARD jump in those rows and is kept too (e.g. 1,833 -> 625,000 won). Candidate amendment:
in the last TAIL rows an upward jump (> +31% close or open vs the previous close) still counts; downward moves stay
exempt. Also applies the rule to every stock (survivor_jumps.py).
  discovery window: results as registered vs amended; test window: holdings that would be dropped, prices only.
Run: python tail_jumps.py   (reads the repository .env, never prints credentials)
"""
import json
from pathlib import Path

import numpy as np

import factor_diagnostics as fd

HERE = Path(__file__).resolve().parent
st, fs = fd.st, fd.fs


def amended_span(df):
    g = df.groupby('sid', sort=False)
    prev_c = g['c'].shift(1)
    rows_left = g['d'].transform('size') - df['n_prev'] - 1
    up = ((df['c'] / prev_c - 1) > st.JUMP) | (df['gap'] > st.JUMP)
    any_ = ((df['c'] / prev_c - 1).abs() > st.JUMP) | (df['gap'].abs() > st.JUMP)
    jump = (any_ & (rows_left >= st.TAIL)) | (up & (rows_left < st.TAIL))
    cj = jump.astype(int).groupby(df['sid']).cumsum()
    cj_exit = cj.groupby(df['sid']).shift(-(st.H - 1))
    tail = df['kind'].isin(['DELIST', 'STUCK'])
    cj_exit[tail] = cj.groupby(df['sid']).transform('last')[tail]
    return jump, (cj_exit - cj).fillna(0) > 0


def window(df, trading, lo, hi):
    end = max(i for i, d in enumerate(trading) if d <= hi)
    return ((df['d'] >= lo) & (df['d'] <= hi) & (df['n_prev'] >= st.MIN_ROWS) & (df['val20'] >= st.MIN_VALUE)
            & (df['gap'] < st.MAX_GAP) & (df['pos'] + st.H - 1 <= end) & (df['kind'] != 'NONE'))


def main():
    disc = json.loads(st.DISCOVERY_JSON.read_text(encoding='utf-8'))
    c = fs.connect()
    df, flows, trading, _ = st.load(c, 'discovery')
    df = st.stats(df, trading)
    jump, span = amended_span(df)
    new = span & ~df['jump']
    out = ['tail jumps: amended jump rule (all stocks; upward jumps count inside the 정리매매 tail)', '',
           f'jump rows: registered rule {int((df["jump"] & False).sum()) if False else "-"}, amended {int(jump.sum())} '
           f'(delisted source {int((jump & (df["src"] == "D")).sum())}, survivors {int((jump & (df["src"] == "S")).sum())})']
    for lbl, lo, hi in (('discovery', st.DISCOVERY_FROM, st.DISCOVERY_END), ('test (prices only)', st.TEST_FROM, st.TEST_END)):
        w = window(df, trading, lo, hi)
        m = w & new
        out.append(f'{lbl}: universe holdings {int(w.sum())}, newly dropped {int(m.sum())} '
                   f'({df.loc[m, "sid"].nunique()} stocks: {sorted(df.loc[m, "sid"].unique().tolist())[:12]})')
    sigs = fs.signals(df, flows, trading)
    del flows
    lo, hi = st.DISCOVERY_FROM, st.DISCOVERY_END
    fr0, _ = st.frames(df, sigs, trading, lo, hi)
    df['jump'] = df['jump'] | span
    fr1, _ = st.frames(df, sigs, trading, lo, hi)
    out.append('')
    result = {}
    for name in st.SIGNALS:
        direction = disc[name]['direction']
        res, r = [], {}
        for lbl, fr in (('registered', fr0), ('amended', fr1)):
            days, leg = st.evaluate(fr[name].copy(), direction)
            ex, lng = st.boot(days['excess'].tolist()), st.boot(days['long'].tolist())
            hh = st.boot(days['long_h'].tolist())
            yearly = days.groupby(days.index.map(lambda d: d.year))['excess'].mean()
            buy = fr[name][leg.to_numpy()]
            r[lbl] = {'excess': [round(x, 4) for x in ex], 'net': [round(x, 4) for x in lng], 'net_high': round(hh[0], 4),
                      'years_positive': int((yearly > 0).sum()),
                      'pass': bool(ex[0] > 0 and ex[1] > 0 and (yearly > 0).sum() >= 5 and lng[0] > 0)}
            res.append(f'  {lbl:10s} excess {st.fmt(ex)}, Net {st.fmt(lng)}, Net HIGH {hh[0]:+.3f}, '
                       f'years {int((yearly > 0).sum())}/7, max buy-side Net {fs.net(buy["o"], buy["xc"]).max():.0f}% '
                       f'-> {"PASS" if r[lbl]["pass"] else "FAIL"}')
        out += [f'{name}:'] + res
        result[name] = r
    text = '\n'.join(out) + '\n'
    print(text)
    (HERE / 'tail_jumps.txt').write_text(text, encoding='utf-8', newline='\n')
    (HERE / 'tail_jumps.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
