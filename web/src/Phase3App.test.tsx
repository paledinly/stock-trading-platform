import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Phase3App } from './Phase3App'

beforeEach(() => {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({ groups: [] }),
  }) as typeof fetch
})

test('renders the responsive stock dashboard and empty watchlist', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><Phase3App /></QueryClientProvider>)
  expect(screen.getByText('오늘의 시장을')).toBeInTheDocument()
  expect(await screen.findByText('첫 관심종목 그룹을 만들어보세요')).toBeInTheDocument()
  expect(screen.getByLabelText('종목 검색')).toBeInTheDocument()
})
