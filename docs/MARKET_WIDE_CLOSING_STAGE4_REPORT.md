# 전체 시장 장마감 추천 4단계 구현 보고서

기준일: 2026-09-07

## 완료 범위

- 장중 Market-wide Scan Scheduler
- Asia/Seoul 기준 장 시간 정책
- 09:00~14:00 5분 버킷
- 14:00~15:20 2분 버킷
- 주말 및 설정 휴장일 차단
- 수동/자동 Scan 동시 실행 방지
- 스케줄 버킷별 DB 실행 이력
- 완료 버킷 중복 실행 방지
- 실패·중단 버킷 재실행 가능
- 재시작 후 DB 완료 이력 기반 복구
- 실행 시간, 성공·실패·스킵, 후보 수, fallback 및 순위별 상태 진단
- Market-wide 상태 API
- 웹 시장 전체 화면에 자동 Scan 상태 표시

## DB Migration

`V17__create_market_wide_scan_run.sql`

상태:

- `RUNNING`
- `COMPLETED`
- `FAILED`

`scheduled_for`에 Unique 제약을 두어 같은 스케줄 버킷을 중복 완료하지 않는다. 실패 또는 비정상 종료로 `RUNNING`에 남은 버킷은 다음 실행에서 다시 시작할 수 있다.

## 스케줄 정책

Scheduler는 30초마다 현재 시간을 확인하지만 실제 Scan은 계산된 버킷당 한 번만 실행한다.

```text
09:00~14:00  5분 간격
14:00~15:20  2분 간격
그 외 시간     실행하지 않음
토·일          실행하지 않음
설정 휴장일     실행하지 않음
```

## 설정

```text
MARKET_WIDE_SCHEDULE_ENABLED=false
MARKET_WIDE_POLL_INTERVAL=30s
MARKET_WIDE_SCAN_LIMIT=120
MARKET_WIDE_CANDIDATE_LIMIT=30
MARKET_WIDE_INCLUDE_ETF=false
MARKET_WIDE_OPEN=09:00
MARKET_WIDE_CLOSE=15:20
MARKET_WIDE_LATE_START=14:00
MARKET_WIDE_NORMAL_INTERVAL=5m
MARKET_WIDE_LATE_INTERVAL=2m
MARKET_WIDE_HOLIDAYS=
```

휴장일은 쉼표로 구분한다.

```text
MARKET_WIDE_HOLIDAYS=2026-09-24,2026-09-25
```

실계정 순위 API와 WebSocket 검증 전까지 자동 실행 기본값은 false다.

## API

```text
GET /api/v1/market-wide/status
```

주요 응답:

- 현재 실행 여부
- 마지막 시작·완료·실패 및 스케줄 버킷
- 누적 완료·실패·스킵 횟수
- 마지막 스킵·오류 사유
- 최근 실행시간
- 최근 스캔·후보 수
- fallback 여부
- 순위 API별 성공·실패와 후보 수

기존 수동 Scan도 Coordinator를 통과하므로 자동 Scan과 겹치면 HTTP 409로 거절된다.

## 장애 처리

- 순위 API 일부 실패: 성공 결과로 완료하고 상태에 원천별 오류 기록
- 전체 순위 실패: 기존 fallback 결과와 `lastFallback=true` 기록
- Scan 예외: DB Run을 `FAILED`로 기록하고 다음 poll에서 재시도
- 애플리케이션 중단: `RUNNING` 버킷을 재시작 후 재시도
- 완료 후 재시작: 같은 버킷은 DB 이력으로 스킵
- 동시 수동 실행: 하나만 실행하고 나머지는 409 또는 Scheduler 스킵

## 휴장일 한계

현재 KIS/KRX 휴장 캘린더 Client가 프로젝트에 없으므로 휴장일을 추측하지 않는다. 주말은 자동 차단하고 공식 휴장일은 `MARKET_WIDE_HOLIDAYS`로 운영 설정한다. 향후 KRX 캘린더 또는 KIS 장운영 정보가 검증되면 자동 공급자로 교체한다.

## 테스트

- 정상장과 마감 구간 버킷 계산
- 주말·설정 휴장일·장외 시간 차단
- 완료 실행 이력 저장
- 완료 버킷 중복 실행 방지
- 실패 이력과 재시도 가능 상태
- Backend 전체 회귀 테스트
- Web 상태 화면 테스트 및 Production Build

## 다음 단계

5단계는 Broad/Precision 통합 장마감 추천이다. `ScannerDetection`이 없는 Broad-only 후보도 데이터 품질을 명시한 상태로 제한적으로 평가한다.
