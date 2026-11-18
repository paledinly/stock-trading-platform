/**
 * AI 후보 평가 탭(AiCandidatePanel)이에요(ADR-014, docs/AI_CANDIDATE_EVALUATION.md).
 * 프로그램이 정한 후보 40종목 카드를 ChatGPT에 붙여넣고, 받은 등급 JSON을 검사·저장해요.
 * 장 마감 뒤 같은 명단의 평균·무작위·단순 규칙 바구니와 비교한 채점과 사전 등록 판정(처음 40채점일)을 보여 줘요.
 * 실주문·추천·구독에는 영향이 없어요. AiPlanPage의 탭으로 들어가요.
 */
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { pct } from './AiPlanPage'

// 아래 타입은 /api/v1/ai-candidates 응답 모양이에요. 퍼센트 값은 2가 2%예요.
type PoolStock = { rank: number; stockCode: string; stockName: string }
type Pool = { targetDate: string; poolId: string; dataDate: string; stored: boolean; message: string | null; stocks: PoolStock[] }
type Card = { schemaVersion: string; pool: Pool; prompt: string }
/** 평가 한 판의 요약이에요. counted는 집계 판(장 시작 전 첫 판)인지예요. */
type EvalSummary = { id: number; targetDate: string; revision: number; poolId: string; analyzedAt: string | null; submittedAt: string; onTime: boolean; counted: boolean; modelName: string; modelVersion: string | null; promptVersion: string; marketOutlook: string; gradeA: number; gradeB: number; gradeC: number; gradeAvoid: number; warnings: string[] }
type GradeView = { poolRank: number; stockCode: string; stockName: string; grade: string; catalyst: number; flow: number; trend: number; overheat: number; disclosureRisk: number; riskFlags: string | null; confidence: number; entryType: string | null; stopPct: number | null; targetPct: number | null; timeExit: string | null; usedExternalInfo: boolean; status: string | null; openClosePct: number | null; netOpenClosePct: number | null }
type Detail = { evaluation: EvalSummary; rawJson: string; grades: GradeView[]; revisions: EvalSummary[] }
type SubmitResult = { saved: boolean; errors: string[]; warnings: string[]; evaluation: EvalSummary | null }
type Gate = { verdict: string; days: number; wins: number; meanExcess: number | null }
type Day = { date: string; n: number; avoidCount: number; pool: number | null; aiA: number | null; aiAvoid: number | null; random: number | null; rule: number | null }
export type CandidateSummary = { gateDays: number; gateWinDays: number; selection: Gate; profitVerdict: string; selectionMeanNet: number | null; filter: Gate; meanVsRandom: number | null; meanVsRule: number | null; meanVsTopConf: number | null; meanPoolExAvoid: number | null; recordedDays: number; days: Day[] }

const BASE = '/api/v1/ai-candidates'
const VERDICT: Record<string, string> = { PENDING: '기록 중', PASS: '통과', FAIL: '미달' }
const GRADE: Record<string, string> = { A: 'A', B: 'B', C: 'C', AVOID: '회피' }
const ENTRY: Record<string, string> = { OPEN: '시가', BREAKOUT_15M: '15분 고가 돌파', VWAP_RECLAIM: 'VWAP 회복', NONE: '-' }
const OUTLOOK: Record<string, string> = { BULLISH: '강세', NEUTRAL: '중립', BEARISH: '약세' }

async function get<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? 'AI 후보 평가를 불러오지 못했습니다.')
  }
  return response.json()
}

async function post(url: string, body: string): Promise<SubmitResult> {
  const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body })
  if (response.status !== 201 && response.status !== 200 && response.status !== 422) throw new Error('서버 요청이 실패했습니다.')
  return response.json()
}

function tone(value?: number | null) {
  return value == null || value === 0 ? '' : value > 0 ? 'aiUp' : 'aiDown'
}

function verdictTone(verdict: string) {
  return verdict === 'PASS' ? 'aiUp' : verdict === 'FAIL' ? 'aiDown' : ''
}

function kstTime(value: string | null) {
  if (!value) return '시각 없음'
  return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

/** AI 후보 평가 탭이에요. 받는 것(props): 없음. */
export function AiCandidatePanel() {
  const client = useQueryClient()
  const [raw, setRaw] = useState('')
  const [result, setResult] = useState<SubmitResult | null>(null)
  const [showCard, setShowCard] = useState(false)
  const [copied, setCopied] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)
  const [showRaw, setShowRaw] = useState(false)
  const summary = useQuery({ queryKey: ['ai-candidates', 'summary'], queryFn: () => get<CandidateSummary>(`${BASE}/summary`) })
  const list = useQuery({ queryKey: ['ai-candidates', 'list'], queryFn: () => get<EvalSummary[]>(BASE) })
  const card = useQuery({ queryKey: ['ai-candidates', 'card'], queryFn: () => get<Card>(`${BASE}/card`), enabled: showCard, retry: false })
  const currentId = selected ?? list.data?.[0]?.id ?? null
  const detail = useQuery({ queryKey: ['ai-candidates', 'detail', currentId], queryFn: () => get<Detail>(`${BASE}/${currentId}`), enabled: currentId != null })

  const check = useMutation({ mutationFn: () => post(`${BASE}/check`, raw), onSuccess: setResult })
  const save = useMutation({
    mutationFn: () => post(BASE, raw),
    onSuccess: data => {
      setResult(data)
      if (data.saved && data.evaluation) {
        setSelected(data.evaluation.id)
        setRaw('')
        client.invalidateQueries({ queryKey: ['ai-candidates'] })
      }
    },
  })
  const evaluate = useMutation({
    mutationFn: async () => {
      const response = await fetch(`${BASE}/evaluate`, { method: 'POST' })
      if (!response.ok) throw new Error('채점을 실행하지 못했습니다.')
      return response.json() as Promise<number>
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ['ai-candidates'] }),
  })

  async function copyPrompt() {
    if (!card.data) return
    try {
      await navigator.clipboard.writeText(card.data.prompt)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  const s = summary.data
  return (
    <>
      <section className="aiGate" aria-label="후보 평가 판정">
        <article>
          <small>고르기 판정 (A등급 − 명단 평균, 처음 {s?.gateDays ?? 40}채점일)</small>
          <b className={verdictTone(s?.selection.verdict ?? '')}>{s ? VERDICT[s.selection.verdict] ?? s.selection.verdict : '--'}</b>
          <span>{s ? `${Math.min(s.selection.days, s.gateDays)} / ${s.gateDays}일 · 이긴 날 ${s.selection.wins}일 (필요 ${s.gateWinDays}일) · 평균 ${pct(s.selection.meanExcess)}` : '불러오는 중'}</span>
        </article>
        <article>
          <small>수익 판정 (고르기 통과 + A등급 비용 뺀 평균 &gt; 0)</small>
          <b className={verdictTone(s?.profitVerdict ?? '')}>{s ? VERDICT[s.profitVerdict] ?? s.profitVerdict : '--'}</b>
          <span>A등급 비용 뺀 시가→종가 {pct(s?.selectionMeanNet)}</span>
        </article>
        <article>
          <small>걸러내기 판정 (회피 − 명단 평균 &lt; 0)</small>
          <b className={verdictTone(s?.filter.verdict ?? '')}>{s ? VERDICT[s.filter.verdict] ?? s.filter.verdict : '--'}</b>
          <span>{s ? `${Math.min(s.filter.days, s.gateDays)} / ${s.gateDays}일 · 명단보다 나빴던 날 ${s.filter.wins}일 · 평균 ${pct(s.filter.meanExcess)}` : '불러오는 중'}</span>
        </article>
        <article>
          <small>함께 보는 값 (판정에 안 씀)</small>
          <b className={tone(s?.meanVsRule)}>{pct(s?.meanVsRule)}</b>
          <span>A − 단순 규칙 · A − 무작위 {pct(s?.meanVsRandom)} · A − 확신도 상위 {pct(s?.meanVsTopConf)} · 회피 뺀 명단 − 명단 {pct(s?.meanPoolExAvoid)}</span>
        </article>
      </section>

      <section className="aiInput" aria-label="후보 평가 입력">
        <div className="aiInputHead">
          <h2>오늘 후보 평가 입력</h2>
          <div>
            <button type="button" onClick={() => setShowCard(value => !value)}>{showCard ? '카드 닫기' : '후보 카드·프롬프트 보기'}</button>
            <button type="button" onClick={() => evaluate.mutate()} disabled={evaluate.isPending}>{evaluate.isPending ? '채점 중…' : '채점 지금 실행'}</button>
          </div>
        </div>
        {showCard && (
          <div className="aiPrompt">
            {card.isLoading && <p>카드를 만드는 중이에요.</p>}
            {card.isError && <p className="aiError">{(card.error as Error).message}</p>}
            {card.data && <>
              <p>평가 거래일 <b>{card.data.pool.targetDate}</b> · 명단 {card.data.pool.stocks.length}종목 · poolId {card.data.pool.poolId} · 데이터 기준일 {card.data.pool.dataDate}
                {card.data.pool.stored ? ' · 명단 고정됨. 장 시작 전에 저장한 첫 판만 집계돼요.' : ''}</p>
              {card.data.pool.message && <p className="aiError">{card.data.pool.message}</p>}
              <button type="button" onClick={copyPrompt}>{copied ? '복사했어요' : '프롬프트 복사'}</button>
              <pre>{card.data.prompt}</pre>
            </>}
          </div>
        )}
        <textarea aria-label="AI 후보 평가 JSON" value={raw} onChange={event => { setRaw(event.target.value); setResult(null) }}
          placeholder='{"schemaVersion":"ai-candidate-v1", ...}' rows={10} spellCheck={false} />
        <div className="aiActions">
          <button type="button" onClick={() => check.mutate()} disabled={!raw.trim() || check.isPending}>검사만 하기</button>
          <button type="button" className="primary" onClick={() => save.mutate()} disabled={!raw.trim() || save.isPending}>검사 후 저장</button>
        </div>
        {(check.isError || save.isError) && <p className="aiError">서버 요청이 실패했어요. 백엔드가 켜져 있는지 확인해 주세요.</p>}
        {result && (
          <div className="aiResult" role="status">
            {result.saved && result.evaluation && <p className="aiOk">저장했어요: {result.evaluation.targetDate} {result.evaluation.revision}판{result.evaluation.counted ? ' (집계 판)' : result.evaluation.onTime ? ' (첫 판이 아니라 일관성 확인용)' : ' (장 시작 뒤 입력이라 집계에서 빠져요)'}</p>}
            {!result.saved && result.errors.length === 0 && <p className="aiOk">검사를 통과했어요. 저장하려면 "검사 후 저장"을 눌러 주세요.</p>}
            {result.errors.length > 0 && <ul className="aiErrors">{result.errors.map(error => <li key={error}>{error}</li>)}</ul>}
            {result.warnings.length > 0 && <ul className="aiWarnings">{result.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>}
          </div>
        )}
      </section>

      <section className="aiHistory">
        <aside aria-label="후보 평가 기록">
          <h2>기록</h2>
          {list.data?.length === 0 && <p className="aiEmpty">아직 저장한 평가가 없어요.</p>}
          {list.data?.map(item => (
            <button type="button" key={item.id} className={item.id === currentId ? 'active' : ''} onClick={() => { setSelected(item.id); setShowRaw(false) }}>
              <b>{item.targetDate} · {item.revision}판</b>
              <span>A {item.gradeA} · 회피 {item.gradeAvoid} · {OUTLOOK[item.marketOutlook] ?? item.marketOutlook}{item.counted ? ' · 집계' : ''}</span>
            </button>
          ))}
        </aside>
        <div className="aiDetail">
          {!detail.data && <p className="aiEmpty">평가를 고르면 종목별 등급과 결과가 보여요.</p>}
          {detail.data && <EvalDetailView detail={detail.data} day={s?.days.find(d => d.date === detail.data.evaluation.targetDate)} showRaw={showRaw} onToggleRaw={() => setShowRaw(value => !value)} />}
        </div>
      </section>
    </>
  )
}

/** 평가 한 판의 종목별 등급·채점 결과와 그날 바구니 비교예요. */
function EvalDetailView({ detail, day, showRaw, onToggleRaw }: { detail: Detail; day?: Day; showRaw: boolean; onToggleRaw: () => void }) {
  const e = detail.evaluation
  return (
    <>
      <div className="aiDetailHead">
        <div>
          <h2>{e.targetDate} · {e.revision}판</h2>
          <span>A {e.gradeA} · B {e.gradeB} · C {e.gradeC} · 회피 {e.gradeAvoid} · {OUTLOOK[e.marketOutlook] ?? e.marketOutlook}</span>
          <span>분석 {kstTime(e.analyzedAt)} · 저장 {kstTime(e.submittedAt)} · {e.modelName}{e.modelVersion ? ` ${e.modelVersion}` : ''} · 프롬프트 {e.promptVersion}</span>
        </div>
        <em className={e.counted ? 'aiBadge ok' : 'aiBadge'}>{e.counted ? '집계 대상' : e.onTime ? '재실행 판' : '장 시작 뒤 입력'}</em>
      </div>
      {day && <p className="aiNote">그날 시가→종가: 명단 {pct(day.pool)} · A {pct(day.aiA)} · 회피 {pct(day.aiAvoid)} · 무작위 {pct(day.random)} · 단순 규칙 {pct(day.rule)}</p>}
      {e.warnings.length > 0 && <ul className="aiWarnings">{e.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>}
      <div className="aiTable" role="table" aria-label="종목별 등급">
        <div role="row" className="aiRow head">
          <span role="columnheader">종목</span><span role="columnheader">등급</span><span role="columnheader">재료·수급·흐름</span>
          <span role="columnheader">과열·공시위험</span><span role="columnheader">매수 방식</span><span role="columnheader">손절/목표</span>
          <span role="columnheader">확신도</span><span role="columnheader">시가→종가</span>
        </div>
        {detail.grades.map(g => (
          <div role="row" className="aiRow" key={g.stockCode}>
            <span role="cell" title={g.riskFlags ?? ''}>
              <em className={g.grade === 'AVOID' ? 'aiBadge warn' : g.grade === 'A' ? 'aiBadge top' : 'aiBadge'}>{g.poolRank}</em>
              <b>{g.stockName}</b><small>{g.stockCode}{g.riskFlags ? ` · ${g.riskFlags}` : ''}{g.usedExternalInfo ? ' · 외부정보' : ''}{g.status && g.status !== 'COMPLETE' ? ` · ${g.status}` : ''}</small>
            </span>
            <span role="cell"><b>{GRADE[g.grade] ?? g.grade}</b></span>
            <span role="cell">{g.catalyst}·{g.flow}·{g.trend}</span>
            <span role="cell">{g.overheat}·{g.disclosureRisk}</span>
            <span role="cell">{ENTRY[g.entryType ?? 'NONE'] ?? g.entryType}</span>
            <span role="cell">{g.stopPct != null ? `${g.stopPct}% / ${g.targetPct != null ? `${g.targetPct}%` : '-'}` : '-'}</span>
            <span role="cell">{g.confidence}</span>
            <span role="cell" className={tone(g.openClosePct)}>{g.status ? pct(g.openClosePct) : '채점 전'}</span>
          </div>
        ))}
      </div>
      <p className="aiNote">채점: 그날 시가 매수 → 종가 매도. 비교 대상은 같은 명단 40종목이에요. AI가 적은 매수 방식·손절·목표는 기록만 하고 v1 채점에는 쓰지 않아요.</p>
      {detail.revisions.length > 1 && (
        <div className="aiRevisions">
          <small>같은 날 판 {detail.revisions.length}개 · 집계는 장 시작 전 첫 판</small>
        </div>
      )}
      <button type="button" className="aiRawToggle" onClick={onToggleRaw}>{showRaw ? '원본 JSON 닫기' : '원본 JSON 보기'}</button>
      {showRaw && <pre className="aiRaw">{detail.rawJson}</pre>}
    </>
  )
}
