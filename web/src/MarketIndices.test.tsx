/**
 * 고정 지수 칸(MarketIndices) 테스트예요. 가짜 fetch로 장중 관측·일봉 종가·자료 없음 응답을 주고
 * 값·등락률 부호·색 구분, 자료가 없을 때 칸을 숨기는지 확인해요.
 */
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MarketIndices, type MarketIndexView } from './MarketIndices'

function mount(rows: MarketIndexView[]) {
  globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => rows }) as typeof fetch
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MarketIndices />
  </QueryClientProvider>)
}

test('shows each index with a signed change and its tone', async () => {
  mount([
    { name: 'KOSPI', value: 5698.79, changePct: 2.6, observedAt: '2026-10-16T01:05:00Z', source: 'INTRADAY', previousDate: '2026-10-15', previousClose: 5555 },
    { name: 'KOSDAQ', value: 812.3, changePct: -0.42, observedAt: '2026-10-16T01:05:00Z', source: 'INTRADAY', previousDate: '2026-10-15', previousClose: 815.7 },
  ])
  const kospi = (await screen.findByText('KOSPI')).closest('p')!
  expect(kospi).toHaveClass('up')
  expect(kospi).toHaveTextContent('5,698.79')
  expect(kospi).toHaveTextContent('+2.60%')
  expect(screen.getByText('KOSDAQ').closest('p')).toHaveClass('down')
  expect(screen.getByText('-0.42%')).toBeInTheDocument()
  expect(screen.getByText('10:05 수신')).toBeInTheDocument()
})

test('renders nothing when no index value is stored', async () => {
  const { container } = mount([
    { name: 'KOSPI', value: null, changePct: null, observedAt: null, source: 'NONE', previousDate: null, previousClose: null },
  ])
  await new Promise(resolve => setTimeout(resolve, 0))
  expect(container).toBeEmptyDOMElement()
})
