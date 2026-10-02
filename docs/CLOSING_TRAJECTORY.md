# 장마감 Trajectory 데이터 확장

2026-10-02 후속: 외국인·기관 추정 수급과 KRX 프로그램 누적 수량의 수집/30분 관측 변화/DTO 연결을 추가했다. 아래는 Phase 1 당시 기록이며 수급의 현재 범위·활성화·제한은 [Phase 2 수급 문서](INVESTOR_FLOW.md)를 따른다.

## 1. 기존 프로그램 분석 결과

2026-09-30 소스 기준. 기존 미커밋 변경(관심종목 구독 분리, 후보 관측, 독립 Intraday 연구)을 보존했다.

| 영역 | 기존 구현 | 이번 작업 |
|---|---|---|
| 구조 | Java 21/Spring Boot 3.5, JPA/Flyway, PostgreSQL/Redis, React 19/TypeScript/Vite, Flutter | closing/trajectory 추가, 기존 구조 유지 |
| 수집 | KIS REST 현재가·분봉·일봉·랭킹, WebSocket H0STCNT0 체결 | 기존 체결/랭킹 공유, 지수 REST 추가 |
| 저장 | 확정 5M/1D stock_candle, Broad/Scanner Feature JSON | 별도 1분 Aggregate/연구 Snapshot |
| Scanner/후보 | 거래량/상승률/모멘텀/전환, Broad 상세조회 예산, Precision 구독 | 기존 선정 유지, 관측된 종목의 추세 수집 |
| 점수/필터 | 기회·위험 0.45배, 유동성·VWAP·고가·거래량·이평; 거래가능성/신선도/연속성/과열 검사 | 원본 Feature와 별도 Sub Score |
| 시장 | Broad 후보 표본 평균 Regime | 실제 KOSPI/KOSDAQ 및 상대강도 |
| 일봉 | DailyCandleBackfillService, 기본 120일 조회/종목 예산 | 연속 20거래일 Feature 재사용 |
| 성과 | 추천/관찰 후보 익일 관측·비용 포함 가상 체결 | 종가 기준 오전 학습 Target 추가 |
| 백테스트 | Scanner 재생, Closing 비교/가용시각 검사 | 과거 1분 이력 역생성 안 함 |
| Scheduler | 봉 확정, Broad, 일봉, Closing 자동화, Intraday 큐 | 전용 집계/맥락 scheduler |
| 뉴스/공시/수급/업종/테마 | 검증된 수집·시계열 경로 없음 | 미수집 명시, 원인 추측 안 함 |

## 2. 발견한 문제

- 거래대금 랭킹의 `FID_BLNG_CLS_CODE=0`을 공식 예제의 거래금액순 `3`으로 수정했다. 기존 저장 랭킹은 소급 정정하지 않는다. 수정 전후 후보 Universe가 달라질 수 있다.
- 현재 체결강도와 직전 틱 증분만으로 5/15분 지속 수급을 확인할 수 없었다.
- 5분봉으로 1분 OHLC 및 봉 내부 목표/손절 순서를 복원할 수 없다.
- 후보 표본 평균은 실제 지수의 대체값이 아니다.
- 기존 전략은 15:00 Feature 동결이다. 15:10~15:20 자료를 소급 혼합하면 누수가 된다.

## 3. 추가한 데이터

`ObservedMarketTick` 커밋 후 → 메모리 1분 집계 → 별도 scheduler → DB. 이 경로는 Tick을 영구 저장하지 않는다. 기존 선택적 Intraday 틱 원장 정책은 유지한다.

- 1분 OHLC/체결량/거래대금, 당일 누적량/대금, 일중 O/H/L, 분말 체결강도, 매수/매도 체결량 증분.
- 분 시작, 마지막 발생/수신, 확정 요청 시각을 분리한다.
- 09:00~15:20 미만만 집계한다. Phase 1=14:30~15:00, Phase 2=15:00~15:10, Phase 3=15:10~15:20. 종가 단일가·시간외는 섞지 않는다.
- 첫 부분 분봉, 재시작, 누적량/수신 체결량 불일치는 `complete=false`. 결측 분을 0으로 생성하지 않는다.
- 확정 bucket에 늦은 틱을 반영하지 않는다. DB 실패 시 같은 Aggregate를 재시도한다. 종목당 미저장 bucket 최대 3개, 초과 시 누락 카운터를 올린다. 프로세스 종료 전 메모리 자료는 유실될 수 있다.
- 순위는 기존 응답의 수신시각과 scope를 보존한다. ALL과 개별 시장 순위를 섞지 않는다. 공급자 응답 범위 밖 종목의 순위를 추정하지 않는다.
- 지수는 `inquire-index-price`/`FHPUP02100000`, U, KOSPI 0001/KOSDAQ 1001. 원천시각이 없는 REST 응답에는 수신시각만 저장한다.

## 4. 추가한 Feature

- 가격: 전일종가/시가 대비, dayRangePosition, 고저 거리, 장후반 수익률·고가·돌파·눌림 깊이.
- 거래량/거래대금: 기본 1/5/15/30분, 최근 N분 대 직전 N분 변화율. 전체 구간의 연속·완전성이 필요하다.
- 대금 가속도: `(최근 N분 - 2×직전 N분 + 그 이전 N분) / 그 이전 N분`, 5/15분. 10→15→25는 0.5다.
- 순위: 5/15/30/60분 전과 비교, `과거 순위 - 현재 순위`. 기준시각 이하 관측만 쓰며 기본 6분 이상 오래된 값은 null.
- VWAP: 당일 누적대금/누적량, 이격도·상향 전환·연속 관측 유지분·5/15분 이격도 기울기.
- 체결: 분말 강도의 5/15분 산술평균·회귀 기울기, 구간 매수량/매도량. 분내 틱 가중 평균은 아니다.
- 지수: 전일/30/60분 대비와 종목 수익률 - 소속 시장 수익률.
- 일봉: 직전 거래일까지 연속·수신 확인된 자료의 1/3/5/10/20일 수익률, 5/10/20일 고저·평균량·대금·MA, 10/20일 표본 표준편차. 변동성 N일은 N+1봉이 필요하며 비연율화 퍼센트 단위다.
- 과열 관측: 윗꼬리 비율·장후반 매도 비중. 자동 제외 조건으로 쓰지 않는다.
- 패턴: Late Breakout, Pullback Recovery, Failed Breakout, Distribution. 봉내 순서는 추측하지 않고 데이터 부족은 null. 14:30부터 이력이 없으면 장후반 패턴은 결측이다.

금액=원, 수량=주, Pct 및 오전 Return=퍼센트(2는 2%). 범위 위치·가속도·매수/매도는 비율. 분모 0은 null. VWAP의 관측 전 돌파시각을 생성하지 않는다.

## 5. 후보 평가 변경

운영 점수/가중치/필터는 유지한다. `ClosingPrecisionEvaluator.dataReadiness.trajectory`에 기준시각 이하이면서 입력 유예 내에 계산된 Snapshot만 연결한다. 없으면 null.

`timestamp`는 원천 정보 cutoff, `inputAvailableBy`는 수신/확정 검사시각, `evaluatedAt`은 계산 완료시각이다. 15:00 Snapshot에 이후 원천 분봉·순위·지수를 넣지 않는다. REST 맥락은 수신시각을 제한한다. 기존 입력 유예 정책은 유지한다.

14:30~15:20 기본 5분마다 관측 종목의 Snapshot을 저장한다. 15:10~15:20 자료는 별도 연구이며 공식 15:00 추천 입력이 아니다. 과거 Snapshot을 현재 값으로 채우지 않는다.

Sub Score에는 입력·이유·산식을 저장한다. `50+50*tanh(input/10)`은 미검증 연구 표시값이다. 단위가 다른 점수끼리 합산하거나 운영 순위에 적용하지 않는다. LateMoneyFlow/ClosingStrength/ExecutionMomentum/VWAPStrength/MarketRelativeStrength/OverheatRisk를 제공하고 미수집 분야는 null. Catalyst=UNKNOWN.

`[ClosingCandidate]` 로그에는 SHADOW/cutoff/가격/대금/체결/VWAP/패턴 근거를 기록한다. 매수 추천·주문 로그가 아니다.

## 6. DB 변경

V28은 아래 테이블만 추가한다. JSON은 기존 프로젝트와 같은 text 저장이다.

| 테이블 | 키 / 인덱스 | 역할 |
|---|---|---|
| closing_minute_feature | PK(symbol,start_time), start_time | 불변 1분 Aggregate |
| closing_context_observation | PK(kind,symbol,scope,received_at), received_at | 지수/순위 수신 이력 |
| closing_trajectory_snapshot | PK(symbol,evaluation_time), evaluation_time/symbol | 불변 입력 + 가변 outcome |

동일 키 INSERT는 기존 입력을 보존한다. outcome 갱신은 Feature를 수정하지 않는다. 자동 삭제는 없으므로 운영 보관기간/백업 정책이 필요하다. 운영 DB migration은 이번 작업에서 실행하지 않았다.

## 7. API 및 운영

- GET `/api/v1/closing-trajectory/status`
- GET `/api/v1/closing-trajectory/minutes?symbol=005930&date=YYYY-MM-DD`
- GET `/api/v1/closing-trajectory/snapshots?date=YYYY-MM-DD`
- GET `/api/v1/closing-trajectory/outcomes?date=YYYY-MM-DD`
- POST `/api/v1/closing-trajectory/outcomes/track?date=YYYY-MM-DD`

기본 비활성. 수집을 시작할 때 `CLOSING_TRAJECTORY_ENABLED=true`, 지수는 `CLOSING_TRAJECTORY_INDEX_ENABLED=true`를 설정한다. 기존 KIS/실시간 구독·Broad 스캔·일봉 준비가 필요하다. 계정 설정이나 자동 구독을 이번 작업에서 변경하지 않았다.

`closing.trajectory`에서 분석시각, Snapshot 간격, watermark, 맥락 유효기간, 지수 주기, 눌림 기준, 거래량/대금 비교창, 오전 종료, 목표/손절을 설정한다. 오전 종료는 5분봉 경계다. 정책은 Snapshot마다 고정하므로 나중 설정이 과거 목표를 바꾸지 않는다.

기존 유동성 필터도 `CLOSING_MIN_DAILY_TRADING_VALUE`/`CLOSING_MIN_5M_TRADING_VALUE`로 외부화했다. 기본값 10억원/2천만원은 그대로이며 실행 criteria/hash에 실제 설정값을 기록한다.

오전 결과: 종가 기준 Gap/고저 수익률, 1/2/3/5% 도달, 지정 Target, 손절 선행. 종가 또는 구간 봉이 없으면 DATA_INCOMPLETE/null. 같은 봉의 목표/손절 동시 터치는 null이며 시초가 갭의 선행 조건은 구별한다. 10:00/10:30/11:00 고저도 각 구간 전체가 있어야 한다.

연구 진입은 실제 계산 완료 다음 5분 경계 이후 첫 관측 확정봉 시가이고 15:20 전만 허용한다. 종가 기준 Target과 `entryToNextOpenReturn`을 분리한다. Gross 학습 라벨이며 공식 비용 포함 성과와 합산하지 않는다. 기존 `OvernightExecutionSimulator`는 유지한다.

자동 성과 추적은 10분마다 직전 거래일 대상이다. 중단으로 놓친 과거 날짜는 POST로 재처리한다. 필요한 5분봉 수집/backfill은 기존 경로를 쓰며 성과 조회가 KIS를 추가 호출하지 않는다.

### 호출 한도

KIS 공식 2026-04-20 안내: REST 실전 18건/초, 모의 1건/초, 앱키당 WS 1세션·상품 전체 합산 41건. 신규 고객 별도 제한 공지도 있으므로 계정 적용 한도는 별도 확인한다. 독립 분당 시세 한도는 해당 안내에 명시되지 않아 추정하지 않는다.

현재 코드: 공통 125ms, 현재가 250ms, endpoint RateLimiter, 429/유량 오류 backoff·제한 재시도, WS 기본 41. REST 기본값은 모의 1건/초에 맞지 않으므로 `KIS_REQUEST_MIN_INTERVAL` 등을 계정 한도에 맞게 설정해야 한다. 공통 제한기는 프로세스별이며 여러 프로세스의 계정 유량을 합산하지 않는다.

새 지수는 공통 제한기 아래 기본 5분당 2건. 순위/Feature별 중복 REST는 추가하지 않는다. 향후 호가/예상체결은 전체 TR 구독 수로 슬롯을 계산해야 한다.

## 8. 테스트

전체 backend Gradle test, web npm test/build 통과. 신규 검사: 수치 연산, 네 패턴, 늦은 틱·누락·단일가 경계, 미래 수신/확정/일봉 수정 배제, DB 불변성/가용시각, 오전 결측/순서 모호성, 지수 파싱. Flutter 소스는 변경하지 않았다.

성공은 실계정 수집, 운영 PostgreSQL migration, 전략 수익성 검증을 뜻하지 않는다.

## 9. 미구현 데이터 및 Phase 2 설계

다음은 **프로젝트 미연동**이며 공급자 API 자체 미지원이라는 뜻은 아니다.

- 투자자 수급·30분/3일/5일 변화, 업종/테마 매핑·시계열.
- 뉴스/공시·중복 기사·근거 있는 Catalyst.
- 종가 단일가 예상체결/잔량, 호가 10단계·변화, 대량 체결.
- 시간외, 공매도/대차, CB/BW/보호예수/실적 일정, 52주 고가·상한가 횟수.
- 수집 전 1분 이력 및 새 Sub Score의 OOS/Forward 검증.

뉴스는 publishedAt/firstSeenAt, 원문 URL·정규화 제목·사건 fingerprint, 최초 보도시각·중복 수를 분리 저장한다. 반복 기사를 신규 호재로 가산하지 않고 수정 기사도 원본을 덮어쓰지 않는 구조를 사용한다. 평가 전 공개 **및 수신**된 자료만 연결한다. 공시는 식별자·유형·공개/수신시각·riskLevel·근거를 보존한다. 관련종목이 확인되지 않으면 임의 연결하지 않는다. 공급자 접근권한과 갱신 주기를 확정한 뒤 추가한다.

## 10. 다음 우선순위

1. 실제 세션의 분봉 완전성·순위 범위·지수 최신성·20거래일 준비 확인 및 Forward 표본 축적.
2. 동일 Universe/진입/청산/비용의 기준 전략 대 추세 전략 비교. 그 전까지 SHADOW 유지.
3. Phase 2: 수급, 업종/테마, 뉴스/공시, 종가 단일가.
4. Phase 3: 슬롯/저장 예산 검증 후 호가·대량체결·시간외·외부 시장·NLP.

## 외부 확인 자료

- [KIS 순위 공식 예제](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/volume_rank/volume_rank.py)
- [KIS 현재지수 공식 예제](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_price/inquire_index_price.py)
- [KIS 지수 응답 필드](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_price/chk_inquire_index_price.py)
- [KIS API 유량 안내](https://apiportal.koreainvestment.com/community/10000000-0000-0011-0000-000000000001/post/d0d1a83f-6f8d-4437-9700-6d26702fd989)
