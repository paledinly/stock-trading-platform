# Market-wide Closing Stage 5 Report

기준일: 2026-09-08

## 완료 범위

- 장마감 추천 입력을 Precision `ScannerDetection`과 Broad `MarketBroadSnapshot`으로 통합했다.
- 같은 종목이 양쪽에 존재하면 Precision 후보를 우선한다.
- Broad 후보는 `BROAD_C`이면서 현재가, 등락률, 거래대금이 있는 경우만 평가한다.
- 관리·거래정지·ETF·ETN·비활성 종목은 양쪽 모두 최종 단계에서 다시 제외한다.
- Broad 점수 공식은 Precision과 분리하고 추천 점수를 최대 65점으로 제한했다.
- Broad에서 확보할 수 없는 VWAP, Volume Ratio, 체결강도는 0으로 위장하지 않고
  `missing_features`에 기록하며 점수와 위험에 패널티를 적용한다.
- 추천 결과에 후보 출처, 데이터 품질, 관측 범위, Broad Snapshot ID 및 누락 Feature를 저장·노출한다.
- 추천 평가 시간은 Asia/Seoul 14:30~15:30으로 제한하며 당일 실행 중에는 현재 시각 이후 데이터를 차단한다.
- 기존 날짜별 삭제 후 재생성 동작을 유지해 `(recommendation_date, stock_id)` 멱등성을 보장한다.

## DB 변경

`V18__extend_closing_recommendation_sources.sql`

- `source_detection_id`, `scanner_type` nullable 전환
- `candidate_source`
- `data_quality`
- `coverage_minutes`
- `broad_snapshot_id`
- `candidate_observed_at`
- `missing_features`

기존 추천은 `PRECISION`, `PRECISION_B`로 보정하고 원본 Detection의 탐지 시각을 이관한다.

## API/UI 변경

기존 장마감 추천 API 경로를 유지하면서 응답에 다음 필드를 추가했다.

- `candidateSource`
- `dataQuality`
- `coverageMinutes`
- `broadSnapshotId`
- `missingFeatures`
- 생성 응답의 `sourceBroadSnapshots`

웹 추천 카드에서 정밀/Broad 출처, 품질, 관측 시간과 누락 Feature를 확인할 수 있다.

## 검증

- Backend: 72 tests passed
- Web: 8 test files, 10 tests passed
- Web production build passed
- Precision/Broad 중복 시 Precision 우선 테스트
- Broad-only 포함 및 `INSUFFICIENT` 제외 테스트
- Broad 점수 상한과 누락 Feature 보존 테스트

## 운영상 주의

- Broad 추천은 Precision 추천과 같은 신뢰도로 해석하면 안 된다.
- `coverage_minutes`는 현재 추천 평가구간 내 Detection 관측 간격이다. 영속적인 WebSocket 구독 이력 기반
  체류시간은 단계 6 Coverage 구현에서 별도로 측정해야 한다.
- Broad 추천 성과는 아직 과거 데이터로 검증되지 않았다. 단계 6에서 출처별 성과와 포착률을 분리한다.
