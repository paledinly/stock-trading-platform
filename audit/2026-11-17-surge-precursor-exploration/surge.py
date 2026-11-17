"""급등 전조 탐색 (2026-11-17). 가설을 찾는 단계이며 판정이 아니다.

질문: 급등이 일어나기 전날 장 마감까지 알 수 있는 정보로 다음 날 급등을 미리 알 수 있는가? 안다면 그 종목을 사서 비용을 넘는가?

데이터: 일봉 파일 stock_days.csv (수정주가 일봉 + 상장폐지 이력, ETF·ETN 제외, 직전 20일 평균 거래대금 10억 이상인 날만).
  열: code, market, date, next, ret(당일 종가/전일 종가-1, %), loc(종가의 당일 고저 범위 위치), gap(다음 날 시가/당일 종가-1, %),
      cc(다음 날 종가/당일 종가-1, %), tv(거래대금)
  지수: index_days.csv (kospi, kosdaq 당일 등락 %, *_loc).
대상: 6자리 숫자 코드, 끝자리 0(우선주 제외). 이름 정보가 없어 스팩은 거르지 못한다.
급등(목표): 다음 날 종가 기준 +10% 이상(cc >= 10). 보조로 다음 날 시가 +5% 이상(gap >= 5)도 본다.
특징: 모두 당일 장 마감까지 알 수 있는 값. 연속 이력이 끊긴 행(거래대금 기준 탈락 후 복귀)은 직전 20행이 이어진 경우만 쓴다.
수익: 당일 종가 매수 기준. OV = 다음 날 시가 매도, CC = 다음 날 종가 매도. BASE 왕복 0.39% 차감.
기간: P1 2018-2021(모델 학습), P2 2022-2024, P3 2025-01 ~ 2026-11-11. P2·P3는 학습에 쓰지 않는다.

사용: python -X utf8 surge.py <stock_days.csv> <index_days.csv> > result.txt
"""
import sys
import numpy as np
import pandas as pd

COST = 0.39
FEATS = ['r1', 'intra', 'gap0', 'loc', 'r5', 'r20', 'r60', 'tvr1', 'tvr5', 'vol20', 'dd60', 'up60', 'surge20', 'surge60',
         'streak', 'ltv20', 'kosdaq', 'mkt_r', 'mkt_loc']
NAMES = {'r1': '당일 등락', 'intra': '당일 시가→종가', 'gap0': '당일 갭', 'loc': '종가 위치(고저 범위)', 'r5': '5일 등락',
         'r20': '20일 등락', 'r60': '60일 등락', 'tvr1': '당일 거래대금/20일 평균', 'tvr5': '5일 거래대금/20일 평균',
         'vol20': '20일 변동성', 'dd60': '60일 고점 대비', 'up60': '60일 저점 대비', 'surge20': '20일 내 +10% 일수',
         'surge60': '60일 내 +10% 일수', 'streak': '연속 상승일', 'ltv20': '20일 평균 거래대금(log)', 'kosdaq': '코스닥',
         'mkt_r': '시장 당일 등락', 'mkt_loc': '시장 종가 위치'}


def period(d):
    return np.where(d < '2022-01-01', 'P1', np.where(d < '2025-01-01', 'P2', 'P3'))


def load(sd, idx):
    d = pd.read_csv(sd, dtype={'code': str})
    d = d[d.code.str.fullmatch(r'\d{5}0')].copy()
    d = d.sort_values(['code', 'date']).reset_index(drop=True)
    g = d.groupby('code', sort=False)
    d['contig'] = (g['next'].shift(1) == d['date']).astype(int)
    d['run'] = g['contig'].transform(lambda s: s.groupby((s == 0).cumsum()).cumcount())
    d['gap0'] = g['gap'].shift(1).where(d.contig == 1)  # 당일 시가 갭 = 전날 행의 다음 날 갭
    d['r1'] = d.ret
    d['intra'] = ((1 + d.ret / 100) / (1 + d.gap0 / 100) - 1) * 100
    lr = np.log1p(d.ret / 100)
    d['px'] = g['ret'].transform(lambda s: np.log1p(s / 100).cumsum())
    g = d.groupby('code', sort=False)
    for n in (5, 20, 60):
        d[f'r{n}'] = (np.exp(d.px - g['px'].shift(n)) - 1) * 100
    d['tv20'] = g['tv'].transform(lambda s: s.shift(1).rolling(20).mean())
    d['tvr1'] = d.tv / d.tv20
    d['tvr5'] = g['tv'].transform(lambda s: s.rolling(5).mean()) / d.tv20
    d['vol20'] = g['ret'].transform(lambda s: s.rolling(20).std())
    d['dd60'] = (np.exp(d.px - g['px'].transform(lambda s: s.rolling(60).max())) - 1) * 100
    d['up60'] = (np.exp(d.px - g['px'].transform(lambda s: s.rolling(60).min())) - 1) * 100
    big = (d.ret >= 10).astype(int)
    d['surge20'] = big.groupby(d.code).transform(lambda s: s.shift(1).rolling(20).sum())
    d['surge60'] = big.groupby(d.code).transform(lambda s: s.shift(1).rolling(60).sum())
    up = (d.ret > 0).astype(int)
    d['streak'] = up.groupby([d.code, (up == 0).cumsum()]).cumsum()
    d['ltv20'] = np.log10(d.tv20)
    d['kosdaq'] = (d.market == 'KOSDAQ').astype(int)
    ix = pd.read_csv(idx)
    d = d.merge(ix[['date', 'kospi', 'kosdaq', 'kospi_loc', 'kosdaq_loc']].rename(columns={'kosdaq': 'kq'}), on='date', how='left')
    d['mkt_r'] = np.where(d.kosdaq == 1, d.kq, d.kospi)
    d['mkt_loc'] = np.where(d.kosdaq == 1, d.kosdaq_loc, d.kospi_loc)
    d = d[(d.run >= 60) & d.cc.notna() & d.gap.notna()].copy()
    d['y'] = (d.cc >= 10).astype(int)
    d['y_gap'] = (d.gap >= 5).astype(int)
    d['ov'] = ((1 + d.gap / 100) * (1 - 0.00295) / (1 + 0.00095) - 1) * 100
    d['ccn'] = ((1 + d.cc / 100) * (1 - 0.00295) / (1 + 0.00095) - 1) * 100
    d['P'] = period(d.date.values)
    _ = lr
    return d


def daymean(x, col):
    return x.groupby('date')[col].mean().mean()


def deciles(d):
    print('\n## 특징별 10분위 (분위 경계는 각 날짜 안에서 정한다)\n')
    print('급등률 = 다음 날 종가 +10% 이상 비율. OV/CC = 당일 종가 매수 후 다음 날 시가/종가 매도 Net 하루 평균(%).')
    for f in FEATS:
        if f == 'kosdaq': continue
        x = d[['date', 'P', f, 'y', 'ov', 'ccn']].dropna().copy()
        if f in ('mkt_r', 'mkt_loc'):  # 날짜 단위 값은 전체 기간 분위로 나눈다
            x['q'] = pd.qcut(x[f].rank(method='first'), 10, labels=False)
        else:
            x['q'] = x.groupby('date')[f].transform(lambda s: pd.qcut(s.rank(method='first'), 10, labels=False) if len(s) >= 10 else np.nan)
        x = x.dropna(subset=['q'])
        print(f'\n### {NAMES[f]} ({f})\n')
        print('분위 | 중앙값 | ' + ' | '.join(f'{p} 급등률 / OV / CC' for p in ('P1', 'P2', 'P3')))
        for q in range(10):
            xq = x[x.q == q]
            cells = []
            for p in ('P1', 'P2', 'P3'):
                xp = xq[xq.P == p]
                cells.append(f'{xp.y.mean() * 100:.2f}% / {daymean(xp, "ov"):+.2f} / {daymean(xp, "ccn"):+.2f}' if len(xp) else '-')
            print(f'{q + 1} | {xq[f].median():.2f} | ' + ' | '.join(cells))


def model(d):
    import lightgbm as lgb
    from sklearn.metrics import roc_auc_score
    print('\n## 모델 (P1로만 학습, P2·P3는 처음 보는 기간)\n')
    tr = d[d.P == 'P1']
    m = lgb.LGBMClassifier(n_estimators=300, learning_rate=0.03, num_leaves=31, min_child_samples=500, subsample=0.8,
                           subsample_freq=1, colsample_bytree=0.8, random_state=20261117, verbose=-1)
    m.fit(tr[FEATS], tr.y)
    d = d.copy(); d['s'] = m.predict_proba(d[FEATS])[:, 1]
    imp = sorted(zip(m.booster_.feature_importance('gain'), FEATS), reverse=True)
    print('중요도(gain 상위 8): ' + ', '.join(f'{NAMES[f]}' for _, f in imp[:8]))
    print('\n기간 | 행 | 기본 급등률 | AUC | 하루 상위 10 급등률 | 상위 10 OV | 상위 10 CC | 하위 50% OV | 전체 OV | 전체 CC | 상위 10 다음 날 −10% 이하 비율')
    for p in ('P1', 'P2', 'P3'):
        x = d[d.P == p]
        top = x.sort_values('s', ascending=False).groupby('date').head(10)
        bot = x[x.groupby('date')['s'].rank(pct=True) <= 0.5]
        print(f'{p}{"(학습)" if p == "P1" else ""} | {len(x):,} | {x.y.mean() * 100:.2f}% | {roc_auc_score(x.y, x.s):.3f} | '
              f'{top.y.mean() * 100:.2f}% | {daymean(top, "ov"):+.2f} | {daymean(top, "ccn"):+.2f} | {daymean(bot, "ov"):+.2f} | '
              f'{daymean(x, "ov"):+.2f} | {daymean(x, "ccn"):+.2f} | {(top.cc <= -10).mean() * 100:.2f}%')
    print('\n하루 상위 10의 다음 날 종가 등락 분포 (P2+P3, Gross %)')
    x = d[d.P != 'P1']; top = x.sort_values('s', ascending=False).groupby('date').head(10)
    print(top.cc.describe(percentiles=[.05, .25, .5, .75, .95]).round(2).to_string())
    print('\n상한가 마감 종목(당일 +29% 이상, 종가 매수가 어렵다)을 뺀 상위 10')
    print('기간 | 상위 10 중 상한가 비율 | 상한가 제외 급등률 | 상한가 제외 OV | 상한가 제외 CC')
    for p in ('P1', 'P2', 'P3'):
        x = d[(d.P == p)]
        top = x.sort_values('s', ascending=False).groupby('date').head(10)
        lim = top.r1 >= 29
        x2 = x[x.r1 < 29]; top2 = x2.sort_values('s', ascending=False).groupby('date').head(10)
        print(f'{p} | {lim.mean() * 100:.1f}% | {top2.y.mean() * 100:.2f}% | {daymean(top2, "ov"):+.2f} | {daymean(top2, "ccn"):+.2f}')
    # 연도별
    print('\n연도 | 상위 10 급등률 | 기본 급등률 | 상위 10 OV | 상위 10 CC | 전체 OV')
    d['yr'] = d.date.str[:4]
    for yr, x in d.groupby('yr'):
        top = x.sort_values('s', ascending=False).groupby('date').head(10)
        print(f'{yr} | {top.y.mean() * 100:.2f}% | {x.y.mean() * 100:.2f}% | {daymean(top, "ov"):+.2f} | {daymean(top, "ccn"):+.2f} | {daymean(x, "ov"):+.2f}')
    return d


def surge_profile(d):
    print('\n## 급등 전날 모습 (급등일 전날 특징 중앙값 vs 전체, P1~P3 합)\n')
    print('특징 | 급등 전날 | 전체 | 급등 전날 상위 10% 이상 비율')
    for f in FEATS:
        a = d.loc[d.y == 1, f].dropna(); b = d[f].dropna()
        hi = (a >= b.quantile(0.9)).mean() * 100
        print(f'{NAMES[f]} | {a.median():.2f} | {b.median():.2f} | {hi:.0f}%')
    print('\n급등일 다음 날(급등 다음 거래일) 성과: 급등 당일 종가 매수 기준은 다른 연구 범위라 다루지 않는다.')


def main(sd, idx):
    d = load(sd, idx)
    print(f'행 {len(d):,}, 종목 {d.code.nunique():,}, 기간 {d.date.min()} ~ {d.date.max()}')
    for p in ('P1', 'P2', 'P3'):
        x = d[d.P == p]
        print(f'{p}: 행 {len(x):,}, 거래일 {x.date.nunique()}, 다음 날 +10% 급등률 {x.y.mean() * 100:.2f}%, '
              f'다음 날 시가 +5% 비율 {x.y_gap.mean() * 100:.2f}%, 전체 OV {daymean(x, "ov"):+.2f}, CC {daymean(x, "ccn"):+.2f}')
    surge_profile(d)
    deciles(d)
    model(d)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
