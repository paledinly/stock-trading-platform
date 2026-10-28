# API Guide

Backend REST/SSE API 목록이다. 모든 경로는 `/api/v1`로 시작하며 Controller mapping(`backend/src/main/java/**/api/*Controller.java`, `closing/trajectory/TrajectoryController.java`)이 기준이다. 정확한 응답 필드는 해당 DTO와 Web 호출 코드를 함께 확인한다.

표시: **[운영]** Web 화면이 호출하지 않고 운영자가 직접 부르는 API, **[연구]** 연구용 수집 결과 조회. 표시가 없으면 Web(또는 Mobile)이 사용한다.

날짜 파라미터는 `YYYY-MM-DD`(KST 거래일), 시각 파라미터(`Instant`)는 ISO-8601 UTC 문자열이다.

## 종목·시세

**StockController**, **StockMasterController**, **QuoteController**, **CandleController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/stocks/search` | 종목 검색 | `q`(필수), `limit`(1~50, 기본 20) |
| POST | `/stocks/master/sync` | **[운영]** KIS 종목 마스터 파일로 종목 테이블 동기화 | 없음 |
| GET | `/stocks/{stockCode}/quote` | 현재가. 실시간 구독을 추가하지 않는다(이미 구독 중이면 최신 체결 반영). | `stockCode`(영숫자 6~12자) |
| GET | `/stocks/{stockCode}/candles` | 저장된 봉 조회 | `timeFrame`(기본 `5M`), `from`, `to`(필수, Instant) |

## 관심종목

**WatchlistController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/watchlists` | 그룹과 항목 전체 조회 | 없음 |
| POST | `/watchlist-groups` | 그룹 생성 | 본문 `name`, `displayOrder` |
| PATCH | `/watchlist-groups/{id}` | 그룹 이름·순서 변경 | 본문 `name`, `displayOrder`, `version` |
| DELETE | `/watchlist-groups/{id}` | 그룹 삭제 | 없음 |
| POST | `/watchlists` | 항목 추가 | 본문 `groupId`, `stockCode`, `displayOrder` |
| PATCH | `/watchlists/{id}` | 항목 이동 | 본문 `groupId`, `displayOrder`, `version` |
| PATCH | `/watchlists/{id}/realtime` | 실시간 고정 켜기/끄기(최대 `WATCHLIST_REALTIME_MAX_PINNED`개). 목록 저장과 별개다. | 본문 `enabled`, `version` |
| DELETE | `/watchlists/{id}` | 항목 삭제 | 없음 |

## 실시간 상태·구독·스트림

**MarketStreamController**, **RealtimeStatusController**, **RealtimeSubscriptionController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/stream` | SSE 스트림 | 헤더 `Last-Event-ID`(재연결 시 replay) |
| GET | `/market/realtime/status` | 실시간 수집 진단, Redis 상태, 구독 수, SSE 연결 수 | 없음 |
| GET | `/market/subscriptions` | 현재 실시간 구독 목록과 출처·상태·정밀 점수 | 없음 |
| POST | `/market/subscriptions/{stockCode}` | 수동(`MANUAL`) 구독 추가. 한도가 차면 실패한다. | `stockCode` |
| DELETE | `/market/subscriptions/{stockCode}` | 수동 구독 해제 | `stockCode` |

SSE 이벤트: `quote.updated`, `candle.5m.updated`, `candle.5m.closed`, `scanner.<종류>.detected`(`volume`, `price`, `momentum`, `volume-breakout`, `turnover-breakout`, `high-breakout`, `vwap-breakout`, `vwap-reclaim`, `pullback-rebreak`), `scanner.performance.updated`. 새 이벤트가 있을 때만 전송한다.

### 실시간 진단 필드 (`/market/realtime/status`)

- 최상위: `realtime`(아래), `redisStatus`(`UP`/`DOWN`/`UNAVAILABLE`), `subscriptionCount`/`subscriptionLimit`/`subscriptionRemaining`, `sseClients`(현재 SSE 연결 수), `sseSlowClientDisconnects`(전송 큐가 넘쳐 끊은 느린 클라이언트 누적 수).
- `realtime.processingQueued`, `processingDropped`: 처리 큐 대기 수와 큐 초과로 버린 틱 누적 수.
- `realtime.outsideSessionTicks`/`lastOutsideSessionSourceAt`: 원천시각이 수집 창(09:00~15:31:00) 밖이라 버린 체결 수와 마지막 원천시각. `closingAuctionTicks`/`lastClosingAuctionSourceAt`: 15:30:00 이후 수락한 종가 단일가 체결.
- `realtime.scannerCooldownFallbacks`/`lastScannerCooldownFallbackAt`: Redis 대신 프로세스 내부 쿨다운으로 탐지를 저장한 횟수.
- `realtime.timings`: 단계별 `count`, `averageMs`, `maxMs`, `lastMs`, `minMs`, `negativeCount`(프로세스 시작 이후). `minMs`/`negativeCount`는 음수 원값을 보존해 호스트 시계가 원천보다 늦은지 보여 준다.
  - `sourceToCallback`: 체결 원천시각부터 첫 수신 콜백까지(두 시계 차이와 원천 초 단위 절사 포함)
  - `processingQueueWait`: 수신 후 파싱된 틱의 작업 큐 대기시간
  - `tickTransaction`: 단일 틱 전체 처리시간(이름은 과거 그대로). 일반 틱에는 DB 트랜잭션이 없으며 확정봉이 있으면 커밋 비용을 포함한다.
  - `transactionBoundary`: 틱 처리에서 메서드 본문을 뺀 호출 부가 비용. DB 시간 지표가 아니다.
  - `candleTransaction`: 확정봉 저장·Scanner 평가·커밋을 포함한 트랜잭션 시간
  - `tickBody`, `redisWrite`, `quoteSse`, `performanceAndSse`, `candleScannerAndSse`, `candleRepository`: 세부 구간. 구간이 겹치므로 합산하지 않는다.
- 메모리 값이며 재시작하면 초기화된다.

### 구독 목록 필드 (`/market/subscriptions`)

`stockCodes`, `sources`, `limit`, `remaining`, `items`(종목코드순: `stockCode`, `stockName`, `market`, `sources`, `state`/`stateMessage`/`stateUpdatedAt`, `precisionScore`/`precisionAddedAt`(정밀이 아니면 null)), `precisionState`(`ACTIVE`/`FROZEN`/`DISABLED`, 배정기가 없으면 null), `precisionCapacity`. 상태 시각은 호스트 시각이며 체결 원천시각이 아니다.

## Scanner·성과 분석·백테스트

**ScannerController**, **ScannerAnalyticsController**, **BacktestController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/scanner-settings` | Scanner 설정 목록 | 없음 |
| POST | `/scanner-settings` | 설정 생성(201) | 본문 `SettingRequest` |
| PATCH | `/scanner-settings/{id}` | 설정 수정 | 본문 `SettingRequest` |
| DELETE | `/scanner-settings/{id}` | 설정 삭제(204) | 없음 |
| GET | `/scanner-detections` | 탐지 목록 | `type`(ScannerType), `limit`(1~100, 기본 50), `todayOnly`(기본 true) |
| GET | `/scanner-detections/{id}` | 탐지 상세 | 없음 |
| GET | `/scanner-detections/{id}/performance` | 탐지 이후 성과 | 없음 |
| GET | `/scanner-analytics` | 기간별 탐지 성과 집계 | `from`, `to`(필수, Instant), `settingId`, `targetRate`(기본 3), `stopRate`(기본 -2), `minimumSampleSize`(기본 20) |
| GET | `/backtests/stocks` | 5분봉이 저장된 백테스트 가능 종목 | 없음 |
| GET | `/backtests/run` | 저장된 5분봉으로 Scanner 재생 | `stockCode`, `from`, `to`(필수, Instant), `settingId`, `limit`(1~200, 기본 50) |

ScannerType: `VOLUME`, `PRICE_RISE`, `MOMENTUM`, `VOLUME_BREAKOUT`, `TURNOVER_BREAKOUT`, `HIGH_BREAKOUT`, `VWAP_BREAKOUT`, `VWAP_RECLAIM`, `PULLBACK_REBREAK`.

## 시장 전체 스캔

**MarketWideScannerController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/market-wide/scan` | 수동 시장 전체 스캔 실행·결과 | `market`(KOSPI/KOSDAQ, 생략 시 전체), `limit`(1~120, 기본 40), `candidates`(1~41, 기본 12), `includeEtf`(기본 false) |
| GET | `/market-wide/status` | 스캔·상세시세 보강 큐 진단 | 없음 |
| GET | `/market-wide/coverage` | 날짜별 수집 범위 | `date` |

## 마감 추천

**ClosingRecommendationController**, **ClosingDailyHealthController**, **DailyCandleBackfillController** (모두 `/closing-recommendations` 아래)

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| POST | `/closing-recommendations/generate` | 마감 평가 실행 | `date`, `limit`(1~30, 기본 10, 최종 추천은 최대 5), `minOpportunity`(기본 35), `maxRisk`(기본 65), 헤더 `Idempotency-Key` |
| GET | `/closing-recommendations` | 저장된 최종 추천 | `date`, `runId` |
| GET | `/closing-recommendations/runs` | 평가 실행(run) 이력 | `date` |
| GET | `/closing-recommendations/evaluation` | run의 전체 후보 평가 결과(없으면 204) | `date`, `runId` |
| POST | `/closing-recommendations/performance/track` | 다음 거래일 성과 계산 | `date`, `runId`, `targetRate`(기본 3), `stopRate`(기본 -2) |
| GET | `/closing-recommendations/performance` | 다음 거래일 성과 조회 | `date`, `runId` |
| POST | `/closing-recommendations/decisions/evaluate` | 보유/매도 판단 계산 | `date`, `runId`, `targetRate`, `stopRate` |
| GET | `/closing-recommendations/decisions` | 보유/매도 판단 조회 | `date`, `runId` |
| POST | `/closing-recommendations/candidate-observations/track` | 전체 후보(선정·미선정) 관측 재계산 | `runId`(필수), `targetRate`, `stopRate` |
| GET | `/closing-recommendations/candidate-observations` | 전체 후보 관측 조회. 공식 추천 성과와 별도 집계다. | `runId`(필수) |
| GET | `/closing-recommendations/strategy-analytics` | 기간별 전략 분석 | `from`, `to` |
| GET | `/closing-recommendations/account-performance` | 계좌 성과. 현재 항상 "사용 불가" 응답을 돌려준다. | 없음 |
| GET | `/closing-recommendations/daily-health` | 마감 파이프라인 일별 완결성 | `from`, `to` |
| GET | `/closing-recommendations/daily-collection` | 하루 자동 수집 개수(조회 전용, Web '오늘' 화면용): 공식 FORWARD run, 가상 진입 상태별 수, 상한가 감시 표본별·출처별 수, 후보 맥락 행 수, 15:25 봉 종목 수·거래량 있는 수, 그날이 관측 세션인 정밀 후보 관측 상태별 수(`observation.statuses`)와 참고용 Broad 후보 상태별 수(`observation.broadStatuses`), 빠진 것이 15:25 종가 봉 하나뿐인 정밀 관측 수(`observation.closingBarOnly`, 18:00 재보완 대기), 분봉 보완(BACKFILL) 5분봉 수, 그날 접수 DART 공시 수, 그날 수정주가 수집 확인 종목 수, 07:00~09:00(08:10 후보 일봉 준비)·14:20~15:00(14:25·14:35·14:52 정밀 후보 일봉 보완)에 저장된 일봉 수(`morningDaily`, `precisionDaily`, 실행 기록이 아니라 저장 흔적), 16:00 뒤 저장된 그날 지수 일봉 수(`indexDaily`), 18:00 뒤 다시 채운 그날 5분봉 수와 관측 재평가 시각(`lateObservation.refilledBars`, `lateObservation.lastEvaluatedAt`), 그날 정규장 개장·마감(`sessionOpen`, `sessionClose`), 그날 시장 스캔 기록(`scanRuns`: 완료 수·실패 수·마지막 완료 시각, `market_wide_scan_run`)과 실시간 5분봉 수(`realtimeBars`: 봉 수·종목 수·마지막 저장 시각, source REALTIME). 뒤의 둘은 재시작해도 남는 저장 기록이다. `adjustedUniverse`는 수정주가 일봉 수집 대상 종목 수(활성, ETF·ETN 제외)다. 시각은 호스트 수신·저장 시각이다. 수능일처럼 장 시간이 바뀐 날은 14:20~15:00·16:00·18:00·15:25 기준을 그만큼 옮겨 센다. | `date`(기본 오늘 KST) |
| POST | `/closing-recommendations/daily-data/prepare` | 후보용 일봉 준비 실행 | 없음 |
| GET | `/closing-recommendations/backtest` | **[운영]** 저장 데이터로 마감 전략 재생 | `from`, `to`, `limit`, `minOpportunity`, `maxRisk`, `targetRate`, `stopRate` |
| GET | `/closing-recommendations/trading-calendar` | **[운영]** 거래일 달력 상태(KIS 갱신 결과) | 없음 |
| POST | `/market-data/dart-filings/backfill` | **[연구]** 과거 DART 공시 목록 수집 시작(백그라운드, 최신부터 거꾸로, 상장사만). 202 시작, 409 키 없음·기간 오류·실행 중 | `from`(필수), `to`(기본 오늘 KST) |
| GET | `/market-data/dart-filings/backfill/status` | **[연구]** 과거 공시 수집 상태: 기간, 마지막 완료 날짜, 호출 수, 저장 수, 멈춘 이유 | 없음 |
| GET | `/closing-recommendations/session-hours` | 그날 정규장 개장·마감(`open`, `close`, "HH:mm")과 특별 개장일 여부(`special`). `MARKET_SPECIAL_SESSIONS` 설정 기준 | `date`(기본 오늘 KST) |

`generate` 규칙:

- 오늘 날짜는 입력 마감(15:15 + 유예) 전에 실행할 수 없다(400). 미래 날짜도 400이다.
- 오늘이 거래일이고 진입 마감(15:20) 전이면 FORWARD, 그 밖은 REPLAY다. FORWARD는 실시간 마지막 틱이 `MARKET_REALTIME_STALE_TIMEOUT`보다 오래되면 503이다.
- FORWARD 요청 키는 `official-<날짜>-<전략버전>`으로 고정되어 자동·수동 요청이 하나의 실행을 공유한다. 같은 키에 다른 설정이면 409, 같은 설정이면 저장된 결과를 돌려준다. REPLAY는 `Idempotency-Key`(없으면 무작위 키)로 별도 실행된다.
- run 설정(criteria)에 `entryModel=FRESH_OBSERVED_TICK_AFTER_COMPLETION`, `inputAvailability=EXCLUDE_UNAVAILABLE_AT_RECEIPT_DEADLINE`이 기록된다.

`daily-health` 규칙: 기본 `to`는 오늘(KST), `from`은 `to`의 13일 전이며 최대 366일, `from > to`는 400이다. 최신 거래일부터 반환한다. 기록이 없는 거래일(`ClosingTradingCalendar` 기준)은 `verdict=MISSING`, `reasons=["NO_HEALTH_RECORD"]`, `recorded=false`로 채운다. 휴장일·미래일과 장 종료 15분 전의 오늘은 포함하지 않는다. 주요 필드:

- `verdict`(`COMPLETE`/`DEGRADED`/`MISSING`), `reasons`(판정 사유 코드)
- `runId`, `runCompletedAt`: 현재 전략 버전의 공식 FORWARD 평가와 평가 완료시각(체결·원천시각 아님)
- `selectedCount`, `selectedEntered`: 최종 추천 수와 그중 `ENTERED` 가상 진입 수
- `paperEntryStatuses`: 해당 run 전체 후보의 `closing_paper_entry` 상태별 수
- `observationStatuses`: 당일이 관측 세션인 FORWARD 후보 관측의 상태별 수(정밀·Broad 합계)
- `precisionObservationStatuses`, `broadObservationStatuses`: 판정에 쓰는 정밀 후보와 참고용 Broad 후보의 상태별 수. V39(2026-10-22) 이전 행은 null
- `stage`: `PRELIMINARY`(15:45 1차), `FINAL`(18:15, 18:00 재보완 뒤 최종). V39 이전 행은 null
- `indicators`: 판정에 쓰지 않는 참고 지표 `precisionReplacements`, `dailyDataFlagged`, `coverageGaps`, `coverageStillMissing`, `notes`. 못 센 값은 null. V41 이전 행은 null
- `realtimeConnected`, `realtimeDroppedDelta`, `realtimeWindowStart/End`: 15:15 직전~15:20 이후 호스트 표본 사이 연결 유지 여부와 `processingDropped` 증가량. 미관측이면 null
- `checkedAt`: 판정 기록시각

판정 기준은 [운영 가이드](OPERATIONS.md#마감-파이프라인-일별-완결성)를 따른다.

## 수정주가 일봉 (연구)

**AdjustedDailySweepController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| POST | `/market-data/adjusted-daily/sweep` | **[운영·연구]** ETF·ETN 제외 전 종목 수정주가 일봉 수집을 백그라운드로 시작. 시작하면 202, 이미 실행 중이거나 KIS 비활성 또는 보호 시간(거래일 08:30~장 종료 15분 후)이면 409. 본문은 현재 상태. | 없음 |
| GET | `/market-data/adjusted-daily/status` | **[운영·연구]** 진행 상태 | 없음 |

상태 필드: `running`, `startedAt`/`finishedAt`, `latestSession`(수집 기준 마지막 완료 거래일), `universe`, `processed`, `upToDate`, `fullFetches`, `calls`, `savedRows`, `revisedRows`(가격이 다시 계산된 행), `failed`, `lastStockCode`, `stoppedReason`(`SESSION_GUARD` 등). 메모리 값이며 재시작하면 초기화된다.

## 지수 (화면 표시·연구 기록)

**MarketIndexController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/market-data/indices` | **[운영]** KOSPI·KOSDAQ 최신 값. 왼쪽 메뉴 고정 지수 칸이 1분마다 읽는다. | 없음 |
| POST | `/market-data/index-daily/backfill` | **[연구]** 지수 일봉을 KIS(`FHKUP03500100`)에서 받아 `market_index_daily`에 저장(있으면 갱신). 지수마다 50행씩 거꾸로 나눠 받는다(1년 ≈ 지수당 5~6회). 장 마감 10분 뒤 전에는 오늘 일봉을 저장하지 않는다. | `from`, `to`(기본 오늘) |

`indices` 응답 필드(지수마다 한 행): `name`, `value`, `changePct`(전일 대비 %), `observedAt`(호스트 수신·수집 시각, KIS 원천 시각 아님), `source`(`INTRADAY`: 오늘 장중 지수 관측 `closing_context_observation` kind=INDEX의 최신값, `DAILY_CLOSE`: 오늘 관측이 없을 때 마지막 일봉 종가와 그 전 일봉 대비 등락률, `NONE`: 자료 없음), `previousDate`/`previousClose`(비교 기준 일봉). 장중 관측은 `CLOSING_TRAJECTORY_INDEX_ENABLED=true`일 때만 쌓인다. 강세·약세 같은 국면 판정은 하지 않는다.

`backfill` 응답: 지수별 `name`, `requests`, `saved`, `oldest`, `newest`.

`POST /market-data/overseas-daily/backfill?from=&to=`(**[연구]**): 미국 지수(SPX·COMP·NDX·DJI·VIX·SOX)·원/달러(USDKRW)·EWY 일봉을 KIS에서 받아 `overseas_daily`에 저장(있으면 갱신)한다. 한국 날짜 오늘 이후 일봉은 받지 않는다. 한 종목이 실패해도 다음 종목을 계속한다. 응답: 종목별 `symbol`, `requests`, `saved`, `oldest`, `newest`, `error`.

## 장마감 Trajectory (연구)

**TrajectoryController** (`/closing-trajectory` 아래, 모두 **[연구]**)

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/closing-trajectory/status` | Trajectory 수집 상태 | 없음 |
| GET | `/closing-trajectory/minutes` | 저장된 1분 Aggregate | `symbol`, `date` |
| GET | `/closing-trajectory/snapshots` | 날짜별 Snapshot(`investorFlow`, `orderbook`, `execution` 포함) | `date` |
| GET | `/closing-trajectory/outcomes` | 오전 결과 라벨 | `date` |
| POST | `/closing-trajectory/outcomes/track` | 오전 결과 재계산 | `date` |
| GET | `/closing-trajectory/flow/status` | 수급 수집 상태 | 없음 |
| GET | `/closing-trajectory/flow` | 저장된 수급 Context 이력 | `symbol`, `date` |
| GET | `/closing-trajectory/microstructure/status` | 호가·대량체결 수집 상태 | 없음 |
| GET | `/closing-trajectory/microstructure/minutes` | 저장된 1분 호가·대량체결 | `symbol`, `date` |

의미는 [장마감 Trajectory](CLOSING_TRAJECTORY.md), [투자자 수급](INVESTOR_FLOW.md), [호가·대량체결](MICROSTRUCTURE.md)을 따른다.

## 투자기록

**TradeController**

| 메서드 | 경로 | 용도 | 주요 파라미터 |
| --- | --- | --- | --- |
| GET | `/trades` | 거래 기록 목록 | `limit`(1~100, 기본 50) |
| POST | `/trades` | 거래 기록 생성(201). 실제 주문이 아니다. | 본문 `TradeRequests.Create`, 헤더 `Idempotency-Key` |
| GET | `/trades/{id}` | 거래 기록 조회 | 없음 |
| PATCH | `/trades/{id}` | 거래 기록 수정 | 본문 `TradeRequests.Update` |
| DELETE | `/trades/{id}` | 거래 기록 삭제(204) | 없음 |
| PUT | `/trades/{id}/journal` | 투자 노트 저장 | 본문 `TradeRequests.Journal` |

## 기타

- `GET /actuator/health`, `GET /actuator/info`: Spring Boot Actuator(상태 확인). 상세 정보는 노출하지 않는다.

## 삭제된 API (과거 기록)

다음 경로는 현재 없다(호출하면 404).

- 2026-10-13: `/api/v1/intraday/*`와 SSE `intraday.entry-ready` (장중 추천 모듈 제거)
- 2026-10-14 정리: `GET /market-wide/snapshots`, `GET /market-wide/precision`, `GET /scanners/{kind}`(→ `/scanner-detections?type=` 사용), `GET /stocks/{code}/features/latest`, `GET /stocks/{code}`

## 원칙

- 날짜와 시각의 timezone 의미를 보존한다.
- 빈 목록과 undefined payload를 구분한다.
- 추천 생성 API는 실제 주문 API가 아니다. 점수는 확률로 표시하지 않는다.
- 데이터 품질, 제외 사유, 실행 가정을 결과와 함께 전달한다.
- KIS 오류 원문이나 인증정보를 응답에 노출하지 않는다.
- API 변경 시 Controller, DTO, Web client와 테스트를 함께 수정한다. 사용자에게 보이는 enum/영문 reason은 서버 원본을 보존하되 UI 번역 mapping으로 표시한다.
