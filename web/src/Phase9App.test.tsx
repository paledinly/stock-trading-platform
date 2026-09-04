import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Phase9App } from './Phase9App'

beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => Promise.resolve({
    ok: true,
    json: async () => url.includes('backtests/stocks')
      ? [
          {
            stockCode: '005930',
            stockName: '삼성전자',
            market: 'KOSPI',
            candleCount: 72,
            firstCandleAt: '2026-09-03T00:00:00Z',
            lastCandleAt: '2026-09-03T06:30:00Z',
          },
        ]
      : url.includes('backtests')
      ? {
          stockCode: '005930',
          stockName: '삼성전자',
          from: '2026-09-03T00:00:00Z',
          to: '2026-09-03T06:30:00Z',
          evaluatedCandles: 0,
          virtualDetections: 0,
          summaries: [],
          detections: [],
        }
      : url.includes('closing-recommendations')
        ? []
      : [],
  })) as typeof fetch
})

test('opens backtesting workspace', async () => {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Phase9App /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: '고급' }))
  fireEvent.click(screen.getByRole('button', { name: /백테스트/ }))
  expect(screen.getByRole('heading', { name: '전략 재생' })).toBeInTheDocument()
  expect(screen.getByText('사용 안내')).toBeInTheDocument()
  expect(await screen.findByText('조회 가능한 종목')).toBeInTheDocument()
  expect(await screen.findByText('전략 통계가 없습니다')).toBeInTheDocument()
})

test('opens closing recommendation workspace', async () => {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Phase9App /></QueryClientProvider>)
  expect(screen.queryByRole('button', { name: /백테스트/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /마감 추천/ }))
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  expect(screen.getByText('사용 안내')).toBeInTheDocument()
  expect(await screen.findByText('추천 후보가 없습니다')).toBeInTheDocument()
})
