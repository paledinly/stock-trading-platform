# Database

## Candidate Context · DART Filing (V35, 기록 전용)

- `closing_candidate_context`: 공식 마감 평가 직후(15:16:45, 미수집분 15:19:00) 그 run의 후보마다 KIS 현재가 응답의 시장경고(`mrkt_warn_cls_code`), 투자유의, VI 적용, 단기과열, 정리매매, 관리종목, 임시정지, 종목상태 코드를 저장한다. `received_at`은 수신시각이다. 조회 실패는 `error`와 함께 남기고 다음 회차에 다시 읽는다.
- `dart_filing`: DART 공시 목록(유가·코스닥 상장사)을 접수번호 기본키로 저장한다. DART는 접수 **날짜**만 주므로 15:15 판단에 쓸 수 있는 공시는 `rcept_dt`가 판단일보다 이전인 것뿐이다. 평일 07:40과 매일 18:10에 최근 3일을 다시 읽고, 이미 있는 행은 유지한다. `DART_API_KEY`가 없으면 수집하지 않는다.
- 두 테이블 모두 추천 점수·선정에 쓰지 않는다. ADR-010 판단 기간 동안 외부 요인 가설을 나중에 사전 등록해 검증하기 위한 기록이다.

## Limit-Up Watch (V34, 연구용)

`limit_up_watch`는 거래일 15:16:15와 15:18:30에 등락률 +20% 이상 종목(KIS 등락률 순위 KOSPI·KOSDAQ 각 상위 100, 표본당 최대 40종목)의 순위 값과 10단계 호가 최우선·합계 잔량을 저장한다. 상한가 근처 종목을 15:20 진입 마감 전에 실제로 살 수 있었는지 보는 [사전 등록 연구](../audit/2026-10-14-limit-up-entry/README.md)용이다. `ranking_received_at`·`book_received_at`은 수신시각, `book_source_at`은 KIS 호가 원천시각이다. 호가 조회 실패도 `error`와 함께 행으로 남긴다. 추천 전략은 이 테이블을 읽지 않는다.

## Adjusted Daily Candle (V33)

`daily_adjusted_candle`은 ETF·ETN을 제외한 상장 종목 전체의 수정주가 일봉을 `(stock_id, trade_date)` 기본키로 저장한다. 연구용 시점 기준 Universe(각 날짜 이전 거래대금 순위 등)를 재구성하기 위한 테이블이다. 수정주가는 기업행사 후 과거 값이 다시 계산되므로 `stock_candle`과 달리 행을 갱신하며, `fetched_at`은 마지막 기록시각이다. 운영 이동평균이 쓰는 `stock_candle`의 `1D`(원주가)는 바뀌지 않는다. `daily_adjusted_sweep_state`는 종목별 수집 범위(`covered_from`~`covered_through`)로 중단된 수집을 이어 가고 최근 구간만 다시 읽게 한다.

## Closing Daily Health (V32)

`closing_daily_health`는 `trade_date` 기본키로 마감 파이프라인 일별 완결성 판정을 저장한다. 당일 행만 갱신하고 지난 날짜는 다시 쓰지 않는다. 평가 완료시각(`run_completed_at`), 실시간 표본시각(`realtime_window_start/end`), 판정 기록시각(`checked_at`)을 구분한다. 가상 진입·후보 관측 상태별 수는 JSON 텍스트다. [판정 기준](OPERATIONS.md#마감-파이프라인-일별-완결성).

## Closing Paper Entry (V30)

`closing_paper_entry`는 `(run_id, stock_code)` 기본키로 v9 후보별 가상 진입을 저장한다. 평가 완료/진입 마감/원천 발생/수신/가상 진입 시각과 가격·상태를 구분하며 PENDING만 ENTERED로 바뀐다. 상태·마감 인덱스로 대기 행을 조회한다. 선정/미선정 비교 관측이 같은 기록을 사용하며 기존 v8 성과를 소급 변경하지 않는다.

## Microstructure (V29)

`closing_microstructure_minute`에 호가와 대량체결 1분 JSON을 저장한다. `(kind, symbol, start_time)` 기본키로 확정된 관측을 덮어쓰지 않으며 `(start_time, symbol)` 인덱스를 추가한다. [집계·시각·보관 정책](MICROSTRUCTURE.md).

## Closing Trajectory (V28)

Phase 2 수급은 기존 closing_context_observation의 JSON을 재사용한다. Context에 nullable sourceAt/status/unit이 추가되며 기존 레코드는 유지한다. 추가 테이블·migration은 없다. [수급 데이터 의미](INVESTOR_FLOW.md).

`closing_minute_feature`, `closing_context_observation`, `closing_trajectory_snapshot`을 추가한다. 기존 봉/추천 테이블은 유지한다. 입력 JSON text는 불변이고 오전 outcome만 갱신한다. [키·인덱스·보관 정책](CLOSING_TRAJECTORY.md)을 참고한다.

## Intraday (V27, 미사용)

장중 추천 모듈은 2026-10-13 제거했다([실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). V27 migration과 테이블은 그대로 남아 있고 Flyway 이력도 변경하지 않았다. `spring.jpa.hibernate.ddl-auto=validate`이므로 엔티티 제거로 스키마가 바뀌지 않는다.

- `intraday_input`: 거래일·종목·수신/평가시각과 변경 불가 입력 JSON. 더 이상 쓰지 않는다.
- `intraday_recommendation`: 장중 추천 Snapshot과 Paper 성과 JSON. 더 이상 쓰거나 읽지 않는다.
- 두 테이블 모두 Closing/Scanner 성과 FK나 집계에 연결되지 않았다. 데이터 보관·삭제(테이블 drop 포함)는 사용자 확인 후 별도 migration으로 진행한다.

## 역할

PostgreSQL은 영구 데이터의 시스템 오브 레코드다. Redis와 프로세스 메모리는 캐시와 실시간 상태이며 영구 이력으로 간주하지 않는다. Schema는 backend/src/main/resources/db/migration의 Flyway migration으로 관리한다.

## 주요 영역

- 종목/관심종목: stock, watchlist_group, watchlist_item
- 수동 거래 기록: trade, trade_reason, investment_journal
- 시장 데이터: stock_candle, market_broad_snapshot, 시장 스캔 실행 이력
- 탐지: scanner_setting, scanner_detection, detection_performance
- 마감 추천: 추천 실행/결과, closing_recommendation
- 익일 평가: overnight_performance, overnight_position_decision
- 운영 메타데이터: schema/version과 정밀 구독 세션

정확한 컬럼과 제약은 최신 migration을 기준으로 한다.

## 마이그레이션

- 적용된 migration을 수정하거나 이름을 바꾸지 않는다.
- Schema 변경은 다음 순번 migration으로 추가한다.
- FK가 있는 부모 행을 재생성할 때 자식 성과를 임의 삭제하지 않는다.
- 과거 결과 정정은 재현 가능한 원자료가 있는 범위만 수행한다.

## 로컬과 Supabase

기본 JDBC는 localhost:5432/stock_platform, 사용자는 stock이다. Supabase는 Session Pooler 정보와 sslmode=require를 .env에만 설정한다. endpoint와 비밀번호는 문서나 Git에 넣지 않는다.

백업 파일은 Git에서 제외한다. 기존 schema에 data-only restore를 반복하면 PK 중복이 발생할 수 있다. 빈 대상 DB 또는 명시적으로 정리한 schema에 FK 순서를 지켜 복원하며 system trigger 비활성화를 전제로 하지 않는다.
