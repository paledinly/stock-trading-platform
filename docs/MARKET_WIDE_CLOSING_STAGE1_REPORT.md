# 전체 시장 장마감 추천 1단계 구현 보고서

기준일: 2026-09-07

## 완료 범위

- KIS 국내주식 거래대금 순위 Client
- KIS 국내주식 거래량 순위 Client
- KIS 국내주식 등락률 순위 Client
- KIS 국내주식 체결강도 순위 Client
- KIS 국내주식 신고가·고점 근접 순위 Client
- 순위 결과를 공통 `KisRankingEntry`로 정규화
- 종목코드 기준 후보 합집합 및 순위 출처 보존
- 활성·관리·거래정지·시장·ETF/ETN 필터 적용
- 순위 종류별 가중치로 REST 상세 조회 전 후보 우선순위 계산
- 일부 순위 API 실패 시 성공 결과만으로 계속 실행
- 모든 순위가 실패하거나 비어 있을 때 기존 종목 샘플 방식으로 fallback
- Broad Scan 응답에 순위 API별 성공 여부, 건수, 오류와 fallback 여부 추가
- 후보 응답에 순위 출처와 원본 순위 추가
- KIS Ranking 전용 Rate Limiter 및 Circuit Breaker 추가

## 데이터 흐름

```text
KIS Ranking 5종
  -> 종목코드 기준 합집합
  -> Stock Master 거래 가능 필터
  -> Ranking Score 정렬
  -> 요청 limit만큼 KIS 현재가 조회
  -> Quote Score + Ranking Score
  -> Broad Candidate 반환
```

## 기존 API 호환성

기존 `GET /api/v1/market-wide/scan` 경로와 요청 파라미터는 유지했다. 응답에는 다음 필드가 추가됐다.

- `fallback`
- `rankingSources[]`
- 후보별 `rankingSources[]`
- 후보별 `rankingRanks{}`

## 안전 동작

- 순위 API 한 곳의 장애가 전체 Scan 실패로 전파되지 않는다.
- 다섯 순위가 모두 실패한 경우에만 기존 `broadScanUniverse`를 사용한다.
- KIS 자격 증명이 없으면 fallback 여부와 원천별 실패 사유로 확인할 수 있다.
- 종목 상세 현재가 조회 실패 종목은 해당 실행 결과에서 제외된다.
- 주문 API는 호출하지 않는다.

## 설정

```text
KIS_RANKING_RATE_LIMIT=5
```

기본값은 초당 5회이며 다섯 순위 호출을 한 실행 주기 안에서 처리한다. 실제 KIS 계정 정책을 장중에 확인한 후 보수적으로 조정한다.

## 테스트

- KIS 거래대금 순위 Endpoint, TR ID, 시장 및 정렬 파라미터 검증
- 숫자 콤마 제거와 응답 필드 매핑 검증
- 복수 순위 합집합과 원본 순위 보존
- 관리·거래정지 등 거래 불가 종목 제외
- 일부 순위 실패 시 부분 성공
- 전체 순위 실패 시 fallback

검증 결과:

```text
Backend full test: BUILD SUCCESSFUL
Web production build: SUCCESS
git diff --check: pending final workspace check
```

## 남은 운영 검증

자동 테스트는 고정 Fixture를 사용한다. 따라서 실계정에서 다음을 확인해야 한다.

- 각 TR의 실전 계정 사용 가능 여부
- KOSPI/KOSDAQ 시장 파라미터별 실제 반환 결과
- 순위별 최대 반환 건수
- 장중 Rate Limit 응답
- 일부 종목의 응답 필드 공백 여부
- 휴장·장전·장후 응답 특성

운영 검증이 끝나기 전에는 다음 단계의 자동 Precision 구독 교체를 활성화하지 않는다.

## 다음 단계

2단계는 Broad Snapshot과 후보 이력 저장이다. 당일 추천 입력과 사후 검증을 재현할 수 있도록 시점별 후보, 순위 출처, 데이터 품질 및 제외 사유를 보존한다.
