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
- [ ] 4단계 코드 주석 작성

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
