# 전체 시장 장마감 추천 3단계 구현 보고서

기준일: 2026-09-07

## 완료 범위

- Broad Scan 상위 후보의 Precision 자동 구독
- `RealtimeSubscriptionRegistry.Source`에 `CORE`, `PRECISION` 추가
- 기존 관심종목·수동·Quote 구독을 보호 종목으로 처리
- 전체 구독 한도, 보호 종목, Reserve를 반영한 동적 Precision 용량 계산
- Precision 최대 종목 수 설정
- 후보 최소 유지시간 적용
- 신규 후보 교체 점수 차이 적용
- 14:50 이후 기존 후보 고정
- 신규 후보를 먼저 요청하고 KIS 성공 ACK 이후 기존 후보 해제
- KIS 거절 ACK 시 신규 후보만 롤백하고 기존 후보 유지
- 재접속 시 Registry 목표 목록을 기존 WebSocket Client가 재구독
- 자동 할당 상태 조회 API 및 Broad Scan 응답 추가
- 웹 시장 전체 화면에 자동 구독 상태, 용량, Reserve, 승인 대기 표시

## 슬롯 계산

```text
Precision Capacity
  = min(Precision Max,
        WebSocket Limit - Reserve - Protected Unique Stocks)
```

기본값 41개, Reserve 3개, 보호 관심종목 20개라면 Precision 용량은 최대 18개다. 동일 종목이 여러 Source를 가져도 실제 슬롯은 하나만 차지한다.

## 교체 순서

```text
Broad 신규 상위 후보
  -> Reserve 슬롯으로 구독 요청
  -> KIS H0STCNT0 ACK 대기
     -> 성공: 기존 최하위 Precision 해제
     -> 실패: 신규 Precision Source 제거, 기존 후보 유지
```

이 구조는 신규 구독이 거절됐는데 기존 후보부터 해제되는 공백을 방지한다.

## 설정

```text
MARKET_PRECISION_ENABLED=false
MARKET_PRECISION_MAX=28
MARKET_SUBSCRIPTION_RESERVE=3
MARKET_PRECISION_MIN_HOLD=15m
MARKET_PRECISION_REPLACE_MARGIN=10
MARKET_PRECISION_FREEZE_AT=14:50
```

실제 자동 구독에는 `MARKET_REALTIME_ENABLED=true`와 `MARKET_PRECISION_ENABLED=true`가 모두 필요하다. 실계정 ACK 동작을 확인하기 전까지 기본값은 false다.

## API

현재 자동 할당 상태:

```text
GET /api/v1/market-wide/precision
```

`GET /api/v1/market-wide/scan` 응답에도 `precisionAllocation`이 포함된다.

상태 값:

- `DISABLED`: 자동 할당 비활성
- `ACTIVE`: 자동 할당 및 교체 가능
- `FROZEN`: 마감 고정 시각 이후

## 테스트

- 관심종목·수동 구독 보호
- 보호 종목과 Reserve를 반영한 용량 계산
- 상위 후보까지만 자동 할당
- 신규 후보 ACK 전 기존 후보 유지
- 성공 ACK 후 기존 후보 해제
- 실패 ACK 시 신규 후보 롤백
- 백엔드 전체 회귀 테스트
- 웹 전체 테스트 및 프로덕션 빌드

검증 결과:

```text
Backend full test: BUILD SUCCESSFUL
Web tests: 8 files, 10 tests passed
Web production build: SUCCESS
```

## 운영 확인 사항

- 실제 KIS 성공·거절 ACK의 `tr_key` 전달 여부
- 구독 500ms 간격에서의 승인 안정성
- 재접속 후 전체 목표 목록 승인 여부
- 초기 관심종목 수에 따른 Precision 실제 용량
- 14:30~15:20 후보 교체 빈도

## 다음 단계

4단계는 장중 자동 실행과 운영 진단이다. 현재 자동 할당은 Broad Scan이 실행될 때 동작하므로, KRX 영업일과 장 시간을 반영한 Scheduler가 필요하다.
