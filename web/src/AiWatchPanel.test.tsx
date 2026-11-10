/**
 * AI 실시간 감시 탭 테스트예요. fetch를 가짜 함수로 바꿔 2단계 판정 카드, 종목 상태, 가상 매매, 조건 표, 상태 이력을 확인해요.
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AiPlanPage } from './AiPlanPage'

const group = (rule: string, exit: string, meanNetPct: number) => ({ rule, exit, scope: 'ALL', trades: 3, days: 2, meanNetPct, winRate: 66.7, paired: 3, meanVsOpenPct: 0.2 })
const summary = { verdict: 'PENDING', minTrades: 40, minDays: 15, gate: group('P', 'E1', 0.42), groups: [group('P', 'E1', 0.42), group('P', 'E2', -0.3), group('A', 'E1', 0.15)] }
const position = { stockCode: '005930', rule: 'P', shadow: true, signalAt: '2026-11-11T00:20:03Z', entrySourceAt: '2026-11-11T00:20:10Z', entryTickPrice: 102.7, entryPrice: 102.75, quantity: 97322, capacityLimited: false, stopPrice: 101.1, spreadSource: 'ORDERBOOK', e1: null, e2: { price: 105.8, at: '2026-11-11T00:30:05Z', reason: 'TAKE', netPct: 2.6 }, maxPrice: 105.9, minPrice: 102.7, trackingGap: false }
const view = {
  date: '2026-11-11', live: true, ruleVersion: 'ai-watch-p1', positionAmount: 10000000, positions: [position],
  session: { tradeDecision: 'WATCH_ONLY', startedAt: '2026-11-10T23:50:00Z', endedAt: null, pickCount: 2, subscribedCount: 2, unsubscribedCodes: null, positionAmount: 10000000, ruleVersion: 'ai-watch-p1' },
  stocks: [{
    pickId: 1, stockCode: '005930', stockName: '삼성전자', priority: 1, topPriority: true, subscribed: true, stateP: 'BUY_CANDIDATE', stateA: 'UNSUPPORTED',
    indicators: { price: '103.8', vwap: '101.75', or15High: '102', or15Low: '100', rvol5m: '5' },
    checksP: { BREAKOUT_5M: 'PASS', MARKET: 'UNAVAILABLE' }, checksA: {}, unsupportedA: ['지표 SECTOR_STRENGTH'], positions: [position],
  }],
  events: [{ id: 1, stockCode: '005930', rule: 'P', fromState: 'READY', toState: 'BUY_CANDIDATE', reasons: '', evaluatedAt: '2026-11-11T00:20:03Z' }],
}

beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => {
    const body = url.endsWith('/watch/summary') ? summary : url.includes('/watch') ? view : url.endsWith('/ai-plans') ? [] : {}
    return Promise.resolve({ ok: true, status: 200, json: async () => body })
  }) as unknown as typeof fetch
})

it('shows the live watch, virtual trades and the phase two gate', async () => {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AiPlanPage /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('tab', { name: '실시간 감시' }))

  expect((await screen.findAllByText('삼성전자')).length).toBe(2)
  expect(screen.getByText('기록 중')).toBeInTheDocument()
  expect(screen.getByText('3 / 40건 · 2 / 15일')).toBeInTheDocument()
  expect(screen.getByText('+0.42%')).toBeInTheDocument()
  expect(screen.getByText('감시 중')).toBeInTheDocument()
  expect(screen.getByText(/계획 감시만 \(진입은 그림자 기록\) · 구독 2\/2종목/)).toBeInTheDocument()
  expect(screen.getAllByText('매수 후보').length).toBeGreaterThan(0)
  expect(screen.getByText('지원 안 됨')).toBeInTheDocument()
  expect(screen.getByText('P 그림자 102.75')).toBeInTheDocument()
  expect(screen.getByText('E2 익절 +2.60%')).toBeInTheDocument()
  // 아직 청산 안 된 E1은 지금 가격 기준 평가손익이에요: 103.8 ÷ 102.75 − 1
  expect(screen.getByText('E1 보유 +1.02%')).toBeInTheDocument()

  fireEvent.click(screen.getAllByText('삼성전자')[0])
  expect(screen.getByText('✓ 5분봉 돌파')).toBeInTheDocument()
  expect(screen.getByText('? 시장')).toBeInTheDocument()
  expect(screen.getByText('지원 안 됨: 지표 SECTOR_STRENGTH')).toBeInTheDocument()
})
