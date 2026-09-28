# Database

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
