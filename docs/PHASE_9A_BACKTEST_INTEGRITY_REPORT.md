# Phase 9A Backtest Integrity Report

기준일: 2026-09-04

Phase 9A는 장마감 오버나잇 백테스트 결과가 실제 매매 가능한 데이터 범위와 체결 가정을 지키는지 확인하는 정합성 리포트를 추가했다.

## 완료 내용

- 오버나잇 백테스트 응답에 `integrity` 리포트 추가
- 백테스트 샘플 수 부족 경고
- 장마감 추천 탐지 시각 검증
- 추천일과 탐지일 불일치 검증
- 다음 거래일 데이터 누락 검증
- 다음 거래일이 추천일 이후인지 검증
- 5분봉 중복 검증
- 5분봉 간격 누락 검증
- 비정상 OHLC 검증
- 음수 거래량/거래대금 검증
- 장 시작 5분봉 누락 경고
- 같은 5분봉 내 목표가와 손절가 동시 도달 경고
- 종목/날짜 편중 경고
- 장마감 추천 화면의 오버나잇 백테스트 결과에 정합성 패널 추가

## 주요 파일

- `backend/src/main/java/com/sunmo/stockplatform/closing/application/BacktestIntegrityService.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/application/OvernightBacktestService.java`
- `backend/src/main/java/com/sunmo/stockplatform/closing/api/ClosingRecommendationDtos.java`
- `backend/src/test/java/com/sunmo/stockplatform/closing/OvernightBacktestServiceTest.java`
- `web/src/ClosingRecommendationApp.tsx`
- `web/src/closingRecommendation.css`

## 리포트 상태

정합성 상태는 다음 기준으로 계산한다.

```text
PASS    실패와 경고 없음
WARNING 실패는 없지만 경고 존재
FAIL    하나 이상의 오류 존재
```

## 현재 검사 범위

### 데이터 범위

- 다음 거래일 확정 5분봉 존재 여부
- 완료 결과의 검증 대상 candle 존재 여부
- 5분봉 중복 여부
- 5분봉 간격 누락 여부
- 장 시작 candle 누락 여부

### 미래 데이터 참조

- 탐지일과 추천일 일치 여부
- 탐지 시각이 14:30 이전인지 여부
- 탐지 시각이 15:30 이후인지 여부
- 성과 계산일이 추천일 이후인지 여부

### 체결 가정

- 같은 5분봉에서 목표가와 손절가가 동시에 도달했는지 여부

### 표본 편향

- 최소 샘플 수 미달
- 고유 종목 수 부족
- 특정 날짜 결과 집중

## 검증 결과

```text
Backend tests: passed
Web tests: passed
Web build: passed
git diff --check: passed
```

## 주의사항

- 현재 정합성 리포트는 백테스트 실행 결과에 즉시 포함되며 별도 DB 저장은 하지 않는다.
- 같은 5분봉 안에서 목표가와 손절가가 모두 도달한 경우 실제 선후관계는 알 수 없으므로 `EXECUTION` 경고로 표시한다.
- 일봉 이동평균선 기능이 추가되면 추천일 이후 일봉 데이터가 계산에 섞이지 않는 검증 항목을 추가해야 한다.
- 보유 연장 백테스트가 추가되면 판단 시각 이후 데이터를 사용하지 않는 검증 항목을 추가해야 한다.

## 다음 단계

다음 단계는 `Phase 9B 5분봉 이동평균선 Feature`다.

권장 작업:

- 최근 5분봉 기반 MA5, MA20, MA60 계산
- 현재가와 이동평균선 이격도 계산
- MA5 > MA20 > MA60 정배열 여부 계산
- MA5/MA20 골든크로스 여부 계산
- MA20 지지/이탈 여부 계산
- 장마감 추천과 오버나잇 백테스트가 같은 MA 계산 로직을 재사용하도록 구성
