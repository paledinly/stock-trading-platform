# Analytics·Supabase 전송량 최적화 5단계 (2026-09-14)

## 확인한 원인과 적용

| 경로 | 이전 | 변경 |
| --- | --- | --- |
| 시장전체 coverage | 당일 모든 Broad entity 및 ranking_sources/원본 관측 데이터 로드 | SQL에서 종목별 최신 snapshot 1건만 선택, 상태/종목 ID/거래 가능 플래그만 projection |
| coverage 탐지 종목 수 | 탐지 entity 전체 조회 후 distinct | DB count(distinct stock_id), 날짜 범위 끝은 다음날 미포함 |
| Scanner Analytics | 탐지 전체 로드 후 탐지마다 성과 findById | 설정/기간 필터를 DB에 적용, 집계용 15개 필드를 단일 projection 조회 |
| 같은 summary 요청 반복 | 매번 재조회 | 서버 단위 최대 64개 결과 DTO 캐시, 기본 TTL 60초, 오류는 캐시하지 않음 |
| 탐지 목록 | 8초 polling | 당일 30초 polling, 과거 포함은 polling 없음 |
| coverage 화면 | 60초 polling | 120초 polling/동일 staleTime, 창 포커스 자동 재조회 없음 |
| Analytics 화면 | 기본 포커스/재연결 refetch | 60초 staleTime, 포커스/재연결 자동 refetch 없음 |

커버리지 최신 선택은 captured_at, 동시 시각이면 ID로 결정한다. 상태 집계와 추천 source별 성과 계산은 유지하며, 보강 snapshot도 최신 선택에 포함된다. 추천/Scanner 신호 계산과 실시간 tick 수집은 변경하지 않는다.

Analytics API 조회 기간은 최대 93일이며 역전 기간, 양수가 아닌 target, 음수가 아닌 stop, 유효하지 않은 minimumSampleSize는 HTTP400으로 거절한다. 과도한 요청을 조용히 자르거나 일부 표본으로 통계를 계산하지 않는다. 기존 통계 공식은 그대로 유지했다. Target/Stop 둘 다 도달한 경우의 선도달 판정 등 기존 Analytics 정합성 한계는 이 최적화로 해결했다고 주장하지 않는다.

## 설정·호환·신선도

ANALYTICS_SUMMARY_CACHE_TTL=60s를 .env.example에 추가했다. 0s이면 서버 캐시를 끄며 허용 최대 TTL은 5분이다. 실제 .env는 변경하지 않았다. 캐시는 프로세스 단위이며 재시작 시 초기화된다. DTO만 저장하고 JPA entity를 캐시하지 않는다.

추천 생성·성과 업데이트 직후 coverage/Analytics가 서버 TTL 동안 이전 결과를 보여줄 수 있다. 웹 coverage는 다음 120초 polling 때 갱신된다. 응답의 기존 measurement/generated 시각을 확인해야 한다. 추천 생성/성과 상세 자체와 최신 수집 진단은 summary 캐시 대상이 아니다. 새 migration/DB 데이터 삭제는 없다. 운영 반영은 백엔드 재시작/프론트 반영이 필요하다.

## 검증과 실제 비용 확인

테스트는 projection 집계 값/단일 조회, 같은 키 재사용·다른 키 분리·만료·0 TTL·오류 미보존, 과도/역전 기간 차단, H2 최신 snapshot 선택과 nullable setting 쿼리 실행, 기존 기능 회귀를 포함한다. 실제 Supabase PostgreSQL 쿼리 실행계획/전송 바이트와 장중 KIS는 검증하지 않았다.

DB → 백엔드 결과 전송량을 줄이는 변경이다. 웹 polling 감소는 해당 DB 조회 횟수도 줄인다. 웹 정적 번들 크기를 줄이는 것과 Supabase DB egress 감소는 별개다. 절감률/월 비용을 아직 수치로 보장할 수 없다.

동일 조건의 운영일을 비교해야 한다: 일별 Supabase egress 증가분, 활성 화면/동시 사용자 수, snapshot 및 detection 수, coverage/Analytics 요청 횟수와 SQL 실행시간. query plan은 운영 DB에서 별도 읽기 점검을 통해 확인한다. 이미 적용된 stock/time 및 date/score 인덱스를 유지하며, 실제 계획을 보기 전에 중복 인덱스를 추가하지 않았다.

아직 남는 비용은 성과 추적/복구, 추천 생성 시 MA 조회, snapshot 이력/상세 조회 등이다. 전체 egress가 계속 높으면 이 백그라운드 경로의 호출 횟수/행 크기를 따로 측정해야 한다. 화면 미사용 때에도 수집/성과 추적이 발생시키는 egress는 이 화면 최적화만으로 제거되지 않는다.

운영 DB 직접 변경, 서비스 재시작, git commit/push는 수행하지 않았다.
