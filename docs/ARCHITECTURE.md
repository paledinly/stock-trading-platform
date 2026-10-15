# Architecture

## 목적

KIS 국내주식 데이터를 수집해 5분봉과 Feature를 만들고, 실시간 탐지 → 시장 후보 → 15:15 마감 평가 → 가상 진입 → 다음 거래일 성과를 잇는 연구용 시스템이다. 주문 실행은 범위 밖이다.

## 구성요소

- **Backend** (`backend/`): Java 21, Spring Boot 3.5, JPA, Flyway, Resilience4j(호출 한도·회로 차단). 수집, 집계, 평가, 저장, 스케줄, REST API와 SSE 스트림(`GET /api/v1/stream`)을 담당한다. 서버 쪽 WebSocket 엔드포인트는 없다. KIS WebSocket은 Backend가 클라이언트로 접속한다(`java.net.http`).
- **Web** (`web/`): React 19, TypeScript, Vite, TanStack Query, lightweight-charts. 라우터 라이브러리 없이 `AppShell`이 왼쪽 메뉴와 해시 주소(`#/closing` 등, `routes.ts`)로 화면을 바꾼다. 색은 `theme.css`의 토큰만 쓰며 밝은/어두운 화면을 지원한다.
- **Mobile** (`mobile/`): Flutter 보조 클라이언트. Web보다 기능 범위가 작다.
- **PostgreSQL**: 종목, 봉, 탐지, 추천, 성과, 거래 기록의 시스템 오브 레코드(공식 원장)다.
- **Redis와 프로세스 메모리**: 최신 시세, 탐지 쿨다운, 실시간 상태용이며 영구 이력의 근거가 아니다. Redis가 없거나 실패하면 탐지 쿨다운은 프로세스 내부 값으로 대체된다.
- **KIS**: REST(현재가, 분봉, 일봉, 순위, 휴장일, 호가, 수급, 지수)와 WebSocket 체결(H0STCNT0) 공급자.
- **DART**: 공시 목록(선택, `DART_API_KEY`가 있을 때만).

## Backend 패키지

`com.sunmo.stockplatform` 아래:

| 패키지 | 역할 |
| --- | --- |
| `kis` | KIS 인증 토큰, REST 클라이언트(현재가·분봉·일봉·순위·휴장일), 종목 마스터 파일, WebSocket 클라이언트와 체결 처리 큐 |
| `stock` | 종목 마스터와 검색 |
| `watchlist` | 관심종목 그룹·항목, 실시간 고정 |
| `quote` | 현재가 조회 |
| `market` | 실시간 구독 레지스트리, 실시간 진단, SSE 게이트웨이, 시장 Feature(`market/feature`) |
| `candle` | 5분봉 집계·확정, 분봉/일봉 보충, 수정주가 일봉 수집 |
| `scanner` | Scanner 설정과 탐지, 점수 |
| `analytics` | 탐지 이후 성과 추적과 집계 |
| `backtest` | 저장된 5분봉으로 Scanner 재생 |
| `marketwide` | 시장 전체 스캔(Broad 스냅샷), 상세시세 보강 큐, 정밀 구독 배정 |
| `closing` | 거래일 달력, 15:15 마감 평가, 가상 진입, 다음 거래일 성과, 후보 관측, 일별 완결성, 연구 수집(`closing/trajectory`, 상한가 직전, 후보 Context, DART) |
| `trade` | 수동 거래 원장과 투자 노트 |
| `common` | 공통 오류 처리 |

## Web 화면

| 묶음 | 메뉴 (주소) | 컴포넌트(파일) |
| --- | --- | --- |
| 운영 | 대시보드 (`#/dashboard`) | `Phase3App` (`Phase3App.tsx`) |
| 운영 | 마감 추천 (`#/closing`) | `ClosingRecommendationPage` (`ClosingRecommendationApp.tsx`) |
| 탐색 | 시장 스캐너 (`#/wide`) | `MarketWidePage` (`Phase8App.tsx`) |
| 탐색 | 실시간 레이더 (`#/scanner`) | `ScannerPage` (`Phase6App.tsx`) |
| 검증 | 성과 분석 (`#/analytics`) | `AnalyticsPage` (`Phase7App.tsx`) |
| 검증 | 백테스트 (`#/backtest`) | `BacktestPage` (`BacktestPage.tsx`) |
| 기록 | 투자기록 (`#/journal`) | `JournalPage` (`Phase4App.tsx`) |

`RealtimeBridge`가 SSE를 받아 TanStack Query 캐시를 갱신한다. 파일 이름의 `PhaseN`은 과거 개발 단계 이름이 남은 것이다.

## 데이터 흐름

1. KIS 체결을 파싱해 원천 거래일·발생시각과 수신시각을 분리한다.
2. 종목별 체결을 정규장 5분 bucket으로 집계하고 확정봉을 저장한다.
3. 누적 시세, 확정봉, 일봉으로 시장 Feature를 계산하고 Scanner가 탐지를 저장한다.
4. 시장 전체 스캔은 Broad 후보를 만들고, 상위 후보를 정밀(Precision) 실시간 구독에 배정한다.
5. 거래일 15:15 이후 첫 poll에서 마감 추천(v9.1)이 입력 마감 정책을 만족한 후보를 평가한다.
6. 평가 완료 후 가상 진입(`closing_paper_entry`)과 다음 거래일 관측을 따로 저장해 비용 포함 성과를 계산한다.

## 시간 경계

- 거래 시간대: Asia/Seoul
- 정규장: 09:00–15:30
- 정상 5분봉: 09:00~15:25 시작 bucket. 15:30 종가 단일가 체결은 15:25 봉에 들어가고, 15:20 봉은 정상적으로 체결이 없다.
- 평가 기준·입력 마감 기본값: 15:15까지 실제 수신·확정된 자료(유예 0초)
- 가상 진입 마감 기본값: 15:20 미만

가격 발생시각, 서버 수신시각, 봉 확정시각, 평가 완료시각, 가상 체결시각은 서로 다른 의미다.

## 신뢰성 원칙

- 확정봉은 늦은 부분 틱으로 덮어쓰지 않는다.
- 결측 또는 파싱 실패를 정상값 0으로 조용히 변환하지 않는다.
- 과거 재생은 당시 사용 가능했던 시각 정보가 확인되는 데이터만 공식 비교에 사용한다.
- 거래 불가·핵심 결측은 높은 점수로 상쇄하지 않는다.
- 전략 점수와 확률, 코드 수정과 수익성 검증을 구분한다.

## 과거 기록: 제거된 장중 추천 모듈

독립 장중 추천 모듈(`intraday` 패키지, `/api/v1/intraday/*`, Web 장중 추천 화면)은 2026-10-13 검증 실패 후 제거했다([진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md), [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). 남은 `intraday_input`·`intraday_recommendation` 테이블은 어떤 코드도 읽거나 쓰지 않는다([DATABASE](DATABASE.md)). `closing` 패키지의 `IntradayMovingAverageService`는 마감 전략의 장중 이동평균 Feature이며 이 모듈과 무관하다.

## 관련 문서

- [시장 데이터](MARKET_DATA.md)
- [데이터베이스](DATABASE.md)
- [API](API.md)
- [거래 전략](TRADING_STRATEGY.md)
- [백테스트](BACKTESTING.md)
