"""사후 점검 (결과를 본 뒤 추가): 장 전 명단 300에 장중 정보로 고른 종목을 더하면 목표를 얼마나 더 담는가.

장중 정보는 일봉의 당일 값(ret = 당일 종가 등락, tv = 당일 거래대금)으로 근사한다. 실제 운영에서는 14:50 무렵 값이라
종가 값보다 조금 덜 정확하다(그래서 결과는 상한에 가깝다).
조합 (모두 최대 41 + 추가분이 아니라, 명단 크기 자체를 비교):
  MIX300             장 전 명단만
  MIX300 + UP100     + 당일 등락 상위 100 (명단에 없던 것만 더함)
  MIX300 + TVR100    + 당일 거래대금/20일 평균 상위 100
  MIX300 + ABS100    + 당일 |등락| 상위 100
  VOL300 + UP100     변동성 명단 + 당일 등락 상위 100
  UP400              당일 등락 상위 400만 (장 전 명단 없이)
상한가 마감 종목(당일 +29% 이상)은 종가에 사기 어렵다. 목표에서 뺀 recall도 함께 본다.

사용: python -X utf8 intraday_add.py <stock_days.csv>
"""
import sys
import numpy as np
import pandas as pd
from criteria import load


def main(path):
    d = load(path)
    d['UP'] = d['ret']
    d['ABS'] = d['ret'].abs()
    d['TVRD'] = d['tv'] / d['TV20']
    combos = {
        'MIX300': [('MIX', 300)],
        'MIX300+UP100': [('MIX', 300), ('UP', 100)],
        'MIX300+TVR100': [('MIX', 300), ('TVRD', 100)],
        'MIX300+ABS100': [('MIX', 300), ('ABS', 100)],
        'VOL300+UP100': [('VOL20', 300), ('UP', 100)],
        'UP400': [('UP', 400)],
    }
    rows = []
    for date, x in d.groupby('date'):
        if len(x) < 400: continue
        top20 = set(x.nlargest(20, 'gap').index)
        nolim = x[x.ret < 29]
        top20n = set(nolim.nlargest(20, 'gap').index)
        for name, parts in combos.items():
            s = set()
            for col, k in parts:
                s |= set(x[x[col].notna()].nlargest(k, col).index)
            ov = x.loc[list(s), 'ov'].mean()
            rows.append((x.P.iloc[0], name, len(s), len(top20 & s) / 20, len(top20n & s) / 20, ov))
    r = pd.DataFrame(rows, columns=['P', 'combo', 'size', 'rec20', 'rec20n', 'ov'])
    t = r.groupby(['combo', 'P']).mean()
    print('조합 | 평균 명단 크기 | ' + ' | '.join(f'{p} recall20 / 상한가 제외 recall20 / 명단 OV' for p in ('P1', 'P2', 'P3')))
    for c in combos:
        cells = [f'{t.loc[(c, p), "rec20"] * 100:.1f}% / {t.loc[(c, p), "rec20n"] * 100:.1f}% / {t.loc[(c, p), "ov"]:+.2f}%' for p in ('P1', 'P2', 'P3')]
        print(f'{c} | {r[r.combo == c]["size"].mean():.0f} | ' + ' | '.join(cells))


if __name__ == '__main__':
    main(sys.argv[1])
