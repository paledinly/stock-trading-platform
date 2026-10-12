# 원천시각 > 수신시각 역전 원인 — 2026-10-12

## 결론

10-08 진단의 "source>receipt 시간역전 63,218건(약 8%)"은 KIS 데이터 오류가 아니라 **수집 호스트 시계가 KIS 원천 시계보다 늦었던 시간대**에 발생한 것으로 판단한다. 원천 발생시각은 KIS 체결시각(HHMMSS) 초 단위 절사값이므로 실제 발생보다 늦을 수 없다. 따라서 `occurredAt > receivedAt`은 호스트 시계가 최소 그만큼 늦었다는 뜻이다.

- 2026-10-12 18:10 KST 확인: Windows Time 서비스(w32time)가 **시작되지 않은 상태**(0x80070426). `w32tm /stripchart /computer:time.windows.com` 기준 호스트 시계 오프셋 약 **1.13초**.
- 시간대별 최소 지연(received−occurred)이 하루 동안 단조 변화한다(10-12: 0.27초→1.03초, 10-07: −0.50초→−0.58초). 호스트 시계가 동기화 없이 흐르는 패턴과 일치한다. 10-08은 09시대 최소 −0.88초 이후 10시대 +0.21초로 바뀌어 그 사이 1회 시계 보정이 있었던 것으로 보인다.

## 표본 집계

`intraday_input`의 각 날짜 ID 범위에서 200번째마다 1건을 추출해 DB 안에서 집계했다([sample_skew.sql](sample_skew.sql)). 원장 원문은 내려받지 않았다. 지연 단위는 초.

| 날짜 | 표본 | occurred>received | 최소 지연 범위(시간대별) | 비고 |
| --- | --- | --- | --- | --- |
| 10-01 | 2,497 | 1,176 (47%) | −0.95 ~ −0.68 | 종일 호스트 시계 늦음 |
| 10-06 | 6,604 | 463 (7%) | −0.20 ~ +0.19 | 경계 부근 |
| 10-07 | 11,055 | 4,397 (40%) | −0.58 ~ −0.50 | 종일 늦음 |
| 10-08 | 9,830 | 958 (10%) | −0.88(09시) → +0.21(10시) ~ −0.08(15시) | 09시대에 집중 |
| 10-12 | 6,994 | 0 | +0.27 ~ +1.03 | 역전 없음, 반대 방향으로 흐름 |

전체 원장 집계는 Supabase statement timeout 때문에 표본 집계로 대체했다. 비율은 표본 기준이며 10-08 진단의 전수 수치와 정확히 일치하지 않는다.

## 영향

다음 경로는 `occurredAt > receivedAt` 틱을 버린다.
- `IntradayEngine.accept`/`IntradayTracker.observe`: 장중 추천 입력과 가상 진입·청산 판정. 손절·목표 도달 틱이 누락될 수 있다.
- `ClosingPaperEntryService.executable`: 15:15~15:20 마감 가상 진입. `NO_FRESH_TICK_BEFORE_DEADLINE` 일부의 원인일 수 있다.
- `MinuteFeatureAggregator`: trajectory 분 집계.

거부 규칙 자체는 시각 의미를 지키기 위한 정책이므로 이번에 완화하지 않았다. 허용 오차 도입은 재생 결과를 바꾸므로 별도 비교가 필요하다.

## 조치

1. 코드: `RealtimeDiagnostics.Timing`에 `minMs`, `negativeCount`를 추가했다. 기존에는 음수 지연을 0으로 잘라 역전이 보이지 않았다. `/api/v1/market/realtime/status`의 `timings.sourceToCallback.negativeCount`가 0보다 크면 호스트 시계를 점검한다.
2. 운영(관리자 권한 필요, 미실행): Windows Time 서비스 자동 시작과 짧은 동기화 주기 설정. [OPERATIONS](../../docs/OPERATIONS.md) 참고.
