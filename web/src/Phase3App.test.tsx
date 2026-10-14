import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Phase3App } from './Phase3App'

const subscriptions = {
  limit: 41, remaining: 7, precisionState: 'FROZEN', precisionCapacity: 34,
  items: [
    { stockCode: '005930', stockName: '삼성전자', market: 'KOSPI', sources: ['OVERNIGHT', 'PRECISION'],
      state: 'SUBSCRIBED', stateMessage: 'SUBSCRIBE SUCCESS', stateUpdatedAt: '2026-10-14T00:08:42Z',
      precisionScore: 71.234, precisionAddedAt: '2026-10-14T00:10:00Z' },
    { stockCode: '000400', stockName: null, market: null, sources: ['OVERNIGHT'],
      state: 'QUEUED', stateMessage: 'waiting for connection', stateUpdatedAt: '2026-10-14T00:08:41Z',
      precisionScore: null, precisionAddedAt: null },
  ],
}

beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation(async (url: string) => ({
    ok: true,
    status: 200,
    json: async () => url.startsWith('/api/v1/market/subscriptions') ? subscriptions : { groups: [] },
  })) as typeof fetch
})

function renderDashboard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><Phase3App /></QueryClientProvider>)
}

test('renders the responsive stock dashboard and empty watchlist', async () => {
  renderDashboard()
  expect(screen.getByText('오늘의 시장을')).toBeInTheDocument()
  expect(await screen.findByText('첫 관심종목 그룹을 만들어보세요')).toBeInTheDocument()
  expect(screen.getByLabelText('종목 검색')).toBeInTheDocument()
})

test('lists realtime subscriptions with their source, state and precision capacity', async () => {
  renderDashboard()
  const panel = await screen.findByRole('region', { name: '실시간 구독 종목' })
  expect(await within(panel).findByText('삼성전자')).toBeInTheDocument()
  expect(within(panel).getByText('34 / 41')).toBeInTheDocument()
  expect(within(panel).getByText('1 / 34')).toBeInTheDocument()
  expect(within(panel).getByText('고정됨')).toBeInTheDocument()
  expect(within(panel).getAllByText('다음날 관측').length).toBeGreaterThan(1)
  expect(within(panel).getByText('71.2')).toBeInTheDocument()
  // A missing name falls back to the code; queued state is shown as waiting.
  expect(within(panel).getAllByText('000400').length).toBeGreaterThan(0)
  expect(within(panel).getByText('연결 대기')).toBeInTheDocument()
})
