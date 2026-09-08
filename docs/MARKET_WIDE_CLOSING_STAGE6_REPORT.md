# Market-wide Closing Stage 6 Report

기준일: 2026-09-08

## 완료 범위

- Precision 자동 구독의 요청, 승인, 거절, 종료 이력을 DB에 저장한다.
- 일별 시장 Coverage와 Broad → Precision 전환 상태를 계산한다.
- Broad와 Precision 추천의 다음 거래일 성과를 서로 분리해 집계한다.
- 후보 제외·전환 상태를 이유별로 집계한다.
- 시장 전체 스캐너 화면에 Coverage 및 출처별 성과 패널을 추가했다.

## Coverage API

`GET /api/v1/market-wide/coverage?date=YYYY-MM-DD`

제공 항목:

- 현재 종목 마스터 기준 활성/거래 가능 종목 수
- 자동 스캔 예정/완료/실패 횟수
- 순위 포착 종목 수와 모집단 대비 비율
- Broad Snapshot 수집 성공/실패 종목 수와 Coverage
- Precision 요청/승인 고유 종목 수
- 실제 승인 구독 세션의 평균 체류시간
- Scanner Detection 발생 고유 종목 수
- Broad/Precision 추천 수
- Broad 후보의 추천, Precision 승격, Quote 실패, 품질 부족, 점수·개수 제한 제외 건수
- 출처별 완료/누락, 종가 승률, 평균 시가·종가 수익률, MFE 성격의 최대수익,
  MAE 성격의 최대낙폭, 목표·손절 도달률

## DB 변경

`V19__create_precision_subscription_session.sql`

- 종목코드
- 거래일
- 요청/승인/종료 시각
- REQUESTED/ACTIVE/ENDED/REJECTED 상태
- 종료 또는 거절 사유

통계 저장 실패가 실시간 구독 자체를 중단시키지 않도록 구독 경로에서는 이력 기록을 부가 기능으로 처리한다.

## 해석상 제한

- 과거 날짜의 모집단 분모는 현재 종목 마스터 상태다. 정확한 과거 Coverage에는 일별 Universe Snapshot이 필요하다.
- 순위 API가 반환하지 않은 종목은 관측할 수 없으므로 포착률은 Broad Snapshot으로 저장된 종목 기준이다.
- 출처별 성과는 기술통계이며 인과관계나 미래 수익을 의미하지 않는다.
- 수수료, 세금, 호가 Spread 및 Slippage는 아직 반영하지 않는다.
- 충분한 실제 거래일 표본이 쌓이기 전에는 승률과 평균수익을 전략 우위로 해석하면 안 된다.

## 검증

- Coverage, Precision 체류시간, 출처별 성과 및 제외 사유 Unit Test 추가
- Backend 전체 테스트 통과
- Web 테스트 및 프로덕션 빌드 통과
