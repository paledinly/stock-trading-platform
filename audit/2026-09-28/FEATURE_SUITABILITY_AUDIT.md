# 15시 마감 매수 후보 평가정보 적합성 감사

감사일 2026-09-28, Asia/Seoul. 대상은 현재 `closing-recommend-v8-1500`의 실제 호출 경로와 보존된 추천·성과 데이터다. 애플리케이션·설정·운영 DB는 변경하지 않았다. 작업 중 이미 수정되어 있던 WatchlistService 및 해당 테스트도 변경하지 않았다. 감사 파일만 추가했다.

**결론: 현재 정보는 ‘장중 가격 강도·거래 활성도·일봉 추세’에 따른 관찰 후보 선정에는 논리가 있다. 그러나 ‘오늘 매수해서 익일 비용 차감 후 수익을 낼 종목’을 구분하는 데 유효하다는 실증 근거는 없다.** 현재 공식 완료 표본 N=0이며, 입력봉 오류·시간 재현성·거래일 달력·성과 관측 결함이 검증을 막는다. Feature를 늘리거나 Weight를 최적화하기 전에 이 연결을 복구해야 한다.

본 문서에서 **필요성**은 전략상 측정 목적, **사용됨**은 실행 경로 연결, **효과 입증**은 유효한 미사용 데이터의 성과 개선을 뜻한다. 세 가지를 구분했다. KEEP도 운영 안전에 필요한 정보라는 뜻일 수 있으며, 상승 예측력이 검증됐다는 뜻이 아니다.

근거 자료: [전체 시스템 감사](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/QUANT_STRATEGY_AUDIT.md), [Feature별 전체 통계](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/FEATURE_BUCKETS.md), [계산 결과 JSON](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/feature-buckets.json), [조회 원본](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/detail.tsv), [14:14 KST 공식 분석 API](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/feature-audit-analytics.json). 오늘 15시 전 조회이므로 오늘 추천을 사후 평가한 보고서가 아니다.

## 1. 현재 마감 후보 선정 구조

### 실제 실행 경로

`KIS 체결 → MarketDataService → IntradayFeatureState / QuoteState / 5분봉 집계 → ScannerEngine → ScannerEvaluator → OpportunityRiskScorer → ScannerDetection 저장 → ClosingRecommendationService → ClosingPrecisionEvaluator → 분봉·일봉 MA → ClosingRecommendationScorer → 순위 → 최대 1개 추천 및 전체 평가 Snapshot`

| 단계 | 실제 사용하는 정보·조건 | 해석 |
|---|---|---|
| 최초 Universe | 종목 마스터 4,393개가 있으나 실시간 Scanner는 구독·수신 종목만 평가 | 전 시장 전 종목 동등 탐색이 아님. 보존 조회의 실시간 5분봉은 49종목 |
| Broad 탐색 | 거래대금·거래량·등락·체결강도·고가근접 순위와 시세 | 별도 WATCH 경로. Broad 점수가 높아도 직접 최종 추천되지 않음 |
| 구독 확장 | precision 자동 확장 설정 false, 세션 0건 | Broad에만 있는 좋은 후보가 정밀 후보로 자동 연결됐다고 볼 수 없음 |
| Scanner 제외 | active, 관리/정지 여부, ETF 설정, 과거 6봉 및 유효 분모 | 최신 종목 마스터 상태에 의존 |
| Scanner 유동성 gate | 최소가격·5분 거래대금·누적 거래대금 | 저장된 9개 설정은 이 세 최소값이 0. 실질적 유동성 제외는 뒤 단계에서 수행 |
| 이벤트 탐지 | 아래 9종 조건, 조건 진입·재진입 및 cooldown | 모든 종목의 매 시점 재평가 Snapshot과 다름 |
| 대표 신호 | 14:30~15:00, 종목별 **가장 최근** 탐지 1건 | 최고점 신호를 고르는 것이 아님. 마지막 이벤트 종류·시각에 좌우 |
| 1차 gate | Opportunity≥35, Risk≤65; 필수 Feature, 수신시각, 신호 나이≤30분 | 최종점수 55와 다른 문턱 |
| 데이터 gate | 탐지 전 연속 확정봉≥4·20분, 일봉≥21, 직전 거래일 일봉 | 지연 수정봉·달력 문제에 영향 |
| 추세·가격 gate | 전일 종가>일봉 MA20, MA20 상승; 탐지가≤MA20×1.12; 최신봉 종가≥탐지가×0.99 | 추세·과열·신호 이후 반락을 필터링 |
| 유동성 gate | 신호 시 누적 거래대금≥10억원, 최신 확정 5분 거래대금≥2천만원 | 체결가능성의 대용치. Spread·호가 수량 검증은 아님 |
| 최종 gate·Rank | finalScore≥55; 내림차순, 동점은 최신 탐지 우선; 최대 1개 | 통과자가 없으면 NO TRADE. UI limit=10도 실제 상한 1 |
| 저장·추적 | 추천·탈락 이유·점수 요인·일부 Feature 저장 → 익일 가격 관측 | 실제/모의 체결 Net 결과와 연결된 검증 표본은 없음 |

Scanner 현재 설정의 조건은 VOLUME(q≥2), PRICE_RISE(g≥2%), MOMENTUM(둘 다), VOLUME_BREAKOUT(메모리 거래량배수≥2.5), TURNOVER_BREAKOUT(거래대금배수≥2), HIGH_BREAKOUT(현 봉 고가>이전 6봉 고가 및 g≥1%), VWAP_BREAKOUT(VWAP 괴리≥1%), VWAP_RECLAIM(괴리≥0 및 g≥0.5%), PULLBACK_REBREAK(현 봉 저가≤VWAP, 종가>이전 고가, |괴리|≤1%)다. 여기서 g는 **당일 등락률이 아니라 직전 봉 종가 대비 현 봉 가격 변화율**이다.

근거: [추천 서비스](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationService.java), [실제 gate·순위](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingPrecisionEvaluator.java), [Scanner](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/scanner/application/ScannerEvaluator.java), [설정·데이터 조회](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/inventory.tsv).

## 2. 현재 사용 정보 전체 목록

F=Hard Filter, S=Ranking Score, P=Risk Penalty, T=Scanner Trigger, M=저장·표시만. ‘조건부’는 해당 시점 값과 수신·수정 이력이 정확하다는 전제다. 숫자는 상승 확률이 아니라 구현된 점수다.

| 정보 | 계산 방법 | 사용 위치 | Weight/문턱 | 목적 | 15시 사용 가능 | 평가 |
|---|---|---|---|---|---|---|
| 종목 active/관리/정지/ETF/ETN | Stock 상태 | F | 최종 부적격 제외 | 거래대상 안전성 | 당시 상태 보존 시 O | 현재 마스터 재사용 위험; 실시간 상태와 연결 보완 |
| 현재가격·최소가격 | 현 봉 close | T/F | Scanner 설정 0 | 저가·거래 조건 | 조건부 | 가격 자체와 최저가격 정책 구분 |
| 신호·수신·평가시각 | detectedAt/receivedAt/asOf | F/M | 14:30~15:00, age≤30분 | 시점·신선도 | O | NULL 수신시각 허용은 미검증 |
| 마지막 틱 시각 | 전체 데이터 수신 상태 | F | 서비스 전역 신선도 | 시스템 중단 감지 | O | 종목별 stale 보증 아님 |
| 확정봉·연속성·일봉 수 | final/createdAt/updatedAt/count | F | 4봉/20분, 일봉21 | 계산 가능성 | 조건부 | 최종 gate에는 있으나 Scanner 분모·MA 전체창과 불일치 |
| 누적 거래대금 | quote/detection.dailyValue | F/S | 10억; min(금액/1억,15) | 유동성 | 조건부 | 관측 38건 모두 15점 포화 |
| 최근 확정 5분 거래대금 | 최신 final candle.tradingValue | F | 2천만원 | 현재 거래 지속 | 조건부 | 유지 필요. 주문 크기 대비 체결가능성은 별도 |
| Scanner 거래량배수 q | 현 진행 봉 volume / 이전 6개 DB봉 평균 | T/S | O 최대25, 추가최대10 | 거래 활성화 | 조건부 | 진행시간·봉 연속성·오류 분모 문제 |
| Feature 거래량배수 | 진행 bucket / 메모리 이전 최대6 bucket 평균 | T/M | VOLUME_BREAKOUT≥2.5 | 장중 거래 활성화 | 조건부 | q와 다른 값; 초기화·짧은 baseline |
| 거래대금배수 turnoverRatio | 진행 bucket 금액 / 메모리 과거평균 | T/M | TURNOVER_BREAKOUT≥2 | 거래 활성화 | 조건부 | 유통주식 회전율이 아님; 최종 직접점수 없음 |
| g: 직전 봉 대비 가격변화 | (현 close/직전 close−1)×100 | T/S/P | O≤30; 음수 R≤20+추가10 | 단기 가격 상승 | 조건부 | 5분간 수익률로 부르더라도 진행 봉 시간은 가변 |
| VWAP | 누적금액/누적량, 없으면 틱 기반 대체 | T/파생 | 직접점수 없음 | 당일 평균 거래가격 | 조건부 | fallback이 당일 VWAP과 동등하지 않음 |
| v: VWAP 괴리 | (현재가/VWAP−1)×100 | T/S/P | O≤20,+≤15; R≤25,+감점≤20 | 평균 거래가 위의 강도·과열 | 조건부 | 두 단계 중복, 매수 우위의 직접 증거는 아님 |
| h: 당일 고가거리 | (누적고가−현재가)/누적고가×100 | S/P | O≤10,+≤10; R≤15,+감점≤15 | 고가 유지·반락 | 조건부 | 전체일 최종고가가 아닌 실시간고가 사용; 중복 |
| 직전6봉 고가·현재봉 고저 | max(high), current.high/low | T | 돌파·눌림 조건 | 국지적 돌파 | 조건부 | 봉 내부 사건 순서·종가 안착 미확인 |
| 체결강도 t | KIS snapshot 값 | S/P | O≤15; R≤20,+감점≤15 | 체결 매수·매도 강도 | 조건부 | Level만 사용; 후반 강화 여부 없음 |
| 매수·매도량 증분 | 누적 매수/매도량의 직전 틱 차이 | P | 매도 우위면 R 최대20 | 매도 우세 위험 | 조건부 | 시간 길이 가변, 불연속 감점 |
| 마감 근접시각 | 14:30 이후 정수분×15/60 | S | 15시까지 최대7.5 | 신호 최신성 대용 | O | 수급 가속도 아님; stale는 F가 적절 |
| 분봉 MA5/20/60 | 확정 5M close 평균 | S/P | 정배열8, 교차5, 지지4, 이탈−12 | 중단기 추세·지지 | 조건부 | 20/60은100/300분; 창 연속성 보증 없음 |
| 일봉 MA5/20/60 | 전일까지의 확정 일봉 평균 | F/S/P | 정배열8, 상승5, 위4; 약화≤10 | 기존 추세 | 조건부 | 최종 통과자 상승5+위4는 상수 |
| 일봉 MA20 과열 | 전일close/MA20 및 탐지가/MA20 | F/P | 탐지가>112% 제외; 전일>112% −10 | 중기 과열 | 조건부 | 당일 급등·갭·연속상승·변동성 대체 불가 |
| 최신봉 신호 유지 | latest.close/detectedPrice | F | ≥99%, 봉 종료≥14:50 | 신호 뒤 반락 | 조건부 | 최신 Feature 재계산은 아님 |
| O/R 합계 | 아래 수식 | F/S/P | O≥35,R≤65;0.45배 후cap | 복합 기회·위험 | 조건부 | 경험적으로 검증된 확률·Weight 아님 |
| Scanner 종류별 score | g+q 및 종류별 보정 | T/M | 유형별 가산 | Scanner 표시·별도 비교 | 조건부 | 최종점수에 이 숫자를 그대로 더하지 않음 |
| Broad 순위/시세 점수 | 순위 점수+당일 momentum/금액/range | WATCH | 별도 scorer 최대65 | 탐색·관찰 | 조건부 | 현재 최종 정밀 추천 S와 구분 |
| 시장·업종·계좌 상태 | readiness 문자열 | M | UNVERIFIED/orderEligible=false | 적용 범위 표시 | 미검증 | 최종 점수·검증된 위험통제 아님 |

### 계산·보존되지만 직접적인 최종 선정 Feature는 아닌 정보

| 정보 | 실제 연결 상태 | 주의점 |
|---|---|---|
| vwapSlopeRate | 직전 틱 VWAP 대비 변화율 계산·Snapshot; 최종 scorer 미사용 | 시간당 기울기가 아님. 그냥 연결하면 부적절 |
| buyRatio | 틱/Feature 보존, 최종 직접 미사용 | 매수량 증분 위험항과 별개 |
| MarketTick.turnoverRate | KIS 필드40을 파싱하지만 IntradayFeatureState의 turnoverRatio 계산에는 사용하지 않음 | 원천 회전율과 자체 거래대금배수를 혼동하지 않음 |
| tick.tradingHalted, viStandardPrice | Feature에 보존, 최종 gate는 Stock.tradingHalted 사용 | 원천 위험필드 존재가 위험 차단 구현을 의미하지 않음 |
| openPrice/lowPrice/cumulativeVolume | 원천·VWAP·봉·Snapshot 구성에 사용 | 당일 시가 Gap, 당일 저가거리 등 별도 최종 Feature는 없음 |
| previousLow, MA5/20/60 거리 일부 | 계산·보존 | 모든 출력 필드가 가중치에 연결되지는 않음 |
| Broad 시장 regime | 순위로 모은 표본의 등락/상승비율로 RISK_ON/OFF/MIXED | 전 시장 breadth나 공식 지수 아님. closing marketRegime는 UNVERIFIED |
| 뉴스·공시·외국인/기관·프로그램 수급 | 실제 closing 입력/점수 연결 없음 | 별도 기능 존재 여부와 추천 사용 여부를 혼동하지 않음 |
| RSI/MACD/Bollinger/Stochastic/ADX/CCI/ATR | backend/src/main 및 web/src 정확 단어 검색과 호출 경로에서 사용 확인 안 됨 | 사용하지 않는 지표를 제거하자는 제안 불필요 |

근거: [Feature 상태 계산](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/market/feature/application/IntradayFeatureState.java), [Feature 필드](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/market/feature/domain/MarketFeatureSnapshot.java), [O/R](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/scanner/score/OpportunityRiskScorer.java), [최종 scorer](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScorer.java), [Broad scorer](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/BroadClosingRecommendationScorer.java).

### Score의 정확한 의미

`C(x,m)=min(max(x,0),m)`, `x+=max(x,0)`. g/v/h는 퍼센트 단위, q는 배수, t는 체결강도다.

```text
O = C(12*g+,30) + C(15*(q-1)+,25) + C(8*v+,20)
    + C(0.25*(t-100)+,15) + C(10-5*h,10)
R = C(8*(v-3)+,25) + C(0.25*(100-t)+,20)
    + C(10*(-g)+,20) + C(5*(h-2)+,15) + sellPressure
sellPressure = sell<=buy 또는 total<=0이면0;
               그 외 20*sell/(sell+buy)  [직전 틱 증분]
O,R는 0~100 제한, 소수3자리 반올림.

가산 = C(.45*O,35) + 마감근접시각 + C(누적금액/1억원,15)
       + C(5*v+,15) + C(10-4*h,10) + C(5*(q-1)+,10)
       + 분봉(정배열8 + 골든크로스5 + MA20지지4)
       + 일봉(정배열8 + MA20상승5 + 종가>MA20 4)
감점 = C(.45*R,40) + C(5*(v-5)+,20) + [g<0]*10
       + C(4*(h-3)+,15) + C(.15*(100-t)+,15)
       + [분봉MA20이탈]*12 + 일봉추세약화(최대10)
       + [전일종가가 일봉MA20보다12%초과]*10
최종 = round(C(가산-감점,100),3)
```

위 sellPressure의 분기는 `sell≤buy 또는 total≤0이면 0, 그 외 20×sell/total`이다. Weight는 소스에 정해진 상수이며, 현재 데이터에서 학습·OOS 검증으로 선택됐다는 근거는 없다. 과최적화가 있었다고 단정할 자료도 없다.

실제 9/23 한미반도체 ID117: `21.930 + 5.500 + 15.000 + 4.602 + 9.179 + 5.333 + 8 + 0 + 0 + 8 + 5 + 4 − 0 = 86.544`(표시 반올림값 합). 당일 상승확률 86.544%가 아니다. 기준 Feature는 14:52:17, 탐지는 14:52:24이며 15시 최신 값 자체로 모든 항목을 다시 계산한 점수가 아니다. [공식 Snapshot](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/official_20260923.json).

## 3. 잘 선택된 정보

| 정보 | 이 전략에서 필요한 이유 | 현재 입증 범위 |
|---|---|---|
| 누적·최근 거래대금 | 장 마감 전에 진입하고 익일 처분할 최소 거래 활동 확인 | 운영상 필요. 10억/2천만원이라는 문턱의 최적성은 미검증 |
| 데이터 시각·수신시각·연속성 | 추천 순간 사용 가능했던 정보인지, 계산 분모가 정상인지 판단 | 필수 검증정보. 입력 결함 때문에 엄격한 적용 필요 |
| 거래정지·관리·상품구분 | 매매 자체의 가능성과 전략 대상 분리 | 기본 gate 타당. 당시 상태 및 실시간 연계 보완 필요 |
| 고가 대비 반락·신호 이후 가격 유지 | 과거 상승과 현재까지 유지되는 강도를 구분 | 측정 논리는 타당, 익일 수익 예측력은 미검증 |
| VWAP 괴리·단기 가격변화 | 평균 거래가격과 최근 가격 경로를 관찰 | 가격 강도 후보 변수로 타당. 여러 번 가중할 근거는 없음 |
| 과열·매도압력 평가 의도 | 강세만 보며 밤사이 위험을 무시하지 않도록 함 | 의도는 맞으나 현재 변수·창·비용 연결 부족 |

**실증적으로 KEEP이라고 확정할 수 있는 상승 예측 Feature는 현재 없다.** 가격·거래 활동 Feature는 우선 원자료를 유지하고 계산 신뢰성을 복구할 대상이다.

## 4. 문제가 있는 정보

### 계산·의미

1. **거래량 배수의 분모와 경과시간이 일치하지 않는다.** 진행 봉 누적량을 이전 완성 봉 평균과 비교한다. 같은 초당 거래량이라도 봉 초반과 끝의 q가 달라진다. DB의 과거 6봉은 연속·동일 세션·확정 여부 보증이 없고 메모리 배수는 최대6개, 초기에는 더 적다. 두 값이 동일 이름으로 읽힐 수 있다.
2. **turnoverRatio는 주식 회전율이 아니다.** 현재 5분 거래대금의 과거 대비 배수다. 유통물량 대비 손바뀜이나 매수 주체 유입을 직접 의미하지 않는다.
3. **VWAP fallback의 의미가 변한다.** 정상 경로는 당일 누적금액/누적수량이나 결측 경로는 틱 기반 대체가격이다. 동일 Feature 이름으로 정상 VWAP처럼 가점하면 안 된다.
4. **매도압력은 지속 수급이 아니다.** 직전 틱 간 delta에서 sell>buy가 되면 raw penalty가 거의 10점부터 시작한다. 기본 R의 0.45배를 통해 약4.5점 불연속이 생길 수 있다. 몇 초·몇 분의 같은 길이 자료를 비교하지 않는다.
5. **Trigger 이름이 실제 경로를 보증하지 않는다.** VWAP_BREAKOUT은 괴리 Level, RECLAIM은 현재 VWAP 이상 여부로 과거 아래→위 전환을 확인하지 않는다. PULLBACK_REBREAK는 봉의 low와 close만으로 눌림·재돌파의 순서를 확정할 수 없다. HIGH_BREAKOUT도 high 조건만으로 돌파 뒤 안착을 확인하지 않는다.
6. **분봉 MA 표본 길이가 다르면 정의도 달라진다.** MA60이 없으면 정배열은 5>20, 있으면 5>20>60이다. 같은 8점이 서로 다른 정보 요건에 부여된다. MA20/60의 전체 연속성은 최종 4봉 gate로 보장되지 않는다.

### 15시 시점 정합성

| Feature/자료 | 데이터 기준시간 | 15시 사용 가능 | 미래·시점 위험 | 판정 |
|---|---|---|---|---|
| 현재가·고저·누적 거래량/대금·체결강도 | 실시간 Feature 발생시각 | 원칙상 O | 실제 수신시각·파싱 정확성·지연 필요 | 조건부; 오류 존재 |
| q/g/고가돌파 | 탐지 시 현 봉 및 이전봉 | 원칙상 O | 과거봉 수정·부분봉·시간축 불일치 | MODIFY |
| VWAP·고가거리 | 신호시각까지 누적 | 원칙상 O | 당일 EOD 고가/금액을 직접 쓰는 경로는 아님; fallback·수정 문제 | MODIFY |
| 매수/매도 delta·거래대금배수 | 실시간 메모리 상태 | 원칙상 O | 수신 누락·재시작·baseline 차이 | MODIFY |
| 분봉 MA | 탐지시각 전에 끝난 final candle | 원칙상 O | updatedAt 제한 있으나 NULL 허용·revision 이력 부재 | 부분 보호 |
| 일봉 MA | 전일까지 확정된 일봉 | O | 당일 종가 포함 안 함; 수정주가·기업행사 시점 일관성 미검증 | 부분 보호 |
| 14:55~15:00 확정봉 | 종료≤15시, 수신/수정≤15:00:10 유예 | 엄밀한15:00:00 수신기준이면 X 가능 | 계산 대기 후 추천이면 그 대기시간을 실행시각으로 명시해야 함 | 정확한 의사결정 시점 정의 필요 |
| 15시 이후 최종 일봉·수급·뉴스 | EOD/발표시각 | X | 현재 최종 scorer에 직접 사용 확인 안 됨 | 연결 없음; 임의 추가 금지 |
| 익일 가격 | 성과 추적 | 추천에는 X | scorer 직접 유입 경로는 확인 안 됨 | Label용으로 분리 |
| 과거 종목 상태 | 현재 Stock 마스터 | 당시 상태 확인 필요 | 생존·정지·상품 상태 사후 반영 | 과거 Universe 검증 불충분 |

확인된 중요한 문제는 “모든 Feature에 명백한 익일 정보가 섞였다”가 아니다. **당시 입력을 현재 DB로 동일하게 재현할 수 없고, 일부 데이터의 시점·수신 이력을 보증하지 못한다**는 것이다. 실제로 9/23 공식 후보가 같은 날 replay에서는 늦은 봉 수정으로 제외됐다. 원래 14:55 봉은 15:00:03 생성, 15:00:33 수정이며 후자는 유예를 넘는다. 수정 전 값의 이력이 없다.

실시간 파싱 오류가 지속됐고 원본 집계 클래스로 늦은 동일 봉 재생성 시 volume 600→50 축소를 재현했다. 이는 q·거래대금·MA·VWAP 연관 입력을 신뢰하기 전에 해결해야 하는 P0다. 당시 카운터 오류 비율을 틱 손실률이라고 해석하지 않았다. [재현 결과](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/reproduction.txt), [후속 실시간 상태](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/realtime-followup.json).

## 5. 불필요하거나 효과가 의심되는 정보

| 대상 | 코드·데이터 근거 | 판단 |
|---|---|---|
| 일봉 MA20 상승5 + 전일 종가>MA20 4 | 두 조건은 최종 QUALIFIED의 필수 gate | 후보 모두 +9점. 통과 후보 간 변별력 없음; 점수 보너스 제거 검토 |
| 일봉 약화 penalty의 종가아래6·비상승3 | 같은 hard gate에서 먼저 탈락 | 통과 후보에서는 해당 분기 불가능. 일봉MA5<20의4점은 별개 |
| 누적 거래대금 15점 | 구버전 관측38/38 및 현재 유일 추천 모두 cap | 표본에서 순위를 구분하지 못함. 거래대금 원자료·최저 유동성 gate는 유지 |
| closingRecency | 이벤트 발생이 늦을수록 최대7.5점 | 후반 수급 개선과 동일하지 않음. stale gate와 분리해 가점 제거 비교 |
| 여러 MA 상태 동시 보너스 | 분봉17·일봉17의 항목별 상한 | 단기 수익 목적에 대한 증분 효과 미검증; 개별·묶음 ablation 대상 |
| VWAP·고가거리·거래량의 O와 최종 가산 | 동일 입력 재사용 | 반복 가중치를 정당화한 OOS 자료 없음 |
| vwapSlopeRate 등 미사용 계산값 | 실제 점수 미연결 | “더 있으니 연결” 금지. 보존 비용 이유만으로 원자료 삭제도 불필요 |

상수 9점을 빼면 최종55 문턱과100 cap 효과도 달라진다. **같은 Threshold를 유지한 채 추천 감소를 ‘Feature 제거 효과’로 평가하면 잘못된 실험**이다. 수학적 상수 제거와 모델 구조 변경을 구분해야 한다. 효과가 검증되지 않았다는 이유만으로 운영 중 즉시 삭제하자는 결론은 아니다.

## 6. 현재 빠져 있는 정보

아래는 검증할 가설이며 상승 예측력이 입증된 추가 항목이 아니다. 기존 틱·봉·Snapshot을 우선 사용하고 P0 복구 전 추가 점수화를 하지 않는다.

| 후보 | 필요한 이유·측정 현상 | 필요한 데이터 | 15시 가능 | 기존 중복 | 검증 방법·역할 |
|---|---|---|---|---|---|
| Spread·호가 깊이·예상 체결비용 | 거래대금이 있어도 주문 가격·수량이 실행 가능한지 | 당시 bid/ask·잔량·주문수량·체결시각 | 수신 시 O | 거래대금과 일부 관련, 동일 아님 | 같은 추천의 보수적 체결 Net 민감도; 비정상 값은 F |
| 거래정지/VI/경고·단기과열의 당시 상태 | 익일 상승 여부 이전에 진입·청산 제한 위험 | 실시간 상태 및 공표·효력 시각 이력 | 공표된 상태만 O | Stock 정지값과 일부 중복 | 당시 상태별 미체결·갭손실 비교; 규칙상 불가 상태 F, 기타 위험 별도 |
| 고정창 후반 거래활동 변화 | 오전 누적활동과 14:30 이후 신규 활동 분리 | 정상 확정봉 또는 동일 길이 틱창의 수량/금액 | O | 현재 q 대체 우선 | 14:30~14:45 vs14:45~15:00 비율 한 가설 고정; 기간순 OOS |
| 지속 주문흐름/체결강도 변화 | 한 틱 매도압력과 지속 수급 구분 | 동일 길이 buy/sell delta, strength 시계열 | 수집되면 O | 기존 delta/strength 대체 후보 | Level 단독 vs Level+고정창 변화, 비용 후 증분 비교 |
| 당일·최근N일 상승/갭과 변동성 척도 | +4%와+19%, 연속급등·갭 과열의 구분 | 당시가격·시가·전일종가·PIT 일봉/기업행사 | O | MA/VWAP 과열과 일부 중복 | 신규 가산보다 Risk 후보; 큰 갭손실·목표 전 MAE 감소 여부 |
| 시장 대비 수익·대표 지수 상황 | 시장 상승 동행과 종목 고유강도 분리 | 동일 시간 구간 종목·KOSPI/KOSDAQ 시세 | O | Broad 표본 regime 대체, MA와 일부 관련 | 우선 regime별 층화; 그 후 동일 후보에서 상대강도 증분 OOS |
| 업종 상대강도 | 특정 업종 전체 움직임·집중 확인 | 당시 업종분류와 동일시각 업종지수 | 확보 시 O | 시장 상대강도와 관련 | 시장 상대강도를 넘는 추가효과 없으면 도입하지 않음 |
| 공시·이벤트 위험 여부 | 가격 움직임 원인·예정 이벤트의 보유 위험 | 실제 게시·수신 시각, 정정 이력, 사전 알려진 일정 | 공표분만 O | 가격강도와 다른 정보 | 뉴스 감성점수보다 위험 플래그부터; 사후 재료 분류 금지 |

KIS 공식 H0STCNT0 예제에는 시세 관련 원천 필드가 더 있지만, 현재 파서가 읽는 필드·정확도를 먼저 확인해야 한다. 필드 존재만으로 호가 이력·체결 모형이 준비됐다고 보지 않는다. [KIS 공식 원천 예제](https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py).

## 7. 중복 Feature

### 그룹별 가중치 구조

O를 최종점수의0.45로 환산한 **항목별 명목 최대치**와 직접가산을 합산했다. baseOpportunity 전체cap35·최종cap100·동시 성립 불가능한 조건 때문에 아래 숫자를 실제 기여율이나 고정 Weight%로 해석하면 안 된다.

| 그룹 | 연결된 양의 항목 | 명목 상한 합계 |
|---|---|---:|
| PRICE | VWAP 9+15, 고가근접4.5+10 | 38.50 |
| MOMENTUM | g13.5, 분봉MA17, 일봉MA17 | 47.50 |
| VOLUME | q11.25+10 | 21.25 |
| LIQUIDITY | 누적대금 | 15.00 |
| ORDER_FLOW | 체결강도 | 6.75 |
| TIME/운영시각 | closingRecency | 7.50 |
| RELATIVE_STRENGTH / MARKET / EVENT | 최종 양의 항목 없음 | 0 |
| RISK | VWAP·고가거리·음수g·체결강도·sellPressure·MA | 위 가산과 별도 감점; 단일 독립 그룹이 아님 |

공유 cap 전 합계136.5 중 PRICE+MOMENTUM 항목상한이86이다. 이는 실제 63% 기여율이 아니라 가격 기반 항목이 많이 배치되어 있다는 구조적 증거다. baseOpportunity의 개별상한45가 공유상한35로 제한되므로 독립 합산 모델이 아니다.

VWAP·고가근접·MA·g는 서로 다른 가격 경로를 보기도 하므로 “같은 가격 자료”라는 이유만으로 모두 불필요한 것은 아니다. 다만 같은 v/h/q를 두 단계에서 그대로 재가중한 것은 명시적 중복이다. 음수g/약한체결강도/과도한VWAP/고가이탈도 R과 최종 penalty에 중복된다. 상관계수 대신, 고정 Universe·체결모형에서 날짜별 묶음 ablation으로 추가 정보를 확인해야 한다.

## 8. 시간 구간 문제

| 정보 | 현재 Level/Window | 필요한 비교의 가설 | 판단 |
|---|---|---|---|
| 체결강도 | 마지막 Snapshot Level | 마지막5/15분의 시작·끝 또는 평균 변화 | 누적 지표 자체의 정의 확인 후 동일창 비교; Trend 우월성 미검증 |
| 거래량/거래대금 | 당일 누적 + 진행5분/과거평균 | 같은 길이 후반 두 창, 필요시 과거일 동일 시각 | 진행률 차이를 제거하는 것이 우선 |
| VWAP 괴리 | 최신 이벤트 Level, slope는 미사용 | 고정창 괴리 변화·유지시간 | 단순 이격 확대는 과열일 수 있어 상승방향 가점 금지 |
| 호가 불균형 | 최종 사용 없음 | 일정 시간 평균·지속성 | 단일 호가 Snapshot은 지속 수급과 다름; 데이터부터 |
| 상대강도 | 최종 사용 없음 | 같은 시작·끝 시각의 종목−시장 수익 | 시간축 정합성 필수 |
| Momentum | 진행봉 close/직전close, MA100/300분 | 후반 고정5/15/30분 중 최소 대표창 | 세 창 모두 가산하지 말고 사전 선택·대체 비교 |
| 신호 최신성 | 탐지≤30분, 시각가산 | 종목별 입력나이·최근 반락·새로 계산한15시 상태 | 시간은 품질 gate로, 수급 방향성과 분리 |

09:00~14:00 /14:00~14:30 /14:30~14:45 /14:45~15:00의 활동을 나누는 현재 최종 Feature는 없다. 누적 거래대금과 이벤트시각만으로 오전 강세와 후반 신규 유입을 충분히 구분할 수 없다. 모든 구간을 새 Score로 추가할 필요는 없고, 기존 q를 정상적인 동일창 비교로 대체하는 실험이 먼저다.

분봉 MA20/60은 각각 100/300분이다. ‘장 후반 5~30분’과 목적이 다르고 이전 세션 봉이 포함될 수 있다. 당일 추세 맥락으로 쓸 수는 있으나 익일 매도 적합성을 자동 보장하지 않는다. [분봉 MA](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/IntradayMovingAverageService.java), [일봉 MA](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/DailyMovingAverageService.java).

## 9. Risk 평가 문제

| 영역 | 현재 판단 가능 범위 | 부족한 점 |
|---|---|---|
| A 가격 강도 | VWAP·고가거리·g·MA·신호가 유지 | 최신15시 재평가·지속시간 부족 |
| B 거래활동 | 누적대금·최근봉대금·두 종류 배수 | 14:30~15:00 신규 유입·정규화 부족 |
| C 체결/호가 | 강도 Level·한 틱 매도압력 | 지속 매수 흐름·Spread·깊이·충격비용 미검증 |
| D 시장/업종 | readiness=UNVERIFIED | 상대강도·대표시장 breadth·업종집중 미반영 |
| E 이벤트 | 직접 연결 없음 | 상승 원인의 지속성·경고·일정 위험 판단 못함 |
| F Overnight | VWAP/MA 과열·반락·약한수급·최소유동성 | 당일급등·연속급등·갭·변동성·VI·익일 청산제약 불충분 |

현재 g는 당일 등락률이 아니다. 따라서 +4% 종목과 +19% 종목이 비슷한 마지막5분 강도·고가근접·VWAP 조건이면 과열 차이를 직접 평가하지 못한다. MA20 12% gate가 일부 사례를 거르지만 당일 갭이나 전일 대비 상승률을 대신하지 못한다. B가 항상 더 높은 점수를 받는다고도 단정할 수 없다. VWAP 과열 penalty와 MA gate가 있기 때문이다.

권장 역할은 거래 불가·핵심 결측·비정상 시세·극단적 체결 불가능 상태를 **Hard Filter**, 정상 가격/수급 차이를 **Ranking**, 과열·변동성·갭 노출을 **Risk Penalty 또는 별도 위험표시**로 분리하는 것이다. 일봉 상승 여부는 거래 불가 조건이 아니라 전략 가설이므로 현재 hard gate의 필요성도 대체모형 비교가 필요하다.

실제 9/23 다음 거래일을9/24로 잘못 지정했고, 집계기는15:30 틱을 제외하는데 성과 관측은15:30봉 포함79개를 요구한다. 이 때문에 가격 위험 라벨 자체를 완성하기 어렵다. 비용 기본설정도0이며 공식 관측은 체결 Net이 아니다. 이런 상태에서 Feature별 ‘위험 감소’를 수익률 통계로 확정할 수 없다. 휴장 근거: [정부 추석 금융 안내](https://m.korea.kr/briefing/pressReleaseView.do?newsId=156781483&pWiseMinistry=ministryNews&repCode=C00003&repCodeType=%EC%A0%95%EB%B6%80%EB%B6%80%EC%B2%98).

## 10. 실제 성과 기반 Feature 검증

### 사용 가능한 모집단과 한계

| 자료 | N/날짜 | 사용 가능 범위 |
|---|---|---|
| 현재 공식 FORWARD 실행 | 2일, 추천1건 | 실행·점수 구조 확인 |
| 현재 공식 완료 결과 | **0건** | Feature 유효성·Net·OOS·Paper 검증 불가 |
| 이전 버전 COMPLETED 표기 | 38건/6추천일 | 불완전 가격 관측의 탐색 비교만 가능 |
| 현재 백테스트 8/25~9/23 | 추천0·완료0 | ablation 효과·기대값 산출 불가 |

구버전38건은 모두 익일 전체 세션이 불완전하고,16건은 신호가15시 이후이며 모두 추천 생성이15:20 이후다. 현재 전략의 공식 Paper나 OOS로 합치지 않았다. 아래 수익은 저장된 마지막 close 관측의 신호가 대비 Gross이고, 고가/저가는 불완전 관측값이다. +1/2/3% 도달률도 누락 시간으로 편향될 수 있다. **평균 Net·실현승률·완전 MFE/MAE는 모든 구간에서 미산출**이다.

### Feature 구간 비교

| Feature 구간 | N/날짜 | 평균 관측Gross% | Median% | 양수close비율% | 해석 |
|---|---:|---:|---:|---:|---|
| 체결강도<100 | 5/4 | -1.185 | -0.505 | 20.0 | 작은 혼합 표본 |
| 100~120 | 7/4 | -1.961 | -1.801 | 28.6 | 단조 개선 아님 |
| 120~140 | 8/5 | -0.879 | -1.055 | 37.5 | 최적 구간이라고 선택 불가 |
| 140~160 | 8/5 | -1.045 | -1.432 | 12.5 | 고가 도달과 close 결과 다름 |
| ≥160 | 10/4 | -1.760 | -1.976 | 10.0 | 높은 Level의 유효성 입증 안 됨 |
| 거래량 q<1 | 5/3 | -2.267 | -1.992 | 0.0 | 원천 품질도 확인 필요 |
| q 1~2 | 3/2 | -3.128 | -2.693 | 0.0 | 매우 적은 표본 |
| q 2~3 | 15/5 | -0.265 | -0.707 | 33.3 | OOS 개선 증거 아님 |
| q 3~5 | 8/3 | -0.641 | -0.625 | 37.5 | 날짜 혼재 |
| q≥5 | 7/**1** | -3.260 | -3.971 | 0.0 | **전부9/11**, 날짜·비정상 분모와 분리 불가 |
| VWAP괴리<0 | 1/1 | -0.847 | -0.847 | 0.0 | 판단 불가 |
| 0~1% | 8/3 | -1.131 | -0.852 | 37.5 | 선택 편향 |
| 1~3% | 25/5 | -1.258 | -1.420 | 20.0 | 높은 값의 개선 미입증 |
| 3~5% | 3/1 | -2.293 | -2.265 | 0.0 | 전부9/9 |
| ≥5% | 1/1 | -4.425 | -4.425 | 0.0 | 한 사례로 문턱 선택 금지 |
| 고가거리0~0.3% | 19/5 | -1.402 | -1.529 | 26.3 | 근접 우월성 미입증 |
| 0.3~1% | 14/5 | -1.467 | -1.504 | 14.3 | 작은 표본 |
| 1~3% | 5/3 | -1.090 | -0.847 | 20.0 | 단조 관계 없음 |

각 구간의 **+1/+2/+3%, 고가·저가 관측치 및 N**은 [전체 통계표](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/FEATURE_BUCKETS.md)에 모두 제공한다. q≥5의 관측에는 매우 큰60~371배 값이 있으며 봉 분모 품질과 특정 날짜 효과를 먼저 조사해야 한다. 이를 근거로 “5배 이상 제외” 같은 사후 규칙을 만들지 않았다.

### 미리 정한 세 조합

| 조합 | 충족 N/날짜, 평균Gross | 미충족 N/날짜, 평균Gross | 판단 |
|---|---|---|---|
| q≥2 + VWAP>0 | 29/5, -1.072% | 9/5, -2.396% | 상대적으로 덜 음수. 긍정 기대값·독립효과 증거 아님 |
| q≥2 + 고가거리≤0.3% | 12/5, -0.701% | 26/6, -1.701% | 선택된 추천 안의 탐색 차이 |
| g>0 + 거래대금배수≥2 | 15/6, -0.842% | 23/5, -1.739% | 후반가속도의 실제 관측이라고 볼 수 없음 |

가능한 조합·Threshold 전수탐색은 하지 않았다. 후보 선정 자체가 이 Feature에 의존하므로 추천표본 내 관계는 전체 Universe의 예측관계와도 다르다.

### 성공·실패와 False Positive

실현손익 라벨이 없어 양수close8건/4일, 음수close28건/5일, 보합2건/2일로 **대용 분류**했다. 성공20~50건 요청을 충족할 자료가 없으며 임의로 성공을 늘리지 않았다. 개별 목록은 [사례 추출표](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/FEATURE_CASES.md)와 JSON에 포함한다.

| 추천 당시 값 중앙값 | 양수 N=8 | 음수 N=28 |
|---|---:|---:|
| 거래량배수 | 2.383 | 2.531 |
| 누적대금, 억원 | 281.087 | 756.490 |
| 직전봉 대비 변화% | 0.304 | 0.378 |
| 체결강도 | 130.000 | 143.310 |
| VWAP괴리% | 1.315 | 1.453 |
| 고가거리% | 0.143 | 0.303 |
| 거래대금배수 | 2.008 | 1.401 |

고가거리·거래대금배수에 탐색 차이는 있으나 “가장 잘 구분하는 Feature”로 선정할 수 없다. 날짜 군집·버전·진입가·관측 누락을 통제하지 못했고 독립 OOS가 없다. 유사한 중앙값도 ‘차이 없음’을 입증하는 동등성 검정은 아니다.

Score≥80인데 음수close인 대용 False Positive는4건이다: 9/9 006400(81.126점,-2.265%),247540(80.231점,-2.693%),9/15 052690(85.965점,-4.425%),047040(82.793점,-1.403%). 앞의 두 건은 체결강도180 이상·VWAP거리3%대,052690은 VWAP거리5.155%였다. 그러나047040은 VWAP거리1.907%로 동일 패턴이 아니었다. 뉴스성 급등·테마·시장약세·VI를 원인으로 단정할 자료는 없다. 익일 중 목표에 먼저 도달했는지도 완전한 경로 없이는 판정할 수 없다.

### False Negative·탈락 후보

공식 Snapshot의 정밀 후보 대표 평가를 확인했다.9/22 정밀24종목은 O/R탈락22·수신시각탈락2,9/23 정밀40종목은 O/R탈락36·수신시각2·봉누락1·통과1이다. Broad WATCH는 별도18/19건이다. [9/22](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/official_20260922.json), [9/23](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/official_20260923.json).

탈락 이유는 저장되어 있어 진단에 활용할 수 있지만, 탈락 종목의 동일 체결규칙·완전 익일봉 Net 결과가 준비되지 않았다. 따라서 탈락 후 급등한 종목의 FN율·Threshold 과도 여부는 미산출이다. 9/23 후보의 replay 탈락은 **동일 추천 재현 문제**이며 수익성 FN으로 세지 않았다. 소스 이벤트 수73/183은 대표 종목 수24/40과 다른 분모다.

### Ablation·OOS·불확실성

유효한 Feature ablation 완료 N=0. 기존 `CLOSING_NO_MA`는 MA 점수가 없는 비교를 만들더라도 그 전 후보 평가의 MA gate가 남아 있어 “MA 전체 제거” 실험이 아니다. [비교 엔진](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightBacktestService.java).

검증 재개 시 날짜순 개발/검증/최종 OOS를 분리하고 경계의 익일 Label 겹침을 제거한다. 동일 PIT Universe·진입·Exit·비용으로 (1) 중복 가산 제거,(2) MA 점수만 제거,(3) 별도로 MA gate 제거,(4) q를 고정창으로 대체를 사전 등록한다. 추천 수가 달라지면 커버리지와 동일 top-K 결과를 함께 보고, 사후에 가장 좋은 Threshold를 고르지 않는다. 한 번 본 OOS로 다시 Weight를 조정하면 그 기간은 개발 데이터로 전환한다.

현재 N=0에 CI나 bootstrap을 계산하지 않았다. 구버전38건에 정밀한 CI를 붙여도 결측·시점 오류가 고쳐지지 않으며 독립 추천일은6개뿐이다. 복구 후에는 종목 독립 bootstrap보다 **추천일 단위 묶음 bootstrap**으로 평균 Net 및 Feature 제거 차이의 불확실성을 평가한다. 잠정 수집계획은 최소60~120거래일의 고정 정책 Forward 관측, 상승·하락·고변동 국면별 기록이다. 이는 신뢰성 통과 기준이 아니다. 최대1개/일 및 NO TRADE 때문에120일이어도120거래가 안 될 수 있다. 승률의 95% 신뢰구간 반폭 ±10%p 수준도 독립·승률50% 근사 가정에서 약100건, ±5%p는 약400건 규모가 필요하며 군집·희귀 손실은 더 많은 기간이 필요하다. 핵심은 개수 달성이 아니라 비용 후 기대값·위험의 불확실성이다.

## 11. KEEP / MODIFY / REMOVE / ADD

아래 실제성과근거의 ‘N0’은 현재 공식 완료 표본0을 뜻한다. 구버전38은 효과 입증이 아닌 탐색자료다. 원자료 보존과 점수 항목 유지를 분리했다.

| 평가정보 | 현재사용 | 필요성 | 데이터시점 | 중복위험 | 실제성과근거 | 최종판정 | 변경사항 |
|---|---:|---|---|---|---|---|---|
| 발생/수신/평가시각·버전 | O | 재현성 필수 | PIT 필요 | 낮음 | replay 불일치 실증 | KEEP/MODIFY | NULL·늦은 수정·가용성 정책 명확화 |
| 확정봉·연속성·데이터 품질 | O | 계산 전제 | 탐지 전 | 낮음 | 봉 축소·누락 재현 | KEEP/MODIFY | baseline/MA 전체창까지 일관 검증 |
| 종목 active/관리/정지/상품 | O | 매매 대상 적격성 | 당시 상태 필요 | 낮음 | 상태효과 N0 | KEEP/MODIFY | 실시간·효력시각 상태 반영 |
| 누적 거래대금 원자료·gate | O | 최소 활동 | 신호까지 | 중간 | 수익효과 N0 | KEEP | Threshold 최적성은 별도 검증 |
| 최근5분 거래대금 gate | O | 최근 거래 지속 | 최신final | 중간 | N0 | KEEP/MODIFY | 정상봉·주문수량 대비 검증 |
| 누적 거래대금 가산 | O | 후보 구분 가설 | 신호까지 | 높음 | 38/38·현재1건 cap | REMOVE CANDIDATE | 원자료/gate 유지, 포화보너스 ablation |
| Scanner q | O | 거래 활성화 | 진행봉/과거6봉 | 높음 | 구간 비단조·하루 편중 | MODIFY | 동시간길이·정상 baseline·중복가산 축소 |
| 메모리 volumeRatio | Trigger | 거래 활성화 | 진행/메모리 최대6 | 높음 | 독립효과 N0 | MODIFY | q와 정의 통일 또는 명시 분리 |
| turnoverRatio | Trigger/표시 | 금액 활동 | 진행/메모리 | 높음 | 탐색 중앙값차만 존재 | MODIFY | 의미·창 수정; 점수 자동추가 금지 |
| 단기 g | O | 가격 강도 | 가변 진행5분 | 높음 | N0 | MODIFY | 고정창 수익·당일수익 분리 |
| VWAP/괴리 | O | 평균가 대비 위치 | 누적실시간 | 높음 | 구간 개선 미입증 | MODIFY | fallback 품질·중복·과열 결합 검증 |
| 당일 고가거리 | O | 반락·유지 | 누적실시간 | 높음 | 단조관계 없음 | MODIFY | 한 번 반영; 현재 지속성 검증 |
| HIGH/VWAP/RECLAIM/눌림 trigger | O | 패턴 탐지 | 현/과거봉 | 높음 | N0 | MODIFY | 이름과 실제 사건 조건 일치 |
| 체결강도 | O | 체결 방향 대용 | Snapshot | 중간 | ≥160 개선 안 보임 | MODIFY | Level 유지, 고정창 변화 대체 비교 |
| 매수/매도 delta penalty | O | 매도 우세 | 한 틱 간격 | 중간 | N0 | MODIFY | 동일 길이·연속성·감점 불연속 수정 검토 |
| closingRecency 가산 | O | 최신성 대용 | 이벤트시각 | stale gate 중복 | N0 | REMOVE CANDIDATE | freshness gate 유지, 가점 효과 비교 |
| 분봉 MA5/20/60·교차·지지 | O | 기존 추세 | 탐지 전 확정봉 | 높음 | ablation N0 | MODIFY/REMOVE CANDIDATE | 정의·창 보정 뒤 묶음 제거 비교 |
| 일봉 추세 hard gate | O | 추세 가설 | 전일까지 | MA가산 중복 | N0 | MODIFY | 거래 적격과 분리, gate 자체 OOS 비교 |
| 일봉 상승5+위4 보너스 | O | 추가 변별 없음 | 전일까지 | 완전 중복 | 통과자 모두9점 | REMOVE CANDIDATE | 상수 제거 시 threshold/cap 보정 |
| 일봉 정배열8 | O | 추세 가설 | 전일까지 | 높음 | N0 | REMOVE CANDIDATE | gate 대비 증분효과 검증 |
| MA20 과열·신호유지 | O | 과열/반락 위험 | 전일·신호·최신봉 | 중간 | N0 | MODIFY | 당일급등·갭·변동성과 목적 분리 |
| vwapSlopeRate/buyRatio | 보존 | 가설·진단 | 틱간/누적 | 중간 | N0 | 현 미사용 유지 | 근거 없이 Score 연결하지 않음 |
| viStandardPrice/tick정지 | 보존 | 위험상태 보조 | 실시간 | 상태 일부 중복 | 선정에는 미연결 | MODIFY | 실제 거래상태 gate와 연결 검토 |
| Broad 순위/당일등락 | WATCH | 관찰 범위 확장 | 당시 REST/시세 | 가격·량 중복 | 최종 효과 N0 | KEEP/MODIFY | WATCH로 구분·구독 편향 기록 |
| Broad regime | 별도표시 | 맥락 가설 | 순위선정 표본 | 높음 | closing 미연결 | MODIFY | 전시장 regime로 오인 금지 |
| Spread/깊이/비용 | X | 실행 가능성 | 당시호가 | 낮음 | N0 | ADD CANDIDATE | P1, 우선 실행 적격·비용용 |
| 후반 고정창 활동·흐름 | X | 신규유입·지속성 | 15시 이전 | 기존량/flow 높음 | N0 | ADD CANDIDATE | 기존 항목 대체 실험 우선 |
| 당일/N일 수익·갭·변동성 | X(직접) | Overnight 과열 | 과거~현재 | MA위험 일부 | N0 | ADD CANDIDATE | 위험별 층화부터 |
| 시장/업종 상대강도 | X | 공통요인 분리 | 동일시각 | 서로 일부 | N0 | ADD CANDIDATE | 시장부터, 업종은 증분 확인 |
| 공시/일정/경고 이력 | X(최종) | 사건·거래 위험 | 공표/효력 시각 | 낮음 | N0 | ADD CANDIDATE | 구조화 위험부터, 감성점수 후순위 |
| RSI/MACD 등 미사용 기술지표 | X | 추가 필요성 미입증 | 해당없음 | 잠재 높음 | N0 | 추가하지 않음 | 지표 수 확대 목적 개발 금지 |

## 12. 권장 마감추천 평가 구조

현재 시스템을 재작성하기보다 기존 평가 Snapshot과 gate/score/reason 구조를 활용한다. 새 Weight 숫자는 제안하지 않는다. 적합한 숫자를 정할 유효 데이터가 없다.

1. **사용 가능 여부**: 평가시각·종목별 수신나이·원천/봉 품질·PIT 거래가능 상태. 실패하면 추천 제외 또는 검증 미완료 표시.
2. **진입·청산 가능성**: 기존 누적/최근 거래대금에 당시 Spread·주문규모 대비 호가·예상비용을 붙인다. 수익 가점보다 실행 gate와 비용 계산에 사용한다.
3. **최소 강도 정보**: 정상 VWAP/고가거리에서 중복을 줄인 가격 유지 정보, 동일 길이 후반 활동 변화, 하나의 대표 단기 수익 창. MA는 증분 검증 전 확정 우위로 해석하지 않는다.
4. **별도 보유 위험**: 당일·누적 과열/갭/변동성, 반락, 거래제약·공표 이벤트. 상승점수에 묻히지 않도록 원자료·위험 사유를 병기한다.
5. **시장 맥락**: 먼저 실제 시장/업종에 따른 성과 층화를 하고 이후 상대강도 점수 연결 여부를 결정한다. 미확인 regime는 그대로 미확인으로 표시한다.
6. **증거 수준**: 전략·Feature 버전, 유효 표본N/날짜수, 실현 Net/OOS/Paper 여부, 불확실성. 현재 N=0에서는 상승확률·Expected Net Return을 숫자로 생성하지 않는다.

후보 화면에서 현재 함께 확인할 최소 정보는 **점수 구성, 신호/Feature 시각, 현재 호가와 신호가 차이, 데이터 누락·수정 여부, 유동성, 과열/거래제약, 다음 실제 거래일, 비용을 포함한 진입·Exit 가정, 검증 표본수**다.86.544점만 표시하는 것은 충분하지 않다.

## 13. 개선 우선순위

| 순위 | 변경 제안 | 대상 파일/기존 기능 | 필요한 이유 |
|---|---|---|---|
| P0 | 실제 프레임 파싱·거래일/시각·수신 검증 | KisRealtimeTickParser, KisRealtimeClient | 손상 입력으로 계산한 Feature 통계 방지 |
| P0 | 늦은 틱·동일봉 재생성·수정 이력 정책 | FiveMinuteCandleAggregator, StockCandle 저장 경로 | volume 축소 재현·PIT replay 불일치 해소 |
| P0 | 거래일 달력·마감 관측 규칙 일치 | ClosingTradingCalendar, OvernightPerformanceService | 잘못된 익일·불가능한79봉 완성조건 수정 |
| P0 | 결측·유예·시점 정책 적용 | ClosingPrecisionEvaluator, MA 서비스 | 엄밀한15시와 수신 유예, NULL receipt, 수정봉 처리 구분 |
| P1 | 거래량/금액의 동일창·baseline 정상화 | IntradayFeatureState, ScannerEvaluator | 장 후반 수급 비교의 의미 복구 |
| P1 | 체결·수수료/세금/Spread/Slippage 결과 연결 | 기존 execution simulator·performance·analytics | Feature의 Net 효과를 판단할 Label 확보 |
| P1 | 실시간 적격 상태·입력나이·호가 위험 | MarketFeatureSnapshot, ClosingPrecisionEvaluator | 거래불가·stale 후보 차단 |
| P1 | 상수9·포화유동성·중복 가산 실험 | ClosingRecommendationScorer, OvernightBacktestService | 점수 변별력·ablation 해석 복구 |
| P2 | 후반고정창·과열/갭·시장 맥락의 소수 가설 | 기존 Feature/Snapshot/analytics 활용 | 검증 가능한 차별정보 탐색 |
| P2 | 탈락 후보의 동일 규칙 결과 비교 | 저장된 run.evaluations 활용 | FN·NO TRADE·hard gate 판단에 필요 |
| P3 | 업종·이벤트 지속성·복잡한 흐름모델 | 증분 OOS 확인 후 | 현재 데이터만으로 추가 우선순위 낮음 |

대상 경로: [파서](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/kis/websocket/KisRealtimeTickParser.java), [봉 집계](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/candle/application/FiveMinuteCandleAggregator.java), [달력](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingTradingCalendar.java), [성과 서비스](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightPerformanceService.java), [분석 서비스](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingStrategyAnalyticsService.java). 이 문서는 변경 제안이며 실제 코드 수정은 하지 않았다.

### 반드시 유지할 정보

발생·수신·평가시각과 버전, 원본 추천 Snapshot, 데이터 품질·연속성, 종목 거래가능 상태, 누적·최근 거래대금 원자료, 가격·수급 원자료 및 탈락 사유. 운영·검증에 필요한 정보이므로 유지한다.

### 수정해야 할 정보

거래량/대금배수의 정의·시간창, VWAP fallback, 틱 매도압력, Trigger 의미, stale Feature 사용, MA 전체창·정의, 상태/시점 이력, 익일·마감 관측·체결비용 Label. 이것이 실제 성과 검증보다 먼저다.

### 제거 검토할 정보

통과자 공통 일봉9점, 포화된 거래대금 보너스, 시각 보너스, 같은 v/h/q의 중복 가산·감점, MA 점수 묶음. **원자료 삭제가 아니라 점수 항목의 제거 비교**다. 비용 후 OOS가 같거나 개선되면 단순한 쪽을 선택한다.

### 새롭게 검증할 정보

Spread·체결가능성, 동일창 후반 거래활동·지속 수급, 당일/연속 급등·갭/변동성, 실제 시장 상대강도, 시점이 검증된 이벤트·거래제약. 전부 점수에 추가하지 말고 안전성→기존 항목 대체→증분효과 순서로 검증한다.

**최종 판단: 현재 평가정보의 방향은 부분적으로 적절하지만, 시간창·중복·Overnight 위험과 입력 품질에 문제가 있고 실전 유효성은 검증 불충분이다. 현재 점수와 순위를 ‘익일 순수익 가능성이 높은 종목을 구분하는 검증된 기준’으로 사용할 근거는 아직 축적되지 않았다.**
