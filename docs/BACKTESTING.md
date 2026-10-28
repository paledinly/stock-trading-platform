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

## 시험한 가설과 결론

일봉·5분봉으로 시험한 탐색과 사전 등록을 한곳에 모은 목록이다. 실패한 가설도 남긴다. 같은 시험을 반복하지 않기 위해서이고, 시험한 가설 수를 알아야 나중에 나온 "통과"가 우연인지 판단할 수 있다. 모든 탐색은 실행 전에 조건을 스크립트 머리말에 고정했고, 앞뒤 기간을 나눠(탐색 / 확인) 두 기간 부호가 같고 탐색 구간이 0을 포함하지 않을 때만 남겼다.

**판정 기호:** 실패 = 찾던 우위 없음 · 반대 = 기대와 반대 방향으로 일관됨 · 통과 = 남기는 기준 통과 · 일부 통과 · 판단 불가 = 표본 부족 · 대기 = 사전 등록 후 판정 전.

상장폐지 종목은 데이터에 없다(생존 편향). 연구용 수정주가 일봉은 2026-10-27부터 3년(2023-10~)이고, 그 전 탐색은 7개월(2026-03~)이었다.

| 날짜 | 가설 | 데이터 | 판정 | 결론과 남은 쓸모 | 기록 |
| --- | --- | --- | --- | --- | --- |
| 10-14 | 상한가 직전 매수 | 장중 관찰 | 대기 (BUYABLE 60건·15거래일, 약 11-10) | 사전 등록. 살 수 있었던 사건만 판정한다 | [limit-up-entry](../audit/2026-10-14-limit-up-entry/README.md) |
| 10-23 | 선정 규칙 R-TOP3 (최종 점수 상위 3개 vs 전체) | 확증 표본 | 대기 (체크포인트 약 11-13) | 사전 등록 | [closing-preregistration](../audit/2026-10-23-closing-preregistration/README.md) |
| 10-23 | 밤사이 EWY 신호로 청산 방식 고르기 (H-D) | 일봉 7개월 | 실패 (과거 검증 기준 미달) | 다음 날 시가 청산보다 나빴다 | [overseas-h-d](../audit/2026-10-23-overseas-h-d-preregistration/README.md) |
| 10-23 | 점수·관문이 다음 날 성과 순서를 가르는가 | 공식 평가 17일 413건 | 실패 | 점수 순위 상관 −0.05, 55점 이상이 오히려 낮음 | [score-calibration](../audit/2026-10-23-score-calibration-exploration/README.md) |
| 10-23 | 청산 방식 비교 | 일봉 7개월 | **통과** | 다음 날 시가 청산이 +3%/−2% 규칙보다 일관되게 나음(+0.4~0.7%p/일) | [exit-method](../audit/2026-10-23-exit-method-exploration/README.md) |
| 10-23 | 일봉 가설 고정 검색 | 일봉 7개월 | 일부 통과 | 상한가 근처 마감만 강함. 대부분 잠겨 살 수 없음 | [hypothesis-scan](../audit/2026-10-23-hypothesis-scan/README.md) |
| 10-23 | 장중 5분봉 특징 (마지막 15·45분, VWAP 등) | 5분봉 23일 | 실패 / 판단 불가 | 표본 부족, 남긴 특징 없음 | [intraday-features](../audit/2026-10-23-intraday-features-exploration/README.md) |
| 10-27 | 움직이기 전 징후 (변동성 수축, 거래량 마름, 저항선) | 일봉 7개월 | 반대 | 징후가 있으면 크게 오를 확률이 오히려 낮음. 변동성 군집 확인 | [early-signal](../audit/2026-10-27-early-signal-exploration/README.md) |
| 10-27 | 요일·만기일·월말, 대형주 | 일봉 7개월 | 실패 | 효과 없음. 만기일·월말은 표본 부족 | [calendar-size](../audit/2026-10-27-calendar-size-exploration/README.md) |
| 10-27 | 보유 기간 늘리기 (1~10일) | 일봉 7개월 | 반대 | 오래 들수록 나쁨. 강세 후보는 3~5일 시장보다 빠짐 | [holding-period](../audit/2026-10-27-holding-period-exploration/README.md) |
| 10-28 | 급락 종목 반등 (−5%, −10% 이하 매수) | 일봉 3년 | 반대 | 급락 종목은 1~10일 계속 시장보다 빠짐(20개 비교 모두). **피할 종목 기준** 후보 | [reversal](../audit/2026-10-28-reversal-exploration/README.md) |
| 10-28 | 저변동성 (월 교체), 급등 종목 재확인 | 일봉 3년 | 실패 / 일부 통과 | 저변동성은 시장을 못 이김. 고변동성 묶음이 가장 나쁨. 급등 종목 밤사이 이익은 상한가 마감에서만 | [volatility](../audit/2026-10-28-volatility-exploration/README.md) |
| 10-28 | 점수의 일봉 이동평균 요소·관문 | 일봉 3년 2.4만 건 | 실패 | 일봉 추세·과열 관문, 정배열·20일선 가점 모두 효과 0 근처. v9.2 단순화 근거 | [moving-average](../audit/2026-10-28-moving-average-exploration/README.md) |
| 10-28 | 이동평균 매수·매도 시점 (20일선 돌파, 5·20 크로스, 눌림목) | 일봉 3년 | 실패 | 거래당 손실, 시장 대비 0 근처 | [ma-timing](../audit/2026-10-28-ma-timing-exploration/README.md) |
| 10-28 | 중기 모멘텀(6·12개월), 52주 신고가 근접 (월 교체) | 일봉 2년 | **일부 통과** | 12개월 모멘텀·52주 신고가 상위가 시장보다 월 +1%p 안팎(두 기간 플러스). 기간당 11개월이라 근거 약함 | [momentum](../audit/2026-10-28-momentum-exploration/README.md) |
| 10-28 | 공시 종류별 이후 성과 (자기주식 취득, 공급계약, 잠정실적, 유상·무상증자, 전환사채, 소각) | DART 3년 + 일봉 | **일부 통과** | 자기주식 취득 결정이 1·5·20일 모두 두 기간 시장보다 나음(+0.5~2%p). 무상증자 20일 우위(건수 적음). 공급계약·잠정실적·유상증자는 다음 날 장중 시장보다 빠짐 | [disclosure](../audit/2026-10-28-disclosure-exploration/README.md) |

**종합 (2026-10-28 기준).** 하룻밤 보유에서 비용을 이기는 살 수 있는 우위는 찾지 못했다. 일관된 결과는 두 가지다. 다음 날 시가 청산이 낫다는 것, 그리고 크게 움직인 종목(급락·고변동성, 강세 종목의 며칠 보유)은 이후 성과가 나쁘다는 것이다. 유망 후보는 한 달 보유의 12개월 모멘텀·52주 신고가 근접과, 자기주식 취득 결정 공시 뒤 5~20일 보유다. 더 긴 데이터와 Forward 누적으로 확인해야 한다.

## 현재 한계

2026-09-28 감사 시점 현행 전략의 공식 완료 표본은 0건이었다. 그 뒤 v9.1(2026-10-13~)의 표본 수는 TODO: 확인 필요(운영 DB 조회 필요). 충분한 Forward 표본 전에는 Alpha, Sharpe, Sortino, Risk of Ruin을 신뢰성 있게 주장할 수 없으며 실험 기능 준비 상태로 보고한다.

## 과거 기록: 제거된 장중 Paper / 수신 재생

장중 추천의 Paper 추적·수신 재생·`/exit-comparison` 청산 비교는 2026-10-13 장중 추천 모듈과 함께 제거했다. 마지막 비교 결과와 실패 원인은 [진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md)와 [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)에 남아 있다.
