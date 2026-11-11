# AI 장전 계획 2단계: 실시간 감시와 가상 매수

상태: **구현(2026-11-10), 기록 시작 2026-11-11.** 사용자 결정으로 1단계 판정 전에 착수했다([ADR-012 보완](decisions/ADR-012-AI-PREMARKET-FORWARD-RECORD.md#보완-2단계-착수-2026-11-10)). 1단계 기록·채점은 [AI_PREMARKET](AI_PREMARKET.md).

코드: `backend/.../aiplan/realtime`(`AiWatchService`, `AiWatchEngine`, `StockMonitor`, `WatchRules`, `AiCondition`, `AiWatchStore`, `AiMarketClient`), `V49__create_ai_watch.sql`, Web `AI 장전` → `실시간 감시` 탭(`AiWatchPanel.tsx`).

## 목표

장전 AI 계획의 종목을 장중에 실시간으로 감시하고, 미리 정한 조건이 맞는 순간에 **가상으로 매수**해 비용을 뺀 성과를 기록한다. 실제 주문은 없다.

판단하려는 질문은 두 가지다.

1. AI 종목을 "조건이 맞을 때" 사면, 같은 종목을 시가에 사는 것(1단계 채점 기준)보다 나은가?
2. 그 결과가 비용(왕복 약 0.39% + 스프레드)을 넘는가?

## 2026-10-13 장중 추천 실패에서 가져온 제약

[실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)의 원인 네 가지를 규칙에 넣었다.

| 실패 원인 | 이번 규칙 |
| --- | --- |
| 한 번 거래의 기대 움직임(중앙값 0.18%)이 비용보다 작음 | 작은 목표 익절 없음. 기본 청산은 구조 손절 아니면 장 마감 |
| 이미 오른 뒤 추격 매수 | 시가 대비 +8% 초과, 직전 5분봉 +2% 이상, 돌파선 +2% 초과면 매수 금지 |
| 손절폭(0.35%)이 평소 흔들림보다 좁음 | 손절 거리 ≥ ATR5m 그리고 ≥ 0.8%. 못 맞추면 매수 후보가 아님 |
| 시장 방향 무시 | KOSPI·KOSDAQ 1분 조회, 둘 중 하나라도 시가 대비 −0.5% 아래면 신규 매수 차단 |

## 하루 흐름

```
08:50~  30초마다 그날 집계 대상 계획(장 시작 전 마지막 판, PREMARKET)을 확인 → 종목 구독, 과거 5분봉 거래량 프로필 준비
        장 시작 전에 새 판이 오면 바꿔 구독. 장 시작 뒤에는 바꾸지 않음
09:00~  틱 → 종목별 지표 갱신(메모리). 분이 끝나고 2초 뒤 상태 판단 → 바뀌면 저장·알림
        매수 후보 → 호가 1회 조회(스프레드) → 그 뒤 첫 체결로 가상 진입 → 틱마다 손절·익절 확인
14:30   신규 진입 마감(진입 없는 종목은 EXPIRED)
15:19   남은 가상 포지션을 마지막 체결 가격으로 청산(CLOSE)
15:20   AI 구독 해제, 감시 종료
```

재시작하면 그날 계획을 다시 읽고 DB의 상태(INVALIDATED·EXPIRED)와 가상 포지션을 되살린다. 재시작 사이의 가격은 못 보므로 포지션에 `tracking_gap`을 표시한다. 장중에 처음 받은 종목은 시가 범위를 모르므로 규칙 P 매수 후보가 되지 않는다(장중 재시작은 피한다).

## 1. 구독 (KIS 한도 41개)

- 출처 `AI_PLAN`(`RealtimeSubscriptionRegistry.Source`). **AI가 준 종목(picks, 최대 20) 전부를 우선순위 순으로 구독한다**(사용자 결정 2026-11-10). 순서는 최우선 감시 종목(`topPriorityStockCodes`) → `priority` 순.
- 이미 다른 출처로 구독 중이면 출처만 더하고, 빈자리가 있으면 그 자리에, 꽉 찼으면 **정밀 후보(PRECISION) 중 가장 약한 자리를 양보받는다**(`PrecisionSubscriptionAllocator.yieldSlotTo`, 최소 유지 시간 무시). 관심종목·수동·전일 추천(OVERNIGHT) 자리는 건드리지 않는다.
- 정밀 구독 자리 수는 "한도 − 예비 − 다른 출처 전용 자리"로 계산되므로, AI 종목만큼 마감 추천 정밀 후보가 자동으로 줄고 다시 채워지지 않는다.
- 양보받을 자리도 없으면 그 종목은 `NOT_SUBSCRIBED`로 남고 판단하지 않는다(실시간 시세 없이 매수 신호 금지). `ai_watch_session.unsubscribed_codes`에 남긴다.
- 15:20에 AI 출처를 모두 뺀다. 정밀 배정은 14:50 이후 동결이라 그날은 다시 채우지 않는다.

## 2. 실시간 지표 (`StockMonitor`)

| 지표 | 계산 |
| --- | --- |
| 1분·5분 칸 | 누적 거래량·누적 거래대금 증가분으로 집계(KIS가 체결 일부를 빼고 보내도 거래량이 맞음). 칸이 끝나고 2초 뒤 닫고, 늦게 온 틱은 닫힌 칸에 넣지 않음 |
| 시가 범위 5분·15분 고가·저가 | 09:05·09:15 전 마지막 틱의 당일 고가·저가 |
| VWAP | 틱의 당일 누적 거래대금 ÷ 누적 거래량 |
| ATR5m | 최근 완료 5분봉(최대 12개) 고가−저가 평균. 3개 미만이면 없음 |
| RVOL_5M | 마지막 완료 5분봉 거래량 ÷ 같은 5분 칸 과거 평균(`intraday_research_bar` 직전 20거래일, 5분봉이 있는 날만, 빈 칸은 0). 10일 미만이면 없음 |
| 전일 5분 평균 거래대금 | 전 거래일 5분봉 거래대금 합 ÷ 칸 수. RVOL이 없을 때 대신 씀 |
| 전일 고가·종가 | `daily_adjusted_candle` |
| 시장 | KOSPI(0001)·KOSDAQ(1001) 1분마다 REST(FHPUP02100000). 시가 `bstp_nmix_oprc`가 없으면 그날 처음 받은 값. 3분 넘게 오래되면 없음 |
| 스프레드 | 매수 신호 순간 호가 1회(FHKST01010200) (매도1 − 매수1) ÷ 중간값 ÷ 2. 실패하면 0.06% |
| 자료 끊김 | `dataLoss`(재연결·처리 대기열 넘침·처리 실패), 09:01 뒤 처음 받은 틱. 누적 거래량 증가분 ≠ 체결량(KIS가 작은 체결을 빼고 보냄)은 끊김이 아니고 `volumeMismatches`로 횟수만 남긴다(p2부터) |

연구 수집기(지수·호가, 동결)를 다시 켜지 않고 AI 감시가 따로 조회한다. AI가 적은 가격(`keyLevels`, `marketData`)은 쓰지 않는다.

## 3. 상태 (`AiWatchEngine`)

우선순위: `INVALIDATED > EXPIRED > CAUTION > BUY_CANDIDATE > READY > WATCH`. 구독 못 한 종목은 `NOT_SUBSCRIBED`, 규칙 A를 계산할 수 없으면 `UNSUPPORTED`.

- 판단: 1분마다(분 끝 + 2초). 거래정지 틱은 바로 `CAUTION`. 손절·익절은 틱마다.
- `INVALIDATED`: 15분 시가 범위 뒤 완료 5분봉 종가가 15분 저가 아래. 그날 다시 풀리지 않는다(두 규칙 공통).
- `EXPIRED`: 14:30 뒤 그 규칙의 진입이 없음.
- `CAUTION`: 거래정지, 최근 5분 안 자료 끊김, (규칙 P) 시장 약세. 체결이 60초 넘게 없는 것은 매수만 막고 `CAUTION`은 아니다.
- `BUY_CANDIDATE`: 그 규칙의 조건이 모두 충족(`PASS`). 자료 없음(`UNAVAILABLE`)은 충족이 아니다.
- `READY`(규칙 P만): 15분 고가 1% 이내로 다가오고 VWAP 위.
- 계획이 `TRADE`가 아닌 날(`WATCH_ONLY`·`NO_TRADE`·`DATA_INSUFFICIENT`)도 똑같이 판단하고 가상 진입하되 `shadow`로 표시한다. AI의 "사지 마라" 판단이 맞았는지 따로 본다.
- 상태가 바뀔 때마다 `ai_signal_event`에 원천 체결시각·수신시각·평가시각, 이전·새 상태, 충족 안 된 조건, 그 순간 지표(JSON)를 남긴다.

## 4. 매수 조건 (같은 날 두 규칙을 함께 기록)

### P: 프로그램 고정 규칙 (사전 등록, `WatchRules`, `ai-watch-p2`)

임계값은 모두 HYPOTHESIS이며 기록 중 바꾸지 않는다. 바꾸면 `RULE_VERSION`을 올리고 따로 센다. 판정·집계(`/watch/summary`)는 지금 버전으로 감시한 날만 센다.

- `ai-watch-p1`: 2026-11-11 하루. 진입 0건.
- `ai-watch-p2`(2026-11-12~): 임계값은 같다. 누적 거래량 불일치를 끊김으로 보지 않는다. 11-11 하이닉스가 2~470주 불일치로 하루 10번 가까이 5분씩 `CAUTION`이 됐다. 같은 시간 실시간 5분봉과 비교하니 처리 손실은 0건이었고 KIS가 체결 일부를 빼고 보낸 것이었다.
- 거래량 2배는 과거 1년 5분봉 비교([audit/2026-11-11-ai-watch-volume-study](../audit/2026-11-11-ai-watch-volume-study/FINDINGS.md), 브랜치 `audit/2026-11-11-ai-watch-volume`)에서 낮출 근거가 없어 그대로 둔다. 같은 비교에서 규칙 P는 모든 거래량 변형에서 비용 포함 손실이었다(전체 종목 기준).

| 조건 | 기준 |
| --- | --- |
| `BREAKOUT_5M` | 09:15 이후 시작한 마지막 완료 5분봉 종가 > 15분 시가 범위 고가 |
| `ABOVE_VWAP` | 현재가 > VWAP |
| `VOLUME_5M` | RVOL_5M ≥ 2. RVOL이 없으면 5분 거래대금 ≥ 전일 5분 평균 × 2 |
| `NOT_CHASING_OPEN` | 현재가 ≤ 시가 × 1.08 |
| `NOT_CHASING_5M` | 마지막 완료 5분봉 상승 < 2% |
| `NOT_CHASING_BREAKOUT` | 현재가 ≤ 15분 고가 × 1.02 |
| `STOP_DISTANCE` | 손절 = max(15분 저가, VWAP − 0.5 × ATR5m). 현재가 − 손절 ≥ ATR5m 그리고 ≥ 현재가의 0.8% |
| `MARKET` | KOSPI·KOSDAQ 둘 다 시가 대비 ≥ −0.5% |
| `TIME` | 09:15~14:30 |
| `DATA` | 마지막 틱 60초 이내, 최근 5분 끊김 없음 |

### A: AI 조건 규칙 (비교안, `AiCondition`)

AI JSON `picks[].entryStrategy.conditions[]`를 그대로 적용하고 `STOP_AVAILABLE`(위 손절 계산이 되고 현재가가 그 위), `TIME`, `DATA`를 더한다. 추격 금지·시장 조건은 넣지 않는다(그 차이를 보는 비교안).

- 지원 지표: `RVOL_5M`, `PRICE_VS_VWAP`(단위 KRW 또는 PERCENT), `CLOSE_5M`(기준 `OPENING_RANGE_5M_HIGH`, `OPENING_RANGE_15M_HIGH`, `PREVIOUS_HIGH`, `PREVIOUS_CLOSE`, `VWAP` 또는 숫자), `TURNOVER_5M`, `PRICE_CHANGE_FROM_OPEN`. 연산자 `> >= < <=`.
- `value`가 null이면 규칙 P의 같은 기준으로 바꾼다(RVOL 2, 돌파선 15분 고가, 거래대금 전일 평균 × 2, 시가 대비 8%).
- 모르는 지표·연산자·기준이 하나라도 있거나 조건이 없으면 그 종목의 규칙 A는 `UNSUPPORTED`.
- 11-11에는 세 종목 모두 `UNSUPPORTED`였다(AI가 `VALIDATED_RVOL_THRESHOLD`, `PULLBACK_RECOVERY_LEVEL`, `MARKET_TRADE_PERMISSION`을 적음). v2 계획을 저장할 때 계산할 수 없는 조건이 있으면 종목별로 경고한다(저장은 한다).
- 11-12부터 쓰는 v4 프롬프트는 종목마다 전략 여러 개(`strategies[]`)에 진입 조건을 적는다. 규칙 A는 확인형 진입(`CONFIRMED_ENTRY`)의 `entryRules.conditions` 중 `required`가 false가 아닌 것만 따라간다. 조건 결합(`entryRules.operator`)이 AND가 아니면 `UNSUPPORTED`.
  - v4 표기 읽기: `PRICE > VWAP`(숫자 없음)은 `PRICE_VS_VWAP > 0`, 기준 `PROGRAM_CONFIG`는 값 없음(규칙 P 기준)으로 본다. `enabled`·`profileId`는 실주문 활성화 표시라 가상 기록에는 쓰지 않는다.
  - 조기 진입(`EARLY_MOMENTUM`)은 1분 거래량·거래대금, 호가 스프레드가 없어 아직 기록하지 않는다. 확인형 진입이 없는 종목은 규칙 A가 `UNSUPPORTED`다. 조기·확인형 성과를 따로 기록하려면 규칙을 하나 더 두는 작업이 필요하다.
  - v3 계획(배포 안 됨)은 진입 조건이 없어 규칙 A가 `UNSUPPORTED`("진입 조건 없음(v3 전략 …)")다.

## 5. 가상 진입과 청산

- 진입: 매수 후보 판정 → 호가 1회 조회 → **그 뒤 처음 들어온 체결** 가격 + 스프레드 절반. 판정 순간 가격이 아니다. 60초 안에 체결이 없으면 취소하고 다음 분에 다시 판단한다. 종목·규칙마다 하루 1회.
- 수량: 가상 1회 **1,000만 원**(`AI_PLAN_WATCH_POSITION_AMOUNT`, 사용자 결정 2026-11-10) ÷ 진입가, 내림. 직전 1분 거래대금의 5%를 넘으면 `capacity_limited`.
- 비용: 마감 추천과 같은 `closing.execution-cost`(수수료·세금·슬리피지). 청산 가격도 체결가 − 스프레드 절반.
- 청산 두 가지를 한 진입에서 함께 기록:
  - E1 구조 손절 + 장 마감: 체결가 ≤ 손절가면 `STOP`, 아니면 15:19 마지막 체결 가격 `CLOSE`
  - E2 기존 앱 규칙: +3% `TAKE` / −2% `STOP`, 아니면 15:19 `CLOSE`
- 함께 기록: 진입 뒤 최고·최저가, 그날 시가, 신호·진입 시각(원천·수신).

## 6. 화면과 알림

- `AI 장전` → `실시간 감시` 탭: 2단계 판정 카드, 종목별 규칙 P·A 상태, 현재가·VWAP·15분 고저·RVOL, 가상 매매(E1·E2 청산 또는 보유 중 평가), 조건 표(✓ 충족, ✗ 미충족, ? 자료 없음), 상태 이력. 장중에는 5초마다 새로 읽는다. 지난 날짜는 저장된 마지막 상태와 포지션을 보여 준다.
- 브라우저 알림(허용한 경우, 화면이 열려 있을 때): `READY`, `BUY_CANDIDATE`, `INVALIDATED`, 가상 매수, 가상 손절.
- SSE 이벤트 `ai.signal.changed`, `ai.position.changed`도 보낸다.
- API: `GET /api/v1/ai-plans/watch?date`, `GET /api/v1/ai-plans/watch/summary`([API](API.md)).

## 7. DB (V49)

| 테이블 | 내용 |
| --- | --- |
| `ai_watch_session` | 날짜별 감시: 계획 판 id, 판단, 시작·종료 시각, 종목 수·구독 수·구독 못 한 종목, 1회 금액, 규칙 버전 |
| `ai_signal_event` | 상태 변화(종목, 규칙 P/A, 시각 3종, 이전·새 상태, 충족 안 된 조건, 지표 JSON) |
| `ai_virtual_position` | 가상 진입 한 건(날짜·종목·규칙마다 하나): 그림자 여부, 신호·진입 시각, 체결가·스프레드·진입가·수량, 손절가, E1·E2 청산, 최고·최저가, 비용 가정 |

틱은 저장하지 않는다.

## 8. 2단계 판정 (사전 등록)

- 표본: 규칙 P·청산 E1 가상 거래(그림자 포함)를 날짜 순으로 쌓아 **40건 이상 그리고 15거래일 이상**이 처음 된 날까지. 그 뒤 거래는 판정을 바꾸지 않는다.
- 계속 조건: 거래당 비용 뺀 평균 > 0 **그리고** 같은 종목 시가 매수(1단계 채점의 비용 뺀 시가→종가)보다 나음. 둘 중 하나라도 아니면 `FAIL`이고 실시간 매매 방향을 접는다.
- 함께 보는 값: 규칙 A, 청산 E2, 실제(TRADE 날)와 그림자 나눠 본 평균·이긴 비율·시가 매수 대비. 날짜 블록 재표본 신뢰구간은 판정 시점에 audit에서 계산한다.

## 9. 테스트

- `AiWatchEngineTest`: 합성 틱으로 시가 범위 → 돌파 → 매수 후보 → 호가 뒤 첫 체결 진입 → E2 익절·E1 손절, 그림자·15:19 청산, 시장 약세 차단, 추격 차단, 구조 무효화, 지원 안 되는 AI 조건, 거래정지.
- `AiWatchServiceTest`(H2, V47~V49): 우선순위 구독과 정밀 자리 양보, 15:20 해제, 거래량 프로필, 포지션 저장·갱신, 판정 표본.
- `PrecisionSubscriptionAllocatorTest.yieldsTheWeakestPrecisionSlotToTheAiPlanWatch`.
- 장중 실제 동작은 첫 운영일(2026-11-11)에 확인한다.

## 한계

- 상태 판단·진입 규칙은 과거 틱으로 검증하지 않은 가설이다. 틱을 저장하지 않으므로 과거로 다시 돌려 볼 수 없다.
- 시장 필터는 지수 1분 REST라 최대 1분 늦다. 스프레드는 신호 순간 1회만 본다.
- 프로필은 `intraday_research_bar`(매일 19:00 수집)가 비면 RVOL 대신 전일 평균 거래대금을 쓴다.
- AI 종목만큼 마감 추천 정밀 후보 구독이 줄어든다.
