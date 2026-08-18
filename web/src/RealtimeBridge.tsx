import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'

export function RealtimeBridge() {
  const cache = useQueryClient()
  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const source = new EventSource('/api/v1/stream')
    const updateQuote = (message: MessageEvent) => {
      const event = JSON.parse(message.data)
      const payload = event.payload
      cache.setQueryData(['quote', payload.stockCode], (current: Record<string, unknown> | undefined) =>
        current ? { ...current, currentPrice: Number(payload.price), accumulatedVolume: Number(payload.cumulativeVolume), quotedAt: payload.occurredAt } : current)
    }
    source.addEventListener('quote.updated', updateQuote as EventListener)
    return () => source.close()
  }, [cache])
  return null
}
