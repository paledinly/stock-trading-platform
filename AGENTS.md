# Agent Working Rules

## Start Here

새 작업에서는 필요한 범위만 다음 순서로 읽는다.

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. 도메인 작업이면 `docs/TRADING_STRATEGY.md`
4. 데이터 수집 작업이면 `docs/MARKET_DATA.md`
5. DB/API 작업이면 `docs/DATABASE.md` 또는 `docs/API.md`

`audit/`는 특정 시점의 증거와 분석 기록이다. 현재 동작을 설명하는 기본 문서로 사용하지 않는다.

## Project Rules

- 목표 전략은 15:00까지 실제로 수신 가능한 정보로 후보를 평가하고, 평가 완료 후 장 마감 전 가상 진입해 다음 거래일 성과를 추적하는 것이다.
- 추천 점수는 상승 확률이 아니다. 완료된 OOS/Forward 표본 없이 수익성이 검증됐다고 표현하지 않는다.
- 실주문 API는 없다. 거래 및 체결 관련 기능은 수동 기록 또는 가상 실행이다.
- 원천 발생시각, 수신시각, 봉 확정시각, 평가시각, 가상 체결시각을 혼동하지 않는다.
- 늦은 틱으로 확정봉을 덮어쓰거나 과거 값을 현재 값으로 대체하지 않는다.
- 휴장일과 다음 거래일은 `ClosingTradingCalendar` 정책을 따른다. 정적 휴장일 목록은 자동 공식 달력이 아니다.
- 새 가중치나 임계값을 운영 기본값으로 적용하기 전에 동일 Universe, 진입, 청산, 비용 조건의 비교 검증을 남긴다.
- 환경변수와 인증정보를 커밋하거나 로그에 노출하지 않는다.
- 사용자의 기존 변경을 되돌리지 않는다. 생성물과 로컬 데이터는 소스와 구분한다.

## Build And Test

```powershell
Set-Location backend
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat test

Set-Location ..\web
npm test
npm run build
```

Flutter 작업을 변경했다면 `mobile`에서 `flutter test`를 추가로 실행한다.

## Documentation Ownership

- 사용자 시작점: `README.md`
- 구조와 경계: `docs/ARCHITECTURE.md`
- 실행 및 개발: `docs/DEVELOPMENT.md`, `docs/OPERATIONS.md`
- 데이터 의미: `docs/MARKET_DATA.md`, `docs/DATABASE.md`, `docs/API.md`
- 전략과 검증: `docs/TRADING_STRATEGY.md`, `docs/BACKTESTING.md`
- 남은 작업: `docs/ROADMAP.md`
- 중요한 결정: `docs/decisions/`
- 과거 증거: `audit/`
