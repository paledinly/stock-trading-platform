# Roadmap

## Phase 3 호가·대량체결

- 10단계 호가의 제한된 REST 표본 수집, 1분 깊이·불균형·변화량, 기존 실시간 체결 기반 대량체결 집계와 평가 DTO 연결을 구현했다. 기본 비활성이고 기존 점수는 유지한다.
- 실제 장중 신선도·연속성·호출 부하 검증이 남아 있다. WebSocket 호가 구독, 허매수/허매도 추정, 시간외·공매도·대차·해외 데이터는 후속 범위다. [상세 및 활성화 방법](MICROSTRUCTURE.md).

## Phase 2 수급

- 외국인·기관 가집계/프로그램 수량의 제한된 REST 수집, 기존 Context 저장, 30분 관측 변화, Trajectory DTO 연결을 구현했다. 기본 비활성이다.
- 실계정 지원·원천 갱신·시장 범위·데이터 신선도·수집 부하는 장중 검증 대기다. 개인·과거 3/5일·확정 일별 수급은 미연동이다. [상세](INVESTOR_FLOW.md).

## 장중 추천 연구 (종료, 과거 기록)

- 2026-10-13 7세션 비교에서 진입 확인(HOLD/RETEST)과 최소 손절 FILTER 모두 비용 후 기대값을 양수로 만들지 못했고 목표 선도달률이 무작위 기준선보다 낮았다([진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md), [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). 장중 추천 모듈과 화면은 같은 날 제거했고 마감 추천에 집중한다.
- 남은 일: DB에 남은 `intraday_input`·`intraday_recommendation` 테이블의 삭제 여부를 사용자 확인 후 결정한다([DATABASE](DATABASE.md#사용하지-않는-테이블)).

## 정리 보류 항목 (2026-10-14 정리 작업에서 미승인)

- 미사용 테이블 `platform_schema_version`, `intraday_input`, `intraday_recommendation` 삭제(새 migration 필요)
- `web/vite.config.ts`의 하드코딩된 ngrok 허용 호스트
- Mobile 앱 유지 여부(2026-09-28 이후 변경 없음, Android 릴리스 manifest에 `INTERNET` 권한 없음)
- `MarketSessionPolicy`와 `ClosingTradingCalendar`의 거래일 판단 중복
- `application.yml`에만 있고 `.env.example`에 없는 설정 키 보완
- 실시간 레이더(`ScannerPage`)가 `RealtimeBridge`와 별도로 SSE 연결을 하나 더 여는 구조

## P0: 신뢰 가능한 운영 관측

- 실제 KIS 원문 표본으로 파싱 오류 원인을 계속 확인한다.
- KIS 국내휴장일조회 기반 거래일 저장·우선 적용은 구현됐다(코드 기본 비활성, `CLOSING_CALENDAR_KIS_REFRESH_ENABLED`). 운영 활성화 후 거래소 공지와 대조하고, 특별 개장·폐장 시간은 별도 과제로 남는다.
- 종목별 최신성과 실시간 구독 상태를 운영 화면에서 명확히 확인한다.
- 15시 공식 입력 snapshot의 보존 범위와 재현 가능성을 확대한다.

## P1: 성과 연결

- 실제 비용 가정의 출처를 확정하고 환경별 버전을 기록한다.
- 추천 snapshot부터 가상 진입, 청산, Net까지 완료 표본을 축적한다.
- 누락, 정상 무거래, 거래정지, 세션 미종료를 통계에서 분리한다.
- 탈락 후보 동일 실행규칙 관측은 구현됐다. 충분한 Forward 완료 표본을 쌓아 선택 편향을 비교한다.
- 마감 v9 판단 기준과 NEXT_OPEN 비교안을 [ADR-008](decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md)로 사전 등록했고, v9.1([ADR-009](decisions/ADR-009-CLOSING-V9_1-INPUT-AVAILABILITY.md))부터 표본을 다시 센다. 1차 표본은 정밀 후보 전체다([ADR-010](decisions/ADR-010-CLOSING-PRIMARY-SAMPLE-ALL-PRECISION.md)). 1차 체크포인트(완료 60건·15거래일)에서 [판정 스크립트](../audit/closing-v9-judge/README.md)로 계산한다.

## P2: 비교 실험

동일 길이 시간창의 장 후반 거래활동, 지속 주문흐름, 갭과 변동성 위험, 시장 상대강도, spread와 호가 깊이를 분리 실험한다. 검증 전에는 운영 기본 점수에 넣지 않는다.

## P3: 장기 연구

업종 상대강도, 뉴스·공시 지속성, 복잡한 주문흐름 모델을 검토한다. 기술지표 수를 늘리는 것 자체는 목표가 아니다.
