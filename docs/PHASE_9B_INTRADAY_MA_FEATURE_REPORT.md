# Phase 9B Intraday Moving Average Feature Report

기준일: 2026-09-04

Phase 9B는 장마감 추천과 오버나잇 백테스트에서 동일하게 사용할 수 있는 5분봉 이동평균선 feature를 추가했다.

## 완료 내용

- 저장된 확정 5분봉 기반 MA5, MA20, MA60 계산
- 현재가와 MA5/MA20/MA60 이격도 계산
- 5분봉 정배열 여부 계산
- MA5/MA20 골든크로스 여부 계산
- MA20 지지 여부 계산
- MA20 이탈 여부 계산
- 장마감 추천 생성 시 추천/위험 근거 JSON에 `intradayMa` 포함
- 오버나잇 백테스트의 가상 추천에도 동일한 `intradayMa` 계산 적용
- 장마감 추천 화면의 추천 근거/위험 근거에 5분봉 이동평균선 상태 표시

## 주요 파일

- `backend/src/main/java/com/sunmo/stockplatform/closing/application/IntradayMovingAverageFeature.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/IntradayMovingAverageService.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScorer.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationService.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightBacktestService.java`
- `backend/src/main/java/com/sunmo/stockplatform/candle/infrastructure/StockCandleRepository.java`
- `backend/src/test/java/com/sunmo/stockplatform/closing/IntradayMovingAverageServiceTest.java`
- `backend/src/test/java/com/sunmo/stockplatform/closing/ClosingRecommendationScorerTest.java`
- `web/src/ClosingRecommendationApp.tsx`

## 계산 기준

이동평균선은 추천 탐지 시각까지 저장된 확정 5분봉만 사용한다.

```text
MA5  = 최근 확정 5분봉 5개 종가 평균
MA20 = 최근 확정 5분봉 20개 종가 평균
MA60 = 최근 확정 5분봉 60개 종가 평균
```

20개 미만이면 `ready=false`로 처리하고 임의 값을 만들지 않는다.

## Feature 항목

```text
ready
candleCount
lastClose
ma5
ma20
ma60
ma5DistanceRate
ma20DistanceRate
ma60DistanceRate
bullishAlignment
goldenCross
ma20Support
ma20Broken
```

## 추천 근거 표시

추천 근거에는 다음 항목을 표시한다.

- 5분봉 정배열
- 5분봉 골든크로스
- MA20 지지
- MA20 이격

위험 근거에는 다음 항목을 표시한다.

- MA20 이탈
- MA5
- MA20
- MA60

## 의도적으로 제외한 범위

Phase 9B에서는 이동평균선 값을 추천 점수에 직접 가산/감점하지 않았다.

점수 반영은 `Phase 9D 이동평균선 기반 장마감 추천 점수 반영`에서 기존 방식과 MA 반영 방식의 백테스트 비교가 가능하도록 진행한다.

## 검증 결과

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 다음 단계

다음 단계는 `Phase 9C 일봉 이동평균선 Feature`다.

권장 작업:

- KIS 일봉 또는 과거 시세 API 연동 방식 결정
- 기존 `stock_candle.timeframe`에 `1D` 저장 방식 검토
- 일봉 MA5, MA20, MA60 계산
- 일봉 20일선 위/아래, 20일선 기울기, 일봉 정배열 계산
- 추천일 이후 일봉 데이터가 계산에 섞이지 않도록 Phase 9A 정합성 검사 확장
