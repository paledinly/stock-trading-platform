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
- [ ] 3단계 md 문서 재작성
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

## 다음 할 일

3단계: README.md와 docs/를 코드와 대조해 다시 쓴다(CHANGELOG은 형식만). 장 마감 후 Backend 재시작 시에는 원래 폴더(`fix-sse-backpressure`)로 실행한다.
