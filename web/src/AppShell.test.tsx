/**
 * 앱 껍데기(AppShell) 테스트예요. fetch를 가짜 함수로 바꿔 주소별로 빈 백테스트 결과 등을 돌려줘요.
 * 해시 주소로 화면 복원, 없어진 화면 주소 처리, 왼쪽 메뉴로 화면 열기, 테마 전환을 확인해요.
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppShell } from './AppShell'
import { routeFromHash } from './routes'

// 매 테스트 전에 해시 주소·테마 저장값을 지우고, fetch를 주소별 가짜 응답으로 바꿔요.
beforeEach(() => {
  window.location.hash = ''
  localStorage.clear()
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

const app = () => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AppShell /></QueryClientProvider>

// 메뉴로 마감 추천을 열면 해시 주소가 남고, 다시 그려도(remount) 같은 화면이 열리는지 봐요.
test('restores the closing page from the hash after remount', () => {
  const first = render(app())
  fireEvent.click(screen.getByRole('link', { name: '마감 추천' }))
  expect(window.location.hash).toBe('#/closing')
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  first.unmount()
  render(app())
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '마감 추천' })).toHaveAttribute('aria-current', 'page')
})

// 주소가 없거나 없어진 '장중 추천' 같은 주소는 '오늘' 화면으로 보내는지 봐요.
test('falls back to the today page for an unknown route', () => {
  expect(routeFromHash('')).toBe('today')
  expect(routeFromHash('#/intraday')).toBe('today')
  expect(routeFromHash('#/backtest?x=1')).toBe('backtest')
  window.location.hash = '/intraday'
  render(app())
  expect(screen.getByRole('link', { name: '오늘' })).toHaveAttribute('aria-current', 'page')
  expect(screen.getByRole('heading', { name: '오늘', level: 1 })).toBeInTheDocument()
})

// 모드 전환 없이 모든 메뉴가 보이고, 백테스트 화면이 열리는지 봐요.
test('opens the backtesting page from the sidebar', async () => {
  render(app())
  for (const name of ['오늘', '대시보드', '마감 추천', '시장 스캐너', '실시간 레이더', '성과 분석', '백테스트', '투자기록'])
    expect(screen.getByRole('link', { name })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('link', { name: '백테스트' }))
  expect(screen.getByRole('heading', { name: '전략 재생' })).toBeInTheDocument()
  expect(await screen.findByText('조회 가능한 종목')).toBeInTheDocument()
  // 화면을 여는 것만으로는 재생하지 않고, 재생을 눌러야 결과를 조회해요.
  expect(screen.getByText('종목과 기간을 정한 뒤 재생을 누르세요.')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '재생' }))
  expect(await screen.findByText('전략 통계가 없습니다')).toBeInTheDocument()
})

// 마감 추천은 예전 고급 모드처럼 전체 내용을 보여 주는지 봐요.
test('opens the closing recommendation page with full controls', async () => {
  render(app())
  fireEvent.click(screen.getByRole('link', { name: '마감 추천' }))
  expect(screen.getByRole('heading', { name: '오늘의 마감 추천' })).toBeInTheDocument()
  expect(screen.getByText('후보 수')).toBeInTheDocument()
  expect(await screen.findByText('추천 후보가 없습니다')).toBeInTheDocument()
})

// 테마 버튼을 누르면 html의 data-theme이 바뀌고 저장되는지 봐요.
test('toggles and remembers the light theme', () => {
  render(app())
  const initial = document.documentElement.dataset.theme
  fireEvent.click(screen.getByRole('button', { name: /화면으로 바꾸기/ }))
  const next = document.documentElement.dataset.theme
  expect(next).not.toBe(initial)
  expect(localStorage.getItem('ui.theme')).toBe(next)
})
