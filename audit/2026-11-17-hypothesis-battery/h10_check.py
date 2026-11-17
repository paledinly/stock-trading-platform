"""사후 점검 (결과를 본 뒤 추가): H10이 상한가 마감 종목 때문인지 당일 등락 구간으로 나눠 본다.

사용: python -X utf8 h10_check.py <stock_days.csv>
"""
import sys, csv
from collections import defaultdict
from battery import per, stk_net, PERIODS

BK = [(-1, 0.0, '<0%'), (0.0, 0.05, '0~5%'), (0.05, 0.10, '5~10%'), (0.10, 0.20, '10~20%'), (0.20, 0.29, '20~29%'), (0.29, 1, '상한가(>=29%)')]


def main(path):
    byday = defaultdict(list)
    with open(path, encoding='utf-8') as f:
        rd = csv.reader(f); next(rd)
        for code, market, date, nxt, ret, loc, gap, cc, tv in rd:
            if not per(date) or not code.isdigit() or not code.endswith('0') or gap == '' or cc == '': continue
            byday[date].append((float(tv), float(ret) / 100, float(loc) if loc else None, float(gap) / 100, float(cc) / 100))
    cells = defaultdict(lambda: defaultdict(list))
    for d, rows in byday.items():
        top = sorted(rows, key=lambda r: -r[0])[:100]
        for r in top:
            if r[2] is None or r[2] < 0.9: continue
            for lo, hi, name in BK:
                if lo <= r[1] < hi:
                    cells[name][d].append(stk_net(r[3]))
                    if name != '상한가(>=29%)': cells['상한가 제외'][d].append(stk_net(r[3]))
    print('당일 등락 | ' + ' | '.join(p for p, _, _ in PERIODS) + ' (하루 평균 Net, 다음 날 시가 매도)')
    for name in [b[2] for b in BK] + ['상한가 제외']:
        out = []
        for p, a, b in PERIODS:
            x = [sum(v) / len(v) for d, v in cells[name].items() if a <= d < b]
            n = sum(len(v) for d, v in cells[name].items() if a <= d < b)
            out.append(f'{len(x)}일 {n}건 {sum(x) / len(x) * 100:+.3f}%' if x else '-')
        print(f'{name} | ' + ' | '.join(out))


if __name__ == '__main__':
    main(sys.argv[1])
