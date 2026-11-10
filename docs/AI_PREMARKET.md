# AI 장전 계획 (1단계: 기록과 채점)

ChatGPT가 장 시작 전에 고른 종목을 기록하고, 장 마감 뒤 실제 시장과 비교한다. 2단계(실시간 감시·가상 매수) 설계 초안은 [AI_PREMARKET_PHASE2](AI_PREMARKET_PHASE2.md). 결정과 통과 기준은 [ADR-012](decisions/ADR-012-AI-PREMARKET-FORWARD-RECORD.md)에 있다. 매매 신호가 아니고, 운영 마감 추천에 영향을 주지 않는다.

## 매일 하는 일

1. Web `AI 장전` 화면에서 "ChatGPT 프롬프트 보기" → "프롬프트 복사". 분석 대상 거래일과 JSON 예시가 채워져 있다.
2. ChatGPT에 붙여넣고, 나온 JSON을 화면 입력 칸에 넣는다.
3. "검사만 하기"로 오류를 확인하고 "검사 후 저장". **장 시작(09:00, 특별 개장일은 그 시각) 전에 저장해야 집계된다.**
4. 다시 넣으면 새 판으로 저장된다. 장 시작 전 마지막 판만 집계한다. 장 시작 뒤 판은 저장만 한다.
5. 매일 21:30에 자동 채점한다(`AI_PLAN_EVALUATION_ENABLED`, `AI_PLAN_EVALUATION_CRON`). 화면의 "채점 지금 실행"으로 바로 돌릴 수 있다. 그날 수정주가 일봉(18:30 갱신)이 들어오기 전에는 채점을 미룬다.

## JSON 규격 `ai-premarket-v2`

프롬프트는 사용자가 정한 원문 [`ai-premarket-v2.prompt.md`](../backend/src/main/resources/ai-premarket/ai-premarket-v2.prompt.md)이고, 화면의 프롬프트 복사는 날짜(2026-11-11, 20261111)를 다음 분석 거래일로 바꿔 준다. 출력 구조 예시는 [`ai-premarket-v2.example.json`](../backend/src/main/resources/ai-premarket/ai-premarket-v2.example.json). 처음 만든 `ai-premarket-v1`(2026-11-10)도 계속 받는다. 규격을 바꾸면 `schemaVersion`을 올린다.

| 칸 | 필수 | 검사 |
| --- | --- | --- |
| `schemaVersion` | ✓ | `ai-premarket-v2` (또는 `ai-premarket-v1`) |
| `targetDate` | ✓ | YYYY-MM-DD, 거래일, 오늘 이후. 지난 날짜는 거부 |
| `analysisMode` | ✓ | PREMARKET / INTRADAY. INTRADAY는 저장만 하고 집계에서 뺀다 |
| `analyzedAt` | | 시간대 포함 시각 또는 null. null이면 경고하고 저장 시각으로만 장 시작 전 여부를 판단한다. 지금보다 5분 넘게 미래면 거부 |
| `analysisValidUntil` | | 없으면 targetDate 장 마감. analyzedAt보다 뒤 |
| `model.name`, `model.promptVersion` | ✓ | 비면 거부. `model.version`이 없으면 경고 |
| `market.outlook` | ✓ | BULLISH / NEUTRAL / BEARISH |
| `market.regime`, `market.riskLevel` | | 정해진 값이 아니면 경고. 저장해 두고 나중에 나눠 본다 |
| `market.news[].publishedAt`, `picks[].sources[].publishedAt` | | 지금보다 미래면 거부(미래 정보) |
| `tradeDecision` | ✓ | TRADE / WATCH_ONLY / NO_TRADE / DATA_INSUFFICIENT. TRADE면 picks 1개 이상 |
| `topPriorityStockCodes` | | 최대 3개, picks에 있는 코드만 |
| `picks[]` | | `stockCode`(종목 마스터에 있는 6자리), `priority`(정수), `reason` 필수. 출처·매수 금지/무효화 조건이 없거나, priority가 겹치거나, status가 WATCH가 아니면 경고 |
| `avoid[]` | | 매수 금지 종목. picks와 겹치면 거부 |

- ChatGPT가 JSON을 코드 블록(```json … ```)으로 감싸 보내도 벗겨서 읽는다.
- 같은 종목이 두 번 나오면 거부한다. 종목명이 마스터와 다르면 경고하고 코드 기준으로 저장한다.
- `marketData`, `keyLevels`, `entryStrategy.conditions`, `exitPlan` 같은 AI가 적은 가격·조건은 **원본 JSON에만** 남는다. 확정 매매 가격으로 쓰지 않는다. 2단계에서 실시간 데이터로 다시 확인한다.

## 채점

- 대상: 날짜별로 장 시작 전에 저장된 마지막 판(analysisMode PREMARKET).
- 종목마다 `daily_adjusted_candle`의 그날 시가·고가·저가·종가와 전 거래일 종가로 계산한다(퍼센트).
  - 갭(전일 종가→시가), 시가→종가, 고가·저가(시가 대비), 전일 종가→종가
  - 비용 뺀 시가→종가: 마감 추천과 같은 `CLOSING_*_FEE/TAX/SLIPPAGE_PERCENT`
  - 시장 평균: 직전 20거래일 평균 거래대금 10억 원 이상(ETF·ETN 제외) 종목의 같은 날 시가→종가 평균(동일 가중)
  - 초과 = 시가→종가 − 시장 평균
- 상태: `COMPLETE`, `NO_DATA`(그날 일봉 없음, 7일 동안 다시 채점), `SUSPECT`(갭 ±30% 초과, 기업행사 가능성. 집계에서 뺌)
- 채점일: TRADE 또는 WATCH_ONLY로 판단하고 COMPLETE 종목이 있는 날. NO_TRADE·DATA_INSUFFICIENT 날은 '매매 안 함' 날로 따로 센다.
- 1단계 판정: 처음 20개 채점일의 평균 초과 > 0이고 12일 이상 시장보다 나으면 `PASS`. 20일 전에는 `PENDING`.
- 함께 보는 값: 최우선 감시 종목(`topPriorityStockCodes`)의 초과, 매수 금지 종목의 초과, '매매 안 함' 날과 종목을 고른 날의 시장 평균 차이(매매하지 말아야 할 날을 맞히는지).

## 한계

- 시가→종가는 실제 진입 시점과 다르다(2단계에서 실시간 가상 매수로 확인).
- 일봉은 수정주가라 그날 분할·병합이 있으면 갭이 틀릴 수 있다(SUSPECT로 표시).
- 시장 평균은 연구 기준 Universe이고, AI가 고를 수 있는 종목 전체와 같지 않다.
