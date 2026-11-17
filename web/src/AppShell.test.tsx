/**
 * 앱 껍데기(AppShell) 테스트예요. fetch를 가짜 함수로 바꿔 빈 응답을 돌려줘요.
 * 해시 주소로 화면 복원, 없어진 화면 주소 처리, 왼쪽 메뉴 목록, 테마 전환을 확인해요.
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
    json: async () => url.includes('closing-recommendations')
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
  expect(routeFromHash('#/backtest?x=1')).toBe('today')
  expect(routeFromHash('#/scanner')).toBe('today')
  expect(routeFromHash('#/closing?x=1')).toBe('closing')
  window.location.hash = '/intraday'
  render(app())
  expect(screen.getByRole('link', { name: '오늘' })).toHaveAttribute('aria-current', 'page')
  expect(screen.getByRole('heading', { name: '오늘', level: 1 })).toBeInTheDocument()
})

// 남은 메뉴만 보이고, 제거한 실시간 레이더·성과 분석·백테스트 메뉴는 없는지 봐요.
test('shows only the remaining menus in the sidebar', () => {
  render(app())
  for (const name of ['오늘', '대시보드', '마감 추천', 'AI 장전', '시장 스캐너', '투자기록'])
    expect(screen.getByRole('link', { name })).toBeInTheDocument()
  for (const name of ['실시간 레이더', '성과 분석', '백테스트'])
    expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
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
  fireEvent.click(screen.getByRole('button', { name: '☀ 밝게' }))
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(screen.getByRole('button', { name: '☀ 밝게' })).toHaveAttribute('aria-pressed', 'true')
  expect(localStorage.getItem('ui.theme')).toBe('light')
  fireEvent.click(screen.getByRole('button', { name: '☾ 어둡게' }))
  expect(document.documentElement.dataset.theme).toBe('dark')
  expect(localStorage.getItem('ui.theme')).toBe('dark')
})
