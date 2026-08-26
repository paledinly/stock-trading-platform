export type Quote = {
  currentPrice: number
  change: number
  changeRate: number
  openPrice: number
  highPrice: number
  lowPrice: number
  accumulatedVolume: number
  accumulatedTradingValue: number
  quotedAt?: string
}

export type Candle = {
  startTime: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  tradingValue: number
  finalCandle: boolean
  revision: number
}

export type QuoteEventPayload = {
  stockCode: string
  price: string | number
  cumulativeVolume: string | number
  cumulativeTradingValue: string | number
  occurredAt: string
}

export type CandleEventPayload = {
  stockCode: string
  startTime: string
  open: string | number
  high: string | number
  low: string | number
  close: string | number
  volume: string | number
  tradingValue: string | number
  final: boolean
  revision: number
}

export function mergeRealtimeQuote(current: Quote, payload: QuoteEventPayload): Quote {
  const currentPrice = Number(payload.price)
  const previousClose = current.currentPrice - current.change
  const change = currentPrice - previousClose
  const changeRate = previousClose === 0 ? 0 : change / previousClose * 100
  return {
    ...current,
    currentPrice,
    change,
    changeRate,
    highPrice: Math.max(current.highPrice || currentPrice, currentPrice),
    lowPrice: Math.min(current.lowPrice || currentPrice, currentPrice),
    accumulatedVolume: Math.max(current.accumulatedVolume, Number(payload.cumulativeVolume)),
    accumulatedTradingValue: Math.max(
      current.accumulatedTradingValue,
      Number(payload.cumulativeTradingValue),
    ),
    quotedAt: payload.occurredAt,
  }
}

export function candleFromEvent(payload: CandleEventPayload): Candle {
  return {
    startTime: payload.startTime,
    open: Number(payload.open),
    high: Number(payload.high),
    low: Number(payload.low),
    close: Number(payload.close),
    volume: Number(payload.volume),
    tradingValue: Number(payload.tradingValue),
    finalCandle: payload.final,
    revision: payload.revision,
  }
}

export function upsertCandle(current: Candle[] | undefined, next: Candle): Candle[] | undefined {
  if (!current) return current
  const index = current.findIndex(candle => candle.startTime === next.startTime)
  if (index < 0) return [...current, next].sort((a, b) => a.startTime.localeCompare(b.startTime))
  if (current[index].revision > next.revision) return current
  const updated = [...current]
  updated[index] = next
  return updated
}
