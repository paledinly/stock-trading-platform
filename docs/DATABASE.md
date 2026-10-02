# Database

## Microstructure (V29)

`closing_microstructure_minute`에 호가와 대량체결 1분 JSON을 저장한다. `(kind, symbol, start_time)` 기본키로 확정된 관측을 덮어쓰지 않으며 `(start_time, symbol)` 인덱스를 추가한다. [집계·시각·보관 정책](MICROSTRUCTURE.md).

## Closing Trajectory (V28)

Phase 2 수급은 기존 closing_context_observation의 JSON을 재사용한다. Context에 nullable sourceAt/status/unit이 추가되며 기존 레코드는 유지한다. 추가 테이블·migration은 없다. [수급 데이터 의미](INVESTOR_FLOW.md).

`closing_minute_feature`, `closing_context_observation`, `closing_trajectory_snapshot`을 추가한다. 기존 봉/추천 테이블은 유지한다. 입력 JSON text는 불변이고 오전 outcome만 갱신한다. [키·인덱스·보관 정책](CLOSING_TRAJECTORY.md)을 참고한다.

## Intraday (V27)

- `intraday_input`: 거래일·종목·수신/평가시각과 변경 불가 입력 JSON. 실험 당시 Feature와 설정을 함께 저장한다.
- `intraday_recommendation`: UUID, 종목/Setup/추천시각 고유 제약, 변경 불가 추천 Snapshot, 별도 가변 성과 JSON, optimistic version 및 추적 완료 표식.
- 두 테이블 모두 기존 Closing/Scanner 성과 FK나 집계에 연결하지 않는다. Backtest는 원장을 읽어 별도 응답을 만들며 Paper 테이블에 추가하지 않는다.
- 틱 원장은 데이터량이 크다. 활성화 전 보관 용량과 기간을 정해야 하며 자동 삭제를 수행하지 않는다. 입력 원장을 제거하면 해당 기간 재생 가능성도 사라진다.

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
