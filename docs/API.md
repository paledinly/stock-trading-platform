# API Guide

## 장마감 Trajectory 연구 API

`/api/v1/closing-trajectory` 아래 GET `/status`, `/minutes?symbol=...&date=...`, `/snapshots?date=...`, `/outcomes?date=...`, POST `/outcomes/track?date=...`를 제공한다. 기존 평가의 `dataReadiness.trajectory`에도 당시 사용 가능한 Snapshot을 연결한다. [설정·응답 의미](CLOSING_TRAJECTORY.md)를 참고한다.

## 장중 연구 API

- `GET /api/v1/intraday/status`: 활성 여부, 비용 설정 여부, 큐/누락/오류, 현재 관찰 후보 단계·사유.
- `GET /api/v1/intraday/recommendations?date=YYYY-MM-DD`: 한국 거래일의 Paper 추천. `signal`은 고정 Snapshot, `outcome`은 이후 관측·가상 체결이다. score 내림차순이며 현재가에는 관측시각이 포함된다. signal.risks에 COSTS_NOT_CONFIGURED가 있으면 plan.riskReward는 가격 기준이며 outcome.netReturn은 null이다. 이 추천은 Net 통계 표본에서 제외한다.
- `GET /api/v1/intraday/analytics?date=YYYY-MM-DD`: 독립 Paper의 Setup/시간대/점수구간/시장 맥락/정책별 통계. 자료가 없으면 null이다.
- `POST /api/v1/intraday/replay?date=YYYY-MM-DD`: 본문이 없으면 당시 설정·수신 순서로 재생한다. 전체 `IntradayProperties` JSON 본문을 전달하면 동일 관측 Universe/입력의 반사실 실험이다. Paper 성과에 합산하지 않는다. 비교 시 비용/진입/청산 설정은 동일하게 유지해야 한다.
- SSE `intraday.entry-ready`: 새 추천의 id·종목·Setup·계획·점수·이유 Snapshot. cooldown/활성 포지션으로 반복 발행을 제한한다. 외부 메신저 발송이나 주문 API는 추가하지 않는다.

0건 추천은 정상 NO TRADE이며 비활성·자료 부족·유동성·Setup 미충족 등 원인은 status에서 구분한다. Confidence는 UNVALIDATED이며 score는 확률이 아니다.

Backend의 REST/SSE endpoint는 Controller mapping이 기준이다. 이 문서는 영역과 사용 원칙만 설명하며 정확한 request/response 필드는 DTO와 Web 호출 코드를 함께 확인한다.

## 주요 영역

- 종목 검색과 현재가
- 관심종목 그룹/항목
- 5분봉과 과거 데이터 준비
- 실시간 상태와 SSE 스트림
- 스캐너 설정, 탐지, 성과
- 시장 전체 스캔과 후보
- 마감 추천 생성, 후보 평가, 조회
- 익일 성과와 보유/매도 판단
- 완료된 Forward 실행의 전체 후보 성과 관측
- 백테스트와 전략 분석
- 수동 거래 및 투자 기록

## 원칙

- 날짜와 시각의 timezone 의미를 보존한다.
- 빈 목록과 undefined payload를 구분한다.
- 추천 생성 API는 실제 주문 API가 아니다.
- 점수는 확률로 표시하지 않는다.
- 데이터 품질, 제외 사유, 실행 가정을 결과와 함께 전달한다.
- KIS 오류 원문이나 인증정보를 응답에 노출하지 않는다.

API 변경 시 Controller, DTO, Web client와 테스트를 함께 수정한다. 사용자에게 보이는 enum/영문 reason은 서버 원본을 보존하되 UI 번역 mapping으로 표시한다.

관심종목 실시간 고정은 `PATCH /api/v1/watchlists/{id}/realtime`로 목록 저장과 별도로 변경한다. 전체 후보 관측은 `POST /api/v1/closing-recommendations/candidate-observations/track`으로 계산하고 같은 경로의 GET 조회 API에서 확인한다. 이 결과는 공식 추천 성과와 별도 집계한다.
