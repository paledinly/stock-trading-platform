# Development Guide

## 요구사항

- Java 21 (Gradle toolchain이 21을 요구한다)
- Node.js/npm
- Docker Compose (로컬 PostgreSQL 17, Redis 8)
- Flutter SDK (Mobile 변경 시에만, `pubspec.yaml` 기준 Dart SDK 3.5 이상)

## 로컬 실행

루트에서 `.env.example`을 `.env`로 복사하고 KIS 키를 입력한다. 기본 DB는 Docker의 로컬 PostgreSQL이다.

```powershell
Copy-Item .env.example .env
docker compose up -d
```

Backend (`http://localhost:8080`):

```powershell
Set-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat bootRun
```

`application.yml`의 `spring.config.import`가 작업 디렉터리의 `.env`와 `../.env`를 읽는다. 따라서 `backend/`에서 실행하면 루트 `.env`가 적용된다.

Web (`http://localhost:5173`):

```powershell
Set-Location web
npm install --cache .npm-cache
npm run dev
```

`web/vite.config.ts`가 `/api`, `/actuator`를 `http://localhost:8080`으로 프록시한다. 같은 파일의 `server.allowedHosts`에 특정 ngrok 호스트가 하드코딩되어 있다(외부 접속용, TODO: 확인 필요 — 현재도 쓰는지).

Mobile은 [mobile/README.md](../mobile/README.md)를 따른다.

## 검증

```powershell
Set-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat test

Set-Location ..\web
npm test        # vitest run
npm run lint    # eslint .
npm run build   # tsc -b && vite build
```

- Backend 테스트는 대부분 단위 테스트이며 일부가 H2(메모리 DB)를 쓴다. `ClosingRunMigrationTest`는 Testcontainers(테스트용 Docker 컨테이너) PostgreSQL을 쓰며 Docker가 없으면 건너뛴다.
- Mobile 변경 시 `mobile`에서 `flutter test`를 실행한다.

## 변경 원칙

- 적용된 Flyway migration을 수정하지 말고 새 버전을 추가한다. 현재 마지막 버전은 `V36`이다.
- `.env`, DB dump, 로그, build 결과, `node_modules`를 커밋하지 않는다.
- Feature 정의 변경 시 버전을 올리고 이전 통계와 섞지 않는다(현재 `market-feature-v2`).
- 마감 전략 점수·필터·시각을 바꾸면 `ClosingRecommendation.STRATEGY_VERSION`을 올리고 [ADR-008](decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md) 표본을 처음부터 다시 센다.
- 스케줄 작업은 실제 주문을 만들지 않으며 운영 기본값은 보수적으로 유지한다.
- 시간 테스트는 KST, 장 경계, 휴장일, 늦은 수신, NULL 시각을 포함한다.
- 전략 변경은 동일 조건 비교와 추천 수/NO TRADE 비율을 함께 남긴다.
- 기능을 켜거나 끄거나 추가하면 [FEATURES](FEATURES.md)를 같은 커밋에서 갱신한다.

현재 상태는 `docs/`에, 시점 고정 재현 자료는 `audit/YYYY-MM-DD-*`에 둔다. 완료된 구현 계획을 현재 문서처럼 유지하지 않는다.
