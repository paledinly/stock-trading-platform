import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import './closingRecommendation.css'

type Recommendation = {
  id: number
  recommendationDate: string
  generatedAt: string
  rank: number
  stockCode: string
  stockName: string
  market: string
  scannerType: string
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
  recommendationDate: string
  generatedAt: string
  sourceDetections: number
  storedCandidates: number
  strategyVersion: string
  candidates: Recommendation[]
}

type OvernightPerformance = {
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
  buyReferencePrice: number
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
  averageMaxDrawdownRate: number | null
  targetHitRate: number | null
  stopHitRate: number | null
  ambiguousCount: number
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

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!response.ok) throw new Error('마감 추천 데이터를 처리하지 못했습니다.')
  return response.status === 204 ? undefined as T : response.json()
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

function daysAgo(days: number) {
  return new Date(Date.now() - days * 86400000).toISOString().slice(0, 10)
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

function scannerTypeLabel(value: string) {
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
  const [lastRun, setLastRun] = useState<GenerateResponse>()
  const [lastTrack, setLastTrack] = useState<TrackPerformanceResponse>()
  const [lastDecisionRun, setLastDecisionRun] = useState<DecisionEvaluationResponse>()
  const recommendations = useQuery({
    queryKey: ['closing-recommendations', date],
    queryFn: () => api<Recommendation[]>(`/api/v1/closing-recommendations?date=${date}`),
  })
  const performances = useQuery({
    queryKey: ['closing-recommendation-performance', date],
    queryFn: () => api<OvernightPerformance[]>(`/api/v1/closing-recommendations/performance?date=${date}`),
  })
  const decisions = useQuery({
    queryKey: ['closing-recommendation-decisions', date],
    queryFn: () => api<OvernightDecision[]>(`/api/v1/closing-recommendations/decisions?date=${date}`),
  })
  const generate = useMutation({
    mutationFn: () => api<GenerateResponse>(
      `/api/v1/closing-recommendations/generate?date=${date}&limit=${limit}&minOpportunity=${minOpportunity}&maxRisk=${maxRisk}`,
      { method: 'POST' },
    ),
    onSuccess: result => {
      setLastRun(result)
      cache.invalidateQueries({ queryKey: ['closing-recommendations', date] })
    },
  })
  const track = useMutation({
    mutationFn: () => api<TrackPerformanceResponse>(
      `/api/v1/closing-recommendations/performance/track?date=${date}&targetRate=${targetRate}&stopRate=${stopRate}`,
      { method: 'POST' },
    ),
    onSuccess: result => {
      setLastTrack(result)
      cache.invalidateQueries({ queryKey: ['closing-recommendation-performance', date] })
    },
  })
  const evaluateDecisions = useMutation({
    mutationFn: () => api<DecisionEvaluationResponse>(
      `/api/v1/closing-recommendations/decisions/evaluate?date=${date}&targetRate=${targetRate}&stopRate=${stopRate}`,
      { method: 'POST' },
    ),
    onSuccess: result => {
      setLastDecisionRun(result)
      cache.invalidateQueries({ queryKey: ['closing-recommendation-decisions', date] })
    },
  })
  const runBacktest = useMutation({
    mutationFn: () => api<OvernightBacktest>(
      `/api/v1/closing-recommendations/backtest?from=${backtestFrom}&to=${backtestTo}&limit=${limit}&minOpportunity=${minOpportunity}&maxRisk=${maxRisk}&targetRate=${targetRate}&stopRate=${stopRate}`,
    ),
    onSuccess: setBacktest,
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    generate.mutate()
  }

  const rows = recommendations.data ?? lastRun?.candidates ?? []
  const performanceRows = performances.data ?? lastTrack?.performances ?? []
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
          <label>추천일<input type="date" value={date} onChange={event => setDate(event.target.value)} /></label>
          {advanced && <label>후보 수<input type="number" min="1" max="30" value={limit} onChange={event => setLimit(Number(event.target.value))} /></label>}
          {advanced && <label>최소 기회점수<input type="number" min="0" max="100" value={minOpportunity} onChange={event => setMinOpportunity(Number(event.target.value))} /></label>}
          {advanced && <label>최대 위험점수<input type="number" min="0" max="100" value={maxRisk} onChange={event => setMaxRisk(Number(event.target.value))} /></label>}
          <button disabled={generate.isPending}>{generate.isPending ? '생성 중...' : '추천 생성'}</button>
        </form>
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
        <section className="menuGuide">
          <h2>사용 안내</h2>
          <p>{advanced ? '장마감 전 추천일과 점수 기준을 정한 뒤 추천 생성을 누릅니다. 다음 거래일 데이터가 쌓인 뒤 다음날 성과 추적을 누르면 시가, 최고가, 최저가, 종가 기준 수익률을 함께 확인할 수 있습니다.' : '장마감 전 추천 생성을 누르고 후보를 확인합니다. 다음 거래일에는 매도/보유 판단을 눌러 계속 보유할지, 익절할지, 손절 기준에 닿았는지 확인합니다.'}</p>
        </section>
        {generate.error && <div className="closingEmpty">{generate.error.message}</div>}
        {track.error && <div className="closingEmpty">{track.error.message}</div>}
        {evaluateDecisions.error && <div className="closingEmpty">{evaluateDecisions.error.message}</div>}
        {runBacktest.error && <div className="closingEmpty">{runBacktest.error.message}</div>}
        {recommendations.error && <div className="closingEmpty">{recommendations.error.message}</div>}
        {advanced && backtest && <BacktestResult data={backtest} />}
        <section className="closingSummary">
          <article><small>원본 탐지</small><b>{lastRun?.sourceDetections ?? '--'}</b></article>
          <article><small>저장 후보</small><b>{lastRun?.storedCandidates ?? rows.length}</b></article>
          <article><small>성과 완료</small><b>{lastTrack?.completed ?? performanceRows.filter(row => row.status === 'COMPLETED').length}</b></article>
          <article><small>보유 연장</small><b>{lastDecisionRun?.extendHold ?? (decisions.data ?? []).filter(row => row.decision === 'EXTEND_HOLD').length}</b></article>
        </section>
        <section className="closingGrid">
          <div className="closingList">
            <h2>추천 랭킹</h2>
            {recommendations.isLoading && <div className="closingEmpty">불러오는 중...</div>}
            {!recommendations.isLoading && rows.length === 0 && <div className="closingEmpty"><b>추천 후보가 없습니다</b><p>장중 탐지가 쌓인 뒤 추천 생성을 실행하세요.</p></div>}
            {rows.map(item => <RecommendationCard key={item.id} item={item} performance={performanceByRecommendation.get(item.id)} decision={(decisions.data ?? lastDecisionRun?.decisions ?? []).find(row => row.recommendationId === item.id)} advanced={advanced} />)}
          </div>
        </section>
      </main>
    </div>
  )
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
      <AlgorithmSummaryPanel summaries={data.algorithmSummaries ?? []} />
      <StrategySummaryPanel summaries={data.strategySummaries ?? []} />
      <div className="backtestRows">
        {data.rows.length === 0 && <div className="closingEmpty">백테스트 결과가 없습니다</div>}
        {data.rows.slice(0, 20).map(row => (
          <article key={`${row.recommendationDate}-${row.rank}-${row.stockCode}`}>
            <span><b>{row.recommendationDate} #{row.rank} {row.stockName}</b><small>{row.stockCode} · {scannerTypeLabel(row.scannerType)} · 점수 {num(row.recommendationScore)}</small></span>
            <span className={(row.openReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>시가 {pct(row.openReturnRate)}</span>
            <span className={(row.maxReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>최고 {pct(row.maxReturnRate)}</span>
            <span className={(row.maxDrawdownRate ?? 0) >= 0 ? 'gain' : 'loss'}>최저 {pct(row.maxDrawdownRate)}</span>
            <span>{row.targetHit ? '목표 도달' : row.stopHit ? '손절 도달' : row.status === 'COMPLETED' ? '미도달' : '데이터 없음'}</span>
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
        <small>목표/손절 동시 도달은 보수적으로 손절 처리</small>
      </div>
      <div className="strategyRows">
        {summaries.map(item => (
          <article key={item.strategy}>
            <span><b>{item.label}</b><small>{item.sampleSize}건 검증</small></span>
            <span><small>승률</small><b>{pct(item.winRate)}</b></span>
            <span><small>평균 수익</small><b className={(item.averageReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageReturnRate)}</b></span>
            <span><small>평균 낙폭</small><b className={(item.averageMaxDrawdownRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.averageMaxDrawdownRate)}</b></span>
            <span><small>목표 도달</small><b>{pct(item.targetHitRate)}</b></span>
            <span><small>손절 도달</small><b>{pct(item.stopHitRate)}</b></span>
            <span><small>동시 도달</small><b>{item.ambiguousCount}</b></span>
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
  return (
    <article className="recommendationCard">
      <div className="recommendationHead">
        <i>{item.rank}</i>
        <span>
          <b>{item.stockName}</b>
          <small>{item.stockCode} · {item.market} · {scannerTypeLabel(item.scannerType)} · {new Date(item.detectedAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</small>
        </span>
        <strong>{money(item.buyReferencePrice)}원</strong>
      </div>
      <dl>
        <span><dt>추천점수</dt><dd>{num(item.recommendationScore)}</dd></span>
        <span><dt>기회점수</dt><dd>{num(item.opportunityScore)}</dd></span>
        <span><dt>위험점수</dt><dd>{num(item.riskScore)}</dd></span>
        <span><dt>5분 등락</dt><dd className={(item.fiveMinuteChangeRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.fiveMinuteChangeRate)}</dd></span>
        <span><dt>거래량</dt><dd>{num(item.volumeRatio)}배</dd></span>
        <span><dt>거래대금</dt><dd>{money((item.dailyTradingValue ?? 0) / 1000000)}백만</dd></span>
      </dl>
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
  if (performance.status === 'DATA_MISSING') return <div className="performancePanel pending">다음 거래일 5분봉 데이터가 아직 없습니다</div>
  return (
    <div className="performancePanel">
      <span><small>다음 거래일</small><b>{performance.nextTradingDate ?? '--'}</b></span>
      <span><small>시가</small><b className={(performance.openReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.openReturnRate)}</b></span>
      <span><small>최고</small><b className={(performance.maxReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.maxReturnRate)}</b></span>
      <span><small>최저</small><b className={(performance.maxDrawdownRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.maxDrawdownRate)}</b></span>
      <span><small>종가</small><b className={(performance.closeReturnRate ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(performance.closeReturnRate)}</b></span>
      <span><small>도달</small><b>{performance.targetHit ? '목표' : performance.stopHit ? '손절' : '미도달'}</b></span>
    </div>
  )
}

function FactorColumn({ title, rows }: { title: string; rows: { label: string; value: string }[] }) {
  return <div><h3>{title}</h3>{rows.map(row => <span key={row.label}><small>{row.label}</small><b>{row.value}</b></span>)}</div>
}
