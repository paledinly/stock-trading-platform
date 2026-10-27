/**
 * '오늘' 화면의 자동 작업 시간표 판정 규칙이에요. 화면(TodayPage)과 분리해서 시각·자료만으로 상태를 정하므로 테스트하기 쉬워요.
 * 시각은 모두 한국시간(KST)이에요. 예약 시각은 백엔드 @Scheduled 설정과 같게 적었어요(바뀌면 여기도 고쳐야 해요).
 * 상태: done(정상) · warn(확인 필요) · problem(문제) · running(진행 중) · waiting(예정) · unknown(기록 없음) · idle(휴장일 등 해당 없음).
 */

export type StepState = 'done' | 'warn' | 'problem' | 'running' | 'waiting' | 'unknown' | 'idle'
export type Step = { id: string; time: string; title: string; state: StepState; detail: string }

/** 한국시간 기준 날짜(YYYY-MM-DD), 요일, 시:분:초, 자정부터 지난 분이에요. */
export type Clock = { date: string; weekday: string; time: string; minutes: number }

type Counted = { rows: number; errors: number; lastAt: string | null }
export type Collection = {
  date: string; tradingDay: boolean; previousTradingDay: string | null; checkedAt: string
  run: { runId: number; strategyVersion: string; completedAt: string | null } | null
  paperEntries: Record<string, number>
  limitUp: { sampleLabel: string; rows: number; errors: number; rankingRows: number; sources: Record<string, number>; firstReceivedAt: string | null }[]
  candidateContext: Counted
  closingBar: { stocks: number; withVolume: number }
  // statuses는 정밀(PRECISION, ADR-010 표본) 후보만, broadStatuses는 Broad 후보(원래 결측이 많아 참고용)예요.
  observation: { statuses: Record<string, number>; lastEvaluatedAt: string | null; backfilledBars: number; broadStatuses?: Record<string, number> }
  dart: Counted
  adjustedDaily?: Counted
  /** 07:00~09:00·14:20~14:50에 저장된 일봉 수(08:10 후보 일봉 준비, 14:25·14:35 정밀 보완의 흔적). 예전 Backend에는 없어요. */
  morningDaily?: DailyCandles
  precisionDaily?: DailyCandles
  // 16:05 지수 일봉(그날 일봉을 16:00 뒤 저장한 수)과 18:00 분봉 재보완·관측 재평가 흔적이에요. 예전 백엔드에는 없어요.
  indexDaily?: Counted
  lateObservation?: { refilledBars: number; lastEvaluatedAt: string | null }
  /** 그날 정규장 개장·마감("HH:mm"). 수능일처럼 시간이 바뀐 날은 시간표를 그만큼 옮겨 보여 줘요. 예전 백엔드에는 없어요. */
  sessionOpen?: string
  sessionClose?: string
}
type DailyCandles = { rows: number; stocks: number; lastAt: string | null }
export type Health = {
  tradeDate: string; verdict: string; reasons: string[]; runId: number | null; runCompletedAt: string | null
  paperEntryStatuses: Record<string, number> | null; observationStatuses: Record<string, number> | null
  // V39부터: 판정에 쓰는 정밀 후보 관측, 참고용 Broad 관측, 기록 단계(15:45 1차 PRELIMINARY · 18:15 최종 FINAL). 이전 행은 null이에요.
  precisionObservationStatuses?: Record<string, number> | null; broadObservationStatuses?: Record<string, number> | null
  stage?: 'PRELIMINARY' | 'FINAL' | null
  indicators?: HealthIndicators | null
}

/** 일별 완결성의 참고 지표예요(판정에 쓰지 않아요). V41 이전 행은 없어요. */
export type HealthIndicators = {
  precisionReplacements?: number | null; dailyDataFlagged?: number | null
  coverageGaps?: number | null; coverageStillMissing?: number | null; notes?: string[]
}

const INDICATOR_NOTE_LABEL: Record<string, string> = {
  REPLACEMENTS_NONE: '정밀 교체 0건', DAILY_DATA_FLAGGED: '일봉 결함 후보 있음', COVERAGE_GAP_UNFILLED: '재연결 빈칸 남음',
}

/** 참고 지표를 한 줄 문구로 만들어요. 주의할 점이 있으면 앞에 ⚠와 이름을 붙여요. 없으면 '-'예요. */
export function indicatorLine(indicators?: HealthIndicators | null) {
  if (!indicators) return '-'
  const value = (n?: number | null) => (n == null ? '?' : String(n))
  const parts = [`교체 ${value(indicators.precisionReplacements)}`, `일봉 결함 ${value(indicators.dailyDataFlagged)}`,
    `재연결 빈칸 ${value(indicators.coverageGaps)}(남음 ${value(indicators.coverageStillMissing)})`]
  const notes = (indicators.notes ?? []).map(note => INDICATOR_NOTE_LABEL[note] ?? note)
  return `${notes.length ? `⚠ ${notes.join(', ')} · ` : ''}${parts.join(' · ')}`
}
/** Broad 후보 관측 건수를 참고용 꼬리 문구로 만들어요. 없으면 빈 글자예요. */
function broadLine(values?: Record<string, number>) {
  const total = Object.values(values ?? {}).reduce((sum, count) => sum + count, 0)
  return total ? ` (Broad 참고: 완료 ${values?.COMPLETED ?? 0}/${total})` : ''
}

export type TodayInputs = {
  clock: Clock
  realtime?: {
    realtime: {
      connected: boolean; connectedAt: string | null; lastTickAt: string | null; receivedTicks: number
      parseErrors: number; processingErrors: number; processingDropped: number
    }
  }
  subscriptions?: { limit: number; remaining: number; precisionState?: string | null; precisionCapacity?: number | null }
  wide?: { completedRuns: number; failedRuns: number; lastCompletedAt: string | null; lastError: string | null }
  calendar?: { lastFetchedAt: string | null; lastDate: string | null }
  collection?: Collection
  collectionUnavailable?: boolean
  /** 첫 응답을 기다리는 중이면 true예요. 자료가 없어도 '못 불러옴' 대신 '불러오는 중'으로 보여 줘요. */
  loading?: boolean
  health?: Health[]
}

const ZONE = 'Asia/Seoul'

/** 브라우저 시간대와 상관없이 한국시간 날짜·요일·시각을 만들어요. 받는 것: 지금 Date. */
export function kstClock(now: Date): Clock {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false, weekday: 'short',
  }).formatToParts(now).map(part => [part.type, part.value]))
  const hour = Number(parts.hour) % 24
  const weekdays: Record<string, string> = { Mon: '월', Tue: '화', Wed: '수', Thu: '목', Fri: '금', Sat: '토', Sun: '일' }
  return {
    date: `${parts.year}-${parts.month}-${parts.day}`,
    weekday: weekdays[parts.weekday] ?? parts.weekday,
    time: `${String(hour).padStart(2, '0')}:${parts.minute}:${parts.second}`,
    minutes: hour * 60 + Number(parts.minute),
  }
}

/** "HH:mm" 글자를 자정부터 지난 분으로 바꿔요. */
function at(value: string) {
  const [hour, minute] = value.split(':').map(Number)
  return hour * 60 + minute
}

/** "HH:mm" 시각을 분만큼 옮긴 "HH:mm"이에요. 수능일처럼 장 시간이 바뀐 날 예약 시각을 옮길 때 써요. */
export function shifted(value: string, minutes: number) {
  if (!minutes) return value
  const total = at(value) + minutes
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

/** 시각 문자열의 한국시간 날짜(YYYY-MM-DD)예요. */
function kstDate(value?: string | null) {
  return value ? kstClock(new Date(value)).date : null
}

/** 시각 문자열을 한국시간 "HH:mm:ss"로 보여 줘요. 값이 없으면 '-'예요. */
export function hms(value?: string | null) {
  return value ? kstClock(new Date(value)).time : '-'
}

function sum(values?: Record<string, number> | null) {
  return Object.values(values ?? {}).reduce((total, count) => total + count, 0)
}

/**
 * 하루 자동 작업 목록과 각 상태를 만들어요.
 * 받는 것: 한국시간 시계와 각 상태 API 응답(없으면 undefined). 돌려주는 것: 시각 순서의 작업 목록.
 */
export function buildTimeline(input: TodayInputs): Step[] {
  const { clock, collection } = input
  const now = clock.minutes
  const nonTrading = collection ? !collection.tradingDay : false
  const pending = input.collectionUnavailable ? 'Backend 재시작 후 표시' : '불러오는 중…'
  const failed = input.loading ? '불러오는 중…' : '상태를 불러오지 못했어요.'
  // 예약 시각 전이면 예정, 유예(grace분)가 지나도 자료가 없으면 문제로 봐요.
  const due = (time: string, grace = 3) => now >= at(time) + grace
  const before = (time: string) => now < at(time)
  const steps: Step[] = []
  // 장 시간이 바뀐 날(수능일 등)은 장에 묶인 작업 시각을 옮겨요. s: 마감 기준, o: 개장 기준. 08:10·DART·수정주가는 그대로예요.
  const closeShift = collection?.sessionClose ? at(collection.sessionClose) - at('15:30') : 0
  const openShift = collection?.sessionOpen ? at(collection.sessionOpen) - at('09:00') : 0
  const s = (time: string) => shifted(time, closeShift)
  const o = (time: string) => shifted(time, openShift)
  // 휴장일이면 DART(매일 실행)를 빼고 '해당 없음'이고, 자료를 못 읽었어도 예약 시각 전이면 '예정'으로 보여요.
  const add = (id: string, time: string, title: string, state: StepState, detail: string) => {
    const idle = nonTrading && id !== 'dart'
    steps.push({ id, time, title, state: idle ? 'idle' : state === 'unknown' && before(time) ? 'waiting' : state,
      detail: idle ? '휴장일이라 실행하지 않아요.' : detail })
  }

  // 07:40 거래일 달력 갱신(Backend 기동 때도 갱신해요).
  const calendarDay = kstDate(input.calendar?.lastFetchedAt)
  add('calendar', '07:40', '거래일 달력 갱신 (KIS)',
    calendarDay === clock.date ? 'done' : before('07:40') ? 'waiting' : input.calendar ? 'warn' : 'unknown',
    input.calendar ? `마지막 갱신 ${calendarDay ?? '-'} ${hms(input.calendar.lastFetchedAt)} · ${input.calendar.lastDate ?? '-'}까지 확보` : failed)

  // 08:10 후보 일봉 준비: 실행 기록 테이블이 없어서, 그 시간대(07:00~09:00)에 새로 저장된 일봉 수로 실행 여부를 봐요.
  // 준비는 빠진 일봉만 저장하므로 보통 전 거래일 일봉이 종목마다 하나 이상 남아요.
  const morning = collection?.morningDaily
  const manualHint = '마감 추천 > 도구 > "일봉 데이터 준비"로 실행하세요.'
  add('daily-prepare', '08:10', '후보 일봉 준비',
    !morning ? 'unknown' : morning.rows > 0 ? 'done' : before('08:10') ? 'waiting' : due('08:10', 5) ? 'warn' : 'running',
    !collection ? pending
      : !morning ? `실행 기록을 읽을 수 없어요(Backend 재시작 후 표시). 08:10에 Backend가 꺼져 있었다면 ${manualHint}`
        : morning.rows > 0 ? `${morning.stocks}종목 일봉 ${morning.rows}개 저장 · 마지막 ${hms(morning.lastAt)}`
          : `08:10 실행 흔적(새 일봉 저장)이 없어요. 그 시각 Backend가 꺼져 있었다면 ${manualHint} 이미 최신이라 저장할 게 없었을 수도 있어요.`)

  // 08:59 실시간 연결.
  const live = input.realtime?.realtime
  const connectedToday = kstDate(live?.connectedAt) === clock.date
  add('realtime', o('08:59'), '실시간 체결 연결',
    !live ? 'unknown' : live.connected ? 'done' : before(o('08:59')) ? 'waiting' : now > at(s('15:32')) ? (connectedToday ? 'done' : 'problem') : 'problem',
    live ? `${live.connected ? '연결됨' : '연결 안 됨'} · 연결 ${hms(live.connectedAt)} · 마지막 체결 ${hms(live.lastTickAt)} · 틱 ${live.receivedTicks.toLocaleString('ko-KR')}` : failed)

  // 09:00~15:20 시장 전체 스캔(5분, 14:00부터 2분 간격).
  const wide = input.wide
  const scanState: StepState = !wide ? 'unknown' : before(o('09:00')) ? 'waiting'
    : wide.failedRuns > 0 ? 'warn'
      : now < at(s('15:20')) ? (wide.completedRuns > 0 ? 'running' : due(o('09:00'), 6) ? 'problem' : 'running')
        : wide.completedRuns > 0 ? 'done' : 'problem'
  add('scan', o('09:00'), `시장 전체 스캔 (${o('09:00')}~${s('15:20')})`, scanState,
    wide ? `완료 ${wide.completedRuns} · 실패 ${wide.failedRuns} · 마지막 완료 ${hms(wide.lastCompletedAt)}${wide.lastError ? ` · 오류 ${wide.lastError}` : ''} (재시작 후 누적)` : failed)

  // 14:25·14:35 정밀 후보 일봉 보완: 저장된 게 없으면 이미 최신이었을 수 있어 '확인 필요'로 두지 않아요.
  const precisionDaily = collection?.precisionDaily
  add('precision-daily', s('14:25'), `정밀 후보 일봉 보완 (${s('14:25')} · ${s('14:35')})`,
    !precisionDaily ? 'unknown' : precisionDaily.rows > 0 ? 'done' : before(s('14:25')) ? 'waiting' : due(s('14:35'), 5) ? 'unknown' : 'running',
    !collection ? pending : !precisionDaily ? 'Backend 재시작 후 표시'
      : precisionDaily.rows > 0 ? `${precisionDaily.stocks}종목 일봉 ${precisionDaily.rows}개 저장 · 마지막 ${hms(precisionDaily.lastAt)}`
        : '새로 저장된 일봉이 없어요(이미 최신이었거나 실행되지 않음).')

  // 14:50 정밀 구독 고정.
  const subs = input.subscriptions
  add('freeze', s('14:50'), '정밀 구독 고정',
    !subs ? 'unknown' : before(s('14:50')) ? 'waiting' : subs.precisionState === 'FROZEN' ? 'done' : 'problem',
    subs ? `정밀 상태 ${subs.precisionState ?? '-'} · 구독 ${subs.limit - subs.remaining}/${subs.limit}` : failed)

  // 15:15 공식 평가(FORWARD run).
  const run = collection?.run
  add('evaluate', s('15:15'), '공식 마감 평가', !collection ? 'unknown' : run ? 'done' : before(s('15:15')) ? 'waiting' : due(s('15:15'), 3) ? 'problem' : 'running',
    !collection ? pending : run ? `run #${run.runId} · ${run.strategyVersion} · 완료 ${hms(run.completedAt)}` : '아직 공식 run이 없어요.')

  // 15:16:15 · 15:18:30 상한가 감시.
  const limitUp = collection?.limitUp ?? []
  add('limit-up', s('15:16'), `상한가 감시 (${s('15:16')} · ${s('15:18')})`,
    !collection ? 'unknown' : before(s('15:16')) ? 'waiting' : limitUp.some(sample => sample.errors > 0) ? 'warn'
      : limitUp.length >= 2 ? 'done' : due(s('15:18'), 2) ? 'warn' : 'running',
    !collection ? pending : limitUp.length ? limitUp.map(sample => `${sample.sampleLabel} ${sample.rows}종목(${Object.entries(sample.sources).map(([key, count]) => `${key} ${count}`).join(', ')})${sample.errors ? ` 오류 ${sample.errors}` : ''}`).join(' / ')
      : '아직 표본이 없어요. +20% 이상 종목이 없었으면 0건이 정상이에요.')

  // 15:16:45 · 15:19 후보 맥락(시장경고·VI 등).
  const context = collection?.candidateContext
  add('context', s('15:16'), `후보 맥락 기록 (${s('15:16')}:45 · ${s('15:19')})`,
    !collection ? 'unknown' : before(s('15:16')) ? 'waiting' : context && context.rows > 0 ? (context.errors > 0 ? 'warn' : 'done') : due(s('15:19'), 2) && run ? 'problem' : 'running',
    !collection ? pending : `${context?.rows ?? 0}행 · 오류 ${context?.errors ?? 0} · 마지막 ${hms(context?.lastAt)}`)

  // 15:20 가상 진입 마감.
  const entries = collection?.paperEntries ?? {}
  const entered = entries.ENTERED ?? 0
  add('paper', s('15:20'), `가상 진입 (${s('15:20')} 마감)`,
    !collection ? 'unknown' : before(s('15:15')) ? 'waiting' : sum(entries) === 0 ? (due(s('15:20'), 2) && run ? 'problem' : 'running') : entered > 0 ? 'done' : 'warn',
    !collection ? pending : sum(entries) ? `진입 ${entered} / 후보 ${sum(entries)} · ${Object.entries(entries).map(([key, count]) => `${key} ${count}`).join(', ')}` : '아직 가상 진입 기록이 없어요.')

  // 15:31 종가 단일가 체결이 15:25 봉에 반영됐는지.
  const bar = collection?.closingBar
  add('closing-print', s('15:31'), `종가 단일가 체결 (${s('15:25')} 봉)`,
    !collection ? 'unknown' : before(s('15:31')) ? 'waiting' : bar && bar.withVolume > 0 ? 'done' : due(s('15:31'), 2) ? 'warn' : 'running',
    !collection ? pending : `${s('15:25')} 봉 ${bar?.stocks ?? 0}종목 중 거래량 있음 ${bar?.withVolume ?? 0}`)

  // 15:40 전일 후보 관측(직전 분봉 보완 포함).
  const observation = collection?.observation
  const observed = sum(observation?.statuses)
  const missing = (observation?.statuses.DATA_MISSING ?? 0) + (observation?.statuses.DATA_INCOMPLETE ?? 0)
  add('observation', s('15:40'), '전일 후보 관측 + 분봉 보완',
    !collection ? 'unknown' : before(s('15:40')) ? 'waiting' : observed === 0 ? (due(s('15:40'), 5) ? 'problem' : 'running') : missing > 0 ? 'warn' : 'done',
    !collection ? pending : observed ? `정밀 ${Object.entries(observation!.statuses).map(([key, count]) => `${key} ${count}`).join(' · ')}${broadLine(observation!.broadStatuses)} · 보완 봉 ${observation!.backfilledBars} · 마지막 ${hms(observation!.lastEvaluatedAt)}`
      : `아직 관측 결과가 없어요 (전 거래일 ${collection.previousTradingDay ?? '-'} 후보 대상).`)

  // 15:45 일별 완결성 기록.
  const today = input.health?.find(row => row.tradeDate === clock.date)
  add('health', s('15:45'), '일별 완결성 1차 기록',
    !input.health ? 'unknown' : !today ? (before(s('15:45')) ? 'waiting' : due(s('15:45'), 5) ? 'problem' : 'running')
      : today.verdict === 'COMPLETE' ? 'done' : today.verdict === 'DEGRADED' ? 'warn' : 'problem',
    today ? `${today.verdict}${today.reasons.length ? ` · ${today.reasons.join(', ')}` : ''}${today.stage === 'FINAL' ? ' (최종 기록으로 갱신됨)' : ''}` : '아직 오늘 기록이 없어요.')

  // 16:05 지수 일봉(KOSPI·KOSDAQ 최근 20일 갱신). 그날 일봉 2개가 저장되면 정상이에요.
  const indexDaily = collection?.indexDaily
  add('index-daily', s('16:05'), '지수 일봉 갱신 (KOSPI·KOSDAQ)',
    !collection || !indexDaily ? 'unknown' : indexDaily.rows >= 2 ? 'done' : before(s('16:05')) ? 'waiting' : due(s('16:05'), 10) ? 'warn' : 'running',
    !collection ? pending : !indexDaily ? '백엔드 재시작 후 표시' : `오늘 일봉 ${indexDaily.rows}/2 · 마지막 ${hms(indexDaily.lastAt)}`)

  // 18:00 분봉 재보완 + 관측 재평가(늦게 채워지는 15:30 종가 단일가 분봉 반영).
  const late = collection?.lateObservation
  add('late-observation', s('18:00'), '분봉 재보완 + 관측 재평가',
    !collection || !late ? 'unknown' : late.lastEvaluatedAt ? 'done' : before(s('18:00')) ? 'waiting' : due(s('18:00'), 10) ? 'warn' : 'running',
    !collection ? pending : !late ? '백엔드 재시작 후 표시' : late.lastEvaluatedAt ? `다시 채운 5분봉 ${late.refilledBars} · 재평가 ${hms(late.lastEvaluatedAt)}` : '아직 재평가 기록이 없어요.')

  // 18:10 DART 공시(다음 날 07:40에 보충). 휴장일에도 18:10에 돌아요.
  const dart = collection?.dart
  add('dart', '18:10', 'DART 공시 수집',
    !collection ? 'unknown' : dart && dart.rows > 0 ? 'done' : before('18:10') ? 'waiting' : due('18:10', 5) ? 'warn' : 'running',
    !collection ? pending : `오늘 접수 공시 ${dart?.rows ?? 0}건 · 마지막 수집 ${hms(dart?.lastAt)}`)

  // 18:15 일별 완결성 최종 기록(18:00 재보완 반영).
  add('health-final', s('18:15'), '일별 완결성 최종 기록',
    !input.health ? 'unknown' : today?.stage === 'FINAL'
      ? (today.verdict === 'COMPLETE' ? 'done' : today.verdict === 'DEGRADED' ? 'warn' : 'problem')
      : before(s('18:15')) ? 'waiting' : due(s('18:15'), 5) ? 'warn' : 'running',
    today?.stage === 'FINAL' ? `${today.verdict}${today.reasons.length ? ` · ${today.reasons.join(', ')}` : ''} · 참고: ${indicatorLine(today.indicators)}` : '아직 최종 기록이 없어요.')

  // 18:30 수정주가 일봉(연구).
  const adjusted = collection?.adjustedDaily
  add('adjusted', '18:30', '수정주가 일봉 (연구)',
    !collection ? 'unknown' : adjusted && adjusted.rows > 0 ? 'done' : before('18:30') ? 'waiting' : due('18:30', 30) ? 'warn' : 'running',
    !collection ? pending : `오늘 확인 ${adjusted?.rows ?? 0}종목 · 마지막 ${hms(adjusted?.lastAt)}`)

  // 장 시간이 바뀐 날은 옮긴 시각과 고정 시각(DART 등)이 섞이므로 시각 순으로 다시 줄 세워요(같은 시각은 원래 순서).
  return steps.sort((a, b) => at(a.time) - at(b.time))
}
