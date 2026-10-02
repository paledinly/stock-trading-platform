# Phase 2 투자자 수급 관측

추천 점수와 선정 필터는 유지한다. 누적 수급과 관측 변화량을 Trajectory Snapshot의 investorFlow에 연결하는 연구용 구현이다. 기본 비활성이며 운영 설정 변경·서버 재시작·실계정 호출은 수행하지 않았다.

## 공식 API 확인 (2026-10-02)

- [외국인·기관 가집계](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/foreign_institution_total/foreign_institution_total.py): `foreign-institution-total`, TR `FHPTJ04400000`. 순매수/순매도 방향을 각각 조회해 응답 범위 내 관측 대상만 저장한다. 전체 종목을 보장하지 않으며 응답 밖은 결측이다.
- [종목별 추정 가집계](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/investor_trend_estimate/investor_trend_estimate.py)도 제공되나 이번에는 입력구분별 행 순서를 추측하지 않고 위 일괄 응답을 사용한다. 외국인 입력 예정은 09:30/11:20/13:20/14:30, 기관은 10:00/11:20/13:20/14:30이며 변동 가능하다. 빈번한 조회가 원천 갱신을 뜻하지 않는다.
- [프로그램 추이](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/program_trade_by_stock/program_trade_by_stock.py): `program-trade-by-stock`, TR `FHPPG04650101`, 시장 `J`(KRX). `bsop_hour` 기준 미래가 아닌 최신 행의 `whol_smtn_ntby_qty`를 선택한다.
- [공식 유량 공지](https://apiportal.koreainvestment.com/community/10000000-0000-0011-0000-000000000001/post/d0d1a83f-6f8d-4437-9700-6d26702fd989)의 검색 노출 본문은 실전 계좌당 초당 18건, 실시간 합산 41건을 안내한다. 개별 계정 제한·변경 공지는 운영자가 확인해야 한다. 이번 확인에서 두 TR의 독립 분당 제한 및 모의 제공을 확인하지 못해 추측하지 않는다. 수집기는 실전 API 호스트에서만 실행한다.

## 수집·저장

`CLOSING_TRAJECTORY_ENABLED=true`와 `CLOSING_TRAJECTORY_FLOW_ENABLED=true`가 모두 필요하다. 거래일 09:00~analysisEnd, 기본 5분 fixed-delay. 최근 6분 안에 1분봉이 있는 최대 20종목을 대상으로 한다. 대상 초과 시 최근 봉 시각과 종목코드 순이며 전체 Universe를 보장하지 않는다.

주기당 최대 2+20=22 REST 요청(재시도 제외), 기존 공통 KisRequestExecutor의 pacing/429 backoff 공유. WS 구독 추가 없음. 각 응답을 한 번 처리하고 Feature별 재호출하지 않는다. 한 endpoint 실패는 다른 endpoint 수집을 막지 않는다. 인증정보나 응답 전문을 로그에 출력하지 않는다.

기존 `closing_context_observation`과 수집 큐/재시도 경로를 재사용하며 신규 migration은 없다. Context JSON에 sourceAt/status/unit을 추가했다. 기존 JSON의 추가 필드는 null로 읽힌다. kind는 FOREIGN_NET_BUY / INSTITUTION_NET_BUY / PROGRAM_NET_BUY다.

- 외국인·기관: scope=KIS_ESTIMATE_ALL, unit=SHARES, sourceAt=null, status=ESTIMATED_SOURCE_TIME_UNKNOWN. 금액을 주수로 바꾸거나 가집계를 확정값으로 취급하지 않는다. 해당 응답에 원천 일자/시각이 없어 당일 데이터 여부·시장 범위는 실응답 대조가 필요하다.
- 프로그램: scope=KRX, unit=SHARES, status=INTRADAY_PROVISIONAL_DATE_FROM_REQUEST. API의 시각과 요청 거래일을 결합하며 원천 일자가 제공됐다고 주장하지 않는다. 응답 시각이 수신보다 미래이면 배제한다.

## DTO 의미와 시점 제한

foreign/institution/program 각각 NetBuyToday, NetBuy30mDelta, Observation을 제공한다. Observation은 scope/unit/status/receivedAt/sourceAt/baselineReceivedAt/deltaMeaning을 보존한다. 원본 Context 이력은 GET `/api/v1/closing-trajectory/flow?symbol=005930&date=YYYY-MM-DD`, 상태는 GET `/api/v1/closing-trajectory/flow/status`로 조회한다. 기존 Snapshot API의 investorFlow에 계산값을 제공한다.

30분 변화량은 평가 cutoff와 cutoff-30분 각각 이전의 마지막 관측치 차이다. 같은 종목·scope·수량 단위·거래일만 비교하며 기본 contextMaxAge=6분을 넘거나 기준 관측이 없으면 null이다. 프로그램은 sourceAt의 신선도도 검사한다. 미래 수신, 이전 거래일 값, 다른 시장 범위를 섞지 않는다. 나중에 조회한 과거 프로그램 행을 과거 수신처럼 저장하지 않는다.

가집계의 차이는 입력/정정 변화도 포함할 수 있고, 변화 0은 실제 30분간 매매가 없었다는 뜻이 아니다. sourceAt 미제공이므로 응답을 최근에 받았다는 것만 확인할 수 있다. 개인·3일/5일 수급은 이번 범위에서 미수집으로 표시한다. 금액·일별 확정 수급을 임의 생성하지 않는다.

## 실제 장중 검증 대기

1. 계정별 두 TR 사용 가능 여부와 현재 유량 정책, 실응답 필드/부호/주 단위 대조.
2. 가집계 응답의 당일성·조회 범위·종목 누락, 반복 응답 및 정정, 예정 갱신시각 대조.
3. 프로그램 원천 시각·KRX 화면과의 누적 수량 일치 및 응답 정렬·정체 검증.
4. 활성화 후 30분 이상 관측하여 delta 기준시각·결측·공통 REST 부하·DB 저장 확인.
5. 공식 15시 입력 유예 안의 Snapshot 연결 확인. 신규 점수 반영은 동일 실행조건 비교 및 Forward/OOS 검증 후 별도 작업.
