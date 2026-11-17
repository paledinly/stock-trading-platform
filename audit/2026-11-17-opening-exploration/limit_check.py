"""OV(종가 매수 -> 다음 날 시가) 상위 칸이 상한가 마감 종목 때문인지 본다. 상한가 종가는 실제로 사기 어렵다.

사용: python -X utf8 limit_check.py <opening.pkl> <stock_days.csv>
"""
import sys, pickle
from collections import defaultdict
import explore as E


def main(pkl, daily_path):
    data = pickle.load(open(pkl, 'rb'))
    daily = E.load_daily(daily_path, '2025-10-01')
    feats = E.features(data)
    cells = defaultdict(lambda: defaultdict(list))  # (ret10 group, close group) -> day -> OV
    for d, fr in feats.items():
        ds = d.isoformat()
        for r in fr:
            row = daily.get((r['code'], ds))
            if not row or row[3] is None: continue
            close = float(r['r']['cday']); cret = close / r['pc'] - 1
            ov = E.netr(close, close * (1 + row[3] / 100))
            g = '>=20' if r['ret10'] >= 0.20 else '10~20' if r['ret10'] >= 0.10 else '5~10' if r['ret10'] >= 0.05 else None
            if g is None: continue
            c = '상한가(>=29%)' if cret >= 0.29 else '20~29' if cret >= 0.20 else '10~20' if cret >= 0.10 else '0~10' if cret >= 0 else '<0'
            cells[(g, c)][d].append(ov)
            cells[(g, '상한가 제외')][d].append(ov) if cret < 0.29 else None
    print('RET10 | 당일 종가 등락 | 종목-일 | A 일수 net | B 일수 net')
    for k in sorted(cells):
        dm = {d: sum(v) / len(v) for d, v in cells[k].items()}
        n = sum(len(v) for v in cells[k].values())
        a = [v for d, v in dm.items() if d < E.SPLIT]; b = [v for d, v in dm.items() if d >= E.SPLIT]
        f = lambda x: f'{len(x)}일 {E.pct(sum(x) / len(x))}' if x else '-'
        print(f'{k[0]} | {k[1]} | {n:,} | {f(a)} | {f(b)}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
