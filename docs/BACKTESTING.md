# Backtesting And Performance Validation

## 목적

백테스트는 높은 과거 수익률을 만드는 기능이 아니라 당시 이용 가능했던 데이터에서 동일한 평가, 진입, 청산, 비용 규칙을 재현하는 도구다.

## 제공 기능

| 기능 | 경로 | 화면 |
| --- | --- | --- |
| Scanner 규칙 재생 | `GET /api/v1/backtests/run`, `GET /api/v1/backtests/stocks` | 백테스트 (`BacktestPage`) |
| 탐지 이후 성과 집계 | `GET /api/v1/scanner-analytics`, `GET /api/v1/scanner-detections/{id}/performance` | 성과 분석 (`AnalyticsPage`) |
| 마감 전략 재생 | `GET /api/v1/closing-recommendations/backtest` | 없음(운영자 직접 호출) |
| 마감 전략 기간 분석 | `GET /api/v1/closing-recommendations/strategy-analytics` | 마감 추천 |
| 다음 거래일 성과·후보 관측 | `/api/v1/closing-recommendations/performance`, `/candidate-observations` | 마감 추천 |

파라미터는 [API](API.md)를 따른다.

## v9.1 가상 진입과 과거 재생의 한계

v9.1(15:15) 가상 진입은 평가 완료 후 신선한 실시간 체결로 기록한다(`closing_paper_entry`). 과거 5분 OHLC만으로는 15:20 전 진입을 복원할 수 없으므로 이 시각 설정의 과거 백테스트는 `ENTRY_DATA_MISSING`으로 남긴다. 동일 후보·진입·청산·비용 조건의 실제 관측 표본을 확보한 뒤 비교해야 하며, 시간 변경 자체는 성과 개선 증거가 아니다.

## 데이터 적격성

공식 비교에는 원천 발생시각과 실제 수신시각, 15:15 Feature 기준과 입력 마감, 확정봉 연속성, 실제 다음 거래일과 전체 세션 관측([세션 완결성](TRADING_STRATEGY.md#다음-거래일-세션-완결성)), 종목별 거래가능 상태가 확인되어야 한다. 현재 값으로 과거 결측을 채운 자료나 backfill로 당시 수신 여부를 알 수 없는 자료는 별도 표시한다.

## 실행 모델

1. 공식 추천 snapshot을 고정한다.
2. 추천 완료 이후의 진입 가능 가격을 선택한다.
3. 가상 체결가격과 신호가격을 분리한다.
4. 다음 거래일의 목표/손절/종가 또는 보유 연장 규칙을 적용한다(기본 목표 +3%, 손절 -2%).
5. 수수료, 세금, spread와 양방향 slippage를 반영한다(`CLOSING_*_PERCENT`, 기본 0).
6. Gross와 Net, 관측 완전성과 불확실성을 함께 저장한다.

동일 봉에서 목표와 손절이 모두 발생하면 순서를 알 수 없으므로 낙관 결과 하나로 확정하지 않는다.

## 비교 실험

동일 Universe, 날짜, Feature cutoff, 진입, 청산, 비용을 유지하고 한 번에 한 요소만 바꾼다. 추천 수, NO TRADE 비율, 완료 표본 수, 승률, 평균/중앙 Net, MDD, 손익비와 benchmark를 함께 본다.

기존 작은 표본에 Weight나 Threshold를 맞추지 않는다. MA score 제거와 MA gate 제거, 중복 가산 제거, baseline 정의 변경은 별도 실험이다.

마감 v9.1의 판단 기준과 비교안(BASELINE, NEXT_OPEN)은 [ADR-008](decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md), 1차 표본 정의는 [ADR-010](decisions/ADR-010-CLOSING-PRIMARY-SAMPLE-ALL-PRECISION.md)을 따른다. 판정 스크립트는 [audit/closing-v9-judge](../audit/closing-v9-judge/README.md)에 있다.

## 현재 한계

2026-09-28 감사 시점 현행 전략의 공식 완료 표본은 0건이었다. 그 뒤 v9.1(2026-10-13~)의 표본 수는 TODO: 확인 필요(운영 DB 조회 필요). 충분한 Forward 표본 전에는 Alpha, Sharpe, Sortino, Risk of Ruin을 신뢰성 있게 주장할 수 없으며 실험 기능 준비 상태로 보고한다.

## 과거 기록: 제거된 장중 Paper / 수신 재생

장중 추천의 Paper 추적·수신 재생·`/exit-comparison` 청산 비교는 2026-10-13 장중 추천 모듈과 함께 제거했다. 마지막 비교 결과와 실패 원인은 [진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md)와 [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)에 남아 있다.
