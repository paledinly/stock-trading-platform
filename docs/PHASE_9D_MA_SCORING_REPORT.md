# Phase 9D Moving Average Scoring Report

기준일: 2026-09-04

Phase 9D는 Phase 9B/9C에서 계산한 5분봉 및 일봉 이동평균선 feature를 장마감 추천 점수에 반영했다.

## 완료 내용

- 장마감 추천 전략 버전을 `closing-recommendation-v2-ma`로 갱신
- 5분봉 이동평균선 기반 추천 가산점 추가
- 일봉 이동평균선 기반 추천 가산점 추가
- 5분봉 MA20 이탈 위험 감점 추가
- 일봉 추세 약화 위험 감점 추가
- 일봉 MA20 과열 위험 감점 추가
- 추천/위험 reason JSON의 `factors`에 이동평균선 점수 기여 항목 저장
- 장마감 추천 화면에서 이동평균선 점수 항목을 한글 라벨로 표시
- 이동평균선 점수 반영 회귀 테스트 추가

## 주요 파일

- `backend/src/main/java/com/sunmo/stockplatform/closing/domain/ClosingRecommendation.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScorer.java`
- `backend/src/test/java/com/sunmo/stockplatform/closing/ClosingRecommendationScorerTest.java`
- `web/src/ClosingRecommendationApp.tsx`

## 점수 반영 기준

최종 추천 점수는 기존 구조를 유지한다.

```text
최종 추천점수 = 추천 가산점 합계 - 위험 감점 합계
범위 = 0 ~ 100
```

## 추가된 추천 가산점

```text
intradayBullishAlignment  5분봉 정배열이면 +8
intradayGoldenCross       5분봉 MA5/MA20 골든크로스면 +5
intradayMa20Support       5분봉 MA20 지지면 +4
dailyTrendAlignment       일봉 정배열이면 +8
dailyMa20Rising           일봉 MA20 상승이면 +5
dailyCloseAboveMa20       일봉 종가가 MA20 위면 +4
```

## 추가된 위험 감점

```text
intradayMa20Breakdown     5분봉 MA20 이탈이면 -12
dailyTrendWeakness        일봉 MA20 아래, MA5 <= MA20, MA20 하락/횡보를 조합해 최대 -10
dailyMaOverextension      일봉 MA20 대비 12% 초과 과열이면 -10
```

## 데이터 부족 정책

이동평균선 데이터가 부족해 `ready=false`인 경우에는 가산점과 감점 모두 0점으로 처리한다.

현재 일부 환경에는 `1D` 캔들이 아직 저장되어 있지 않을 수 있으므로, 데이터 부족을 바로 감점하면 기존 추천 후보가 과도하게 낮아질 수 있다. 일봉 데이터 수집이 안정화된 뒤 백테스트 결과를 보고 약한 감점 정책으로 바꿀 수 있다.

## 의도적으로 제외한 범위

- KIS 일봉 API 자동 수집은 포함하지 않았다.
- 이동평균선 조건을 무조건 제외 필터로 사용하지 않았다.
- 기존 방식과 MA 반영 방식의 별도 A/B 백테스트 API는 아직 추가하지 않았다.

## 검증 결과

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 다음 단계

다음 단계는 `Phase 9E 다음날 매도/보유 판단`이다.

권장 작업:

- 추천 종목의 다음날 실시간 상태 평가 도메인 추가
- `HOLD`, `EXTEND_HOLD`, `TAKE_PROFIT`, `SELL_WARNING`, `STOP_LOSS` 상태 정의
- 현재가, 수익률, VWAP, 5분봉 MA, 목표/손절 기준으로 판단
- 판단 reason JSON 저장
- 장마감 추천 화면에 매도/보유 판단 패널 추가
