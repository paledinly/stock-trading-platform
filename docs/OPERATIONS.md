# Operations Guide

## 실시간 수집 운영시간

KIS WebSocket은 `ClosingTradingCalendar`의 거래일에만 기본 08:59부터 연결하고 15:31부터 종료한다. 기존 30초 health check에서 시간 경계를 확인하므로 연결/종료는 최대 한 주기 늦을 수 있다. 장외에는 승인키 발급·장애 재접속을 중지하고, 다음 거래일에 보존된 구독 목록으로 다시 연결한다. Broad 스캔의 15:20 종료시각과 분리하여 15:20~15:30 종가 체결을 받을 수 있게 한다.

수집할 체결의 원천시각은 당일 09:00~15:30(마지막 체결 포함)이다. 연결 준비/종료 유예 중 들어온 장외 체결을 정규장 데이터로 저장하지 않는다. 기본 여유시간은 `MARKET_REALTIME_CONNECT_BEFORE_OPEN=1m`, `MARKET_REALTIME_DISCONNECT_AFTER_CLOSE=1m`으로 조절한다. 달력은 정적 확인 휴장일과 설정 목록을 사용하며 자동 공식 달력이나 특별 개장시간을 보장하지 않는다.

브라우저 SSE는 페이지가 열린 동안 대기 연결을 유지하지만 새 이벤트가 있을 때만 전송한다. 장외에 주기적으로 시세를 만들어 보내지 않는다. REST 조회·일봉 준비·성과 계산은 이 WebSocket 시간 제한과 별개다.

장마감 추세 수집은 기본 비활성이다. `CLOSING_TRAJECTORY_ENABLED`, `CLOSING_TRAJECTORY_INDEX_ENABLED`, 기존 실시간/Broad/일봉 준비 및 [Trajectory 운영 절차](CLOSING_TRAJECTORY.md)를 확인한다. 새 점수는 공식 추천 가중치에 적용하지 않는다.

## 장중 연구 기능

기본값 `INTRADAY_ENABLED=false`를 유지한다. 연구 수집을 시작할 때 기존 실시간 구독이 작동하는 환경에서 활성화한다. 비용 환경변수는 `INTRADAY_BUY_FEE_PERCENT`, `INTRADAY_SELL_FEE_PERCENT`, `INTRADAY_SELL_TAX_PERCENT`, `INTRADAY_SPREAD_PERCENT`, `INTRADAY_BUY_SLIPPAGE_PERCENT`, `INTRADAY_SELL_SLIPPAGE_PERCENT`다. 모두 percent 단위이며 실제 적용 계좌/상품/일자의 확인된 가정을 입력한다. 빈 항목이 있어도 가격 기준 R:R로 추천하고 기준 체결가격의 Gross 성과를 추적한다. 비용 항목이 일부만 있으면 비용 전체를 미반영으로 표시하고 Net은 null로 남긴다. 여섯 항목이 모두 있을 때만 비용 포함 R:R·Net을 계산한다. 세금이나 수수료율을 코드에서 현행 제도로 추정하지 않는다.

세부 실험값은 `IntradayProperties`의 `intraday.*` 설정을 사용한다. 모든 입력과 추천에 전체 설정이 보존된다. `require-complete-risk-data=true`이면 현재 미확보 지수/호가/VI/위험 메타데이터 때문에 NO TRADE가 된다. 기본 false는 미확보 항목을 위험정보로 표시하는 연구 설정이지 운영 안전성 승인이 아니다.

`/api/v1/intraday/status`에서 enabled/costsConfigured/error/queued/dropped와 후보 사유를 확인한다. 30분 연속 워밍업을 기다린다. 큐 적체, DB 장애, 지연/누락은 먼저 해소하고 미확정 성과를 정상 표본으로 바꾸지 않는다. 추천 화면의 최근 관측시각을 확인한다. SSE는 화면 알림용이며 메시지 서비스로 발송하지 않는다.

V27은 앱 시작 시 Flyway로 적용한다. 기존 Closing 테이블은 변경하지 않는다. 원장 보관 용량·기간과 단일 backend 인스턴스 운영을 전제로 한다. 멀티 인스턴스 중복 수집/leader 선출은 지원하지 않는다. 실제 운영 DB 적용 및 장중 KIS Forward 연결 검증은 로컬 합성 테스트와 별개다.

## 시작 순서

1. .env의 DB, Redis, KIS 자격정보를 확인한다.
2. PostgreSQL과 Redis를 시작한다.
3. Backend health가 UP인지 확인한다.
4. Web을 시작한다.
5. 실시간 기능이 필요하면 MARKET_REALTIME_ENABLED=true로 Backend를 재시작한다.

## 일일 점검

- KIS WebSocket 연결·재연결 상태와 종목별 마지막 틱 시각
- 파싱 실패와 처리 실패 카운터의 원인 구분
- 5분봉의 연속성, 확정 여부, 장 경계
- Broad 후보의 QUOTE_FAILED 및 상세시세 예산 소진 여부
- 15시 평가의 기준시각, 입력 마감, 완료시각
- 추천 제외 사유와 데이터 품질
- 다음 거래일 관측 상태와 비용 설정

## KIS 한도

WebSocket 구독 기본 한도는 41개다. 관심종목 중 실시간 고정 종목, 정밀 후보, 예약 슬롯이 같은 한도를 사용한다. 관심종목 등록 수가 곧 실시간 구독 수는 아니며, 고정 구독은 기본 최대 10개다. REST 호출은 endpoint별 rate limiter가 적용된다.

## 마감 추천

Feature 기준은 15:00, 기본 입력 유예는 10초, 진입 마감은 15:20이다. 후보가 없으면 점수를 낮추기 전에 데이터 품질, 일봉 준비, 종목별 최신성, 거래가능 상태와 제외 사유를 확인한다. 비용이 모두 0이면 Net 결과는 현실 검증값이 아니다.

Precision 후보 교체는 기본 14:35에 동결된다. 일봉 준비는 08:10 전체 준비와 14:25/14:35 Precision 보완으로 나뉜다. 완료된 Forward 평가의 `전체 후보 성과 추적`은 최종 추천과 미선정 후보를 같은 가상 실행 규칙으로 관측하지만 추천 결과 자체를 변경하지 않는다.

## 장애 확인

1. /actuator/health
2. DB/Redis 연결
3. KIS 인증과 rate limit
4. WebSocket과 종목별 최신성
5. 수집 원문 규격과 파싱 실패 표본
6. 봉 저장과 확정 상태
7. 평가 API 제외 사유

민감한 원문, 앱 키, 토큰, 계좌번호는 로그와 이슈에 남기지 않는다.
