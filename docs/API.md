# API Guide

v9 마감 평가 기본 기준은 15:15이며 수신 유예는 0초다. 같은 거래일·전략의 FORWARD 수동/자동 요청은 하나의 실행을 공유한다. 다른 설정으로 중복 요청하면 409이며 과거 REPLAY는 별도 실행으로 보존한다. criteria.entryModel은 FRESH_OBSERVED_TICK_AFTER_COMPLETION이고 가상 진입은 15:20 이전 실관측만 사용한다.

## 실시간 처리 진단

`GET /api/v1/market/realtime/status`의 `realtime`에 `processingQueued`, `processingDropped`, `timings`를 추가했다. 각 timing은 프로세스 시작 이후 `count`, `averageMs`, `maxMs`, `lastMs`다. 최상위 `sseClients`는 현재 SSE 연결 수, `sseSlowClientDisconnects`는 전송 큐가 넘쳐 끊은 느린 클라이언트 누적 수다. `outsideSessionTicks`/`lastOutsideSessionSourceAt`은 원천시각이 수집 창(09:00~15:31:00) 밖이라 버린 체결 수와 마지막 원천시각, `closingAuctionTicks`/`lastClosingAuctionSourceAt`은 15:30:00 이후 수락한 종가 단일가 체결 수와 마지막 원천시각이다.

- `sourceToCallback`: 체결 원천시각부터 첫 수신 콜백까지(두 시계 차이 및 원천 초 단위 정밀도 포함).
- `processingQueueWait`: 수신 후 파싱된 틱의 작업 큐 대기시간.
- `tickTransaction`: 기존 필드명을 유지한 단일 틱 전체 처리시간. 일반 틱에는 DB 트랜잭션이 없으며 확정봉이 있는 경우의 커밋 비용은 포함한다.
- `transactionBoundary`: 성공한 틱 전체 처리에서 메서드 본문을 제외한 호출 부가 비용. 현재 DB 시간 지표로 사용하지 않는다.
- `candleTransaction`: 확정봉 저장·Scanner 평가와 커밋을 포함한 트랜잭션 시간.
- `tickBody`, `redisWrite`, `quoteSse`, `performanceAndSse`, `candleScannerAndSse`, `candleRepository`: 본문 및 해당 처리 구간. 구간은 중첩되므로 합산하지 않는다. Repository 시간에는 커밋 시 지연 실행되는 SQL 전체가 포함되지 않는다.

큐 분리 자체가 소비자 처리량을 높였다는 보장은 없다. 큐 대기 증가 시 위 시간을 확인하고 병목 단계를 개선한다. 기존 원장·추천을 재평가하거나 지연 기준을 완화하지 않는다.

## Phase 3 호가·대량체결

- GET `/api/v1/closing-trajectory/microstructure/status`: 설정, 요청 오류, 관측 거절, 저장 오류 상태.
- GET `/api/v1/closing-trajectory/microstructure/minutes?symbol=005930&date=YYYY-MM-DD`: 저장된 1분 호가 및 대량체결 관측.
- Snapshot의 `orderbook`, `execution`에 원본 Feature와 관측 상태를 제공한다. [시간·결측 의미](MICROSTRUCTURE.md).

## Phase 2 수급 관측

- GET `/api/v1/closing-trajectory/flow/status`: 수급 설정, 수집 시도 완료시각, 오류 횟수.
- GET `/api/v1/closing-trajectory/flow?symbol=005930&date=YYYY-MM-DD`: 저장된 수급 Context 이력.
- 기존 Trajectory Snapshot의 `investorFlow`에 누적 수량·30분 관측 변화·시각/품질 정보를 제공한다. [상세 의미](INVESTOR_FLOW.md).

## 장마감 Trajectory 연구 API

`/api/v1/closing-trajectory` 아래 GET `/status`, `/minutes?symbol=...&date=...`, `/snapshots?date=...`, `/outcomes?date=...`, POST `/outcomes/track?date=...`를 제공한다. 기존 평가의 `dataReadiness.trajectory`에도 당시 사용 가능한 Snapshot을 연결한다. [설정·응답 의미](CLOSING_TRAJECTORY.md)를 참고한다.

## 제거된 장중 연구 API

`/api/v1/intraday/*` endpoint와 SSE `intraday.entry-ready`는 2026-10-13 장중 추천 모듈 제거와 함께 삭제됐다([실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). 호출하면 404가 반환된다.

Backend의 REST/SSE endpoint는 Controller mapping이 기준이다. 이 문서는 영역과 사용 원칙만 설명하며 정확한 request/response 필드는 DTO와 Web 호출 코드를 함께 확인한다.

## 주요 영역

- 종목 검색과 현재가
- 관심종목 그룹/항목
- 5분봉과 과거 데이터 준비
- 실시간 상태와 SSE 스트림
- 스캐너 설정, 탐지, 성과
- 시장 전체 스캔과 후보
- 마감 추천 생성, 후보 평가, 조회
- 익일 성과와 보유/매도 판단
- 완료된 Forward 실행의 전체 후보 성과 관측
- 백테스트와 전략 분석
- 수동 거래 및 투자 기록

## 원칙

- 날짜와 시각의 timezone 의미를 보존한다.
- 빈 목록과 undefined payload를 구분한다.
- 추천 생성 API는 실제 주문 API가 아니다.
- 점수는 확률로 표시하지 않는다.
- 데이터 품질, 제외 사유, 실행 가정을 결과와 함께 전달한다.
- KIS 오류 원문이나 인증정보를 응답에 노출하지 않는다.

API 변경 시 Controller, DTO, Web client와 테스트를 함께 수정한다. 사용자에게 보이는 enum/영문 reason은 서버 원본을 보존하되 UI 번역 mapping으로 표시한다.

관심종목 실시간 고정은 `PATCH /api/v1/watchlists/{id}/realtime`로 목록 저장과 별도로 변경한다. 전체 후보 관측은 마감 자동화 스케줄이 거래일 장 종료 10분 후 자동 계산하며, `POST /api/v1/closing-recommendations/candidate-observations/track`으로 수동 재계산할 수 있고 같은 경로의 GET 조회 API에서 확인한다. 이 결과는 공식 추천 성과와 별도 집계한다.

## 수정주가 일봉 수집

- `POST /api/v1/market-data/adjusted-daily/sweep`: ETF·ETN 제외 전 종목 수정주가 일봉 수집을 백그라운드로 시작한다. 시작하면 202, 이미 실행 중이거나 KIS 비활성 또는 장중 보호 시간(거래일 08:30~장 종료 15분 후)이면 409다. 본문은 현재 상태다.
- `GET /api/v1/market-data/adjusted-daily/status`: `running`, `startedAt`/`finishedAt`(수집 실행시각), `latestSession`(수집 기준 마지막 완료 거래일), `universe`, `processed`, `upToDate`, `fullFetches`, `calls`, `savedRows`, `revisedRows`(가격이 다시 계산된 행), `failed`, `lastStockCode`, `stoppedReason`(`SESSION_GUARD` 등). 프로세스 메모리 값이며 재시작하면 초기화된다.

## 실시간 구독 목록

`GET /api/v1/market/subscriptions`: 기존 `stockCodes`, `sources`, `limit`, `remaining`에 더해 `items`(종목코드순: `stockCode`, `stockName`, `market`, `sources`, `state`/`stateMessage`/`stateUpdatedAt`(구독 상태 변경 시각), `precisionScore`/`precisionAddedAt`(정밀 배정 점수와 배정 시각, 정밀이 아니면 null)), `precisionState`(`ACTIVE`/`FROZEN`/`DISABLED`), `precisionCapacity`를 반환한다. 상태 시각은 호스트 시각이며 체결 원천시각이 아니다.

## 마감 파이프라인 일별 완결성

`GET /api/v1/closing-recommendations/daily-health?from=YYYY-MM-DD&to=YYYY-MM-DD`: 기본 `to`는 오늘(KST), `from`은 `to` 13일 전이며 최대 366일, `from > to`는 400이다. 최신 거래일부터 반환한다. 기록이 없는 거래일(`ClosingTradingCalendar` 기준)은 `verdict=MISSING`, `reasons=["NO_HEALTH_RECORD"]`, `recorded=false`로 채운다. 휴장일·미래일과 장 종료 15분 전의 오늘은 포함하지 않는다.

- `verdict`: `COMPLETE`/`DEGRADED`/`MISSING`, `reasons`: 판정 사유 코드 목록
- `runId`, `runCompletedAt`: 공식 FORWARD v9 평가와 평가 완료시각(체결·원천시각 아님)
- `selectedCount`, `selectedEntered`: 최종 추천 수와 그중 `ENTERED` 가상 진입 수
- `paperEntryStatuses`: 해당 실행의 전체 후보 `closing_paper_entry` 상태별 수(미진입 사유 포함)
- `observationStatuses`: 당일이 관측 세션인 FORWARD 후보 관측의 상태별 수
- `realtimeConnected`, `realtimeDroppedDelta`, `realtimeWindowStart/End`: 15:15 직전~15:20 이후 호스트 표본시각 사이 연결 유지 여부와 `processingDropped` 증가량. 미관측이면 null
- `checkedAt`: 판정 기록시각
