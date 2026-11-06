"""Coverage check of the delisted list and its price history (README.md > 상장폐지 목록 점검). Reads prices and volume
only, never the net-buy columns, so the seal is not involved.
Run: python delisted_coverage.py   (reads the repository .env, never prints credentials)
"""
import datetime as dt
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / '2026-11-05-investor-flow-preregistration'))
import flow_study as fs  # noqa: E402

START = dt.date(2018, 1, 2)


def main():
    c = fs.connect()
    kind = pd.read_csv(HERE.parent / '2026-11-05-delisted-list' / 'kind_delisted_2017-2026.csv', dtype=str)
    meta = pd.DataFrame(c.execute('select stock_code, stock_name, market, delisted_date, reason from delisted_stock')
                        .fetchall(), columns=['code', 'name', 'market', 'delisted', 'reason'])
    h = pd.DataFrame(c.execute("""select stock_code, min(trade_date), max(trade_date), count(*),
                                         min(trade_date) filter (where volume > 0), max(trade_date) filter (where volume > 0),
                                         count(*) filter (where volume > 0)
                                    from delisted_daily_history group by stock_code""").fetchall(),
                     columns=['code', 'first', 'last', 'rows', 'first_t', 'last_t', 'rows_t'])
    # market trading days from the survivors' candles (a day with >= 500 stocks)
    days = [d for d, n in c.execute("""select trade_date, count(*) from daily_adjusted_candle where trade_date >= %s
                                        group by trade_date""", (START,)).fetchall() if n >= 500]
    days = pd.Series(sorted(days))
    out = ['delisted coverage check', '']

    k = kind[kind['market'].isin(['KOSPI', 'KOSDAQ'])]
    out.append(f'KIND KOSPI/KOSDAQ rows {len(k)}, registered {len(meta)}, '
               f'registered but not in KIND {len(set(meta["code"]) - set(k["stock_code"]))}')
    m = meta.merge(h, on='code', how='left')
    m['delisted'] = pd.to_datetime(m['delisted']).dt.date
    no_rows = m[m['rows'].isna()]
    out.append(f'registered without any history row {len(no_rows)}: ' + ', '.join(no_rows['code'] + ' ' + no_rows['name']))
    m = m[m['rows'].notna()].copy()
    m['span_days'] = [int(((days >= a) & (days <= b)).sum()) for a, b in zip(m['first'], m['last'])]
    m['missing'] = m['span_days'] - m['rows']
    out.append(f'rows missing inside first..last (vs market trading days): total {int(m["missing"].clip(lower=0).sum())}, '
               f'stocks with > 5 missing {int((m["missing"] > 5).sum())}')
    late_start = m[(m['first'] > START + dt.timedelta(days=10)) & (m['delisted'] > dt.date(2018, 6, 1))]
    out.append(f'history starts after 2018-01-12 (listed later or truncated) {len(late_start)}; '
               f'of these delisted >= 2 years after their first row {int(((pd.to_datetime(late_start["delisted"]) - pd.to_datetime(late_start["first"])).dt.days > 730).sum())}')
    m['tail_gap'] = [int(((days > b) & (days <= d)).sum()) if pd.notna(b) else -1 for b, d in zip(m['last'], m['delisted'])]
    m['halt_gap'] = [int(((days > b) & (days <= d)).sum()) if pd.notna(b) else -1 for b, d in zip(m['last_t'], m['delisted'])]
    out.append('trading days between last history row and delisting date: '
               + ', '.join(f'{lbl} {int(v)}' for lbl, v in
                           [('<=3', (m['tail_gap'] <= 3).sum()), ('4..20', m['tail_gap'].between(4, 20).sum()),
                            ('>20', (m['tail_gap'] > 20).sum())]))
    out.append('trading days between last traded row (volume > 0) and delisting date: '
               + ', '.join(f'{lbl} {int(v)}' for lbl, v in
                           [('<=3', (m['halt_gap'] <= 3).sum()), ('4..60', m['halt_gap'].between(4, 60).sum()),
                            ('61..250', m['halt_gap'].between(61, 250).sum()), ('>250', (m['halt_gap'] > 250).sum())]))
    big = m[m['tail_gap'] > 20].sort_values('tail_gap', ascending=False)
    if len(big):
        out.append('  history ends > 20 days before delisting: ' + ', '.join(
            f'{r.code} {r.name} last {r.last} delisted {r.delisted} ({r.tail_gap})' for r in big.head(15).itertuples()))
    never = m[m['rows_t'].fillna(0) == 0]
    out.append(f'stocks with no traded row at all {len(never)}')

    # survivors that stop early: still listed today but no candle in the last 20 market days
    surv = pd.DataFrame(c.execute("""select s.stock_code, s.stock_name, max(d.trade_date) from stock s
                                       join daily_adjusted_candle d on d.stock_id = s.id group by s.stock_code, s.stock_name""")
                        .fetchall(), columns=['code', 'name', 'last'])
    cut = days.iloc[-20]
    stale = surv[surv['last'] < cut]
    stale_delisted = stale['code'].isin(set(meta['code']))
    out.append(f'survivor-table stocks whose candles end before {cut}: {len(stale)} '
               f'(in the delisted list {int(stale_delisted.sum())}, not {int((~stale_delisted).sum())})')

    by_year = m.assign(y=pd.to_datetime(m['delisted']).dt.year).groupby('y').size()
    out.append('registered delistings by year: ' + ', '.join(f'{y} {n}' for y, n in by_year.items()))
    text = '\n'.join(out) + '\n'
    print(text)
    (HERE / 'delisted_coverage.txt').write_text(text, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
