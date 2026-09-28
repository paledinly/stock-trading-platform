# Operations Guide

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

WebSocket 구독 기본 한도는 41개다. 관심종목, 정밀 후보, 예약 슬롯이 같은 한도를 사용하므로 관심종목 수가 곧 실시간 구독 수는 아니다. REST 호출은 endpoint별 rate limiter가 적용된다.

## 마감 추천

Feature 기준은 15:00, 기본 입력 유예는 10초, 진입 마감은 15:20이다. 후보가 없으면 점수를 낮추기 전에 데이터 품질, 일봉 준비, 종목별 최신성, 거래가능 상태와 제외 사유를 확인한다. 비용이 모두 0이면 Net 결과는 현실 검증값이 아니다.

## 장애 확인

1. /actuator/health
2. DB/Redis 연결
3. KIS 인증과 rate limit
4. WebSocket과 종목별 최신성
5. 수집 원문 규격과 파싱 실패 표본
6. 봉 저장과 확정 상태
7. 평가 API 제외 사유

민감한 원문, 앱 키, 토큰, 계좌번호는 로그와 이슈에 남기지 않는다.
