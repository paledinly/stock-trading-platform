# closing v9 ADR-008 판정 도구

[ADR-008](../../docs/decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md)에 사전 등록한 기준으로 `closing-recommend-v9-1515`의 두 비교안(BASELINE, NEXT_OPEN)을 반복 계산하는 도구다. 앱 코드와 DB를 수정하지 않는다. 이 폴더는 도구이고, 체크포인트 판정 결과는 날짜별 폴더 `audit/YYYY-MM-DD-closing-v9-checkpoint/`에 저장한다.

## 구성

| 파일 | 역할 |
| --- | --- |
| [ExportClosingV9.java](ExportClosingV9.java) | 읽기 전용 export. `strategy_version='closing-recommend-v9-1515'` 이고 `execution_mode='FORWARD'`인 run만 읽는다(LEGACY/REPLAY 제외). |
| [judge.py](judge.py) | BASELINE/NEXT_OPEN 재계산, 통계, ADR 판정. 표준 라이브러리만 사용. `--selftest` 포함. |
| `data/` | export 결과(TSV). gitignore 대상이며 커밋하지 않는다. |
| [status-2026-10-13.json](status-2026-10-13.json) | 2026-10-13 12:17 KST 시점 상태 요약(체크포인트 판정 아님). |

### export 내용 (`data/`)

- `entries.tsv`: v9 FORWARD run의 `closing_paper_entry` 전체 행. run 정보, 진입 상태·시각(원천 `source_at`, 수신 `received_at`, 가상 체결 `entered_at`)·가격, `closing_recommendation`(SELECTED만 존재) 순위·점수, `overnight_performance` 공식 저장값, run `response_snapshot`의 후보 disposition(SELECTED/WATCH/EXCLUDED).
- `bars.tsv`: ENTERED 종목의 `stock_candle` 5M, 추천일 다음 날부터 10일, KST 09:00~15:30 시작 봉만.
- `calendar.tsv`: `trading_calendar_day`(첫 v9 run 날짜 이후).
- `run_inventory.tsv`: 버전·모드별 run 수(제외 대상 확인용). `meta.tsv`: DB 기준 export 시각.

`intraday_input`은 읽지 않는다. 한 번 export 크기는 수백 KB 수준이다.

## 실행

```powershell
cd audit\closing-v9-judge
$jar = (Get-ChildItem ..\..\backend\.gradle-user-home -Recurse -Filter postgresql-42.7.11.jar | Select-Object -First 1).FullName
# 다른 worktree라면 메인 체크아웃의 .gradle-user-home 경로를 지정한다.
java -Xmx256m -cp $jar ExportClosingV9.java <path\to\.env> data
python -I judge.py --selftest
python -I judge.py --data data              # 요약
python -I judge.py --data data --details    # 건별 행 포함
# 체크포인트 도달일 기록
python -I judge.py --data data --details --save-dir ..\YYYY-MM-DD-closing-v9-checkpoint
```

- `.env`는 경로로만 넘긴다. 프로그램은 DB_URL/DB_USERNAME/DB_PASSWORD만 읽고 어떤 값도 출력하지 않는다. 연결은 `setReadOnly(true)`, `REPEATABLE READ, READ ONLY` 트랜잭션, 마지막에 rollback.
- 평가 기준 시각은 export 시각(DB `now()`)이다. `--now`로 바꿀 수 있지만 export 이후 시각을 주면 그 사이 저장된 봉은 반영되지 않는다.
- 체크포인트 기록 시 `--save-dir` 폴더에 `judge_output.txt`, `judge_summary.json`이 생긴다. 같은 폴더에 짧은 README(날짜, export 시각, 판정, 특이사항)를 함께 남긴다. raw TSV는 커밋하지 않는다.

## 계산 규칙

### 표본

- **1차 표본(판정 대상)**: v9 FORWARD run에서 SELECTED(=`closing_recommendation` 행이 있는) 후보의 paper entry. ADR-008이 "하루 최대 5건"을 전제하고 관측 후보를 2차 표본으로 분리하므로 이렇게 해석했다.
- **2차 표본(참고)**: 같은 run의 WATCH/EXCLUDED 후보 paper entry. `ClosingRecommendationService`가 평가한 모든 후보에 paper entry를 만들기 때문에 존재한다. 통계는 출력하지만 판정에 합산하지 않는다.
- 해석 주의: ADR 문구 "1차 표본: v9 공식 FORWARD run의 `closing_paper_entry` ENTERED 건"을 문자 그대로 읽으면 2차 표본도 포함된다. 판정 전에 ADR에서 어느 쪽인지 명시해야 한다. 이 도구는 두 표본을 분리해 둘 다 보여준다.

### 세션 완료와 결측 (OvernightPerformanceService와 동일)

- 다음 거래일: `overnight_performance.expected_session_date`가 있으면 그 값, 없으면 `trading_calendar_day` → 주말 → 정적 휴장일(judge.py `STATIC_HOLIDAYS`) 순서. 정적 목록은 공식 달력이 아니며 `trading_calendar_day`가 없는 과거 날짜용이다.
- 다음 세션 15:30 KST 이전이면 미종료로 보고 표본·결측 비율 모두에서 제외한다.
- 봉 필터: `is_final`, 시작 09:00~15:30, 09:00 기준 5분 정렬, 봉 종료 ≤ 평가 시각, 가격 정합성(low>0, high ≥ max(open,close), low ≤ min(open,close)).
- 상태: 봉 없음 `DATA_MISSING`, 09:00~15:25 시작 봉 중 하나라도 정확히 1개가 아니면 `DATA_INCOMPLETE`, 모두 있으면 `COMPLETED`. 진입하지 않은 건은 `NO_ENTRY`.
- **완료 = COMPLETED**. 두 비교안은 같은 완료 건에서 계산한다. 결측/판정불가 비율 = (세션 종료된 표본 중 COMPLETED가 아닌 건) / (세션 종료된 표본). 20% 초과면 체크포인트 판정을 미룬다.

### 비교안

- BASELINE: `OvernightExecutionSimulator.targetOrStop`(overnight-execution-v1)을 그대로 옮겼다. 진입가 = paper entry `price`, 목표 +3% / 손절 -2%(10자리 HALF_UP), 봉마다 시가 ≤ 손절이면 시가(GAP_STOP), 시가 ≥ 목표면 시가(GAP_TARGET), 한 봉에서 둘 다 닿으면 손절가(AMBIGUOUS_STOP), 아니면 마지막 봉 종가(TIME_EXIT).
- NEXT_OPEN: 다음 세션 09:00 봉 시가 전량 청산.
- Net: `(청산가×(1−매도비용) − 진입가×(1+매수비용)) / (진입가×(1+매수비용))`, 시뮬레이터와 같은 반올림.
- 불확실: AMBIGUOUS_STOP 또는 해당 비교안 계산 불가 건 수. 손익 보정 없이 그대로 포함한다.

### 비용 (%)

| 시나리오 | 매수 | 매도 | 비고 |
| --- | --- | --- | --- |
| BASE | 0.095 | 0.295 | ADR-008 값(`.env` CLOSING_*). 가정 분해: 수수료 0.015, 매도세 0.20, 나머지(spread+slippage) 편도 0.08 |
| HIGH | 0.165 | 0.365 | 수수료·세금 유지, 편도 slippage 0.15로 교체 |

HIGH 분해는 ADR의 "slippage 0.15%"를 편도 비수수료 비용으로 해석한 것이다. 다르게 정하려면 judge.py `COSTS`를 바꾸고 판정 기록에 명시한다.

### 통계와 판정

- 평균 Net(BASE, HIGH), Net PF(이익 합 / 손실 합 절대값), 승률.
- 거래일 블록 부트스트랩: 추천일 단위로 복원 추출 10,000회, seed 20261013, 통계량은 추출된 날들의 전체 건 평균. 95% 구간은 2.5/97.5 백분위.
- 상위 3거래일 제외 평균: 일별 Net 합이 가장 큰 3일을 빼고 남은 건 평균.
- 체크포인트: 완료 60건 AND 15거래일(1차), 120건 AND 30거래일(연장). 미도달이면 "체크포인트 미도달"과 중간 중단 조건(30건 이상이고 95% 상한 < 0)만 출력한다.
- 판정표(비교안별): 중단(평균 ≤ 0 또는 PF < 1.0) / 실거래 검토(평균 > 0, 하한 > 0, PF ≥ 1.2, HIGH 평균 > 0, 상위 3일 제외 평균 > 0) / 그 외 계속. 연장 체크포인트에서 "계속"은 중단으로 출력한다. 도구는 이전 체크포인트 이력을 모르므로 1차 판정이 "계속"이었는지는 기록으로 확인한다.
- 공식 저장 성과(`overnight_performance` COMPLETED, net 있음)가 있으면 그 행의 `cost_assumption`으로 BASELINE을 다시 계산해 진입가·청산 사유·청산가·Net 불일치를 출력한다.

## 해석 시 주의

- 추천 점수는 상승 확률이 아니다. 판정 "실거래 검토"도 수익성 확정이 아니라 소액 수동 검증 계획을 세우는 단계다.
- 판정 기간 중 v9 점수·필터·평가 시각·진입 모델·비용·구독 우선순위를 바꾸면 표본을 0부터 다시 센다.
- 2차 표본 숫자로 판정하지 않는다.

## 2026-10-13 12:17 KST 상태 (체크포인트 아님)

- v9 FORWARD run 2개(10-08 run 61, 10-12 run 62). LEGACY/REPLAY와 v8 이하 run은 제외됨(`run_inventory.tsv`).
- **1차 표본 0건**: 두 run 모두 SELECTED 추천이 없다(`closing_recommendation` 0행, 공식 `overnight_performance` 0행). 판정: 두 비교안 모두 체크포인트 미도달, 중간 중단 조건 미해당.
- 2차 표본 113건(10-08 62건, 10-12 51건). paper entry ENTERED 42건(10-08 27, 10-12 15, 전부 WATCH/EXCLUDED).
  - 10-12 run의 15건은 다음 세션(10-13)이 진행 중이라 미종료로 제외.
  - 10-08 run(다음 세션 10-12, 10-09 한글날 휴장)의 62건 중 NO_ENTRY 35, DATA_MISSING 16, DATA_INCOMPLETE 11 → 완료 0건, 결측 100%.
- 발견된 구조적 문제(판정 규칙은 바꾸지 않음):
  1. 10-01 이후 저장된 5M 봉에 **15:20, 15:25 시작 봉이 한 종목도 없다**(09-28~09-30은 일부 존재). 현재 COMPLETED 정의는 09:00~15:25 전 봉을 요구하므로, 이 상태가 계속되면 SELECTED 추천이 생겨도 공식 성과와 이 도구 모두 완료 0건이 된다. 10-12 DATA_INCOMPLETE 11건 중 2건은 이 두 봉만 빠졌다.
  2. 5M 봉은 실시간 구독 종목에만 쌓인다. 10-08 ENTERED 27건 중 16건은 10-12 5M 봉이 전혀 없었다. 다음 거래일에도 진입 종목을 구독·저장하지 않으면 결측 20% 기준에 걸린다.
  3. SELECTED 0건이 이어지면 체크포인트(60건)에 도달할 수 없다.
