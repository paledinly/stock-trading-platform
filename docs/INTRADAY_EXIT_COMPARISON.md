# 장중 동일 진입 청산 비교

## 구현 범위

기존 추천/입력 원장을 재사용해 관측 품질 분류, 공통 비용 민감도, 동일 진입 청산 비교를 제공한다. DB migration, 외부 API 요청, 새 주문, 운영 threshold 변경은 없다. Backend와 Web을 재시작/배포한 뒤 장중 추천 하단에서 실행한다. 원장 전체를 페이지 단위로 읽으므로 대량 데이터 비교는 장 종료 후 한 번씩 수행하는 것을 권장한다.

## 요청

`POST /api/v1/intraday/exit-comparison?date=2026-10-06`

```json
{
  "variants": [
    {"name": "STOP_1_5", "stopDistanceMultiplier": 1.5, "targetDistanceMultiplier": 1},
    {"name": "TARGET_0_75", "stopDistanceMultiplier": 1, "targetDistanceMultiplier": 0.75},
    {"name": "PROTECT", "stopDistanceMultiplier": 1, "targetDistanceMultiplier": 1,
     "protectAtPercent": 0.5, "protectFloorPercent": 0.1}
  ],
  "cohortBoundary": "2026-10-06T01:42:38Z"
}
```

위 숫자는 요청 형식 예시이며 채택할 운영값이 아니다. `cohortBoundary`는 사용자가 지정한 운영 변경 경계다. 자동으로 장애 개선 시각을 추론하지 않는다. 최대 12개 실험을 허용하며 BASELINE은 자동으로 포함된다. 빈 variants는 기존 청산의 재현·품질 점검만 수행한다.

Web은 한 개 실험과 기존안을 비교하고 당시 비용을 사용한다. ‘진입·청산 비교 실험’의 ‘추가 실험 사용’에서 최소 손절폭, 돌파 확인, 부분 익절/추적 손절, 재진입 조건, 종목별 가격 단위를 지정할 수 있다. 여러 실험·공통 비용·변경 전후 구분은 API로 지정한다.

## 고정되는 것과 변하는 것

- 기존 종목·추천시각·진입 조건·가상 진입시각·원체결 가격·기존 진입 fill을 고정한다. 진입한 Paper만 청산 실험에 포함하고 미진입은 paperCohorts에 따로 남긴다.
- 원장을 ID 순서로 읽어 기존 Tracker로 진입을 재현한다. 저장된 진입시각·원가격·fill과 다르거나 진입 원장이 없으면 `ENTRY_NOT_REPRODUCIBLE`로 제외한다. 저장된 성과를 가짜 체결로 넣지 않는다.
- 손절선 = 진입 fill − (진입 fill − 기존 손절선) × 손절 거리 배수.
- 목표선 = 진입 fill + (기존 Target 1 − 진입 fill) × 목표 거리 배수.
- 배수 1은 원래 가격선이며, 손절 거리 변경이 목표 거리나 Setup 판정에 영향을 주지 않는다. 참고 Target 2는 그대로이며 청산에 사용하지 않는다.
- 보유기한·마감 청산·누락 기준·기존 trailing-after-target 정책은 당시 정책을 유지한다. 최소 R:R gate로 후보를 다시 제거하지 않는다.
- 보호 시작은 진입 fill 기준 관측 MFE(%)가 기준에 도달했을 때다. 보호선은 진입 fill × (1 + 보호선%/100). 다음 관측 틱부터 유효하며 실제 관측가격으로 청산하므로 갭이나 비용에 의해 보호선 아래에서 종료할 수 있다.
- 종목별 명시적 가격 단위는 아래 추가 실험으로 지원한다. 과거 종목 유형/가격구간별 공식 호가 단위 자동 매핑·거래가능 수량은 아직 모델링하지 않는다.

## 4~7단계 추가 실험 (options)

각 variant에 options를 선택적으로 추가한다. 생략하면 기존 API와 동일하다. 아래 값은 형식 예시이지 권장 운용값이 아니다. 원인 분리를 위해 한 요청의 각 variant에는 한 종류의 변경부터 적용한다.

```json
{
  "variants": [
    {"name":"MIN_STOP", "stopDistanceMultiplier":1, "targetDistanceMultiplier":1,
     "options":{"minimumStopPercent":0.3,"narrowStopMode":"WIDEN"}},
    {"name":"HOLD", "stopDistanceMultiplier":1, "targetDistanceMultiplier":1,
     "options":{"entryMode":"HOLD","holdSeconds":20}},
    {"name":"RETEST", "stopDistanceMultiplier":1, "targetDistanceMultiplier":1,
     "options":{"entryMode":"RETEST","retestTolerancePercent":0.1}},
    {"name":"PARTIAL", "stopDistanceMultiplier":1, "targetDistanceMultiplier":1,
     "options":{"partialAtPercent":0.5,"partialFraction":0.5,"trailingDistancePercent":0.3}},
    {"name":"REENTRY", "stopDistanceMultiplier":1, "targetDistanceMultiplier":1,
     "options":{"maxStoppedPerSymbol":2,"requireFreshStructureAfterStop":true}}
  ]
}
```

### 가격·최소 손절폭

`tickSizes`는 `{ "005930": 100 }` 형태의 종목별 고정 단위 가정이다. 손절·보호·추적 손절은 아래로, Target 1·부분 익절선은 위로 정규화한다. 원체결/진입 fill과 참고 Target 2는 변경하지 않는다. 빈 map이면 정규화하지 않으며, map에 없는 종목은 `PRICE_GRID_UNAVAILABLE`로 남긴다. 가격구간 변경/상품별 다른 호가 체계를 자동 추정하지 않으므로 공식 주문가격 검증으로 사용하면 안 된다.

`minimumStopPercent`는 진입 fill 기준 최소 손절폭이다. WIDEN은 최초 손절폭을 확대하고 FILTER는 원래 진입 가능한 가격에서 최소폭 미달 후보를 제외한다. 0이면 끈다. 목표선은 독립 배수로 유지한다. Setup의 VWAP 지지 판정에 쓰는 기존 stopBufferFraction을 변경하지 않으므로 손절만 바꾸는 실험에서 Setup이 함께 바뀌지 않는다.

`riskUnits`에는 초기 손절폭과 Gross 수익률/초기 손절폭을 제공한다. 서로 다른 손절폭의 위험 대비 수익을 비교하는 기술통계이며 고정 위험 예산으로 실제 주식 수를 계산하거나 자금 제약을 모델링한 계좌 손익은 아니다.

### 돌파 진입 확인

entryMode는 IMMEDIATE(기본), HOLD, RETEST다. HOLD는 추천 후 연속 관측에서 돌파선 위를 holdSeconds 동안 유지해야 진입한다. 선 아래로 내려오면 유지 시간을 초기화한다. RETEST는 서로 다른 관측으로 선 위 → 허용 폭 안으로 재접근 → 허용 폭 상단 회복 순서를 요구한다. 허용 폭 아래로 이탈하면 순서를 초기화한다. 원래 Entry 구간/추격 제한/추천 만료/누락 기준은 그대로 적용한다. PULLBACK은 돌파 확인 조건의 적용 대상이 아니다.

새 추천은 setupSupport/setupResistance/setupRange를 Feature에 저장한다. 과거 BREAKOUT의 돌파선은 당시 plan과 정책의 정확한 산식을 역산한다. 기준선이 없는 과거 RE_BREAKOUT은 `BREAKOUT_REFERENCE_UNAVAILABLE`이다. 미래 가격으로 기준선을 만들지 않는다.

진입 확인·FILTER·재진입 조건 중 하나라도 사용하면 응답 mode는 `FIXED_CANDIDATE_ENTRY_EXIT_COMPARISON`이다. 저장된 모든 후보(기존 미진입 포함)를 동일 Universe로 사용한다. 조건 변경으로 새로 생길 추천 자체를 재생성하는 전체 전략 실험은 아니다. 기본 청산 비교는 `FIXED_ENTRY_EXIT_COMPARISON`이며 기존 진입 거래만 사용한다.

### 부분 익절·추적 손절

partialAtPercent는 진입 fill 대비 익절 시작 %, partialFraction은 0보다 크고 1보다 작은 청산 비중이다. 부분 목표는 정규화 후에도 최종 Target 1보다 낮아야 한다. 부분 물량과 잔여 물량을 같은 Tracker로 따로 추적하고 각각 실제 관측가격과 비용으로 청산한다. 부분 물량은 목표에서 청산하며, 잔여 물량에는 기존 Target 1/시간 청산/손절 정책이 적용된다.

trailingDistancePercent는 수익 보호 시작 또는 부분 익절 확인 후 관측가격에서 떨어진 거리 %다. 추적선은 다음 틱부터 유효하고 내려가지 않는다. 결과 grossReturn/netReturn/exitPrice는 청산 비중 가중값이며 exitAt/holdingMinutes/status/targetBeforeStop은 잔여 물량 최종 청산 기준이다. tradeMfe/tradeMae는 비중 조정 전 가격 경로의 극값이다. `partialExits`에 부분 물량의 실제 청산시각·비중·당시 비용 기준 성과를 별도로 제공한다. costs override는 합산 Net에 적용한다.

부분 익절 후 잔여 물량의 관측이 끊기면 합산 수익은 null이고 `PARTIAL_PATH_UNRESOLVED`다. 이미 실현된 부분 성과는 partialExits에 남는다. 소수 비중의 가상 포지션이며 정수 주식 수/최소 수수료/호가 체결 수량은 모델링하지 않는다.

### 재진입 제한

maxStoppedPerSymbol은 해당 비교안에서 후보 추천시각까지 확인된 같은 종목의 당일 STOPPED 횟수 상한이다. 0이면 끈다. requireFreshStructureAfterStop을 켜면 직전 손절 추천보다 높은 돌파선과 더 큰 volumeRatio5m/valueRatio5m을 모두 요구한다. 기준 자료가 없으면 `REENTRY_REFERENCE_UNAVAILABLE`로 남긴다. 저장된 최종 손절 결과를 읽어 과거 재진입을 차단하지 않는다.

진입 변경 실험에서 앞선 동일 종목 포지션이 계속 열려 있으면 신규 진입을 대기한다. 추천 만료시간은 연장하지 않는다. 후보 제외는 FILTERED/사유로 구분하고 미진입을 손실 0으로 평균에 넣지 않는다. `missedBaselineWinners`는 기본안에서 확정 Gross 수익이 났으나 실험에서는 확정 미진입으로 종료한 후보 수다. 공통 완료 표본만으로 진입 필터의 우월성을 판단하지 않는다.

## 비용

costs를 생략하면 추천 당시 비용을 사용한다. 비용 미설정이면 Gross만 계산하고 Net은 null이다. 시장 세율/수수료를 임의로 채우지 않는다.

API의 선택적 `costs`에는 `buyFeePercent`, `sellFeePercent`, `sellTaxPercent`, `spreadPercent`, `buySlippagePercent`, `sellSlippagePercent` 여섯 값을 모두 명시한다. 단위는 %이며 0은 명시적 무비용 가정이다. 이 경우 동일 원체결 진입·청산가격에 모든 비교안의 공통 비용을 적용해 Net만 다시 계산한다. 기존 entryPrice/exitPrice는 당시 fill이고, override Net은 별도 비용 민감도다. 비용 때문에 진입 가능 여부가 달라지는 전체 전략 실험은 기존 `/replay`를 사용한다. 응답 request에 비용 가정이 남지만 DB에 별도 저장하지 않으므로 응답을 보관해야 재현할 수 있다.

## 결과 해석

- paperCohorts: 원래 추천의 상태, 미진입/진행/완료/불확실, 비용 설정 여부, 선택적 진입시각 경계 전후 건수.
- comparisons: BASELINE과 각 실험의 상세 결과, 품질 분류, 기존 Setup/시간대/정책별 Net 통계.
- commonCompletedCount / commonCompleted: 모든 비교안이 확정 청산한 동일 추천 ID의 교집합. 평균 Gross, 목표선행률, Net 표본 수·승률·평균 Net·PF·보유시간 등을 제공한다. 평균 수익률은 동일 명목 거래의 기술통계이며 계좌 수익률이 아니다.
- 보유 중 수신 누락/지연/거래정지는 판정 불가로 남긴다. 기존안 청산 후 누락이 생겼더라도 그 기존 청산을 무효화하지 않는다. 반면 당시 계속 보유 중이던 실험안은 불확실해질 수 있다.
- 공통 표본만 좋아지고 판정 불가가 늘어나는 선택 편향을 피하려면 각 비교안의 전체 품질 건수도 함께 확인한다.
- 요청 시작의 원장 ID와 평가시각을 상한으로 사용하며 이후 수신 자료를 더하지 않는다. 장중 요청은 진행 중 표본이 남으므로 최종 일간 비교는 장 종료 후 실행한다.

## 다음 작업

실제 비용 가정 확정 및 정상 수집 기간의 일별 비교 결과 보관이 우선이다. 다음으로 Phase 2/3 관측을 장중 판단에 연결하고, 공식 과거 가격 단위/정수 수량/자금 제약을 보완한다. 이번 추가 실험의 운영 기본값 채택과 수익성 주장은 별도 기간 OOS/Forward 검증 이후에만 한다.
