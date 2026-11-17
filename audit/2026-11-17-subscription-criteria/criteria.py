"""구독 종목 기준 탐색 (2026-11-17). 순위표 대신 장 시작 전에 정할 수 있는 기준으로 명단을 만들면
"그날 종가에 사서 다음 날 시가에 팔 만한 종목"을 얼마나 담는지 비교한다. 정의는 첫 실행 전에 고정했다.

배경: 구독 재생 검증(audit/2026-11-03-subscription-replay)에서 순위표 방식은 전 종목 ML 상위 5 중 27%만 담았고,
  장 전 명단(최근 20일 갭업 비율 상위 300)이 더 나았다(BETTER). 다만 그 기준은 같은 1년 데이터로 골랐다.
  여기서는 2018 ~ 2026 일봉으로 여러 기준을 같은 조건에서 비교한다.

데이터: stock_days.csv (수정주가 일봉 + 상장폐지 이력, ETF·ETN 제외, 직전 20일 평균 거래대금 10억 이상인 날).
대상: 6자리 숫자 코드, 끝자리 0. 날 d의 전 종목.
목표(그날 놓치면 안 되는 종목): 날 d 종가 -> d+1 시가 등락(gap_d) 상위 20종목, 그리고 상위 5%.
  이 값은 d 장 마감 뒤에야 알 수 있다. 명단은 d 장 시작 전에 정한다.
장 시작 전에 아는 값: d-1 종가까지의 일봉, 그리고 d-1 시가까지의 갭(행 d-2의 gap). 행 d-1의 gap은 d 시가라 쓰지 않는다.
기준 (값이 큰 순서로 K종목, K = 150, 300):
  G20   최근 20개 갭(행 d-21 ~ d-2의 gap) 중 양수 비율        <- 지금 후보(장 전 명단)
  GM20  최근 20개 갭 평균
  GM60  최근 60개 갭 평균
  VOL20 최근 20일 등락 표준편차(행 d-20 ~ d-1의 ret)
  TV20  최근 20일 평균 거래대금
  TVR   전날 거래대금 / 최근 20일 평균
  R1    전날 등락
  ABS1  |전날 등락|
  MIX   G20 순위 + VOL20 순위 평균 (사전에 정한 조합 하나)
  RAND  무작위 K (seed 20261117)
지표 (기간별 하루 평균):
  recall20  목표 상위 20 중 명단에 든 비율
  recall5p  목표 상위 5% 중 명단에 든 비율
  명단 평균 OV: 명단 전체를 종가 매수 -> 다음 날 시가 매도 했을 때 Net(BASE 0.39%)
기간: P1 2018-2021, P2 2022-2024, P3 2025-01 ~ 2026-11.
남기는 기준: 세 기간 모두 recall20이 RAND와 G20보다 높은 기준. 없으면 G20 유지 근거로 본다.

사용: python -X utf8 criteria.py <stock_days.csv> > result.txt
"""
import sys
import numpy as np
import pandas as pd

KS = (150, 300)
CRIT = ['G20', 'GM20', 'GM60', 'VOL20', 'TV20', 'TVR', 'R1', 'ABS1', 'MIX', 'RAND']


def load(path):
    d = pd.read_csv(path, dtype={'code': str})
    d = d[d.code.str.fullmatch(r'\d{5}0')].sort_values(['code', 'date']).reset_index(drop=True)
    g = d.groupby('code', sort=False)
    gap_known = g['gap'].shift(2)  # 날 d 장 전에 아는 가장 최근 갭 = 행 d-2의 gap (d-1 시가)
    d['G20'] = gap_known.gt(0).astype(float).where(gap_known.notna()).groupby(d.code).transform(lambda s: s.rolling(20, min_periods=15).mean())
    d['GM20'] = gap_known.groupby(d.code).transform(lambda s: s.rolling(20, min_periods=15).mean())
    d['GM60'] = gap_known.groupby(d.code).transform(lambda s: s.rolling(60, min_periods=40).mean())
    r_prev = g['ret'].shift(1)
    d['VOL20'] = r_prev.groupby(d.code).transform(lambda s: s.rolling(20, min_periods=15).std())
    tv_prev = g['tv'].shift(1)
    d['TV20'] = tv_prev.groupby(d.code).transform(lambda s: s.rolling(20, min_periods=15).mean())
    d['TVR'] = tv_prev / d['TV20']
    d['R1'] = r_prev
    d['ABS1'] = r_prev.abs()
    # 연속성: 전날 행이 바로 앞 거래일이어야 전날 값으로 쓴다
    contig = g['next'].shift(1) == d['date']
    for c in ('VOL20', 'TV20', 'TVR', 'R1', 'ABS1', 'G20', 'GM20', 'GM60'):
        d.loc[~contig, c] = np.nan
    d = d[d.gap.notna()].copy()
    rk = d.groupby('date')
    d['MIX'] = (rk['G20'].rank(pct=True) + rk['VOL20'].rank(pct=True)) / 2
    rng = np.random.default_rng(20261117)
    d['RAND'] = rng.random(len(d))
    d['ov'] = ((1 + d.gap / 100) * (1 - 0.00295) / (1 + 0.00095) - 1) * 100
    d['P'] = np.where(d.date < '2022-01-01', 'P1', np.where(d.date < '2025-01-01', 'P2', 'P3'))
    return d


def evaluate(d):
    rows = []
    for date, x in d.groupby('date'):
        n = len(x)
        if n < 400: continue
        top20 = set(x.nlargest(20, 'gap').index)
        top5p = set(x.nlargest(max(1, int(n * 0.05)), 'gap').index)
        for c in CRIT:
            xs = x[x[c].notna()]
            for k in KS:
                sel = xs.nlargest(k, c)
                s = set(sel.index)
                rows.append((date, x.P.iloc[0], c, k, n, len(top20 & s) / 20, len(top5p & s) / len(top5p), sel.ov.mean()))
    return pd.DataFrame(rows, columns=['date', 'P', 'crit', 'k', 'n', 'rec20', 'rec5p', 'ov'])


def main(path):
    d = load(path)
    print(f'행 {len(d):,}, 기간 {d.date.min()} ~ {d.date.max()}, 하루 평균 대상 {d.groupby("date").size().mean():.0f}종목')
    r = evaluate(d)
    print(f'평가일 {r.date.nunique()}')
    for k in KS:
        print(f'\n## 명단 {k}종목\n')
        print('기준 | ' + ' | '.join(f'{p} recall20 / recall5% / 명단 OV' for p in ('P1', 'P2', 'P3')))
        t = r[r.k == k].groupby(['crit', 'P'])[['rec20', 'rec5p', 'ov']].mean()
        for c in CRIT:
            cells = [f'{t.loc[(c, p), "rec20"] * 100:.1f}% / {t.loc[(c, p), "rec5p"] * 100:.1f}% / {t.loc[(c, p), "ov"]:+.2f}%' for p in ('P1', 'P2', 'P3')]
            print(f'{c} | ' + ' | '.join(cells))
        keep = []
        for c in CRIT:
            if c in ('G20', 'RAND'): continue
            if all(t.loc[(c, p), 'rec20'] > max(t.loc[('G20', p), 'rec20'], t.loc[('RAND', p), 'rec20']) for p in ('P1', 'P2', 'P3')):
                keep.append(c)
        print(f'\nG20·RAND보다 세 기간 모두 recall20이 높은 기준: {", ".join(keep) if keep else "없음"}')
    print(f'\n(참고) 하루 평균 대상 종목 수 대비 명단 비율: 150 = {150 / d.groupby("date").size().mean():.0%}, 300 = {300 / d.groupby("date").size().mean():.0%}')


if __name__ == '__main__':
    main(sys.argv[1])
