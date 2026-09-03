# 개발 작업 인수인계

기준일: 2026-09-02

작업 폴더: `D:\sunmo\codexApp\stock-trading-platform`

브랜치: `main`
원격 저장소: `https://github.com/paledinly/stock-trading-platform.git`

이 문서는 다른 Codex 세션이나 개발 에이전트가 현재 미커밋 작업을 보존하면서 다음 Phase를 이어가기 위한 기준 문서다. 문서보다 `git status --short`와 실제 소스를 우선한다.

## 1. 시작할 때 반드시 확인할 사항

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform
git status --short
git status -sb
git log -3 --oneline
git diff --check
```

2026-09-02 Phase 1·Phase 2A 기능 구현 커밋은 다음과 같다.

```text
6737bc7 feat: harden market data and performance recovery
```

이 커밋은 `main` 브랜치에 push하는 대상으로 검증됐다. 다음 에이전트는 `git status -sb`와 `git log -3 --oneline`으로 원격 반영 상태를 다시 확인한다. 사용자 지시 없이 reset, restore, checkout, clean, stash, pull, rebase를 실행하지 않는다.

다음 기존 미추적 항목은 이번 개발 결과가 아니므로 수정하거나 스테이징하지 않는다.

```text
.codex-write-test.tmp
patch_probe.txt
backend/bin/
```

`.env`에는 실제 KIS 및 DB 비밀값이 있을 수 있다. 내용을 출력하거나 Git에 추가하지 않는다.

## 2. 현재 시스템 기준 구조

```text
KIS REST 현재가/당일 분봉
         │
KIS H0STCNT0 WebSocket
         ↓
MarketTick
 ├─ QuoteStateStore(memory + Redis)
 ├─ SSE quote.updated
 ├─ FiveMinuteCandleAggregator
 │    ├─ stock_candle
 │    └─ Candle Backfill/GAP merge
 ├─ ScannerEngine
 │    └─ scanner_detection
 └─ DetectionPerformanceTracker
      ├─ in-memory pending registry
      ├─ one-second batch persistence
      └─ detection_performance
```

Spring Boot 모듈러 모놀리스를 유지한다. React와 Flutter는 Backend API만 호출하고 KIS 인증값은 서버 밖으로 노출하지 않는다.

## 3. 완료된 Phase 1 제한 범위

상세 문서: `docs/PHASE_1_DATA_RELIABILITY_REPORT.md`

완료 내용:

- KIS 당일 1분봉 REST 조회 및 5분봉 합성
- 오늘 확정 구간의 5분봉 gap 탐지
- 누락 구간만 제한적으로 Backfill
- `(stock_id, timeframe, start_time)` 고유키 기반 병합
- `REALTIME`, `BACKFILL` source 표준화
- 확정 실시간 봉을 Backfill이 덮어쓰지 못하도록 보호
- 실시간 revision이 Backfill 봉을 갱신할 수 있는 구조
- 관심종목 서버 시작 자동 구독
- 관심종목 추가·삭제·그룹 삭제에 따른 구독 source 동기화
- WebSocket 구독 한도 기본 41개
- 구독 해제 요청 지원
- Redis, WebSocket, Candle gap, Backfill, Scanner 진단 확장
- Scanner Detection 시간을 candle 시작 시간이 아닌 실제 평가 시간으로 저장
- Flyway `V8__harden_stock_candle_backfill.sql`

주요 신규 파일:

- `backend/src/main/java/com/sunmo/stockplatform/kis/candle/KisMinuteCandleClient.java`
- `backend/src/main/java/com/sunmo/stockplatform/kis/candle/MinuteCandle.java`
- `backend/src/main/java/com/sunmo/stockplatform/candle/application/CandleBackfillService.java`
- `backend/src/main/java/com/sunmo/stockplatform/candle/application/CandleGapDetector.java`
- `backend/src/main/java/com/sunmo/stockplatform/candle/application/CandleQueryService.java`
- `backend/src/main/java/com/sunmo/stockplatform/candle/domain/CandleSource.java`
- `backend/src/main/java/com/sunmo/stockplatform/watchlist/application/WatchlistSubscriptionBootstrap.java`

아직 필요한 실제 환경 검증:

- 장중 KIS 당일 분봉 response 필드와 pagination 확인
- Backfill 거래대금이 KIS 누적 거래대금 기준으로 정확히 계산되는지 확인
- 휴장일 및 거래 없는 종목의 gap 반복 여부 확인
- PostgreSQL에 V8 적용 확인
- 재접속·구독 해제·41개 한도의 실제 KIS 승인 응답 확인
- `/api/v1/market/realtime/status` 운영 데이터 확인

## 4. 완료된 Phase 2A 범위

상세 문서: `docs/PHASE_2A_PERFORMANCE_RELIABILITY_REPORT.md`

완료 내용:

- pending 성과를 애플리케이션 시작 시 메모리 registry로 복구
- tick마다 PostgreSQL에서 pending Detection을 조회하던 경로 제거
- 종목별 pending registry에서 실시간 성과 계산
- dirty 성과를 기본 1초 간격으로 batch 저장
- 저장된 확정 5분봉을 이용한 재시작 구간 복구
- 탐지 이전 timestamp의 tick 무시
- 탐지 시점이 포함된 부분 candle을 복구에서 제외
- `mfe`, `mae` 명시적 저장
- 기존 `max_return`, `max_drawdown` 호환 유지
- 5/10/30/60분 실제 관측 시각 제공
- `recovery_used`로 실시간 결과와 candle 복구 결과 구분
- `MARKET_CLOSE`, `STARTUP_RECOVERY` 종료 사유 저장
- 이전 거래일 pending 및 장 종료 후 재시작한 당일 pending 정리
- 계산 버전 `performance-v2`
- v1/v2가 함께 조회되면 Analytics 버전을 `mixed`로 반환
- 진단에 pending 성과 수와 최근 batch flush 정보 추가
- Flyway `V9__harden_detection_performance.sql`

핵심 파일:

- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/DetectionPerformanceTracker.java`
- `backend/src/main/java/com/sunmo/stockplatform/analytics/domain/DetectionPerformance.java`
- `backend/src/main/java/com/sunmo/stockplatform/analytics/api/PerformanceDtos.java`
- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/ScannerAnalyticsService.java`
- `backend/src/main/java/com/sunmo/stockplatform/analytics/infrastructure/DetectionPerformanceRepository.java`

중요한 계산 규칙:

- 실시간 tick은 탐지 시각 이후 값만 사용한다.
- 재시작 복구는 확정 5분봉의 high/low/close를 사용한다.
- 탐지가 봉 중간에 발생했다면 해당 봉에는 탐지 전 가격이 섞여 있으므로 복구에서 제외한다.
- 이 정책은 일부 구간을 포기하더라도 탐지 전 가격이 MFE/MAE에 포함되는 오류를 방지한다.
- 첫 tick이 정확한 5/10/30/60분 시점에 없으면 해당 시점 이후 처음 수신한 tick을 사용하고 실제 관측 시각을 함께 저장한다.

## 5. 현재 검증 상태

마지막 실행 결과:

```text
Backend: 33 tests passed
Gradle: BUILD SUCCESSFUL
git diff --check: passed
```

실행 명령:

```powershell
Set-Location D:\sunmo\codexApp\stock-trading-platform\backend
.\gradlew.bat test --no-daemon
```

Codex 샌드박스에서 사용자 Gradle cache 쓰기가 막히면 테스트 실행에 승인 권한이 필요할 수 있다.

## 6. 현재 DB Migration

```text
V1 foundation
V2 stock
V3 watchlist
V4 trade/journal
V5 stock_candle
V6 scanner
V7 detection_performance
V8 stock candle backfill hardening
V9 detection performance hardening
```

V8과 V9는 Git에 반영됐지만 공유 DB에 적용됐다고 가정하면 안 된다. 애플리케이션 시작 전 DB 백업 및 Flyway 상태를 확인한다. 이미 적용된 migration 파일을 이후 수정하지 말고 필요한 변경은 V10 이상으로 추가한다.

## 6.1 2026-09-02 Push 내용

대상 브랜치: `main`

기능 커밋:

```text
6737bc7 feat: harden market data and performance recovery
```

포함 범위:

- Phase 1 데이터 신뢰성 개선 전체
- Phase 2A 성과 신뢰성 개선 전체
- Flyway V8·V9
- Backend 신규 및 회귀 테스트
- Phase 보고서와 개발 인수인계 문서

Push 직전 검증 결과:

```text
Backend tests: 33 passed
Gradle: BUILD SUCCESSFUL
git diff --check: passed
staged secret scan: no matches
```

다음 개발 시작점은 `Phase 2B Feature Engine`이다. 실제 장중 KIS 검증에서 Phase 1 문제가 발견되면 Phase 2B보다 해당 문제를 우선 수정한다.

## 7. 다음 작업: Phase 2B Feature Engine

Phase 2B에서는 다음만 구현하는 것이 권장된다.

1. H0STCNT0 parser 확장
   - 시가·고가·저가
   - 체결강도
   - 매수/매도 누적 체결량
   - 매수 비율
   - 거래정지 여부
   - VI 기준가
2. 공통 `MarketFeatureSnapshot`
3. Volume Ratio 및 Turnover Ratio
4. 당일 VWAP, 가격 대비 VWAP 거리, VWAP slope
5. Trade Strength 및 buy/sell volume delta
6. Day High Distance
7. Detection 시점 Feature Snapshot 저장

권장 패키지:

```text
market/feature/domain/MarketFeatureSnapshot.java
market/feature/application/MarketFeatureEngine.java
market/feature/application/IntradayFeatureState.java
market/feature/api/MarketFeatureController.java
```

DB는 자주 검색할 Feature와 단순 보존 Feature를 구분한다. Opportunity/Risk Score는 Phase 4이므로 Phase 2B에서 구현하지 않는다.

Phase 2B 필수 테스트:

- VWAP 계산
- Volume Ratio
- Turnover Ratio
- 누적 매수/매도량 delta 및 reset
- 중복·역순 tick
- Day High Distance
- 앱 재시작 상태 초기화
- Feature Snapshot 직렬화

## 8. 이후 권장 순서

### Phase 3 — Scanner 고도화

- `VOLUME_BREAKOUT`
- `TURNOVER_BREAKOUT`
- `HIGH_BREAKOUT`
- `VWAP_BREAKOUT`
- `VWAP_RECLAIM`
- 상태 기반 `PULLBACK_REBREAK`
- 구조화된 Detection Reason

Scanner 평가 코어는 이후 Backtest에서도 재사용할 수 있도록 순수 계산 로직으로 분리한다.

### Phase 4 — Opportunity/Risk Score

- Opportunity 0~100
- Risk 0~100
- 설정 가능한 가중치
- 항목별 점수 근거
- 두 점수를 합쳐 숨기지 않음

### Phase 5 — Market Radar

- 실시간 후보 목록과 filter
- Detection Detail
- 탐지/BUY/SELL/VWAP chart marker
- Scanner 설정 CRUD UI
- 실시간 운영 Dashboard

### Phase 6 — 고급 Performance Analytics

- Target/Stop First
- 시간대별 성과
- Signal Combination
- Historical Edge
- minimum sample size 및 신뢰도 표시

### Phase 7 — Market-wide Scanner

- Universe 관리
- REST 기반 Broad Scanner
- WebSocket 기반 Precision Scanner
- Market Regime
- 구독 우선순위 및 교체 정책

KIS 실시간 등록은 체결·호가 등을 합산해 기본 41건 한도이므로 전체 시장을 WebSocket으로 구독하려 하면 안 된다.

### Phase 8 — Backtesting

- 실시간 Scanner와 동일한 평가 함수 사용
- 시점 당시 알 수 있던 데이터만 사용
- Virtual Detection 및 Performance
- Scanner 설정 비교와 전략 통계

## 8.1 2026-09-03 Phase 2B·Phase 3·Phase 4 진행 상태

상세 문서:

- `docs/PHASE_2B_FEATURE_ENGINE_REPORT.md`
- `docs/PHASE_3_ADVANCED_SCANNER_REPORT.md`
- `docs/PHASE_4_OPPORTUNITY_RISK_SCORE_REPORT.md`

완료 내용:

- H0STCNT0 parser 확장 필드 매핑
- `MarketFeatureSnapshot`, `MarketFeatureEngine`, `IntradayFeatureState`
- VWAP, VWAP 거리, VWAP slope, Volume Ratio, Turnover Ratio, buy/sell delta, Day High Distance
- 최신 feature 조회 API `GET /api/v1/stocks/{stockCode}/features/latest`
- Detection 시점 feature snapshot 저장
- `VOLUME_BREAKOUT`, `TURNOVER_BREAKOUT`, `HIGH_BREAKOUT`, `VWAP_BREAKOUT`, `VWAP_RECLAIM`, `PULLBACK_REBREAK`
- 구조화된 `scanner-reason-v1` detection reason 저장
- Web Scanner 고급 타입 탭 노출
- Flyway `V10__add_market_feature_snapshot.sql`
- Flyway `V11__add_advanced_scanner_types.sql`
- `OpportunityRiskScorer` 기반 Opportunity/Risk 분리 점수
- Detection별 score breakdown JSON 및 `opportunity-risk-v1` 저장
- Web Scanner 탐지 목록의 Opportunity/Risk 표시
- Flyway `V12__add_opportunity_risk_score.sql`

검증 결과:

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 8.2 2026-09-03 Phase 5 진행 상태

상세 문서:

- `docs/PHASE_5_MARKET_RADAR_REPORT.md`

완료 내용:

- Web Scanner workspace를 `Market Radar`로 확장
- 종목명/코드, Opportunity 최소값, Risk 최대값 후보 필터
- Opportunity/Risk 기반 후보 정렬
- Detection Detail 패널
- Detection reason, score breakdown, performance 상태 표시
- Detection/VWAP/BUY/SELL marker가 있는 상세 차트
- Realtime status 기반 운영 상태 카드
- Scanner 설정 생성/수정/삭제 UI

검증 결과:

```text
Web tests: passed
Web build: passed
git diff --check: passed
```

## 8.3 2026-09-03 Phase 6 진행 상태

상세 문서:

- `docs/PHASE_6_ADVANCED_PERFORMANCE_ANALYTICS_REPORT.md`

완료 내용:

- `GET /api/v1/scanner-analytics` 확장
- Target/Stop 기준 파라미터
- minimum sample size 파라미터
- Target/Stop outcome summary
- 시간대별 성과 bucket
- scanner type + Opportunity band + Risk band 기반 Signal Combination
- Historical Edge summary
- 샘플 수 기반 confidence 표시
- Web Analytics workspace를 `Signal Edge Lab`으로 확장

검증 결과:

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 8.4 2026-09-03 Phase 7 진행 상태

상세 문서:

- `docs/PHASE_7_MARKET_WIDE_SCANNER_REPORT.md`

완료 내용:

- `GET /api/v1/market-wide/scan`
- stock master 기반 tradable universe 조회
- KIS REST quote 기반 Broad Scan
- market regime summary
- broad candidate score
- realtime subscription capacity 기반 precision eligibility
- Web Market-wide Scanner workspace
- 후보별 Precision 구독 action

검증 결과:

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 8.5 2026-09-03 Phase 8 진행 상태

상세 문서:

- `docs/PHASE_8_BACKTESTING_REPORT.md`

완료 내용:

- `GET /api/v1/backtests/run`
- stored 5-minute candle 기반 scanner replay
- `ScannerEvaluator` 재사용
- Virtual Detection 생성
- 5분/30분/60분/max return/max drawdown Virtual Performance
- 설정별 전략 통계
- candle 기반 historical feature proxy
- Web Backtesting workspace

검증 결과:

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

다음 개발 시작점은 신규 Phase보다 장중 실데이터 검증 및 안정화다. 특히 Phase 2B field mapping, candle coverage, Phase 3 detection reason, Phase 4 score distribution, Phase 7 broad scan candidate quality, Phase 8 replay 결과를 같은 날짜 데이터로 비교한다.

## 9. 다음 에이전트가 주의할 코드 위험

- `DetectionPerformanceTracker`는 detached JPA entity를 registry에 보관한다. batch `saveAll` 반환 entity를 registry에 다시 등록해 version을 갱신하며 `onTick`, `flushDirty`, `finalizeMarketClose`를 동기화한다. 이 부분을 단순화하면서 optimistic locking 안전성을 깨뜨리지 않는다.
- Candle 복구는 의도적으로 탐지 중간 봉을 제외한다. 이를 포함하려면 tick-level 영속 데이터가 먼저 필요하다.
- Backfill은 종목 차트 조회 시 실행되며 시장 전체 자동 Backfill이 아니다.
- Scanner는 아직 미확정 candle update마다 DB에서 이전 6개 봉과 활성 설정을 조회한다. 이 최적화는 Feature Engine 도입 시 함께 처리한다.
- Redis cooldown은 여전히 fail-closed다. local fallback은 아직 구현하지 않았다.
- 구독 registry는 프로세스 메모리 기반이며 관심종목으로 재구성한다. 전체 시장 universe 저장소가 아니다.
- KIS에서 제공하지 않는 값을 임의 생성하지 않는다. 체결 방향은 공식 필드 또는 명확한 계산 근거를 사용한다.
- 시간 기준은 `Asia/Seoul`, 정규장은 현재 09:00~15:30으로 구현돼 있다.

## 10. Commit 전 권장 절차

사용자가 commit 또는 push를 요청했을 때만 수행한다.

```powershell
git diff --check
git status --short
```

Phase 1과 Phase 2A 파일만 명시적으로 `git add -- <files>`로 추가한다. 다음 항목은 제외한다.

```text
.env
.codex-write-test.tmp
patch_probe.txt
backend/bin/
backend/build/
```

스테이징 후 검사:

```powershell
git diff --cached --name-status
git diff --cached --check
git diff --cached | Select-String -Pattern 'KIS_APP_KEY\s*=\s*\S+|KIS_APP_SECRET\s*=\s*\S+|ghp_|github_pat_|BEGIN (RSA|OPENSSH|PRIVATE) KEY'
```

권장 커밋 분리는 다음과 같다.

```text
feat: harden candle backfill and realtime subscriptions
feat: harden detection performance recovery
docs: add phase handoff documentation
```

단, 현재 Phase 1과 Phase 2A 변경이 일부 공통 파일에서 겹치므로 실제 diff를 확인하고 분리가 위험하면 하나의 커밋으로 묶는다.
