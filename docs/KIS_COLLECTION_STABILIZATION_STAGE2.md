# KIS 수집 안정화 2단계 (2026-09-14)

## 적용 범위

- PRICE_RISE 공식 fluctuation 요청에 FID_INPUT_CNT_1, FID_PRC_CLS_CODE, FID_RSFL_RATE1/2 보완.
- ETF/ETN 마스터 플래그와 인버스/레버리지/선물/ETN/ETF/2X 이름을 상세 조회 전에 제외. 기존 includeEtf=true 명시적 선택은 유지.
- 랭킹 원본 가격·등락률·거래량·거래대금·OHLC·수신시간을 보존. 없는 값은 null이며 추정 생성하지 않음.
- 최신 실시간 틱+실제 당일 현재가 기준값, 신선한 공통 캐시, 완전한 랭킹 데이터, REST 순으로 활용.
- 랭킹 검색 범위와 상세 REST 조회 예산 분리. 조회 실패/예산 소진 종목도 부분 데이터를 저장하되 INSUFFICIENT로 처리.
- 현재가/랭킹/분봉 REST 요청을 한 프로세스의 공통 간격으로 제어. EGW00201/HTTP429만 제한적으로 재시도하고 전역 백오프. 입력 필드 오류는 재시도하지 않음.
- 시장전체 화면에 출처별 확보 건수, REST 예산/실패, 필수값 부족, 최대 데이터 경과시간, 랭킹 성공 횟수와 KIS 누적 요청/재시도 진단 추가.

## 설정 (.env.example)

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| KIS_QUOTE_RATE_LIMIT | 4 | 현재가 RateLimiter 초당 허용량 |
| KIS_REQUEST_MIN_INTERVAL | 125ms | 데이터 REST 요청 공통 간격 |
| KIS_QUOTE_MIN_INTERVAL | 250ms | 현재가 요청 간격 |
| KIS_RATE_LIMIT_BACKOFF | 500ms | 제한 오류 초기 백오프 |
| KIS_RATE_LIMIT_MAX_RETRIES | 1 | 제한 오류 추가 재시도 수 |
| MARKET_WIDE_RANKING_LIMIT | 100 | 랭킹별 검색 범위 |
| MARKET_WIDE_DETAIL_QUOTE_BUDGET | 30 | 스캔 1회 상세 조회 종목 예산 (0이면 REST 안 함) |
| MARKET_WIDE_QUOTE_CACHE_MAX_AGE | 60s | 캐시/랭킹 최대 경과시간 |
| MARKET_WIDE_TICK_MAX_AGE | 30s | 최신 틱 최대 경과시간 |
| MARKET_WIDE_EXCLUDE_STRUCTURED_NAMES | true | 이름 기반 구조화 상품 제외 |

실제 .env는 자동 변경하지 않았다. 기존 환경변수는 새 기본값보다 우선한다. 동일 앱키를 사용하는 다른 프로세스/서비스는 공통 요청 간격으로 제어되지 않으므로 합산 호출량을 별도로 관리해야 한다. 상세 조회 예산은 종목 수이며 재시도 요청까지 포함한 HTTP 횟수 제한은 아니다.

## DB 및 운영 적용

V21__add_broad_quote_source.sql: market_broad_snapshot.quote_source 추가. 기존 행은 LEGACY_REST로 표시한다. 랭킹 원본 관측 정보는 기존 ranking_sources JSON에 보존한다.

백엔드 재시작 시 Flyway를 적용하며 프론트도 변경 빌드를 반영해야 한다. 이번 작업에서 실행 중 서비스 재시작, 실제 KIS 호출 검증, 운영 DB 직접 변경 또는 git commit/push는 하지 않았다. 미적용 V20이 있다면 함께 적용된다.

## 유지/후속 범위

최종 마감추천 점수 기준과 BROAD 관찰 전용 정책은 유지했다. 추천이 없다는 이유로 기준을 낮추지 않았다. 기존 Precision 구독 할당 구조를 유지하며 BROAD 전체 자동 구독은 추가하지 않았다.

후속 단계: 추천 정합성(연속 캔들/시점/중복 탐지 선택) 검증, 비동기 실패 후보 보강 큐, 장중 실제 수집 성공률 확인. 수집 개선만으로 추천 수나 수익을 보장하지 않는다.
