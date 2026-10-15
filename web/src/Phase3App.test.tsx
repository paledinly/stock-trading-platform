/**
 * 대시보드(Phase3App) 화면 테스트예요. fetch를 가짜 함수로 바꿔 서버 없이 화면 글자를 확인해요.
 * 빈 관심종목 안내와 실시간 구독 표(출처, 상태, 정밀 한도)가 제대로 나오는지 봐요.
 */
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Phase3App } from './Phase3App'

// 테스트용 가짜 구독 현황이에요. 두 번째 종목은 이름이 없고 연결 대기 상태예요.
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

// 그룹 편집 테스트에서 쓰는 관심종목 그룹 하나예요(종목 1개).
const groups = {
  groups: [{ id: 7, name: '반도체', version: 3, items: [
    { id: 70, groupId: 7, stockCode: '000660', stockName: 'SK하이닉스', market: 'KOSPI', realtimePinned: false, version: 0 },
  ] }],
}
let watchlists: unknown = { groups: [] }

// 매 테스트 전에 fetch를 가짜로 바꿔요. 구독 API면 위 데이터를, 아니면 관심종목(기본은 빈 목록)을 돌려줘요.
beforeEach(() => {
  sessionStorage.clear()
  watchlists = { groups: [] }
  globalThis.fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => ({
    ok: true,
    status: init?.method === 'DELETE' ? 204 : 200,
    json: async () => url.startsWith('/api/v1/market/subscriptions') ? subscriptions : watchlists,
  })) as typeof fetch
})

/** 재시도 없는 새 쿼리 캐시로 대시보드를 그려요. 받는 것·돌려주는 것: 없음. */
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
  fireEvent.click(screen.getByRole('tab', { name: /실시간 구독/ }))
  expect(screen.getByRole('tab', { name: /실시간 구독/ })).toHaveAttribute('aria-selected', 'true')
  expect(await screen.findByRole('tab', { name: '실시간 구독 34' })).toBeInTheDocument()
  const panel = await screen.findByRole('region', { name: '실시간 구독 종목' })
  expect(await within(panel).findByText('삼성전자')).toBeInTheDocument()
  expect(within(panel).getByText('34 / 41')).toBeInTheDocument()
  expect(within(panel).getByText('1 / 34')).toBeInTheDocument()
  expect(within(panel).getByText('고정됨')).toBeInTheDocument()
  expect(within(panel).getAllByText('다음날 관측').length).toBeGreaterThan(1)
  expect(within(panel).getByText(/71\.2/)).toBeInTheDocument()
  // A missing name falls back to the code; queued state is shown as waiting.
  expect(within(panel).getAllByText('000400').length).toBeGreaterThan(0)
  expect(within(panel).getByText('연결 대기')).toBeInTheDocument()
})

// 구독 종목을 누르면 오른쪽 상세가 그 종목으로 바뀌고, 탭을 다시 열어도 마지막 탭이 기억되는지 봐요.
test('selects a subscribed stock for the chart and remembers the tab', async () => {
  renderDashboard()
  fireEvent.click(screen.getByRole('tab', { name: /실시간 구독/ }))
  fireEvent.click(await screen.findByRole('button', { name: /삼성전자/ }))
  expect(await screen.findByRole('heading', { name: '삼성전자' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /삼성전자/ })).toHaveAttribute('aria-current', 'true')
  expect(sessionStorage.getItem('dashboard.tab')).toBe('subscriptions')
})

// 그룹 이름을 바꾸면 version과 함께 PATCH를 보내는지 봐요.
test('renames a watchlist group in place', async () => {
  watchlists = groups
  renderDashboard()
  fireEvent.click(await screen.findByRole('button', { name: '반도체 이름 변경' }))
  fireEvent.change(screen.getByLabelText('반도체 새 이름'), { target: { value: '메모리' } })
  fireEvent.click(screen.getByRole('button', { name: '저장' }))
  await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith('/api/v1/watchlist-groups/7',
    expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ name: '메모리', version: 3 }) })))
})

// 그룹 삭제는 확인을 받은 뒤에만 DELETE를 보내는지 봐요.
test('deletes a watchlist group only after confirmation', async () => {
  watchlists = groups
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  renderDashboard()
  const remove = await screen.findByRole('button', { name: '반도체 그룹 삭제' })
  fireEvent.click(remove)
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('종목 1개'))
  expect(globalThis.fetch).not.toHaveBeenCalledWith('/api/v1/watchlist-groups/7', expect.objectContaining({ method: 'DELETE' }))
  fireEvent.click(remove)
  await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith('/api/v1/watchlist-groups/7',
    expect.objectContaining({ method: 'DELETE' })))
  confirm.mockRestore()
})
