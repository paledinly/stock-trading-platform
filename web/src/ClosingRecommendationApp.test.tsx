import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ClosingRecommendationPage } from './ClosingRecommendationApp'

test('restores excluded candidate evaluation without generating recommendations', async () => {
  const mockFetch = vi.fn().mockImplementation((url: string) => Promise.resolve({
    ok: true,
    json: async () => url.includes('/evaluation?') ? {
      recommendationDate: new Date().toISOString().slice(0, 10),
      generatedAt: '2026-09-14T06:20:00Z', evaluationEnd: '2026-09-14T06:20:00Z',
      sourceDetections: 1, sourceBroadSnapshots: 0, storedCandidates: 0,
      watchCandidates: 0, excludedCandidates: 1, strategyVersion: 'closing-recommend-v4-conservative',
      exclusionReasons: { LOW_FINAL_SCORE: 1 }, decisionReasons: { LOW_FINAL_SCORE: 1 },
      criteria: { minimumFinalScore: 55 }, candidates: [],
      evaluations: [{ stockCode: '035720', stockName: '카카오', candidateSource: 'PRECISION',
        scannerType: 'VOLUME_BREAKOUT', observedAt: '2026-09-14T06:04:00Z', referencePrice: 34550,
        finalScore: 50.8, opportunityScore: 40.77, riskScore: 12.377, dataQuality: 'PRECISION_A',
        finalCandles: 4, coverageMinutes: 20, missingFeatures: [], disposition: 'EXCLUDED',
        decisionReason: 'LOW_FINAL_SCORE', recommendationReason: '{}', riskReason: '{}', featureSnapshot: null }],
    } : [],
  }))
  globalThis.fetch = mockFetch as typeof fetch
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ClosingRecommendationPage back={() => {}} />
  </QueryClientProvider>)
  expect(await screen.findByText('생성 당시 후보 평가')).toBeInTheDocument()
  expect(screen.getByText(/카카오 \(035720\).*50.8/)).toBeInTheDocument()
  expect(mockFetch.mock.calls.every(([url]) => !String(url).includes('/generate'))).toBe(true)
})

test('shows account performance as unavailable without a paper execution ledger', async () => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => Promise.resolve({
    ok: true,
    json: async () => url.includes('/account-performance') ? {
      status: 'NOT_READY', reason: 'PAPER_EXECUTION_LEDGER_MISSING',
      cumulativeReturnRate: null, maxDrawdownRate: null, profitFactor: null,
      sharpeRatio: null, sortinoRatio: null, benchmarkStatus: 'NO_ALIGNED_INDEX_DATA',
      excessReturnRate: null, returnObservations: 0,
    } : [],
  })) as typeof fetch
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ClosingRecommendationPage back={() => {}} advanced />
  </QueryClientProvider>)
  expect(await screen.findByText(/미산출: 모의 체결/)).toBeInTheDocument()
  expect(screen.queryByText(/계좌 MDD/)).not.toBeInTheDocument()
})
