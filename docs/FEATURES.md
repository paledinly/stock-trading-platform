# 기능 목록과 상태

무엇이 켜져 있고, 무엇을 판단 근거로 쓰는지 한곳에서 확인한다. 기능을 켜거나 끄거나 새로 추가하면 이 표를 같은 커밋에서 갱신한다.

## 상태 정의

| 상태 | 의미 |
| --- | --- |
| 운영 | 켜져 있고 결과를 현재 판단에 사용한다 |
| 실험 | 켜져 있지만 데이터 수집·비교용이다. 결과를 수익성 근거로 쓰지 않는다 |
| 동결 | 코드는 있지만 꺼져 있다. 다른 작업의 전제가 될 때까지 새 작업을 하지 않는다 |
| 폐기 후보 | 쓰임이 확인되지 않았다. 제거 여부를 사용자가 결정한다 |

## 목록 (2026-10-13)

"코드 기본값"은 `application.yml`, "이 PC"는 운영 PC `.env` 기준이다. 다른 환경은 각자 확인한다.

### 수집과 기반

| 기능 | 설정 | 코드 기본값 | 이 PC | 상태 | 문서 |
| --- | --- | --- | --- | --- | --- |
| KIS REST 연동 | `KIS_ENABLED` | 꺼짐 | 켜짐 | 운영 | [MARKET_DATA](MARKET_DATA.md) |
| 실시간 체결 WebSocket | `MARKET_REALTIME_ENABLED` | 꺼짐 | 켜짐 | 운영 | [MARKET_DATA](MARKET_DATA.md) |
| 5분봉 집계·Scanner·실시간 레이더 화면 | 실시간 수집에 포함 | — | 켜짐 | 운영 | [MARKET_DATA](MARKET_DATA.md) |
| 분봉 백필 | `MARKET_BACKFILL_ENABLED` | 켜짐 | 켜짐 | 운영 | [MARKET_DATA](MARKET_DATA.md) |
| 일봉 백필 (마감 후보용) | `CLOSING_DAILY_BACKFILL_ENABLED` | 켜짐 | 켜짐 | 운영 | [OPERATIONS](OPERATIONS.md) |
| KIS 공식 거래일 달력 | `CLOSING_CALENDAR_KIS_REFRESH_ENABLED` | 꺼짐 | 켜짐 | 운영 | [OPERATIONS](OPERATIONS.md) |
| 종목 마스터 자동 동기화 | `KIS_MASTER_SYNC_ENABLED` | 꺼짐 | 꺼짐 | 동결 | [OPERATIONS](OPERATIONS.md) |
| 시장 전체 Broad 스캔 | `MARKET_WIDE_SCHEDULE_ENABLED`, `MARKET_WIDE_ENRICHMENT_ENABLED` | 꺼짐 / 켜짐 | 켜짐 / 켜짐 | 운영 | [API](API.md) |
| Precision 후보 | `MARKET_PRECISION_ENABLED` | 꺼짐 | 켜짐 | 운영 | [TRADING_STRATEGY](TRADING_STRATEGY.md) |

### 전략

| 기능 | 설정 | 코드 기본값 | 이 PC | 상태 | 문서 |
| --- | --- | --- | --- | --- | --- |
| 마감 추천 v9 자동 실행·가상 진입·성과 | `CLOSING_AUTOMATION_ENABLED` | 켜짐 | 켜짐 | 실험 ([ADR-008](decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md) 판단 대기) | [TRADING_STRATEGY](TRADING_STRATEGY.md) |
| 마감 후보 관측 (탈락 후보 동일 규칙 평가) | 자동화에 포함, `CLOSING_OBSERVATION_SUBSCRIPTION_LIMIT` | 켜짐, 10 | 켜짐, 10 | 실험 | [TRADING_STRATEGY](TRADING_STRATEGY.md) |
| 신호 성과 분석 (Scanner 탐지 성과) | 실시간 수집에 포함 | — | 켜짐 | 실험 | [BACKTESTING](BACKTESTING.md) |
| 전략 재생·백테스트 | API·화면에서 실행 | — | — | 실험 | [BACKTESTING](BACKTESTING.md) |

### 추가 데이터 (마감 Trajectory)

| 기능 | 설정 | 코드 기본값 | 이 PC | 상태 | 문서 |
| --- | --- | --- | --- | --- | --- |
| Trajectory 1분 Feature | `CLOSING_TRAJECTORY_ENABLED` | 꺼짐 | 켜짐 | 실험 | [CLOSING_TRAJECTORY](CLOSING_TRAJECTORY.md) |
| 수급 (외국인·기관·프로그램) | `CLOSING_TRAJECTORY_FLOW_ENABLED` | 꺼짐 | 켜짐 | 실험 | [INVESTOR_FLOW](INVESTOR_FLOW.md) |
| 지수 수집 | `CLOSING_TRAJECTORY_INDEX_ENABLED` | 꺼짐 | 꺼짐 | 동결 (장중 Regime 작업 시 재개) | [CLOSING_TRAJECTORY](CLOSING_TRAJECTORY.md) |
| 대량체결 집계 | `CLOSING_MICROSTRUCTURE_ENABLED` | 꺼짐 | 꺼짐 | 동결 | [MICROSTRUCTURE](MICROSTRUCTURE.md) |
| 호가 REST 표본 | `CLOSING_ORDERBOOK_ENABLED` | 꺼짐 | 꺼짐 | 동결 | [MICROSTRUCTURE](MICROSTRUCTURE.md) |

### 기록과 화면

| 기능 | 화면 | 상태 | 문서 |
| --- | --- | --- | --- |
| 관심종목·초기 관심종목 (`WATCHLIST_INITIAL_ENABLED`) | `Phase3App` | 운영 | [README](../README.md) |
| 투자기록 (수동 거래 원장) | `Phase4App` 투자기록 | 운영 | [ADR-007](decisions/ADR-007-TRADE-LEDGER.md) |
| 실시간 레이더 | `Phase6App` | 운영 | [MARKET_DATA](MARKET_DATA.md) |
| 신호 성과 분석 | `Phase7App` | 실험 | [BACKTESTING](BACKTESTING.md) |
| 시장 전체 스캐너 | `Phase8App` | 운영 | [API](API.md) |
| 전략 재생 | `Phase9App` | 실험 | [BACKTESTING](BACKTESTING.md) |
| 오늘의 마감 추천 | `ClosingRecommendationApp` | 실험 | [TRADING_STRATEGY](TRADING_STRATEGY.md) |

### 제거됨 (2026-10-13)

장중 추천(`INTRADAY_ENABLED`), 장중 비용 Net 전용(`INTRADAY_COSTS_FOR_NET_ONLY`), 장중 진입·청산 비교 실험, 실시간 장중 추천 화면(`IntradayApp`)을 기준선 검증 실패로 제거했다([진입 비교](../audit/2026-10-13-intraday-entry-comparison/README.md), [실패 분석](../audit/2026-10-13-intraday-failure-analysis/README.md)). `intraday_input`·`intraday_recommendation` 테이블은 DB에 남아 있으며 처리 방법은 사용자 확인 후 정한다.

현재 "폐기 후보"는 없다. 동결 기능 중 3개월 동안 재개 계획이 없는 것은 폐기 후보로 옮겨 사용자에게 묻는다.

## 신규 기능 추가 규칙

마감 v9의 1차 체크포인트([ADR-008](decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md))가 나올 때까지 다음을 지킨다.

- 새 데이터 수집, 새 전략, 새 화면은 추가하지 않는다. 측정 신뢰성 수정, 버그 수정, 이미 등록된 비교 실험은 예외다.
- 새 실험 옵션은 기존 옵션 하나를 정리하거나, 결과가 나온 실험이 있을 때만 추가한다.
- 동결 기능은 다른 작업의 전제가 될 때만 재개하고, 재개하면 이 표의 상태를 바꾼다.
