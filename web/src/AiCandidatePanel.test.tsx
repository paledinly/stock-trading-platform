/**
 * AI 후보 평가 탭 테스트예요. fetch를 가짜 함수로 바꿔 판정 카드, 종목별 등급, 카드 미리보기 안내, 검사 실패 표시를 확인해요.
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AiCandidatePanel } from './AiCandidatePanel'

const evaluation = { id: 3, targetDate: '2026-11-20', revision: 1, poolId: 'abc123def456', analyzedAt: '2026-11-19T23:20:00Z', submittedAt: '2026-11-19T23:30:00Z', onTime: true, counted: true, modelName: 'ChatGPT', modelVersion: 'test', promptVersion: 'candidate-v1', marketOutlook: 'NEUTRAL', gradeA: 1, gradeB: 0, gradeC: 0, gradeAvoid: 1, warnings: [] }
const gate = { verdict: 'PENDING', days: 1, wins: 1, meanExcess: 4.875 }
const summary = { gateDays: 40, gateWinDays: 22, selection: gate, profitVerdict: 'PENDING', selectionMeanNet: 4.6, filter: { verdict: 'PENDING', days: 1, wins: 1, meanExcess: -5.125 }, meanVsRandom: 2, meanVsRule: 3.5, meanVsTopConf: 0, meanPoolExAvoid: 0.1, recordedDays: 1,
  days: [{ date: '2026-11-20', n: 1, avoidCount: 1, pool: 0.125, aiA: 5, aiAvoid: -5, random: 0, rule: 1.5 }] }
const detail = {
  evaluation, rawJson: '{}', revisions: [evaluation],
  grades: [
    { poolRank: 1, stockCode: '000010', stockName: '가나전자', grade: 'A', catalyst: 2, flow: 1, trend: 2, overheat: 0, disclosureRisk: 0, riskFlags: null, confidence: 70, entryType: 'BREAKOUT_15M', stopPct: 3, targetPct: 6, timeExit: 'CLOSE', usedExternalInfo: false, status: 'COMPLETE', openClosePct: 5, netOpenClosePct: 4.6 },
    { poolRank: 2, stockCode: '000020', stockName: '다라바이오', grade: 'AVOID', catalyst: 0, flow: 0, trend: 0, overheat: 2, disclosureRisk: 2, riskFlags: 'DILUTION', confidence: 80, entryType: 'NONE', stopPct: null, targetPct: null, timeExit: null, usedExternalInfo: false, status: null, openClosePct: null, netOpenClosePct: null },
  ],
}
const card = { schemaVersion: 'ai-candidate-v1', prompt: '# 후보 평가 카드', pool: { targetDate: '2026-11-20', poolId: 'abc123def456', dataDate: '2026-11-19', stored: false, message: '미리보기예요.', stocks: [{ rank: 1, stockCode: '000010', stockName: '가나전자' }] } }

beforeEach(() => {
  globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === 'POST')
      return Promise.resolve({ ok: false, status: 422, json: async () => ({ saved: false, errors: ['평가하지 않은 명단 종목이 1개 있어요: 000020'], warnings: [], evaluation: null }) })
    const body = url.endsWith('/summary') ? summary : url.endsWith('/ai-candidates') ? [evaluation] : url.endsWith('/3') ? detail : url.endsWith('/card') ? card : {}
    return Promise.resolve({ ok: true, status: 200, json: async () => body })
  }) as unknown as typeof fetch
})

function renderPanel() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AiCandidatePanel /></QueryClientProvider>)
}

it('shows the preregistered gates and the grades of the latest evaluation', async () => {
  renderPanel()

  expect(await screen.findByText(/1 \/ 40일 · 이긴 날 1일 \(필요 22일\) · 평균 \+4.88%/)).toBeInTheDocument()
  expect(await screen.findByText('가나전자')).toBeInTheDocument()
  expect(screen.getByText('15분 고가 돌파')).toBeInTheDocument()
  expect(screen.getByText('회피')).toBeInTheDocument()
  expect(screen.getByText(/DILUTION/)).toBeInTheDocument()
  expect(screen.getByText('채점 전')).toBeInTheDocument()
  expect(screen.getByText('집계 대상')).toBeInTheDocument()
  expect(screen.getByText(/명단 \+0.13% · A \+5.00%/)).toBeInTheDocument()
})

it('shows the preview notice on the card and lists validation errors', async () => {
  renderPanel()
  fireEvent.click(screen.getByText('후보 카드·프롬프트 보기'))
  expect(await screen.findByText('미리보기예요.')).toBeInTheDocument()

  fireEvent.change(screen.getByLabelText('AI 후보 평가 JSON'), { target: { value: '{}' } })
  fireEvent.click(screen.getByText('검사 후 저장'))
  await waitFor(() => expect(screen.getByText('평가하지 않은 명단 종목이 1개 있어요: 000020')).toBeInTheDocument())
})
