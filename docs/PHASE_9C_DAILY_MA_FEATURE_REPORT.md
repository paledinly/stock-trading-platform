# Phase 9C Daily Moving Average Feature Report

기준일: 2026-09-04

Phase 9C는 장마감 추천과 오버나잇 백테스트에서 사용할 수 있는 일봉 이동평균선 feature를 추가했다.

## 완료 내용

- 기존 `stock_candle.timeframe` 구조를 활용해 `1D` 캔들을 담을 수 있도록 `StockCandle` 생성자 확장
- 저장된 확정 `1D` 캔들 기반 MA5, MA20, MA60 계산
- 종가와 MA5/MA20/MA60 이격도 계산
- MA20 기울기 계산
- 종가가 MA20 위인지 계산
- MA5가 MA20 위인지 계산
- MA20 상승 여부 계산
- 일봉 정배열 여부 계산
- MA20 기준 과열 여부 계산
- 장마감 추천 생성 시 추천/위험 근거 JSON에 `dailyMa` 포함
- 오버나잇 백테스트의 가상 추천에도 동일한 `dailyMa` 계산 적용
- 장마감 추천 화면의 추천 근거/위험 근거에 일봉 이동평균선 상태 표시

## 주요 파일

- `backend/src/main/java/com/sunmo/stockplatform/closing/application/DailyMovingAverageFeature.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/DailyMovingAverageService.java`
- `backend/src/main/java/com/sunmo/stockplatform/candle/domain/StockCandle.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScorer.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationService.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightBacktestService.java`
- `backend/src/test/java/com/sunmo/stockplatform/closing/DailyMovingAverageServiceTest.java`
- `backend/src/test/java/com/sunmo/stockplatform/closing/ClosingRecommendationScorerTest.java`
- `web/src/ClosingRecommendationApp.tsx`

## 계산 기준

일봉 이동평균선은 추천 탐지일 전일까지 저장된 확정 `1D` 캔들만 사용한다.

```text
MA5  = 최근 확정 일봉 5개 종가 평균
MA20 = 최근 확정 일봉 20개 종가 평균
MA60 = 최근 확정 일봉 60개 종가 평균
```

추천 당일 일봉은 의도적으로 제외한다. 장중 또는 장마감 직전 추천에서 당일 종가가 미래 데이터로 섞이는 것을 피하기 위한 보수적 정책이다.

20개 미만이면 `ready=false`로 처리하고 임의 값을 만들지 않는다.

## Feature 항목

```text
ready
candleCount
asOfDate
lastClose
ma5
ma20
ma60
ma5DistanceRate
ma20DistanceRate
ma60DistanceRate
ma20SlopeRate
closeAboveMa20
ma5AboveMa20
ma20Rising
bullishAlignment
overextendedFromMa20
```

## 추천 근거 표시

추천 근거에는 다음 항목을 표시한다.

- 일봉 20일선
- 일봉 5일선
- 20일선 기울기
- 일봉 정배열

위험 근거에는 다음 항목을 표시한다.

- 일봉 과열
- 일봉 MA20 이격
- 일봉 MA20
- 일봉 기준일

## 의도적으로 제외한 범위

Phase 9C에서는 KIS 일봉 API 연동과 일봉 backfill 자동 저장을 구현하지 않았다.

현재 범위는 저장된 `1D` 캔들이 있을 때 장마감 추천과 백테스트가 같은 일봉 추세 feature를 재사용할 수 있도록 계산 경로를 준비하는 것이다. 일봉 데이터 수집 자동화는 별도 작업으로 진행한다.

또한 일봉 MA를 추천 점수에 직접 가산/감점하지 않았다. 점수 반영은 `Phase 9D 이동평균선 기반 장마감 추천 점수 반영`에서 기존 방식과 비교 가능하게 진행한다.

## 검증 결과

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 다음 단계

다음 단계는 `Phase 9D 이동평균선 기반 장마감 추천 점수 반영`이다.

권장 작업:

- 기존 장마감 추천 점수 유지
- 5분봉 MA와 일봉 MA를 가산/감점 factor로 추가
- 이동평균선 데이터 부족 시 중립 또는 약한 감점 정책 결정
- 기존 방식과 MA 반영 방식의 오버나잇 백테스트 결과 비교
- 전략 버전 갱신
