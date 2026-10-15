/**
 * 장마감 추천 화면이에요. 15:00 전후까지 실제로 받은 정보로 후보를 평가하고, 추천 랭킹·다음 거래일 성과·매도/보유 판단·백테스트·전략 검증 통계를 보여줘요.
 * 추천 점수는 상승 확률이 아니고, 진입과 청산은 모두 가상 실행이에요(실주문 없음).
 * AppShell(앱 전체 껍데기)이 '마감 추천' 메뉴(#/closing)에서 ClosingRecommendationPage를 띄워요.
 */
import { FormEvent, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import './closingRecommendation.css'

/** 백엔드가 돌려주는 추천 종목 한 줄이에요. 타입(type, 데이터 모양을 미리 정한 약속)으로 응답 필드를 적어 둬요. */
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

/** "후보 평가" 실행 결과예요. 최종 추천(candidates)과 관찰·제외까지 포함한 전체 평가(evaluations)를 함께 담아요. */
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

/** 후보 한 종목을 평가한 기록이에요. disposition은 최종 추천(SELECTED)·관찰(WATCH)·제외(EXCLUDED) 중 하나예요. */
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

/**
 * 추천 종목의 다음 거래일 성과 관측 결과예요.
 * 가상 진입가·청산가와 비용 반영 순수익은 가상 실행 값이고, 비용이 검증되지 않으면 순수익은 비어 있어요.
 */
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
  virtualEntryAt?: string | null
  virtualEntryPrice?: number | null
  executionExitAt?: string | null
  executionExitPrice?: number | null
  grossReturnRate?: number | null
  netReturnRate?: number | null
  executionExitReason?: string | null
  executionAmbiguous?: boolean
  executionModelVersion?: string | null
  costAssumption?: string
  costStatus?: string
}

/** "다음날 성과 추적" 버튼을 눌렀을 때 받는 요약 응답이에요. */
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

/** 최종 추천과 미선정 후보를 같은 규칙으로 관측해 선택 편향을 확인하는 보고서예요. */
type CandidateObservationReport = {
  runId: number
  candidates: number
  selected: number
  unselected: number
  completed: number
  rows: Array<{
    id: number; stockCode: string; stockName: string; candidateSource: string
    disposition: string; decisionReason: string; finalScore: number | null; status: string
    expectedSessionDate: string; entryPrice: number | null; exitPrice: number | null
    grossReturnRate: number | null; netReturnRate: number | null; costStatus: string
  }>
}

/** 보유 중인 추천 종목에 대한 매도/보유 판단 한 건이에요(가상 판단이며 주문이 아니에요). */
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

/** "매도/보유 판단" 버튼을 눌렀을 때 받는 판단 결과 묶음이에요. */
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

/** 오버나잇 백테스트(과거 데이터로 다시 돌려 보는 검증)의 종목별 결과 한 줄이에요. */
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

/** 오버나잇 백테스트 전체 결과예요. 승률·평균 수익과 정합성 검사, 전략·알고리즘 비교를 함께 담아요. */
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

/** 모의 계좌 기준 성과예요. 원장과 지수 데이터가 준비되기 전에는 status가 READY가 아니에요. */
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

/** 추천 알고리즘별 백테스트 비교 요약이에요. */
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

/** 매도(청산) 전략별 백테스트 비교 요약이에요. */
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

/** 백테스트 정합성 검사 결과예요. 미래 데이터 사용이나 데이터 부족 같은 문제를 모아요. */
type BacktestIntegrity = {
  status: 'PASS' | 'WARNING' | 'FAIL'
  totalChecks: number
  passedChecks: number
  warningChecks: number
  failedChecks: number
  issues: BacktestIntegrityIssue[]
}

/** 정합성 검사에서 발견된 문제 한 건이에요. */
type BacktestIntegrityIssue = {
  severity: 'INFO' | 'WARNING' | 'ERROR'
  category: string
  recommendationDate: string | null
  stockCode: string | null
  stockName: string | null
  message: string
  detail: string
}

/**
 * 공식 전진(Forward) 관측 표본으로 만든 전략 통계예요.
 * 점수 구간, 관측 그룹, 시간순 표본 외(OOS) 검증, 최근 성과 감시, 전략 승격 조건을 담아요.
 */
type StrategyAnalytics = {
  from: string
  to: string
  generatedAt: string
  sampleSize: number
  population: string
  netExecutionSampleSize: number
  averageNetReturn: number | null
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

/** 기간 하나의 표본 수와 성과 지표예요. */
type PeriodMetrics = {
  from: string | null; to: string | null; sampleSize: number
  targetHitRate: number | null; averageCloseReturn: number | null
}

/** OOS(표본 외) 검증에서 상위/하위 그룹을 비교할 때 쓰는 타입들이에요. */
type OosGroup = { sampleSize: number; targetHitRate: number | null; averageCloseReturn: number | null }
type OosComparison = {
  developmentLow: OosGroup; developmentHigh: OosGroup
  validationLow: OosGroup; validationHigh: OosGroup
}
type MonitoringMetrics = {
  sampleSize: number; targetHitRate: number | null; stopHitRate: number | null
  averageCloseReturn: number | null; averageMaxDrawdown: number | null
}

/**
 * 백엔드 API를 호출하는 공통 함수예요. fetch(브라우저 내장 HTTP 요청 함수)를 비동기(async, 기다렸다가 결과를 받는 방식)로 불러요.
 * 받는 것: 요청 주소와 선택 옵션. 돌려주는 것: JSON 결과(204면 null).
 * 실패하면 409(기존 실행과 조건이 다름)와 그 밖의 오류를 한국어 메시지로 바꿔 던져요.
 * ⚠️ 확인 필요: `...init`이 headers 뒤에 펼쳐져서, init에 headers가 있으면 위에서 합친 Content-Type이 덮어써져요(현재 POST 호출은 본문이 없어 영향은 작아 보여요).
 */
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

/** 같은 날짜에 실행된 후보 평가 이력 한 건이에요. FORWARD는 장중 공식 평가, REPLAY는 과거 재생이에요. */
type EvaluationRun = {
  id: number; recommendationDate: string; generatedAt: string; completedAt: string | null
  evaluatedAsOf: string | null; executionMode: string; strategyVersion: string
}

/** 거래일 하루의 수집 완결성 점검 결과예요. 공식 평가·가상 진입·실시간 연결이 제대로 됐는지 판정해요. */
export type DailyHealth = {
  tradeDate: string; verdict: 'COMPLETE' | 'DEGRADED' | 'MISSING'; reasons: string[]; recorded: boolean
  runId: number | null; runCompletedAt: string | null; selectedCount: number | null; selectedEntered: number | null
  observationStatuses: Record<string, number>; realtimeConnected: boolean | null
  realtimeDroppedDelta: number | null; checkedAt: string | null
}

// 판정 코드와 사유 코드를 화면에 보여줄 한국어 문구로 바꾸는 표예요.
const healthVerdictLabels: Record<DailyHealth['verdict'], string> = { COMPLETE: '정상', DEGRADED: '불완전', MISSING: '누락' }
const healthReasonLabels: Record<string, string> = {
  NO_HEALTH_RECORD: '기록 없음(백엔드 중단 추정)',
  OFFICIAL_RUN_MISSING: '공식 평가 없음',
  OFFICIAL_RUN_INCOMPLETE: '공식 평가 미완료',
  EVALUATION_AFTER_ENTRY_DEADLINE: '진입 마감 후 평가 완료',
  SELECTED_ENTRY_MISSING: '추천 종목 가상 진입 누락',
  REALTIME_UNOBSERVED: '15:15 전후 실시간 상태 미관측',
  REALTIME_DISCONNECTED: '15:15 전후 실시간 연결 끊김',
  REALTIME_TICKS_DROPPED: '15:15 전후 틱 처리 누락',
  OBSERVATION_INCOMPLETE: '후보 관측 미완료·결측',
  SCANNER_DETECTIONS_MISSING: '14:30~15:15 스캐너 탐지 0건',
}

/**
 * 일별 수집 완결성 표를 그리는 React 컴포넌트(화면 조각을 만드는 함수)예요. 출석부처럼 거래일마다 정상/불완전/누락을 보여줘요.
 * 받는 것(props, 부모가 넘겨주는 값): rows(거래일별 점검 결과). 돌려주는 것: 표 화면(제목은 감싸는 접는 영역이 붙여요).
 */
export function DailyHealthTable({ rows }: { rows: DailyHealth[] }) {
  return (
    <section className="menuGuide">
      {rows.length === 0 ? <p>기록된 거래일이 없습니다.</p> : <table>
        <thead><tr><th>거래일</th><th>판정</th><th>평가 완료</th><th>추천/진입</th><th>후보 관측</th><th>실시간</th><th>사유</th></tr></thead>
        <tbody>{rows.map(row => {
          // 후보 관측 상태별 건수를 모두 더해 전체 관측 수를 구해요.
          const observed = Object.values(row.observationStatuses ?? {}).reduce((sum, count) => sum + count, 0)
          return <tr key={row.tradeDate}>
            <td>{row.tradeDate}</td>
            <td>{healthVerdictLabels[row.verdict] ?? row.verdict}</td>
            <td>{row.runCompletedAt ? new Date(row.runCompletedAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' }) : '--'}</td>
            <td>{row.selectedCount == null ? '--' : `${row.selectedEntered ?? 0}/${row.selectedCount}`}</td>
            <td>{row.recorded ? `완료 ${row.observationStatuses?.COMPLETED ?? 0}/${observed}` : '--'}</td>
            <td>{row.realtimeConnected == null ? '미관측' : row.realtimeConnected ? `연결 · 누락 ${row.realtimeDroppedDelta ?? 0}` : '끊김'}</td>
            <td>{row.reasons.map(reason => healthReasonLabels[reason] ?? reason).join(', ') || '--'}</td>
          </tr>
        })}</tbody>
      </table>}
    </section>
  )
}

/** "일봉 데이터 준비" 실행 결과예요. */
type DailyPrepareResult = {
  recommendationDate: string; latestRequiredDate: string; candidateStocks: number
  attemptedStocks: number; savedCandles: number; readyStocks: number
  failedStocks: number; skippedStocks: number
}

/** 실행 방식 코드를 한국어 이름으로 바꿔요. 받는 것: 실행 방식. 돌려주는 것: 표시 문구. */
function executionLabel(mode?: string) {
  return mode === 'FORWARD' ? '장중 평가' : mode === 'REPLAY' ? '과거 재생' : '기존 기록'
}

/** 목표·손절 도달 여부를 한 줄 문구로 만들어요. 둘 다 도달하면 어느 쪽이 먼저인지 모른다고 표시해요. */
function hitLabel(target: boolean, stop: boolean) {
  return target && stop ? '목표·손절 모두 도달 (순서 미확인)' : target ? '목표 도달' : stop ? '손절 도달' : '미도달'
}

/** 한국 시간 기준 오늘 날짜(YYYY-MM-DD)를 돌려줘요. */
function today() {
  return marketDate(new Date())
}

/** 한국 시간 기준 며칠 전 날짜(YYYY-MM-DD)를 돌려줘요. 받는 것: 며칠 전인지. */
function daysAgo(days: number) {
  return marketDate(new Date(Date.now() - days * 86400000))
}

/**
 * 주어진 시각을 서울 시간대 날짜 문자열(YYYY-MM-DD)로 바꿔요.
 * 브라우저가 어느 시간대에 있든 한국 장 날짜를 쓰려고 Intl 날짜 포맷을 사용해요.
 */
function marketDate(date: Date) {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date)
  const part = (type: string) => parts.find(row => row.type === type)!.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

/** 금액을 반올림해 천 단위 쉼표로 보여줘요. 값이 없으면 '--'를 돌려줘요. */
function money(value?: number | null) {
  return value == null ? '--' : Math.round(value).toLocaleString('ko-KR')
}

/** 비율을 부호가 붙은 소수 둘째 자리 퍼센트로 보여줘요. 값이 없으면 '--'를 돌려줘요. */
function pct(value?: number | null) {
  return value == null ? '--' : `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
}

/** 숫자를 소수 둘째 자리까지 한국식으로 보여줘요. 값이 없으면 '--'를 돌려줘요. */
function num(value?: number | null) {
  return value == null ? '--' : value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

/** 스캐너 유형 코드를 한국어 이름으로 바꿔요. 값이 없으면 Broad(넓은 범위 스냅샷) 후보로 봐요. */
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
    VWAP_RECLAIM: 'VWAP 상단·상승 동시 충족',
    PULLBACK_REBREAK: '동일 봉 VWAP 접촉·고점 상회',
  }
  return labels[value] ?? value
}

/** 백테스트 정합성 상태(통과/경고/실패)를 한국어로 바꿔요. */
function integrityStatusLabel(value: BacktestIntegrity['status']) {
  const labels = {
    PASS: '통과',
    WARNING: '경고',
    FAIL: '실패',
  }
  return labels[value]
}

/** 정합성 문제 분류 코드를 한국어로 바꿔요. */
function integrityCategoryLabel(value: string) {
  const labels: Record<string, string> = {
    DATA_COVERAGE: '데이터 범위',
    LOOKAHEAD: '미래 데이터',
    EXECUTION: '체결 가정',
    SAMPLE_BIAS: '표본 편향',
  }
  return labels[value] ?? value
}

/** 매도/보유 판단 코드를 한국어로 바꿔요. */
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

/** 알고리즘 비교의 신뢰도 등급을 한국어로 바꿔요. */
function confidenceLabel(value: RecommendationAlgorithmSummary['confidence']) {
  const labels = {
    LOW: '낮음',
    MEDIUM: '보통',
    HIGH: '높음',
  }
  return labels[value]
}

/** 손실 패턴 코드를 한국어로 바꿔요. */
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

/**
 * 추천/위험 근거 JSON을 화면용 "이름: 값" 목록으로 풀어요.
 * 받는 것: 근거 JSON 문자열과 종류(추천 또는 위험). 돌려주는 것: { label, value } 배열.
 * JSON이 깨져 있으면 '확인 대기' 한 줄로 대신해요.
 */
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
    // 점수 가감 요인과 5분봉·일봉 이동평균선 상태를 한 목록으로 합쳐요.
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

/**
 * 일봉 이동평균선 상태를 화면용 목록으로 바꿔요.
 * 받는 것: 일봉 이평선 정보와 종류. 추천이면 정배열·기울기, 위험이면 과열·이격을 보여줘요.
 */
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

/**
 * 5분봉 이동평균선 상태를 화면용 목록으로 바꿔요.
 * 받는 것: 5분봉 이평선 정보와 종류. 추천이면 정배열·골든크로스, 위험이면 MA20 이탈과 이평선 값을 보여줘요.
 */
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

/**
 * 장마감 추천 화면 전체를 그리는 React 컴포넌트예요. 마감 직전 장보기 목록처럼 오늘 살펴볼 후보를 순위대로 정리해요.
 * 받는 것(props): advanced(true면 백테스트·전략 통계까지 표시. AppShell은 항상 true로 넘겨요).
 * 돌려주는 것: 위에서부터 조회 줄(추천일·평가 이력), 오늘 결과(요약·추천 랭킹·성과·판단), 접는 영역(후보 관측·생성 당시
 * 후보 평가·검증 요약·수집 완결성), 도구(후보 평가 실행·성과 추적·백테스트·일봉 준비)가 담긴 화면.
 * React Query(서버 데이터를 가져와 쿼리 캐시(메모리 보관소)에 저장하는 도구)로 조회와 실행을 처리해요.
 */
export function ClosingRecommendationPage({ advanced = false }: { advanced?: boolean }) {
  const cache = useQueryClient()
  // 화면 입력값을 상태(state, 바뀌면 화면이 다시 그려지는 값)로 들고 있어요.
  const [date, setDate] = useState(today())
  const [limit, setLimit] = useState(10)
  const [minOpportunity, setMinOpportunity] = useState(35)
  const [maxRisk, setMaxRisk] = useState(65)
  const [targetRate, setTargetRate] = useState(3)
  const [stopRate, setStopRate] = useState(-2)
  const [backtestFrom, setBacktestFrom] = useState(daysAgo(20))
  const [backtestTo, setBacktestTo] = useState(daysAgo(1))
  const [backtest, setBacktest] = useState<OvernightBacktest>()
  // 사용자가 고른 평가 실행이에요. 날짜와 함께 기억해서 날짜가 바뀌면 무효가 돼요.
  const [selection, setSelection] = useState<{ date: string; id: number }>()
  // 같은 입력으로 다시 누르면 같은 Idempotency-Key(중복 실행 방지 키)를 재사용하려고 기억해 둬요.
  const request = useRef<{ fingerprint: string; key: string } | null>(null)
  // 선택한 날짜의 평가 이력을 불러와요. useQuery는 데이터를 가져와 캐시하는 훅(hook, React 기능을 꺼내 쓰는 함수)이에요.
  const history = useQuery({
    queryKey: ['closing-runs', date],
    queryFn: () => api<EvaluationRun[]>(`/api/v1/closing-recommendations/runs?date=${date}`),
  })
  // 기본으로는 공식 장중 평가(FORWARD)를 먼저 고르고, 없으면 가장 앞의 이력을 써요.
  const defaultRun = history.data?.find(run => run.executionMode === 'FORWARD') ?? history.data?.[0]
  const runId = selection?.date === date ? selection.id : defaultRun?.id
  const selectedRun = history.data?.find(run => run.id === runId)
  const runParam = runId == null ? '' : `&runId=${runId}`
  // 아래 조회들은 평가 이력을 받은 뒤(enabled)에만 실행돼요.
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
  // 전체 후보 관측은 고급 모드에서 공식 장중 평가를 골랐을 때만 조회해요.
  const candidateObservations = useQuery({
    queryKey: ['closing-candidate-observations', runId],
    queryFn: () => api<CandidateObservationReport>(
      `/api/v1/closing-recommendations/candidate-observations?runId=${runId}`),
    enabled: advanced && runId != null && selectedRun?.executionMode === 'FORWARD',
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
  // 최근 20일 수집 완결성은 모드와 상관없이 항상 조회해요.
  const dailyHealth = useQuery({
    queryKey: ['closing-daily-health'],
    queryFn: () => api<DailyHealth[]>(`/api/v1/closing-recommendations/daily-health?from=${daysAgo(20)}&to=${today()}`),
  })
  // 후보 평가 실행이에요. useMutation은 서버에 변경 요청을 보내는 훅이에요.
  const generate = useMutation({
    mutationFn: (input: { date: string; limit: number; minOpportunity: number; maxRisk: number; key: string }) => api<GenerateResponse>(
      `/api/v1/closing-recommendations/generate?date=${input.date}&limit=${input.limit}&minOpportunity=${input.minOpportunity}&maxRisk=${input.maxRisk}`,
      { method: 'POST', headers: { 'Idempotency-Key': input.key } },
    ),
    onSuccess: result => {
      // 성공하면 중복 방지 키를 비우고, 공식 평가이거나 공식 평가가 아직 없을 때만 새 실행을 선택해요.
      // 결과는 바로 캐시에 넣고, 이력과 추천 목록은 다시 불러오게 해요.
      request.current = null
      if (result.executionMode === 'FORWARD' || !history.data?.some(run => run.executionMode === 'FORWARD'))
        setSelection({ date: result.recommendationDate, id: result.runId })
      cache.setQueryData(['closing-evaluation', result.recommendationDate, result.runId], result)
      cache.invalidateQueries({ queryKey: ['closing-runs', result.recommendationDate] })
      cache.invalidateQueries({ queryKey: ['closing-recommendations', result.recommendationDate] })
    },
  })
  // 추천 종목의 다음 거래일 성과를 계산하게 요청해요.
  const track = useMutation({
    mutationFn: () => api<TrackPerformanceResponse>(
      `/api/v1/closing-recommendations/performance/track?date=${date}&targetRate=${targetRate}&stopRate=${stopRate}${runParam}`,
      { method: 'POST' },
    ),
    onSuccess: () => {
      cache.invalidateQueries({ queryKey: ['closing-recommendation-performance'] })
    },
  })
  // 최종 추천과 미선정 후보 전체의 성과를 같은 규칙으로 추적하게 요청해요.
  const trackCandidates = useMutation({
    mutationFn: () => api<CandidateObservationReport>(
      `/api/v1/closing-recommendations/candidate-observations/track?runId=${runId}&targetRate=${targetRate}&stopRate=${stopRate}`,
      { method: 'POST' },
    ),
    onSuccess: result => cache.setQueryData(['closing-candidate-observations', result.runId], result),
  })
  // 현재가 기준 매도/보유 판단을 요청해요(가상 판단이며 주문이 아니에요).
  const evaluateDecisions = useMutation({
    mutationFn: () => api<DecisionEvaluationResponse>(
      `/api/v1/closing-recommendations/decisions/evaluate?date=${date}&targetRate=${targetRate}&stopRate=${stopRate}${runParam}`,
      { method: 'POST' },
    ),
    onSuccess: () => {
      cache.invalidateQueries({ queryKey: ['closing-recommendation-decisions'] })
    },
  })
  // 지정한 기간과 조건으로 오버나잇 백테스트를 실행해요. 결과는 화면 상태에 보관해요.
  const runBacktest = useMutation({
    mutationFn: () => api<OvernightBacktest>(
      `/api/v1/closing-recommendations/backtest?from=${backtestFrom}&to=${backtestTo}&limit=${limit}&minOpportunity=${minOpportunity}&maxRisk=${maxRisk}&targetRate=${targetRate}&stopRate=${stopRate}`,
    ),
    onSuccess: setBacktest,
  })
  // 이동평균선 계산에 필요한 일봉 데이터를 미리 수집하게 요청해요.
  const prepareDaily = useMutation({
    mutationFn: () => api<DailyPrepareResult>('/api/v1/closing-recommendations/daily-data/prepare',
      { method: 'POST' }),
  })

  /**
   * 후보 평가 폼을 제출할 때 실행돼요. 받는 것: 폼 제출 이벤트.
   * 입력이 그대로면 같은 키를, 바뀌었으면 새 키를 만들어 같은 요청이 두 번 실행되지 않게 해요.
   */
  function submit(event: FormEvent) {
    event.preventDefault()
    const input = { date, limit, minOpportunity, maxRisk }
    const fingerprint = JSON.stringify(input)
    if (request.current?.fingerprint !== fingerprint)
      request.current = { fingerprint, key: crypto.randomUUID() }
    generate.mutate({ ...input, key: request.current.key })
  }

  // 조회된 추천이 없으면 방금 실행한 평가 결과의 후보를 대신 보여줘요.
  const rows = recommendations.data ?? lastRun?.candidates ?? []
  const performanceRows = performances.data ?? []
  const performanceByRecommendation = new Map(performanceRows.map(row => [row.recommendationId, row]))
  const top = rows[0]
  const errors = [
    generate.error && `후보 평가 실패: ${generate.error.message}`,
    track.error && `다음날 성과 추적 실패: ${track.error.message}`,
    trackCandidates.error && `전체 후보 성과 추적 실패: ${trackCandidates.error.message}`,
    evaluateDecisions.error && `매도/보유 판단 실패: ${evaluateDecisions.error.message}`,
    runBacktest.error && `백테스트 실패: ${runBacktest.error.message}`,
  ].filter(Boolean) as string[]
  const loadErrors = [
    history.error && `평가 이력 조회 실패: ${history.error.message}`,
    evaluation.error && `후보 평가 내역 조회 실패: ${evaluation.error.message}`,
    recommendations.error && `추천 조회 실패: ${recommendations.error.message}`,
    performances.error && `성과 조회 실패: ${performances.error.message}`,
    dailyHealth.error && `수집 완결성 조회 실패: ${dailyHealth.error.message}`,
  ].filter(Boolean) as string[]
  const latestHealth = Array.isArray(dailyHealth.data) ? dailyHealth.data[0] : undefined
  // 화면 순서: 조회 줄 → 오늘 결과(요약·안내·추천 랭킹) → 후보 관측·평가 내역 → 검증 요약 → 수집 완결성 → 도구(실행 버튼 모음).
  // 연구·도구 영역은 <details>로 접어 두어, 매일 보는 결과가 맨 위에 오게 해요.
  return (
    <div className="closingPage">
      <header>
        <div>
          <h1>오늘의 마감 추천</h1>
          <span>{advanced ? '15:15까지 수신된 정보로 후보를 평가하고, 장 마감 전 가상 진입 후 다음 거래일 성과를 추적합니다.' : '오늘 장마감에 살펴볼 후보와 다음날 매도 판단을 간단히 정리합니다.'}</span>
        </div>
        <aside>
          <small>최종 추천</small>
          <b>{rows.length}</b>
          <span>{top ? `1위 ${top.stockName}` : selectedRun ? '조건 충족 종목 없음' : '평가 전'}</span>
        </aside>
      </header>
      <main>
        <section className="closingToolbar" aria-label="조회할 평가">
          <label>추천일<input type="date" value={date} onChange={event => {
            setDate(event.target.value); setSelection(undefined)
            generate.reset(); track.reset(); evaluateDecisions.reset()
          }} /></label>
          <label className="evaluationHistory">평가 이력<select value={runId ?? ''} onChange={event => {
            setSelection({ date, id: Number(event.target.value) }); track.reset(); evaluateDecisions.reset()
          }} disabled={!history.data?.length}>
            {!history.data?.length && <option value="">평가 이력 없음</option>}
            {(history.data ?? []).map(run => <option key={run.id} value={run.id}>
              #{run.id} · {new Date(run.generatedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })} · {executionLabel(run.executionMode)}
            </option>)}
          </select></label>
          {selectedRun && <span className="runChip">{executionLabel(selectedRun.executionMode)} · {selectedRun.strategyVersion}
            {selectedRun.completedAt ? ` · 완료 ${new Date(selectedRun.completedAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })}` : ''}</span>}
          {latestHealth && <span className={`runChip health ${latestHealth.verdict.toLowerCase()}`} title={latestHealth.reasons.join(', ')}>
            최근 완결성 {latestHealth.tradeDate.slice(5)} {healthVerdictLabels[latestHealth.verdict]}</span>}
        </section>
        <p className="closingNote">시장 관측과 가상 체결은 분리됩니다. 가상 진입가는 추천 완료 후 첫 확정 5분봉 시가이며, 비용이 검증되지 않으면 순수익을 표시하지 않습니다. 추천 점수는 상승 확률이 아닙니다.</p>
        {[...loadErrors, ...errors].map(message => <div className="closingEmpty" key={message}>{message}</div>)}

        <section className="closingSummary" aria-label="평가 요약">
          <article><small>원본 탐지</small><b>{lastRun?.sourceDetections ?? '--'}</b></article>
          <article><small>Broad 원본</small><b>{lastRun?.sourceBroadSnapshots ?? '--'}</b></article>
          <article><small>저장 후보</small><b>{lastRun?.storedCandidates ?? rows.length}</b></article>
          <article><small>관찰 후보</small><b>{lastRun?.watchCandidates ?? '--'}</b></article>
          <article><small>제외 후보</small><b>{lastRun?.excludedCandidates ?? '--'}</b></article>
          <article><small>최종 관측 완료</small><b>{performanceRows.filter(row => row.status === 'COMPLETED' && row.calculationVersion === 'overnight-observation-v2').length}</b></article>
          <article><small>보유 연장</small><b>{(decisions.data ?? []).filter(row => row.decision === 'EXTEND_HOLD').length}</b></article>
        </section>
        {lastRun?.criteria?.marketSectorAccountChecks === 'UNVERIFIED' &&
          <div className="closingNotice"><b>제한 모드: 주문 자격 미확인</b><p>조건을 충족한 종목을 최대 5종목까지 추천합니다. 시장 지표·업종 집중·계좌 노출은 미검증이며 추천 순위는 매수 가능 판정이나 수익 확률이 아닙니다.</p></div>}
        {lastRun && lastRun.storedCandidates === 0 && lastRun.watchCandidates + lastRun.excludedCandidates > 0 &&
          <div className="closingNotice"><b>조건을 충족한 최종 추천이 없습니다</b><p>{Object.entries(lastRun.exclusionReasons).map(([reason, count]) => `${closingExclusionLabel(reason)} ${count}건`).join(' · ')}</p></div>}
        <section className="closingGrid">
          <div className="closingList">
            <h2>추천 랭킹</h2>
            {recommendations.isLoading && <div className="closingEmpty">불러오는 중...</div>}
            {!recommendations.isLoading && rows.length === 0 && (lastRun
              ? <p className="closingListEmpty">이 평가에는 최종 추천 종목이 없습니다. 제외 사유는 위 안내와 '생성 당시 후보 평가'에서 확인하세요.</p>
              : <div className="closingEmpty"><b>추천 후보가 없습니다</b><p>공식 평가는 15:15에 자동 실행됩니다. 평가 이력이 없으면 아래 '도구'에서 후보 평가를 실행할 수 있습니다.</p></div>)}
            {rows.map(item => <RecommendationCard key={item.id} item={item} performance={performanceByRecommendation.get(item.id)} decision={(decisions.data ?? []).find(row => row.recommendationId === item.id)} advanced={advanced} />)}
          </div>
        </section>

        {advanced && candidateObservations.data && <details className="closingSection">
          <summary><b>전체 후보 관측 (선택 편향 확인)</b><span>최종 추천 {candidateObservations.data.selected} · 미선정 {candidateObservations.data.unselected} · 완료 {candidateObservations.data.completed}/{candidateObservations.data.candidates}</span></summary>
          <p>동일한 가상 진입·익일 청산·비용 규칙으로 최종 추천과 미선정 후보를 분리 관측합니다.</p>
          {candidateObservations.data.rows.map(row => <p key={row.id}>
            {row.stockName} ({row.stockCode}) · {row.disposition === 'SELECTED' ? '최종 추천' : '미선정'} · {row.status} · 총수익 {pct(row.grossReturnRate)} · 순수익 {row.netReturnRate == null ? '미산출' : pct(row.netReturnRate)}
          </p>)}
        </details>}
        {lastRun?.evaluations && <details className="closingSection">
          <summary><b>생성 당시 후보 평가</b><span>최종 {lastRun.evaluations.filter(row => row.disposition === 'SELECTED').length} · 관찰 {lastRun.evaluations.filter(row => row.disposition === 'WATCH').length} · 제외 {lastRun.evaluations.filter(row => row.disposition === 'EXCLUDED').length}</span></summary>
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
        </details>}

        {advanced && <details className="closingSection">
          <summary><b>검증 요약</b><span>전략 승격 조건 · 시간순 표본 외 검증 · 모의 계좌 성과</span></summary>
          <section className="menuGuide">
            <h2>모의 계좌 성과</h2>
            {accountPerformance.isLoading && <p>계좌 자료 확인 중...</p>}
            {accountPerformance.error && <p>계좌 상태 조회 실패: {accountPerformance.error.message}</p>}
            {accountPerformance.data?.status !== 'READY' && !accountPerformance.isLoading && !accountPerformance.error &&
              <p>미산출: 모의 체결·비용 원장과 일별 순자산이 아직 없습니다. KOSPI/KOSDAQ 비교용 동일 보유시간 지수 데이터도 없어 수익률·MDD·샤프·시장 초과수익을 표시하지 않습니다.</p>}
            {accountPerformance.data?.status === 'READY' && <p>누적수익 {pct(accountPerformance.data.cumulativeReturnRate)} · 계좌 MDD {pct(accountPerformance.data.maxDrawdownRate)} · 손익비 {num(accountPerformance.data.profitFactor)} · 샤프 {num(accountPerformance.data.sharpeRatio)} · 소르티노 {num(accountPerformance.data.sortinoRatio)} · 시장 초과수익 {accountPerformance.data.excessReturnRate == null ? '미산출' : pct(accountPerformance.data.excessReturnRate)}</p>}
          </section>
          <StrategyAnalyticsPanel data={strategyAnalytics.data} loading={strategyAnalytics.isLoading}
            error={strategyAnalytics.error?.message} />
        </details>}

        {Array.isArray(dailyHealth.data) && <details className="closingSection">
          <summary><b>일별 수집 완결성</b><span>최근 {Math.min(10, dailyHealth.data.length)}거래일 · 자세한 하루 현황은 '오늘' 화면</span></summary>
          <DailyHealthTable rows={dailyHealth.data.slice(0, 10)} />
        </details>}

        <details className="closingSection closingTools">
          <summary><b>도구</b><span>후보 평가 실행 · 성과 추적 · 매도/보유 판단{advanced ? ' · 오버나잇 백테스트 · 일봉 준비' : ''}</span></summary>
          <h3>후보 평가 실행</h3>
          <p>공식 평가는 15:15에 자동 실행됩니다. 같은 날짜·설정의 공식 평가는 하나로 공유되고, 장 마감 뒤에는 과거 재생(REPLAY)으로 따로 저장됩니다.</p>
          <form className={advanced ? 'advancedForm' : 'simpleForm'} onSubmit={submit}>
            <label>추천일<input type="date" value={date} readOnly aria-readonly="true" /></label>
            {advanced && <label>후보 수<input type="number" min="1" max="30" value={limit} onChange={event => setLimit(Number(event.target.value))} /></label>}
            {advanced && <label>최소 기회점수<input type="number" min="0" max="100" value={minOpportunity} onChange={event => setMinOpportunity(Number(event.target.value))} /></label>}
            {advanced && <label>최대 위험점수<input type="number" min="0" max="100" value={maxRisk} onChange={event => setMaxRisk(Number(event.target.value))} /></label>}
            <button disabled={generate.isPending}>{generate.isPending ? '평가 중...' : '후보 평가'}</button>
          </form>
          <h3>성과 추적 · 매도/보유 판단</h3>
          <section className="performanceControls">
            <label>목표 수익률 %<input type="number" step="0.1" value={targetRate} onChange={event => setTargetRate(Number(event.target.value))} /></label>
            <label>손절 기준 %<input type="number" step="0.1" value={stopRate} onChange={event => setStopRate(Number(event.target.value))} /></label>
            <button onClick={() => track.mutate()} disabled={track.isPending || rows.length === 0}>{track.isPending ? '추적 중...' : '다음날 성과 추적'}</button>
            <button onClick={() => evaluateDecisions.mutate()} disabled={evaluateDecisions.isPending || rows.length === 0}>{evaluateDecisions.isPending ? '판단 중...' : '매도/보유 판단'}</button>
            {advanced && <button onClick={() => trackCandidates.mutate()}
              disabled={trackCandidates.isPending || runId == null || selectedRun?.executionMode !== 'FORWARD'}>
              {trackCandidates.isPending ? '전체 후보 추적 중...' : '전체 후보 성과 추적'}
            </button>}
          </section>
          {advanced && <>
            <h3>오버나잇 백테스트</h3>
            <section className="backtestControls">
              <label>백테스트 시작일<input type="date" value={backtestFrom} onChange={event => setBacktestFrom(event.target.value)} /></label>
              <label>백테스트 종료일<input type="date" value={backtestTo} onChange={event => setBacktestTo(event.target.value)} /></label>
              <button onClick={() => runBacktest.mutate()} disabled={runBacktest.isPending}>{runBacktest.isPending ? '검증 중...' : '오버나잇 백테스트'}</button>
            </section>
            {backtest && <BacktestResult data={backtest} />}
            <h3>일봉 데이터 준비</h3>
            <section className="performanceControls">
              <button type="button" onClick={() => prepareDaily.mutate()} disabled={prepareDaily.isPending}>
                {prepareDaily.isPending ? '일봉 수집 중...' : '일봉 데이터 준비'}
              </button>
              {prepareDaily.data && <span>{prepareDaily.data.recommendationDate} 기준 · {prepareDaily.data.readyStocks}종목 준비 · {prepareDaily.data.savedCandles}봉 저장 · 실패 {prepareDaily.data.failedStocks}종목</span>}
              {prepareDaily.error && <span>일봉 수집 실패: {prepareDaily.error.message}</span>}
            </section>
          </>}
          {errors.map(message => <div className="closingEmpty" key={`tool-${message}`}>{message}</div>)}
        </details>
      </main>
    </div>
  )
}

/**
 * 공식 전진 관측 기반 전략 통계 패널이에요. 성적표처럼 점수 구간·그룹별 성과와 검증 결과를 모아 보여줘요.
 * 받는 것(props): data(통계), loading(불러오는 중), error(오류 문구). 돌려주는 것: 통계 화면 또는 null.
 */
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

/**
 * 전략 승격 조건 체크리스트를 보여줘요. 체크리스트처럼 조건마다 충족/미충족을 표시해요.
 * 받는 것(props): value(승격 조건 정보). 돌려주는 것: 조건 목록 화면.
 */
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

/**
 * 최근 전략 상태를 이전 구간과 비교해 보여줘요. 건강검진 결과지처럼 지난번과 이번 수치를 나란히 놓아요.
 * 받는 것(props): value(감시 결과), context(시장·업종 자료 준비 여부). 돌려주는 것: 비교 화면.
 */
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

/**
 * 시간순 표본 외(OOS, 기준을 만든 기간 밖에서 다시 확인하는) 검증 결과를 보여줘요.
 * 모의고사로 정한 기준을 실제 시험에 그대로 적용해 보는 것과 같아요.
 * 받는 것(props): value(OOS 검증 결과). 돌려주는 것: 개발/검증 구간 비교 화면.
 */
function OosValidationPanel({ value }: { value: StrategyAnalytics['oosValidation'] }) {
  if (!value) return null
  const featureLabels: Record<string, string> = {
    vwapDistanceRate: 'VWAP 이격률', dayHighDistanceRate: '당일 고가 거리',
  tradeStrength: '체결강도', turnoverRatio: '5분 거래대금 배수',
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

/**
 * OOS 비교 한 줄을 그려요. 개발 구간에서 정한 기준으로 나눈 상위·하위 그룹의 검증 구간 성과를 보여줘요.
 * 받는 것(props): label(비교 이름), comparison(그룹별 성과). 돌려주는 것: 표 한 줄.
 */
function OosComparisonRow({ label, comparison }: { label: string; comparison: OosComparison }) {
  return <article>
    <span><b>{label}</b><small>개발 구간에서 정한 기준을 검증 구간에 고정 적용</small></span>
    <span><small>개발 상위</small><b>{comparison.developmentHigh.sampleSize}건 · {pct(comparison.developmentHigh.averageCloseReturn)}</b></span>
    <span><small>검증 상위</small><b>{comparison.validationHigh.sampleSize}건 · {pct(comparison.validationHigh.averageCloseReturn)}</b></span>
    <span><small>검증 하위</small><b>{comparison.validationLow.sampleSize}건 · {pct(comparison.validationLow.averageCloseReturn)}</b></span>
    <span><small>검증 목표 도달</small><b>{pct(comparison.validationHigh.targetHitRate)}</b></span>
  </article>
}

/** 후보 제외·관찰 사유 코드를 한국어로 바꿔요. 모르는 코드는 그대로 보여줘요. */
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

/** 점검 결과 코드(PASS/FAIL)를 한국어로 바꿔요. 그 밖의 값은 미검증으로 봐요. */
function checkLabel(status?: string) {
  return status === 'PASS' ? '통과' : status === 'FAIL' ? '미통과' : '미검증'
}

/**
 * 오버나잇 백테스트 결과 패널이에요. 성적표처럼 승률·평균 수익과 종목별 결과(최대 20줄)를 보여줘요.
 * 받는 것(props): data(백테스트 결과). 돌려주는 것: 결과 화면.
 * 수신 시각이 없는 과거 기록이 섞일 수 있어 탐색용 재생 결과로만 안내해요.
 */
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

/**
 * 추천 알고리즘 비교표예요. 같은 조건에서 여러 알고리즘의 성적을 나란히 놓은 비교표예요.
 * 받는 것(props): summaries(알고리즘별 요약). 돌려주는 것: 비교 화면, 없으면 null.
 */
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

/**
 * 매도 전략 비교표예요. 같은 추천에 여러 청산 규칙을 적용한 결과를 나란히 보여줘요.
 * 받는 것(props): summaries(전략별 요약). 돌려주는 것: 비교 화면, 없으면 null.
 */
function StrategySummaryPanel({ summaries }: { summaries: OvernightExitStrategySummary[] }) {
  if (summaries.length === 0) return null
  return (
    <section className="strategyPanel">
      <div className="strategyTitle">
        <span>
          <small>매도 전략 비교</small>
          <b>보유 연장 백테스트</b>
        </span>
        <small>목표/손절 동시 도달은 보수적으로 손절 처리 · 비용 미설정 시 순수익은 미검증으로 표시</small>
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

/**
 * 백테스트 정합성 검사 결과 패널이에요. 점검표처럼 통과·경고·실패 건수와 문제 목록(최대 8건)을 보여줘요.
 * 받는 것(props): integrity(검사 결과). 돌려주는 것: 점검 화면.
 */
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

/**
 * 추천 종목 하나를 카드로 보여주는 컴포넌트예요. 진열대 가격표 카드처럼 순위·점수·근거·성과를 한 장에 담아요.
 * 받는 것(props): item(추천), performance(다음날 성과), decision(매도/보유 판단), advanced(고급 모드).
 * 돌려주는 것: 추천 카드 화면.
 * ⚠️ 확인 필요: 탐지 시각(detectedAt)은 timeZone 없이 표시해서 브라우저 시간대를 따라요. 다른 곳은 Asia/Seoul로 고정해요.
 */
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

/** JSON 배열 문자열을 문자열 배열로 풀어요. 비었거나 깨졌으면 빈 배열을 돌려줘요. */
function parseMissingFeatures(value: string): string[] {
  try {
    const parsed = JSON.parse(value || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

/**
 * 초보자용 한 줄 요약이에요. 기회·위험 점수를 쉬운 말(우선 검토/관찰/신중 검토)로 바꿔 보여줘요.
 * 받는 것(props): item(추천), decision(매도/보유 판단). 돌려주는 것: 요약 화면.
 * 여기 기준값(60/45/40/65)은 화면 안내용이며 수익 확률이 아니에요.
 */
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

/**
 * 매도/보유 판단 패널이에요. 받는 것(props): decision(판단 결과).
 * 돌려주는 것: 판단이 없거나 데이터 대기 중이면 대기 문구, 있으면 현재가·수익률·VWAP 이격 등.
 */
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

/**
 * 다음 거래일 성과 관측 패널이에요. 받는 것(props): performance(성과 관측).
 * 돌려주는 것: 관측 상태, 시가·최고·최저·종가 수익률, 가상 체결 수익과 누락 구간 목록.
 * 계산 버전이 overnight-observation-v2가 아니면 예전 방식(완결성 미검증)으로 보고 일부 항목을 숨겨요.
 */
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
      {!legacy && <span><small>가상 진입가</small><b>{performance.virtualEntryPrice == null ? '--' : `${money(performance.virtualEntryPrice)}원`}</b></span>}
      {!legacy && <span><small>가상 체결 총수익</small><b>{pct(performance.grossReturnRate ?? null)}</b></span>}
      {!legacy && <span><small>비용 반영 순수익</small><b>{performance.netReturnRate == null ? '미검증' : pct(performance.netReturnRate)}</b></span>}
      {!legacy && <span><small>비용 상태</small><b>{performance.costStatus ?? 'NOT_EVALUATED'}</b></span>}
    </div>
    {missing.length > 0 && <details className="observationGaps"><summary>누락·중복 구간 {missing.length}개</summary>
      <ul>{missing.map(at => <li key={at}>{new Date(at).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</li>)}</ul>
    </details>}
    </>
  )
}

/** 근거 목록 한 열을 그려요. 받는 것(props): title(제목), rows(이름·값 목록). 돌려주는 것: 목록 화면. */
function FactorColumn({ title, rows }: { title: string; rows: { label: string; value: string }[] }) {
  return <div><h3>{title}</h3>{rows.map(row => <span key={row.label}><small>{row.label}</small><b>{row.value}</b></span>)}</div>
}
