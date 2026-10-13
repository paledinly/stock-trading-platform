# Changelog

사용자와 운영에 영향을 주는 주요 변경만 기록한다. 단계별 AI 작업 로그는 Git 이력과 audit에 보존한다.

## 2026-10-13

- 장중 추천 기능 제거: 7세션 비교에서 비용 후 기대값이 양수가 되지 않고 목표 선도달률이 무작위 기준선보다 낮아 `intraday` 백엔드 모듈, `/api/v1/intraday/*` API, SSE `intraday.entry-ready`, 장중 추천 화면, `INTRADAY_*` 설정을 삭제했다([진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md), [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). 마감 추천에 집중한다.
- `intraday_input`·`intraday_recommendation` 테이블과 데이터는 DB에 남아 있으며 처리 방법은 사용자 확인 후 정한다.

## 2026-09-28

- 실시간 시각, 확정봉 불변성, 거래일/세션 완전성, 15시 입력 마감의 신뢰성 보강
- 거래량 baseline과 Feature 의미 구분, 가상 체결 비용 관측 연결
- 문서를 현재 상태, 운영, 전략, 검증, 의사결정, 감사 기록으로 재구성

과거 상세 변경은 Git history를 기준으로 확인한다.
