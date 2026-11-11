/**
 * AI 장전 계획 화면(AiPlanPage)이에요(ADR-012 1단계: 기록과 채점).
 * ChatGPT용 프롬프트를 복사하고, ChatGPT가 만든 JSON을 붙여넣어 검사·저장하고, 장 마감 뒤 채점 결과와 1단계 통과 기준을 봐요.
 * 기록·채점 탭의 채점은 실제 일봉으로만 해요. AI가 적은 가격·조건은 원본에만 남아요.
 * '실시간 감시' 탭(AiWatchPanel)은 2단계: 장중에 계획 종목을 감시하고 조건이 맞으면 가상으로 사고팔아 기록해요(실주문 없음).
 * 화면 이동과 메뉴는 AppShell.tsx가 맡아요.
 */
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import './aiPlan.css'
import { AiWatchPanel } from './AiWatchPanel'

// 아래 타입은 /api/v1/ai-plans 응답 모양이에요. 퍼센트 값은 2가 2%예요.
/** 날짜별 계획 한 판의 요약이에요. scored는 성과 집계에 쓰는 판(장 시작 전 마지막 판)인지예요. */
export type PlanSummary = { id: number; targetDate: string; revision: number; schemaVersion: string; analysisId: string | null; analysisMode: string; analyzedAt: string | null; submittedAt: string; onTime: boolean; scored: boolean; tradeDecision: string; marketOutlook: string; marketRegime: string | null; riskLevel: string | null; modelName: string; modelVersion: string | null; promptVersion: string; pickCount: number; avoidCount: number; warnings: string[] }
/** 장 마감 뒤 실제 일봉으로 잰 결과예요. */
type Outcome = { tradeDate: string; status: string; gapPct: number | null; openClosePct: number | null; highPct: number | null; lowPct: number | null; netOpenClosePct: number | null; universeOpenClosePct: number | null; excessOpenClosePct: number | null; universeCount: number | null }
/** 계획에 나온 종목 하나예요. kind는 PICK(매수 후보) 또는 AVOID(매수 금지)예요. */
type Pick = { id: number; kind: string; stockCode: string; stockName: string; priority: number | null; reason: string | null; topPriority: boolean; confidence: string | null; outcome: Outcome | null }
type Detail = { plan: PlanSummary; rawJson: string; picks: Pick[]; revisions: PlanSummary[] }
type SubmitResult = { saved: boolean; errors: string[]; warnings: string[]; plan: PlanSummary | null }
type Day = { date: string; picks: number; pickOpenClose: number | null; pickNet: number | null; universeOpenClose: number | null; excess: number | null }
/** 1단계 통과 기준 진행 상황과 누적 통계예요. */
export type Summary = { scoredDays: number; gateDays: number; gateWinDaysRequired: number; gateVerdict: string; gateMeanExcess: number | null; gateWinDays: number; meanPickOpenClose: number | null; meanPickNet: number | null; meanUniverseOpenClose: number | null; meanExcess: number | null; winDays: number; pickCount: number; meanAvoidExcess: number | null; avoidCount: number; noTradeDays: number; noTradeUniverseOpenClose: number | null; tradeUniverseOpenClose: number | null; meanTopExcess: number | null; topCount: number; days: Day[] }
type Template = { schemaVersion: string; targetDate: string; prompt: string; example: string }

/** GET 요청을 보내고 JSON을 돌려줘요. 실패하면 오류를 던져요. */
async function get<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) throw new Error('AI 장전 계획을 불러오지 못했습니다.')
  return response.json()
}

/** JSON 원문을 그대로 POST해요. 검사 실패(422)도 본문(오류 목록)을 돌려줘요. */
async function post(url: string, body: string): Promise<SubmitResult> {
  const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
  if (response.status !== 201 && response.status !== 200 && response.status !== 422) throw new Error('서버 요청이 실패했습니다.')
  return response.json()
}

/** 숫자를 "+1.23%"로 바꿔요. 값이 없으면 "--"예요. */
export function pct(value?: number | null) {
  return value == null ? '--' : `${value >= 0 ? '+' : ''}${Number(value).toFixed(2)}%`
}

/** 값의 부호에 맞는 색 class예요(한국식: 오르면 빨강, 내리면 파랑). */
function tone(value?: number | null) {
  return value == null || value === 0 ? '' : value > 0 ? 'aiUp' : 'aiDown'
}

const OUTLOOK: Record<string, string> = { BULLISH: '강세', NEUTRAL: '중립', BEARISH: '약세' }
const DECISION: Record<string, string> = { TRADE: '매매', WATCH_ONLY: '감시만', NO_TRADE: '매매 안 함', DATA_INSUFFICIENT: '자료 부족' }
const RISK: Record<string, string> = { LOW: '위험 낮음', MEDIUM: '위험 보통', HIGH: '위험 높음', EXTREME: '위험 매우 높음' }
const VERDICT: Record<string, string> = { PENDING: '기록 중', PASS: '통과', FAIL: '미달' }
const STATUS: Record<string, string> = { COMPLETE: '완료', NO_DATA: '일봉 없음', SUSPECT: '확인 필요' }

/** 시각을 한국 시간 "11-11 07:50"으로 보여 줘요. */
function kstTime(value: string | null) {
  if (!value) return '시각 없음'
  return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

/** AI 장전 계획 화면이에요. 받는 것(props): 없음. */
export function AiPlanPage() {
  const client = useQueryClient()
  const [raw, setRaw] = useState('')
  const [result, setResult] = useState<SubmitResult | null>(null)
  const [selected, setSelected] = useState<number | null>(null)
  const [showPrompt, setShowPrompt] = useState(false)
  const [copied, setCopied] = useState(false)
  const [showRaw, setShowRaw] = useState(false)
  const [tab, setTab] = useState<'plan' | 'watch'>('plan')
  const summary = useQuery({ queryKey: ['ai-plans', 'summary'], queryFn: () => get<Summary>('/api/v1/ai-plans/summary') })
  const plans = useQuery({ queryKey: ['ai-plans', 'list'], queryFn: () => get<PlanSummary[]>('/api/v1/ai-plans') })
  const template = useQuery({ queryKey: ['ai-plans', 'template'], queryFn: () => get<Template>('/api/v1/ai-plans/template'), enabled: showPrompt })
  const currentId = selected ?? plans.data?.[0]?.id ?? null
  const detail = useQuery({ queryKey: ['ai-plans', 'detail', currentId], queryFn: () => get<Detail>(`/api/v1/ai-plans/${currentId}`), enabled: currentId != null })

  const check = useMutation({ mutationFn: () => post('/api/v1/ai-plans/check', raw), onSuccess: setResult })
  const save = useMutation({
    mutationFn: () => post('/api/v1/ai-plans', raw),
    onSuccess: data => {
      setResult(data)
      if (data.saved && data.plan) {
        setSelected(data.plan.id)
        setRaw('')
        client.invalidateQueries({ queryKey: ['ai-plans'] })
      }
    },
  })
  const evaluate = useMutation({
    mutationFn: async () => {
      const response = await fetch('/api/v1/ai-plans/evaluate', { method: 'POST' })
      if (!response.ok) throw new Error('채점을 실행하지 못했습니다.')
      return response.json() as Promise<number>
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ['ai-plans'] }),
  })

  /** 프롬프트를 클립보드에 복사해요. 브라우저가 막으면 화면에 펼친 글을 직접 복사하면 돼요. */
  async function copyPrompt() {
    if (!template.data) return
    try {
      await navigator.clipboard.writeText(template.data.prompt)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  const s = summary.data
  return (
    <div className="aiPlanPage">
      <header>
        <h1>AI 장전 계획</h1>
        <span>ChatGPT 장전 분석을 장 시작 전에 기록하고, 장 마감 뒤 실제 시장과 비교해요. 장중에는 계획 종목을 감시하며 가상으로만 사고팔아요.</span>
        <nav className="aiTabs" role="tablist" aria-label="AI 장전 계획 보기">
          <button type="button" role="tab" aria-selected={tab === 'plan'} className={tab === 'plan' ? 'active' : ''} onClick={() => setTab('plan')}>기록·채점</button>
          <button type="button" role="tab" aria-selected={tab === 'watch'} className={tab === 'watch' ? 'active' : ''} onClick={() => setTab('watch')}>실시간 감시</button>
        </nav>
      </header>
      {tab === 'watch' ? <main><AiWatchPanel /></main> : <main>
        <section className="aiGate" aria-label="1단계 통과 기준">
          <article>
            <small>1단계 판정 (처음 {s?.gateDays ?? 20}개 채점일)</small>
            <b className={s?.gateVerdict === 'PASS' ? 'aiUp' : s?.gateVerdict === 'FAIL' ? 'aiDown' : ''}>{s ? VERDICT[s.gateVerdict] ?? s.gateVerdict : '--'}</b>
            <span>{s ? `${Math.min(s.scoredDays, s.gateDays)} / ${s.gateDays}일 · 시장보다 나은 날 ${s.gateWinDays}일 (필요 ${s.gateWinDaysRequired}일)` : '불러오는 중'}</span>
          </article>
          <article>
            <small>기준 기간 평균 초과 (AI 종목 − 시장 평균)</small>
            <b className={tone(s?.gateMeanExcess)}>{pct(s?.gateMeanExcess)}</b>
            <span>0보다 커야 통과예요</span>
          </article>
          <article>
            <small>AI 종목 시가→종가 (전체 {s?.scoredDays ?? 0}일, {s?.pickCount ?? 0}종목)</small>
            <b className={tone(s?.meanPickOpenClose)}>{pct(s?.meanPickOpenClose)}</b>
            <span>비용 뺀 값 {pct(s?.meanPickNet)} · 시장 평균 {pct(s?.meanUniverseOpenClose)}</span>
          </article>
          <article>
            <small>최우선 감시 종목의 초과 ({s?.topCount ?? 0}건)</small>
            <b className={tone(s?.meanTopExcess)}>{pct(s?.meanTopExcess)}</b>
            <span>매수 금지 종목 초과 {pct(s?.meanAvoidExcess)} ({s?.avoidCount ?? 0}건) · 매매 안 함 {s?.noTradeDays ?? 0}일 시장 {pct(s?.noTradeUniverseOpenClose)} · 종목 고른 날 {pct(s?.tradeUniverseOpenClose)}</span>
          </article>
        </section>

        <section className="aiInput" aria-label="계획 입력">
          <div className="aiInputHead">
            <h2>오늘 계획 입력</h2>
            <div>
              <button type="button" onClick={() => setShowPrompt(value => !value)}>{showPrompt ? '프롬프트 닫기' : 'ChatGPT 프롬프트 보기'}</button>
              <button type="button" onClick={() => evaluate.mutate()} disabled={evaluate.isPending}>{evaluate.isPending ? '채점 중…' : '채점 지금 실행'}</button>
            </div>
          </div>
          {showPrompt && (
            <div className="aiPrompt">
              {template.isLoading && <p>프롬프트를 불러오는 중이에요.</p>}
              {template.data && <>
                <p>분석 대상 거래일 <b>{template.data.targetDate}</b>. 아래 글을 ChatGPT에 붙여넣고, 나온 JSON을 아래 칸에 넣어 주세요. 장 시작(09:00) 전에 저장해야 집계돼요.</p>
                <button type="button" onClick={copyPrompt}>{copied ? '복사했어요' : '프롬프트 복사'}</button>
                <pre>{template.data.prompt}</pre>
              </>}
            </div>
          )}
          <textarea aria-label="AI 장전 분석 JSON" value={raw} onChange={event => { setRaw(event.target.value); setResult(null) }}
            placeholder='{"schemaVersion":"ai-premarket-v3", ...}' rows={10} spellCheck={false} />
          <div className="aiActions">
            <button type="button" onClick={() => check.mutate()} disabled={!raw.trim() || check.isPending}>검사만 하기</button>
            <button type="button" className="primary" onClick={() => save.mutate()} disabled={!raw.trim() || save.isPending}>검사 후 저장</button>
          </div>
          {(check.isError || save.isError) && <p className="aiError">서버 요청이 실패했어요. 백엔드가 켜져 있는지 확인해 주세요.</p>}
          {result && (
            <div className="aiResult" role="status">
              {result.saved && result.plan && <p className="aiOk">저장했어요: {result.plan.targetDate} {result.plan.revision}판{result.plan.onTime ? '' : ' (장 시작 뒤 입력이라 집계에서 빠져요)'}</p>}
              {!result.saved && result.errors.length === 0 && <p className="aiOk">검사를 통과했어요. 저장하려면 "검사 후 저장"을 눌러 주세요.</p>}
              {result.errors.length > 0 && <ul className="aiErrors">{result.errors.map(error => <li key={error}>{error}</li>)}</ul>}
              {result.warnings.length > 0 && <ul className="aiWarnings">{result.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>}
            </div>
          )}
        </section>

        <section className="aiHistory">
          <aside aria-label="계획 기록">
            <h2>기록</h2>
            {plans.data?.length === 0 && <p className="aiEmpty">아직 저장한 계획이 없어요.</p>}
            {plans.data?.map(plan => (
              <button type="button" key={plan.id} className={plan.id === currentId ? 'active' : ''} onClick={() => { setSelected(plan.id); setShowRaw(false) }}>
                <b>{plan.targetDate}</b>
                <span>{DECISION[plan.tradeDecision] ?? plan.tradeDecision}{plan.pickCount > 0 ? ` ${plan.pickCount}종목` : ''} · {OUTLOOK[plan.marketOutlook] ?? plan.marketOutlook} · {plan.revision}판</span>
              </button>
            ))}
          </aside>
          <div className="aiDetail">
            {!detail.data && <p className="aiEmpty">계획을 고르면 종목별 결과가 보여요.</p>}
            {detail.data && <PlanDetailView detail={detail.data} showRaw={showRaw} onToggleRaw={() => setShowRaw(value => !value)}
              onSelect={id => { setSelected(id); setShowRaw(false) }} />}
          </div>
        </section>
      </main>}
    </div>
  )
}

/** 계획 하나의 종목별 채점 결과, 같은 날 다른 판, 원본 JSON을 보여 줘요. */
function PlanDetailView({ detail, showRaw, onToggleRaw, onSelect }: { detail: Detail; showRaw: boolean; onToggleRaw: () => void; onSelect: (id: number) => void }) {
  const plan = detail.plan
  return (
    <>
      <div className="aiDetailHead">
        <div>
          <h2>{plan.targetDate} · {plan.revision}판</h2>
          <span>{DECISION[plan.tradeDecision] ?? plan.tradeDecision} · {OUTLOOK[plan.marketOutlook] ?? plan.marketOutlook}{plan.riskLevel ? ` · ${RISK[plan.riskLevel] ?? plan.riskLevel}` : ''}{plan.marketRegime ? ` · ${plan.marketRegime}` : ''}</span>
          <span>분석 {kstTime(plan.analyzedAt)} · 저장 {kstTime(plan.submittedAt)} · {plan.modelName}{plan.modelVersion ? ` ${plan.modelVersion}` : ''} · 프롬프트 {plan.promptVersion}{plan.analysisMode === 'INTRADAY' ? ' · 장중 분석' : ''}</span>
        </div>
        <em className={plan.scored ? 'aiBadge ok' : 'aiBadge'}>{plan.scored ? '집계 대상' : plan.onTime ? '이전 판' : '장 시작 뒤 입력'}</em>
      </div>
      {plan.warnings.length > 0 && <ul className="aiWarnings">{plan.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>}
      <div className="aiTable" role="table" aria-label="종목별 결과">
        <div role="row" className="aiRow head">
          <span role="columnheader">종목</span><span role="columnheader">갭</span><span role="columnheader">시가→종가</span>
          <span role="columnheader">고가</span><span role="columnheader">저가</span><span role="columnheader">비용 뺀</span>
          <span role="columnheader">시장 평균</span><span role="columnheader">초과</span>
        </div>
        {detail.picks.map(pick => {
          const o = pick.outcome
          return (
            <div role="row" className="aiRow" key={pick.id}>
              <span role="cell" title={pick.reason ?? ''}>
                <em className={pick.kind === 'AVOID' ? 'aiBadge warn' : pick.topPriority ? 'aiBadge top' : 'aiBadge'}>{pick.kind === 'AVOID' ? '매수 금지' : `${pick.priority ?? '-'}순위${pick.topPriority ? ' ★' : ''}`}</em>
                <b>{pick.stockName}</b><small>{pick.stockCode}{o && o.status !== 'COMPLETE' ? ` · ${STATUS[o.status] ?? o.status}` : ''}{!o ? ' · 채점 전' : ''}</small>
              </span>
              <span role="cell" className={tone(o?.gapPct)}>{pct(o?.gapPct)}</span>
              <span role="cell" className={tone(o?.openClosePct)}>{pct(o?.openClosePct)}</span>
              <span role="cell" className={tone(o?.highPct)}>{pct(o?.highPct)}</span>
              <span role="cell" className={tone(o?.lowPct)}>{pct(o?.lowPct)}</span>
              <span role="cell" className={tone(o?.netOpenClosePct)}>{pick.kind === 'AVOID' ? '--' : pct(o?.netOpenClosePct)}</span>
              <span role="cell" className={tone(o?.universeOpenClosePct)}>{pct(o?.universeOpenClosePct)}</span>
              <span role="cell" className={tone(o?.excessOpenClosePct)}><b>{pct(o?.excessOpenClosePct)}</b></span>
            </div>
          )
        })}
      </div>
      <p className="aiNote">채점: 그날 시가 매수 → 종가 매도. 시장 평균은 직전 20거래일 평균 거래대금 10억 원 이상 종목(ETF·ETN 제외)의 같은 날 시가→종가 평균이에요. 고가·저가는 시가 대비예요.</p>
      {detail.revisions.length > 1 && (
        <div className="aiRevisions">
          <small>같은 날 다른 판</small>
          {detail.revisions.map(revision => (
            <button type="button" key={revision.id} className={revision.id === plan.id ? 'active' : ''} onClick={() => onSelect(revision.id)}>
              {revision.revision}판 · {kstTime(revision.submittedAt)}{revision.scored ? ' · 집계' : revision.onTime ? '' : ' · 장중'}
            </button>
          ))}
        </div>
      )}
      <button type="button" className="aiRawToggle" onClick={onToggleRaw}>{showRaw ? '원본 JSON 닫기' : '원본 JSON 보기'}</button>
      {showRaw && <pre className="aiRaw">{detail.rawJson}</pre>}
    </>
  )
}
