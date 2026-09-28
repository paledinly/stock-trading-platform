# Development Guide

## 요구사항

Java 21, Node.js/npm, Docker Compose가 필요하다. Flutter SDK는 Mobile 변경 시에만 필요하다.

## 로컬 실행

루트에서 .env.example을 .env로 복사하고 필요한 KIS 키를 입력한다. 기본 DB는 Docker의 로컬 PostgreSQL이다.

    Copy-Item .env.example .env
    docker compose up -d

Backend:

    Set-Location backend
    $env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
    .\gradlew.bat bootRun

Web:

    Set-Location web
    npm install --cache .npm-cache
    npm run dev

## 검증

    Set-Location backend
    .\gradlew.bat test

    Set-Location ..\web
    npm test
    npm run build

Mobile 변경 시 flutter test를 실행한다.

## 변경 원칙

- 적용된 Flyway migration을 수정하지 말고 새 버전을 추가한다.
- .env, DB dump, 로그, build 결과, node_modules를 커밋하지 않는다.
- Feature 정의 변경 시 버전을 올리고 이전 통계와 섞지 않는다.
- 스케줄 작업은 실제 주문을 만들지 않으며 운영 기본값은 보수적으로 유지한다.
- 시간 테스트는 KST, 장 경계, 휴장일, 늦은 수신, NULL 시각을 포함한다.
- 전략 변경은 동일 조건 비교와 추천 수/NO TRADE 비율을 함께 남긴다.

현재 상태는 docs에, 시점 고정 재현 자료는 audit/YYYY-MM-DD에 둔다. 완료된 구현 계획을 현재 문서처럼 유지하지 않는다.
