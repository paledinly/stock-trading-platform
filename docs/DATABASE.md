# Database

## 역할

PostgreSQL은 영구 데이터의 시스템 오브 레코드다. Redis와 프로세스 메모리는 캐시와 실시간 상태이며 영구 이력으로 간주하지 않는다. Schema는 `backend/src/main/resources/db/migration`의 Flyway migration(`V1`~`V36`)으로 관리하고, `spring.jpa.hibernate.ddl-auto=validate`라 엔티티가 스키마를 바꾸지 않는다. 정확한 컬럼과 제약은 최신 migration을 기준으로 한다.

## 테이블 목록

| 영역 | 테이블 (만든 migration) |
| --- | --- |
| 종목 | `stock`(V2) |
| 관심종목 | `watchlist_group`, `watchlist_item`(V3, 실시간 고정 컬럼 V25) |
| 수동 거래 기록 | `trade`, `trade_reason`, `investment_journal`(V4) |
| 봉 | `stock_candle`(V5, `5M`·`1D`, 보충 보강 V8) |
| 탐지 | `scanner_setting`, `scanner_detection`(V6, 확장 V10~V12·V23) |
| 탐지 성과 | `detection_performance`(V7, V9) |
| 시장 전체 스캔 | `market_broad_snapshot`(V16, V21), `market_wide_scan_run`(V17) |
| 정밀 구독 이력 | `precision_subscription_session`(V19) |
| 마감 추천 | `closing_recommendation_run`(V20, V22), `closing_recommendation`(V13, V18, V22) |
| 다음 거래일 성과 | `overnight_performance`(V14, V22, V24), `overnight_position_decision`(V15) |
| 후보 관측 | `closing_candidate_observation`(V26) |
| 가상 진입 | `closing_paper_entry`(V30) |
| 거래일 달력 | `trading_calendar_day`(V31) |
| 일별 완결성 | `closing_daily_health`(V32, V39, V41) |
| 실시간 빈 구간 기록 | `realtime_coverage_gap`(V38) |
| 연구: Trajectory | `closing_minute_feature`, `closing_context_observation`, `closing_trajectory_snapshot`(V28) |
| 연구: 호가·대량체결 | `closing_microstructure_minute`(V29) |
| 연구: 수정주가 일봉 | `daily_adjusted_candle`, `daily_adjusted_sweep_state`(V33) |
| 연구: 상한가 직전 | `limit_up_watch`(V34, V36) |
| 기록 전용: 외부 요인 | `closing_candidate_context`, `dart_filing`(V35), `market_index_daily`(V37), `overseas_daily`(V40) |
| 사용하지 않음 | `platform_schema_version`(V1), `intraday_input`, `intraday_recommendation`(V27) |

## 주요 테이블 설명

### closing_paper_entry (V30)

`(run_id, stock_code)` 기본키로 마감 평가 후보별 가상 진입을 저장한다. 평가 완료/진입 마감/원천 발생/수신/가상 진입 시각과 가격·상태를 구분하며 PENDING만 ENTERED로 바뀐다. 상태·마감 인덱스로 대기 행을 조회한다. 선정/미선정 비교 관측이 같은 기록을 쓰며 과거 v8 성과를 소급 변경하지 않는다.

### closing_daily_health (V32, V39)

`trade_date` 기본키로 마감 파이프라인 일별 완결성 판정을 저장한다. 당일 행만 갱신하고 지난 날짜는 다시 쓰지 않는다. 평가 완료시각(`run_completed_at`), 실시간 표본시각(`realtime_window_start/end`), 판정 기록시각(`checked_at`)을 구분한다. 가상 진입·후보 관측 상태별 수는 JSON 텍스트다. V39에서 판정에 쓰는 정밀 후보 관측(`precision_observation_statuses`), 참고용 Broad 관측(`broad_observation_statuses`), 기록 단계(`record_stage`: `PRELIMINARY` 15:45 · `FINAL` 18:15)를 더했다. `observation_statuses`는 합계로 남는다. V41의 `indicators`(JSON)는 판정에 쓰지 않는 참고 지표다. [판정 기준](OPERATIONS.md#마감-파이프라인-일별-완결성).

### trading_calendar_day (V31)

KIS 국내휴장일조회로 받은 날짜별 개장 여부(`market_open`), 출처, 수신시각을 저장한다. `CLOSING_CALENDAR_KIS_REFRESH_ENABLED=true`일 때만 채워진다.

### daily_adjusted_candle · daily_adjusted_sweep_state (V33, 연구용)

`daily_adjusted_candle`은 ETF·ETN을 제외한 상장 종목 전체의 수정주가 일봉을 `(stock_id, trade_date)` 기본키로 저장한다. 시점 기준 Universe(각 날짜 이전 거래대금 순위 등)를 재구성하기 위한 테이블이다. 수정주가는 기업행사 후 과거 값이 다시 계산되므로 `stock_candle`과 달리 행을 갱신하며 `fetched_at`은 마지막 기록시각이다. 운영 이동평균이 쓰는 `stock_candle`의 `1D`(원주가)는 바뀌지 않는다. `daily_adjusted_sweep_state`는 종목별 수집 범위(`covered_from`~`covered_through`)로 중단된 수집을 이어 가고 최근 구간만 다시 읽게 한다.

### limit_up_watch (V34·V36, 연구용)

거래일 15:16:15와 15:18:30에 등락률 +20% 이상 종목(KIS 등락률 순위 KOSPI·KOSDAQ 각 상위 100, 표본당 최대 40종목)의 순위 값과 10단계 호가 최우선·합계 잔량을 저장한다. 상한가 근처 종목을 15:20 진입 마감 전에 실제로 살 수 있었는지 보는 [사전 등록 연구](../audit/2026-10-14-limit-up-entry/README.md)용이다. `ranking_received_at`·`book_received_at`은 수신시각, `book_source_at`은 KIS 호가 원천시각이다. 호가 조회 실패도 `error`와 함께 행으로 남긴다. V36부터 `candidate_source`(`RANKING` 또는 최근 10분 시장 스캔 스냅샷 `BROAD_SNAPSHOT`)와 `ranking_rows`(시장별 순위 응답 건수)를 함께 저장한다. 추천 전략은 이 테이블을 읽지 않는다.

### closing_candidate_context · dart_filing (V35, 기록 전용)

- `closing_candidate_context`: 공식 마감 평가 직후(15:16:45, 미수집분 15:19:00) 그 run의 후보마다 KIS 현재가 응답의 시장경고(`mrkt_warn_cls_code`), 투자유의, VI 적용, 단기과열, 정리매매, 관리종목, 임시정지, 종목상태 코드를 저장한다. `received_at`은 수신시각이다. 조회 실패는 `error`와 함께 남기고 다음 회차에 다시 읽는다.
- `dart_filing`: DART 공시 목록(유가·코스닥 상장사)을 접수번호 기본키로 저장한다. DART는 접수 **날짜**만 주므로 15:15 판단에 쓸 수 있는 공시는 `rcept_dt`가 판단일보다 이전인 것뿐이다. 평일 07:40과 매일 18:10에 최근 3일을 다시 읽고, 이미 있는 행은 유지한다. `DART_API_KEY`가 없으면 수집하지 않는다.
- 두 테이블 모두 추천 점수·선정에 쓰지 않는다. ADR-010 판단 기간 동안 외부 요인 가설을 나중에 사전 등록해 검증하기 위한 기록이다.

### realtime_coverage_gap (V38, 기록 전용)

장중 재시작(`RESTART`)·연결 끊김(`DISCONNECT`)으로 실시간 수신이 끊긴 구간이 종목·재구독마다 한 행이다. 키는 (`stock_code`, `resumed_at`). `lost_from`은 빈 구간 첫 5분 칸, `resumed_at`은 다시 구독된 시각(호스트 수신시각), `target_bars`는 대상 칸 수, `repaired_bars`는 끝났을 때 확정봉이 있는 칸 수, `still_missing`은 남은 칸 수(체결이 없던 칸 포함)다. 그날 표본이 연속 관측이 아니었음을 구분하는 데 쓴다.

### overseas_daily (V40, 기록 전용)

미국 지수·원/달러·EWY 일봉. 키는 (`symbol`, `trade_date`). `symbol`은 SPX·COMP·NDX·DJI·VIX·SOX·USDKRW·EWY, `source_code`는 KIS 조회 구분(`N:SPX`, `X:FX@KRW`, `AMS:EWY` 등)이다. `trade_date`는 거래소 현지 거래일, `fetched_at`은 호스트 수집 시각이다. 미국 거래일 D의 일봉은 한국시간 D+1 아침에야 확정되므로, 평가 시점에 쓸 수 있었는지는 `fetched_at`이 아니라 이 규칙으로 판단한다. 외부 요인 가설(H-D 등) 검증용이며 점수에 쓰지 않는다.

### market_index_daily (V37, 기록 전용)

KOSPI(`0001`)·KOSDAQ(`1001`) 지수 일봉(KIS `FHKUP03500100`). 키는 (`index_code`, `trade_date`). `volume`·`trading_value`는 KIS 단위 그대로(`acml_vol`, `acml_tr_pbmn`), `fetched_at`은 호스트 수집 시각이다. 거래일 16:05에 최근 20일을 다시 받아 갱신하고 과거는 `POST /market-data/index-daily/backfill`로 채운다. 시장 국면 가설 검증과 화면의 전일 종가 표시에 쓰며 추천 점수·선정에는 쓰지 않는다.

### Trajectory · Microstructure (V28·V29, 연구용)

- `closing_minute_feature`, `closing_context_observation`, `closing_trajectory_snapshot`: 입력 JSON text는 불변이고 오전 outcome만 갱신한다. [키·인덱스·보관 정책](CLOSING_TRAJECTORY.md#6-db-변경).
- 투자자 수급은 `closing_context_observation`의 JSON을 재사용한다(kind `FOREIGN_NET_BUY`/`INSTITUTION_NET_BUY`/`PROGRAM_NET_BUY`, 추가 migration 없음). [수급 데이터 의미](INVESTOR_FLOW.md).
- `closing_microstructure_minute`: 호가와 대량체결 1분 JSON. `(kind, symbol, start_time)` 기본키로 확정된 관측을 덮어쓰지 않으며 `(start_time, symbol)` 인덱스가 있다. [집계·시각·보관 정책](MICROSTRUCTURE.md).

## 사용하지 않는 테이블

- `platform_schema_version`(V1): Flyway 연결 확인용으로 만든 테이블이다. 코드가 읽거나 쓰지 않는다.
- `intraday_input`, `intraday_recommendation`(V27): 2026-10-13 제거한 장중 추천 모듈의 테이블이다([실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). V27 migration과 Flyway 이력은 그대로다. `intraday_input`은 2026-10-13 백업 후 비웠고([보관 기록](../audit/2026-10-13-intraday-archive/README.md)), `intraday_recommendation`은 데이터가 남아 있다. 어떤 Closing/Scanner 성과 FK나 집계에도 연결되지 않는다.
- 이 테이블들의 삭제(drop)는 사용자 확인 후 새 migration으로 진행한다.

## 마이그레이션 원칙

- 적용된 migration을 수정하거나 이름을 바꾸지 않는다.
- Schema 변경은 다음 순번 migration(현재 다음은 `V37`)으로 추가한다.
- FK가 있는 부모 행을 재생성할 때 자식 성과를 임의 삭제하지 않는다.
- 과거 결과 정정은 재현 가능한 원자료가 있는 범위만 수행한다.

## 로컬과 Supabase

기본 JDBC는 `localhost:5432/stock_platform`, 사용자는 `stock`이다. Supabase는 Session Pooler 정보와 `sslmode=require`를 `.env`에만 설정한다. endpoint와 비밀번호는 문서나 Git에 넣지 않는다. 운영 DB가 로컬인지 Supabase인지는 TODO: 확인 필요.

백업 파일은 Git에서 제외한다. 기존 schema에 data-only restore를 반복하면 PK 중복이 생길 수 있다. 빈 대상 DB 또는 명시적으로 정리한 schema에 FK 순서를 지켜 복원하며 system trigger 비활성화를 전제로 하지 않는다.
