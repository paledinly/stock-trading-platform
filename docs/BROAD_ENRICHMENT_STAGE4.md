# 실패 후보 비동기 보강 4단계 (2026-09-14)

## 흐름

기존 랭킹 수집 → 제한된 상세 조회 → Broad snapshot 저장 → 필수값 부족 후보 접수 → 전용 단일 워커 → 캐시/틱/랭킹/REST 재확인 → 새 Broad snapshot 추가.

일반 스캔은 보강 완료를 기다리지 않는다. 같은 종목 작업을 병합하며 원래 접수시간과 시도 횟수는 초기화하지 않는다. 기존 순위 순서대로 접수하며, 재시도 대기 중에는 다음 준비된 후보를 처리한다. 완료/실패 작업도 max-age 동안 보관하여 바로 재접수되는 것을 막는다. 용량에는 이 보관 작업도 포함된다.

전용 broadEnrichmentScheduler에서 1건씩 실행한다. 기존 작업은 별도 taskScheduler에 유지하며 KIS 요청 간격/유량 제한은 모든 데이터 REST 호출이 공유한다. HTTP 제한 오류의 내부 재시도와 큐 전체 작업 재시도는 별개이며 모두 제한된다. 입력 필드 오류는 큐에서도 추가 재시도하지 않는다.

## 시점 및 장애 안전

- Asia/Seoul, 기존 주말/설정 휴장일/장중 정책을 사용한다. 설정된 휴장일에 없는 임시 휴장일은 자동 판별하지 않는다.
- featureFreezeAt(기본 15:20) 전만 접수/실행한다. 요청 중 동결 시각을 넘거나 작업이 만료되면 저장하지 않는다.
- 결과를 과거 랭킹 bucket에 덮어쓰지 않는다. 실제 완료시간 captured_at으로 새 행을 저장하고 quote_source는 ENRICHED_*로 구분한다. quoted_at과 랭킹 원본 관측시간도 보존한다.
- 기존 추천/성과/audit는 자동 재생성하지 않는다. 보강 완료 후 새 생성 실행에 반영된다. Precision 신규 할당은 다음 일반 스캔의 기존 할당기를 통해 이뤄진다.
- KIS/DB 실패는 지연 후 제한 재시도하며 워커 밖으로 예외를 전파하지 않는다. 큐가 가득 차면 접수 제외 카운터를 올리고 일반 스캔을 계속한다.
- 메모리 큐이며 앱 재시작 시 작업과 누적 진단은 초기화된다. 다음 스캔에서 부족한 후보를 재접수한다. 여러 백엔드 인스턴스 사이 중복 방지는 제공하지 않는다.

## 환경변수 (.env.example)

| 변수 | 기본값 |
| --- | --- |
| MARKET_WIDE_ENRICHMENT_ENABLED | true |
| MARKET_WIDE_ENRICHMENT_CAPACITY | 200 |
| MARKET_WIDE_ENRICHMENT_MAX_ATTEMPTS | 3 |
| MARKET_WIDE_ENRICHMENT_MAX_AGE | 3m |
| MARKET_WIDE_ENRICHMENT_RETRY_DELAY | 10s |
| MARKET_WIDE_ENRICHMENT_POLL_INTERVAL | 1s |

3번은 최초 시도를 포함한다. 재시도 지연은 10s, 20s 등 지수 증가하며 성공·만료·시도 소진 시 중단한다. poll-interval은 처리 완료 후 다음 처리까지 간격이다. 실제 .env는 변경하지 않았다. 자동 Broad 스캔 활성화는 기존 MARKET_WIDE_SCHEDULE_ENABLED로 별도 설정한다. 수동 스캔으로 접수된 작업도 장중이면 보강된다.

## API/UI 및 DB

기존 GET /api/v1/market-wide/status에 enrichment 필드 추가. 활성 상태, 보관 수/용량, QUEUED/RUNNING/RETRY/SUCCEEDED/EXHAUSTED/EXPIRED, 접수/중복/성공/소진/만료/제외 누적 건수, 최근 처리시간/오류 제공. 기존 시장전체 상태 조회를 재사용하며 추가 DB polling은 없다.

신규 테이블/migration 없음. 성공 보강 종목당 snapshot 한 행을 추가하므로 DB 저장량과 조회량은 일부 증가한다. 다음 5단계에서 집계/조회 범위/egress를 함께 점검한다.

## 테스트와 운영 반영

실제 KIS 없이 fixture 테스트: 중복/큐 포화, 성공 1회 저장, 지연 재시도/시도 소진, 접수 반복 시 TTL 유지, 동결 시각 도중 통과 시 저장 차단, 비활성/장외, DB 실패 처리, 기존 bucket 변경 없이 append, 웹 상태 표시.

운영 반영은 백엔드 재시작/프론트 빌드 반영이 필요하다. 실제 장중 실패율·보강 완료율·호출량은 운영 축적으로 확인해야 한다. 추천 점수 기준/BROAD 관찰 전용 정책은 변경하지 않는다. 운영 서비스 재시작, 운영 DB 직접 변경, git commit/push는 이번 작업에서 하지 않는다.
