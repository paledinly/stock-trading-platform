/**
 * ClosingRecommendationPage(장마감 추천 화면) 테스트예요. fetch를 가짜 함수로 바꿔 서버 응답을 흉내 내요.
 * 평가 내역 복원, 모의 계좌 미산출 표시, 공식 평가 우선 선택, 일별 수집 완결성 표를 확인해요.
 * Vitest(테스트 실행 도구)가 npm test로 실행해요.
 */
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ClosingRecommendationPage } from './ClosingRecommendationApp'

// 저장된 평가 내역만 다시 보여주고, 후보 평가(/generate)는 새로 실행하지 않는지 확인해요.
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
    <ClosingRecommendationPage />
  </QueryClientProvider>)
  expect(await screen.findByText('생성 당시 후보 평가')).toBeInTheDocument()
  expect(screen.getByText(/카카오 \(035720\).*50.8/)).toBeInTheDocument()
  expect(mockFetch.mock.calls.every(([url]) => !String(url).includes('/generate'))).toBe(true)
})

// 모의 체결 원장이 없으면 계좌 성과를 숫자 대신 '미산출'로 안내하는지 확인해요.
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
    <ClosingRecommendationPage advanced />
  </QueryClientProvider>)
  expect(await screen.findByText(/미산출: 모의 체결/)).toBeInTheDocument()
  expect(screen.queryByText(/계좌 MDD/)).not.toBeInTheDocument()
})

// 더 최근의 과거 재생(REPLAY)보다 공식 장중 평가(FORWARD)를 기본 선택하는지 확인해요.
test('prefers the official forward run over a newer replay', async () => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => Promise.resolve({
    ok: true,
    json: async () => url.includes('/runs?') ? [
      { id: 28, recommendationDate: new Date().toISOString().slice(0, 10), generatedAt: '2026-09-23T06:36:00Z', executionMode: 'REPLAY', strategyVersion: 'v8' },
      { id: 27, recommendationDate: new Date().toISOString().slice(0, 10), generatedAt: '2026-09-23T06:00:00Z', executionMode: 'FORWARD', strategyVersion: 'v8' },
    ] : [],
  })) as typeof fetch

  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ClosingRecommendationPage />
  </QueryClientProvider>)

  await waitFor(() => expect(screen.getByLabelText('평가 이력')).toHaveValue('27'))
})

// 일별 수집 완결성 표가 판정·사유를 한국어로 보여주고 누락된 날도 표시하는지 확인해요.
test('shows the daily completeness table with Korean labels and missing days', async () => {
  globalThis.fetch = vi.fn().mockImplementation((url: string) => Promise.resolve({
    ok: true,
    json: async () => url.includes('/daily-health?') ? [
      { tradeDate: '2026-10-02', verdict: 'DEGRADED', reasons: ['SELECTED_ENTRY_MISSING'], recorded: true,
        runId: 7, runCompletedAt: '2026-10-02T06:15:08Z', selectedCount: 2, selectedEntered: 1,
        observationStatuses: { COMPLETED: 3 }, realtimeConnected: true, realtimeDroppedDelta: 0,
        checkedAt: '2026-10-02T06:45:00Z' },
      { tradeDate: '2026-10-01', verdict: 'MISSING', reasons: ['NO_HEALTH_RECORD'], recorded: false,
        runId: null, runCompletedAt: null, selectedCount: null, selectedEntered: null,
        observationStatuses: {}, realtimeConnected: null, realtimeDroppedDelta: null, checkedAt: null },
    ] : [],
  })) as typeof fetch

  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ClosingRecommendationPage />
  </QueryClientProvider>)

  expect(await screen.findByText('일별 수집 완결성')).toBeInTheDocument()
  const degraded = screen.getByText('2026-10-02').closest('tr')!
  expect(degraded).toHaveTextContent('불완전')
  expect(degraded).toHaveTextContent('1/2')
  expect(degraded).toHaveTextContent('추천 종목 가상 진입 누락')
  const missing = screen.getByText('2026-10-01').closest('tr')!
  expect(missing).toHaveTextContent('누락')
  expect(missing).toHaveTextContent('기록 없음(백엔드 중단 추정)')
  expect(missing).toHaveTextContent('미관측')
})