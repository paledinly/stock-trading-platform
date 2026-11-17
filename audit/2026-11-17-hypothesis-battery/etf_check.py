"""후보로 남은 지수 가설을 실제 ETF 가격으로 다시 계산한다 (결과를 본 뒤 추가한 실행 가능성 점검).

정의는 battery.py와 같다. 지수 대신 ETF 시가·종가를 쓰고, 장중 인버스는 실제 인버스 ETF 가격을 쓴다.
  KOSPI 쪽: KODEX 200(069500), KODEX 인버스(114800)
  KOSDAQ 쪽: KODEX 코스닥150(229200), KODEX 코스닥150선물인버스(251340, 2016-08 상장), 참고로 TIGER 코스닥150(232080)
가격: 네이버 금융 공개 일봉(수정 전 가격, 분배금 미반영). 왕복 비용 0.05%. 손익분기 비용도 보고한다.
H3 조건(하락한 날)은 ETF 자신의 당일 등락 <= -1%.

사용: python -X utf8 etf_check.py <etf_dir> > etf_check.txt
"""
import sys, os
from battery import summarize as _summarize, PERIODS, IDX_COST, per


def summarize(name, daily, gross=None):
    keep = {d: v for d, v in daily.items() if per(d)}
    return _summarize(name, keep, {d: gross[d] for d in keep} if gross else None)

_ = PERIODS


def load(path):
    rows = [l.strip().split('|') for l in open(path, encoding='utf-8').read().splitlines()[1:] if l.strip()]
    out = []
    for d, o, h, l, c, v in rows:
        d = f'{d[:4]}-{d[4:6]}-{d[6:]}'
        o, c = float(o), float(c)
        if o <= 0 or c <= 0: continue
        out.append((d, o, c))
    return out


def net(r): return (1 + r) * (1 - IDX_COST) - 1


def run(label, long_code, inv_code, etf_dir):
    a = load(os.path.join(etf_dir, f'{long_code}.csv'))
    print(f'\n## {label} (왕복 비용 {IDX_COST * 100:.2f}%)\n')
    print('가설 | P1 | P2 | P3 | 전체 [95% 구간] | 판정')
    on, dn, intra_long = {}, {}, {}
    for i in range(1, len(a) - 1):
        d, o, c = a[i]; pc = a[i - 1][2]; no = a[i + 1][1]
        g = no / c - 1
        if abs(g) > 0.15 or abs(c / pc - 1) > 0.15: continue  # 분할 등 이상치
        on[d] = g
        if c / pc - 1 <= -0.01: dn[d] = g
        intra_long[d] = c / o - 1
    res = [(f'H1 {label}', summarize('H1 밤 보유', {d: net(v) for d, v in on.items()}, on)),
           (f'H3 {label}', summarize('H3 하락일(<=-1%) 밤', {d: net(v) for d, v in dn.items()}, dn))]
    ok_h2 = None
    if inv_code:
        b = load(os.path.join(etf_dir, f'{inv_code}.csv'))
        inv = {d: c / o - 1 for d, o, c in b if abs(c / o - 1) < 0.15}
        res.append((f'H2 {label}', summarize('H2 장중 인버스(실제 인버스 ETF)', {d: net(v) for d, v in inv.items()}, inv)))
        # H7: 전날 종가->당일 시가(본 ETF) + 당일 시가->종가(인버스 ETF)
        dates = [x[0] for x in a]
        h7 = {}
        for i in range(1, len(dates)):
            pv, d = dates[i - 1], dates[i]
            if pv in on and d in inv:
                h7[d] = (1 + net(on[pv])) * (1 + net(inv[d])) - 1
        res.append((f'H7 {label}', summarize('H7 밤 + 장중 인버스', h7)))
        ok_h2 = True
    # 참고: 같은 날 본 ETF 장중 등락의 반대와 인버스 ETF 실제 장중 수익 차이
    if ok_h2:
        common = [d for d in intra_long if d in inv]
        diff = sum(inv[d] + intra_long[d] for d in common) / len(common)
        print(f'(참고) 인버스 ETF 장중 수익 - (본 ETF 장중 등락의 반대) 하루 평균: {diff * 100:+.3f}%p, {len(common)}일')
    return res


def main(etf_dir):
    r = run('KODEX 200', '069500', '114800', etf_dir)
    r += run('KODEX 코스닥150', '229200', '251340', etf_dir)
    r += run('TIGER 코스닥150', '232080', None, etf_dir)
    print('\n후보로 남은 것: ' + (', '.join(n for n, ok in r if ok) or '없음'))


if __name__ == '__main__':
    main(sys.argv[1])
