/**
 * AI 장전 계획 화면 테스트예요. fetch를 가짜 함수로 바꿔 1단계 기준 카드, 기록·종목별 결과, 검사 실패 표시를 확인해요.
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AiPlanPage, pct } from './AiPlanPage'

const plan = { id: 7, targetDate: '2026-11-11', revision: 2, analyzedAt: '2026-11-10T22:50:00Z', submittedAt: '2026-11-10T23:10:00Z', onTime: true, scored: true, tradeDecision: 'TRADE', marketOutlook: 'BULLISH', modelName: 'ChatGPT', modelVersion: 'test', promptVersion: 'premarket-v1', pickCount: 1, avoidCount: 1, warnings: [] }
const summary = { scoredDays: 3, gateDays: 20, gateWinDaysRequired: 12, gateVerdict: 'PENDING', gateMeanExcess: 0.5, gateWinDays: 2, meanPickOpenClose: 1.2, meanPickNet: 0.8, meanUniverseOpenClose: 0.7, meanExcess: 0.5, winDays: 2, pickCount: 4, meanAvoidExcess: -0.4, avoidCount: 2, noTradeDays: 0, noTradeUniverseOpenClose: null, tradeUniverseOpenClose: 0.7, days: [] }
const detail = {
  plan, rawJson: '{"schemaVersion":"ai-premarket-v1"}', revisions: [plan],
  picks: [
    { id: 1, kind: 'PICK', stockCode: '005930', stockName: '삼성전자', priority: 1, reason: '테스트', outcome: { tradeDate: '2026-11-11', status: 'COMPLETE', gapPct: -0.99, openClosePct: 4, highPct: 5, lowPct: -1, netOpenClosePct: 3.59, universeOpenClosePct: 1.02, excessOpenClosePct: 2.98, universeCount: 150 } },
    { id: 2, kind: 'AVOID', stockCode: '000660', stockName: 'SK하이닉스', priority: null, reason: '과열', outcome: null },
  ],
}

// 주소별 가짜 응답이에요. 저장(POST /api/v1/ai-plans)은 검사 실패(422)로 돌려줘요.
beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === 'POST')
      return Promise.resolve({ ok: false, status: 422, json: async () => ({ saved: false, errors: ['targetDate는 YYYY-MM-DD 형식이어야 해요.'], warnings: [], plan: null }) })
    const body = url.endsWith('/summary') ? summary : url.endsWith('/ai-plans') ? [plan] : url.endsWith('/7') ? detail : {}
    return Promise.resolve({ ok: true, status: 200, json: async () => body })
  }) as unknown as typeof fetch
})

function renderPage() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AiPlanPage /></QueryClientProvider>)
}

it('formats percent values with a sign', () => {
  expect(pct(1.234)).toBe('+1.23%')
  expect(pct(-0.5)).toBe('-0.50%')
  expect(pct(null)).toBe('--')
})

it('shows the gate progress and the scored picks of the latest plan', async () => {
  renderPage()

  expect(await screen.findByText('기록 중')).toBeInTheDocument()
  expect(screen.getByText(/3 \/ 20일 · 시장보다 나은 날 2일 \(필요 12일\)/)).toBeInTheDocument()
  expect(await screen.findByText('삼성전자')).toBeInTheDocument()
  expect(screen.getByText('+2.98%')).toBeInTheDocument()
  expect(screen.getByText('매수 금지')).toBeInTheDocument()
  expect(screen.getByText(/채점 전/)).toBeInTheDocument()
  expect(screen.getByText('집계 대상')).toBeInTheDocument()
})

it('lists validation errors when saving fails', async () => {
  renderPage()
  fireEvent.change(screen.getByLabelText('AI 장전 분석 JSON'), { target: { value: '{"targetDate":"11/11"}' } })
  fireEvent.click(screen.getByText('검사 후 저장'))

  await waitFor(() => expect(screen.getByText('targetDate는 YYYY-MM-DD 형식이어야 해요.')).toBeInTheDocument())
})
