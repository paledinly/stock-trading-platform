/**
 * 시세(Quote)와 5분봉(Candle) 데이터 타입(데이터 모양 정의)과, 실시간 이벤트를 화면 데이터에 합치는 함수를 모아 둔 파일이에요.
 * 서버 이벤트는 숫자가 문자열로 올 수 있어서 여기서 숫자로 바꿔요.
 * RealtimeBridge와 대시보드 화면, 그리고 marketData.test.ts가 써요.
 */
/** 화면에 보여 주는 현재 시세 한 건의 모양이에요. quotedAt은 원천 발생시각(서버 이벤트의 occurredAt)이에요. */
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

/** 5분봉 한 개의 모양이에요. finalCandle은 확정 여부, revision은 같은 봉이 몇 번 고쳐졌는지예요. */
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

/** SSE로 받는 실시간 시세 이벤트 내용이에요. 숫자가 문자열로 올 수 있어요. */
export type QuoteEventPayload = {
  stockCode: string
  price: string | number
  cumulativeVolume: string | number
  cumulativeTradingValue: string | number
  occurredAt: string
}

/** SSE로 받는 5분봉 이벤트 내용이에요. final이 true면 확정봉이에요. */
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

/**
 * 기존 시세에 실시간 체결가를 합쳐 새 시세를 만들어요.
 * 받는 것: current(지금 캐시의 시세), payload(실시간 이벤트). 돌려주는 것: 합쳐진 새 시세(원본은 바꾸지 않아요).
 */
export function mergeRealtimeQuote(current: Quote, payload: QuoteEventPayload): Quote {
  const currentPrice = Number(payload.price)
  // 전일 종가 = 현재가 - 전일 대비. 이 값을 기준으로 등락과 등락률을 다시 계산해요.
  const previousClose = current.currentPrice - current.change
  const change = currentPrice - previousClose
  const changeRate = previousClose === 0 ? 0 : change / previousClose * 100
  return {
    ...current,
    currentPrice,
    change,
    changeRate,
    // 고가·저가는 넓히기만 하고, 누적 거래량·대금은 줄어들지 않게 해서 늦게 온 이벤트가 값을 되돌리지 않게 해요.
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

/**
 * 서버 5분봉 이벤트를 화면용 Candle 모양으로 바꿔요.
 * 받는 것: payload(봉 이벤트). 돌려주는 것: 숫자로 바뀐 Candle.
 */
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

/**
 * 봉 목록에 새 봉을 넣거나(insert) 같은 시작시각 봉을 갈아끼워요(update).
 * 받는 것: current(기존 봉 목록, 없을 수 있음), next(새 봉). 돌려주는 것: 새 봉 목록, 또는 기존 목록이 없으면 undefined.
 */
export function upsertCandle(current: Candle[] | undefined, next: Candle): Candle[] | undefined {
  // 아직 차트 데이터를 조회하지 않았다면 캐시를 새로 만들지 않아요.
  if (!current) return current
  const index = current.findIndex(candle => candle.startTime === next.startTime)
  if (index < 0) return [...current, next].sort((a, b) => a.startTime.localeCompare(b.startTime))
  // 더 오래된 수정본(revision이 작은 이벤트)이 늦게 와도 최신 봉을 덮어쓰지 않아요.
  if (current[index].revision > next.revision) return current
  const updated = [...current]
  updated[index] = next
  return updated
}
