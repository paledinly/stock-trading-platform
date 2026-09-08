# 전체 시장 장마감 추천 구현 계획

기준일: 2026-09-07

## 1. 목표와 제약

목표는 KOSPI/KOSDAQ의 거래 가능한 전체 보통주가 매일 Broad Scan의 모집단에 포함되고, 그중 데이터가 충분한 후보를 장마감 추천으로 평가하는 것이다. 모든 종목을 WebSocket으로 동시에 구독하는 것이 목표는 아니다.

현재 애플리케이션의 KIS WebSocket 안전 한도는 41종목이다. 이 값은 `KIS_WEBSOCKET_SUBSCRIPTION_LIMIT`으로 조정할 수 있지만, 운영 환경에서는 KIS 구독 승인 응답으로 계정의 실제 한도를 검증해야 한다. 체결과 호가 구독 한도가 서로 독립적이라고 가정하지 않는다.

핵심 원칙은 다음과 같다.

- 전체 종목은 Broad Scan 대상이 된다.
- 순위 API와 저비용 REST 데이터로 후보를 압축한다.
- 제한된 종목만 WebSocket Precision 구독을 수행한다.
- Broad 데이터와 Precision 데이터를 동일한 품질로 취급하지 않는다.
- 추천 당시 확보한 데이터와 품질 등급을 함께 보존한다.
- 15:30 이후 데이터나 다음 거래일 데이터를 추천 점수에 사용하지 않는다.

## 2. 현재 구현과 부족한 부분

### 현재 구현

- 종목 마스터에서 활성·비관리·비정지 종목 조회
- `GET /api/v1/market-wide/scan` Broad Scan
- REST 현재가 기반 Broad Score
- 최대 30개 후보 반환
- 구독 여유에 따른 수동 Precision 구독
- Tick → Feature → Scanner → Detection
- 14:30 이후 Detection을 이용한 장마감 추천
- 장중·일봉 이동평균선, 오버나잇 성과 및 백테스트 기반

### 현재 한계

- Broad Scan은 종목코드순 첫 120개만 조회한다.
- KIS 순위 API를 사용하지 않는다.
- 전체 시장 후보를 자동으로 수집하는 스케줄러가 없다.
- Precision 후보 구독과 교체가 수동이다.
- 장마감 추천 입력이 `ScannerDetection`에만 한정된다.
- WebSocket에 들지 못한 종목은 추천 대상이 되기 어렵다.
- Broad 후보의 시점별 데이터와 제외 사유가 저장되지 않는다.
- 실제 KIS 구독 한도를 ACK 결과로 측정·노출하지 않는다.

## 3. 목표 데이터 흐름

```text
Stock Master
  -> Tradable Universe
  -> KIS Ranking APIs (거래대금/거래량/등락률/체결강도/고점근접)
  -> Candidate Union + Tradability Filter
  -> Broad Snapshot (200~500)
  -> Broad Score + Data Quality
  -> Precision Allocator (약 28~35)
  -> KIS H0STCNT0 WebSocket
  -> Feature Engine / Candle / Scanner
  -> Precision Detection
  -> Broad Candidate + Precision Detection 통합
  -> Closing Recommendation
  -> Performance / Backtest / Coverage Report
```

## 4. 구현 단계

## 단계 1 — KIS 순위 기반 Broad Scan

상태: **구현 완료 (2026-09-07)**. 실제 장중 KIS 계정 검증은 별도 운영 점검으로 남아 있다.

### 목적

종목코드순 120개 샘플링을 제거하고 전체 시장에서 움직이는 종목을 저비용으로 찾는다.

### 작업 항목

1. KIS 국내주식 순위 API Client를 추가한다.
2. 최소 다음 공급자를 독립 구현한다.
   - 거래대금 상위
   - 거래량 또는 거래량 증가 상위
   - 등락률 상위
   - 체결강도 상위
   - 신고가·고점 근접
3. 각 응답을 공통 `BroadCandidateSignal`로 정규화한다.
4. 종목코드 기준 합집합을 만들고 순위 출처를 모두 보존한다.
5. Stock Master의 거래 가능 조건으로 후처리한다.
6. API 일부 실패 시 성공한 순위 결과만으로 계속 실행한다.
7. 호출 수, 지연, 오류, 마지막 성공 시각을 Diagnostics에 추가한다.

### 권장 신규 타입

- `KisMarketRankingClient`
- `KisMarketRankingProvider`
- `BroadCandidateSignal`
- `BroadCandidateCollector`
- `BroadScanProperties`

### 주요 수정 대상

- `backend/.../kis/` REST Client 계층
- `backend/.../marketwide/application/MarketWideScannerService.java`
- `backend/.../marketwide/api/MarketWideDtos.java`
- `backend/src/main/resources/application.yml`
- `.env.example`

### 설정값

```text
MARKET_WIDE_ENABLED=true
MARKET_WIDE_BROAD_MAX_CANDIDATES=500
MARKET_WIDE_RANKING_REFRESH=2m
MARKET_WIDE_RANKING_RETRY=1
```

### 테스트

- 순위별 응답 파싱 Fixture
- 여러 순위의 합집합·중복 제거
- 비활성·관리·거래정지·ETF/ETN 제외
- 일부 API 실패 시 부분 성공
- 같은 시각의 중복 실행 방지

### 완료 조건

- KOSPI/KOSDAQ 모두에서 후보가 만들어진다.
- 응답 후보마다 하나 이상의 순위 출처와 원본 순위가 있다.
- 종목코드순 120개 제한에 의존하지 않는다.
- 실제 주문 API는 호출하지 않는다.

## 단계 2 — Broad Snapshot과 후보 이력

상태: **구현 완료 (2026-09-07)**. 실제 PostgreSQL 운영 데이터의 보존량과 조회 성능 점검은 남아 있다.

### 목적

마감 추천과 사후 검증에 사용할 당시 데이터를 보존한다.

### 권장 DB 변경

Flyway `V16__create_market_broad_snapshot.sql`을 검토한다.

`market_broad_snapshot` 후보 필드:

- `id`
- `session_date`
- `captured_at`
- `stock_id`
- `current_price`
- `change_rate`
- `accumulated_volume`
- `accumulated_trading_value`
- `day_open`, `day_high`, `day_low`
- `trade_strength`
- `broad_score`
- `ranking_sources` JSONB 또는 text
- `data_quality`
- `source_version`
- `created_at`

권장 유니크 키는 `(session_date, captured_at_bucket, stock_id)`다. 전체 원본 응답을 무제한 저장하지 않고 1~2분 버킷으로 제한한다.

### 품질 등급

- `PRECISION_A`: 실시간 30분 이상, 필요한 5분봉과 Feature 확보
- `PRECISION_B`: 실시간 10분 이상 또는 일부 Feature 누락
- `BROAD_C`: 순위와 REST Snapshot만 확보
- `INSUFFICIENT`: 핵심 필드 부족, 추천 제외

### 테스트

- 동일 버킷 upsert
- 오래된 Snapshot이 최신 값을 덮어쓰지 않음
- null Feature를 0으로 위장하지 않음
- Asia/Seoul 거래일과 버킷 경계

### 완료 조건

- 특정 추천일·시각의 후보 모집단을 재현할 수 있다.
- 후보 제외 사유와 데이터 품질을 조회할 수 있다.

## 단계 3 — 자동 Precision 구독 할당

상태: **구현 완료 (2026-09-07)**. 기본 설정은 비활성이며 실계정 장중 ACK 검증 후 활성화한다.

### 목적

Broad 상위 후보를 제한된 WebSocket 슬롯에 자동 배치한다.

### Registry 변경

`RealtimeSubscriptionRegistry.Source`에 다음 Source를 추가한다.

- `CORE`: 사용자가 지정한 핵심 고정 종목
- `PRECISION`: 자동 선별 후보

기존 `WATCHLIST`, `MANUAL`, `QUOTE`는 유지한다. 동일 종목에 여러 Source가 있으면 하나의 실제 KIS 구독만 사용한다.

### 권장 슬롯 정책

기본 41개 기준:

- Core/고정: 최대 10
- Precision: 최대 28
- Reserve: 최소 3

숫자는 설정으로 분리한다.

```text
MARKET_PRECISION_MAX=28
MARKET_CORE_MAX=10
MARKET_SUBSCRIPTION_RESERVE=3
MARKET_PRECISION_MIN_HOLD=15m
MARKET_PRECISION_REPLACE_MARGIN=10
MARKET_PRECISION_FREEZE_AT=14:50
```

### 교체 규칙

- 신규 후보 점수가 현재 최하위보다 설정 임계값 이상 높을 때만 교체한다.
- 최소 유지시간 전에는 거래정지·오류 외 사유로 해제하지 않는다.
- 이미 Detection이 발생한 종목은 보호시간을 둔다.
- 14:50 이후에는 후보군을 원칙적으로 고정한다.
- KIS 구독 성공 ACK 후에만 ACTIVE로 표시한다.
- 구독 실패 시 기존 종목을 먼저 해제하지 않는다.

### 권장 신규 타입

- `PrecisionSubscriptionAllocator`
- `PrecisionCandidateState`
- `SubscriptionCapacityPolicy`
- `SubscriptionAckTracker`

### 테스트

- 41개 초과 방지
- 복수 Source 참조 보존
- 최소 유지시간과 교체 임계값
- ACK 실패 시 롤백
- 재접속 후 목표 구독 목록 복원
- 후보 점수 진동 시 불필요한 교체 방지

### 완료 조건

- 사용자 조작 없이 Precision 후보가 자동 구독된다.
- 구독 수가 설정 한도와 Reserve 정책을 위반하지 않는다.
- 대시보드에서 요청·승인·실패·해제 상태를 확인할 수 있다.

## 단계 4 — 장중 자동 실행과 운영 진단

상태: **구현 완료 (2026-09-07)**. 휴장일은 설정 목록으로 차단하며, 자동 KRX 휴장 캘린더 연동은 후속 운영 개선 항목이다.

### 목적

운영자가 버튼을 누르지 않아도 후보 수집과 갱신이 수행되게 한다.

### 권장 스케줄

- 09:00~14:00: 5분 간격
- 14:00~14:30: 2분 간격
- 14:30~15:20: 1분 간격
- 15:20: 후보 고정 및 최종 Snapshot
- 15:25 전후: 마감 추천 생성

스케줄러는 KRX 영업일·장 상태를 확인하고, 동일 거래일·버킷의 중복 실행을 막아야 한다. 서버 재시작 시 마지막 완료 버킷부터 복구한다.

### Diagnostics 추가

- 전체 거래 가능 종목 수
- 순위 API별 후보 수와 마지막 성공
- 합집합 후보 수
- Broad Snapshot 성공/실패 수
- Precision 목표/요청/승인 종목 수
- 후보 교체 횟수
- 종목별 실시간 수집 시작 시각
- 추천 평가 가능 등급별 종목 수
- API Rate Limit 및 Circuit Breaker 상태

### 완료 조건

- 휴장일에는 실행되지 않는다.
- 애플리케이션 재시작 후 중복 후보·중복 구독이 생기지 않는다.
- 장애 원인을 운영 화면에서 확인할 수 있다.

## 단계 5 — Broad/Precision 통합 마감 추천

상태: **구현 완료 (2026-09-08)**. Broad 추천의 실전 성과와 영속 구독 Coverage 검증은 단계 6에서 수행한다.

### 목적

WebSocket에 선택되지 않은 종목도 Broad 데이터가 충분하면 제한적으로 추천 평가 대상에 포함한다.

### 모델 변경

추천 입력을 `ScannerDetection` 하나로 고정하지 않고 공통 인터페이스로 분리한다.

```text
ClosingCandidate
  - PrecisionDetectionCandidate
  - BroadSnapshotCandidate
```

`ClosingRecommendation`에는 다음 필드를 추가하는 방안을 권장한다.

- `candidate_source`: `PRECISION` 또는 `BROAD`
- `data_quality`
- `coverage_minutes`
- `broad_snapshot_id` nullable
- 기존 `source_detection_id` nullable 전환 여부 검토
- `missing_features`

DB 변경은 `V17__extend_closing_recommendation_sources.sql`로 분리한다.

### 평가 규칙

- Precision과 Broad의 점수 Formula를 분리한다.
- Broad에서 확보하지 못한 Feature는 0점으로 간주하지 않고 미평가 처리한다.
- Broad 후보는 데이터 품질 패널티와 최대 점수 상한을 둔다.
- 동일 종목에 Precision Detection이 있으면 Broad 후보보다 우선한다.
- 관리·정지·ETF/ETN 필터는 최종 단계에서 다시 적용한다.
- 추천 결과에 후보 출처, 원본 순위, 데이터 품질을 노출한다.

### 테스트

- 동일 종목 Precision 우선
- Broad-only 후보의 추천 편입
- 품질 부족 후보 제외
- Feature 누락 점수 처리
- 14:30 이전/15:30 이후 데이터 차단
- 추천 재생성의 멱등성

### 완료 조건

- 그날 Broad Scan에 포함된 전체 시장 후보가 평가 대상 또는 명시적 제외 상태를 가진다.
- 추천마다 사용 데이터와 산출 근거를 재현할 수 있다.
- Broad 결과를 Precision 결과처럼 과신하게 표시하지 않는다.

## 단계 6 — Coverage 및 성과 검증

상태: **구현 완료 (2026-09-08)**. 통계적 판단은 실제 장중 데이터와 다음 거래일 성과가 축적된 후 수행한다.

### 목적

전체 시장을 실제로 얼마나 커버했고 후보 축소가 유효했는지 측정한다.

### 일별 지표

- 전체 거래 가능 종목 수
- 순위 API가 한 번 이상 포착한 종목 수와 비율
- Broad Snapshot 확보 종목 수와 비율
- Precision 구독 종목 수와 평균 체류시간
- Precision Detection 발생 종목 수
- Broad/Precision 추천 수
- 추천 제외 사유별 종목 수
- Broad 후보 중 다음날 목표수익 도달 종목 포착률
- Precision 승격 전후 성과

### 백테스트 분리

- Broad-only 전략
- Precision-only 전략
- Broad → Precision 2단계 전략
- 고정 관심종목 전략

각 전략의 수익률뿐 아니라 후보 Coverage와 API 비용을 함께 비교한다.

### 완료 조건

- 특정 날짜에 추천이 없었던 이유를 데이터로 설명할 수 있다.
- 후보 수, API 호출량, 구독 교체량, 성과 사이의 관계를 비교할 수 있다.

## 5. API 변경안

기존 API를 우선 확장한다.

- `GET /api/v1/market-wide/scan`
  - 순위 출처, 품질 등급, Snapshot 시각 추가
- `POST /api/v1/market-wide/scan/run`
  - 운영자 수동 Broad Scan 실행
- `GET /api/v1/market-wide/status`
  - Scheduler, Ranking, Snapshot, Precision 상태
- `GET /api/v1/market-wide/candidates`
  - 현재 Broad/Precision 후보와 제외 사유
- `GET /api/v1/market/subscriptions`
  - Source, 요청/승인 상태, 유지시간 추가
- 기존 장마감 추천 응답
  - 후보 출처, 데이터 품질, 누락 Feature, Coverage 추가

수동 실행 API에는 동시 실행 방지와 동일 버킷 멱등성을 적용한다.

## 6. 장애 처리 기준

- Ranking API 일부 장애: 성공한 Ranking 결과로 계속하되 품질 저하 표시
- 전체 Ranking 장애: 직전 Snapshot을 제한 시간 내에서만 재사용
- Quote Rate Limit: 큐잉 후 다음 주기로 넘기며 무제한 재시도 금지
- WebSocket disconnect: 목표 구독 목록을 유지하고 재연결 후 재구독
- ACK 실패: ACTIVE로 표시하지 않고 Reserve 슬롯을 훼손하지 않음
- Redis 장애: DB Snapshot과 로컬 단기 상태로 제한적 계속, 중복 가능성 기록
- PostgreSQL 장애: 신규 추천 생성 중단, 메모리 결과를 공식 추천으로 표시하지 않음
- 장중 재시작: 마지막 완료 버킷과 목표 구독 목록에서 복구
- 거래정지·VI: 후보 상태와 평가 제한 사유 기록

## 7. 개발 단위와 권장 커밋

한 커밋에 전체 기능을 넣지 않는다.

1. `feat: add KIS market ranking clients`
2. `feat: collect market-wide broad candidates`
3. `feat: persist broad market snapshots`
4. `feat: allocate precision subscriptions automatically`
5. `feat: schedule market-wide closing scans`
6. `feat: include broad candidates in closing recommendations`
7. `feat: expose market coverage diagnostics`
8. `test: compare broad and precision closing strategies`

각 커밋은 Backend Unit Test를 통과해야 하며, API 변경 커밋은 Web 타입과 화면 테스트도 함께 갱신한다.

## 8. 착수 순서

바로 다음 작업은 **단계 1 — KIS 순위 기반 Broad Scan**이다.

구현 전 확인할 항목:

1. 실제 KIS 계정에서 사용 가능한 국내주식 순위 TR과 응답 필드
2. 각 TR의 초당 호출 제한
3. KOSPI/KOSDAQ 시장 파라미터
4. 결과 건수와 페이지네이션 가능 여부
5. 실전/모의 환경 지원 차이

확인 후 Fixture를 먼저 만들고 Client → 정규화 → 후보 합집합 → Service/API 순서로 구현한다. 단계 1이 실제 장중 데이터로 검증되기 전에는 자동 구독 교체를 활성화하지 않는다.

## 9. 최종 완료 정의

다음 조건을 모두 만족하면 전체 시장 장마감 추천 기반이 완성된 것으로 본다.

- 거래 가능한 전체 종목이 Broad 모집단에 포함된다.
- 시장에서 의미 있게 움직인 종목이 순위 기반 후보로 수집된다.
- WebSocket 한도를 넘지 않고 상위 후보가 자동 정밀 구독된다.
- Broad-only 종목도 데이터 품질을 명시한 상태로 평가될 수 있다.
- 추천 당시 데이터, 점수, 출처, 누락 항목을 재현할 수 있다.
- 미래 데이터 없이 실시간과 백테스트가 동일한 평가 규칙을 사용한다.
- API 제한, 장애, 미탐지 원인을 운영 화면에서 확인할 수 있다.
