/**
 * '오늘'(운영 현황) 화면이에요. 하루 자동 작업 시간표(08:59 연결 → 15:15 평가 → 15:40 관찰 → 18:30 수정주가)를 한 줄로 보여 주고,
 * 각 작업이 정상인지 실시간 상태·수집 개수 API로 판정해요. 매일 DB를 직접 조회해 확인하던 내용을 화면 하나로 옮긴 거예요.
 * 판정은 보기용이며, 공식 일별 완결성(COMPLETE/DEGRADED)은 15:45 기록(daily-health)을 그대로 보여 줘요.
 * AppShell이 '오늘' 메뉴(#/today)에서 이 화면을 보여 줘요.
 */
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { buildTimeline, hms, kstClock, type Collection, type Health, type StepState, type TodayInputs } from './todayTimeline'
import './today.css'

/** 서버 JSON을 가져와요. 실패하면 상태 코드를 담은 오류를 던져요(404면 아직 재시작 전인 새 API예요). */
async function get<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) throw Object.assign(new Error(`HTTP ${response.status}`), { status: response.status })
  return response.json()
}

const STATE_LABEL: Record<StepState, string> = {
  done: '정상', warn: '확인 필요', problem: '문제', running: '진행 중', waiting: '예정', unknown: '기록 없음', idle: '해당 없음',
}
const VERDICT_LABEL: Record<string, string> = { COMPLETE: '완결', DEGRADED: '불완전', MISSING: '기록 없음' }
const STAGE_LABEL: Record<string, string> = { PRELIMINARY: '1차', FINAL: '최종' }
const REFRESH = 30_000

/** 상태별 개수 맵을 "완료 3 · 누락 1" 같은 글자로 줄여요. */
function statusLine(values?: Record<string, number> | null) {
  const entries = Object.entries(values ?? {})
  return entries.length ? entries.map(([key, count]) => `${key} ${count}`).join(' · ') : '-'
}

/** 전일 후보 관측 칸이에요. V39 이후 행은 판정에 쓰는 정밀 후보와 참고용 Broad를 나눠 보여 주고, 이전 행은 합계만 있어요. */
function observationCell(row: Health) {
  if (!row.precisionObservationStatuses) return statusLine(row.observationStatuses)
  const broad = Object.values(row.broadObservationStatuses ?? {}).reduce((sum, count) => sum + count, 0)
  return <>정밀 {statusLine(row.precisionObservationStatuses)}{broad ? <small> · Broad 참고 완료 {row.broadObservationStatuses?.COMPLETED ?? 0}/{broad}</small> : null}</>
}

/** '오늘' 화면 컴포넌트예요. 받는 것: 없음. 돌려주는 것: 요약 칸, 하루 시간표, 최근 일별 완결성 표. */
export function TodayPage() {
  // 화면의 "지금"을 30초마다 다시 계산해서 예정→진행 중 같은 상태가 저절로 바뀌게 해요.
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), REFRESH)
    return () => window.clearInterval(timer)
  }, [])
  const clock = kstClock(now)
  const options = { refetchInterval: REFRESH, retry: false }
  const realtime = useQuery({ queryKey: ['today', 'realtime'], queryFn: () => get<TodayInputs['realtime']>('/api/v1/market/realtime/status'), ...options })
  const subscriptions = useQuery({ queryKey: ['today', 'subscriptions'], queryFn: () => get<TodayInputs['subscriptions']>('/api/v1/market/subscriptions'), ...options })
  const wide = useQuery({ queryKey: ['today', 'wide'], queryFn: () => get<TodayInputs['wide']>('/api/v1/market-wide/status'), ...options })
  const calendar = useQuery({ queryKey: ['today', 'calendar'], queryFn: () => get<TodayInputs['calendar']>('/api/v1/closing-recommendations/trading-calendar'), ...options })
  const collection = useQuery({ queryKey: ['today', 'collection', clock.date], queryFn: () => get<Collection>(`/api/v1/closing-recommendations/daily-collection?date=${clock.date}`), ...options })
  const health = useQuery({ queryKey: ['today', 'health'], queryFn: () => get<Health[]>('/api/v1/closing-recommendations/daily-health'), ...options })
  const flow = useQuery({ queryKey: ['today', 'flow'], queryFn: () => get<{ enabled: boolean; failures: number; lastCompletedAt: string | null }>('/api/v1/closing-trajectory/flow/status'), ...options })

  const collectionMissing = (collection.error as { status?: number } | null)?.status === 404
  const steps = buildTimeline({
    clock,
    realtime: realtime.data,
    subscriptions: subscriptions.data,
    wide: wide.data,
    calendar: calendar.data,
    collection: collection.data,
    collectionUnavailable: collectionMissing,
    health: health.data,
    loading: [realtime, subscriptions, wide, calendar, collection, health].some(query => query.isLoading),
  })
  const todayHealth = health.data?.find(row => row.tradeDate === clock.date)
  const live = realtime.data?.realtime
  const used = subscriptions.data ? subscriptions.data.limit - subscriptions.data.remaining : null
  // 새 수집 API가 없을 때(재시작 전)는 오늘 실시간 연결이 있었는지로 거래일을 짐작해요.
  const tradingDay = collection.data ? collection.data.tradingDay
    : live?.connectedAt && kstClock(new Date(live.connectedAt)).date === clock.date ? true : null
  const loading = [realtime, subscriptions, wide, collection, health].some(query => query.isLoading)
  const problems = steps.filter(step => step.state === 'problem').length
  const warnings = steps.filter(step => step.state === 'warn').length

  return (
    <div className="todayPage">
      <header>
        <div>
          <h1>오늘</h1>
          <span>{clock.date} ({clock.weekday}) · {tradingDay === false ? '휴장일' : tradingDay ? '거래일' : '거래일 확인 중'} · 현재 {clock.time} KST</span>
        </div>
        <aside className={loading ? undefined : problems ? 'problem' : warnings ? 'warn' : 'done'}>
          <small>오늘 자동 작업</small>
          <b>{loading ? '불러오는 중' : problems ? `문제 ${problems}` : warnings ? `확인 ${warnings}` : '이상 없음'}</b>
          <span>{steps.filter(step => step.state === 'done').length}개 정상 · {steps.filter(step => step.state === 'waiting').length}개 예정</span>
        </aside>
      </header>
      <main>
        {collectionMissing && <p className="todayNotice">상한가 감시·후보 맥락·가상 진입·종가 봉·관측·DART 개수는 새 조회 API(daily-collection)가 필요합니다. Backend를 다시 시작하면 표시됩니다.</p>}
        <section className="todayStats" aria-label="실시간 요약">
          <article className={!live ? undefined : live.connected ? 'done' : 'problem'}>
            <small>실시간 연결</small><b>{live ? (live.connected ? '연결됨' : '끊김') : '-'}</b>
            <span>마지막 체결 {hms(live?.lastTickAt)} · 틱 {(live?.receivedTicks ?? 0).toLocaleString('ko-KR')}</span>
          </article>
          <article className={(live?.processingDropped ?? 0) > 0 || (live?.processingErrors ?? 0) > 0 ? 'warn' : 'done'}>
            <small>처리 오류 · 누락</small><b>{live ? `${live.processingErrors} · ${live.processingDropped}` : '-'}</b>
            <span>파싱 오류 {live?.parseErrors ?? '-'}</span>
          </article>
          <article>
            <small>실시간 구독</small><b>{used == null ? '-' : `${used} / ${subscriptions.data!.limit}`}</b>
            <span>정밀 {subscriptions.data?.precisionState === 'FROZEN' ? '고정됨' : subscriptions.data?.precisionState === 'ACTIVE' ? '교체 가능' : subscriptions.data?.precisionState ?? '-'}</span>
          </article>
          <article className={(wide.data?.failedRuns ?? 0) > 0 ? 'warn' : undefined}>
            <small>시장 스캔</small><b>{wide.data ? `${wide.data.completedRuns} / ${wide.data.failedRuns}` : '-'}</b>
            <span>완료 / 실패 · 마지막 {hms(wide.data?.lastCompletedAt)}</span>
          </article>
          <article className={flow.data && flow.data.failures > 0 ? 'warn' : undefined}>
            <small>연구 수급 수집</small><b>{flow.data ? (flow.data.enabled ? `실패 ${flow.data.failures}` : '꺼짐') : '-'}</b>
            <span>마지막 완료 {hms(flow.data?.lastCompletedAt)} · 재시작 후 누적</span>
          </article>
        </section>

        <section className="todayTimeline" aria-label="오늘 자동 작업 시간표">
          <h2>자동 작업 시간표</h2>
          <ol>
            {steps.map(step => (
              <li key={step.id} className={step.state}>
                <time>{step.time}</time>
                <span className="stepDot" aria-hidden="true" />
                <div>
                  <b>{step.title}</b>
                  <p>{step.detail}</p>
                </div>
                <em>{STATE_LABEL[step.state]}</em>
              </li>
            ))}
          </ol>
        </section>

        <section className="todayHealth" aria-label="최근 일별 완결성">
          <h2>최근 일별 완결성 <small>15:45 1차 · 18:15 최종 · 정밀 후보 기준 · {todayHealth ? `오늘 ${VERDICT_LABEL[todayHealth.verdict] ?? todayHealth.verdict}${todayHealth.stage ? ` (${STAGE_LABEL[todayHealth.stage]})` : ''}` : '오늘 기록 전'}</small></h2>
          {health.isLoading && <p>불러오는 중…</p>}
          {health.data && health.data.length === 0 && <p>기록이 없습니다.</p>}
          {health.data && health.data.length > 0 && (
            <table>
              <thead><tr><th>거래일</th><th>판정</th><th>사유</th><th>공식 run</th><th>가상 진입</th><th>전일 후보 관측</th></tr></thead>
              <tbody>
                {health.data.slice(0, 10).map(row => (
                  <tr key={row.tradeDate}>
                    <td>{row.tradeDate}</td>
                    <td><i className={`verdict ${row.verdict.toLowerCase()}`}>{VERDICT_LABEL[row.verdict] ?? row.verdict}</i>{row.stage ? <small> {STAGE_LABEL[row.stage]}</small> : null}</td>
                    <td>{row.reasons.join(', ') || '-'}</td>
                    <td>{row.runId ? `#${row.runId} · ${hms(row.runCompletedAt)}` : '-'}</td>
                    <td>{statusLine(row.paperEntryStatuses)}</td>
                    <td>{observationCell(row)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </main>
    </div>
  )
}
