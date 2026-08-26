import { candleFromEvent, mergeRealtimeQuote, upsertCandle, type Quote } from './marketData'

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

test('updates an in-progress candle by start time and revision', () => {
  const first = candleFromEvent({ stockCode: '005930', startTime: '2026-08-26T00:00:00Z', open: 100,
    high: 101, low: 99, close: 100, volume: 10, tradingValue: 1000, final: false, revision: 0 })
  const revised = { ...first, high: 103, close: 102, revision: 1 }
  expect(upsertCandle([first], revised)).toEqual([revised])
})
