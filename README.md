# Stock Trading Platform

한국투자증권(KIS) Open API로 국내주식 시세를 모아, 장 마감 직전(15:15)에 "오늘 사서 다음 거래일에 파는" 후보를 평가하고 그 결과를 다음 거래일까지 추적하는 **연구용** 프로그램입니다.
실제 주문은 하지 않습니다. 매수·매도는 화면에 직접 남기는 수동 기록이거나, 프로그램 안에서만 계산하는 가상 체결입니다.
추천 점수는 상승 확률이 아니며, 충분한 Forward(실제 시간 순서대로 쌓은) 표본이 모이기 전까지 수익성은 검증되지 않은 상태입니다.

## 폴더 구조

| 폴더·파일 | 역할 |
| --- | --- |
| `backend/` | 서버. Java 21 + Spring Boot(자바 웹 서버 프레임워크). 시세 수집, 5분봉 생성, 탐지, 마감 평가, 성과 계산, REST API와 SSE(서버가 브라우저로 이벤트를 계속 밀어 보내는 방식)를 담당합니다. |
| `backend/src/main/resources/db/migration/` | Flyway(DB 구조 변경 이력 관리 도구) 마이그레이션 SQL `V1`~`V36` |
| `web/` | 주 화면. React 19 + TypeScript + Vite(개발 서버·빌드 도구), TanStack Query(서버 데이터 조회·캐시 라이브러리) |
| `mobile/` | Flutter(모바일 앱 프레임워크) 보조 앱. 관심종목·탐지·투자기록 정도만 다룹니다. [mobile/README.md](mobile/README.md) |
| `scripts/` | Windows에서 Backend를 상시 실행·자동 재시작하는 PowerShell 스크립트 |
| `docs/` | 현재 코드 기준의 설명 문서 |
| `audit/` | 특정 날짜의 분석·증거 기록. 현재 동작 설명서가 아닙니다. |
| `docker-compose.yml` | 로컬 PostgreSQL 17, Redis 8 실행 설정 |
| `.env.example` | 환경변수 예시. 복사해서 `.env`로 씁니다. |

## 실행 방법

필요한 것: Java 21, Node.js/npm, Docker(Compose 포함). Mobile을 고칠 때만 Flutter SDK가 필요합니다. 아래 명령은 Windows PowerShell 기준입니다.

### 1. 설정 파일과 DB

프로젝트 루트에서 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up -d
```

`.env`에 KIS 앱 키(`KIS_APP_KEY`, `KIS_APP_SECRET`)를 넣습니다. `.env`는 Git에 올리지 않습니다.

### 2. Backend

```powershell
Set-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat bootRun
```

Backend는 실행 폴더의 `.env`와 그 상위 폴더의 `.env`(즉 루트 `.env`)를 읽습니다. 처음 실행하면 Flyway가 DB 테이블을 만들고, 종목 테이블이 비어 있으면 KIS 종목 마스터 파일을 내려받아 종목을 채웁니다. 상태 확인 주소: `http://localhost:8080/actuator/health`

### 3. Web

```powershell
Set-Location web
npm install --cache .npm-cache
npm run dev
```

브라우저에서 `http://localhost:5173`을 엽니다. Vite 개발 서버가 `/api`, `/actuator` 요청을 `http://localhost:8080`(Backend)으로 넘겨 줍니다.

### 4. Mobile (선택)

```powershell
Set-Location mobile
flutter pub get
flutter run
```

### 테스트

```powershell
Set-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat test

Set-Location ..\web
npm test
npm run build
```

Windows에서 Backend를 로그온 시 자동 시작·재시작하게 하는 방법은 [운영 가이드](docs/OPERATIONS.md#백엔드-상시-실행-windows)를 봅니다.

## 주요 기능

Web은 왼쪽 메뉴를 운영(오늘·대시보드·마감 추천), 탐색(시장 스캐너·실시간 레이더), 검증(성과 분석·백테스트), 기록(투자기록)으로 묶습니다. 화면마다 주소(`#/closing` 등)가 있어 새로고침·즐겨찾기가 되고, 메뉴 아래 버튼으로 밝은/어두운 화면을 바꿉니다.

- **오늘(운영 현황)**: 하루 자동 작업 시간표(07:40 달력 → 08:59 연결 → 15:15 평가 → 15:40 관찰 → 15:45 완결성 → 18:10 DART → 18:30 수정주가)와 작업별 정상·확인 필요·문제 표시, 최근 일별 완결성
- **대시보드**: 종목 검색, 왼쪽 탭의 관심종목(그룹 추가·이름 변경·삭제, 실시간 고정)과 실시간 구독 목록, 현재가, 5분봉 차트
- **실시간 수집**: KIS WebSocket(서버와 연결을 유지한 채 실시간 체결을 받는 통신)으로 체결을 받아 5분봉을 만들고, Scanner(조건을 만족하는 종목을 찾는 탐지기)를 돌립니다. 화면은 SSE로 갱신됩니다.
- **시장 전체 스캔**: KIS 순위 API로 시장 전체 후보(Broad)를 만들고, 그중 상위 종목을 Precision(정밀 관측) 실시간 구독에 배정합니다.
- **마감 추천**: 거래일 15:15 기준으로 후보를 자동 평가하고(전략 버전 `closing-recommend-v9_1-1515`), 평가가 끝난 뒤 15:20 전에 실제로 들어온 체결로 가상 진입합니다. 최종 추천은 하루 최대 5종목이며 0종목(NO TRADE)일 수도 있습니다.
- **다음 거래일 성과**: 가상 진입 종목과 미선정 후보의 다음 거래일 결과를 비용 설정을 반영해 계산합니다.
- **일별 수집 완결성**: 거래일마다 마감 파이프라인이 빠짐없이 돌았는지 판정합니다.
- **백테스트·성과 분석**: 저장된 5분봉으로 Scanner 규칙을 다시 돌려 보고, 탐지 이후 성과를 집계합니다.
- **투자기록**: 직접 입력하는 매수·매도 기록과 투자 노트(실제 주문 아님)
- **연구용 수집(기본 꺼짐)**: 장마감 Trajectory, 투자자 수급, 호가·대량체결, 수정주가 일봉, 상한가 직전 호가, 후보 시장경고·DART 공시

기능별 켜짐/꺼짐 상태는 [기능 목록과 상태](docs/FEATURES.md)에 정리되어 있습니다.

## 설정과 환경변수

설정 예시는 [`.env.example`](.env.example)에 있습니다. 실제 기본값은 `backend/src/main/resources/application.yml`이 정하고, `.env`에 적은 값이 우선합니다. 설정을 바꾸면 Backend를 다시 시작해야 반영됩니다.

| 환경변수 | 하는 일 | 코드 기본값 |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL 접속 정보. Supabase를 쓰면 Session Pooler 주소와 `sslmode=require`를 넣습니다. | `jdbc:postgresql://localhost:5432/stock_platform`, `stock`, `change-me` |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `docker compose`가 만드는 로컬 DB 이름·계정 | `stock_platform`, `stock`, `change-me` |
| `REDIS_HOST`, `REDIS_PORT` | Redis(메모리 저장소. 최신 시세 캐시, 탐지 쿨다운에 사용) | `localhost`, `6379` |
| `KIS_APP_KEY`, `KIS_APP_SECRET` | KIS Open API 인증 키. 절대 커밋하지 않습니다. | 비어 있음 |
| `KIS_ENABLED` | KIS REST 호출 사용 여부 | `false` (`.env.example`은 `true`) |
| `MARKET_REALTIME_ENABLED` | 실시간 체결 WebSocket 수집. 꺼져 있으면 5분봉·탐지·마감 평가 입력이 생기지 않습니다. | `false` |
| `KIS_WEBSOCKET_SUBSCRIPTION_LIMIT` | 동시에 실시간 구독할 수 있는 종목 수 | `41` |
| `MARKET_WIDE_SCHEDULE_ENABLED` | 장중 시장 전체 스캔 자동 실행 | `false` |
| `MARKET_PRECISION_ENABLED`, `MARKET_PRECISION_MAX`, `MARKET_SUBSCRIPTION_RESERVE` | 정밀 구독 자동 배정. 관심종목 고정·수동·다음날 관측 구독을 뺀 남은 자리를 모두 정밀 구독에 씁니다. | `false`, `41`, `0` |
| `WATCHLIST_REALTIME_MAX_PINNED` | 관심종목 중 실시간 고정할 수 있는 최대 수 | `10` |
| `CLOSING_AUTOMATION_ENABLED` | 15:15 자동 마감 평가, 가상 진입, 성과·후보 관측, 일별 완결성 기록 | `true` |
| `CLOSING_FEATURE_FREEZE_AT`, `CLOSING_CANDLE_FINALIZATION_GRACE`, `CLOSING_ENTRY_DEADLINE` | 평가 기준시각, 입력 수신 유예, 가상 진입 마감 | `15:15`, `0s`, `15:20` |
| `CLOSING_BUY_FEE_PERCENT` 등 `CLOSING_*_FEE/TAX/SLIPPAGE_PERCENT` | 가상 체결 비용(수수료·세금·슬리피지). 모두 0이면 Net 수익을 현실 수익으로 보지 않습니다. | `0` |
| `CLOSING_CALENDAR_KIS_REFRESH_ENABLED` | KIS 휴장일 조회로 거래일 달력을 매일 갱신 | `false` |
| `MARKET_WIDE_HOLIDAYS` | 추가 휴장일 목록(쉼표 구분). 공식 달력을 대신하지 않습니다. | 비어 있음 |
| `DART_API_KEY` | DART 공시 목록 수집 키. 비우면 수집하지 않습니다. | 비어 있음 |

연구용 수집 스위치(`CLOSING_TRAJECTORY_*`, `CLOSING_MICROSTRUCTURE_ENABLED`, `CLOSING_ORDERBOOK_*`, `MARKET_ADJUSTED_DAILY_ENABLED`, `CLOSING_LIMIT_UP_WATCH_ENABLED`, `CLOSING_CANDIDATE_CONTEXT_ENABLED`)는 모두 기본 꺼짐입니다. 일부 키(`MARKET_BACKFILL_*`, `MARKET_REALTIME_CONNECT_BEFORE_OPEN`, `CLOSING_MIN_DAILY_TRADING_VALUE` 등)는 `.env.example`에 없고 `application.yml`에만 있습니다.

## 문서

- [아키텍처](docs/ARCHITECTURE.md) · [개발 가이드](docs/DEVELOPMENT.md) · [운영 가이드](docs/OPERATIONS.md)
- [시장 데이터](docs/MARKET_DATA.md) · [데이터베이스](docs/DATABASE.md) · [API](docs/API.md)
- [거래 전략](docs/TRADING_STRATEGY.md) · [백테스트와 성과 검증](docs/BACKTESTING.md) · [기능 목록과 상태](docs/FEATURES.md)
- 연구 수집: [장마감 Trajectory](docs/CLOSING_TRAJECTORY.md) · [투자자 수급](docs/INVESTOR_FLOW.md) · [호가·대량체결](docs/MICROSTRUCTURE.md)
- [로드맵](docs/ROADMAP.md) · [변경 기록](docs/CHANGELOG.md) · 의사결정 기록: [ADR-007](docs/decisions/ADR-007-TRADE-LEDGER.md), [ADR-008](docs/decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md), [ADR-009](docs/decisions/ADR-009-CLOSING-V9_1-INPUT-AVAILABILITY.md), [ADR-010](docs/decisions/ADR-010-CLOSING-PRIMARY-SAMPLE-ALL-PRECISION.md)
