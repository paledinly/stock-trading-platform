/**
 * 앱 껍데기(Phase9App) 테스트예요. fetch를 가짜 함수로 바꿔 주소별로 빈 백테스트 결과 등을 돌려줘요.
 * 마지막 화면 복원, 사라진 화면 저장값 처리, 백테스트·마감 추천 화면 열기를 확인해요.
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Phase9App } from './Phase9App'

// 매 테스트 전에 저장된 화면 선택을 지우고, fetch를 주소별 가짜 응답으로 바꿔요.
beforeEach(() => {
  sessionStorage.clear()
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

// 화면을 다시 만들어도(remount) 마감 추천 화면이 그대로 복원되는지 봐요.
test('restores closing workspace after remount and keeps it on focus', async () => {
  const app = () => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Phase9App /></QueryClientProvider>
  const first = render(app())
  fireEvent.click(screen.getByRole('button', { name: /마감 추천/ }))
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  first.unmount()
  render(app())
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  fireEvent(window, new Event('blur'))
  fireEvent(window, new Event('focus'))
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '장중 추천' })).not.toBeInTheDocument()
})

// 예전에 있던 '장중 추천' 같은 없어진 화면이 저장돼 있으면 대시보드로 돌아가는지 봐요.
test('falls back to dashboard when a removed intraday page was saved', () => {
  sessionStorage.setItem('workspace.page', 'intraday')
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Phase9App /></QueryClientProvider>)
  expect(screen.getByRole('button', { name: '대시보드' })).toHaveClass('active')
})

// 고급 모드로 바꾼 뒤 백테스트 화면이 열리는지 봐요.
test('opens backtesting workspace', async () => {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Phase9App /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: '고급' }))
  fireEvent.click(screen.getByRole('button', { name: /백테스트/ }))
  expect(screen.getByRole('heading', { name: '전략 재생' })).toBeInTheDocument()
  expect(screen.getByText('사용 안내')).toBeInTheDocument()
  expect(await screen.findByText('조회 가능한 종목')).toBeInTheDocument()
  expect(await screen.findByText('전략 통계가 없습니다')).toBeInTheDocument()
})

// 초보자 모드에서는 백테스트 메뉴가 숨겨지고 마감 추천 화면은 열리는지 봐요.
test('opens closing recommendation workspace', async () => {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Phase9App /></QueryClientProvider>)
  expect(screen.queryByRole('button', { name: /백테스트/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /마감 추천/ }))
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  expect(screen.getByText('사용 안내')).toBeInTheDocument()
  expect(await screen.findByText('추천 후보가 없습니다')).toBeInTheDocument()
})
