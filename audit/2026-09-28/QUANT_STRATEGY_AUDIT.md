# 15시 추천 → 익일 매도 전략 신뢰성 감사

감사일: 2026-09-28, Asia/Seoul. 코드 기준: `a066e28aa7001b297b75610560cca573e3f63d05`.

## 1. Executive Summary

**현재는 이 프로그램의 추천을 ‘익일 수익 매도 후보를 통계적으로 선별하는 검증된 도구’로 신뢰할 근거가 축적되어 있지 않다.** 규칙 기반 관찰 후보를 만드는 기능은 있으나, 입력 데이터 오류와 성과 추적 결함을 먼저 해결해야 한다. 높은 Score를 상승 확률로 해석하거나, 이전 버전의 저장 성과를 현재 전략의 실적이라고 해석하면 안 된다.

이 결론은 전략이 영구적으로 실패한다는 뜻이 아니다. 현재 코드와 데이터로는 양의 기대값을 입증하지 못했고, 검증을 방해하는 실제 결함이 있다는 뜻이다.

| 확인 항목 | 직접 확인한 결과 | 해석 |
|---|---:|---|
| 전체 추천 실행 / 추천 행 | 30회 / 49건 | 여러 버전·사후 재생 혼재 |
| 현재 v8 공식 FORWARD 실행 | 2일, 2회 | 9/22 추천 0건, 9/23 추천 1건 |
| 현재 v8 공식 완료 관측 | **0건** | Score·Rank·기대수익 검증 불가 |
| 실제/모의 체결 기반 Net 완료 표본 | **확인된 연결 표본 0건** | 모의 체결 원장 자체가 없음; 일반 trade 테이블도 0건 |
| 이전 버전 COMPLETED | 38건, 추천일 6일 | 전부 전체 익일 세션 누락, 16건은 15시 이후 신호 |
| 현재 코드 백테스트: 8/25–9/23 | 추천 0, 완료 0 | 승률·Net 수익률 미산출 |
| 실시간 상태 09:52:26 KST | 프레임 125,155, 오류 54,943 | 최근 오류는 시간/숫자 파싱 실패 |
| 후속 상태 09:53:21 KST | 프레임 127,249, 오류 55,801 | 약 55초 동안 오류 858건 추가 |
| 원본 클래스로 재현 | 15:30 체결 거부, 확정봉 축소 재생성 | 입력·성과 연결의 실제 결함 |

오류/프레임 약 43.90%는 진단 카운터 비율이며, 체결 틱 손실률과 동일하지 않다. 프레임에 제어 메시지도 포함되고 오류 카운터가 처리 예외까지 포괄하기 때문이다. 그래도 최근 파싱 실패가 지속된다는 사실은 확실하다.

감사는 운영 DB에 READ ONLY / REPEATABLE READ 트랜잭션으로 조회했으며, 서버는 GET API만 호출했다. 추천 생성·성과 갱신·주문·설정 변경은 하지 않았다. 애플리케이션 코드는 수정하지 않았고 감사 문서·조회 스크립트·재현 자료만 별도 디렉터리에 작성했다. 서버가 계속 수집 중이어서 서로 다른 조회의 시장 데이터 행 수는 증가할 수 있다. 오늘은 감사 시점이 오전이므로 오늘 15시 결과는 아직 존재하지 않는다.

핵심 원자료: [DB 목록](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/inventory.tsv), [추천·성과](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/outcomes.tsv), [세부 검증](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/detail.tsv), [공식 분석 API](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/analytics.json), [실시간 오류](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/realtime.json).

## 2. Architecture 검증 결과

### 실제 연결

| 단계 | 실제 실행 경로 | 판정 |
|---|---|---|
| 시장 데이터 수집 | KIS H0STCNT0 → KisRealtimeClient → KisRealtimeTickParser → MarketDataService.onTick | 연결됨. 운영 파싱 오류 다수 |
| Feature | MarketFeatureEngine → IntradayFeatureState; QuoteStateStore; FiveMinuteCandleAggregator | 연결됨. 봉 수정·지연 수신 문제 존재 |
| Scanner Filtering | ScannerEngine → ScannerEvaluator; active setting, 과거 6봉, 거래량/모멘텀/유동성 gate | 연결됨. 구독 종목에 한정 |
| 기회/위험 Score | OpportunityRiskScorer → ScannerDetection에 feature/score/settings 저장 | 연결됨. 통계 학습된 가중치 아님 |
| 추천 Filtering/Score | ClosingRecommendationService → ClosingPrecisionEvaluator → 분봉·일봉 MA → ClosingRecommendationScorer | 연결됨 |
| Ranking/최종 추천 | 최종 Score 내림차순, 동점은 탐지시각 내림차순; 최대 1개 | 연결됨. Rank 2 이상 현재 최종 표본 없음 |
| Snapshot | ClosingRecommendationRun + ClosingRecommendation; 설정 hash, 실행 mode, 전체 평가 JSON | 연결됨. 공식 run과 재생 run 구분 가능 |
| 실제/가상 진입 | 실시간 추천의 buyReferencePrice는 과거 탐지가. 백테스트만 15:05 5분봉 시가 사용 | **공식 추천→모의체결 연결 없음** |
| 익일 가격 추적 | Scheduler → OvernightPerformanceService.track → 저장 5M 조회 | 연결되지만 달력/마감봉 조건에 결함 |
| MFE/MAE·목표/손절 | 기준 신호가 대비 익일 관측 고가/저가; 각각의 도달 여부 | 체결 P&L·도달 선후관계 아님 |
| 비용 적용 | OvernightExecutionSimulator를 백테스트 exit 전략에서 호출 | 공식 performance 저장에는 적용되지 않음 |
| Performance 저장 | overnight_performance | 관측값 저장. order/fill/fee/net 원장 아님 |
| 전략 통계 | 공식 FORWARD + official-* + observation-v2 + COMPLETED만 조회 | 실제 연결됨. 현재 N=0 |
| Score Calibration | 10점 구간 목표 도달률·Wilson CI, 시간순 70/30 비교 | 진단 통계. 확률 모델/점수 재보정 아님 |
| Dashboard | 웹 ClosingRecommendationApp에서 run/evaluation/performance/analytics/backtest API 호출 | 연결됨. 모의 계좌는 NOT_READY |

자동 추천은 `.env`의 `CLOSING_AUTOMATION_ENABLED=true`이고 공식 run 2건으로 실행 흔적도 확인했다. 그러나 `MARKET_PRECISION_ENABLED=false`, precision_subscription_session 0건이다. 시장 전체 Broad 후보가 자동으로 정밀 관찰 대상으로 확장되는 기능은 현재 운영에서 꺼져 있다. Broad 후보는 추천의 대체 출처가 아니라 WATCH/EXCLUDED 표시 대상이다.

일반 `backtest/BacktestService`는 별도 장중 Scanner 재생 엔진이다. 동일 설정의 오버나잇 전략 검증 엔진이 아니며 두 화면의 성과를 합칠 수 없다. 모바일 코드에서 closing 전용 호출 연결은 확인되지 않았다. 계좌 수익 계산 클래스는 존재하지만 Controller는 `calculate()` 대신 **항상 `unavailable()`**을 반환한다.

근거: [추천 서비스](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationService.java:77), [자동 실행](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScheduler.java:54), [계좌 API](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/api/ClosingRecommendationController.java:124), [웹 연결](D:/Repo/_dev/app/stock-trading-platform/web/src/ClosingRecommendationApp.tsx:597).

### 소프트웨어 검증 범위

추천·점수·분봉·Feature·파서 관련 기존 백엔드 테스트 88개(24 suite)와 웹 테스트 13개(9 file)가 통과했다. 전체 백엔드 및 모바일 테스트를 모두 실행한 것은 아니다. 컴파일된 원본 집계 클래스에 별도 감사 입력을 넣어 아래 두 결함을 직접 재현했다. 따라서 테스트 통과를 데이터 정확성이나 수익성의 증거로 쓰지 않는다.

[백엔드 테스트 집계](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/backend-tests.json), [감사 재현 입력](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/AuditReproduction.java), [재현 출력](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/reproduction.txt).

## 3. 데이터 신뢰성

### 3.1 실제 보유량과 범위

최초 DB 조회 시 stock 4,393개, 1D 3,649봉/43종목, REALTIME 5M 34,612봉/49종목, BACKFILL 5M 3,828봉/41종목이었다. Scanner 탐지 종목은 46개이며 실시간 Feature 추적 종목은 41개다. 종목 마스터 수와 실제 추천 가능한 데이터 범위는 크게 다르다. 일봉은 2026-05-21~09-22 KST, 분봉은 08-25부터 일부 구독 종목만 존재한다.

저장 OHLC 관계·음수 거래량/거래대금 검사 위반은 이 조회에서 0건이었다. 이는 값이 형식적으로 말이 된다는 확인일 뿐, 거래소 원본 일치나 틱 누락 없음의 확인이 아니다. 원시 프레임 전체, 수신 지연 분포, 정정 전 이력, 체결 호가를 보존한 완전한 감사 원장은 없다.

### 3.2 현재 확인된 핵심 데이터 결함

**(1) 실시간 파싱 실패가 계속 발생한다.** 약 55초 동안 2,094프레임과 오류 858건이 추가됐다. `MinuteOfHour:75`, `SecondOfMinute:70`, `For input string:64.99` 등이 관측된다. Client는 parseMany 실패 시 해당 프레임을 무시하고, 처리 도중 예외도 같은 오류 카운터에 넣는다. 일부 성공 틱만으로 전체 lastTickAt이 갱신되므로 추천의 신선도 검사만으로 이 문제를 차단할 수 없다.

정확한 근본 원인은 원시 실패 프레임이 없어 확정하지 않았다. 고정 46필드 파싱의 레코드 경계·공백·배치 수를 실제 프레임과 대조해야 한다. KIS 공식 샘플도 H0STCNT0를 46필드로 열거하므로 단순히 ‘필드 수가 변경되었다’고 단정하지 않는다. [KIS 공식 실시간 샘플](https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py).

**(2) 확정봉이 지연 틱에 의해 축소 재생성될 수 있다.** `flush()`가 현재 state를 지운 후 같은 5분 구간의 지연 틱을 받으면 `state == null` 경로가 새 state를 만든다. 재현에서는 확정 거래량 600인 봉이 거래량 50, 같은 revision 0인 봉으로 재출력됐다. `MarketDataService.persist()`는 이를 `StockCandle.revise()`로 저장하며 이전 전체 봉을 병합하지 않는다. 고가/저가/거래량·기준 평균·유동성 필터가 함께 영향을 받는다. 실제 모든 축소봉의 발생 건수를 확정한 것은 아니지만 코드 경로와 재현은 확인됐다.

**(3) 15:30 입력과 완료 조건이 모순이다.** 집계기의 허용 시간은 `[09:00,15:30)`로 15:30 체결을 거부한다. 반면 관측 서비스는 “집계기가 15:30 별도 봉을 만든다”는 주석 아래 09:00~15:30 시작점 79개를 필수로 요구한다. DB에서 REALTIME/BACKFILL 양쪽 모두 15:30 봉은 0개다. 정상적인 실시간 경로만으로 이 완료 조건을 만족시킬 수 없다. 별도 검증된 마감가격/보충 데이터 경로가 필요하다.

**(4) 휴장일 설정이 비어 있고 실제 잘못된 익일이 저장됐다.** 추천 117(9/23)의 expected_session_date는 9/24이며 missing interval 79개다. 9/24~27 추석 연휴 뒤 9/28이 다음 거래 세션이어야 한다. 정부 금융 안내도 해당 연휴와 증시 결제 이연을 설명한다. [금융위원회 추석 금융 안내](https://m.korea.kr/briefing/pressReleaseView.do?newsId=156781483&pWiseMinistry=ministryNews&repCode=C00003&repCodeType=%EC%A0%95%EB%B6%80%EB%B6%80%EC%B2%98).

기존 observation은 초기 session date를 고정하므로 달력 설정만 수정해도 이미 잘못 저장된 expected date가 자동 복구되는 구조가 아니다. Scheduler도 당일의 previousTradingDay만 찾아서 과거 미완료 건을 계속 회수하지 않는다. 이번 9/23 결과는 현재 오전 감사 시점에 올바른 9/28 장이 아직 끝나지 않았다는 점도 분리해야 한다. ‘오늘까지 완료되지 않았으니 투자 손실’이라는 뜻은 아니다.

근거: [집계기](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/candle/application/FiveMinuteCandleAggregator.java:53), [관측 완료 조건](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightPerformanceService.java:97), [달력](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingTradingCalendar.java:25), [추가 DB 검사](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/checks.tsv).

### 3.3 Feature 전체 목록과 Look-Ahead 감사

아래 ‘가능’은 원천이 정상이고 해당 시점에 수신됐을 때의 조건부 판단이다. 현재 파싱 오류까지 무시한 정확성 보증이 아니다. 메타데이터 stockCode/businessDate/occurredAt/featureVersion은 식별·시점 감사용이며 수익 예측 Feature가 아니다.

| Feature | 데이터 기준시간 | 15시 사용 가능 | 미래 데이터 위험 | 판정 |
|---|---|---|---|---|
| price / detectedPrice | 탐지 당시 틱·진행봉 종가, 14:30~15:00 | 가능 | Feature 틱 시각과 탐지시각 불일치 가능 | 조건부; 실제 진입가격 아님 |
| cumulativeVolume | 틱 시점 누적 거래량 | 가능 | 당일 최종 거래량으로 치환하면 오염 | 현재 틱 기준, 수신 누락 감사 필요 |
| cumulativeTradingValue / dailyTradingValue | 틱 시점 누적 거래대금 | 가능 | 마감 최종값 사용 경로는 추천에서 확인되지 않음 | 시간상 조건부 적합 |
| openPrice | 당일 장중 틱 제공 시가 | 가능 | 현재 parser는 원본 영업일 대신 로컬 오늘 사용 | 세션 검증 부족 |
| highPrice / lowPrice | 해당 틱까지의 당일 고저 | 가능 | 15시 이후 최종 고저 사용은 금지 | snapshot 기준; 원본 정합 미입증 |
| current5m OHLC/volume/value | 탐지 시점 진행 중인 봉 | 가능 | 확정봉 전체값을 시작시각에 사용하면 미래 정보 | 라이브 진행봉; 지연 재생성 결함 |
| fiveMinuteChangeRate | 현재 봉 종가 / 직전 봉 종가 − 1 | 가능 | 이전 6봉의 확정·연속성 검사 불충분 | 불균일한 시간 구간 위험 |
| Scanner volumeRatio | 현재 5분봉 거래량 / 이전 6봉 평균 | 가능 | 미래 normalization 없음 | 결측·재시작·부분봉 편향 |
| Feature volumeRatio | 메모리 현재 bucket / 최근 최대 6개 완료 bucket 평균 | 가능 | 미래 normalization 없음 | Scanner ratio와 정의·값 다름 |
| turnoverRatio | 현재 bucket 거래대금 / 메모리 완료 bucket 평균 | 가능 | 미래값 사용 없음 | 시총 회전율이 아님, 재시작 민감 |
| VWAP | 틱 누적 거래대금 / 누적 거래량 | 가능 | 누적대금 없으면 틱 근사로 fallback | 품질 플래그와 원천 검증 부족 |
| vwapDistanceRate | (현재가−VWAP)/VWAP | 가능 | 동일 snapshot 전제 | 중복 가점, 통계효과 미확인 |
| vwapSlopeRate | 직전 틱 VWAP 대비 변화 | 가능 | 미래값 없음 | 고정 시간 slope 아님; 최종 점수 직접 미사용 |
| dayHighDistanceRate | (그 시점 고가−현재가)/그 시점 고가 | 가능 | 이후 고가 혼입 금지 | 틱 snapshot 경로는 조건부 적합 |
| tradeStrength | 틱 체결강도 | 가능 | 누락 문자열이 parser에서 0으로 전환 | ‘값 존재’와 ‘유효값’ 구분 약함 |
| buyVolumeDelta / sellVolumeDelta | 직전 수신 이후 누적 매수/매도 차이 | 가능 | 미래값 없음 | 누락·지연·재시작 영향, 위험점수 반영 |
| buyRatio | 틱 제공 매수 비율 | 가능 | 미래값 없음 | snapshot만 저장, 최종 점수 직접 미사용 |
| tradingHalted | 틱 플래그 | 가능 | 마스터의 현재 상태와 다를 수 있음 | 최종 필터는 stock 현재 상태 의존 |
| viStandardPrice | 틱 VI 기준가 | 가능 | 미래값 없음 | VI 발동/체결 가능성 검증과는 다름 |
| 분봉 MA5/20/60 및 거리 | 탐지시각 전에 끝난 확정봉 | 가능 | updatedAt≤탐지시각 필터 | 방향은 적절; 수정 이력·연속성 부족 |
| 분봉 정배열/골든크로스/MA20 지지·이탈 | 위 MA와 직전 봉 | 가능 | MA60 부족 시 5/20 조건으로 정의 변경 | 표본마다 신호 의미 달라질 수 있음 |
| 일봉 MA5/20/60·slope·거리 | 전일까지 일봉; updatedAt≤탐지시각 | 가능 | 당일 종가는 배제 | 원주가 Corporate Action 미조정 |
| 일봉 정배열/MA20 상승/종가 상회/과열 | **전일 종가** 기준 | 가능 | 코드의 close는 당일 최종 종가 아님 | 정상 구분; 시점·기업행사 검증 필요 |
| latestFiveMinuteClose/value, signalRetained, liquidity | 15시까지 끝난 최근 봉 | 엄격한 수신 15시 기준에는 불일치 | **15:00:10까지 수신·수정 허용** | 10초 grace의 전략 시점 정의 필요 |
| closingRecency | 탐지시각 | 가능 | 15시 이후 탐지는 v8 입력에서 배제 | 과거 버전은 실제 15시 이후 포함 |
| opportunity/risk/final score | 위 항목의 고정 가중치 | 조건부 | 상위 Feature 문제 전파 | 확률 아님 |
| Broad ranking/등락/거래대금/고저 위치 | 랭킹·quote 수신 snapshot | 관찰용 가능 | 비동기 enrichment 시각 주의 | 현재 최종 추천에 직접 진입하지 않음 |
| Market Regime / 시장·업종 상대강도 | 유효한 당시 지수·업종 시계열 없음 | 입증 불가 | 사후 분류는 누출 가능 | 최종 경로 UNVERIFIED |
| 외국인/기관 수급·프로그램 매매 | 추천 Feature 경로 없음 | 해당 없음 | 마감 확정 수급 추가 시 주의 | 미사용 |
| 뉴스/공시 | 공개/수신/정정 시각 데이터 없음 | 해당 없음 | 사후 뉴스 사용 위험 | 미사용 |
| ATR/독립 변동성·overnight gap 예측 | 최종 점수에 독립 Feature 없음 | 해당 없음 | 익일 gap을 당일 Feature로 쓰면 오염 | 미사용 |
| 미래 캔들·익일 가격 | 성과·exit 계산에서만 사용 | 추천에는 불가 | 성과 데이터와 Feature 분리 필수 | v8 추천 직접 혼입 증거 없음 |
| normalization | 고정 cap, 과거 6봉 평균; 학습 scaler 없음 | 가능 | 전체기간 fit scaler는 발견 안 됨 | 미래 normalization 증거 없음 |

엄격히 ‘15:00까지 실제 수신한 자료만’이라는 요구에는 `availableBy=15:00:10`이 맞지 않는다. 늦게 전달된 과거 사건과 미래 사건을 구분해야 하며, 15:00 이후 가격을 실제 사용했다고 입증한 것은 아니다. v8 공식 1건의 Feature occurredAt=14:52:17, detection/receipt=14:52:24.200536, 추천 완료=15:00:27.077965로 기본 선후관계는 확인됐다.

전체 탐지 18,174건을 조회한 시점에 received_at NULL 14,056건, feature occurredAt이 detectedAt보다 뒤인 기록 5,485건이 있었다. 상당수는 초 단위 원천시각과 로컬 시계의 수 초 차이다. 이를 모두 look-ahead라고 단정하지 않는다. 같은 조회에서 탐지시각≤15시인데 Feature occurredAt>15시인 건은 0건이었다. 원시 수신시각·서버 동기화로 해소해야 할 시간 정합성 문제다.

반면 LEGACY 추천 48건 중 **26건은 15시 이후 탐지**, 완료 관측 38건 중 **16건은 15시 이후 탐지**이며 38건 모두 추천 생성이 15:20 이후다. 이 표본을 ‘15시 실제 추천 검증’으로 사용하는 것은 명백히 부적절하다.

### 3.4 Point-in-time 재현성 실증

9/23 공식 run 27에서는 042700이 QUALIFIED, Score 86.544로 저장됐다. 같은 날짜를 오늘 재생한 기존 run 30에서는 RECEIVED_AFTER_EVALUATION으로 탈락한다. 공식 snapshot은 14:55 봉을 사용했지만 현재 DB의 그 봉 updatedAt은 15:00:33.749523으로 grace를 초과한다. 재생은 14:50 봉으로 후퇴하고 거래대금 gate도 실패한다.

현재 코드는 사후 수정값을 과거에 사용하지 않도록 버리는 방향이다. 그러나 수정 전 버전이 없으므로 당시 판단을 재현할 수 없다. Snapshot 보존은 장점이지만 response hash가 캔들 revision·원천 데이터 이력까지 보존하지는 않는다. 이 때문에 백테스트 0건은 ‘추천이 원래 없었다’나 ‘전략이 손실 0건’이라는 뜻이 아니다.

## 4. 추천 Algorithm

### Universe와 필터

1. 명목 Universe는 현재 활성 KOSPI/KOSDAQ 마스터. Broad는 거래대금/거래량/상승률/체결강도/고가근접 랭킹 합집합이며 전종목 전수 정밀평가가 아니다.
2. 실제 정밀 Universe는 관심종목/구독된 종목에서 발생한 ScannerDetection. 현재 자동 precision 배분은 꺼져 있다.
3. Scanner는 과거 6봉과 현재 봉으로 지표를 만들고 9개 활성 탐지 유형을 평가한다. 현재 저장 설정은 거래량 2배, 모멘텀 상승 2%+거래량 2배, Volume Breakout 2.5배 등이다. Scanner 최소 가격·거래대금 설정은 현재 모두 0이며 최종 추천 단계에서 강화된다.
4. 중복/쿨다운을 통과해 **조건 진입 이벤트**가 저장된다. 지속적으로 조건을 만족한 모든 종목이 매 순간 새 이벤트를 만드는 구조는 아니다. 14:30 전에 탐지된 뒤 상태가 유지된 종목은 최종 구간에 이벤트가 없을 수 있다.
5. 추천은 14:30≤detectedAt≤15:00에 속한 종목별 **가장 최근 이벤트 하나**만 선택한다. 최고점 이벤트를 선택하는 구조가 아니다.
6. inactive/관리/거래정지/ETF/ETN 제외; Opportunity≥35, Risk≤65.
7. VWAP 거리·고가거리·체결강도·volumeRatio 존재, 신호 나이≤30분, 수신시각≤15시(단 NULL은 엄격 차단하지 않음), 연속 확정봉≥4개/20분.
8. 일봉≥21개, 전 거래일까지 준비, 전일 종가>MA20, MA20 상승. 최근 봉 완료시각이 15시로부터 10분 이내.
9. 최근 봉 종가≥탐지가×0.99, 탐지가≤일봉 MA20×1.12, 누적 거래대금≥10억원, 최근 5분 거래대금≥2천만원.
10. 최종 Score≥55. Score 내림차순→탐지시각 내림차순으로 정렬하되 최대 1종목. 완전 동점의 별도 stock-code tie-break는 없다.

`limit=10`이나 30을 전달해도 현재 최종 추천은 최대 1개다. 현재 운영 기준은 Score≥80이 아니다. 조건 탈락 또는 준비 부족이면 0개이며 Broad는 최종 추천을 채우지 않는다. `orderEligible=false`, 시장/업종/계좌 노출 UNVERIFIED가 저장된다.

### 실제 Score 공식

`cap(x,m)=min(max(x,0),m)`, `x+=max(x,0)`. 단위는 수익률 percentage point다. g=5분 등락률, q=Scanner 거래량배수, v=VWAP 거리(%), h=고가거리(%), t=체결강도.

기초 Opportunity:

`O = cap(12g+,30) + cap(15(q−1)+,25) + cap(8v+,20) + cap(0.25(t−100)+,15) + cap(10−5h,10)`

기초 Risk:

`R = cap(8(v−3)+,25) + cap(0.25(100−t)+,20) + cap(10(−g)+,20) + cap(5(h−2)+,15) + sellPressure`

sellPressure는 매도 delta가 매수 delta보다 클 때 `20×sellDelta/(buyDelta+sellDelta)`(최대 20), 아니면 0이다. 이 비대칭 전환도 추정된 모델이 아니라 고정 규칙이다.

최종 가점:

| 항목 | 계산 |
|---|---|
| baseOpportunity | cap(0.45 O,35) |
| closingRecency | 14:30 이후 경과 **정수 분** ×15/60; 15시 최대 7.5 |
| liquidity | cap(누적 거래대금/1억원,15) |
| vwapPosition | cap(5v+,15) |
| dayHighProximity | cap(10−4h,10) |
| volumeExpansion | cap(5(q−1)+,10) |
| intradayBullishAlignment / GoldenCross / Ma20Support | 각각 8 / 5 / 4 |
| dailyTrendAlignment / Ma20Rising / CloseAboveMa20 | 각각 8 / 5 / 4 |

최종 감점:

| 항목 | 계산 |
|---|---|
| baseRisk | cap(0.45 R,40) |
| vwapOverextension | cap(5(v−5)+,20) |
| lateNegativeMomentum | g<0이면 10 |
| farFromDayHigh | cap(4(h−3)+,15) |
| weakTradeStrength | cap(0.15(100−t)+,15) |
| intradayMa20Breakdown | 이탈 시 12 |
| dailyTrendWeakness | 전일 종가≤MA20:6, MA5≤MA20:4, MA20 비상승:3 합을 최대 10 |
| dailyMaOverextension | 전일 종가의 MA20 거리>12%이면 10 |

`RecommendationScore = round3(cap(Σ가점−Σ감점,100))`.

O/R과 최종 점수에 VWAP·거래량·고가거리·체결강도가 중복 반영된다. 독립적인 여러 증거가 쌓인다는 뜻이 아니며, 높은 점수 포화도와 실제 성과 단조성을 검증해야 한다. 가중치의 최적성·통계적 유의성을 증명한 데이터는 없다.

### 실제 86.544점 역추적

현재 v8의 유일한 공식 추천은 9/23의 042700(한미반도체), Rank 1이다. 기준가는 14:52 탐지가 243,000원이고 15:05 시가 관측값은 242,500원이다. 둘을 같은 진입가격으로 볼 수 없다.

`O=48.734, R=0, v=0.920486, h=0.205339, q=2.066532`.

`21.930300 + 5.500000 + 15 + 4.602430 + 9.178644 + 5.332660 + 8 + 0 + 0 + 8 + 5 + 4 − 0 = 86.544034 → 86.544`.

사유 JSON은 항목별로 이미 반올림되어 저장되므로 표시된 항목 합과 마지막 자릿수가 다를 수 있다. 이 점수는 추세·유동성·거래량 조건의 합이지 86.544% 상승 확률이 아니다. 이 종목의 v8 완료 성과 N=0이다.

근거: [Scorer](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScorer.java:38), [기초 점수](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/scanner/score/OpportunityRiskScorer.java:17), [필터](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingPrecisionEvaluator.java:72), [공식 snapshot](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/official_20260923.json).

## 5. Score Calibration

**현재 버전의 유효 완료 표본은 0건. 모든 Score 구간의 승률·Net 기대값·확률은 미산출이다.** N=0의 미산출을 0% 승률로 바꾸지 않는다.

현재 서비스는 10점 단위 목표수익 도달률과 Wilson CI를 반환한다. 사용자가 요청한 5점 단위, Net/PF/Median/+1/+2/+3 동시 비교를 모두 제공하지 않는다. 별도 확률 보정 모델, reliability curve, Brier score, OOS calibration이나 점수 가중치 자동 업데이트는 연결되어 있지 않다. ‘분석 API가 존재’와 ‘Score가 교정됐다’는 다르다.

다음은 요청에 따라 계산한 **과거 기록 진단값**이다. 여러 버전·사후추천·불완전 세션이 섞여 있으므로 투자 성과나 v8의 검증 결과가 아니다. Mean은 저장 close 관측 Gross, H/L은 관측 고저수익이다. Net은 모든 행에서 미산출이다.

| Score | N | 승률 % | 평균 Gross % | Median % | +1% % | +2% % | +3% % | H % | L % | Gross PF |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 70–74.999 | 7 | 14.286 | -1.925 | -1.564 | 57.143 | 28.571 | 0 | 0.500 | -3.076 | 0.039 |
| 75–79.999 | 5 | 0 | -2.443 | -1.852 | 20 | 20 | 20 | -0.390 | -3.445 | 0 |
| 80–84.999 | 4 | 25 | -1.117 | -1.834 | 25 | 25 | 0 | 0.631 | -2.455 | 0.298 |
| 85–89.999 | 3 | 66.667 | -1.080 | 0.443 | 66.667 | 0 | 0 | 1.138 | -2.359 | 0.268 |
| 90–94.999 | 1 | 100 | 0.213 | 0.213 | 100 | 0 | 0 | 1.387 | -2.988 | 미산출 |
| 95–100 | 0 | 미산출 | 미산출 | 미산출 | 미산출 | 미산출 | 미산출 | 미산출 | 미산출 | 미산출 |

90점대 1/1은 근거가 아니다. 독립 표본을 가정한 Wilson 95% 구간도 약 20.65~100%다. 날짜·버전 효과가 혼재되어 높은 점수의 성과 개선을 식별할 수 없다. PF는 수익 합/손실 절댓값 합이며 계좌 PF가 아니다. 손실 분모가 없을 때 임의로 999를 부여하지 않았다.

### Feature 유효성

현재 Feature별 유효 완료 표본은 모두 N=0. 과거 38건에 한해서 7개 Feature의 중앙값 양분 집계를 추가했다. 평균·Median·승률·+1/+2/+3·고저수익·PF·N은 [진단 통계 부록](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/LEGACY_DIAGNOSTICS.md)에 모두 수록했다.

| Feature | 낮은 쪽 N / 평균 Gross % | 높은 쪽 N / 평균 Gross % | 해석 |
|---|---|---|---|
| Scanner 거래량배수 | 19 / -1.239 | 19 / -1.532 | 우위 근거 없음 |
| 누적 거래대금 | 19 / -0.642 | 19 / -2.129 | 고거래대금 우위 근거 없음 |
| 5분 모멘텀 | 19 / -0.896 | 19 / -1.875 | 효과 검증 안 됨 |
| 체결강도 | 19 / -1.428 | 19 / -1.343 | 차이 해석 불가 |
| VWAP 거리 | 19 / -0.946 | 19 / -1.825 | 상승 거리 가점의 유효성 미확인 |
| 고가거리 | 19 / -1.402 | 19 / -1.368 | 차이 해석 불가 |
| 거래대금 증가배수 | 19 / -2.130 | 19 / -0.641 | 사후 분할; 인과/예측 우위 아님 |

이 경계는 같은 데이터에서 사후 산출한 중앙값이다. 독립 validation이 아니며, 이를 근거로 Weight를 바꾸면 data snooping이다. 분봉/일봉 MA는 원본 feature_snapshot의 일반 지표와 별도 사유 JSON에 있고, 비교 가능한 v8 성과가 없어 유효성 미산출이다. 외국인/기관·프로그램·뉴스·지수/업종 상대강도·Regime은 유효 데이터가 없어 분석 불가다.

### Threshold 및 NO TRADE

현재 실제 최종 기준은 55점이다. 과거 선발 종목만 사후로 자른 진단 결과는 다음과 같다.

| Score 기준 | N | 관측 승률 % | 평균 Gross % | Net |
|---|---:|---:|---:|---|
| ≥75 | 13 | 30.769 | -1.516 | 미산출 |
| ≥80 | 8 | 50.000 | -0.936 | 미산출 |
| ≥85 | 4 | 75.000 | -0.756 | 미산출 |
| ≥90 | 1 | 100.000 | 0.213 | 미산출 |

90을 선택하면 과최적화 위험이 크다. 탈락 종목의 성과가 체계적으로 추적되지 않으므로 threshold를 낮춘 전략의 결과도 이 표로 추정할 수 없다.

공식 NO TRADE는 9/22 1일이다. 9/23 추천 1건은 미완료다. 따라서 NO TRADE 대 강제 저점수 매매의 비교 가능한 표본은 0건이다. NO TRADE 이유에는 데이터 미준비·수신시각 문제가 섞여 있다. ‘시장 위험을 잘 회피했다’고 판단할 수 없다. 이미 보존하는 candidate evaluation을 활용한 동시점 counterfactual 관측이면 충분하며 추천을 억지로 늘릴 필요는 없다.

## 6. Ranking 검증

현재 최종 Rank는 최대 1이다. v8 Rank 1 완료 N=0, Rank 2/3/4~5/그 이하 최종 추천 N=0. 따라서 TOP 1이 TOP 5보다 낫다는 질문은 현재 정책과 표본으로 답할 수 없다.

아래는 이전 버전의 불완전 관측 진단이다. Net은 전부 미산출이며 순위 효과의 증거로 사용할 수 없다.

| 순위 | N | 추천일 | 승률 % | 평균 Gross % | Median % | +1/+2/+3 도달 % | H/L % | PF |
|---|---:|---:|---:|---:|---:|---|---|---:|
| TOP 1 | 6 | 6 | 33.333 | -1.024 | -1.107 | 50/16.667/16.667 | 0.891/-2.377 | 0.310 |
| TOP 2 | 5 | 5 | 40 | -1.591 | -1.403 | 40/0/0 | -0.494/-3.007 | 0.175 |
| TOP 3 | 5 | 5 | 20 | -1.612 | -1.529 | 40/20/20 | 0.527/-2.950 | 0.084 |
| TOP 4~5 | 8 | 4 | 12.5 | -1.505 | -1.708 | 50/37.5/0 | 0.837/-2.346 | 0.136 |
| TOP 6 이하 | 14 | 3 | 14.286 | -1.317 | -1.221 | 14.286/0/0 | -0.762/-2.992 | 0.102 |

동일 날짜끼리 짝지은 비교가 아니고 표본 날짜도 다르다. 순위가 낮아질수록 수익이 일관되게 떨어지는 패턴도 없다. 1위 제한을 풀어 실전 추천 수를 늘리기보다, 이미 저장된 적격·관찰 후보에 당시 Score/가상 Rank를 보존하고 동일 exit로 관측하는 것이 최소 검증 경로다.

## 7. Backtest 결과

### 현재 코드 재실행

GET `/api/v1/closing-recommendations/backtest`, from=2026-08-25, to=2026-09-23, limit=10, minOpportunity=35, maxRisk=65, target=3, stop=-2.

| 결과 | 값 |
|---|---|
| tradingDays | 16 — 실제 캘린더 거래일 수가 아니라 입력 탐지가 있는 날짜 수 |
| virtualRecommendations / completed / dataMissing | 0 / 0 / 0 |
| Open/Close 승률·평균·MFE/MAE | 모두 null |
| 6개 Exit Strategy 표본 | 각각 0 |
| Integrity | WARNING: 표본 20 미만 |

[재실행 전체 응답](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/backtest.json). 오늘 미완료 시장을 결과에 섞지 않았다. 0표본에서 costsApplied=false가 반환된다고 실제 프로세스의 비용 설정값을 역추론할 수는 없다.

### 엔진 자체의 신뢰성

| 감사 항목 | 코드/실제 결과 | 판단 |
|---|---|---|
| Look-ahead 차단 | 15시 detection cutoff, 완료봉/updatedAt 검사가 있음 | 부분 확인; 10초 grace·receipt NULL·수정 이력 문제 |
| 시점별 Universe | 현재 stock.isActive/managed/halted 사용; 상장/폐지/상태 이력 없음 | Survivorship/상태 미래참조 위험 |
| 체결 진입 | 고정 15:05 확정봉 시가 | 실제 추천 완료 이후 주문 가능·호가·잔량·체결 확인 없음 |
| 늦은 FORWARD | 15:20까지 생성 가능, 백테스트는 완료시각과 무관하게 15:05 사용 | 15:05 이후 완료 run과 비교하면 실행 선후 불일치 |
| 익일 누락 | 한 개라도 익일 확정봉이 있으면 COMPLETED | **불완전 세션을 완료 집계** |
| 검증 gate | Integrity 경고/오류가 통계 집계를 막지 않음 | Warning이어도 성과가 산출될 수 있음 |
| 목표·손절 동시 도달 | targetOrStop은 STOP 우선·ambiguous 표시 | 보수적 모형은 있으나 진짜 선후관계 아님 |
| Gap stop | targetOrStop은 시가 gap 반영 | 부분 확인 |
| trailing exits | VWAP/MA20/EXTEND는 gap시 시가가 아니라 stopPrice로 처리하는 경로 존재 | 손실 과소추정 가능 |
| 종가 신호 청산 | 해당 봉 종가로 지표 확인 후 같은 종가에 매도 | 주문 지연·다음 호가 반영 없음 |
| 제한가격·VI·정지 | 가격 도달만으로 체결 모사; 가격단위·잔량·매매정지 시간 미모형화 | 실전 체결 신뢰성 부족 |
| Corporate Action | 원주가 일봉; 배당락/분할/권리락 사건 연계 없음 | MA·수익률 왜곡 위험 |
| 휴장·특별시간 | 주말+수동 holiday; 고정 09:00/15:30 | 실제 휴장일 오류 확인 |
| 비용 | 설정형 fee/tax/slippage는 구현; 기본 전부 0; spread 별도 없음 | 비용 반영 검증 불충분 |
| 알고리즘 비교 | 같은 기간 최고 관측 평균 수익/승률을 recommendedDefault로 표시 | 사후 선택, OOS 최적화 아님 |
| 비교군 독립성 | NO_MA/BASELINE도 먼저 MA 포함 QUALIFIED 필터를 통과 | 순수한 MA 제거 ablation 아님 |
| Confidence | 완료 20→MEDIUM, 50→HIGH | 표본 개수 규칙이지 통계 신뢰성 등급 아님 |

예를 들어 진입 100, 손절 98, 익일 시가 95이면 targetOrStop은 95로 gap 손절한다. 다른 trailing 경로는 저가≤98에 stopPrice 98을 줄 수 있다. 같은 비용 모형을 사용해도 exit별 Gross가 일관된 체결 가정을 갖지 않는다.

일반 장중 BacktestService는 VWAP를 실제 누적 거래대금/거래량 대신 제한된 warmup 구간의 typical-price×volume으로 근사하고 체결강도를 100으로 고정한다. turnoverRatio 정의도 라이브와 다르다. 쿨다운/조건 재진입 상태를 그대로 재현하지 않으며 6봉·12봉 뒤를 30/60분처럼 취급해 시간 공백을 제대로 반영하지 못한다. 이 별도 엔진은 오버나잇 실전 검증의 대체재가 아니다.

근거: [백테스트 완료·진입](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightBacktestService.java:272), [Integrity](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/BacktestIntegrityService.java:24), [체결 모사](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightExecutionSimulator.java:23), [일반 재생 엔진](D:/Repo/_dev/app/stock-trading-platform/backend/src/main/java/com/sunmo/stockplatform/backtest/application/BacktestService.java:56).

### 이전 저장 성과의 정확한 위치

이전 COMPLETED 38건의 저장 관측 평균은 -1.385%, Median -1.432%, 양수 8/38=21.053%, 고가 기준 +1/+2/+3 도달은 13/38, 5/38, 2/38이다. 이 수치조차 유효한 전략 수익률로 인정할 수 없다. 38건 모두 15:30 종가까지의 세션이 없고 일부 첫 봉도 09:10이다. 9/4 추천 3건은 익일 마지막 봉이 14:25다. ‘완료’라는 이름이 검증을 대신하지 못한다.

## 8. Out-of-Sample 결과

**유효 OOS 완료 N=0. Train/Validation/OOS/Paper의 실현 Net 성과는 전부 미산출이다.**

현재 구현은 사용자가 요청한 날짜 범위의 공식 완료 관측을 추천일 기준 앞 70%/뒤 30%로 나눈다. 앞 구간의 Score/4개 Feature 중앙값으로 뒤 구간을 비교하는 점은 방향상 적절하다. 그러나 고정 holdout, 전략 개발 당시 정보, 파라미터 확정시각, 독립 Paper 실행, purging/embargo가 보존된 검증은 아니다. 날짜 범위를 바꾸면 분할도 바뀐다.

서비스의 개발 20건·검증 10건 READY는 단순 계산 준비 조건이지 채택 근거가 아니다. 저장소 수정 이력과 DB의 버전 변천도 있으므로 과거를 뒤에서 잘라 놓았다는 이유만으로 미사용 데이터라고 인증할 수 없다. 현재 공식 조회는 observation version만 고르고 recommendation strategy/settings hash별 모집단을 고정하지 않아 향후 여러 전략 버전이 섞일 수 있다.

권장 검증은 수정 완료 후 현 규칙·threshold·exit·비용을 동결하고 다음 거래일부터 전진 수집하는 것이다. 이후 변경은 새 버전으로 분리하고 기존 OOS를 다시 훈련·선택에 쓰면 새 OOS를 확보해야 한다.

## 9. Paper Trading 결과

Snapshot 기반 관측은 paper execution이 아니다. 현재 기록에는 주문시각·주문수량·미체결·부분체결·거부·진입/청산 체결가·수수료·세금·spread·slippage 원장이 없다. 일반 trade 테이블 0건, account-performance는 PAPER_EXECUTION_LEDGER_MISSING을 반환한다.

v8 공식 추천 1건은 관측 서비스에서 신호가격 243,000원을 기준으로 삼지만 백테스트 예상 진입은 15:05 시가 242,500원이다. 이 차이만으로도 동일 종목의 목표수익·손절·수익률이 달라진다. `entryModel=NEXT_FINAL_5M_OPEN`이라는 설정 문자열은 실제 paper entry를 생성하지 않는다.

397개의 overnight_position_decision은 매도/보유 **판단 기록**이다. 397번의 거래가 아니다. 해당 서비스는 추천 다음날 이후 현재 quote를 조회할 수 있어 특정 익일·실제 보유 잔량·체결 exit와도 동일하지 않다. 실시간 판단과 백테스트 trailing 구현도 같은 상태 머신이 아니다.

공식 Paper와 백테스트의 성과 차이는 양쪽 유효 표본이 없어 계산 불가다. 위와 같은 가격 기준·exit·시간·비용의 불일치는 코드로 확인됐다.

## 10. Market Regime

| Regime | 유효 분류·완료 N | 승률/평균 Net/MFE/MAE/PF |
|---|---:|---|
| STRONG_BULL | 0 | 미산출 |
| BULL | 0 | 미산출 |
| NEUTRAL | 0 | 미산출 |
| BEAR | 0 | 미산출 |
| HIGH_VOLATILITY | 0 | 미산출 |

Broad의 RISK_ON/RISK_OFF/MIXED는 수집된 랭킹 상위 종목의 평균 등락과 상승/하락 비율이다. 전 시장 지수 Regime이 아니며, 상승·거래활발 종목으로 사전 선별된 표본이므로 시장 전체를 대표하지 않는다. 최종 Closing 경로에는 이를 검증된 분류로 연결하지 않는다.

KOSPI/KOSDAQ 구분은 가능하지만 Regime은 아니다. 과거 관측은 KOSPI N=26, 평균 -1.832%, KOSDAQ N=12, 평균 -0.418%였으나 위 데이터 결함 때문에 시장별 전략 우위를 주장할 수 없다.

업종·테마·시총·상장유효기간·지수 상대강도 이력이 없어 해당 집중도와 일반화 가능성을 평가할 수 없다. 실제 Universe가 소수 관심/구독 종목에 치우쳤다는 selection bias는 확인된다. 기존 기준가격·거래대금으로 가격대/유동성 구간을 나눌 수는 있지만 현재 유효 전진 완료 N=0이므로 성과 의존성을 검증할 수 없다.

## 11. Risk

### MFE / MAE

현재 저장 값은 `100×(익일 관측 최고/최저가 ÷ 신호가 −1)`이다. 체결 이후 전체 경로가 아니며 익일 시장 관측만 포함한다. 매수 당일 15:05 이후의 손실도 포함하지 않는다. 과거 38건 중 high-return이 음수인 사례 16건, low-return이 양수인 사례 1건이 있어 진입점 0을 포함한 전형적 MFE≥0/MAE≤0 정의와도 다르다.

과거 관측 고가수익 평균/중앙값은 +0.041%/+0.156%, 저가수익 평균/중앙값은 -2.755%/-2.582%다. 이 값으로 안전한 목표수익이나 손절폭을 정하면 안 된다. 목표 이전 MAE, 전체 MFE/MAE ratio distribution은 현재 유효 표본 N=0이다. OHLC 봉 안에서 목표와 손절의 순서는 알 수 없으며 더 촘촘한 시계열이 있더라도 실제 체결 증거를 대신하지 못한다.

### 익일 매도 시점

| 구간 | 유효 전체세션/체결 표본 | +1/+2/+3 최초도달률 |
|---|---:|---|
| 09:00~09:05 | 0 | 미산출 |
| 09:05~09:30 | 0 | 미산출 |
| 09:30~10:00 | 0 | 미산출 |
| 10:00~11:00 | 0 | 미산출 |
| 11:00 이후 | 0 | 미산출 |
| 종가 | 0 | 미산출 |

Performance에는 최초 도달시각이나 목표 전 MAE가 없다. 저장 5M로 구간별 탐색은 가능하지만 현재는 세션 누락·기준가 오류가 있어 신뢰할 도달률을 낼 수 없다. 특히 5분봉 끝에서 확인한 신호를 같은 봉 시작시각의 청산으로 기록하면 시간대 분석도 잘못된다. 현재 exit 전략의 합리성은 검증 불충분이다.

### Gap Risk

정확한 overnight gap은 `익일 시가 / 당일 실제 종가 −1`이다. 현재 analytics의 GAP_DOWN은 `익일 첫 관측 시가 / 신호가 −1 <0`을 센다. 매수 후 당일 움직임과 진짜 overnight gap이 섞이며, 09시 봉 누락 시 실제 시가조차 아니다.

과거 신호가 대비 첫 관측 시가 수익은 하락 30/38, 상승 8/38, 평균 -1.425%, -3% 이하 6/38이다. 이는 정확한 Gap Down 빈도가 아니다. 실제 gap up/down, 평균 gap, 하락갭 후 회복, 상승갭 후 추가상승/하락, 큰 gap 빈도와 크기는 당일 종가·익일 공식 시가·완전한 다음 세션을 확보하기 전까지 미산출로 두어야 한다.

### 거래비용

백테스트 모형은 다음과 같다.

`buyCash = entryReference × (1 + buyFee + buySlippage)`

`sellCash = exitReference × (1 − sellFee − sellTax − sellSlippage)`

`Net% = 100 × (sellCash/buyCash −1)` (계산 시 각 비용은 %를 100으로 나눔).

공식 observation에는 이 모형이 적용되지 않는다. application.yml의 5가지 비용 기본값은 모두 0이고 .env에는 비용 override가 없다. 실행 프로세스의 다른 환경/명령행 override까지 확인한 것은 아니므로 최종 runtime 값을 단정하지 않는다. 명시적 spread 모형, 수량·호가단위·시장충격·가격제한 반영도 없다. 세율을 임의로 넣어 Net 결과를 만들지 않았다. 해당 계좌·시장·거래일의 적용 비용을 확정하고 함께 snapshot해야 한다.

Gross/Net을 모두 검증할 수 있는 현재 표본은 0건이다. 가상 비용을 과거 불완전 수익에서 단순 차감하더라도 실전 기대값의 증거가 되지 않는다.

### 극단 손실·Drawdown

과거 저장 close 관측 기준 최악은 9/11 추천 000660의 -5.546%, 같은 관측 저가는 -6.919%였다. Worst 5는 000660 -5.546%, 047040 -5.355%, 052690 -4.425%, 015760 -4.054%, 012330 -3.971%다. 전체 Worst 10은 부록에 수록했다. 실제 거래 손실 순위가 아니다.

Worst 10 중 6건은 9/11 추천에서 나왔다. 하루의 공통 충격과 overnight 하락 노출이 함께 나타나는 징후이나, 지수/업종/VI 이력이 없어 원인을 통계적으로 식별하지 못한다. VI·하한가 체결 불능 사례를 확인할 자료도 부족하다.

`max_drawdown_rate`는 거래별 저가수익이며 **계좌 MDD가 아니다**. 현금·포지션 수량·일별 equity·자금흐름·체결 원장이 없으므로 계좌 MDD, 최대 연속 실현 손실, Sharpe, 실제 손실 꼬리는 미산출이다. 같은 날 종목을 Rank 순서로 직렬 복리 계산해 가짜 equity curve를 만들지 않았다.

## 12. 최근 성과

| 기간 | 현재 v8 공식 완료 N | 비용 반영 완료 N | 평균 Net/승률/PF |
|---|---:|---:|---|
| 최근 20 거래일 | 0 | 0 | 미산출 |
| 최근 60 거래일 | 0 | 0 | 미산출 |
| 최근 120 거래일 | 0 | 0 | 미산출 |
| 전체 보유기간 | 0 | 0 | 미산출 |

휴장 달력 오류 때문에 정확한 거래일 경계를 이 시스템의 캘린더로 인증할 수 없다. 다만 전체 유효 완료 표본 자체가 0이어서 어느 기간에서도 결과는 미산출이다. 현재 monitoring은 달력 기준 20/60/120 거래일이 아니라 최근 **7개 추천일**과 이전 최대 **20개 추천일** 비교다. NO TRADE·데이터 누락일은 제외된다.

과거 관측 날짜별 평균: 9/4 N=3 +1.263%, 9/8 N=1 -0.847%, 9/9 N=10 -1.667%, 9/11 N=10 -2.897%, 9/15 N=9 -1.253%, 9/16 N=5 +0.267%. 월별 결과도 사실상 9월 한 달이며, 기간마다 전략 버전·데이터 품질이 바뀌었다. 이를 안정성이나 최근 회복 증거로 볼 수 없다.

## 13. 현재 추천 신뢰성을 제한하는 요소

중요도 순서이며 단순 코드 품질 평가가 아니다.

1. **P0 — 현재 실시간 처리 오류 지속.** 입력 누락·왜곡 가능성을 제한하지 못한 채 후보 계산이 이어진다. 정확한 손실률·근본 원인 미확정.
2. **P0 — 봉 확정 후 지연 틱의 축소 재생성.** 원본 클래스에서 재현됨. 거래량/고저/Feature/유동성 gate에 직접 영향.
3. **P0 — 15:30 봉 생성과 성과 완료 조건 충돌.** 수집·관측의 통합 경로가 완성되지 않았다. 15:30 봉 실제 0개.
4. **P0 — 휴장일 오류와 잘못 고정된 익일.** 유일한 v8 추천에 실제 발생. 잘못된 날짜 자동 추적과 구독 보호에도 영향.
5. **P0 — 신호가격 관측을 실행 수익으로 연결할 수 없음.** 공식 paper entry/exit/cost 원장 없음. Net 기대값의 기본 표본 0.
6. **P1 — 과거 성과/백테스트의 완료·체결·gap 정의 불일치.** 불완전 익일 봉으로 COMPLETED, trailing gap 낙관, 동일 종가 체결 가정.
7. **P1 — point-in-time 재현 실패.** 공식 1건을 재생하지 못하는 실제 사례, candle 버전 및 당시 마스터 상태 미보존.
8. **P1 — 엄격한 15시 수신 cutoff 미충족.** 10초 grace, 과거 receipt NULL 허용, Feature occurredAt 별도 검증 미흡.
9. **P1 — 통계 모집단과 표본 부족.** v8 공식 완료 0, 이전 기록은 현재 전략 검증에 부적합. Rank/threshold/NO TRADE 비교 표본 없음.
10. **P1 — 시장·업종·기업행사 이력 부재, 구독 Universe 편향.** 장세 적응성·일반화·상장폐지 편향을 통제하지 못함.
11. **P2 — UI/API 의미 불일치 일부.** runId 없는 performance 조회는 최신 run을 고르지만 recommendation은 FORWARD 우선. 웹은 보통 runId를 전달해 피하지만 API/다른 소비자에서 엇갈릴 수 있음. 백테스트 HIGH confidence/recommendedDefault도 통계 인증으로 오해 위험.

## 14. 추가 개선사항

### P0: 유효 데이터를 쌓기 위한 최소 선행조건

- 실패 원시 프레임을 민감정보 제외 후 제한적으로 보존하고 실제 배치 경계/필드 검증으로 파싱 실패 원인을 해결한다. 기존 diagnostics를 이용해 오류·종목별 지연·커버리지 불량 시 후보를 검증 불가로 표시한다.
- 기존 aggregator에서 flush 이후 동일 구간 지연 틱을 기존 봉에 병합하거나 보존된 revision으로 처리한다. 수정으로 거래량·고저가 축소되지 않는 불변조건을 검증한다. 별도 새 집계 시스템은 필요 없다.
- 마감 체결/공식 종가의 저장 규칙과 성과 완료 조건을 맞춘다. ‘79개’ 숫자만 줄여 마감가격 누락을 숨기면 안 된다. 거래 공백·VI·무거래와 수신 누락도 구분한다.
- 실제 거래소 달력을 채우고 특별 개폐장시간을 지원한다. 잘못 저장한 observation 날짜는 원본을 보존한 감사 가능한 정정으로 복구하고, 미완료 공식 run을 정상 다음 세션까지 회수한다.
- 기존 추천 run과 체결 simulator를 재사용해 **추천 완료 후** paper entry, exit, 수량, 비용, 미체결/부분체결, 실제 다음 거래일을 최소 원장으로 연결한다. 실제 주문 자동화는 이번 검증에 필요 없다.

### P1: 성과 비교의 타당성

- 결측 세션은 공식/백테스트 모두 같은 기준으로 제외·표시하고 성과 분모와 탈락 건수를 함께 출력한다. entry/exit/목표·손절/gap 처리는 기존 공통 simulator로 일치시킨다.
- strict 15:00 수신 전략인지, 시장 사건 15:00 cutoff+10초 수신유예 전략인지 사전에 정하고 버전·기준시각을 고정한다. 수신시각 없는 기록은 공식 검증에서 제외한다.
- 기존 run의 사유 JSON/Feature/평가 결과를 활용하되 사용 캔들 revision 또는 그 입력 묶음을 보존해 forward/replay 동등성을 확인한다. 현재 마스터가 아닌 당시 거래 가능 상태를 최소 보존한다.
- 전략 버전·settings hash·비용·exit별로 모집단을 나누고 동결일 이후 OOS/Paper를 수집한다. 후보 평가 snapshot을 이용해 비선정 후보의 반사실 성과를 관측하면 Score/Rank/NO TRADE 검증이 가능하다.
- 필요한 최소 지수 가격·당시 업종 및 기업행사 플래그를 확보한다. 뉴스 모델·ML 대규모 추가는 이 단계의 필수가 아니다.

### P2: 충분한 표본 이후 분석

- 기존 analytics에 5점 Score 구간, Rank별, threshold별, 20/60/120 실제 거래일, 시간대 최초 도달·목표 전 MAE, 정확한 close-to-open gap을 추가한다.
- 날짜 단위 cluster/block bootstrap으로 Net 평균 불확실성을 제시하고 threshold·Feature·exit 다중 비교를 통제한다. confidence=HIGH 같은 임의 표본 등급은 결과 해석에 쓰지 않는다.
- 업종/가격/유동성/변동성 편중을 같은 날짜의 eligible universe와 비교한다. 단순 전체 시장 비중 비교는 선택 절차를 무시한다.

### P3: 보류할 사항

ML·뉴스 NLP·새 dashboard·대규모 아키텍처 재설계·최적 가중치 탐색은 보류한다. 입력·달력·관측·체결 경로가 정상이고 비용 반영 OOS 표본이 확보되기 전에는 신뢰성 판단을 개선하지 못한다.

### 추가로 필요한 표본: 수집 계획이지 합격 보장 수치가 아님

1. 먼저 적어도 20개 실제 거래일 동안 입력/마감봉/달력/공식 run 보존을 일별 대조한다. 이는 **운영 검증** 기간이며 수익성 검증이 아니다.
2. 규칙 동결 후 최소 120거래일과 완료 paper 100건 이상을 탐색적 점검의 시작점으로 삼는다. 현재 최대 1추천/일이고 NO TRADE가 있으므로 120일에 100건이 반드시 쌓이지 않는다.
3. 본격 기대값 평가는 300~500개 독립에 가까운 완료 거래와 충분한 날짜·장세 분산을 목표로 한다. 표본 변동성과 자기상관에 따라 더 필요하다. 해당 일수/건수만 채우면 통과하는 것은 아니다.
4. 상승/중립/하락/고변동성을 사전에 정의해 각각 최소 30~50개는 탐색 관측하고, 적용할 Regime의 유의미한 판단에는 약 100개 이상과 여러 독립 날짜를 목표로 한다. 표본 없는 Regime은 사용 범위에서 제외한다. HIGH_VOLATILITY와 방향성의 중첩 처리도 사전 정의한다.
5. 독립 Bernoulli를 가정한 승률 95% 오차 ±10%p에는 보수적으로 약 97개, ±5%p에는 약 385개가 필요하다(`1.96²×0.25/e²`). 이는 수익률 기대값이나 Score 구간 차이에 충분하다는 뜻이 아니다. Score 구간마다 수 개의 사례로 확률을 제공하면 안 된다.
6. 채택 판단은 비용 스트레스 후 OOS 평균 Net의 불확실성, 손실 꼬리·drawdown, 미체결, 실제 paper 재현성까지 함께 본다. 높은 승률 하나로 결론내리지 않는다.

## 15. 최종 신뢰성 판단

### 다섯 가지 신뢰성의 구분

| 영역 | 판정 | 이유 |
|---|---|---|
| Software Reliability | **문제 있음** | 기능 연결은 상당 부분 확인했지만 집계·마감·달력에 실제 결함 |
| Data Reliability | **문제 있음** | 운영 파싱 실패 지속, 봉 수정/누락, strict 시점·원천 정확성 미입증 |
| Model Reliability | **검증 불충분** | 현재 Score/Rank의 유효 완료 표본 0 |
| Strategy Reliability | **검증 불충분** | 일치하는 entry/exit/cost 기반 OOS 양의 기대값 없음 |
| Live Reliability | **검증 불충분** | paper execution 원장·완료 Net 표본 없음 |

### Q1~Q10

| 질문 | 판정 | 근거 |
|---|---|---|
| Q1 추천 로직이 기술적으로 정상 동작하는가? | **문제 있음** | 저장·필터·점수 경로는 실행되지만 원본 집계 결함과 파싱 오류, 마감·달력 모순으로 전체 정상 판정 불가 |
| Q2 Score가 실제 성과를 구분하는가? | **검증 불충분** | 현재 v8 공식 완료 0; 이전 구간 비교는 버전/시간/결측 오염 |
| Q3 TOP Rank일수록 좋은가? | **검증 불충분** | 현재 1종목 제한, Rank 1 완료 0, Rank 2 이상 최종 표본 없음 |
| Q4 비용 후 양의 기대값인가? | **검증 불충분** | 공식 성과는 신호가 관측, 체결·비용·Net 원장 없음; 현 백테스트 N=0 |
| Q5 OOS에서도 유지되는가? | **검증 불충분** | 유효 OOS N=0, 고정 미사용 holdout 증빙 없음 |
| Q6 Paper에서도 유지되는가? | **검증 불충분** | 연결된 완료 paper execution N=0 |
| Q7 특정 시장 상황에만 의존하는가? | **검증 불충분** | 시점별 지수 Regime·업종 이력 없음; KOSPI/KOSDAQ은 장세 분류가 아님 |
| Q8 표본이 충분한가? | **검증 불충분** | 공식 실행 2일, 추천 1건, 유효 완료 0; 구버전도 6일/38건의 불완전 관측 |
| Q9 사용 전에 반드시 해결할 문제가 있는가? | **확인됨** | 파싱/봉 집계/마감·휴장/실제 진입과 비용/시점 재현성 문제를 확인 |
| Q10 오늘 추천과 함께 확인할 데이터는? | **부분 확인** | 아래의 코드상 제공 필드와 필수 미제공 필드를 구분; 오늘 15시 추천은 감사 시점 아직 없음 |

### 오늘 후보를 볼 때 필요한 정보와 현재 제공 가능성

| 정보 | 현재 상태 |
|---|---|
| 종목명·코드·Rank·Score | 제공 가능. 현재 최종 최대 1개 |
| 현재가격 | 별도 시세 경로 존재. 추천의 buyReferencePrice는 현재가가 아니라 탐지시점 기준가일 수 있음 |
| 예상/실제 진입가격 | 백테스트 15:05 시가 모형만 존재. live 실행가능 호가·체결 확인 필요 |
| 추천 핵심 근거·위험요인 | 가감점 JSON, dataReadiness, missing features, exclusion reason 제공 |
| 15시 기준·발생/수신/완료시각 | run/detection 일부 제공; 원시 틱 수신시각과 수정 이력은 부족 |
| Market Regime | UNVERIFIED. Broad RISK_ON을 검증된 종목 맥락으로 바꾸면 안 됨 |
| Historical Sample Size / Win Rate | 공식 analytics 전체 N=0. 동일 전략/구간의 종목별 유효 통계 없음 |
| P(+1%) / P(+2%) / P(+3%) | 검증된 개인 후보 확률 없음. 미산출로 표시해야 함 |
| Average MFE / MAE | 유효 v8 완료 N=0; 기존 관측과 실제 체결 excursion 구분 필요 |
| Expected Net Return | 미산출. 임의 추정값/높은 Score를 변환한 값 금지 |
| Confidence | 표본 수·기간·CI·출처로 표시해야 함. 임의 HIGH 등급은 불가 |
| Risk Factors | 파싱 오류, 종목별 최신성·누락, 거래정지/VI·호가·spread, 다음 거래일, Corporate Action, 업종·계좌 집중·현금 확인 필요 |

사용자가 실제로 확인해야 할 최소 묶음은 **공식 run 여부와 버전, 데이터 시각과 품질, 점수 가감점, 실행가능한 매수 호가/거래량, 정확한 다음 거래일, 사전 고정 exit와 총비용, 동일 모집단의 완료 표본 수 및 불확실성**이다. 현재 제공하지 못하는 확률과 Net 기대값은 빈칸/미산출로 두는 것이 맞다.

최종적으로 프로그램 자체는 일부 기능이 작동하지만 전체 데이터·성과 연결에 문제가 있다. 추천 알고리즘의 통계적 우위는 확인되지 않았다. 15시 매수→익일 매도의 실제 전략은 비용 반영 OOS/Paper 단계에서 아직 검증되지 않았다. **현 단계의 사용 범위는 오류를 인지한 관찰·연구 후보 목록이며, 수익 우위가 입증된 매수 후보 선정 도구로 간주할 수 없다.**

---

### 재현 자료 및 범위 한계

- 원본 앱/DB 수정 없음. SQL 파일과 ReadOnlyAudit.java는 감사 목적 읽기 전용 도구다. 비밀번호는 출력·보고서·아티팩트에 저장하지 않았다.
- source의 실제 caller와 controller/repository/scheduler 연결, 저장 migration v23, 운영 API/DB와 직접 재현을 근거로 평가했다. 과거 개발 보고서를 성과 증거로 인용하지 않았다.
- 과거 가격의 거래소 원본 전수 대조, 실패 실시간 원시 프레임, 다른 서버/계좌의 별도 데이터는 확보하지 못했다. 조사한 운영 DB 이외에 존재할 수 있는 데이터를 없다고 단정하지 않는다.
- 시장가격·상태 조회는 여러 시점에 실행되어 라이브 데이터 수가 달라질 수 있다. 결과 파일의 각 조회 집계는 해당 조회 시점 기준이다.
- [진단 통계 상세](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/LEGACY_DIAGNOSTICS.md), [통계 JSON](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/legacy-statistics.json), [계산 스크립트](D:/Repo/_dev/app/stock-trading-platform/audit/2026-09-28/legacy-analysis.mjs).
