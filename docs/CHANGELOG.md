# Changelog

사용자와 운영에 영향을 주는 주요 변경만 기록한다. 단계별 AI 작업 로그는 Git 이력과 audit에 보존한다.

## 2026-10-13

- 종가 단일가 체결 수신·저장: 원천시각 15:30:00~15:31:00(랜덤엔드) 체결을 수락해 15:25 봉에 반영하고, 전일 run 종목 구독을 15:31까지 유지하며, WebSocket 종료 유예 기본값을 2분으로 늘렸다. 이전에는 15:30:00 초과 체결을 조용히 버려 실시간 15:25 봉이 저장되지 않았다. 실시간 상태에 `closingAuctionTicks`, `outsideSessionTicks` 진단을 추가했다.
- 다음 거래일 세션 완결성 규칙 변경: 09:00~15:15 봉 + 거래량 있는 15:25 종가 체결 봉. 15:20 봉은 요구하지 않고, 15:32 전에는 `IN_PROGRESS`다. 공식 성과·후보 관측·ADR-008 judge에 같이 적용하며 저장된 과거 결과는 수정하지 않는다([전략 문서](TRADING_STRATEGY.md#다음-거래일-세션-완결성)).

- 장중 추천 기능 제거: 7세션 비교에서 비용 후 기대값이 양수가 되지 않고 목표 선도달률이 무작위 기준선보다 낮아 `intraday` 백엔드 모듈, `/api/v1/intraday/*` API, SSE `intraday.entry-ready`, 장중 추천 화면, `INTRADAY_*` 설정을 삭제했다([진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md), [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). 마감 추천에 집중한다.
- `intraday_input`·`intraday_recommendation` 테이블과 데이터는 DB에 남아 있으며 처리 방법은 사용자 확인 후 정한다.

## 2026-09-28

- 실시간 시각, 확정봉 불변성, 거래일/세션 완전성, 15시 입력 마감의 신뢰성 보강
- 거래량 baseline과 Feature 의미 구분, 가상 체결 비용 관측 연결
- 문서를 현재 상태, 운영, 전략, 검증, 의사결정, 감사 기록으로 재구성

과거 상세 변경은 Git history를 기준으로 확인한다.
