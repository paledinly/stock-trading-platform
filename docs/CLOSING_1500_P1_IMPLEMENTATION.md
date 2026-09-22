# 15:00 마감추천 전략 P1 운영 자동화

## 구현 범위

- 거래일 15:00~15:20에 공식 추천 실행을 자동 재시도한다.
- 공식 실행 키는 `official-{거래일}-{전략버전}`으로 고정해 중복 실행과 재시작을 안전하게 처리한다.
- 다음 거래일에는 공식 실행의 추천만 5분 단위로 자동 평가한다.
- 전일 추천은 익일 15:30까지, 당일 추천은 다음 거래일까지 실시간 구독에서 보호한다.
- 주말에는 직전 거래일 추천 구독을 유지한다.
- 수동 재생 실행은 공식 전진검증 성과와 섞지 않는다.

## 재사용한 기능

- 추천 생성과 데이터 품질 검사는 `ClosingRecommendationService`를 사용한다.
- 성과 완결성 및 다음 거래일 계산은 `OvernightPerformanceService`를 사용한다.
- 실행 보존과 멱등성은 `ClosingRecommendationRun` 및 `request_key`를 사용한다.
- 구독 한도와 출처 병합은 `RealtimeSubscriptionRegistry`를 사용한다.

## 운영 설정

```properties
CLOSING_AUTOMATION_ENABLED=true
CLOSING_AUTOMATION_POLL_INTERVAL=30s
CLOSING_AUTOMATION_LIMIT=10
CLOSING_AUTOMATION_MIN_OPPORTUNITY=35
CLOSING_AUTOMATION_MAX_RISK=65
CLOSING_AUTOMATION_TARGET_RATE=3
CLOSING_AUTOMATION_STOP_RATE=-2
```

자동화는 추천과 관측만 수행한다. 실제 주문이나 매도는 실행하지 않는다.

## 검증 방법

1. 14:59에는 공식 run이 생성되지 않아야 한다.
2. 최신 실시간 틱이 있는 15:00~15:20에는 날짜별 공식 run이 한 건만 생성되어야 한다.
3. 프로세스를 재시작해도 같은 공식 실행 키로 새 run이 추가되지 않아야 한다.
4. 다음 거래일에는 전일 공식 run ID로만 성과가 갱신되어야 한다.
5. 금요일 추천 종목은 주말과 월요일 15:30까지 `OVERNIGHT` 구독 출처를 유지해야 한다.
6. 수동 `REPLAY` run을 추가해도 자동 성과 대상이 바뀌지 않아야 한다.

## 이후 P1 잔여 범위

- 거래소 공식 휴장·단축장 일정 공급원 연동
- 과거 종목 상태, 상장폐지 종목, 기업행사 및 캔들 정정 버전 보존
- 실시간과 과거 재생 Feature의 동일 입력 대조 리포트
- 시간대별 성과와 다중 TP/SL 통계
- OOS/Walk-Forward 실험 이력과 공식 Paper 성과 집계

위 항목은 현재 저장 데이터만으로 정확히 만들 수 없는 부분이 있어 운영 자동화와 분리한다.
