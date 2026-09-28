# Stock Trading Platform

한국투자증권(KIS) 국내주식 시세를 수집해 관심종목, 5분봉, 실시간 탐지, 시장 전체 후보, 15시 마감 추천, 익일 성과와 백테스트를 한 화면에서 확인하는 연구용 플랫폼입니다.

현재 시스템은 실제 주문을 실행하지 않습니다. 추천 점수는 상승 확률이 아니며, 전략 수익성은 충분한 OOS/Forward 표본이 쌓이기 전까지 미검증 상태입니다.

## 주요 기능

- KIS REST/WebSocket 기반 현재가, 분봉, 일봉, 실시간 체결 수집
- 관심종목 및 제한된 정밀 실시간 구독 관리
- 확정 5분봉과 시장 Feature 생성
- 실시간 레이더와 시장 전체 후보 스캔
- 15:00 기준 마감 추천과 제외 사유 조회
- 다음 거래일 성과, 가상 체결 및 비용 포함 순손익 추적
- 저장된 데이터 기반 전략 재생과 성과 분석

## 구성

- `backend/`: Java 21, Spring Boot, JPA, Flyway, PostgreSQL, Redis
- `web/`: React 19, TypeScript, Vite, TanStack Query
- `mobile/`: Flutter 보조 클라이언트
- `docs/`: 현재 코드의 운영·구조·전략 문서
- `audit/`: 특정 시점의 재현 자료와 감사 기록

자세한 구조는 [아키텍처](docs/ARCHITECTURE.md), 전략 의미는 [거래 전략](docs/TRADING_STRATEGY.md)을 참고합니다.

## 빠른 시작

### 1. 환경 파일과 인프라

프로젝트 루트에서 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up -d
```

기본값은 로컬 PostgreSQL과 Redis입니다. Supabase를 사용할 경우 `.env`의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`만 Session Pooler 정보로 변경합니다. `.env`는 커밋하지 않습니다.

### 2. Backend

```powershell
Set-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat bootRun
```

Health check: `http://localhost:8080/actuator/health`

### 3. Web

```powershell
Set-Location web
npm install --cache .npm-cache
npm run dev
```

Vite가 출력한 로컬 주소로 접속합니다.

### 4. Mobile

Flutter가 설치된 경우에만 실행합니다.

```powershell
Set-Location mobile
flutter pub get
flutter run
```

## 테스트

```powershell
Set-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat test

Set-Location ..\web
npm test
npm run build
```

## 운영 전 확인

- 실시간 수집은 `MARKET_REALTIME_ENABLED=true`일 때만 동작합니다.
- KIS WebSocket 구독 기본 한도는 41개이며 관심종목 수와 동일하지 않을 수 있습니다.
- 자동 시장 스캔, 정밀 구독, 마감 추천 자동화는 기본적으로 비활성화되어 있습니다.
- 비용 설정이 모두 0이면 가상 체결의 순수익은 검증된 현실 수익으로 취급하지 않습니다.
- 휴장일 목록과 KIS 호출 한도는 운영 환경에 맞게 확인해야 합니다.

운영 절차와 장애 확인은 [운영 가이드](docs/OPERATIONS.md)를 참고합니다.

## 문서

- [개발 가이드](docs/DEVELOPMENT.md)
- [운영 가이드](docs/OPERATIONS.md)
- [시장 데이터](docs/MARKET_DATA.md)
- [데이터베이스](docs/DATABASE.md)
- [API](docs/API.md)
- [거래 전략](docs/TRADING_STRATEGY.md)
- [백테스트와 성과 검증](docs/BACKTESTING.md)
- [로드맵](docs/ROADMAP.md)
- [변경 기록](docs/CHANGELOG.md)
