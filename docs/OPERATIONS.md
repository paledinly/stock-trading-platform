# Operations Guide

## 15:15 자동 마감 평가 (v9)

`CLOSING_AUTOMATION_ENABLED=true`, `CLOSING_FEATURE_FREEZE_AT=15:15`, `CLOSING_CANDLE_FINALIZATION_GRACE=0s`, `CLOSING_ENTRY_DEADLINE=15:20`을 사용한다. .env의 명시값이 기본값보다 우선하며 백엔드 재시작 후 반영된다. 기본 30초 poll에서 15:15 이상·15:20 미만에 실행한다. 15:20 이후 재시작하면 당일 자동평가를 소급 실행하지 않는다.

수동/자동 FORWARD 요청은 거래일·전략 버전의 동일 키와 DB 잠금을 공유한다. 설정이 다른 중복 요청은 충돌 응답을 반환하며 저장된 결과는 덮어쓰지 않는다. 롤백된 평가는 마감 전 다음 poll에서 재시도한다. 정상 실행은 재시작해도 반복하지 않는다.

가상 진입은 5초 poll에서 평가 완료 후의 체결을 확인한다. 종목별 원천→진입 지연 10초 초과, 거래정지, 누락 표식, 미래 시각은 진입하지 않는다. 평가가 늦으면 EVALUATION_DEADLINE_MISSED, 유효 체결 없이 마감하면 NO_FRESH_TICK_BEFORE_DEADLINE을 closing_paper_entry에 기록한다. 중단 중 가격을 분봉으로 소급 채우지 않는다. 중단으로 남은 PENDING은 다음 활성 구간에서 만료되며 진입으로 간주하지 않는다. 실주문은 없다.

## 제거된 장중 연구 기능

장중 추천 모듈은 2026-10-13 제거했다([실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). `INTRADAY_*` 환경변수와 `intraday.*` 설정은 더 이상 읽지 않으므로 기존 `.env`에 남아 있어도 무시된다. 장 종료 후 `intraday_recommendation` 조회로 생기던 Supabase Egress도 백엔드 재시작 후 사라진다. 기존 `intraday_input`·`intraday_recommendation` 테이블과 로컬 `.intraday-checkpoints/` 디렉터리는 자동 삭제하지 않으며, 보관·삭제는 사용자 확인 후 별도로 진행한다.

## 실시간 수집 운영시간

KIS WebSocket은 `ClosingTradingCalendar`의 거래일에만 기본 08:59부터 연결하고 15:31부터 종료한다. 기존 30초 health check에서 시간 경계를 확인하므로 연결/종료는 최대 한 주기 늦을 수 있다. 장외에는 승인키 발급·장애 재접속을 중지하고, 다음 거래일에 보존된 구독 목록으로 다시 연결한다. Broad 스캔의 15:20 종료시각과 분리하여 15:20~15:30 종가 체결을 받을 수 있게 한다.

수집할 체결의 원천시각은 당일 09:00~15:30(마지막 체결 포함)이다. 연결 준비/종료 유예 중 들어온 장외 체결을 정규장 데이터로 저장하지 않는다. 기본 여유시간은 `MARKET_REALTIME_CONNECT_BEFORE_OPEN=1m`, `MARKET_REALTIME_DISCONNECT_AFTER_CLOSE=1m`으로 조절한다. 달력은 `CLOSING_CALENDAR_KIS_REFRESH_ENABLED=true`이면 KIS 국내휴장일조회(`chk-holiday`, `opnd_yn`)를 KST 기준 하루 한 번(기동 시 또는 `CLOSING_CALENDAR_KIS_REFRESH_CRON`, 기본 07:40) 받아 `trading_calendar_day`에 저장하고, 받은 날짜는 그 값을 우선한다. 받지 못한 날짜와 조회 실패 시에는 정적 확인 휴장일과 `MARKET_WIDE_HOLIDAYS`를 사용한다. 정적 목록과 다른 날짜는 로그에 남긴다. 상태는 `GET /api/v1/closing-recommendations/trading-calendar`로 확인한다. KIS 응답은 증권사 제공 값이며 거래소 공지와의 차이, 특별 개장시간(지연 개장 등)은 반영하지 않는다. 이미 저장된 추천·관측의 기대 거래일은 소급 수정하지 않는다.

브라우저 SSE는 페이지가 열린 동안 대기 연결을 유지하지만 새 이벤트가 있을 때만 전송한다. 장외에 주기적으로 시세를 만들어 보내지 않는다. REST 조회·일봉 준비·성과 계산은 이 WebSocket 시간 제한과 별개다.

장마감 추세 수집은 기본 비활성이다. `CLOSING_TRAJECTORY_ENABLED`, `CLOSING_TRAJECTORY_INDEX_ENABLED`, 기존 실시간/Broad/일봉 준비 및 [Trajectory 운영 절차](CLOSING_TRAJECTORY.md)를 확인한다. 새 점수는 공식 추천 가중치에 적용하지 않는다.

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

실시간 처리 큐 분리 코드는 backend 재시작 후 적용된다. 재시작 전 메모리 큐를 영구 복구하지 않으므로 관측 공백을 숨기지 않는다. `/api/v1/market/realtime/status`의 `realtime.processingQueued`, `processingDropped`, `timings`를 확인한다. `sourceToCallback`은 수신 이전 지연, `processingQueueWait`는 서버 작업 대기, `tickTransaction`과 세부 구간은 처리 비용이다. 큐가 지속 증가하면 분리만으로 처리량 문제가 해결되지 않은 것이다. `sseSlowClientDisconnects`가 늘면 화면 탭/프록시가 이벤트를 따라가지 못하는 것이며 틱 처리에는 영향이 없어야 한다. 누락 카운터가 증가하면 해당 구간은 정상 연속 관측으로 취급하지 않는다. 상세 단위는 [API Guide](API.md)를 따른다.

1. /actuator/health
2. DB/Redis 연결
3. KIS 인증과 rate limit
4. WebSocket과 종목별 최신성
5. 수집 원문 규격과 파싱 실패 표본
6. 봉 저장과 확정 상태
7. 평가 API 제외 사유

민감한 원문, 앱 키, 토큰, 계좌번호는 로그와 이슈에 남기지 않는다.

## 호스트 시계 동기화

KIS 원천 체결시각은 초 단위로 절사되므로 정상이라면 수신시각보다 늦을 수 없다. 수집 호스트 시계가 늦으면 `occurredAt > receivedAt` 틱이 생기고, 마감 가상 진입·분 집계는 시각 정책에 따라 이 틱을 버린다. 2026-10 운영 PC에서는 Windows Time 서비스가 꺼져 있어 하루 중 최대 약 1초까지 어긋났다([진단](../audit/2026-10-12-clock-skew/README.md)).

- 확인: `/api/v1/market/realtime/status`의 `timings.sourceToCallback.negativeCount`가 0보다 크거나 `minMs`가 음수이면 호스트 시계가 원천보다 늦다.
- Windows 조치(관리자 PowerShell):

```powershell
Set-Service w32time -StartupType Automatic
Start-Service w32time
w32tm /config /manualpeerlist:"time.windows.com,0x9" /syncfromflags:manual /update
w32tm /resync
w32tm /stripchart /computer:time.windows.com /samples:3 /dataonly
```

기본 동기화 주기가 길면 하루 중에도 수백 ms 단위로 흐를 수 있다. 장 시작 전 `w32tm /resync`와 stripchart 오프셋 확인을 운영 점검에 포함한다.
