# Phase 3 첫 단계: 호가·대량체결

2026-10-02 구현. 사용자 선택 범위는 10단계 호가·호가 변화·대량체결이다. 기존 추천 점수, 실제 주문 기능, Intraday 진입/손절 기준은 변경하지 않는다. 결과는 Closing Trajectory의 연구 DTO에만 연결한다.

## 수집 경로와 호출 예산

- [공식 KIS 호가/예상체결 REST](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_asking_price_exp_ccn/inquire_asking_price_exp_ccn.py), TR FHKST01010200, 시장 J(KRX), output1의 10단계 가격·잔량·aspr_acpt_hour를 사용한다.
- [공식 응답 필드](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_asking_price_exp_ccn/chk_inquire_asking_price_exp_ccn.py)의 askp/bidp/askp_rsqn/bidp_rsqn 1~10을 파싱한다. 빈 필드를 0으로 바꾸지 않는다. 응답은 시각만 제공하므로 날짜는 요청 거래일 기준이며 원천 날짜가 검증됐다는 뜻이 아니다.
- 실시간 호가 [H0STASP0](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/asking_price_krx/asking_price_krx.py)도 지원되지만 기존 구독 관리가 체결 TR 전용이다. 이번 구현은 별도 슬롯을 쓰지 않는 제한된 REST 표본 수집이다. 향후 WS 전환에는 TR별 구독·ACK 및 전체 합산 슬롯 관리가 필요하다.
- 기본 5초 fixed-delay, 최대 2종목(설정 상한 5). 기본 부하는 분당 최대 약 24회/초당 평균 약 0.4회(재시도 제외). 통신·처리 지연으로 실제 간격은 길어질 수 있다. 기존 KisRequestExecutor의 공통 pacing과 429 backoff를 공유한다. 모의 계정은 공통 요청 간격을 계정 한도에 맞게 설정해야 한다.
- 종목을 설정하면 해당 목록만, 미설정이면 최근 contextMaxAge 내 1분봉이 저장된 종목을 최신 봉/종목코드 순으로 제한 선택한다. 전체 후보의 호가를 보장하지 않는다.
- 거래일 09:00 이상 15:20 미만만 수집한다. 종가 단일가/시간외는 이 모듈에 섞지 않는다. REST 예상체결 output2는 이번 범위에서 사용하지 않는다.
- 대량체결은 기존 ObservedMarketTick 커밋 이후 이벤트를 공유한다. 추가 API 조회나 실시간 구독은 없다.
- [공식 KRX 체결 명세](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/ccnl_krx/ccnl_krx.py)의 마지막 MARKET_CLS_CODE를 포함한 47필드와 기존 46필드 배치를 모두 처리한다. 알려지지 않은 필드 개수는 배제한다.

## 저장과 Feature

메모리 집계 → 1분 확정 → V29 closing_microstructure_minute 저장. PK(kind,symbol,start_time), 시간/종목 인덱스. ORDERBOOK과 LARGE_EXECUTION은 별도 kind다. 모든 호가 표본/틱을 영구 저장하지 않으며 호가는 분의 마지막 10단계와 통계만 남긴다.

호가 DTO: bids/asks 각각 10개의 price/quantity, bestBid/bestAsk, spread, spreadPct(매수1호가 대비 %), bid/askDepth1/5/10(주), bidAskDepthRatio, imbalance=(매수10단계-매도10단계)/(합계), sampleCount. 1분 변화는 직전 분과의 10단계 합계 차이 및 비율 차이다. 매도 잔량 0이면 비율 null, 합계 0이면 imbalance null이다. 교차/빈 최우선 호가는 spread를 계산하지 않는다.

같은 원천시각의 반복 응답은 표본 수를 늘리지 않는다. 미래 시각·날짜 불일치·기본 15초 초과 지연·확정된 분의 늦은 입력을 배제한다. 분 시작/끝의 관측 여유와 표본 사이 간격이 15초 이내이고 2개 이상인 경우에만 호가 complete=true다. 이는 표본 주기의 충족이지 모든 호가 변화 포착을 뜻하지 않는다. 직전 분 또는 연속성이 부족하면 1분 변화는 null이다.

대량체결 기준은 체결가×체결수량, 기본 1억원 이상이며 설정값이다. 매수/매도 각각 largeBuy/SellExecutionCount1m, largeBuy/SellExecutionAmount1m(원)를 저장한다. 직전 틱과 누적 거래량이 정확히 연결되고 누적 매수량 또는 매도량 증가분이 해당 체결량과 일치할 때만 방향을 분류한다. 첫 틱·수신 공백·누적값 불일치·방향 모호성은 Unknown 건수/금액으로 남긴다. 누락된 체결을 하나의 대량체결로 합성하지 않는다. 부분 관측 수치는 관측된 건수이지 그 분 전체 체결의 확정 집계가 아니다.

미저장 분은 종류/종목별 최대 3개이며 초과 유실은 카운터로 드러낸다. DB 실패는 재시도하고 성공 이후에만 메모리에서 제거한다. 재시작으로 메모리 표본은 유실될 수 있다. 저장된 확정분은 overwrite하지 않는다. 보관기간 자동 삭제는 추가하지 않았다.

## 평가 연결과 API

Snapshot의 orderbook 및 execution에 연결하며 수집·확정 시각이 입력 가용시각 이하이고 해당 평가시각 직전 분인 자료만 사용한다. 자료가 없으면 UNAVAILABLE, 부분 관측이면 별도 상태다. 이전 Snapshot을 새 자료로 소급 보정하지 않는다. subScores는 기존 값을 그대로 유지한다. 허매수/허매도는 주문 취소/체결 경로 증거가 없어 NOT_INFERRED로 표시한다.

- GET `/api/v1/closing-trajectory/microstructure/status`
- GET `/api/v1/closing-trajectory/microstructure/minutes?symbol=005930&date=YYYY-MM-DD`
- 기존 `/snapshots`의 orderbook/execution

## 활성화

기본 비활성이다. 새 코드로 서버를 시작하면 V29가 적용된다. 수집에는 다음 설정이 필요하다.

```properties
CLOSING_TRAJECTORY_ENABLED=true
CLOSING_MICROSTRUCTURE_ENABLED=true
CLOSING_ORDERBOOK_ENABLED=true
CLOSING_ORDERBOOK_POLL_INTERVAL=5s
CLOSING_ORDERBOOK_MAX_SYMBOLS=2
CLOSING_ORDERBOOK_SYMBOLS=005930,000660
CLOSING_LARGE_TRADE_THRESHOLD=100000000
CLOSING_MICROSTRUCTURE_MAX_GAP=15s
CLOSING_MICROSTRUCTURE_WATERMARK=2s
```

기존 체결 구독은 별도로 필요하다. 고정 호가 종목 지정만으로 체결 구독을 추가하지 않는다. .env 변경·운영 서버 재시작·실계정 호가 요청은 이번 개발에서 실행하지 않았다.

## 검증 및 후속 범위

자동 테스트: 공식 필드/결측 파싱, 10단계 합산·비율·변화, 방향별 대량체결과 공백 시 Unknown, 중복/늦은/단일가 입력 배제, 미래 수신/확정 데이터 배제, DB 불변 저장·실패 재시도, REST 공통 호출기/시간대 제한.

실제 장중 확인: 계정 한도와 HTS 호가 대조, 원천 호가 시각의 갱신 의미, 5초 수집 지연/다른 API 부하, 방향별 누적수량 일치, 1분 저장/Snapshot 가용성, 재시작·통신 중단 후 품질 상태. 실제 수익성은 미검증이다.

이번 범위 밖: WS 호가 슬롯 확장, 허매수/허매도 추정, 시간외·공매도/대차·뉴스 NLP·해외시장/선물/환율. 뉴스 NLP는 미구현 뉴스 수집 기반이 먼저 필요하다.
