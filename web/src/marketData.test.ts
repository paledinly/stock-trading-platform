/**
 * marketData.ts의 실시간 시세 합치기와 5분봉 갱신이 맞게 동작하는지 확인하는 테스트예요.
 * 화면이 SSE(서버가 실시간으로 밀어 주는 이벤트)로 받은 값을 잘못 합치면 가격·고가·저가가 틀리게 보이기 때문에 중요해요.
 */
import { candleFromEvent, mergeRealtimeQuote, upsertCandle, type Quote } from './marketData'

/**
 * 확인하는 것: 실시간 체결 하나를 기존 시세에 합칠 때 시가는 그대로, 고가는 새 가격까지 넓어지는지 봐요.
 * 준비: 현재가 70,000원 시세와 71,000원 체결 이벤트(입력)
 * 기대: 고가 71,000, 전일 대비 +2,000원(+2.9%), 누적 거래대금은 새 값으로 바뀌어요(결과).
 */
test('merges realtime quote while preserving and extending daily range', () => {
  const quote: Quote = { currentPrice: 70000, change: 1000, changeRate: 1.45, openPrice: 69000,
    highPrice: 70500, lowPrice: 68800, accumulatedVolume: 1000, accumulatedTradingValue: 70000000 }
  const merged = mergeRealtimeQuote(quote, { stockCode: '005930', price: '71000', cumulativeVolume: '1200',
    cumulativeTradingValue: '85000000', occurredAt: '2026-08-26T01:00:01Z' })
  expect(merged.openPrice).toBe(69000)
  expect(merged.highPrice).toBe(71000)
  expect(merged.lowPrice).toBe(68800)
  expect(merged.change).toBe(2000)
  expect(merged.changeRate).toBeCloseTo(2.89855)
  expect(merged.accumulatedTradingValue).toBe(85000000)
})

/**
 * 확인하는 것: 아직 끝나지 않은 5분봉(revision, 수정 번호)이 다시 오면 같은 시작 시각의 봉을 새 값으로 바꾸는지 봐요.
 * 준비: 같은 시작 시각의 봉 두 개, 두 번째는 고가·종가가 바뀐 수정본(입력)
 * 기대: 목록에 봉이 하나만 남고 그 봉이 수정본이에요(결과).
 */
test('updates an in-progress candle by start time and revision', () => {
  const first = candleFromEvent({ stockCode: '005930', startTime: '2026-08-26T00:00:00Z', open: 100,
    high: 101, low: 99, close: 100, volume: 10, tradingValue: 1000, final: false, revision: 0 })
  const revised = { ...first, high: 103, close: 102, revision: 1 }
  expect(upsertCandle([first], revised)).toEqual([revised])
})
