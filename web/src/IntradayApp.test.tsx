import { render, screen, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { IntradayPage } from './IntradayApp'

function mount() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><IntradayPage back={() => {}} /></QueryClientProvider>)
}
beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => Promise.resolve({ ok: true, json: async () =>
    url.includes('/status') ? { enabled: false, costsConfigured: false, queued: 0, dropped: 0, candidates: [] }
      : url.includes('/analytics') ? {} : url.includes('/replay') ? { inputCount: 0, results: [], analytics: {} } : [] })) as typeof fetch
})
test('shows NO TRADE and missing evidence without fabricated win rates', async () => {
  mount()
  expect(await screen.findByRole('heading', { name: 'NO TRADE' })).toBeInTheDocument()
  expect(screen.getByText(/비용 미반영 · 추천 가능/)).toBeInTheDocument()
  expect(screen.getByText(/점수는 확률이 아닙니다/)).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '기준선 정책 · 미검증' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '선택일 재생' }))
  expect(await screen.findByText('수신 이력이 없어 백테스트할 수 없습니다.')).toBeInTheDocument()
})

test('submits a fixed-entry experiment without changing live policy', async () => {
  const original = globalThis.fetch
  globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.includes('/exit-comparison')
    ? Promise.resolve({ ok: true, json: async () => ({ commonCompletedCount: 0, comparisons: {} }) }) : original(url, init)) as typeof fetch
  mount()
  fireEvent.change(screen.getByLabelText('손절 거리 배수'), { target: { value: '1.5' } })
  fireEvent.click(screen.getByRole('button', { name: '선택일 청산 비교' }))
  expect(await screen.findByText(/공통 완료 표본: 0건/)).toBeInTheDocument()
  const call = vi.mocked(fetch).mock.calls.find(([url]) => String(url).includes('/exit-comparison'))!
  expect(call[1]?.method).toBe('POST')
  expect(JSON.parse(call[1]?.body as string)).toEqual({ variants: [{ name: 'EXPERIMENT', stopDistanceMultiplier: 1.5,
    targetDistanceMultiplier: 1, protectAtPercent: null, protectFloorPercent: null }] })
})

test('submits opt-in entry and partial-exit research settings', async () => {
  const original = globalThis.fetch
  globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => url.includes('/exit-comparison')
    ? Promise.resolve({ ok: true, json: async () => ({ mode: 'FIXED_CANDIDATE_ENTRY_EXIT_COMPARISON', commonCompletedCount: 0, comparisons: {} }) }) : original(url, init)) as typeof fetch
  mount()
  fireEvent.click(screen.getByLabelText('추가 실험 사용'))
  fireEvent.change(screen.getByLabelText('돌파 진입 조건'), { target: { value: 'HOLD' } })
  fireEvent.change(screen.getByLabelText('유지 시간 (초)'), { target: { value: '20' } })
  fireEvent.change(screen.getByLabelText('부분 익절 시작 %'), { target: { value: '0.5' } })
  fireEvent.click(screen.getByRole('button', { name: '선택일 청산 비교' }))
  expect(await screen.findByText('후보 고정·진입 변경 실험')).toBeInTheDocument()
  const call = vi.mocked(fetch).mock.calls.find(([url]) => String(url).includes('/exit-comparison'))!
  expect(JSON.parse(call[1]?.body as string).variants[0].options).toMatchObject({ entryMode: 'HOLD', holdSeconds: 20, partialAtPercent: 0.5, partialFraction: 0.5, krxTickSizes: true, minimumStopTicks: 0 })
})
test('shows recommendation reasons, risk and stop-first result', async () => {
  const original = globalThis.fetch
  globalThis.fetch = vi.fn().mockImplementation((url: string) => url.includes('/recommendations') ? Promise.resolve({ ok: true, json: async () => [{
    signal: { id: 'one', stockName: '삼성전자', stockCode: '005930', setup: 'BREAKOUT', price: 10000, score: 75,
      recommendedAt: '2026-09-29T01:00:00Z', sourceAt: '2026-09-29T01:00:00Z', receivedAt: '2026-09-29T01:00:00Z', expiresAt: '2026-09-29T01:05:00Z',
      plan: { entryFrom: 9990, entryTo: 10010, chaseLimit: 10020, stop: 9900, target1: 10200, target2: 10300, riskReward: 1.7, invalidCondition: '지지선 이탈' },
      features: { values: { vwap: 9990, volumeRatio5m: 2.4 }, vwapState: 'ABOVE', marketRegime: 'UNAVAILABLE', unavailable: [] },
      reasons: ['거래량 동반 고점 돌파'], risks: ['VI_STATE_COUNT', 'UNVALIDATED_RESEARCH_SCORE', 'COSTS_NOT_CONFIGURED'] },
    outcome: { status: 'STOPPED', targetBeforeStop: false, mfe: 1, mae: -2, quality: [], failureFactors: ['FAKE_BREAKOUT'], returns: {} },
  }] }) : original(url)) as typeof fetch
  mount()
  expect(await screen.findByText('손절 먼저 도달')).toBeInTheDocument()
  fireEvent.click(screen.getByText(/삼성전자 · 추천 근거/))
  expect(screen.getByText('거래량 동반 고점 돌파')).toBeInTheDocument()
  expect(screen.getByText('VI 상태·횟수 미확보')).toBeInTheDocument()
  expect(screen.getByText(/목표\/손절 순서: 손절 먼저/)).toBeInTheDocument()
  expect(screen.getByText('가격 기준 · 비용 포함 R:R 미산정')).toBeInTheDocument()
  expect(screen.getByText(/Net 미산정 \(비용 미설정\)/)).toBeInTheDocument()
})

test('labels a policy version with a recorded failed validation', async () => {
  const original = globalThis.fetch
  globalThis.fetch = vi.fn().mockImplementation((url: string) => url.includes('/status') ? Promise.resolve({ ok: true, json: async () => ({
    enabled: true, costsConfigured: true, costsForNetOnly: true, version: 'intraday-research-v2-optional-costs', queued: 0, dropped: 0, candidates: [] }) })
    : original(url)) as typeof fetch
  mount()
  expect(await screen.findByRole('heading', { name: '기준선 정책 · 검증 실패' })).toBeInTheDocument()
  expect(screen.getByText(/평균 Net -0.68%/)).toBeInTheDocument()
  expect(screen.getByText(/매매 신호가 아닙니다/)).toBeInTheDocument()
})
