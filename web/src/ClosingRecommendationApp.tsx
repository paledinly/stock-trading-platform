import { FormEvent, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import './closingRecommendation.css'

type Recommendation = {
  runId?: number
  executionMode?: string
  id: number
  recommendationDate: string
  generatedAt: string
  rank: number
  stockCode: string
  stockName: string
  market: string
  scannerType: string | null
  candidateSource: 'PRECISION' | 'BROAD'
  dataQuality: string
  coverageMinutes: number
  broadSnapshotId: number | null
  missingFeatures: string
  detectedAt: string
  buyReferencePrice: number
  recommendationScore: number
  opportunityScore: number | null
  riskScore: number | null
  dailyTradingValue: number | null
  fiveMinuteChangeRate: number | null
  volumeRatio: number | null
  recommendationReason: string
  riskReason: string
  strategyVersion: string
  status: string
}

type GenerateResponse = {
  runId: number
  executionMode: string
  completedAt: string
  recommendationDate: string
  generatedAt: string
  sourceDetections: number
  sourceBroadSnapshots: number
  storedCandidates: number
  watchCandidates: number
  excludedCandidates: number
  exclusionReasons: Record<string, number>
  strategyVersion: string
  candidates: Recommendation[]
  evaluationEnd: string
  criteria: Record<string, string | number>
  decisionReasons: Record<string, number>
  evaluations: CandidateEvaluation[]
}

type CandidateEvaluation = {
  stockCode: string; stockName: string; candidateSource: string; scannerType: string | null
  observedAt: string; referencePrice: number | null; finalScore: number | null
  opportunityScore: number | null; riskScore: number | null; dataQuality: string
  finalCandles: number; coverageMinutes: number; missingFeatures: string[]
  disposition: 'SELECTED' | 'WATCH' | 'EXCLUDED'; decisionReason: string
  recommendationReason: string; riskReason: string; featureSnapshot: string | null
  dataReadiness?: { receiptVerified?: boolean; receivedAt?: string; dailyCandles?: number
    dailyAsOfDate?: string; lastFinalCandleAt?: string; evaluatedAsOf?: string
    dailyTrend?: string; latestFiveMinute?: string; signalRetained?: string
    overextension?: string; liquidity?: string; marketRegime?: string
    sectorExposure?: string; accountExposure?: string; orderEligible?: boolean }
}

type OvernightPerformance = {
  expectedSessionDate?: string | null
  sessionOpen?: string | null
  sessionClose?: string | null
  observedThrough?: string | null
  missingIntervals?: string
  latestPrice?: number | null
  latestReturnRate?: number | null
  targetRate?: number | null
  stopRate?: number | null
  id: number
  recommendationId: number
  recommendationDate: string
  stockCode: string
  stockName: string
  rank: number
  buyReferencePrice: number
  nextTradingDate: string | null
  evaluatedAt: string
  openPrice: number | null
  highPrice: number | null
  lowPrice: number | null
  closePrice: number | null
  openReturnRate: number | null
  closeReturnRate: number | null
  maxReturnRate: number | null
  maxDrawdownRate: number | null
  targetHit: boolean
  stopHit: boolean
  status: string
  calculationVersion: string
}

type TrackPerformanceResponse = {
  recommendationDate: string
  evaluatedAt: string
  recommendations: number
  completed: number
  dataMissing: number
  targetRate: number
  stopRate: number
  calculationVersion: string
  performances: OvernightPerformance[]
}

type OvernightDecision = {
  id: number
  recommendationId: number
  recommendationDate: string
  stockCode: string
  stockName: string
  rank: number
  buyReferencePrice: number
  evaluatedAt: string
  currentPrice: number | null
  returnRate: number | null
  vwap: number | null
  vwapDistanceRate: number | null
  tradeStrength: number | null
  ma5: number | null
  ma20: number | null
  ma60: number | null
  targetHit: boolean
  stopHit: boolean
  decision: string
  reasonJson: string
  calculationVersion: string
}

type DecisionEvaluationResponse = {
  recommendationDate: string
  evaluatedAt: string
  evaluated: number
  extendHold: number
  takeProfit: number
  sellWarning: number
  stopLoss: number
  calculationVersion: string
  decisions: OvernightDecision[]
}

type OvernightBacktestRow = {
  recommendationDate: string
  rank: number
  stockCode: string
  stockName: string
  market: string
  scannerType: string
  detectedAt: string
  signalPrice: number
  entryAt: string | null
  buyReferencePrice: number | null
  recommendationScore: number
  opportunityScore: number | null
  riskScore: number | null
  nextTradingDate: string | null
  openReturnRate: number | null
  closeReturnRate: number | null
  maxReturnRate: number | null
  maxDrawdownRate: number | null
  targetHit: boolean
  stopHit: boolean
  status: string
}

type OvernightBacktest = {
  from: string
  to: string
  tradingDays: number
  virtualRecommendations: number
  completed: number
  dataMissing: number
  winRateOpen: number | null
  winRateClose: number | null
  averageOpenReturn: number | null
  averageCloseReturn: number | null
  averageMaxReturn: number | null
  averageMaxDrawdown: number | null
  targetRate: number
  stopRate: number
  calculationVersion: string
  integrity: BacktestIntegrity
  strategySummaries: OvernightExitStrategySummary[]
  algorithmSummaries: RecommendationAlgorithmSummary[]
  rows: OvernightBacktestRow[]
}

type AccountPerformance = {
  status: 'NOT_READY' | 'INCOMPLETE' | 'READY'
  reason: string | null
  cumulativeReturnRate: number | null
  maxDrawdownRate: number | null
  profitFactor: number | null
  sharpeRatio: number | null
  sortinoRatio: number | null
  benchmarkStatus: string
  excessReturnRate: number | null
  returnObservations: number
}

type RecommendationAlgorithmSummary = {
  algorithm: string
  label: string
  sampleSize: number
  completed: number
  dataMissing: number
  confidence: 'LOW' | 'MEDIUM' | 'HIGH'
  winRateOpen: number | null
  winRateClose: number | null
  averageOpenReturn: number | null
  averageCloseReturn: number | null
  averageMaxReturn: number | null
  averageMaxDrawdown: number | null
  targetHitRate: number | null
  stopHitRate: number | null
  profitFactor: number | null
  uniqueStocks: number
  maxDateConcentrationRate: number | null
  recommendedDefault: boolean
}

type OvernightExitStrategySummary = {
  strategy: string
  label: string
  sampleSize: number
  winRate: number | null
  averageReturnRate: number | null
  averageNetReturnRate: number | null
  averageMaxDrawdownRate: number | null
  targetHitRate: number | null
  stopHitRate: number | null
  ambiguousCount: number
  costsApplied: boolean
  executionModelVersion: string
}

type BacktestIntegrity = {
  status: 'PASS' | 'WARNING' | 'FAIL'
  totalChecks: number
  passedChecks: number
  warningChecks: number
  failedChecks: number
  issues: BacktestIntegrityIssue[]
}

type BacktestIntegrityIssue = {
  severity: 'INFO' | 'WARNING' | 'ERROR'
  category: string
  recommendationDate: string | null
  stockCode: string | null
  stockName: string | null
  message: string
  detail: string
}

type StrategyAnalytics = {
  from: string
  to: string
  generatedAt: string
  sampleSize: number
  population: string
  targetRates: number[]
  warnings: string[]
  scoreBands: Array<{
    band: string; sampleSize: number; targetHits: number; targetHitRate: number | null
    confidenceLower95: number | null; confidenceUpper95: number | null
    averageCloseReturn: number | null; averageMaxReturn: number | null; averageMaxDrawdown: number | null
  }>
  segments: Array<{
    dimension: string; value: string; sampleSize: number
    targetHitRate: number | null; averageCloseReturn: number | null
  }>
  lossPatterns: Array<{ code: string; count: number; rate: number | null }>
  oosValidation: {
    status: 'READY' | 'INSUFFICIENT_SAMPLE'
    method: string
    splitDate: string | null
    development: PeriodMetrics
    validation: PeriodMetrics
    developmentScoreMedian: number | null
    scoreComparison: OosComparison
    features: Array<{ feature: string; developmentMedian: number; comparison: OosComparison }>
  }
  monitoring: {
    status: 'OBSERVE' | 'DEGRADED' | 'INSUFFICIENT_SAMPLE'
    baselineRecommendationDays: number; recentRecommendationDays: number
    baselinePeriod: PeriodMetrics; recentPeriod: PeriodMetrics
    baseline: MonitoringMetrics; recent: MonitoringMetrics
    closeReturnDelta: number | null; targetHitRateDelta: number | null
    failureTrends: Array<{ code: string; baselineRate: number | null; recentRate: number | null; rateDelta: number | null }>
  }
  contextCoverage: {
    marketSegmentAvailable: boolean; marketRegimeAvailable: boolean
    sectorHistoryAvailable: boolean; note: string
  }
  promotionGate: {
    currentStrategy: string; recommendation: string
    statisticalModelStatus: string; eventModelStatus: string
    productionActivationAllowed: boolean
    checks: Array<{ code: string; label: string; passed: boolean; requirement: string }>
  }
}

type PeriodMetrics = {
  from: string | null; to: string | null; sampleSize: number
  targetHitRate: number | null; averageCloseReturn: number | null
}

type OosGroup = { sampleSize: number; targetHitRate: number | null; averageCloseReturn: number | null }
type OosComparison = {
  developmentLow: OosGroup; developmentHigh: OosGroup
  validationLow: OosGroup; validationHigh: OosGroup
}
type MonitoringMetrics = {
  sampleSize: number; targetHitRate: number | null; stopHitRate: number | null
  averageCloseReturn: number | null; averageMaxDrawdown: number | null
}

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!response.ok) throw new Error(response.status === 409
    ? '기존 실행의 요청 또는 성과 기준과 다릅니다. 새 후보 평가를 생성해 주세요.'
    : '마감 추천 데이터를 처리하지 못했습니다.')
  return response.status === 204 ? null as T : response.json()
}

type EvaluationRun = {
  id: number; recommendationDate: string; generatedAt: string; completedAt: string | null
  evaluatedAsOf: string | null; executionMode: string; strategyVersion: string
}

type DailyPrepareResult = {
  recommendationDate: string; latestRequiredDate: string; candidateStocks: number
  attemptedStocks: number; savedCandles: number; readyStocks: number
  failedStocks: number; skippedStocks: number
}

function executionLabel(mode?: string) {
  return mode === 'FORWARD' ? '장중 평가' : mode === 'REPLAY' ? '과거 재생' : '기존 기록'
}

function hitLabel(target: boolean, stop: boolean) {
  return target && stop ? '목표·손절 모두 도달 (순서 미확인)' : target ? '목표 도달' : stop ? '손절 도달' : '미도달'
}

function today() {
  return marketDate(new Date())
}

function daysAgo(days: number) {
  return marketDate(new Date(Date.now() - days * 86400000))
}

function marketDate(date: Date) {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date)
  const part = (type: string) => parts.find(row => row.type === type)!.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

function money(value?: number | null) {
  return value == null ? '--' : Math.round(value).toLocaleString('ko-KR')
}

function pct(value?: number | null) {
  return value == null ? '--' : `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
}

function num(value?: number | null) {
  return value == null ? '--' : value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

function scannerTypeLabel(value: string | null) {
  if (!value) return 'Broad 후보'
  const labels: Record<string, string> = {
    VOLUME: '거래량 급증',
    PRICE_RISE: '5분 급등',
    MOMENTUM: '복합 모멘텀',
    VOLUME_BREAKOUT: '거래량 돌파',
    TURNOVER_BREAKOUT: '회전율 돌파',
    HIGH_BREAKOUT: '고가 돌파',
    VWAP_BREAKOUT: '평균가 돌파',
    VWAP_RECLAIM: '평균가 회복',
    PULLBACK_REBREAK: '눌림 후 재돌파',
  }
  return labels[value] ?? value
}

function integrityStatusLabel(value: BacktestIntegrity['status']) {
  const labels = {
    PASS: '통과',
    WARNING: '경고',
    FAIL: '실패',
  }
  return labels[value]
}

function integrityCategoryLabel(value: string) {
  const labels: Record<string, string> = {
    DATA_COVERAGE: '데이터 범위',
    LOOKAHEAD: '미래 데이터',
    EXECUTION: '체결 가정',
    SAMPLE_BIAS: '표본 편향',
  }
  return labels[value] ?? value
}

function decisionLabel(value: string) {
  const labels: Record<string, string> = {
    DATA_PENDING: '판단 대기',
    HOLD: '보유',
    EXTEND_HOLD: '보유 연장',
    TAKE_PROFIT: '익절 권고',
    SELL_WARNING: '매도 주의',
    STOP_LOSS: '손절',
  }
  return labels[value] ?? value
}

function confidenceLabel(value: RecommendationAlgorithmSummary['confidence']) {
  const labels = {
    LOW: '낮음',
    MEDIUM: '보통',
    HIGH: '높음',
  }
  return labels[value]
}

function lossPatternLabel(value: string) {
  const labels: Record<string, string> = {
    GAP_DOWN: '익일 시가 하락',
    CLOSE_LOSS: '익일 종가 손실',
    STOP_HIT: '손절선 도달',
    TARGET_AND_STOP: '목표·손절 동시 도달',
    DRAWDOWN_3_PERCENT: '장중 -3% 이하 낙폭',
  }
  return labels[value] ?? value
}

function factorLabels(json: string, type: 'recommendation' | 'risk') {
  const labels: Record<string, string> = {
    baseOpportunity: '기본 기회',
    closingRecency: '마감 근접',
    liquidity: '거래대금',
    vwapPosition: '평균가 위치',
    dayHighProximity: '고점 근접',
    volumeExpansion: '거래량 확장',
    intradayBullishAlignment: '5분봉 정배열 가산',
    intradayGoldenCross: '5분봉 골든크로스 가산',
    intradayMa20Support: '5분봉 MA20 지지 가산',
    dailyTrendAlignment: '일봉 정배열 가산',
    dailyMa20Rising: '일봉 20일선 상승 가산',
    dailyCloseAboveMa20: '일봉 20일선 위 가산',
    baseRisk: '기본 위험',
    vwapOverextension: '평균가 과열',
    lateNegativeMomentum: '장후반 약세',
    farFromDayHigh: '고점 이탈',
    weakTradeStrength: '체결 약화',
    intradayMa20Breakdown: '5분봉 MA20 이탈 감점',
    dailyTrendWeakness: '일봉 추세 약화 감점',
    dailyMaOverextension: '일봉 과열 감점',
  }
  try {
    const parsed = JSON.parse(json) as {
      factors?: Record<string, string>
      intradayMa?: {
        ready?: boolean
        candleCount?: number
        ma5?: string
        ma20?: string
        ma60?: string
        ma5DistanceRate?: string
        ma20DistanceRate?: string
        ma60DistanceRate?: string
        bullishAlignment?: boolean
        goldenCross?: boolean
        ma20Support?: boolean
        ma20Broken?: boolean
      }
      dailyMa?: {
        ready?: boolean
        candleCount?: number
        asOfDate?: string
        ma5?: string
        ma20?: string
        ma60?: string
        ma20DistanceRate?: string
        ma20SlopeRate?: string
        closeAboveMa20?: boolean
        ma5AboveMa20?: boolean
        ma20Rising?: boolean
        bullishAlignment?: boolean
        overextendedFromMa20?: boolean
      }
    }
    const factorRows = Object.entries(parsed.factors ?? {}).map(([key, value]) => ({
      label: labels[key] ?? key,
      value,
    }))
    return [...factorRows, ...intradayMaLabels(parsed.intradayMa, type), ...dailyMaLabels(parsed.dailyMa, type)]
  } catch {
    return type === 'recommendation'
      ? [{ label: '추천 근거', value: '확인 대기' }]
      : [{ label: '위험 근거', value: '확인 대기' }]
  }
}

function dailyMaLabels(ma: {
  ready?: boolean
  candleCount?: number
  asOfDate?: string
  ma5?: string
  ma20?: string
  ma60?: string
  ma20DistanceRate?: string
  ma20SlopeRate?: string
  closeAboveMa20?: boolean
  ma5AboveMa20?: boolean
  ma20Rising?: boolean
  bullishAlignment?: boolean
  overextendedFromMa20?: boolean
} | undefined, type: 'recommendation' | 'risk') {
  if (!ma) return []
  if (!ma.ready) return [{ label: '일봉 이평선', value: `데이터 부족 ${ma.candleCount ?? 0}개` }]
  if (type === 'recommendation') {
    return [
      { label: '일봉 20일선', value: ma.closeAboveMa20 ? '위' : '아래' },
      { label: '일봉 5일선', value: ma.ma5AboveMa20 ? '20일선 위' : '20일선 아래' },
      { label: '20일선 기울기', value: ma.ma20Rising ? '상승' : '하락/횡보' },
      { label: '일봉 정배열', value: ma.bullishAlignment ? '충족' : '미충족' },
    ]
  }
  return [
    { label: '일봉 과열', value: ma.overextendedFromMa20 ? '주의' : '아님' },
    { label: '일봉 MA20 이격', value: ma.ma20DistanceRate ? `${Number(ma.ma20DistanceRate).toFixed(2)}%` : '--' },
    { label: '일봉 MA20', value: ma.ma20 ? money(Number(ma.ma20)) : '--' },
    { label: '일봉 기준일', value: ma.asOfDate || '--' },
  ]
}

function intradayMaLabels(ma: {
  ready?: boolean
  candleCount?: number
  ma5?: string
  ma20?: string
  ma60?: string
  ma5DistanceRate?: string
  ma20DistanceRate?: string
  ma60DistanceRate?: string
  bullishAlignment?: boolean
  goldenCross?: boolean
  ma20Support?: boolean
  ma20Broken?: boolean
} | undefined, type: 'recommendation' | 'risk') {
  if (!ma) return []
  if (!ma.ready) return [{ label: '5분봉 이평선', value: `데이터 부족 ${ma.candleCount ?? 0}개` }]
  if (type === 'recommendation') {
    return [
      { label: '5분봉 정배열', value: ma.bullishAlignment ? '충족' : '미충족' },
      { label: '5분봉 골든크로스', value: ma.goldenCross ? '발생' : '없음' },
      { label: 'MA20 지지', value: ma.ma20Support ? '확인' : '미확인' },
      { label: 'MA20 이격', value: ma.ma20DistanceRate ? `${Number(ma.ma20DistanceRate).toFixed(2)}%` : '--' },
    ]
  }
  return [
    { label: 'MA20 이탈', value: ma.ma20Broken ? '주의' : '아님' },
    { label: 'MA5', value: ma.ma5 ? money(Number(ma.ma5)) : '--' },
    { label: 'MA20', value: ma.ma20 ? money(Number(ma.ma20)) : '--' },
    { label: 'MA60', value: ma.ma60 ? money(Number(ma.ma60)) : '--' },
  ]
}

export function ClosingRecommendationPage({ back, advanced = false }: { back: () => void; advanced?: boolean }) {
  const cache = useQueryClient()
  const [date, setDate] = useState(today())
  const [limit, setLimit] = useState(10)
  const [minOpportunity, setMinOpportunity] = useState(35)
  const [maxRisk, setMaxRisk] = useState(65)
  const [targetRate, setTargetRate] = useState(3)
  const [stopRate, setStopRate] = useState(-2)
  const [backtestFrom, setBacktestFrom] = useState(daysAgo(20))
  const [backtestTo, setBacktestTo] = useState(daysAgo(1))
  const [backtest, setBacktest] = useState<OvernightBacktest>()
  const [selection, setSelection] = useState<{ date: string; id: number }>()
  const request = useRef<{ fingerprint: string; key: string } | null>(null)
  const history = useQuery({
    queryKey: ['closing-runs', date],
    queryFn: () => api<EvaluationRun[]>(`/api/v1/closing-recommendations/runs?date=${date}`),
  })
  const defaultRun = history.data?.find(run => run.executionMode === 'FORWARD') ?? history.data?.[0]
  const runId = selection?.date === date ? selection.id : defaultRun?.id
  const selectedRun = history.data?.find(run => run.id === runId)
  const runParam = runId == null ? '' : `&runId=${runId}`
  const evaluation = useQuery({
    queryKey: ['closing-evaluation', date, runId],
    queryFn: () => api<GenerateResponse | null>(`/api/v1/closing-recommendations/evaluation?date=${date}${runParam}`),
    enabled: history.isSuccess,
  })
  const lastRun = evaluation.data ?? undefined
  const recommendations = useQuery({
    queryKey: ['closing-recommendations', date, runId],
    queryFn: () => api<Recommendation[]>(`/api/v1/closing-recommendations?date=${date}${runParam}`),
    enabled: history.isSuccess,
  })
  const performances = useQuery({
    queryKey: ['closing-recommendation-performance', date, runId],
    queryFn: () => api<OvernightPerformance[]>(`/api/v1/closing-recommendations/performance?date=${date}${runParam}`),
    enabled: history.isSuccess,
  })
  const decisions = useQuery({
    queryKey: ['closing-recommendation-decisions', date, runId],
    queryFn: () => api<OvernightDecision[]>(`/api/v1/closing-recommendations/decisions?date=${date}${runParam}`),
    enabled: history.isSuccess,
  })
  const accountPerformance = useQuery({
    queryKey: ['closing-account-performance'],
    queryFn: () => api<AccountPerformance>('/api/v1/closing-recommendations/account-performance'),
    enabled: advanced,
  })
  const strategyAnalytics = useQuery({
    queryKey: ['closing-strategy-analytics'],
    queryFn: () => api<StrategyAnalytics>('/api/v1/closing-recommendations/strategy-analytics'),
    enabled: advanced,
  })
  const generate = useMutation({
    mutationFn: (input: { date: string; limit: number; minOpportunity: number; maxRisk: number; key: string }) => api<GenerateResponse>(
      `/api/v1/closing-recommendations/generate?date=${input.date}&limit=${input.limit}&minOpportunity=${input.minOpportunity}&maxRisk=${input.maxRisk}`,
      { method: 'POST', headers: { 'Idempotency-Key': input.key } },
    ),
    onSuccess: result => {
      request.current = null
      if (result.executionMode === 'FORWARD' || !history.data?.some(run => run.executionMode === 'FORWARD'))
        setSelection({ date: result.recommendationDate, id: result.runId })
      cache.setQueryData(['closing-evaluation', result.recommendationDate, result.runId], result)
      cache.invalidateQueries({ queryKey: ['closing-runs', result.recommendationDate] })
      cache.invalidateQueries({ queryKey: ['closing-recommendations', result.recommendationDate] })
    },
  })
  const track = useMutation({
    mutationFn: () => api<TrackPerformanceResponse>(
      `/api/v1/closing-recommendations/performance/track?date=${date}&targetRate=${targetRate}&stopRate=${stopRate}${runParam}`,
      { method: 'POST' },
    ),
    onSuccess: () => {
      cache.invalidateQueries({ queryKey: ['closing-recommendation-performance'] })
    },
  })
  const evaluateDecisions = useMutation({
    mutationFn: () => api<DecisionEvaluationResponse>(
      `/api/v1/closing-recommendations/decisions/evaluate?date=${date}&targetRate=${targetRate}&stopRate=${stopRate}${runParam}`,
      { method: 'POST' },
    ),
    onSuccess: () => {
      cache.invalidateQueries({ queryKey: ['closing-recommendation-decisions'] })
    },
  })
  const runBacktest = useMutation({
    mutationFn: () => api<OvernightBacktest>(
      `/api/v1/closing-recommendations/backtest?from=${backtestFrom}&to=${backtestTo}&limit=${limit}&minOpportunity=${minOpportunity}&maxRisk=${maxRisk}&targetRate=${targetRate}&stopRate=${stopRate}`,
    ),
    onSuccess: setBacktest,
  })
  const prepareDaily = useMutation({
    mutationFn: () => api<DailyPrepareResult>('/api/v1/closing-recommendations/daily-data/prepare',
      { method: 'POST' }),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const input = { date, limit, minOpportunity, maxRisk }
    const fingerprint = JSON.stringify(input)
    if (request.current?.fingerprint !== fingerprint)
      request.current = { fingerprint, key: crypto.randomUUID() }
    generate.mutate({ ...input, key: request.current.key })
  }

  const rows = recommendations.data ?? lastRun?.candidates ?? []
  const performanceRows = performances.data ?? []
  const performanceByRecommendation = new Map(performanceRows.map(row => [row.recommendationId, row]))
  const top = rows[0]
  return (
    <div className="closingPage">
      <header>
        <button onClick={back}>← 대시보드</button>
        <div>
          <p>9단계 · 장마감 추천</p>
          <h1>오늘의 마감 추천</h1>
          <span>{advanced ? '오후 장중 탐지 흐름을 기준으로 종가 매수 후보를 랭킹으로 정리합니다.' : '오늘 장마감에 살펴볼 후보와 다음날 매도 판단을 간단히 정리합니다.'}</span>
        </div>
        <aside>
          <small>추천 후보</small>
          <b>{rows.length}</b>
          <span>{top ? `1위 ${top.stockName}` : '생성 대기'}</span>
        </aside>
      </header>
      <main>
        <form className={advanced ? 'advancedForm' : 'simpleForm'} onSubmit={submit}>
          <label>추천일<input type="date" value={date} onChange={event => {
            setDate(event.target.value); setSelection(undefined)
            generate.reset(); track.reset(); evaluateDecisions.reset()
          }} /></label>
          {advanced && <label>후보 수<input type="number" min="1" max="30" value={limit} onChange={event => setLimit(Number(event.target.value))} /></label>}
          {advanced && <label>최소 기회점수<input type="number" min="0" max="100" value={minOpportunity} onChange={event => setMinOpportunity(Number(event.target.value))} /></label>}
          {advanced && <label>최대 위험점수<input type="number" min="0" max="100" value={maxRisk} onChange={event => setMaxRisk(Number(event.target.value))} /></label>}
          <button disabled={generate.isPending}>{generate.isPending ? '평가 중...' : '후보 평가'}</button>
        </form>
        <section className="evaluationHistory">
          <label>평가 이력<select value={runId ?? ''} onChange={event => {
            setSelection({ date, id: Number(event.target.value) }); track.reset(); evaluateDecisions.reset()
          }} disabled={!history.data?.length}>
            {!history.data?.length && <option value="">평가 이력 없음</option>}
            {(history.data ?? []).map(run => <option key={run.id} value={run.id}>
              #{run.id} · {new Date(run.generatedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · {executionLabel(run.executionMode)}
            </option>)}
          </select></label>
          {selectedRun && <span>{executionLabel(selectedRun.executionMode)} · {selectedRun.strategyVersion}</span>}
        </section>
        <section className="performanceControls">
          <label>목표 수익률 %<input type="number" step="0.1" value={targetRate} onChange={event => setTargetRate(Number(event.target.value))} /></label>
          <label>손절 기준 %<input type="number" step="0.1" value={stopRate} onChange={event => setStopRate(Number(event.target.value))} /></label>
          <button onClick={() => track.mutate()} disabled={track.isPending || rows.length === 0}>{track.isPending ? '추적 중...' : '다음날 성과 추적'}</button>
          <button onClick={() => evaluateDecisions.mutate()} disabled={evaluateDecisions.isPending || rows.length === 0}>{evaluateDecisions.isPending ? '판단 중...' : '매도/보유 판단'}</button>
        </section>
        {advanced && <section className="backtestControls">
          <label>백테스트 시작일<input type="date" value={backtestFrom} onChange={event => setBacktestFrom(event.target.value)} /></label>
          <label>백테스트 종료일<input type="date" value={backtestTo} onChange={event => setBacktestTo(event.target.value)} /></label>
          <button onClick={() => runBacktest.mutate()} disabled={runBacktest.isPending}>{runBacktest.isPending ? '검증 중...' : '오버나잇 백테스트'}</button>
        </section>}
        {advanced && <section className="performanceControls">
          <button type="button" onClick={() => prepareDaily.mutate()} disabled={prepareDaily.isPending}>
            {prepareDaily.isPending ? '일봉 수집 중...' : '일봉 데이터 준비'}
          </button>
          {prepareDaily.data && <span>{prepareDaily.data.recommendationDate} 기준 · {prepareDaily.data.readyStocks}종목 준비 · {prepareDaily.data.savedCandles}봉 저장 · 실패 {prepareDaily.data.failedStocks}종목</span>}
          {prepareDaily.error && <span>일봉 수집 실패: {prepareDaily.error.message}</span>}
        </section>}
        <section className="menuGuide">
          <h2>사용 안내</h2>
          <p>성과는 신호 가격 대비 시장 관측값이며 실제 체결 손익이 아닙니다. 장중 관측과 최종 종가를 구분하며, 기존 계산 기록은 보존됩니다.</p>
        </section>
        {generate.error && <div className="closingEmpty">{generate.error.message}</div>}
        {track.error && <div className="closingEmpty">{track.error.message}</div>}
        {evaluateDecisions.error && <div className="closingEmpty">{evaluateDecisions.error.message}</div>}
        {runBacktest.error && <div className="closingEmpty">{runBacktest.error.message}</div>}
        {recommendations.error && <div className="closingEmpty">{recommendations.error.message}</div>}
        {evaluation.error && <div className="closingEmpty">후보 평가 내역 조회 실패: {evaluation.error.message}</div>}
        {history.error && <div className="closingEmpty">평가 이력 조회 실패: {history.error.message}</div>}
        {performances.error && <div className="closingEmpty">성과 조회 실패: {performances.error.message}</div>}
        {advanced && backtest && <BacktestResult data={backtest} />}
        {advanced && <section className="menuGuide">
          <h2>모의 계좌 성과</h2>
          {accountPerformance.isLoading && <p>계좌 자료 확인 중...</p>}
          {accountPerformance.error && <p>계좌 상태 조회 실패: {accountPerformance.error.message}</p>}
          {accountPerformance.data?.status !== 'READY' && !accountPerformance.isLoading && !accountPerformance.error &&
            <p>미산출: 모의 체결·비용 원장과 일별 순자산이 아직 없습니다. KOSPI/KOSDAQ 비교용 동일 보유시간 지수 데이터도 없어 수익률·MDD·샤프·시장 초과수익을 표시하지 않습니다.</p>}
          {accountPerformance.data?.status === 'READY' && <p>누적수익 {pct(accountPerformance.data.cumulativeReturnRate)} · 계좌 MDD {pct(accountPerformance.data.maxDrawdownRate)} · 손익비 {num(accountPerformance.data.profitFactor)} · 샤프 {num(accountPerformance.data.sharpeRatio)} · 소르티노 {num(accountPerformance.data.sortinoRatio)} · 시장 초과수익 {accountPerformance.data.excessReturnRate == null ? '미산출' : pct(accountPerformance.data.excessReturnRate)}</p>}
        </section>}
        {advanced && <StrategyAnalyticsPanel data={strategyAnalytics.data} loading={strategyAnalytics.isLoading}
          error={strategyAnalytics.error?.message} />}
        <section className="closingSummary">
          <article><small>원본 탐지</small><b>{lastRun?.sourceDetections ?? '--'}</b></article>
          <article><small>Broad 원본</small><b>{lastRun?.sourceBroadSnapshots ?? '--'}</b></article>
          <article><small>저장 후보</small><b>{lastRun?.storedCandidates ?? rows.length}</b></article>
          <article><small>관찰 후보</small><b>{lastRun?.watchCandidates ?? '--'}</b></article>
          <article><small>제외 후보</small><b>{lastRun?.excludedCandidates ?? '--'}</b></article>
          <article><small>최종 관측 완료</small><b>{performanceRows.filter(row => row.status === 'COMPLETED' && row.calculationVersion === 'overnight-observation-v2').length}</b></article>
          <article><small>보유 연장</small><b>{(decisions.data ?? []).filter(row => row.decision === 'EXTEND_HOLD').length}</b></article>
        </section>
        {lastRun?.criteria?.marketSectorAccountChecks === 'UNVERIFIED' &&
          <div className="closingEmpty"><b>제한 모드: 주문 자격 미확인</b><p>시장 지표·업종 집중·계좌 노출 데이터가 없어 추천 목록에는 최대 1종목만 표시합니다. 추천 순위는 매수 가능 판정이나 수익 확률이 아닙니다.</p></div>}
        {lastRun && lastRun.storedCandidates === 0 && lastRun.watchCandidates + lastRun.excludedCandidates > 0 &&
          <div className="closingEmpty"><b>조건을 충족한 최종 추천이 없습니다</b><p>{Object.entries(lastRun.exclusionReasons).map(([reason, count]) => `${closingExclusionLabel(reason)} ${count}건`).join(' · ')}</p></div>}
        <section className="closingGrid">
          <div className="closingList">
            <h2>추천 랭킹</h2>
            {recommendations.isLoading && <div className="closingEmpty">불러오는 중...</div>}
            {!recommendations.isLoading && rows.length === 0 && <div className="closingEmpty"><b>추천 후보가 없습니다</b><p>장중 탐지가 쌓인 뒤 추천 생성을 실행하세요.</p></div>}
            {rows.map(item => <RecommendationCard key={item.id} item={item} performance={performanceByRecommendation.get(item.id)} decision={(decisions.data ?? []).find(row => row.recommendationId === item.id)} advanced={advanced} />)}
          </div>
        </section>
        {lastRun?.evaluations && <section className="menuGuide">
          <h2>생성 당시 후보 평가</h2>
          <p>{new Date(lastRun.generatedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · {lastRun.strategyVersion} · 평가 종료 {new Date(lastRun.evaluationEnd).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })}</p>
          <p>{Object.entries(lastRun.criteria).map(([key, value]) => `${key}: ${value}`).join(' · ')}</p>
          <p>관찰·제외 후보는 최종 추천이 아니며 오버나잇 성과 추적 대상에 포함되지 않습니다. 관측 분수는 봉의 시간 범위이며 연속 수신 시간을 보장하지 않습니다.</p>
          <p>Broad 점수·위험값은 제한된 지표로 계산한 참고값이며 Precision 점수와 같은 품질의 평가가 아닙니다.</p>
          {selectedRun?.executionMode === 'REPLAY' && <p>과거 재생은 당시 수신 시각이 없는 탐지 기록을 포함할 수 있습니다. 수신 여부가 확인되지 않은 결과는 전진 검증 성과로 해석하지 마세요.</p>}
          {(['SELECTED', 'WATCH', 'EXCLUDED'] as const).map(disposition => <div key={disposition}>
            <h3>{{ SELECTED: '최종 추천', WATCH: '관찰 후보', EXCLUDED: '제외 후보' }[disposition]}</h3>
            {lastRun.evaluations.filter(row => row.disposition === disposition).map(row => <details key={`${row.candidateSource}-${row.stockCode}`}>
              <summary>{row.stockName} ({row.stockCode}) · {row.candidateSource} · 점수 {num(row.finalScore)} · {closingExclusionLabel(row.decisionReason)}</summary>
              <p>탐지 {new Date(row.observedAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })} · {row.scannerType ?? 'Broad'} · 가격 {num(row.referencePrice)} · Opportunity {num(row.opportunityScore)} / Risk {num(row.riskScore)}</p>
              <p>{row.dataQuality} · 확정 봉 {row.finalCandles}개 · 관측 범위 {row.coverageMinutes}분 · 부족 항목 {row.missingFeatures.join(', ') || '없음'}</p>
              {row.dataReadiness && <p>일봉 {row.dataReadiness.dailyCandles ?? 0}개 · 마지막 일봉 {row.dataReadiness.dailyAsOfDate ?? '확인 불가'} · 마지막 확정 5분봉 {row.dataReadiness.lastFinalCandleAt ? new Date(row.dataReadiness.lastFinalCandleAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' }) : '없음'} · 탐지 수신 {row.dataReadiness.receiptVerified ? '확인' : '확인 불가'}</p>}
              {row.dataReadiness?.marketRegime === 'UNVERIFIED' && <p>일봉 추세 {checkLabel(row.dataReadiness.dailyTrend)} · 최신 분봉 {checkLabel(row.dataReadiness.latestFiveMinute)} · 신호 유지 {checkLabel(row.dataReadiness.signalRetained)} · 과열 {checkLabel(row.dataReadiness.overextension)} · 유동성 {checkLabel(row.dataReadiness.liquidity)} · 시장/업종/계좌 미검증 · 주문 자격 미확인</p>}
              <h4>가점 근거</h4><ul>{factorLabels(row.recommendationReason, 'recommendation').map((factor, index) => <li key={index}>{factor.label}: {factor.value}</li>)}</ul>
              <h4>감점 근거</h4><ul>{factorLabels(row.riskReason, 'risk').map((factor, index) => <li key={index}>{factor.label}: {factor.value}</li>)}</ul>
              {row.featureSnapshot && <details><summary>탐지 당시 Feature 원본</summary><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{row.featureSnapshot}</pre></details>}
            </details>)}
          </div>)}
        </section>}
      </main>
    </div>
  )
}

function StrategyAnalyticsPanel({ data, loading, error }: {
  data?: StrategyAnalytics; loading: boolean; error?: string
}) {
  if (loading) return <section className="menuGuide"><h2>공식 전진 전략 검증</h2><p>성과 표본을 집계하고 있습니다.</p></section>
  if (error) return <section className="menuGuide"><h2>공식 전진 전략 검증</h2><p>전략 통계 조회 실패: {error}</p></section>
  if (!data) return null
  return (
    <section className="algorithmPanel">
      <div className="strategyTitle">
        <span><small>{data.from} ~ {data.to}</small><b>공식 전진 전략 검증</b></span>
        <small>완료된 공식 장중 관측 {data.sampleSize}건</small>
      </div>
      {(data.warnings ?? []).map(warning => <p key={warning}>{warning}</p>)}
      <p>목표수익률 {(data.targetRates ?? []).map(value => `${value}%`).join(', ') || '표본 없음'} · 수동 재생과 미완료 관측은 제외됩니다.</p>
      <h3>점수 구간 보정</h3>
      <div className="algorithmRows">
        {(data.scoreBands ?? []).map(item => <article key={item.band}>
          <span><b>{item.band}점</b><small>{item.sampleSize}건 · 목표 {item.targetHits}건</small></span>
          <span><small>목표 도달률</small><b>{pct(item.targetHitRate)}</b></span>
          <span><small>95% 구간</small><b>{num(item.confidenceLower95)}~{num(item.confidenceUpper95)}%</b></span>
          <span><small>종가 평균</small><b>{pct(item.averageCloseReturn)}</b></span>
          <span><small>최고 평균</small><b>{pct(item.averageMaxReturn)}</b></span>
          <span><small>최저 평균</small><b>{pct(item.averageMaxDrawdown)}</b></span>
        </article>)}
      </div>
      <h3>관측 그룹</h3>
      <div className="algorithmRows">
        {(data.segments ?? []).map(item => <article key={`${item.dimension}-${item.value}`}>
          <span><b>{item.value}</b><small>{item.dimension} · {item.sampleSize}건</small></span>
          <span><small>목표 도달률</small><b>{pct(item.targetHitRate)}</b></span>
          <span><small>종가 평균</small><b>{pct(item.averageCloseReturn)}</b></span>
        </article>)}
      </div>
      <OosValidationPanel value={data.oosValidation} />
      <PerformanceMonitoringPanel value={data.monitoring} context={data.contextCoverage} />
      <StrategyPromotionPanel value={data.promotionGate} />
      <h3>손실 관측 패턴</h3>
      <div className="algorithmRows">
        {(data.lossPatterns ?? []).map(item => <article key={item.code}>
          <span><b>{lossPatternLabel(item.code)}</b><small>패턴은 서로 중복될 수 있습니다.</small></span>
          <span><small>발생</small><b>{item.count}건</b></span>
          <span><small>비율</small><b>{pct(item.rate)}</b></span>
        </article>)}
      </div>
    </section>
  )
}

function StrategyPromotionPanel({ value }: { value: StrategyAnalytics['promotionGate'] }) {
  if (!value) return null
  return <>
    <h3>전략 승격 조건</h3>
    <p>현재 기준선: 규칙 기반 전략 · 통계 모델 {value.statisticalModelStatus === 'BLOCKED' ? '실험 보류' : '실험 가능'} · 이벤트 모델 {value.eventModelStatus === 'BLOCKED' ? '실험 보류' : '실험 가능'}</p>
    <div className="algorithmRows">
      {(value.checks ?? []).map(check => <article key={check.code}>
        <span><b>{check.label}</b><small>{check.requirement}</small></span>
        <span><small>준비 상태</small><b className={check.passed ? 'gain' : 'loss'}>{check.passed ? '충족' : '미충족'}</b></span>
      </article>)}
    </div>
    {!value.productionActivationAllowed && <p>모델 출력은 추천이나 실제 주문에 연결되지 않습니다.</p>}
  </>
}

function PerformanceMonitoringPanel({ value, context }: {
  value: StrategyAnalytics['monitoring']; context: StrategyAnalytics['contextCoverage']
}) {
  if (!value) return null
  const status = value.status === 'DEGRADED' ? '최근 성과 악화 관찰'
    : value.status === 'OBSERVE' ? '특이 악화 없음' : '비교 표본 부족'
  return <>
    <h3>최근 전략 상태</h3>
    <p>{status} · 최근 {value.recentRecommendationDays}개 추천일과 이전 최대 {value.baselineRecommendationDays}개 추천일 비교</p>
    {context && <p>{context.note}</p>}
    <div className="algorithmRows">
      <article>
        <span><b>이전 구간</b><small>{value.baselinePeriod.from ?? '--'} ~ {value.baselinePeriod.to ?? '--'}</small></span>
        <span><small>표본</small><b>{value.baseline.sampleSize}건</b></span>
        <span><small>목표 도달</small><b>{pct(value.baseline.targetHitRate)}</b></span>
        <span><small>종가 평균</small><b>{pct(value.baseline.averageCloseReturn)}</b></span>
        <span><small>평균 낙폭</small><b>{pct(value.baseline.averageMaxDrawdown)}</b></span>
      </article>
      <article>
        <span><b>최근 구간</b><small>{value.recentPeriod.from ?? '--'} ~ {value.recentPeriod.to ?? '--'}</small></span>
        <span><small>표본</small><b>{value.recent.sampleSize}건</b></span>
        <span><small>목표 도달</small><b>{pct(value.recent.targetHitRate)}</b></span>
        <span><small>종가 평균</small><b>{pct(value.recent.averageCloseReturn)}</b></span>
        <span><small>평균 낙폭</small><b>{pct(value.recent.averageMaxDrawdown)}</b></span>
      </article>
      {(value.failureTrends ?? []).map(item => <article key={item.code}>
        <span><b>{lossPatternLabel(item.code)}</b><small>최근 실패 패턴 변화</small></span>
        <span><small>이전</small><b>{pct(item.baselineRate)}</b></span>
        <span><small>최근</small><b>{pct(item.recentRate)}</b></span>
        <span><small>증감</small><b>{pct(item.rateDelta)}</b></span>
      </article>)}
    </div>
  </>
}

function OosValidationPanel({ value }: { value: StrategyAnalytics['oosValidation'] }) {
  if (!value) return null
  const featureLabels: Record<string, string> = {
    vwapDistanceRate: 'VWAP 이격률', dayHighDistanceRate: '당일 고가 거리',
    tradeStrength: '체결강도', turnoverRatio: '회전율',
  }
  return <>
    <h3>시간순 표본 외 검증</h3>
    <p>{value.status === 'READY' ? '검증 표본 기준 충족' : '표본 부족'} · 분할일 {value.splitDate ?? '--'} · 같은 추천일은 한 구간에만 포함됩니다.</p>
    <div className="algorithmRows">
      <article>
        <span><b>개발 구간</b><small>{value.development.from ?? '--'} ~ {value.development.to ?? '--'}</small></span>
        <span><small>표본</small><b>{value.development.sampleSize}건</b></span>
        <span><small>목표 도달률</small><b>{pct(value.development.targetHitRate)}</b></span>
        <span><small>종가 평균</small><b>{pct(value.development.averageCloseReturn)}</b></span>
      </article>
      <article>
        <span><b>검증 구간</b><small>{value.validation.from ?? '--'} ~ {value.validation.to ?? '--'}</small></span>
        <span><small>표본</small><b>{value.validation.sampleSize}건</b></span>
        <span><small>목표 도달률</small><b>{pct(value.validation.targetHitRate)}</b></span>
        <span><small>종가 평균</small><b>{pct(value.validation.averageCloseReturn)}</b></span>
      </article>
      <OosComparisonRow label={`추천점수 상위 (개발 중앙값 ${num(value.developmentScoreMedian)})`} comparison={value.scoreComparison} />
      {(value.features ?? []).map(item => <OosComparisonRow key={item.feature}
        label={`${featureLabels[item.feature] ?? item.feature} 상위 (개발 중앙값 ${num(item.developmentMedian)})`}
        comparison={item.comparison} />)}
    </div>
  </>
}

function OosComparisonRow({ label, comparison }: { label: string; comparison: OosComparison }) {
  return <article>
    <span><b>{label}</b><small>개발 구간에서 정한 기준을 검증 구간에 고정 적용</small></span>
    <span><small>개발 상위</small><b>{comparison.developmentHigh.sampleSize}건 · {pct(comparison.developmentHigh.averageCloseReturn)}</b></span>
    <span><small>검증 상위</small><b>{comparison.validationHigh.sampleSize}건 · {pct(comparison.validationHigh.averageCloseReturn)}</b></span>
    <span><small>검증 하위</small><b>{comparison.validationLow.sampleSize}건 · {pct(comparison.validationLow.averageCloseReturn)}</b></span>
    <span><small>검증 목표 도달</small><b>{pct(comparison.validationHigh.targetHitRate)}</b></span>
  </article>
}

function closingExclusionLabel(reason: string) {
  const labels: Record<string, string> = {
    MISSING_REQUIRED_FEATURES: '필수 지표 또는 확정 5분봉 부족',
    DAILY_DATA_MISSING: '이동평균선 계산에 필요한 일봉 부족',
    DAILY_DATA_STALE: '최근 일봉이 오래됨',
    STALE_FEATURE: '최근 탐지 신호가 없음',
    CANDLE_GAP: '연속 확정 5분봉 부족',
    RECEIVED_AFTER_EVALUATION: '평가 시각 이후 수신 또는 정정된 봉',
    CALENDAR_UNVERIFIED: '거래일 확인 불가',
    INSUFFICIENT_INTRADAY_COVERAGE: '당일 관찰시간 부족',
    LOW_FINAL_SCORE: '최종점수 미달',
    BROAD_WATCH_ONLY: 'Broad 관찰 전용',
    RANK_LIMIT_WATCH: '표시 순위 밖 관찰 후보',
    LIMITED_MODE_WATCH: '제한 모드의 종목 수 상한',
    DAILY_TREND_WEAK: '전일 일봉 추세 약화',
    LATEST_CANDLE_STALE: '판단 시각의 확정 5분봉 지연',
    INTRADAY_REVERSAL: '탐지 후 가격 흐름 약화',
    OVEREXTENDED: '20일 이동평균선 대비 과열',
    LOW_LIQUIDITY: '최소 거래대금 미달',
    QUALIFIED: '추천 조건 충족',
    NOT_TRADABLE: '추천 대상 상품/거래 조건 제외',
    OPPORTUNITY_OR_RISK_FILTERED: 'Opportunity 또는 Risk 기준 미달',
    INSUFFICIENT_BROAD_DATA: 'Broad 데이터 품질 미달',
    PRECISION_DUPLICATE: '동일 종목 Precision 평가 우선',
  }
  return labels[reason] ?? reason
}

function checkLabel(status?: string) {
  return status === 'PASS' ? '통과' : status === 'FAIL' ? '미통과' : '미검증'
}

function BacktestResult({ data }: { data: OvernightBacktest }) {
  return (
    <section className="overnightBacktest">
      <div className="backtestTitle">
        <span>
          <small>{data.from} ~ {data.to}</small>
          <h2>오버나잇 백테스트</h2>
        </span>
        <b>{data.virtualRecommendations}건</b>
      </div>
      <div className="backtestMetrics">
        <article><small>거래일</small><b>{data.tradingDays}</b></article>
        <article><small>완료</small><b>{data.completed}</b></article>
        <article><small>시가 승률</small><b>{pct(data.winRateOpen)}</b></article>
        <article><small>종가 승률</small><b>{pct(data.winRateClose)}</b></article>
        <article><small>시가 평균</small><b className={(data.averageOpenReturn ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(data.averageOpenReturn)}</b></article>
        <article><small>최고 평균</small><b className={(data.averageMaxReturn ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(data.averageMaxReturn)}</b></article>
      </div>
      <IntegrityPanel integrity={data.integrity} />
      <p>기존 탐지 기록 중 수신 시각이 없는 데이터는 당시 이용 가능 여부를 검증할 수 없습니다. 이 결과는 탐색적 재생이며 전진 검증 성과가 아닙니다.</p>
      <AlgorithmSummaryPanel summaries={data.algorithmSummaries ?? []} />
      <StrategySummaryPanel summaries={data.strategySummaries ?? []} />
      <div className="backtestRows">
        {data.rows.length === 0 && <div className="closingEmpty">백테스트 결과가 없습니다</div>}
        {data.rows.slice(0, 20).map(row => (
          <article key={`${row.recommendationDate}-${row.rank}-${row.stockCode}`}>
            <span><b>{row.recommendationDate} #{row.rank} {row.stockName}</b><small>{row.stockCode} · {scannerTypeLabel(row.scannerType)} · 신호 {money(row.signalPrice)}원 · {row.entryAt ? `진입 ${money(row.buyReferencePrice)}원` : '진입 데이터 없음'}</small></span>
            <span className={(row.openReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>시가 {pct(row.openReturnRate)}</span>
            <span className={(row.maxReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>최고 {pct(row.maxReturnRate)}</span>
            <span className={(row.maxDrawdownRate ?? 0) >= 0 ? 'gain' : 'loss'}>최저 {pct(row.maxDrawdownRate)}</span>
            <span>{hitLabel(row.targetHit, row.stopHit)}</span>
          </article>
        ))}
      </div>
    </section>
  )
}

function AlgorithmSummaryPanel({ summaries }: { summaries: RecommendationAlgorithmSummary[] }) {
  if (summaries.length === 0) return null
  return (
    <section className="algorithmPanel">
      <div className="strategyTitle">
        <span>
          <small>추천 알고리즘 비교</small>
          <b>튜닝 리포트</b>
        </span>
        <small>같은 기간과 같은 후보 제한으로 비교</small>
      </div>
      <div className="algorithmRows">
        {summaries.map(item => (
          <article key={item.algorithm} className={item.recommendedDefault ? 'recommended' : undefined}>
            <span><b>{item.label}</b><small>{item.recommendedDefault ? '운영 기본값 후보' : `${item.sampleSize}건 비교`}</small></span>
            <span><small>신뢰도</small><b>{confidenceLabel(item.confidence)}</b></span>
            <span><small>완료/누락</small><b>{item.completed}/{item.dataMissing}</b></span>
            <span><small>종가 승률</small><b>{pct(item.winRateClose)}</b></span>
            <span><small>종가 평균</small><b className={(item.averageCloseReturn ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageCloseReturn)}</b></span>
            <span><small>최고 평균</small><b className={(item.averageMaxReturn ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageMaxReturn)}</b></span>
            <span><small>최대 낙폭</small><b className={(item.averageMaxDrawdown ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageMaxDrawdown)}</b></span>
            <span><small>손익비</small><b>{num(item.profitFactor)}</b></span>
            <span><small>종목 수</small><b>{item.uniqueStocks}</b></span>
            <span><small>날짜 편중</small><b>{pct(item.maxDateConcentrationRate)}</b></span>
          </article>
        ))}
      </div>
    </section>
  )
}

function StrategySummaryPanel({ summaries }: { summaries: OvernightExitStrategySummary[] }) {
  if (summaries.length === 0) return null
  return (
    <section className="strategyPanel">
      <div className="strategyTitle">
        <span>
          <small>매도 전략 비교</small>
          <b>보유 연장 백테스트</b>
        </span>
        <small>목표/손절 동시 도달은 보수적으로 손절 처리 · 비용 미설정 시 순수익은 총수익과 동일</small>
      </div>
      <div className="strategyRows">
        {summaries.map(item => (
          <article key={item.strategy}>
            <span><b>{item.label}</b><small>{item.sampleSize}건 검증</small></span>
            <span><small>승률</small><b>{pct(item.winRate)}</b></span>
            <span><small>평균 총수익</small><b className={(item.averageReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageReturnRate)}</b></span>
            <span><small>평균 순수익</small><b className={(item.averageNetReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageNetReturnRate)}</b></span>
            <span><small>평균 낙폭</small><b className={(item.averageMaxDrawdownRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageMaxDrawdownRate)}</b></span>
            <span><small>목표 도달</small><b>{pct(item.targetHitRate)}</b></span>
            <span><small>손절 도달</small><b>{pct(item.stopHitRate)}</b></span>
            <span><small>동시 도달</small><b>{item.ambiguousCount}</b></span>
            <span><small>거래비용</small><b>{item.costsApplied ? '적용' : '미설정'}</b></span>
          </article>
        ))}
      </div>
    </section>
  )
}

function IntegrityPanel({ integrity }: { integrity: BacktestIntegrity }) {
  return (
    <section className={`integrityPanel ${integrity.status.toLowerCase()}`}>
      <div>
        <span>
          <small>백테스트 정합성</small>
          <b>{integrityStatusLabel(integrity.status)}</b>
        </span>
        <span><small>전체 검사</small><b>{integrity.totalChecks}</b></span>
        <span><small>통과</small><b>{integrity.passedChecks}</b></span>
        <span><small>경고</small><b>{integrity.warningChecks}</b></span>
        <span><small>실패</small><b>{integrity.failedChecks}</b></span>
      </div>
      {integrity.issues.length === 0
        ? <p>백테스트 데이터 범위와 체결 가정에서 확인된 문제가 없습니다.</p>
        : <ul>
          {integrity.issues.slice(0, 8).map((issue, index) => (
            <li key={`${issue.category}-${issue.stockCode ?? 'all'}-${index}`}>
              <strong>{integrityCategoryLabel(issue.category)} · {issue.message}</strong>
              <small>{issue.stockName ? `${issue.recommendationDate} ${issue.stockName} ${issue.stockCode}` : '전체 결과'} · {issue.detail}</small>
            </li>
          ))}
        </ul>}
    </section>
  )
}

function RecommendationCard({ item, performance, decision, advanced = false }: {
  item: Recommendation
  performance?: OvernightPerformance
  decision?: OvernightDecision
  advanced?: boolean
}) {
  const recommendationFactors = factorLabels(item.recommendationReason, 'recommendation')
  const riskFactors = factorLabels(item.riskReason, 'risk')
  const missingFeatures = parseMissingFeatures(item.missingFeatures)
  return (
    <article className="recommendationCard">
      <div className="recommendationHead">
        <i>{item.rank}</i>
        <span>
          <b>{item.stockName}</b>
          <small>{item.stockCode} · {item.market} · {item.candidateSource === 'PRECISION' ? '정밀' : 'Broad'} · {scannerTypeLabel(item.scannerType)} · {new Date(item.detectedAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</small>
        </span>
        <strong><small>신호 가격 </small>{money(item.buyReferencePrice)}원</strong>
      </div>
      <dl>
        <span><dt>추천점수</dt><dd>{num(item.recommendationScore)}</dd></span>
        <span><dt>기회점수</dt><dd>{num(item.opportunityScore)}</dd></span>
        <span><dt>위험점수</dt><dd>{num(item.riskScore)}</dd></span>
        <span><dt>5분 등락</dt><dd className={(item.fiveMinuteChangeRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.fiveMinuteChangeRate)}</dd></span>
        <span><dt>거래량</dt><dd>{num(item.volumeRatio)}배</dd></span>
        <span><dt>거래대금</dt><dd>{money((item.dailyTradingValue ?? 0) / 1000000)}백만</dd></span>
      </dl>
      <div className="decisionPanel pending">
        데이터 품질 {item.dataQuality} · 관측 {item.coverageMinutes}분
        {missingFeatures.length > 0 ? ` · 누락 ${missingFeatures.join(', ')}` : ''}
      </div>
      {!advanced && <BeginnerRecommendationSummary item={item} decision={decision} />}
      {advanced && <div className="closingFactors">
        <FactorColumn title="추천 근거" rows={recommendationFactors} />
        <FactorColumn title="위험 근거" rows={riskFactors} />
      </div>}
      <PerformancePanel performance={performance} />
      <DecisionPanel decision={decision} />
    </article>
  )
}

function parseMissingFeatures(value: string): string[] {
  try {
    const parsed = JSON.parse(value || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function BeginnerRecommendationSummary({ item, decision }: { item: Recommendation; decision?: OvernightDecision }) {
  const risk = item.riskScore ?? 0
  const opportunity = item.opportunityScore ?? 0
  const decisionText = decision ? decisionLabel(decision.decision) : '다음날 판단 대기'
  return (
    <div className="beginnerSummary">
      <span><small>한 줄 판단</small><b>{opportunity >= 60 && risk <= 45 ? '우선 검토 후보' : opportunity >= 40 ? '관찰 후보' : '신중 검토'}</b></span>
      <span><small>주의 수준</small><b className={risk > 65 ? 'loss' : undefined}>{risk > 65 ? '높음' : risk > 45 ? '보통' : '낮음'}</b></span>
      <span><small>다음 행동</small><b>{decisionText}</b></span>
    </div>
  )
}

function DecisionPanel({ decision }: { decision?: OvernightDecision }) {
  if (!decision) return <div className="decisionPanel pending">매도/보유 판단 대기</div>
  if (decision.decision === 'DATA_PENDING') {
    return <div className="decisionPanel pending">현재가 기준 판단 대기</div>
  }
  return (
    <div className={`decisionPanel ${decision.decision.toLowerCase()}`}>
      <span><small>현재 판단</small><b>{decisionLabel(decision.decision)}</b></span>
      <span><small>현재 수익률</small><b className={(decision.returnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(decision.returnRate)}</b></span>
      <span><small>현재가</small><b>{money(decision.currentPrice)}원</b></span>
      <span><small>VWAP 이격</small><b className={(decision.vwapDistanceRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(decision.vwapDistanceRate)}</b></span>
      <span><small>체결강도</small><b>{num(decision.tradeStrength)}</b></span>
      <span><small>MA20</small><b>{decision.ma20 ? `${money(decision.ma20)}원` : '--'}</b></span>
    </div>
  )
}

function PerformancePanel({ performance }: { performance?: OvernightPerformance }) {
  if (!performance) return <div className="performancePanel pending">다음날 성과 추적 대기</div>
  const legacy = performance.calculationVersion !== 'overnight-observation-v2'
  const missing = parseMissingFeatures(performance.missingIntervals ?? '[]')
  const label = legacy ? '기존 계산 (완결성 미검증)' : ({
    PENDING: '거래 시작 대기', IN_PROGRESS: '장중 관측', COMPLETED: '최종 관측 완료',
    DATA_INCOMPLETE: '데이터 불완전', DATA_MISSING: '데이터 없음',
  } as Record<string, string>)[performance.status] ?? '확인 대기'
  return (
    <>
    <div className="performancePanel observationPanel">
      <span><small>관측 상태</small><b>{label}</b></span>
      <span><small>다음 거래일</small><b>{performance.nextTradingDate ?? '--'}</b></span>
      <span><small>관측 기준 시각</small><b>{performance.observedThrough
        ? new Date(performance.observedThrough).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '--'}</b></span>
      <span><small>시가</small><b className={(performance.openReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.openReturnRate)}</b></span>
      <span><small>최고</small><b className={(performance.maxReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.maxReturnRate)}</b></span>
      <span><small>최저</small><b className={(performance.maxDrawdownRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.maxDrawdownRate)}</b></span>
      {!legacy && <span><small>최근 관측 수익률</small><b>{pct(performance.latestReturnRate ?? null)}</b></span>}
      <span><small>{legacy ? '기존 마지막 관측' : '최종 종가'}</small><b className={(performance.closeReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.closeReturnRate)}</b></span>
      <span><small>관측 구간 내 도달</small><b>{hitLabel(performance.targetHit, performance.stopHit)}</b></span>
      {!legacy && <span><small>고정 관측 기준</small><b>목표 {pct(performance.targetRate ?? null)} / 손절 {pct(performance.stopRate ?? null)}</b></span>}
    </div>
    {missing.length > 0 && <details className="observationGaps"><summary>누락·중복 구간 {missing.length}개</summary>
      <ul>{missing.map(at => <li key={at}>{new Date(at).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</li>)}</ul>
    </details>}
    </>
  )
}

function FactorColumn({ title, rows }: { title: string; rows: { label: string; value: string }[] }) {
  return <div><h3>{title}</h3>{rows.map(row => <span key={row.label}><small>{row.label}</small><b>{row.value}</b></span>)}</div>
}
