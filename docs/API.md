# API Guide

v9 마감 평가 기본 기준은 15:15이며 수신 유예는 0초다. 같은 거래일·전략의 FORWARD 수동/자동 요청은 하나의 실행을 공유한다. 다른 설정으로 중복 요청하면 409이며 과거 REPLAY는 별도 실행으로 보존한다. criteria.entryModel은 FRESH_OBSERVED_TICK_AFTER_COMPLETION이고 가상 진입은 15:20 이전 실관측만 사용한다.

## 실시간 처리 진단

`GET /api/v1/market/realtime/status`의 `realtime`에 `processingQueued`, `processingDropped`, `timings`를 추가했다. 각 timing은 프로세스 시작 이후 `count`, `averageMs`, `maxMs`, `lastMs`다. 최상위 `sseClients`는 현재 SSE 연결 수, `sseSlowClientDisconnects`는 전송 큐가 넘쳐 끊은 느린 클라이언트 누적 수다.

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

## 장중 연구 API

- `POST /api/v1/intraday/exit-comparison?date=YYYY-MM-DD`: 읽기 전용 비교. 기본은 동일 진입 청산 비교이고 variant.options에서 진입 확인·제외·재진입 조건을 켜면 동일 후보의 진입 변경 실험이다. 후보를 새로 생성하지 않는다. 최소 손절폭·명시적 가격 단위·부분 익절·추적 손절도 지원한다. [본문·비용·품질 의미](INTRADAY_EXIT_COMPARISON.md). Web 장중 추천 하단 ‘진입·청산 비교 실험’에서 실행한다.

- `GET /api/v1/intraday/status`: 활성 여부, 비용 설정 여부, 큐/누락/오류, 현재 관찰 후보 단계·사유.
- `GET /api/v1/intraday/recommendations?date=YYYY-MM-DD`: 한국 거래일의 Paper 추천. `signal`은 고정 Snapshot, `outcome`은 이후 관측·가상 체결이다. score 내림차순이며 현재가에는 관측시각이 포함된다. signal.risks에 COSTS_NOT_CONFIGURED가 있으면 plan.riskReward는 가격 기준이며 outcome.netReturn은 null이다. 이 추천은 Net 통계 표본에서 제외한다.
- `GET /api/v1/intraday/analytics?date=YYYY-MM-DD`: 독립 Paper의 Setup/시간대/점수구간/시장 맥락/정책별 통계. 자료가 없으면 null이다.
- `POST /api/v1/intraday/replay?date=YYYY-MM-DD`: 본문이 없으면 당시 설정·수신 순서로 재생한다. 전체 `IntradayProperties` JSON 본문을 전달하면 동일 관측 Universe/입력의 반사실 실험이다. Paper 성과에 합산하지 않는다. 비교 시 비용/진입/청산 설정은 동일하게 유지해야 한다.
- SSE `intraday.entry-ready`: 새 추천의 id·종목·Setup·계획·점수·이유 Snapshot. cooldown/활성 포지션으로 반복 발행을 제한한다. 외부 메신저 발송이나 주문 API는 추가하지 않는다.

0건 추천은 정상 NO TRADE이며 비활성·자료 부족·유동성·Setup 미충족 등 원인은 status에서 구분한다. Confidence는 UNVALIDATED이며 score는 확률이 아니다.

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
