/**
 * AI 장전 계획 2단계 '실시간 감시' 탭이에요(AiPlanPage 안에서 보여요).
 * 그날 계획 종목의 감시 상태(규칙 P: 프로그램 고정 규칙, 규칙 A: AI 조건), 지표, 조건 충족 여부, 가상 매수·청산, 상태 이력, 2단계 판정을 보여 줘요.
 * 장중에는 5초마다 새로 읽고, 브라우저 알림을 켜 두면 매수 후보·무효·가상 진입·손절 때 알려 줘요. 실제 주문은 없어요.
 */
import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { pct } from './AiPlanPage'

// 아래 타입은 /api/v1/ai-plans/watch 응답 모양이에요. 퍼센트 값은 2가 2%예요.
type Exit = { price: number; at: string; reason: string; netPct: number }
type Position = { stockCode: string; rule: string; shadow: boolean; signalAt: string; entrySourceAt: string; entryTickPrice: number; entryPrice: number; quantity: number; capacityLimited: boolean; stopPrice: number; spreadSource: string; e1: Exit | null; e2: Exit | null; maxPrice: number; minPrice: number; trackingGap: boolean }
type Stock = { pickId: number; stockCode: string; stockName: string; priority: number | null; topPriority: boolean; subscribed: boolean; stateP: string; stateA: string; indicators: Record<string, string | number>; checksP: Record<string, string>; checksA: Record<string, string>; unsupportedA: string[]; positions: Position[] }
type Session = { tradeDecision: string; startedAt: string; endedAt: string | null; pickCount: number; subscribedCount: number; unsubscribedCodes: string | null; positionAmount: number; ruleVersion: string }
type Event = { id: number; stockCode: string; rule: string; fromState: string | null; toState: string; reasons: string | null; evaluatedAt: string }
export type WatchView = { date: string; live: boolean; session: Session | null; stocks: Stock[]; positions: Position[]; events: Event[]; ruleVersion: string; positionAmount: number }
type Group = { rule: string; exit: string; scope: string; trades: number; days: number; meanNetPct: number | null; winRate: number | null; paired: number; meanVsOpenPct: number | null }
export type WatchSummary = { verdict: string; minTrades: number; minDays: number; gate: Group; groups: Group[] }

const STATE: Record<string, string> = { NOT_SUBSCRIBED: '구독 못 함', WATCH: '감시', READY: '준비', BUY_CANDIDATE: '매수 후보', CAUTION: '주의', INVALIDATED: '무효', EXPIRED: '종료', UNSUPPORTED: '지원 안 됨' }
const EXIT: Record<string, string> = { STOP: '손절', TAKE: '익절', CLOSE: '마감' }
const CHECK: Record<string, string> = { BREAKOUT_5M: '5분봉 돌파', ABOVE_VWAP: 'VWAP 위', VOLUME_5M: '거래량 2배', NOT_CHASING_OPEN: '시가 +8% 이하', NOT_CHASING_5M: '직전 5분 +2% 미만', NOT_CHASING_BREAKOUT: '돌파선 +2% 이하', STOP_DISTANCE: '손절 거리', MARKET: '시장', TIME: '진입 시간', DATA: '자료 품질', STOP_AVAILABLE: '손절 기준' }
const MARK: Record<string, string> = { PASS: '✓', FAIL: '✗', UNAVAILABLE: '?' }
const VERDICT: Record<string, string> = { PENDING: '기록 중', PASS: '통과', FAIL: '미달' }
const DECISION: Record<string, string> = { TRADE: '매매', WATCH_ONLY: '감시만', NO_TRADE: '매매 안 함', DATA_INSUFFICIENT: '자료 부족' }
const ALERT_STATES = new Set(['READY', 'BUY_CANDIDATE', 'INVALIDATED'])

async function get<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) throw new Error('실시간 감시 정보를 불러오지 못했습니다.')
  return response.json()
}

function tone(value?: number | null) {
  return value == null || value === 0 ? '' : value > 0 ? 'aiUp' : 'aiDown'
}

function stateClass(state: string) {
  return state === 'BUY_CANDIDATE' ? 'aiState buy' : state === 'READY' ? 'aiState ready' : state === 'CAUTION' ? 'aiState caution'
    : state === 'INVALIDATED' ? 'aiState invalid' : 'aiState'
}

function hhmm(value: string | null | undefined) {
  if (!value) return '--'
  return new Date(value).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}

function num(value: string | number | undefined) {
  return value == null ? '--' : Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

/** 브라우저 알림을 보내요. 권한이 없거나 브라우저가 막으면 조용히 넘어가요. */
function notify(title: string, body: string) {
  try {
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') new Notification(title, { body })
  } catch { /* 알림을 못 보내도 화면은 그대로예요. */ }
}

/** 실시간 감시 탭이에요. 받는 것(props): 없음. */
export function AiWatchPanel() {
  const [date, setDate] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const [permission, setPermission] = useState(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
  const watch = useQuery({
    queryKey: ['ai-watch', date],
    queryFn: () => get<WatchView>(`/api/v1/ai-plans/watch${date ? `?date=${date}` : ''}`),
    refetchInterval: query => (query.state.data?.live ? 5000 : false),
  })
  const summary = useQuery({ queryKey: ['ai-watch', 'summary'], queryFn: () => get<WatchSummary>('/api/v1/ai-plans/watch/summary') })
  const seen = useRef<Map<string, string> | null>(null)

  // 장중이면 이전 화면과 비교해 상태가 바뀐 종목, 새 가상 진입·청산을 브라우저 알림으로 알려요(처음 읽은 화면은 알리지 않아요).
  useEffect(() => {
    const data = watch.data
    if (!data) return
    const next = new Map<string, string>()
    for (const stock of data.stocks) {
      next.set(`${stock.stockCode}|P`, stock.stateP)
      next.set(`${stock.stockCode}|A`, stock.stateA)
      for (const p of stock.positions) next.set(`${stock.stockCode}|${p.rule}|pos`, `${p.e1?.reason ?? ''}/${p.e2?.reason ?? ''}`)
    }
    const previous = seen.current
    if (previous && data.live) {
      for (const stock of data.stocks) {
        for (const rule of ['P', 'A']) {
          const state = rule === 'P' ? stock.stateP : stock.stateA
          if (previous.get(`${stock.stockCode}|${rule}`) !== state && ALERT_STATES.has(state))
            notify(`${stock.stockName} ${STATE[state]}`, `규칙 ${rule} · ${num(stock.indicators.price)}원`)
        }
        for (const p of stock.positions) {
          const key = `${stock.stockCode}|${p.rule}|pos`
          if (!previous.has(key)) notify(`${stock.stockName} 가상 매수`, `규칙 ${p.rule} · ${num(p.entryPrice)}원${p.shadow ? ' (그림자)' : ''}`)
          else if (previous.get(key) !== next.get(key) && (p.e1?.reason === 'STOP' || p.e2?.reason === 'STOP'))
            notify(`${stock.stockName} 가상 손절`, `규칙 ${p.rule}`)
        }
      }
    }
    seen.current = next
  }, [watch.data])

  async function enableAlerts() {
    if (typeof Notification === 'undefined') return
    setPermission(await Notification.requestPermission())
  }

  const data = watch.data
  const s = summary.data
  const group = (rule: string, exit: string) => s?.groups.find(g => g.rule === rule && g.exit === exit && g.scope === 'ALL')
  return (
    <div className="aiWatch">
      <section className="aiGate" aria-label="2단계 판정">
        <article>
          <small>2단계 판정 (규칙 P · 구조 손절+마감)</small>
          <b className={s?.verdict === 'PASS' ? 'aiUp' : s?.verdict === 'FAIL' ? 'aiDown' : ''}>{s ? VERDICT[s.verdict] ?? s.verdict : '--'}</b>
          <span>{s ? `${s.gate.trades} / ${s.minTrades}건 · ${s.gate.days} / ${s.minDays}일` : '불러오는 중'}</span>
        </article>
        <article>
          <small>규칙 P 거래당 비용 뺀 평균</small>
          <b className={tone(s?.gate.meanNetPct)}>{pct(s?.gate.meanNetPct)}</b>
          <span>시가 매수 대비 {pct(s?.gate.meanVsOpenPct)} ({s?.gate.paired ?? 0}건) · 둘 다 0보다 커야 통과</span>
        </article>
        <article>
          <small>규칙 P · +3%/−2% 청산</small>
          <b className={tone(group('P', 'E2')?.meanNetPct)}>{pct(group('P', 'E2')?.meanNetPct)}</b>
          <span>{group('P', 'E2')?.trades ?? 0}건 · 이긴 비율 {group('P', 'E2')?.winRate ?? '--'}%</span>
        </article>
        <article>
          <small>규칙 A(AI 조건) · 구조 손절+마감</small>
          <b className={tone(group('A', 'E1')?.meanNetPct)}>{pct(group('A', 'E1')?.meanNetPct)}</b>
          <span>{group('A', 'E1')?.trades ?? 0}건 · 시가 매수 대비 {pct(group('A', 'E1')?.meanVsOpenPct)}</span>
        </article>
      </section>

      <section className="aiDetail" aria-label="실시간 감시">
        <div className="aiDetailHead">
          <div>
            <h2>{data?.date ?? '오늘'} 실시간 감시 {data?.live && <em className="aiBadge ok">감시 중</em>}</h2>
            {data?.session ? (
              <span>
                계획 {DECISION[data.session.tradeDecision] ?? data.session.tradeDecision}
                {data.session.tradeDecision !== 'TRADE' ? ' (진입은 그림자 기록)' : ''} · 구독 {data.session.subscribedCount}/{data.session.pickCount}종목
                {data.session.unsubscribedCodes ? ` · 구독 못 함 ${data.session.unsubscribedCodes}` : ''} · 1회 {num(data.session.positionAmount)}원 · {data.session.ruleVersion}
              </span>
            ) : <span>이날 감시 기록이 없어요. 장 시작 전에 저장한 계획이 있으면 08:50에 감시를 시작해요.</span>}
          </div>
          <div className="aiWatchTools">
            <input type="date" aria-label="날짜" value={date} onChange={event => setDate(event.target.value)} />
            {permission !== 'granted' && permission !== 'unsupported' && <button type="button" onClick={enableAlerts}>브라우저 알림 켜기</button>}
            {permission === 'granted' && <em className="aiBadge ok">알림 켜짐</em>}
          </div>
        </div>
        {watch.isError && <p className="aiError">실시간 감시 정보를 불러오지 못했어요.</p>}
        <div className="aiTable" role="table" aria-label="감시 종목">
          <div role="row" className="aiWatchRow head">
            <span role="columnheader">종목</span><span role="columnheader">규칙 P</span><span role="columnheader">규칙 A</span>
            <span role="columnheader">현재가</span><span role="columnheader">VWAP</span><span role="columnheader">15분 고/저</span>
            <span role="columnheader">RVOL</span><span role="columnheader">가상 매매</span>
          </div>
          {data?.stocks.map(stock => {
            const x = stock.indicators
            return (
              <div key={stock.stockCode}>
                <button type="button" role="row" className="aiWatchRow" onClick={() => setOpen(open === stock.stockCode ? null : stock.stockCode)}>
                  <span role="cell"><em className={stock.topPriority ? 'aiBadge top' : 'aiBadge'}>{stock.priority ?? '-'}순위{stock.topPriority ? ' ★' : ''}</em><b>{stock.stockName}</b><small>{stock.stockCode}</small></span>
                  <span role="cell"><em className={stateClass(stock.stateP)}>{STATE[stock.stateP] ?? stock.stateP}</em></span>
                  <span role="cell"><em className={stateClass(stock.stateA)}>{STATE[stock.stateA] ?? stock.stateA}</em></span>
                  <span role="cell">{num(x.price)}</span>
                  <span role="cell">{num(x.vwap)}</span>
                  <span role="cell">{num(x.or15High)} / {num(x.or15Low)}</span>
                  <span role="cell">{x.rvol5m != null ? `${num(x.rvol5m)}배` : '--'}</span>
                  <span role="cell" className="aiPositions">
                    {stock.positions.length === 0 && '--'}
                    {stock.positions.map(p => <PositionLine key={p.rule} position={p} price={x.price} />)}
                  </span>
                </button>
                {open === stock.stockCode && <Conditions stock={stock} />}
              </div>
            )
          })}
        </div>
        <p className="aiNote">규칙 P: 15분 시가 범위 고가를 완료 5분봉 종가로 돌파, VWAP 위, 거래량 2배, 추격 금지, 손절 거리, 시장 필터를 모두 통과하면 매수 후보예요.
          가상 매수는 판정 뒤 호가를 한 번 조회하고 처음 들어온 체결 가격에 스프레드 절반을 더해요. 청산은 E1(구조 손절, 아니면 15:19)과 E2(+3%/−2%)를 함께 기록해요.</p>
      </section>

      <section className="aiDetail" aria-label="상태 이력">
        <h2>상태 이력</h2>
        {data?.events.length === 0 && <p className="aiEmpty">아직 상태 변화가 없어요.</p>}
        <ul className="aiEvents">
          {data?.events.slice(0, 50).map(event => (
            <li key={event.id}>
              <span>{hhmm(event.evaluatedAt)}</span>
              <b>{data.stocks.find(s => s.stockCode === event.stockCode)?.stockName ?? event.stockCode}</b>
              <span>규칙 {event.rule}</span>
              <span>{event.fromState ? STATE[event.fromState] ?? event.fromState : '시작'} → <em className={stateClass(event.toState)}>{STATE[event.toState] ?? event.toState}</em></span>
              {event.reasons && <small>{event.reasons}</small>}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

/** 가상 포지션 한 줄이에요. 청산 전이면 지금 가격 기준 평가손익(비용 빼기 전)을 보여 줘요. */
function PositionLine({ position: p, price }: { position: Position; price?: string | number }) {
  const exit = (label: string, e: Exit | null) => e
    ? <span className={tone(e.netPct)}>{label} {EXIT[e.reason] ?? e.reason} {pct(e.netPct)}</span>
    : <span className={tone(price == null ? null : (Number(price) / p.entryPrice - 1) * 100)}>{label} 보유 {price == null ? '' : pct((Number(price) / p.entryPrice - 1) * 100)}</span>
  return (
    <span className="aiPosition" title={`진입 ${hhmm(p.entrySourceAt)} · 체결 ${num(p.entryTickPrice)} · 손절 ${num(p.stopPrice)} · ${p.quantity.toLocaleString('ko-KR')}주${p.capacityLimited ? ' · 체결 규모 경고' : ''}${p.trackingGap ? ' · 추적 공백' : ''}`}>
      <b>{p.rule}{p.shadow ? ' 그림자' : ''} {num(p.entryPrice)}</b>{exit('E1', p.e1)}{exit('E2', p.e2)}
    </span>
  )
}

/** 종목을 누르면 펼쳐지는 조건 충족 표예요(✓ 충족, ✗ 미충족, ? 자료 없음). */
function Conditions({ stock }: { stock: Stock }) {
  const list = (checks: Record<string, string>) => Object.entries(checks).map(([name, check]) => (
    <li key={name} className={check === 'PASS' ? 'aiUp' : check === 'FAIL' ? 'aiDown' : ''}>{MARK[check] ?? check} {CHECK[name] ?? name}</li>
  ))
  return (
    <div className="aiConditions">
      <div><small>규칙 P</small><ul>{Object.keys(stock.checksP).length ? list(stock.checksP) : <li>아직 판단 전이에요.</li>}</ul></div>
      <div><small>규칙 A (AI 조건)</small>
        <ul>{stock.unsupportedA.length > 0 ? stock.unsupportedA.map(reason => <li key={reason}>지원 안 됨: {reason}</li>)
          : Object.keys(stock.checksA).length ? list(stock.checksA) : <li>아직 판단 전이에요.</li>}</ul>
      </div>
    </div>
  )
}
