# 정리·문서화 작업 진행 기록

작업 브랜치: `chore/cleanup-and-docs` (기준 `fix-sse-backpressure`, 2026-10-14). 작업 폴더는 별도 worktree `D:\Repo\_devpp\stp-cleanup` — 운영 Backend가 원래 폴더에서 실행되기 때문이다.

## 원칙

- 운영 Backend가 이 작업 폴더의 코드로 실행 중이다. 이 브랜치의 변경은 커밋만 하고, 반영(재시작)은 장 마감 후에만 한다.
- `audit/`는 과거 증거 기록이라 삭제·주석 대상에서 제외한다. `backup/`, `qa/`, `Untitled`(사용자 파일)도 건드리지 않는다.
- 삭제는 사용자 승인 항목만 한다. 주석 단계에서는 동작을 바꾸지 않는다.

## 0단계: 기준선 (2026-10-14 11:5x KST)

| 대상 | 명령 | 결과 |
| --- | --- | --- |
| Backend 빌드 | `gradlew build -x test` | 성공 |
| Backend 테스트 | `gradlew test` | 257개 통과, 실패 0, 건너뜀 0 |
| Web 테스트 | `npx vitest run` | 8개 파일, 16개 통과 |
| Web 빌드 | `npm run build` | 성공 |
| Web 린트 | `npx eslint .` | 경고·오류 없음 |
| Mobile 테스트 | `flutter test` | 1개 통과 |

## 진행 상황

- [x] 0단계 준비
- [x] 1단계 분석·정리 후보 보고 (2026-10-14 15:4x, 승인 대기)
- [x] 2단계 승인 항목 정리 (승인: A 전부, B1, B2, B4)
- [x] 3단계 md 문서 재작성 (커밋 `b82579d`)
- [x] 4단계 코드 주석 작성 (2026-10-14)

## 1단계 정리 후보 (번호는 승인 회신용)

### A. 삭제 제안 (확신 높음)
- A1 web `Phase4App`/`Phase6App`/`Phase7App`/`Phase8App` 래퍼 함수와 옛 플로팅 버튼 체인. 테스트 4개는 실제 페이지 컴포넌트 기준으로 다시 작성
- A2 web 의존성 `zustand`(import 0)
- A3 web 의존성 `react-router-dom`(BrowserRouter만 사용, 라우트 없음) + main.tsx의 BrowserRouter 래퍼
- A4 web CSS 미사용 규칙: styles.css `.shell/.card/.eyebrow`, 옛 플로팅 버튼 위치 지정 규칙
- A5 backend 미사용 메서드 5개: OvernightBacktestService `deduplicateByStock`·`qualifies`, KisTokenManager `invalidate`, OvernightPerformance `markDataMissing`, DailyCandleBackfillService `prepareCodes`
- A6 backend 의존성 `spring-boot-starter-websocket`(Spring WebSocket 미사용, KIS는 java.net.http, 화면은 SSE)
- A7 `.env.example` `KIS_ACCOUNT_NUMBER`, `KIS_PRODUCT_CODE`(참조 0)
- A8 `.gitignore` 없는 경로: `web/vite.config.js`, `web/vite.config.d.ts`, mobile/.gitignore와 중복 항목
- A9 빈 폴더 `infra/`(Git 미추적)

### B. 확인 필요 (업무 판단)
- B1 미호출 엔드포인트: market-wide/snapshots(+BroadSnapshotQueryService), market-wide/precision, scanners/{kind}(scanner-detections?type= 중복), stocks/{code}/features/latest, stocks/{code}
- B2 `ClosingRecommendationService.latestEvaluation`(테스트만 사용), Scorer 2인자 오버로드(테스트만 사용)
- B3 미사용 DB 테이블: intraday_input, intraday_recommendation, platform_schema_version(삭제는 새 마이그레이션 필요)
- B4 `backend/.intraday-checkpoints/latest.json.gz`(15.5MB)와 `.gitignore` 항목, 로컬 `.env`의 `INTRADAY_*` 8개
- B5 `web/vite.config.ts` 하드코딩된 ngrok 호스트
- B6 mobile 앱 전체(2026-09-28 이후 변경 없음, 8단계 기능까지만), 데스크톱·웹 플랫폼 폴더, Android INTERNET 권한 없음
- B7 MarketSessionPolicy와 ClosingTradingCalendar 거래일 판단 중복(동작 변경이라 정리 범위 밖)
- B8 application.yml 키 25개가 .env.example에 없음(문서 단계에서 보완 가능)
- B9 ScannerPage가 SSE 연결을 따로 엶(RealtimeBridge와 중복)
- B10 로컬 산출물: backend/hs_err_pid*.log 8개, replay_pid*.log 2개, backend/bin/(Git 미추적)

## 2단계 결과 (2026-10-14)

| 커밋 | 내용 |
| --- | --- |
| `e3b3a21` | A1~A4: Web 옛 래퍼 함수 4개 삭제(테스트는 실제 페이지 컴포넌트 기준으로 변경), `zustand`·`react-router-dom` 제거, 미사용 CSS 정리 |
| `7948c39` | A5~A8, B1, B2: Backend 미사용 메서드 5개·테스트 전용 메서드 2개·미호출 API 5개(+BroadSnapshotQueryService, SnapshotResponse)·Spring WebSocket 의존성, `.env.example` 키 2개, `.gitignore` 항목 정리 |
| (Git 밖) | A9 빈 `infra/` 삭제. B4 `backend/.intraday-checkpoints/`(15.5MB) 삭제, 로컬 `.env`의 장중 설정 블록 삭제(삭제 전 사본 `backup/.env.before-intraday-cleanup`) |

검증: Backend 빌드 성공·테스트 258개 통과(기준선 257 + 기준 브랜치에서 추가된 1개), Web 테스트 16개·빌드·린트 통과. Mobile은 변경 없음.

보류(미승인): B3 DB 테이블, B5 ngrok 호스트, B6 mobile, B7 거래일 판단 중복, B8 설정 예시 보완, B9 SSE 중복 연결, B10 로컬 산출물.

## 3단계 결과 (2026-10-14)

커밋 `b82579d`. 코드·설정(`application.yml`, `.env.example`, Controller mapping, migration, `package.json`, `build.gradle`, scripts)과 대조해 다시 썼다. `audit/`와 ADR 본문은 건드리지 않았다. 상대 링크·앵커는 스크립트로 전부 확인했다(깨진 링크 0).

| 파일 | 주요 수정 |
| --- | --- |
| `README.md` | 순서 재구성(목적 → 폴더 → 실행 → 기능 → 환경변수), 용어 첫 등장 시 설명, 주요 환경변수 표(코드 기본값 포함) |
| `AGENTS.md` | 목표 전략 시각 15:00 → 15:15, 문서 소유 목록에 FEATURES·CHANGELOG·연구 문서 추가 |
| `docs/API.md` | Controller별 전체 엔드포인트 표(메서드·경로·용도·파라미터), [운영]/[연구] 표시, SSE 이벤트 이름, generate 규칙(400/409/503), 삭제된 API 기록 |
| `docs/ARCHITECTURE.md` | 실제 패키지 13개 표, Web 메뉴↔컴포넌트 표, 서버 측 WebSocket 없음(SSE만) |
| `docs/DATABASE.md` | V1~V36 전체 테이블 표, 사용하지 않는 테이블(`platform_schema_version` 포함) 절 |
| `docs/OPERATIONS.md` | "마감 추천" 절의 15:00·유예 10초 → 15:15·0초, 시작 순서에 스캔·정밀 스위치, 장중 기능 절을 과거 기록으로 축소 |
| `docs/MARKET_DATA.md` | 재구성, 장중 입력 원장 절 제거, 관측 분봉 보충·구독 출처·정밀 교체 조건 추가 |
| `docs/TRADING_STRATEGY.md` | Precision(탐지)만 SELECTED 가능·Broad는 WATCH까지(코드 기준), ADR-010 표본, OVERNIGHT 유지 15:31 |
| `docs/BACKTESTING.md` | 제공 기능 표, cutoff 15:00 → 15:15, 판정 스크립트 링크 |
| `docs/FEATURES.md` | 화면 이름을 실제 컴포넌트로, 연구 수집 4종 추가, "이 PC" 값은 2026-10-13 기록으로 표시 |
| `docs/DEVELOPMENT.md` | `npm run lint`, Testcontainers 테스트 건너뜀 조건, Vite 프록시, `V36` |
| `docs/ROADMAP.md` | 2단계 보류 항목(B3·B5·B6·B7·B8·B9) 추가, ADR-009/010 반영 |
| `docs/CLOSING_TRAJECTORY.md`, `INVESTOR_FLOW.md`, `MICROSTRUCTURE.md` | 구현 당시 기록임을 표시, 15:00 기준은 과거 v8 기준이라고 명시 |
| `docs/CHANGELOG.md` | 목록 사이 빈 줄 제거(형식만) |
| `mobile/README.md` | 화면·API 표, `API_BASE_URL` 설정, 릴리스 manifest의 INTERNET 권한 누락 |

남긴 `TODO: 확인 필요`:

- `docs/BACKTESTING.md`: v9.1 공식 완료 표본 수(운영 DB 조회 필요)
- `docs/DATABASE.md`: 운영 DB가 로컬인지 Supabase인지
- `docs/DEVELOPMENT.md`: `vite.config.ts`의 ngrok 허용 호스트를 지금도 쓰는지
- `docs/FEATURES.md`: "운영 PC" 열의 현재 값(2026-10-13 기록)

해결됨: ADR-010의 정밀 교체 고정 시각 14:50과 코드 기본값(14:35)이 달랐던 점은 사용자 결정으로 기본값을 14:50으로 통일했다(운영 브랜치 `4e810c8`).

## 다음 할 일

4단계: 코드 주석 작성. 장 마감 후 Backend 재시작 시에는 원래 폴더(`fix-sse-backpressure`)로 실행한다.

## 4단계 결과 (2026-10-14)

- 대상: Java(main·test), web TS/TSX와 설정 2개, mobile Dart, scripts PS1 — 342개 파일, 주석만 약 10,500줄 추가.
- 검증: 주석·공백을 지운 코드 비교 검사 341개 파일 problems=0(eslint.config.js는 diff로 별도 확인), PowerShell 5.1 파싱 오류 0·코드 토큰 수 동일, Backend 빌드·테스트 258개 통과, Web 테스트 16개·빌드·린트 통과, Flutter 테스트 1개·analyze 통과.
- 커밋: e584eb4, 8dfd090, a123ddd, 799fbbb, 886446b, ad24ac9, c9bc47d.

### ⚠️ 확인 필요 표시 83곳 (코드는 고치지 않음)

- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/DetectionPerformanceTracker.java:152` 요일만 보고 실행돼서 휴장일·단축 장을 고려하지 않아요(ClosingTradingCalendar 정책과 다를 수 있어요).
- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/DetectionPerformanceTracker.java:176` 현재가의 수신 시각을 확인하지 않아서, 오늘 체결이 없던 종목이면 오래된 가격이 마감 가격으로 들어갈 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/DetectionPerformanceTracker.java:231` 마지막 봉이 확정봉인지 확인하지 않아요(recoverFromStoredCandles는 확정봉만 써요).
- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/DetectionPerformanceTracker.java:281` 15:30으로 고정돼 있어서 개장 지연·단축 장 같은 특별한 날은 반영하지 않아요.
- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/ScannerAnalyticsService.java:121` 상태를 거르지 않아서 아직 추적 중(PENDING)이거나 DATA_MISSING인 기록도 평균·승률 계산에 섞여요.
- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/ScannerAnalyticsService.java:158` 이름은 "먼저 닿음(first)"이지만 실제로는 최고·최저 수익률만 보고 순서는 알 수 없어요.
- `backend/src/main/java/com/sunmo/stockplatform/analytics/application/ScannerAnalyticsService.java:234` 신뢰도를 글자 순으로 정렬해서 HIGH → LOW → MEDIUM 순서가 돼요(의도는 HIGH → MEDIUM → LOW로 보여요).
- `backend/src/main/java/com/sunmo/stockplatform/analytics/domain/DetectionPerformance.java:226` base(감지 가격)가 0이면 나눗셈 예외가 나요. 감지 가격이 항상 0보다 큰지 확인이 필요해요.
- `backend/src/main/java/com/sunmo/stockplatform/backtest/application/BacktestService.java:160` turnoverRatio에 "이 봉 거래대금 ÷ 당일 누적 거래대금 × 100(%)"을 넣는데,
- `backend/src/main/java/com/sunmo/stockplatform/backtest/application/BacktestService.java:162` viStandardPrice(VI 기준가) 자리에 당일 첫 봉 시가를 넣어요. 실제 VI 기준가가 아니에요.
- `backend/src/main/java/com/sunmo/stockplatform/backtest/application/BacktestService.java:205` 시간이 아니라 "다음 확정봉 개수"로 세서, 빈 봉이 있거나 장 마감을 넘기면(다음 날 봉) 실제 30·60분과 달라질 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/backtest/application/BacktestService.java:251` "ID|이름|유형"을 '|'로 이어 붙였다가 다시 나누므로, 설정 이름에 '|'가 들어가면 요약이 깨지거나 오류가 날 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/candle/application/CandleGapDetector.java:21` 15:20 칸은 종가 단일가 접수 시간이라 정상적으로 체결·봉이 없는데도 빈 칸으로 잡혀요.
- `backend/src/main/java/com/sunmo/stockplatform/candle/application/CandleQueryService.java:44` 거래일인지 확인하지 않아 휴장일·주말에도 09:05 이후 오늘 구간을 조회하면 빈 칸 보충(KIS 호출)을 시도해요.
- `backend/src/main/java/com/sunmo/stockplatform/candle/application/FiveMinuteCandleAggregator.java:143` revision을 올리는 코드가 없어 스냅샷의 revision은 항상 0이에요. 의도한 것인지 확인이 필요해요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/api/ClosingRecommendationController.java:197` 계산기를 주입받았지만 실제 계산을 부르지 않고 항상 unavailable()만 돌려줘요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/BacktestIntegrityService.java:27` DECISION_TIME 15:00, ENTRY_TIME 15:05는 v8(15:00 평가) 기준 하드코딩이에요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/BacktestIntegrityService.java:79` 안내 문구의 "15:05 확정 5분봉"은 v8 진입 모델 기준이에요. 기본 15:15 설정에서는
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingPrecisionEvaluator.java:212` 앞의 수신 시각 검사와 여기서 RECEIVED_AFTER_EVALUATION이 같은 목록에 두 번 들어갈 수 있어요. 판정에는 영향이 없어 보여요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScheduler.java:129` 자동화 limit은 1~30(기본 10)이지만 최종 추천은 LIMITED_MODE_MAX_CANDIDATES=5로 다시 잘려요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingRecommendationScorer.java:204` 숫자가 아닌 문자열이면 NumberFormatException이 나고, featureValues는 JsonProcessingException만 잡아 점수 계산 전체가 실패할 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/ClosingStrategyAnalyticsService.java:120` 현재 전략 라벨이 "RULE_BASED_V9_1515"로 고정돼 있어 STRATEGY_VERSION(v9_1)과 이름이 어긋나요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OfficialTradingDays.java:99` 읽기에 실패해도 아래에서 loaded=true가 되어, 재시작 전까지 DB에 있던 기록을 다시 읽지 않아요(그동안은 새로 받은 값과 정적 정책만 쓰여요).
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightBacktestService.java:39` CUTOFF(14:30)는 이 클래스에서 쓰이지 않는 것으로 보여요(평가 시작은 properties.evaluationStart()를 써요).
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightBacktestService.java:380` NextSessionCoverage 완전성 검사와 가격 정합 검사를 하지 않아요. 봉이 일부만 있어도 COMPLETED가 되고,
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightPerformanceService.java:65` 날짜 없이 부르면(API) 기본값이 "달력상 어제"라, 월요일·휴장일 다음날에는 직전 거래일이 아니라 추천이 없는 날을 가리켜요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightPositionDecisionService.java:74` 다른 서비스와 달리 ClosingTradingCalendar가 아니라 시스템 시계(LocalDate.now/Instant.now)를 써요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightPositionDecisionService.java:130` 달력 날짜로만 비교해서, 추천일 다음날이 주말·휴장일이어도 지난 시세로 판단이 만들어질 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightPositionDecisionService.java:171` 매수 기준가가 없거나 0이면 pct가 0을 돌려줘서, 자료 없음이 "수익률 0%"로 보이고 판단까지 내려져요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/domain/ClosingRecommendation.java:54` 매핑은 optional = false인데 BROAD 생성자는 이 값을 비워 두고, DB(V18)도 NULL을 허용해요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/infrastructure/OvernightPerformanceRepository.java:43` 추천 조회(ClosingRecommendationRepository.findByRecommendationDateOrderByRankAsc)는 FORWARD 실행을 우선하는데,
- `backend/src/main/java/com/sunmo/stockplatform/closing/infrastructure/OvernightPositionDecisionRepository.java:37` 추천 조회는 FORWARD 실행을 우선하지만 여기는 max(id) 실행을 써서 서로 다른 실행을 볼 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/closing/trajectory/TrajectoryService.java:101` 한 종목의 스냅샷 계산이 예외를 던지면 이 묶음 전체가 롤백돼요. 같은 자료로 매번 실패하면
- `backend/src/main/java/com/sunmo/stockplatform/kis/candle/KisMinuteCandleClient.java:86` 응답에 영업일자(stck_bsop_date)가 없으면 '서버의 오늘 날짜'로 대신 채워요.
- `backend/src/main/java/com/sunmo/stockplatform/kis/candle/KisMinuteCandleClient.java:128` 체결량이 비어 있으면 결측 대신 0으로 바꿔요. "결측을 0으로 조용히 바꾸지 않는다"는 원칙과 어긋날 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/kis/quote/KisQuoteMapper.java:51` 현재가나 시고저가가 비어 있어도 결측 대신 0으로 바꿔요. 0원 시세가 정상값처럼 쓰일 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/kis/ranking/KisMarketRankingClient.java:106` publishEvent는 기본적으로 같은 스레드에서 리스너를 바로 실행해요. 리스너(예: TrajectoryContextCollector)가
- `backend/src/main/java/com/sunmo/stockplatform/kis/websocket/KisRealtimeClient.java:230` 해지(subscribe=false) 요청도 여기서 diagnostics.queued(code)를 호출해요.
- `backend/src/main/java/com/sunmo/stockplatform/market/application/MarketDataService.java:114` 이 메서드는 스케줄러 스레드에서 handle(저장·Scanner 평가·SSE)을 부르므로,
- `backend/src/main/java/com/sunmo/stockplatform/market/config/BroadEnrichmentScheduling.java:21` 스레드가 1개라서 이름 없는 @Scheduled 작업(봉 확정 closeExpired 1초 주기, 14:25/14:35 일봉 보완 등)이 모두 한 줄로 실행돼요.
- `backend/src/main/java/com/sunmo/stockplatform/market/feature/application/IntradayFeatureState.java:147` 5분봉 집계기(FiveMinuteCandleAggregator)와 달리 15:30 종가 단일가 체결을 15:25 칸에 합치지 않고 15:30 칸으로 나눠요.
- `backend/src/main/java/com/sunmo/stockplatform/marketwide/application/MarketCoverageService.java:197` 장 마감을 15:30으로 고정했어요. 단축 장·마감 지연일은 반영되지 않아요.
- `backend/src/main/java/com/sunmo/stockplatform/marketwide/application/MarketSessionPolicy.java:41` 설정에 적힌 정적 휴장일 목록만 보고 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/marketwide/application/MarketWideScanCoordinator.java:90` 이 저장은 try/finally 밖에 있어서, 여기서 예외가 나면 running이 true로 남아
- `backend/src/main/java/com/sunmo/stockplatform/marketwide/application/PrecisionSubscriptionAllocator.java:124` 교체 대기 중인 구독의 확인 응답(ack)이 끝내 오지 않으면 pendingReplacements가 비지 않아,
- `backend/src/main/java/com/sunmo/stockplatform/marketwide/domain/StructuredProductFilter.java:21` 단순 "포함" 검사라서 일반 회사 이름에 "선물" 같은 글자가 들어가도 걸러질 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/scanner/application/ScannerEngine.java:59` 만료된 항목을 지우는 곳이 없어 설정×종목 수만큼 계속 쌓여요(메모리 증가 가능).
- `backend/src/main/java/com/sunmo/stockplatform/scanner/application/ScannerEngine.java:158` 미확정 봉 갱신(candle.5m.updated)이 한 5분 구간에 여러 번 오면 확정봉 평가 없이도 2회가 쌓여
- `backend/src/main/java/com/sunmo/stockplatform/scanner/application/ScannerEngine.java:236` Redis가 없으면 redis가 null이라 NullPointerException이 나고 여기서 조용히 무시돼요.
- `backend/src/main/java/com/sunmo/stockplatform/scanner/application/ScannerEvaluator.java:84` 저장 컬럼 이름은 five_minute_change_rate(5분 변화율)지만 실제로는 약 30분 구간 변화율이에요.
- `backend/src/main/java/com/sunmo/stockplatform/scanner/application/ScannerQueryService.java:83` scanner_detection이 이 설정을 ON DELETE 없이 참조해서, 감지 기록이 있는 설정은 DB 제약 오류로 지워지지 않을 수 있어요.
- `backend/src/main/java/com/sunmo/stockplatform/scanner/domain/ScannerDetection.java:49` 이름은 received_at(수신시각)이지만 원천 틱 수신시각이 아니라 감지 기록 생성 시각이에요.
- `backend/src/main/java/com/sunmo/stockplatform/scanner/score/OpportunityRiskScorer.java:44` feature가 없으면 체결강도를 0으로 봐서 weakTradeStrength가 최대 20점이 돼요. 특징값 누락이 위험 점수를 올려요.
- `backend/src/main/java/com/sunmo/stockplatform/scanner/score/OpportunityRiskScorer.java:64` feature가 없으면 거리를 0으로 봐서 만점 10점을 줘요. 데이터가 없을 때 기회 점수가 오히려 올라가요.
- `backend/src/main/java/com/sunmo/stockplatform/stock/application/StockMasterSyncService.java:43` 마스터 파일에서 사라진(상장폐지 등) 종목을 비활성(active=false)으로 바꾸는 처리가 없어요.
- `backend/src/main/java/com/sunmo/stockplatform/stock/application/StockMasterSyncService.java:70` 같은 클래스 안에서 synchronize()를 직접 부르기 때문에(self-invocation)
- `backend/src/main/java/com/sunmo/stockplatform/trade/application/PortfolioCalculator.java:60` 매수 건은 나중에 전량 매도된 뒤에도 '지금'까지로 계산돼서, 이미 청산한 매수 기록의 보유일이 계속 늘어나는 것으로 보여요.
- `backend/src/main/java/com/sunmo/stockplatform/watchlist/application/WatchlistService.java:176` 구독 등록이 DB 커밋보다 먼저 일어나요. 커밋이 실패하면(예: version 충돌)
- `backend/src/test/java/com/sunmo/stockplatform/closing/ClosingRecommendationServiceTest.java:454` <p> 기본 생성자는 입력 마감 15:00, 유예 10초를 써요. 현재 운영 전략(v9_1, 15:15 마감, 유예 0초)과 달라서
- `backend/src/test/java/com/sunmo/stockplatform/closing/ClosingRunMigrationTest.java:83` 이 재시도 로직은 ClosingRecommendationService의 잠금 SQL을 테스트 안에 복사한 것이라,
- `backend/src/test/java/com/sunmo/stockplatform/closing/ClosingStrategyAnalyticsServiceTest.java:122` 현재 전략 버전은 closing-recommend-v9_1-1515인데 승격 관문 표시는 RULE_BASED_V9_1515를 기대해요. 의도한 계열 이름인지 갱신이 빠진 것인지 확인이 필요해 보여요.
- `backend/src/test/java/com/sunmo/stockplatform/closing/OvernightPerformanceServiceTest.java:235` 첫 조회는 다음 거래일 봉, 두 번째 조회는 진입봉을 돌려줘요. 서비스의 조회 순서가 바뀌면 의미가 뒤바뀌는 순서 의존 스텁이에요.
- `backend/src/test/java/com/sunmo/stockplatform/closing/application/ClosingPaperEntryServiceTest.java:95` 메서드 이름에 "OfflineRestart" 가 있지만, 재시작(오프라인 후 재기동) 상황은 이 테스트에서 따로 다루지 않는 것으로 보여요.
- `backend/src/test/java/com/sunmo/stockplatform/kis/websocket/KisRealtimeClientTest.java:158` client.shutdown()이 두 번 호출돼요. 중복 호출이 무해하다면 문제는 없지만 의도한 것인지 확인이 필요해 보여요.
- `backend/src/test/java/com/sunmo/stockplatform/market/MarketEventGatewayTest.java:67` 실제 시간(2초, 5초 대기)에 기대는 검증이라 아주 느린 CI 환경에서는 가끔 실패할 수 있어 보여요.
- `backend/src/test/java/com/sunmo/stockplatform/marketwide/PrecisionSubscriptionAllocatorTest.java:4` 이 파일은 marketwide/ 폴더에 있지만 package는 marketwide.application이에요. 컴파일은 되지만 폴더와 패키지가 달라 찾기 헷갈릴 수 있어요.
- `mobile/lib/core/api_client.dart:58` 빈 줄(이벤트 경계)에서 id·type을 초기화하지 않아, 다음 이벤트에 event: 줄이 없으면 이전 type이 남는 것으로 보여요.
- `mobile/lib/core/api_client.dart:64` data가 JSON 객체가 아니면 예외가 나서 연결 전체가 끊기고 2초 뒤 재연결돼요. 한 줄 오류로 스트림이 재시작되는 셈이에요.
- `mobile/lib/core/models.dart:6` /  값이 없을 때도 0이 되어, '0원'과 '데이터 없음'을 화면에서 구분할 수 없어요.
- `mobile/lib/features/screens.dart:117` '연결 LIVE'는 실제 SSE 연결 상태와 상관없이 항상 고정 문구로 보여요.
- `mobile/lib/features/screens.dart:178` 종목 이름이 빈 문자열이면 name[0]에서 오류가 나요.
- `mobile/lib/features/screens.dart:396` 가격·수량이 숫자가 아니면 num.parse/int.parse에서 예외가 나고, 저장 실패(네트워크 오류)도 따로 처리하지 않아요.
- `scripts/run-backend.ps1:81` runner.log 는 이 패턴에 걸리지 않아 계속 커지는 것으로 보여요.
- `web/src/ClosingRecommendationApp.tsx:387` `...init`이 headers 뒤에 펼쳐져서, init에 headers가 있으면 위에서 합친 Content-Type이 덮어써져요(현재 POST 호출은 본문이 없어 영향은 작아 보여요).
- `web/src/ClosingRecommendationApp.tsx:1360` 탐지 시각(detectedAt)은 timeZone 없이 표시해서 브라우저 시간대를 따라요. 다른 곳은 Asia/Seoul로 고정해요.
- `web/src/Phase4App.tsx:305` 삭제 버튼을 누르면 확인 질문 없이 바로 지워지는 것으로 보여요.
- `web/src/Phase6App.tsx:62` ...init을 headers 뒤에 펼쳐서, init에 headers를 넘기면 Content-Type 합친 값이 덮어써지는 것으로 보여요(지금 호출부는 headers를 안 넘겨요).
- `web/src/Phase6App.tsx:252` main.tsx의 RealtimeBridge도 같은 /api/v1/stream을 열고 있어서, 이 화면에 있는 동안 SSE 연결이 2개가 되는 것으로 보여요.
- `web/src/Phase7App.tsx:89` toISOString은 UTC 기준이라 한국 시간 오전 9시 전에는 하루 전 날짜가 나오는 것으로 보여요.
- `web/src/Phase7App.tsx:155` 사용 안내의 "실제로 우위가 있는 신호만 골라냅니다" 문구는 표본·검증 상태와 상관없이 우위를 단정하는 것으로 보여요.
- `web/src/Phase8App.tsx:197` 화면을 열 때마다 기본 조건 스캔이 바로 실행되는 것으로 보여요. 서버가 매번 KIS 조회를 하면 유량 제한에 부담이 될 수 있어요.
- `web/src/Phase9App.tsx:40` toISOString은 UTC 기준이라 한국 시간 오전 9시 전에는 하루 전 날짜가 나오는 것으로 보여요.
- `web/vite.config.ts:14` 특정 ngrok(외부 접속 터널) 주소가 하드코딩돼 있어요. 터널 주소가 바뀌면 접속이 막히고, 개인 주소가 저장소에 남는 것으로 보여요.

## 개선 Batch 1 결과 (2026-10-14, 안정성·측정)

- 브랜치 `fix/batch1-market`(시세·Scanner·5분봉 8커밋), `fix/batch1-closing`(종가·성과 6커밋)을 이 브랜치에 병합했어요.
- 검증: backend 290 tests 통과, web test 16·build 통과, flutter test 1 통과.
- 기본 스케줄러(`taskScheduler`)는 스레드 1개를 유지하고, 봉 확정(`closeExpired`)만 전용 `candleCloseScheduler`로 분리했어요. 종가 작업들은 예전처럼 차례로 실행돼요.
- 요청 범위 밖 수정 1건: `PrecisionSubscriptionAllocator.trimExcess`가 응답 대기 중인 교체 구독을 초과분으로 세지 않도록 고쳤어요(무관한 정밀 구독이 잘못 해제되던 문제). 정밀 구독 배분이 달라질 수 있어요.
- 남은 일: backtest 기본 종료일(`LocalDate.now`)·15:31 cron 미변경, Trajectory 격리는 Postgres 트랜잭션이 중단되면 효과가 제한적이에요. 다음 배치는 C(거래량 0→null, ADR-010 보완), E/G/F, mobile(B6 결정 후), D(전략, v9.2는 판단 기간 이후).
