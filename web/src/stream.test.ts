/**
 * 공용 SSE 연결(stream.ts) 테스트예요. 가짜 EventSource로 연결이 하나만 열리고, 마지막 사용자가 빠질 때 닫히는지 봐요.
 */
import { subscribeStream } from './stream'

class FakeSource {
  static created = 0
  closed = false
  listeners = new Map<string, EventListener[]>()
  constructor() { FakeSource.created++ }
  addEventListener(name: string, listener: EventListener) { this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener]) }
  removeEventListener(name: string, listener: EventListener) { this.listeners.set(name, (this.listeners.get(name) ?? []).filter(item => item !== listener)) }
  close() { this.closed = true }
}

test('shares one EventSource between subscribers and closes it after the last one leaves', () => {
  const original = globalThis.EventSource
  globalThis.EventSource = FakeSource as unknown as typeof EventSource
  try {
    const quotes = subscribeStream({ 'quote.updated': () => {} })
    const radar = subscribeStream({ 'scanner.momentum.detected': () => {} })
    expect(FakeSource.created).toBe(1)
    quotes()
    radar()
    const reopened = subscribeStream({ 'quote.updated': () => {} })
    expect(FakeSource.created).toBe(2)
    reopened()
  } finally {
    globalThis.EventSource = original
  }
})
