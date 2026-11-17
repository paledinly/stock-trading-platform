"""장초반 상승 종목 탐색 분석 (2026-11-17). 가설을 찾는 단계이며 판정이 아니다.

- 대상과 09:10 지표는 ../2026-11-17-opening-momentum-preregistration/opening.py의 features()를 그대로 쓴다.
- 진입: 09:10 봉 시가(eo). 진입가 >= 전일 종가 x 1.29면 제외.
- 보유 기간 (모두 09:10 진입):
    D0  당일 종가
    N1  다음 날 시가
    C1  다음 날 종가
    C3  3거래일 뒤 종가
    C5  5거래일 뒤 종가
  비교용 OV: 당일 종가 매수 -> 다음 날 시가 매도 (09:10 조건으로 고른 종목).
- 다음 날 이후 가격은 일봉 파일(stock_days.csv: code, market, date, next, ret, loc, gap, cc, tv; 퍼센트 단위)로 잇는다.
  gap = 다음 날 시가 / 당일 종가 - 1, cc = 다음 날 종가 / 당일 종가 - 1. 일봉은 전 종목이라 수집 명단 탈락에 따른 결측이 없다.
- 비용 BASE 왕복(매수 0.095%, 매도 0.295%)을 보유 기간과 무관하게 한 번 뺀다.
- 하루 평균: 그 칸에 든 종목을 그날 평균하고, 날짜로 다시 평균한다. 초과 = 그날 칸 평균 - 그날 대상 전체 평균(같은 보유 기간).
- 안정성: 기간을 A(2025-11 ~ 2026-04), B(2026-05 ~ 2026-11-16) 둘로 나눠 따로 본다.

사용: python -X utf8 explore.py <opening.pkl> <stock_days.csv> > result.txt
"""
import sys, os, csv, pickle, math, datetime as dt
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '2026-11-17-opening-momentum-preregistration'))
from opening import features  # noqa: E402

BUY, SELL = 0.00095, 0.00295
HZ = ['D0', 'N1', 'C1', 'C3', 'C5', 'OV']
SPLIT = dt.date(2026, 5, 1)


def load_daily(path, since):
    d = {}
    with open(path, encoding='utf-8') as f:
        rd = csv.reader(f); next(rd)
        for code, market, date, nxt, ret, loc, gap, cc, tv in rd:
            if date < since: continue
            d[(code, date)] = (market, nxt, float(ret) if ret else None, float(gap) if gap else None, float(cc) if cc else None)
    return d


def chain(daily, code, date, k):
    """date 종가 대비 k거래일 뒤 종가 배수."""
    m = 1.0; cur = date
    for _ in range(k):
        row = daily.get((code, cur))
        if not row or row[4] is None or not row[1]: return None
        m *= 1 + row[4] / 100; cur = row[1]
    return m


def netr(entry, exit_):
    return exit_ * (1 - SELL) / (entry * (1 + BUY)) - 1


def bucket(x, edges, labels):
    for e, l in zip(edges, labels):
        if x < e: return l
    return labels[-1]


RET_E = [-0.05, -0.02, 0, 0.02, 0.05, 0.10, 0.20]
RET_L = ['<-5', '-5~-2', '-2~0', '0~2', '2~5', '5~10', '10~20', '>=20']
GAP_L = RET_L
RVOL_E = [1, 2, 5, 10]; RVOL_L = ['<1', '1~2', '2~5', '5~10', '>=10']
DRIFT_E = [-0.03, -0.01, 0.01, 0.03]; DRIFT_L = ['<-3', '-3~-1', '-1~1', '1~3', '>=3']  # 시가 -> 09:10 가격
PREV_E = [-0.05, 0, 0.05, 0.15]; PREV_L = ['<-5', '-5~0', '0~5', '5~15', '>=15']
TV_E = [3e9, 1e10, 5e10]; TV_L = ['10~30억', '30~100억', '100~500억', '>=500억']
MKT_E = [-0.005, 0.005]; MKT_L = ['약세', '보합', '강세']  # 그날 대상 전체 RET10 중앙값


def build(data, daily):
    feats = features(data)
    days = data['days']
    rows = []
    for d, fr in feats.items():
        if not fr: continue
        ds = d.isoformat()
        med = sorted(r['ret10'] for r in fr)[len(fr) // 2]
        mkt = bucket(med, MKT_E, MKT_L)
        prevd = days[days.index(d) - 1].isoformat()
        for r in fr:
            eo = r['r']['eo']
            if eo is None: continue
            eo = float(eo)
            if eo >= r['pc'] * 1.29: continue
            close = float(r['r']['cday'])
            drow = daily.get((r['code'], ds))
            prow = daily.get((r['code'], prevd))
            o = {'D0': netr(eo, close)}
            if drow and drow[3] is not None:
                o['N1'] = netr(eo, close * (1 + drow[3] / 100))
                o['OV'] = netr(close, close * (1 + drow[3] / 100))
            for k, h in ((1, 'C1'), (3, 'C3'), (5, 'C5')):
                m = chain(daily, r['code'], ds, k)
                if m is not None: o[h] = netr(eo, close * m)
            pv = prow[2] / 100 if prow and prow[2] is not None else None
            rows.append(dict(
                d=d, o=o,
                ret10=bucket(r['ret10'], RET_E, RET_L), gap=bucket(r['gap'], RET_E, GAP_L),
                rvol=bucket(r['rvol'], RVOL_E, RVOL_L), drift=bucket(r['P'] / r['open'] - 1, DRIFT_E, DRIFT_L),
                vwap='P>VWAP' if r['P'] > r['vwap'] else 'P<=VWAP',
                prev=bucket(pv, PREV_E, PREV_L) if pv is not None else '?',
                tv=bucket(float(data['day'][days[days.index(d) - 1]][r['sid']]['tvday']), TV_E, TV_L),
                mkt=mkt, market=drow[0] if drow else '?',
                rise='상승' if r['ret10'] >= 0.02 else '기타'))
    return rows


def daymeans(rows, h):
    s = defaultdict(list)
    for r in rows:
        v = r['o'].get(h)
        if v is not None: s[r['d']].append(v)
    return {d: sum(v) / len(v) for d, v in s.items()}


def summarize(cell, base, h):
    cm = daymeans(cell, h)
    out = {}
    for name, f in (('A', lambda d: d < SPLIT), ('B', lambda d: d >= SPLIT), ('ALL', lambda d: True)):
        ds = [d for d in cm if f(d)]
        if not ds: out[name] = None; continue
        x = [cm[d] for d in ds]; e = [cm[d] - base[h][d] for d in ds]
        n = len(ds); me = sum(e) / n
        sd = math.sqrt(sum((v - me) ** 2 for v in e) / (n - 1)) if n > 1 else float('nan')
        out[name] = dict(days=n, net=sum(x) / n, exc=me, t=me / sd * math.sqrt(n) if sd and sd > 0 else float('nan'),
                         win=sum(v > 0 for v in x) / n)
    return out


def pct(v): return f'{v * 100:+.2f}' if v is not None and not math.isnan(v) else '  -  '


def main(pkl, daily_path):
    data = pickle.load(open(pkl, 'rb'))
    daily = load_daily(daily_path, '2025-10-01')
    rows = build(data, daily)
    base = {h: daymeans(rows, h) for h in HZ}
    print(f'대상 종목-일 {len(rows):,}, 거래일 {len({r["d"] for r in rows})}, 기간 {min(r["d"] for r in rows)} ~ {max(r["d"] for r in rows)}')
    for h in HZ:
        print(f'  {h} 결측 {sum(1 for r in rows if h not in r["o"]):,}')

    def table(title, key_fn):
        cells = defaultdict(list)
        for r in rows: cells[key_fn(r)].append(r)
        print(f'\n## {title}\n')
        print('칸 | 종목-일 | 일수 | ' + ' | '.join(f'{h} net(A/B)' for h in HZ) + ' | ' + ' | '.join(f'{h} 초과(A/B)' for h in HZ))
        res = []
        for k in sorted(cells, key=str):
            s = {h: summarize(cells[k], base, h) for h in HZ}
            line = f'{k} | {len(cells[k]):,} | {s["D0"]["ALL"]["days"] if s["D0"]["ALL"] else 0} | '
            line += ' | '.join(f'{pct(s[h]["A"]["net"]) if s[h]["A"] else "-"}/{pct(s[h]["B"]["net"]) if s[h]["B"] else "-"}' for h in HZ)
            line += ' | ' + ' | '.join(f'{pct(s[h]["A"]["exc"]) if s[h]["A"] else "-"}/{pct(s[h]["B"]["exc"]) if s[h]["B"] else "-"}' for h in HZ)
            print(line)
            res.append((title, k, len(cells[k]), s))
        return res

    print('\n## 대상 전체 (CTRL)\n')
    for h in HZ:
        for name, f in (('A', lambda d: d < SPLIT), ('B', lambda d: d >= SPLIT)):
            x = [v for d, v in base[h].items() if f(d)]
            print(f'{h} {name}: 일수 {len(x)}, 하루 평균 net {pct(sum(x) / len(x))}, 플러스 날 {sum(v > 0 for v in x) / len(x):.0%}')

    allres = []
    allres += table('RET10 (09:10 가격 / 전일 종가)', lambda r: r['ret10'])
    allres += table('GAP (시가 / 전일 종가)', lambda r: r['gap'])
    allres += table('시가 -> 09:10 움직임', lambda r: r['drift'])
    allres += table('RVOL10', lambda r: r['rvol'])
    allres += table('VWAP 위치', lambda r: r['vwap'])
    allres += table('전날 등락', lambda r: r['prev'])
    allres += table('전날 거래대금', lambda r: r['tv'])
    allres += table('시장 (그날 RET10 중앙값)', lambda r: r['mkt'])
    allres += table('시장 구분', lambda r: r['market'])
    up = [r for r in rows if r['rise'] == '상승']
    allres += table('RET10 x RVOL', lambda r: (r['ret10'], r['rvol']))
    allres += table('RET10 x 시가->09:10', lambda r: (r['ret10'], r['drift']))
    allres += table('RET10 x 시장', lambda r: (r['ret10'], r['mkt']))
    allres += table('RET10 x 전날 등락', lambda r: (r['ret10'], r['prev']))
    allres += table('RET10 x 전날 거래대금', lambda r: (r['ret10'], r['tv']))
    allres += table('RET10 x 시장 구분', lambda r: (r['ret10'], r['market']))
    allres += table('GAP x 시가->09:10', lambda r: (r['gap'], r['drift']))
    _ = up

    print('\n## 두 기간 모두 Net > 0, 초과 > 0, 각 기간 40일 이상인 칸 (보유 기간별, B 기간 Net 순)\n')
    for h in HZ:
        c = []
        for title, k, n, s in allres:
            a, b = s[h]['A'], s[h]['B']
            if a and b and a['days'] >= 40 and b['days'] >= 40 and a['net'] > 0 and b['net'] > 0 and a['exc'] > 0 and b['exc'] > 0:
                c.append((b['net'], title, k, n, a, b))
        c.sort(key=lambda x: -min(x[4]['net'], x[5]['net']))
        print(f'### {h}: {len(c)}칸')
        for _, title, k, n, a, b in c[:15]:
            print(f'  {title} {k}: 종목-일 {n:,} | A {a["days"]}일 net {pct(a["net"])} 초과 {pct(a["exc"])} t {a["t"]:.1f} 승 {a["win"]:.0%}'
                  f' | B {b["days"]}일 net {pct(b["net"])} 초과 {pct(b["exc"])} t {b["t"]:.1f} 승 {b["win"]:.0%}')
    print(f'\n검사한 칸 수: {len(allres)} x 보유 기간 {len(HZ)} = {len(allres) * len(HZ)}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
