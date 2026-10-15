/**
 * 서버의 실시간 스트림(SSE, 서버가 브라우저로 계속 밀어 주는 이벤트)을 받아 React Query 캐시에 반영해요.
 * 시세가 오면 ['quote', 종목코드] 캐시를, 5분봉이 오면 ['candles', 종목코드] 캐시를 고쳐 써요.
 * main.tsx가 앱 최상단에 한 번 붙여서 써요.
 */
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
import { subscribeStream } from './stream'

/** 서버가 보내는 이벤트 봉투 모양(타입)이에요. 실제 내용은 payload 안에 있어요. */
type MarketEvent<T> = { payload: T }

/**
 * 실시간 이벤트를 캐시로 옮겨 주는 React 컴포넌트(화면 조각)예요. 화면에는 아무것도 그리지 않아요.
 * 비유하면 "우편물을 받아 각 집 우편함에 넣어 주는 집배원"이에요.
 * 받는 것: 없음(props 없음). 돌려주는 것: null(빈 화면).
 */
export function RealtimeBridge() {
  const cache = useQueryClient()

  // 마운트될 때 공용 SSE 연결(stream.ts)에 처리기를 붙이고, 사라질 때 떼요.
  useEffect(() => {
    const updateQuote = (message: MessageEvent) => {
      const event = JSON.parse(message.data) as MarketEvent<QuoteEventPayload>
      const payload = event.payload
      // 이미 조회된 시세가 있을 때만 합쳐요. 처음 조회 전에는 캐시를 새로 만들지 않아요.
      cache.setQueryData<Quote>(['quote', payload.stockCode], current =>
        current ? mergeRealtimeQuote(current, payload) : current)
    }
    const updateCandle = (message: MessageEvent) => {
      const event = JSON.parse(message.data) as MarketEvent<CandleEventPayload>
      const payload = event.payload
      // 같은 시작시각 봉이면 revision(수정 번호)을 비교해 갈아끼우고, 새 봉이면 추가해요.
      cache.setQueryData<Candle[]>(['candles', payload.stockCode], current =>
        upsertCandle(current, candleFromEvent(payload)))
    }
    // 진행 중 봉(updated)과 확정 봉(closed)은 같은 처리기로 반영해요.
    return subscribeStream({
      'quote.updated': updateQuote,
      'candle.5m.updated': updateCandle,
      'candle.5m.closed': updateCandle,
    })
  }, [cache])

  return null
}
