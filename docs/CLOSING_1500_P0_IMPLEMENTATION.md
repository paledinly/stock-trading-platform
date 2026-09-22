# 15:00 마감추천 전략 P0 구현

## 전략 기준

- 판단 시각은 Asia/Seoul 15:00으로 고정한다.
- 후보 평가는 15:00까지 수신·확정된 데이터만 사용한다.
- 전진 평가는 15:00 이후 15:20까지 완료된 실행만 인정한다.
- 진입은 평가 이후여야 한다. 5분봉 백테스트에서는 15:05 봉 시가를 보수적인 진입 대용값으로 사용한다.
- 15:05 진입 봉이나 예정된 다음 거래일 데이터가 없으면 가격을 추정하지 않는다.

## 기존 기능 재사용

- 실행 이력, 멱등키, 설정 스냅샷과 데이터 버전은 `closing_recommendation_run`을 재사용한다.
- 시점 제한과 후보 심사는 `ClosingPrecisionEvaluator`를 재사용한다.
- 거래일 계산은 `ClosingTradingCalendar`를 재사용한다.
- 소켓 복구는 `KisRealtimeClient.scheduleReconnect` 경로를 재사용한다.

## P0 변경 파일

| 영역 | 파일 | 변경 |
| --- | --- | --- |
| 실시간 | `RealtimeMarketProperties.java`, `application.yml`, `.env.example` | 무수신 제한시간과 점검 주기 설정 |
| 실시간 | `RealtimeDiagnostics.java` | 연결 stale 판정과 파싱 오류 원인 보존 |
| 실시간 | `KisRealtimeClient.java` | 장중 무수신 및 정상 close 자동 재연결 |
| 후보 평가 | `ClosingRecommendationProperties.java` | 15:00 판단, 15:20 진입 기한 설정 |
| 후보 평가 | `ClosingRecommendationService.java` | 15:00 이전 차단, 고정 as-of, stale 실시간 데이터 차단 |
| 전략 버전 | `ClosingRecommendation.java` | `closing-recommend-v8-1500`으로 구분 |
| 백테스트 | `OvernightBacktestService.java` | 신호가와 15:05 진입가 분리, 정확한 다음 거래일 조회 |
| 정합성 | `BacktestIntegrityService.java` | 평가 이후 진입 시각·가격 검증 |
| API/UI | `ClosingRecommendationDtos.java`, `ClosingRecommendationApp.tsx` | 신호 가격과 진입 시각·가격 표시 |
| 테스트 | 관련 backend 테스트 | stale 복구, 15:00 고정, 평가 후 진입 회귀 검증 |

## 검증 방법

1. 단위 테스트에서 60초 무수신 연결과 정상 close가 재연결 상태로 바뀌는지 확인한다.
2. 오늘 14:59 후보 평가는 거절되고, 15:05 실행은 `evaluatedAsOf=15:00`, `FORWARD`인지 확인한다.
3. 15:00 부근 최신 틱이 없으면 `MARKET_DATA_STALE`로 실행이 저장되지 않는지 확인한다.
4. 백테스트에서 탐지가격과 15:05 진입가격이 분리되고, 진입 봉 누락 시 `ENTRY_DATA_MISSING`인지 확인한다.
5. 금요일 추천의 성과 날짜가 설정 휴일을 건너 정확한 다음 거래일인지 확인한다.
6. 백엔드 전체 테스트와 프론트 테스트·프로덕션 빌드를 통과시킨다.
7. 장중 운영 환경에서는 `/api/v1/market/realtime/status`의 `lastTickAt`과 `receivedTicks`가 계속 증가하고, 중단 시 90초 이내 `connectedAt`이 갱신되는지 확인한다.

실제 주문이나 모의 체결 원장은 이번 P0에서 생성하지 않는다. 화면의 추천은 후보 평가이며, 백테스트의 진입가는 `NEXT_FINAL_5M_OPEN` 모형이다.
