/**
 * '오늘' 화면 시간표 판정 규칙(todayTimeline.ts) 테스트예요.
 * 브라우저 시간대와 상관없이 한국시간으로 계산되는지, 시각·자료에 따라 예정/진행/정상/문제가 맞게 정해지는지 확인해요.
 */
import { buildTimeline, kstClock, type Collection, type TodayInputs } from './todayTimeline'

/** 2026-10-02(금) 한국시간 hh:mm의 시계예요. */
const clockAt = (time: string) => kstClock(new Date(`2026-10-02T${time}:00+09:00`))

const collection = (overrides: Partial<Collection> = {}): Collection => ({
  date: '2026-10-02', tradingDay: true, previousTradingDay: '2026-10-01', checkedAt: '2026-10-02T07:00:00Z',
  run: null, paperEntries: {}, limitUp: [], candidateContext: { rows: 0, errors: 0, lastAt: null },
  closingBar: { stocks: 0, withVolume: 0 }, observation: { statuses: {}, lastEvaluatedAt: null, backfilledBars: 0 },
  dart: { rows: 0, errors: 0, lastAt: null }, adjustedDaily: { rows: 0, errors: 0, lastAt: null },
  morningDaily: { rows: 0, stocks: 0, lastAt: null }, precisionDaily: { rows: 0, stocks: 0, lastAt: null }, ...overrides,
})

const realtime: TodayInputs['realtime'] = { realtime: {
  connected: true, connectedAt: '2026-10-01T23:59:01Z', lastTickAt: '2026-10-02T01:00:00Z', receivedTicks: 1200,
  parseErrors: 0, processingErrors: 0, processingDropped: 0,
} }

const state = (steps: ReturnType<typeof buildTimeline>, id: string) => steps.find(step => step.id === id)?.state

// 한국시간 날짜·시각을 브라우저 시간대와 상관없이 만드는지 봐요.
test('builds the KST clock', () => {
  const clock = kstClock(new Date('2026-10-01T23:30:05Z'))
  expect(clock).toEqual({ date: '2026-10-02', weekday: '금', time: '08:30:05', minutes: 510 })
})

// 장중(10:00)에는 연결·스캔이 진행 중이고, 15:15 평가 같은 오후 작업은 예정이에요.
test('marks afternoon jobs as waiting during the session', () => {
  const steps = buildTimeline({
    clock: clockAt('10:00'), realtime, collection: collection(),
    wide: { completedRuns: 12, failedRuns: 0, lastCompletedAt: '2026-10-02T00:55:00Z', lastError: null },
    subscriptions: { limit: 41, remaining: 0, precisionState: 'ACTIVE' }, health: [],
  })
  expect(state(steps, 'realtime')).toBe('done')
  expect(state(steps, 'scan')).toBe('running')
  expect(state(steps, 'freeze')).toBe('waiting')
  expect(state(steps, 'evaluate')).toBe('waiting')
  expect(state(steps, 'observation')).toBe('waiting')
  expect(state(steps, 'dart')).toBe('waiting')
  expect(state(steps, 'daily-prepare')).toBe('warn')
})

// 08:10 일봉 준비는 그 시간대 저장 흔적이 있으면 정상, 예전 Backend(필드 없음)면 기록 없음이에요.
test('shows the 08:10 daily preparation from stored daily candles', () => {
  const ran = buildTimeline({ clock: clockAt('09:30'), collection: collection({
    morningDaily: { rows: 62, stocks: 40, lastAt: '2026-10-01T23:10:09Z' } }) })
  expect(state(ran, 'daily-prepare')).toBe('done')
  expect(ran.find(step => step.id === 'daily-prepare')?.detail).toContain('40종목 일봉 62개 저장 · 마지막 08:10:09')
  const old = buildTimeline({ clock: clockAt('09:30'), collection: collection({ morningDaily: undefined }) })
  expect(state(old, 'daily-prepare')).toBe('unknown')
})

// 장 마감 뒤(16:00) 수집이 다 됐으면 정상, 관측에 누락이 있으면 확인 필요, 완결성 DEGRADED는 확인 필요예요.
test('judges the closing pipeline after the close', () => {
  const steps = buildTimeline({
    clock: clockAt('16:00'), realtime,
    subscriptions: { limit: 41, remaining: 0, precisionState: 'FROZEN' },
    collection: collection({
      run: { runId: 70, strategyVersion: 'closing-recommend-v9_1-1515', completedAt: '2026-10-02T06:15:08Z' },
      paperEntries: { ENTERED: 30, NO_FRESH_TICK_BEFORE_DEADLINE: 11 },
      limitUp: [
        { sampleLabel: '15:16', rows: 2, errors: 0, rankingRows: 2, sources: { RANKING: 2 }, firstReceivedAt: null },
        { sampleLabel: '15:18', rows: 2, errors: 0, rankingRows: 2, sources: { RANKING: 2 }, firstReceivedAt: null },
      ],
      candidateContext: { rows: 82, errors: 0, lastAt: '2026-10-02T06:19:01Z' },
      closingBar: { stocks: 44, withVolume: 44 },
      observation: { statuses: { COMPLETED: 40, DATA_MISSING: 2 }, lastEvaluatedAt: '2026-10-02T06:40:03Z', backfilledBars: 600 },
    }),
    health: [{ tradeDate: '2026-10-02', verdict: 'DEGRADED', reasons: ['OBSERVATION_INCOMPLETE'], runId: 70,
      runCompletedAt: null, paperEntryStatuses: null, observationStatuses: null }],
  })
  expect(state(steps, 'freeze')).toBe('done')
  expect(state(steps, 'evaluate')).toBe('done')
  expect(state(steps, 'limit-up')).toBe('done')
  expect(state(steps, 'context')).toBe('done')
  expect(state(steps, 'paper')).toBe('done')
  expect(state(steps, 'closing-print')).toBe('done')
  expect(state(steps, 'observation')).toBe('warn')
  expect(state(steps, 'health')).toBe('warn')
  expect(steps.find(step => step.id === 'paper')?.detail).toContain('진입 30 / 후보 41')
})

// 예약 시각이 지났는데 자료가 없으면 문제로 보여요(15:15 평가, 14:50 고정).
test('flags missing official evaluation and freeze', () => {
  const steps = buildTimeline({
    clock: clockAt('15:25'), realtime, collection: collection(),
    subscriptions: { limit: 41, remaining: 0, precisionState: 'ACTIVE' }, health: [],
  })
  expect(state(steps, 'evaluate')).toBe('problem')
  expect(state(steps, 'freeze')).toBe('problem')
})

// 휴장일에는 DART를 빼고 모두 '해당 없음'이에요. 새 API가 없으면(재시작 전) 안내 문구를 보여요.
test('handles holidays and a backend without the collection API', () => {
  const holiday = buildTimeline({ clock: clockAt('16:00'), collection: collection({ tradingDay: false }) })
  expect(holiday.filter(step => step.id !== 'dart').every(step => step.state === 'idle')).toBe(true)
  expect(state(holiday, 'dart')).toBe('waiting')

  const old = buildTimeline({ clock: clockAt('16:00'), collectionUnavailable: true })
  expect(state(old, 'evaluate')).toBe('unknown')
  expect(old.find(step => step.id === 'evaluate')?.detail).toBe('Backend 재시작 후 표시')
})
