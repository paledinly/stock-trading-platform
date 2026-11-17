"""새 가설 묶음 탐색 (2026-11-17). 가설을 찾는 단계이며 운영 판정이 아니다. 정의는 첫 실행 전에 고정했다.

지난 탐색에서 일봉 종목 단위 방향(모멘텀·되돌림·변동성·이동평균·공시·달력·매집·보유 기간)은 이미 봤다.
이번에는 아직 보지 않은 축을 본다.
  1) 비용이 낮은 지수 ETF로 "밤에 오르고 장중에 빠지는" 구조를 직접 노리기
  2) 시장 급락 뒤 반등
  3) 유동성 큰 종목의 하룻밤 보유

데이터
  index_days.csv: KOSPI(0001)·KOSDAQ(1001) 일봉 지수, 2017-12 ~ 2026-11. 열 kospi(당일 종가 등락 %), kospi_gap(다음 날 시가/당일 종가-1 %),
    kospi_cc(다음 날 종가/당일 종가-1 %), kospi_loc 등. KOSDAQ 같은 형식.
  stock_days.csv: 수정주가 일봉 + 상장폐지 이력(ETF·ETN 제외). 열은 surge.py와 같다.
  ETF 가격은 DB에 없다. 지수를 ETF 대용으로 쓴다(한계: ETF 시가·종가는 지수와 조금 다르다. 운영 전 ETF 실제 가격으로 다시 확인해야 한다).

비용
  지수(ETF 대용): 왕복 0.05% (수수료 양쪽 + 호가 차이 근사, ETF는 매도 거래세 없음). 손익분기 비용도 함께 보고한다.
  종목: BASE 왕복 0.39% (매수 0.095%, 매도 0.295%).

가설 (지수는 KOSPI·KOSDAQ 각각)
  H1  밤 보유: 매일 종가 매수 -> 다음 날 시가 매도
  H2  장중 반대: 매일 시가에 인버스 ETF 매수 -> 종가 매도 (수익 = -(시가->종가 등락))
  H3  하락한 날 밤 보유: 당일 지수 등락 <= -1%인 날만 H1
  H4  상승한 날 밤 보유: 당일 지수 등락 >= +1%인 날만 H1
  H5  금요일 밤 보유: 금요일 종가 -> 다음 거래일 시가 (요일 효과 확인)
  H6  급락 반등: 당일 지수 등락 <= -3%인 날 종가 매수, 1·3·5거래일 뒤 종가 매도
  H7  밤 보유 + 장중 인버스 결합: H1과 H2를 같은 날 모두 (하루 두 번 거래, 비용 두 번)
  H8  종목: 당일 거래대금 상위 100종목 동일 금액, 종가 매수 -> 다음 날 시가 / 다음 날 종가 매도
  H9  종목: 거래대금 상위 100 중 당일 등락 <= -3% 종목, 종가 매수 -> 다음 날 시가
  H10 종목: 거래대금 상위 100 중 당일 종가 위치(loc) >= 0.9, 종가 매수 -> 다음 날 시가

기간: P1 2018-2021, P2 2022-2024, P3 2025-01 ~ 2026-11. 날짜 단위 평균 Net(그날 거래가 없으면 빠짐).
판정용 기준(후보로 남기는 기준): 세 기간 모두 하루 평균 Net > 0, 전체 기간 5일 묶음 재표본(2,000회, seed 20261117) 95% 구간 하한 > 0,
  기간마다 거래일 20일 이상(H6은 표본이 적어 기술 통계만).
남아도 운영에 바로 쓰지 않는다. 11개 이상 비교라 우연일 수 있으므로 ETF 실제 가격 확인과 새 데이터 확인을 거친다.

사용: python -X utf8 battery.py <index_days.csv> <stock_days.csv> > result.txt
"""
import sys, csv, random, datetime as dt
from collections import defaultdict

IDX_COST = 0.0005
BUY, SELL = 0.00095, 0.00295
PERIODS = [('P1', '2018-01-01', '2022-01-01'), ('P2', '2022-01-01', '2025-01-01'), ('P3', '2025-01-01', '2026-12-31')]


def per(d):
    for n, a, b in PERIODS:
        if a <= d < b: return n
    return None


def idx_net(r):
    return (1 + r) * (1 - IDX_COST) - 1


def stk_net(r):
    return (1 + r) * (1 - SELL) / (1 + BUY) - 1


def boot(series, reps=2000, block=5, seed=20261117):
    """series: 날짜 순 하루 값 목록. 원형 5일 묶음 재표본 평균의 2.5%·97.5% 분위."""
    n = len(series)
    if n < 10: return float('nan'), float('nan')
    rnd = random.Random(seed); ms = []
    for _ in range(reps):
        s = []
        while len(s) < n:
            st = rnd.randrange(n); s.extend(series[(st + k) % n] for k in range(block))
        ms.append(sum(s[:n]) / n)
    ms.sort()
    return ms[int(reps * 0.025)], ms[int(reps * 0.975)]


def summarize(name, daily, gross=None, note=''):
    """daily: {date: net}. gross: {date: gross} (손익분기 비용 계산용)."""
    ds = sorted(daily)
    row = [name]
    ok = True
    for p, a, b in PERIODS:
        x = [daily[d] for d in ds if a <= d < b]
        if not x: row.append('-'); ok = False; continue
        m = sum(x) / len(x)
        row.append(f'{len(x)}일 {m * 100:+.3f}% 승 {sum(v > 0 for v in x) / len(x):.0%}')
        if m <= 0 or len(x) < 20: ok = False
    allx = [daily[d] for d in ds]
    lo, hi = boot(allx)
    m = sum(allx) / len(allx)
    if not (lo > 0): ok = False
    be = ''
    if gross:
        g = [gross[d] for d in ds]
        be = f' | 손익분기 왕복 비용 {sum(g) / len(g) * 100:+.3f}%'
    print(' | '.join(row) + f' | 전체 {len(allx)}일 {m * 100:+.3f}% [{lo * 100:+.3f}, {hi * 100:+.3f}]{be} | {"후보" if ok else "-"}{" " + note if note else ""}')
    return ok


def load_index(path):
    rows = list(csv.DictReader(open(path, encoding='utf-8')))
    out = {}
    prev = None
    for r in rows:
        d = r['date']
        rec = {'date': d, 'wd': dt.date.fromisoformat(d).weekday()}
        for k in ('kospi', 'kosdaq'):
            rec[k] = float(r[k]) / 100
            rec[k + '_gap'] = float(r[k + '_gap']) / 100
            rec[k + '_cc'] = float(r[k + '_cc']) / 100
            # 당일 시가->종가: 전날 행의 다음 날 시가 갭을 쓴다
            rec[k + '_intra'] = ((1 + rec[k]) / (1 + prev[k + '_gap']) - 1) if prev and prev['next'] == d else None
        rec['next'] = r['next']
        out[d] = rec; prev = rec | {'next': r['next']}
    return out


def index_tests(ix):
    ds = sorted(ix)
    pos = {d: i for i, d in enumerate(ds)}
    results = []
    for k, label in (('kospi', 'KOSPI'), ('kosdaq', 'KOSDAQ')):
        print(f'\n## {label} (ETF 대용, 왕복 비용 {IDX_COST * 100:.2f}%)\n')
        print('가설 | P1 | P2 | P3 | 전체 [95% 구간] | 판정')
        g1 = {d: ix[d][k + '_gap'] for d in ds if per(d)}
        results.append(('H1 ' + label, summarize(f'H1 밤 보유', {d: idx_net(v) for d, v in g1.items()}, g1)))
        # H2: 당일 시가 -> 종가의 반대. 날짜는 그 거래일.
        g2 = {d: -ix[d][k + '_intra'] for d in ds if per(d) and ix[d][k + '_intra'] is not None}
        results.append(('H2 ' + label, summarize('H2 장중 인버스', {d: idx_net(v) for d, v in g2.items()}, g2)))
        g3 = {d: v for d, v in g1.items() if ix[d][k] <= -0.01}
        results.append(('H3 ' + label, summarize('H3 하락일(<=-1%) 밤', {d: idx_net(v) for d, v in g3.items()}, g3)))
        g4 = {d: v for d, v in g1.items() if ix[d][k] >= 0.01}
        results.append(('H4 ' + label, summarize('H4 상승일(>=+1%) 밤', {d: idx_net(v) for d, v in g4.items()}, g4)))
        g5 = {d: v for d, v in g1.items() if ix[d]['wd'] == 4}
        results.append(('H5 ' + label, summarize('H5 금요일 밤', {d: idx_net(v) for d, v in g5.items()}, g5)))
        for h in (1, 3, 5):
            g6 = {}
            for d in ds:
                if not per(d) or ix[d][k] > -0.03: continue
                m = 1.0; cur = d
                for _ in range(h):
                    if cur not in ix: m = None; break
                    m *= 1 + ix[cur][k + '_cc']; cur = ix[cur]['next']
                if m is not None: g6[d] = m - 1
            summarize(f'H6 급락(<=-3%) 후 {h}일', {d: idx_net(v) for d, v in g6.items()}, g6, note='(기술 통계만)')
        # H7: 같은 날짜 기준으로 밤(전날 종가->당일 시가)과 당일 장중 인버스를 함께
        g7 = {}
        for d in ds:
            i = pos[d]
            if not per(d) or i == 0 or ix[d][k + '_intra'] is None: continue
            pv = ds[i - 1]
            if ix[pv]['next'] != d: continue
            g7[d] = (idx_net(ix[pv][k + '_gap']) + 1) * (idx_net(-ix[d][k + '_intra']) + 1) - 1
        results.append(('H7 ' + label, summarize('H7 밤 + 장중 인버스', g7)))
        # 연도별 H1·H2 (참고)
        print('\n연도 | H1 밤 Net | H2 장중 인버스 Net | 지수 연간 등락')
        by = defaultdict(lambda: [[], [], 1.0])
        for d in ds:
            if not per(d): continue
            y = d[:4]
            by[y][0].append(idx_net(ix[d][k + '_gap']))
            if ix[d][k + '_intra'] is not None: by[y][1].append(idx_net(-ix[d][k + '_intra']))
            by[y][2] *= 1 + ix[d][k]
        for y in sorted(by):
            a, b, c = by[y]
            print(f'{y} | {sum(a) / len(a) * 100:+.3f}% | {sum(b) / len(b) * 100:+.3f}% | {(c - 1) * 100:+.1f}%')
    return results


def stock_tests(path):
    byday = defaultdict(list)
    with open(path, encoding='utf-8') as f:
        rd = csv.reader(f); next(rd)
        for code, market, date, nxt, ret, loc, gap, cc, tv in rd:
            if not per(date) or not code.isdigit() or not code.endswith('0'): continue
            if gap == '' or cc == '': continue
            byday[date].append((float(tv), float(ret) / 100, float(loc) if loc else None, float(gap) / 100, float(cc) / 100))
    print('\n## 종목 (왕복 비용 0.39%)\n')
    print('가설 | P1 | P2 | P3 | 전체 [95% 구간] | 판정')
    h8o, h8c, h9, h10, g8o = {}, {}, {}, {}, {}
    for d, rows in byday.items():
        top = sorted(rows, key=lambda r: -r[0])[:100]
        h8o[d] = sum(stk_net(r[3]) for r in top) / len(top)
        g8o[d] = sum(r[3] for r in top) / len(top)
        h8c[d] = sum(stk_net(r[4]) for r in top) / len(top)
        a = [stk_net(r[3]) for r in top if r[1] <= -0.03]
        if a: h9[d] = sum(a) / len(a)
        b = [stk_net(r[3]) for r in top if r[2] is not None and r[2] >= 0.9]
        if b: h10[d] = sum(b) / len(b)
    res = [('H8 시가', summarize('H8 상위 100 밤', h8o, g8o)),
           ('H8 종가', summarize('H8 상위 100 다음 날 종가', h8c)),
           ('H9', summarize('H9 상위 100 중 -3% 이하 밤', h9)),
           ('H10', summarize('H10 상위 100 중 종가 고가 근처 밤', h10))]
    return res


def main(ipath, spath):
    ix = load_index(ipath)
    r = index_tests(ix)
    r += stock_tests(spath)
    print('\n## 후보로 남은 가설\n')
    kept = [n for n, ok in r if ok]
    print(', '.join(kept) if kept else '없음')
    print(f'\n판정 대상 비교 수: {len(r)}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
