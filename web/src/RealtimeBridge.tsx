import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  candleFromEvent,
  mergeRealtimeQuote,
  upsertCandle,
  type Candle,
  type CandleEventPayload,
  type Quote,
  type QuoteEventPayload,
} from './marketData'

type MarketEvent<T> = { payload: T }

export function RealtimeBridge() {
  const cache = useQueryClient()

  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const source = new EventSource('/api/v1/stream')
    const updateQuote = (message: MessageEvent) => {
      const event = JSON.parse(message.data) as MarketEvent<QuoteEventPayload>
      const payload = event.payload
      cache.setQueryData<Quote>(['quote', payload.stockCode], current =>
        current ? mergeRealtimeQuote(current, payload) : current)
    }
    const updateCandle = (message: MessageEvent) => {
      const event = JSON.parse(message.data) as MarketEvent<CandleEventPayload>
      const payload = event.payload
      cache.setQueryData<Candle[]>(['candles', payload.stockCode], current =>
        upsertCandle(current, candleFromEvent(payload)))
    }
    source.addEventListener('quote.updated', updateQuote as EventListener)
    source.addEventListener('candle.5m.updated', updateCandle as EventListener)
    source.addEventListener('candle.5m.closed', updateCandle as EventListener)
    return () => source.close()
  }, [cache])

  return null
}