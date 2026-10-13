# Changelog

사용자와 운영에 영향을 주는 주요 변경만 기록한다. 단계별 AI 작업 로그는 Git 이력과 audit에 보존한다.

## 2026-10-13

- 마감 추천 v9.1(`closing-recommend-v9_1-1515`): 15:15 입력 마감 이후 수신된 탐지와 15:15:00에야 끝나는 15:10 봉을 늦은 수정이 아닌 미가용 입력으로 제외한다. 점수·필터·평가 시각은 v9와 같고 ADR-008 표본은 v9.1부터 다시 센다([ADR-009](decisions/ADR-009-CLOSING-V9_1-INPUT-AVAILABILITY.md)).
- Scanner 쿨다운: Redis가 없거나 실패하면 탐지를 버리지 않고 프로세스 내부 쿨다운으로 대체한다. 실시간 상태의 `scannerCooldownFallbacks`, `lastScannerCooldownFallbackAt`과 오류 목록(분당 1회)으로 확인한다. 일별 완결성은 평가 구간 탐지 0건을 `SCANNER_DETECTIONS_MISSING`으로 표시한다.
- 종가 단일가 체결 수신·저장: 원천시각 15:30:00~15:31:00(랜덤엔드) 체결을 수락해 15:25 봉에 반영하고, 전일 run 종목 구독을 15:31까지 유지하며, WebSocket 종료 유예 기본값을 2분으로 늘렸다. 이전에는 15:30:00 초과 체결을 조용히 버려 실시간 15:25 봉이 저장되지 않았다. 실시간 상태에 `closingAuctionTicks`, `outsideSessionTicks` 진단을 추가했다.
- 다음 거래일 세션 완결성 규칙 변경: 09:00~15:15 봉 + 거래량 있는 15:25 종가 체결 봉. 15:20 봉은 요구하지 않고, 15:32 전에는 `IN_PROGRESS`다. 공식 성과·후보 관측·ADR-008 judge에 같이 적용하며 저장된 과거 결과는 수정하지 않는다([전략 문서](TRADING_STRATEGY.md#다음-거래일-세션-완결성)).

- 장중 추천 기능 제거: 7세션 비교에서 비용 후 기대값이 양수가 되지 않고 목표 선도달률이 무작위 기준선보다 낮아 `intraday` 백엔드 모듈, `/api/v1/intraday/*` API, SSE `intraday.entry-ready`, 장중 추천 화면, `INTRADAY_*` 설정을 삭제했다([진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md), [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). 마감 추천에 집중한다.
- `intraday_input`(9,346,586행, 13.3GB)은 로컬 백업과 행 수·최대 id를 대조한 뒤 2026-10-13 13:36 KST경 비웠다(TRUNCATE). DB는 13.64GB에서 0.37GB가 됐다([보관 기록](../audit/2026-10-13-intraday-archive/README.md)). 테이블 정의와 `intraday_recommendation`(약 7MB)은 남아 있다.

## 2026-09-28

- 실시간 시각, 확정봉 불변성, 거래일/세션 완전성, 15시 입력 마감의 신뢰성 보강
- 거래량 baseline과 Feature 의미 구분, 가상 체결 비용 관측 연결
- 문서를 현재 상태, 운영, 전략, 검증, 의사결정, 감사 기록으로 재구성

과거 상세 변경은 Git history를 기준으로 확인한다.
