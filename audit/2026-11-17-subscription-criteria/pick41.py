"""사후 점검 2 (결과를 본 뒤 추가): 명단(변동성 300 + 당일 등락 상위 100) 안에서 실시간 구독 41자리를 무엇으로 채울지.

순서 기준 (당일 값은 일봉 종가 값으로 근사):
  BROAD  지금 Broad 시세 점수 근사: min(등락률%×12, 45, 음수는 0) + min(거래대금/1억, 35) + 종가 위치×20
  UP     당일 등락률
  ABS    당일 |등락률|
  VOL    20일 변동성
  TVR    당일 거래대금 / 20일 평균
  VOLUP  VOL 순위와 UP 순위의 평균
  RAND   무작위
지표: 목표(다음 날 시가 상위 20) 중 41자리에 든 비율, 상한가 마감 제외 비율, 41종목 평균 다음 날 시가 Net.

사용: python -X utf8 pick41.py <stock_days.csv>
"""
import sys
import numpy as np
import pandas as pd
from criteria import load


def main(path):
    d = load(path)
    d['UP'] = d['ret']; d['ABS'] = d['ret'].abs(); d['TVR'] = d['tv'] / d['TV20']; d['VOL'] = d['VOL20']
    d['BROAD'] = d['ret'].clip(lower=0).mul(12).clip(upper=45) + (d['tv'] / 1e8).clip(upper=35) + d['loc'].fillna(0) * 20
    rng = np.random.default_rng(7); d['RAND'] = rng.random(len(d))
    keys = ['BROAD', 'UP', 'ABS', 'VOL', 'TVR', 'VOLUP', 'RAND']
    rows = []
    for date, x in d.groupby('date'):
        if len(x) < 400: continue
        lst = set(x[x.VOL20.notna()].nlargest(300, 'VOL20').index) | set(x.nlargest(100, 'UP').index)
        y = x.loc[list(lst)].copy()
        y['VOLUP'] = (y.VOL.rank(pct=True) + y.UP.rank(pct=True)) / 2
        top20 = set(x.nlargest(20, 'gap').index)
        top20n = set(x[x.ret < 29].nlargest(20, 'gap').index)
        for k in keys:
            s = y[y[k].notna()].nlargest(41, k)
            idx = set(s.index)
            rows.append((x.P.iloc[0], k, len(top20 & idx) / 20, len(top20n & idx) / 20, s.ov.mean(), s[s.ret < 29].ov.mean()))
    r = pd.DataFrame(rows, columns=['P', 'key', 'rec', 'recn', 'ov', 'ovn'])
    t = r.groupby(['key', 'P']).mean()
    print('41자리 순서 | ' + ' | '.join(f'{p} recall20 / 상한가 제외 recall20 / 41 평균 OV / 상한가 제외 OV' for p in ('P1', 'P2', 'P3')))
    for k in keys:
        print(f'{k} | ' + ' | '.join(
            f'{t.loc[(k, p), "rec"] * 100:.1f}% / {t.loc[(k, p), "recn"] * 100:.1f}% / {t.loc[(k, p), "ov"]:+.2f}% / {t.loc[(k, p), "ovn"]:+.2f}%'
            for p in ('P1', 'P2', 'P3')))


if __name__ == '__main__':
    main(sys.argv[1])
