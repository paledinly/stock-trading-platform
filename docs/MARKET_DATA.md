# Market Data

## 공급원

- KIS WebSocket: 실시간 체결(H0STCNT0)
- KIS REST: 현재가, 과거 분봉, 일봉, 거래량/거래대금/등락률 순위, 국내휴장일, (연구용) 호가·수급·지수·수정주가 일봉
- DART OpenAPI: 공시 목록(선택)
- PostgreSQL: 확정봉, 탐지, 추천과 성과 이력

## 실시간 체결 처리

실시간 WebSocket 체결은 첫 text 콜백 진입 시각을 수신시각으로 보존한다(분할 메시지는 첫 조각 기준). 파싱 후 단일 FIFO 작업자가 처리하며 수신 콜백에서는 DB/Redis/SSE 작업을 하지 않는다.

일반 틱의 계산·시세 배포는 DB 트랜잭션 없이 처리한다. 확정봉이 포함되면 해당 봉 저장과 Scanner 평가를 명시적 트랜잭션으로 완료한 뒤 관측 이벤트(`ObservedMarketTick`)를 발행한다. 미확정봉은 평가기에서 추천 불가 입력이므로 DB 조회 없이 이탈 상태 bookkeeping만 갱신한다.

처리 큐는 기본 20,000틱이며 `MARKET_REALTIME_PROCESSING_QUEUE_CAPACITY`로 조절한다. 초과하면 새 틱을 버리고 `processingDropped`에 기록하며, 이후 각 종목의 첫 수락 입력에 `dataLoss`를 전달한다. 재연결과 처리 실패도 연속성 손실로 전달한다. 큐는 메모리이므로 프로세스 강제 종료 시 복구되지 않는다. 정상 종료는 최대 5초 기다린 뒤 남은 작업을 취소한다.

SSE 발행은 작업자에서 클라이언트별 bounded queue(4,096 이벤트)에 넣기만 하고 실제 전송은 클라이언트별 가상 스레드가 한다. 느린 브라우저/프록시가 틱 처리를 멈추지 않는다. 큐가 넘친 클라이언트는 연결을 끊고, 브라우저가 `Last-Event-ID`로 재연결해 replay 버퍼(`MARKET_SSE_REPLAY_SIZE`, 기본 1,000) 범위 안에서 이어 받는다. (과거: 2026-10-13 개장 직후에는 이 전송이 작업자에서 동기로 수행되어 Vite 프록시 연결의 쓰기 대기로 처리 큐가 가득 찼다.)

MarketTick은 원천 거래일과 발생시각, 서버 수신시각을 구분한다. 파서가 확인할 수 없는 날짜나 숫자를 현재 날짜 또는 0으로 대체하지 않는다. 오류 카운터에는 파싱 외 처리 예외가 포함될 수 있어 전체 틱 손실률과 같지 않다.

## 5분봉

정규장 bucket은 09:00부터 15:25까지다. 15:30 종가 체결은 마지막 15:25 봉에 반영한다. 확정 후 도착한 늦은 틱, 중복 틱, 과거 bucket 틱은 확정봉을 부분 데이터로 재생성하지 않는다. 확정 watermark는 `MARKET_CANDLE_WATERMARK`(기본 2초)다.

### 종가 단일가와 마지막 5분봉

KRX 접속매매는 15:20에 끝나고 15:20~15:30은 종가 단일가 호가접수(체결 없음)다. 종가는 15:30에 한 번 체결되며 랜덤엔드로 최대 30초 늦게 체결될 수 있어 원천 체결시각은 15:30:00~15:30:30이다. (과거 장중 입력 원장 기록에서 시가 단일가 첫 체결 원천시각은 09:00:02~09:00:30이었고 09:00:00은 없었다.)

- 수락: 원천시각 09:00:00~15:31:00(`FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW` = 15:30 + 60초). 15:30:00 이후 체결은 종가 단일가 체결로만 본다. 장후 시간외 종가(15:40~)와 시간외 단일가는 받지 않는다.
- bucket: 15:30:00~15:31:00 체결은 15:25 봉에 넣는다. 15:25 봉은 15:31:00 + watermark 이후 확정한다. 15:20 봉은 정상적으로 체결이 없어 만들어지지 않는다.
- 진단: `GET /api/v1/market/realtime/status`의 `closingAuctionTicks`/`lastClosingAuctionSourceAt`, `outsideSessionTicks`/`lastOutsideSessionSourceAt`, `scannerCooldownFallbacks`/`lastScannerCooldownFallbackAt`([API](API.md#실시간-진단-필드-marketrealtimestatus)).
- 과거(2026-10-13 이전): 세션 필터가 원천시각 15:30:00 초과 체결을 조용히 버렸고, 전일 추천 종목의 OVERNIGHT 구독도 15:30 직후 해제됐다. 그래서 실시간 15:20/15:25 봉이 저장되지 않았다(09-28~09-30의 15:20/15:25 봉은 거래량 0인 KIS 분봉 보충 `BACKFILL` 봉이며 종가가 아니다).

### 분봉 보충(backfill)

과거 분봉 보충(`MARKET_BACKFILL_ENABLED`, 기본 켜짐)은 누락 구간 보완용이다. 실시간 당시 수신 여부를 증명하지 못하므로 15:15 공식 판단의 원자료로 소급 사용하지 않는다. 보충 시 15:20~15:29 동시호가 무체결 분봉은 버리고, 15:30 종가 체결 분봉은 15:25 봉에 합친다.

### 장중 재시작·재연결 빈 구간

KIS WebSocket은 끊긴 동안의 체결을 다시 보내지 않고, 5분봉 집계기는 메모리에만 있어 재시작하면 처음부터 다시 모은다. 그래서 끊긴 구간의 봉은 없거나, 다시 구독된 칸의 봉은 구독 뒤 체결만 담는다(예: 09:33 재시작, 09:35:41 재구독이면 09:30 봉 없음, 09:35 봉은 09:35:41 이후 체결만). 2026-10-16 이전에는 이 일부 체결 봉을 실시간 확정봉으로 저장해 보충도 고칠 수 없었다.

- `RealtimeCoverage`가 종목별 구독 성공(구독 응답 수신시각)과 장중 연결 끊김을 기록한다. 빈 구간은 두 경우만 만든다. **DISCONNECT**: 받던 종목이 연결 끊김으로 멈춘 경우, 끊김을 알아챈 시각이 든 칸부터. **RESTART**: 장중에 새로 뜬 프로세스의 첫 구독 중, 프로세스 시작 전 15분 안에 실시간 확정봉이 있던 종목, 그 마지막 봉 다음 칸부터. 빈 구간은 다시 구독된 시각이 든 칸까지다.
- 처음 구독, 장중 새 정밀 구독, 해지 뒤 재구독, 장 마감 뒤 연결 쉬기는 빈 구간이 아니다. 새로 구독한 종목의 첫 봉도 일부 체결 봉이지만, 평소 평가 입력을 바꾸므로 판단 기간 동안 그대로 둔다(별도 결정 필요).
- 빈 구간에 걸친 실시간 봉은 미확정(`is_final=false`, 출처 `REALTIME`)으로 저장하고, 같은 칸에 확정봉이 있으면 건드리지 않는다. Scanner 평가와 SSE는 지금처럼 메모리 봉으로 한다.
- `RealtimeGapRepairService`가 2분마다, 다시 구독된 칸이 끝나고 6분 30초 뒤에 빈 구간의 비었거나 미확정인 칸을 KIS 분봉으로 채운다(출처 `BACKFILL`, 실시간 확정봉은 덮지 않음, 최대 3회). 결과는 `realtime_coverage_gap`에 남는다. `MARKET_GAP_REPAIR_ENABLED=false`면 채우기만 끈다.
- 채운 봉은 저장 시각이 15:15 전이면 그날 평가 입력이 될 수 있다(평가는 저장·수정 시각이 평가 시각 전인 확정봉만 쓴다). 그 시각에 KIS에서 조회할 수 있던 값이라 미래 정보는 아니지만, 실시간 수신을 증명하지는 못한다. 재시작한 날의 평가는 기록으로 구분한다([ADR-010 보완](decisions/ADR-010-CLOSING-PRIMARY-SAMPLE-ALL-PRECISION.md)).
- 체결 하나하나로 만드는 Trajectory 1분 집계·대량체결, 호가 표본, 외국인·기관 가집계, 그 시각의 Scanner 탐지는 다시 만들 수 없다.

장 마감 후 후보 관측 직전(장 종료 10분 후)에는 `ObservationBackfillService`가 실시간 구독을 받지 못한 정밀 후보의 다음 거래일 5분봉을 KIS 당일 분봉으로 채운다(출처 `BACKFILL`, 실시간 확정봉은 덮어쓰지 않음, [ADR-010](decisions/ADR-010-CLOSING-PRIMARY-SAMPLE-ALL-PRECISION.md)).

## Feature

현재 Feature 버전은 `market-feature-v2`다. 진행 중 봉과 확정봉은 같은 의미로 비교하지 않는다. 거래량 배수는 같은 길이의 유효 baseline을 요구하며 결측, 재시작, 세션 불연속을 구분한다.

- VWAP은 당일 누적값과 대체값의 품질을 구분한다.
- 체결강도와 직전 틱 증분은 고정 시간창 지속 수급과 같지 않다.
- 과거 `turnoverRatio` 이름이 거래대금 배수를 뜻하는 경로가 있어 필드 정의를 확인한다.
- VI 기준가와 거래정지는 KIS 원천 필드 및 종목 상태와 함께 검증한다.

마감 추천의 장중·일봉 이동평균(`IntradayMovingAverageService`, `DailyMovingAverageService`)은 `closing` 패키지에서 계산한다.

## 구독과 후보

관심종목은 사용자 목록이고 실시간 구독은 KIS 한도(`KIS_WEBSOCKET_SUBSCRIPTION_LIMIT`, 기본 41) 안의 운영 자원이다. 관심종목에 추가하는 것만으로는 실시간 슬롯을 쓰지 않으며, 실시간 고정한 종목만 `WATCHLIST` 구독을 쓴다(기본 최대 10개). 구독 출처는 `WATCHLIST`, `MANUAL`, `PRECISION`, `OVERNIGHT`다. 정밀 구독 자리는 다른 출처만 가진 종목 수를 뺀 나머지다([운영 가이드](OPERATIONS.md#kis-한도)).

Broad 후보는 KIS 순위와 제한된 상세 현재가 조회(`MARKET_WIDE_DETAIL_QUOTE_BUDGET`, 기본 45)로 만들며, `QUOTE_FAILED`가 많으면 품질이 낮아진다. 시장 전체 스캔은 스캔당 최대 `MARKET_WIDE_CANDIDATE_LIMIT`(기본 41)개를 정밀 배정 후보로 넘긴다. Precision 후보는 실시간 체결로 더 정밀하게 관측한다. 교체는 15분 유지(`MARKET_PRECISION_MIN_HOLD`)와 10점 차이(`MARKET_PRECISION_REPLACE_MARGIN`) 조건을 따르고, 기본 14:50에 동결해 15:15 평가 전 최소 관측 시간을 확보한다. 동결 후 재시작하면 새 후보를 늦게 채워 넣지 않는다.

일봉은 WebSocket 슬롯과 무관한 KIS REST로 준비한다. 기본 08:10 준비 외에 14:25와 14:35에 당일 ACTIVE Precision 종목을 우선 보완하며, 평가일 직전 거래일까지의 일봉만 요청한다.

전역 마지막 틱만으로 모든 종목이 최신이라고 판단하지 않는다. 종목별 최신성, 봉 수, 연속성, 확정 여부, 원천/수신 시각과 거래가능 상태를 각각 확인한다.

## 연구용 데이터 (기본 꺼짐)

- **수정주가 일봉(V33)**: KIS 기간별 시세(`FHKST03010100`)를 `FID_ORG_ADJ_PRC=0`(수정주가)으로 ETF·ETN 제외 전 종목에 대해 받는다. 1회 호출은 최대 100거래일이라 처음에는 기본 210일(`MARKET_ADJUSTED_DAILY_LOOKBACK_DAYS`)을 나눠 받고, 이후에는 최근 140일(`MARKET_ADJUSTED_DAILY_REFRESH_DAYS`) 구간만 다시 읽는다. 겹친 구간의 가격이 바뀌면 기업행사로 과거가 재계산된 것으로 보고 전체 구간을 다시 받는다. 거래량만 바뀐 경우는 그 행만 갱신하고, 가격이 0인 행(거래정지일)은 건너뛴다. 거래일 08:30~장 종료 15분 후에는 실행하지 않는다. 운영 마감 추천의 일봉 이동평균은 계속 `stock_candle` `1D`(원주가)를 쓴다. 상장폐지 종목은 종목 마스터에 없으므로 이 데이터에도 없다(생존 편향).
- **장마감 Trajectory(V28)**: 기존 체결에서 1분 Aggregate와 기본 5분 간격 Snapshot을 만든다. [장마감 Trajectory](CLOSING_TRAJECTORY.md).
- **투자자 수급**: 외국인·기관 가집계와 KRX 프로그램 누적 수량을 Trajectory의 `investorFlow`로 제공한다. [투자자 수급](INVESTOR_FLOW.md).
- **호가·대량체결(V29)**: 10단계 호가는 기본 5초 간격의 제한된 REST 표본, 대량체결은 기존 WebSocket 체결에서 집계한다. [호가·대량체결](MICROSTRUCTURE.md).
- **지수 일봉(V37, 기록 전용·기본 켜짐)**: KOSPI·KOSDAQ 일봉을 KIS 국내업종 기간별 시세(`FHKUP03500100`)로 받아 `market_index_daily`에 저장한다. 한 번에 최근 쪽 50행만 오고 다음 페이지 표시가 없어 끝 날짜를 앞으로 옮겨 가며 받는다. 거래일 16:05(설정 `market.index-daily.cron`)에 최근 20일을 갱신한다. 왼쪽 메뉴의 고정 지수 칸은 장중 지수 관측(5분 간격, `CLOSING_TRAJECTORY_INDEX_ENABLED`)을 먼저 쓰고 없으면 이 일봉 종가를 쓴다.
- **상한가 직전 호가(V34·V36)**, **후보 시장경고·DART 공시(V35)**: [운영 가이드](OPERATIONS.md#상한가-직전-매수-연구-수집), [DATABASE](DATABASE.md).

연구용 데이터는 추천 점수·선정에 쓰지 않는다.
