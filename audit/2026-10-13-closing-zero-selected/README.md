# closing v9 SELECTED 0건 원인 분석 — 2026-10-13

`closing-recommend-v9-1515` 공식 FORWARD run 2개(10-08 run 61, 10-12 run 62)가 SELECTED 추천을 하나도 만들지 않았다. ADR-008 1차 표본(SELECTED paper entry)이 0건이라 체크포인트에 도달할 수 없다. 저장된 run 스냅샷(`closing_recommendation_run.response_snapshot`)과 입력 테이블을 읽기 전용으로 내려받아 어떤 조건이 후보를 떨어뜨렸는지 분해했다. 앱 코드, 설정, DB는 바꾸지 않았다. 임계값 제안은 하지 않는다.

## 결론

1. **10-12 run 62는 데이터 결함이다.** 10-12 세션의 `scanner_detection`이 하루 종일 0건이다(10-08 690건, 10-13 오전 315건). 5M 봉은 27개 종목에 정상 저장됐으므로 실시간 수신은 됐고, 스캐너 저장 경로만 멈췄다. 평가 후보는 BROAD 51건뿐이었고 BROAD는 설계상 선정되지 않는다(`BROAD_WATCH_ONLY`). 로그가 남아 있지 않아 원인은 확정하지 못했다. 후보 가설: `ScannerEngine.acquire`는 Redis가 없거나 예외가 나면 조용히 `false`를 반환해 탐지를 저장하지 않는다.
2. **10-08 run 61도 데이터 결함이 겹쳤다.** 14:58~15:11 KST에 실시간 공백이 있었다. 15:00/15:05 봉은 2개 종목만 BACKFILL로 생겼고, 14:55 봉은 약 700초 늦게 저장됐다. 15:00 탐지는 15:11:46에 수신됐고 15:05 이후 탐지는 없다. 정밀 후보 20건 전부 `lateCandleRevision=true`, `latestFiveMinute=FAIL`이었다.
3. **v9 설정에는 구조적 측정 결함이 있다. 데이터가 정상인 날에도 정밀 후보가 거의 전부 WATCH가 된다.** v9는 `featureFreezeAt=15:15`, `candleFinalizationGrace=0s`다. 15:10 봉은 정확히 15:15:00에 끝나므로 15:15:00 이전에 저장될 수 없다(저장 지연 중앙값 1~5초). 공식 run은 15:15:14~15:15:24에 실행되므로 그때는 이미 확정돼 있다. `ClosingPrecisionEvaluator`는 이 봉을 "평가 시점에 없던 봉"으로 건너뛰는 데서 끝나지 않는다. 마지막 사용 봉보다 뒤에 있는 미가용 봉이 있으면 `lateCandleRevision`으로 표시하고 `RECEIVED_AFTER_EVALUATION`(WATCH)으로 보낸다. 관측된 11거래일 모두 15:10 봉의 73~100%가 이 조건에 걸린다(아래 표). 실제로 10-12에는 26/26 종목이 공식 run 시각 기준으로 걸렸다. 같은 이유로 15:15:00 탐지(15:10 봉 마감 탐지)는 수신이 항상 15:15:00 이후다. 이 탐지가 종목의 대표 탐지가 되면서 이전의 깨끗한 탐지를 가린다(09-29 이후 하루 4~32종목). v8(15:00 + 10초 유예)에서는 경계 봉이 대부분 유예 안에 들어왔다.
4. **데이터 결함을 모두 제거해도 v8과 같은 전략 게이트는 하루 1건 미만을 선정한다. 이것은 전략 자체의 결과다.** v9와 v8의 점수·필터 코드는 같다. 다른 것은 평가 시각·유예·진입 모델뿐이다. v8 공식 FORWARD 9거래일 정밀 후보 223건 중 78%가 기회점수 < 35, 87%가 최종점수 < 55, 57%가 일봉 추세 약함이었다. 데이터·측정 게이트를 모두 무시해도 통과는 9일 동안 8건(0.89건/일)이었다. 그중 5건이 10-01 하루에 몰렸고, 9일 중 6일은 0건이었다. v9 10-08 run은 데이터 게이트를 무시해도 0건이다(20건 모두 전략 게이트 1개 이상 실패).

따라서 0건의 직접 원인은 데이터·측정 결함이다(10-12 스캐너 정지, 10-08 실시간 공백, v9 경계 봉/경계 탐지 처리). 이를 고쳐도 기대 선정 수는 약 0.9건/일이다. ADR-008이 가정한 "하루 최대 5건, 3~6주"와 달리 60건까지는 진입·다음날 데이터 손실 전 기준으로도 약 65~70거래일이 걸린다. 이 부분은 v9 임계값의 결과라서 바꾸려면 새 버전 사전 등록이 필요하다.

## 공식 run별 분해

정밀(PRECISION) 후보만 선정 대상이다. "데이터 게이트 무시"는 스냅샷 readiness의 모든 데이터·측정 실패를 무시하고 전략 게이트만 적용한 상한이다(LIMITED_MODE 최대 5건 적용 전).

| run | 날짜 | 버전 | 탐지 | 정밀 후보 | 저장 SELECTED | 전략+데이터 모두 통과 | late-bar만 무시 | 데이터 게이트 전부 무시 | 기회≥35·위험≤65 | 점수≥55 | 주요 데이터 문제 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 16 | 09-22 | v8 | 73 | 24 | 0 | 0 | 0 | 0 | 2 | 2 | LATE_BAR 24, CANDLE_GAP 14 |
| 27 | 09-23 | v8 | 183 | 40 | 1 | 1 | 2 | 2 | 4 | 3 | LATE_BAR 18 |
| 31 | 09-28 | v8 | 182 | 41 | 0 | 0 | 0 | 0 | 13 | 1 | DAILY_DATA_STALE 41, LATE_BAR 39 |
| 37 | 09-29 | v8 | 114 | 28 | 0 | 0 | 0 | 1 | 4 | 2 | DAILY_DATA_STALE 28 |
| 41 | 09-30 | v8 | 62 | 16 | 0 | 0 | 0 | 0 | 3 | 1 | CANDLE_GAP 8 |
| 45 | 10-01 | v8 | 179 | 27 | 1 | 3 | 3 | 5 | 12 | 11 | LATE_BAR 14, 수신 지연 12 (당시 최대 1건 제한) |
| 49 | 10-02 | v8 | 25 | 9 | 0 | 0 | 0 | 0 | 1 | 2 | - |
| 53 | 10-06 | v8 | 117 | 21 | 0 | 0 | 0 | 0 | 6 | 4 | DAILY_DATA_STALE 21 |
| 59 | 10-07 | v8 | 84 | 17 | 0 | 0 | 0 | 0 | 3 | 2 | - |
| **61** | **10-08** | **v9** | 110 | 20 | 0 | 0 | 0 | 0 | 6 | 4 | LATE_BAR 20, LATEST_CANDLE_STALE 20 (실시간 공백) |
| **62** | **10-12** | **v9** | **0** | **0** | 0 | 0 | 0 | 0 | 0 | 0 | 스캐너 탐지 0건 |

- 앱이 보고하는 `decisionReason`은 첫 실패 조건만 보여 준다. 기회/위험 필터가 맨 앞이라 `OPPORTUNITY_OR_RISK_FILTERED`가 대부분을 덮는다. `analyze.py`는 readiness 필드로 모든 실패 조건을 다시 계산한다.
- `DAILY_DATA_STALE`(09-28, 09-29, 10-06은 후보 전원)는 연휴 직후 일봉 기준일과 달력의 이전 거래일이 어긋난 결과다. v8 시기의 달력·일봉 backfill 결함이며, 10-12 이후에는 KIS 개장 플래그 우선 적용(ac95bbb)으로 일부 바뀌었다.
- 비교용 이전 버전: LEGACY v5(09-15 9건, 09-16 5건)는 SELECTED가 있었다. v6 readiness 게이트 도입(09-17) 이후 v6/v7 REPLAY는 0건이다. 0건 경향은 v9 이전부터 이어져 왔다.

### v8 공식 9거래일 정밀 후보 223건의 실패 조건(중복 포함)

| 조건 | 건수 | 비율 | 구분 |
| --- | ---: | ---: | --- |
| LOW_FINAL_SCORE (< 55) | 195 | 87% | 전략 |
| OPPORTUNITY_LOW (스캐너 기회점수 < 35) | 175 | 78% | 전략 |
| DAILY_TREND_WEAK | 126 | 57% | 전략 |
| LATE_BAR_REVISION | 95 | 43% | 데이터·측정 |
| DAILY_DATA_STALE | 90 | 40% | 데이터 |
| CANDLE_GAP | 75 | 34% | 데이터 |
| OVEREXTENDED | 46 | 21% | 전략 |
| LOW_LIQUIDITY | 45 | 20% | 전략 |

대부분의 후보는 전략 게이트 2개 이상에서 실패한다. 다른 조건은 모두 통과하고 전략 게이트 하나만 막은 경우(데이터 게이트 무시 시)는 9일 동안 OVEREXTENDED 9건, OPPORTUNITY_LOW 5건, 기타 3건이었다. 이 숫자는 서술일 뿐 완화 근거로 쓰지 않는다. 바꾸려면 새 버전 사전 등록이 필요하다.

## v9 10-08 run 61 상위 10 후보 (최종점수 순)

| # | 종목 | 스캐너 | 점수 | 기회 | 위험 | 탐지 | 수신 | 마지막 사용 봉 | 보고 결과 | 실패 조건 전체 |
| --- | --- | --- | ---: | ---: | ---: | --- | --- | --- | --- | --- |
| 1 | 373220 | VOLUME | 61.8 | 37.0 | 2.6 | 14:45:00 | 14:45:02 | 14:55 | WATCH RECEIVED_AFTER_EVALUATION | LATE_BAR_REVISION, CANDLE_GAP, LATEST_CANDLE_STALE, OVEREXTENDED |
| 2 | 005160 | VWAP_BREAKOUT | 57.1 | 58.0 | 25.9 | 15:00:00 | 15:11:47 | 14:55 | WATCH RECEIVED_AFTER_EVALUATION | LATE_BAR_REVISION, LATEST_CANDLE_STALE, OVEREXTENDED |
| 3 | 353200 | VWAP_RECLAIM | 55.8 | 39.3 | 20.0 | 14:35:00 | 14:35:03 | 14:55 | WATCH STALE_FEATURE | STALE_FEATURE, LATE_BAR_REVISION, CANDLE_GAP, LATEST_CANDLE_STALE, OVEREXTENDED |
| 4 | 009730 | VWAP_BREAKOUT | 55.2 | 47.1 | 2.5 | 15:00:00 | 15:11:46 | 14:55 | WATCH RECEIVED_AFTER_EVALUATION | LATE_BAR_REVISION, DAILY_TREND_WEAK, LATEST_CANDLE_STALE, LOW_LIQUIDITY |
| 5 | 419050 | VWAP_BREAKOUT | 52.7 | 30.0 | 48.3 | 15:00:00 | 15:11:46 | 14:55 | EXCLUDED OPPORTUNITY_OR_RISK_FILTERED | OPPORTUNITY_LOW, LATE_BAR_REVISION, LATEST_CANDLE_STALE, OVEREXTENDED, LOW_LIQUIDITY, LOW_FINAL_SCORE |
| 6 | 006400 | PULLBACK_REBREAK | 48.1 | 23.4 | 0.0 | 14:30:00 | 14:30:02 | 14:55 | EXCLUDED OPPORTUNITY_OR_RISK_FILTERED | OPPORTUNITY_LOW, STALE_FEATURE, LATE_BAR_REVISION, CANDLE_GAP, LATEST_CANDLE_STALE, INTRADAY_REVERSAL, LOW_FINAL_SCORE |
| 7 | 018880 | VWAP_BREAKOUT | 44.8 | 19.7 | 6.2 | 14:55:00 | 14:55:02 | 14:55 | EXCLUDED OPPORTUNITY_OR_RISK_FILTERED | OPPORTUNITY_LOW, LATE_BAR_REVISION, LATEST_CANDLE_STALE, OVEREXTENDED, LOW_FINAL_SCORE |
| 8 | 196170 | VWAP_BREAKOUT | 37.6 | 13.3 | 2.7 | 14:55:00 | 14:55:02 | 14:55 | EXCLUDED OPPORTUNITY_OR_RISK_FILTERED | OPPORTUNITY_LOW, LATE_BAR_REVISION, DAILY_TREND_WEAK, LATEST_CANDLE_STALE, LOW_FINAL_SCORE |
| 9 | 005930 | TURNOVER_BREAKOUT | 26.2 | 17.4 | 8.0 | 14:50:00 | 14:50:01 | 14:55 | EXCLUDED OPPORTUNITY_OR_RISK_FILTERED | OPPORTUNITY_LOW, LATE_BAR_REVISION, LATEST_CANDLE_STALE, LOW_FINAL_SCORE |
| 10 | 115450 | VOLUME | 24.1 | 45.9 | 35.9 | 14:35:00 | 14:35:01 | 14:55 | WATCH STALE_FEATURE | STALE_FEATURE, LATE_BAR_REVISION, CANDLE_GAP, LATEST_CANDLE_STALE, INTRADAY_REVERSAL, OVEREXTENDED, LOW_FINAL_SCORE |

데이터가 정상이었어도 상위 4건은 OVEREXTENDED(일봉 MA20 대비 12% 초과) 또는 일봉 추세·유동성 조건에 걸린다. 다만 실시간 공백 때문에 15:00~15:10 봉 기반 값(최신 봉, 신호 유지, 탐지)은 실제와 다르게 계산됐다. 그래서 이 날의 전략 판정 자체도 신뢰도가 낮다. 다른 run의 상위 10은 `results.json`의 `runs[].top10`에 있다.

## v9 경계 봉 문제의 근거

`boundary_bar.sql`: 동결 시각에 끝나는 봉이 유예 안에 저장됐는지 본다. 종목 수 기준이며, 걸림은 확정 시각이 `동결+유예` 이후이고 run 실행 이전인 경우다.

| 날짜 | v8 경계(14:55 봉) 15:00:10까지 저장 | v9 경계(15:10 봉) 15:15:00까지 저장 | v9 run이 15:15:15에 돌았다면 걸리는 종목 |
| --- | --- | --- | --- |
| 09-22 | 41/42 | 0/42 | 41 |
| 09-23 | 0/43 | 0/41 | 41 |
| 09-28 | 2/41 | 11/41 | 30 |
| 09-29 | 41/41 | 0/41 | 41 |
| 09-30 | 23/24 | 3/22 | 18 |
| 10-01 | 13/29 | 0/29 | 29 |
| 10-02 | 26/26 | 0/26 | 26 |
| 10-06 | 28/28 | 1/28 | 27 |
| 10-07 | 26/26 | 5/26 | 21 |
| 10-08 (v9) | - | 1/31 | 30 (실제 run 시각 기준 30) |
| 10-12 (v9) | - | 0/26 | 26 (실제 run 시각 기준 26) |

- 15:15:00 이전에 저장된 일부 15:10 봉(09-28 11개 등)은 호스트 시계가 늦었던 날에 생겼다([clock-skew audit](../2026-10-12-clock-skew/README.md)). 정상 동작이 아니다.
- 경계 탐지: 09-29 이후 탐지는 봉 마감 시각에 찍힌다. v9 창에서 종목의 대표 탐지(14:30~15:15 중 최신)가 15:15:00 탐지인 종목은 09-29 32/40, 09-30 7/18, 10-01 6/28, 10-02 12/20, 10-06 8/22, 10-07 4/19이었다. 이들의 수신시각은 모두 15:15:00 이후다(`results.json` `detectionSupply`).
- 기회·위험 1차 필터를 통과한 대표 탐지 수(ETF 제외, 30분 이내)는 v8 창(14:30~15:00)과 v9 창(14:30~15:15)이 비슷하다(일 1~13 vs 1~11). 평가 시각을 15:15로 옮겨서 후보 공급이 줄어든 것은 아니다.

## 데이터 결함 vs 전략 판정

| 원인 | 성격 | 영향 | 고치면 되살아나는 선정(추정) |
| --- | --- | --- | --- |
| 10-12 스캐너 탐지 0건 | 데이터(운영) | 그날 정밀 후보 0 | 그날 0~1건 (v8 평균 0.89건/일 기준) |
| 10-08 14:58~15:11 실시간 공백 | 데이터(운영) | 후보 20건 전원 late/stale | 0건. 데이터 게이트를 무시해도 전략 게이트에서 전원 탈락 |
| v9 경계 봉(15:10) + 경계 탐지(15:15:00)를 "늦은 수정"으로 처리 | 측정(코드·설정 상호작용) | 정상일에도 정밀 후보의 73~100%가 WATCH | v8 기준 상한 약 0.89건/일. 없애지 않으면 사실상 0 |
| 연휴 후 DAILY_DATA_STALE | 데이터(달력·일봉) | 해당일 후보 전원 WATCH | 09-29 1건 |
| 기회점수 < 35, 최종점수 < 55, 일봉 추세, 과열 | **전략 임계값** | 후보의 78~87% 탈락 | 바꾸면 새 버전 |

경계 봉 처리는 "평가 시점에 없던 입력을 쓰지 않는다"는 시각 원칙을 지키는 방향으로 고칠 수 있다. 없던 봉과 탐지는 늦은 수정으로 표시하지 말고 제외하고, 그 직전 확정 입력으로 평가하면 된다. 다만 결과 선정이 달라지므로 ADR-008 "판단 기간 동안 고정할 것(필터·평가 시각)"에 해당한다. 유예 값을 다시 두는 방법(예: v8처럼 수 초~10초)도 평가 입력이 바뀌므로 같다. 현재 v9 1차 표본은 0건이라 버전을 올려도 잃는 표본은 없다.

## 사용자 결정이 필요한 것

1. **경계 처리 수정을 새 버전(v9.1 등)으로 등록할지.** 임계값과 점수는 그대로 두고, 동결 시각에 끝나는 봉과 탐지를 "제외" 또는 "유예 N초"로 처리하는 규칙만 ADR로 먼저 고정한다. 결과를 보고 N을 고르지 않는다.
2. **10-12 스캐너 정지 원인 확인과 감시.** Redis 가용성과 `ScannerEngine.acquire`의 조용한 실패를 확인한다. `closing_daily_health`에 "당일 14:30~15:15 탐지 0건" 같은 판정 불가 사유를 추가할지 정한다.
3. **ADR-008 표본 정의와 기간 가정.** 경계 문제를 고쳐도 기대치는 약 0.9건/일이라 60건까지 수개월이 걸린다. (a) 그대로 기다리거나, (b) 임계값 변경을 새 버전으로 사전 등록하거나(결과를 보고 고르지 않는다), (c) [judge 도구](../closing-v9-judge/README.md)가 지적한 대로 1차 표본을 "평가된 전체 후보의 paper entry"로 다시 정의한다. 어느 쪽이든 새 ADR이 필요하다.
4. 다음 거래일 15:20/15:25 봉 누락(sibling 작업)과 진입 종목의 다음날 구독은 선정이 생겨도 완료 0건을 만들 수 있다. 같이 해결해야 한다.

## 한계

- v9 정상 데이터일이 아직 없어서 v9 시각(15:15)의 전략 통과율은 v8(15:00) 스냅샷으로 근사했다. 점수의 `closingRecency`는 늦은 탐지에 가산점을 주므로 15:15 평가에서는 점수 분포가 조금 다를 수 있다. 앱 REPLAY는 run을 새로 쓰기 때문에 실행하지 않았다.
- "데이터 게이트 무시"는 결함이 없었다면 같은 readiness 값이 나왔을 것이라고 가정한다. 10-08처럼 봉이 빠진 날에는 최신 봉, 신호 유지, 탐지 자체가 달라질 수 있다.
- 10-12 스캐너 정지 원인은 로그가 없어 가설로만 남긴다.

## 재현

```powershell
cd audit\2026-10-13-closing-zero-selected
$jar = (Get-ChildItem <main checkout>\backend\.gradle-user-home -Recurse -Filter postgresql-42.7.11.jar | Select-Object -First 1).FullName
foreach ($q in "runs_inventory","snapshots","selected","detections","detections_daily","candles","boundary_bar","scanner_settings","paper_entries") {
  java -Xmx256m -cp $jar ExportQuery.java <path\to\.env> "$q.sql" "data\$q.tsv" }
python -I analyze.py data results.json
```

- `ExportQuery.java`: `.env`에서 DB_URL/DB_USERNAME/DB_PASSWORD만 읽고 값은 출력하지 않는다. `setReadOnly(true)`, `REPEATABLE READ, READ ONLY`, 마지막에 rollback. `intraday_input`은 읽지 않는다.
- `data/`(gitignore): raw export, 약 6.5MB(스냅샷 17개 6.3MB). `results.json`: run별 분해, 상위 10, 경계 봉·탐지 집계.
- export 시각: 2026-10-13 12:48~12:55 KST. 10-13 15:15 run은 포함하지 않는다.
