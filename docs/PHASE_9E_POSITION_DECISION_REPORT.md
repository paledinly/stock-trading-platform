# Phase 9E Overnight Position Decision Report

기준일: 2026-09-04

Phase 9E는 장마감 추천 종목을 다음날 장중에 매도할지, 계속 보유할지 판단하는 기능을 추가했다.

## 완료 내용

- 다음날 매도/보유 판단 상태 정의
- 판단 이력 저장 테이블 추가
- 현재가 기준 수익률 계산
- 목표 수익률/손절 기준 도달 여부 계산
- 실시간 VWAP 이탈 여부 반영
- 체결강도 약화 여부 반영
- 5분봉 MA20 이탈 여부 반영
- 당일 고점 대비 후퇴 여부 반영
- 판단 근거 JSON 저장
- 판단 평가 API 추가
- 최신 판단 조회 API 추가
- 장마감 추천 화면에 매도/보유 판단 버튼 추가
- 추천 카드별 현재 판단 패널 추가

## 판단 상태

```text
DATA_PENDING  판단 대기
HOLD          보유
EXTEND_HOLD   보유 연장
TAKE_PROFIT   익절 권고
SELL_WARNING  매도 주의
STOP_LOSS     손절
```

## 판단 기준

기본 판단 순서는 다음과 같다.

```text
1. 손절 기준 도달 -> STOP_LOSS
2. 목표 수익률 도달 + 추세 건강 -> EXTEND_HOLD
3. 목표 수익률 도달 -> TAKE_PROFIT
4. VWAP 이탈/MA20 이탈/체결강도 약화/고점 대비 후퇴 -> SELL_WARNING
5. 그 외 -> HOLD
```

추세 건강 조건:

```text
VWAP 이탈 없음
5분봉 MA20 이탈 없음
체결강도 약화 없음
고점 대비 후퇴 과다 없음
```

## 주요 파일

- `backend/src/main/java/com/sunmo/stockplatform/closing/domain/OvernightPositionDecision.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/domain/OvernightPositionDecisionType.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/infrastructure/OvernightPositionDecisionRepository.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightPositionDecisionService.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/api/ClosingRecommendationController.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/api/ClosingRecommendationDtos.java`
- `backend/src/main/resources/db/migration/V15__create_overnight_position_decision.sql`
- `backend/src/test/java/com/sunmo/stockplatform/closing/OvernightPositionDecisionServiceTest.java`
- `web/src/ClosingRecommendationApp.tsx`
- `web/src/closingRecommendation.css`

## API

판단 실행:

```text
POST /api/v1/closing-recommendations/decisions/evaluate?date=&targetRate=&stopRate=
```

최신 판단 조회:

```text
GET /api/v1/closing-recommendations/decisions?date=
```

## 의도적으로 제외한 범위

- 판단 자동 스케줄링은 포함하지 않았다.
- 실제 주문/체결 연동은 포함하지 않았다.
- 보유 연장 전략의 다일 보유 백테스트는 포함하지 않았다.
- 판단 알림 기능은 포함하지 않았다.

## 검증 결과

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 다음 단계

다음 단계는 `Phase 9F 보유 연장 전략 백테스트`다.

권장 작업:

- 다음날 시가 매도, 종가 매도, 목표 도달 매도, VWAP 이탈 매도, MA20 이탈 매도 전략 비교
- 목표 도달 후 추세 건강 시 보유 연장 전략 시뮬레이션
- 같은 5분봉에서 목표/손절 동시 도달 시 보수적 처리 또는 `AMBIGUOUS` 표시
- 전략별 승률, 평균 수익률, 최대 낙폭, 손익비 비교
