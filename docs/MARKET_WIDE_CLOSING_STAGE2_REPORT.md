# 전체 시장 장마감 추천 2단계 구현 보고서

기준일: 2026-09-07

## 완료 범위

- `market_broad_snapshot` 테이블과 Flyway V16 추가
- Asia/Seoul 거래일 및 2분 Snapshot 버킷 적용
- 동일 거래일·버킷·종목의 반복 Scan upsert
- Broad 순위 출처, 원본 순위, Ranking Score JSON 보존
- 현재가, 등락률, 누적 거래량·거래대금, 당일 OHLC 저장
- 체결강도 순위에서 확보된 체결강도 저장
- 최종 Broad Score와 Source Version 저장
- 현재가 조회 실패 후보도 `QUOTE_FAILED`로 저장
- 실패 데이터에 가격 0을 생성하지 않고 null과 실패 사유 보존
- 데이터 품질 `BROAD_C`, `INSUFFICIENT` 구분
- Broad Scan 후보 응답에 Snapshot ID와 데이터 품질 추가
- 날짜·종목별 Snapshot 이력 조회 API 추가

## DB Migration

`V16__create_market_broad_snapshot.sql`

주요 제약:

- Unique: `(session_date, captured_at, stock_id)`
- Data quality: `BROAD_C`, `INSUFFICIENT`
- Collection status: `COLLECTED`, `QUOTE_FAILED`
- 종목·시간 및 거래일·점수 조회 인덱스

## API

기존 Scan:

```text
GET /api/v1/market-wide/scan
```

후보 응답에 다음 값이 추가된다.

- `snapshotId`
- `dataQuality`

이력 조회:

```text
GET /api/v1/market-wide/snapshots?date=2026-09-07&stockCode=005930&limit=100
```

- `date` 생략 시 Asia/Seoul 오늘
- `stockCode` 생략 시 해당 날짜 전체
- `limit` 1~500
- 최신 버킷, 높은 점수 순으로 반환

## 저장 정책

- Scan 시작 시각을 2분 경계로 내림하여 버킷을 결정한다.
- 같은 버킷에서 재실행하면 기존 행을 갱신한다.
- 가격 조회 성공은 `BROAD_C / COLLECTED`다.
- 가격 조회 실패는 `INSUFFICIENT / QUOTE_FAILED`다.
- 실패 후보도 순위 출처와 실패 원인을 남긴다.
- Snapshot 계산 버전은 `broad-ranking-v1`이다.

## 테스트

- 2분 버킷 경계
- 성공 및 실패 후보 동시 저장
- 실패 가격 필드 null 유지
- 순위 JSON과 Source Version
- 데이터 품질 및 상태
- Flyway V16과 JPA 전체 Context

검증 결과:

```text
Backend full test: BUILD SUCCESSFUL
```

## 현재 범위 밖

- Snapshot 자동 보존기간 정리
- 장중 자동 실행 Scheduler
- Precision 구독 자동 할당
- Broad Snapshot의 장마감 추천 직접 편입
- 운영 데이터 기반 인덱스 튜닝

다음 단계는 **자동 Precision 구독 할당**이다.
