/**
 * '오늘' 화면 시간표 판정 규칙(todayTimeline.ts) 테스트예요.
 * 브라우저 시간대와 상관없이 한국시간으로 계산되는지, 시각·자료에 따라 예정/진행/정상/문제가 맞게 정해지는지 확인해요.
 */
import { buildTimeline, indicatorLine, kstClock, type Collection, type TodayInputs } from './todayTimeline'

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

// Broad 후보의 결측은 참고로만 보여 주고, 정밀 후보가 모두 완료면 15:40 관측은 정상이에요.
test('ignores broad observation gaps for the 15:40 step', () => {
  const steps = buildTimeline({
    clock: clockAt('16:00'), realtime, subscriptions: { limit: 41, remaining: 0, precisionState: 'FROZEN' },
    collection: collection({ observation: { statuses: { COMPLETED: 35 }, lastEvaluatedAt: '2026-10-02T06:40:03Z',
      backfilledBars: 60, broadStatuses: { COMPLETED: 6, DATA_MISSING: 40 } } }),
  })
  expect(state(steps, 'observation')).toBe('done')
  expect(steps.find(step => step.id === 'observation')?.detail).toContain('Broad 참고: 완료 6/46')
})

// 저녁 작업: 16:05 지수 일봉, 18:00 분봉 재보완·관측 재평가, 18:15 완결성 최종 기록을 시간표에 보여 줘요.
test('shows the evening jobs after 18:15', () => {
  const steps = buildTimeline({
    clock: clockAt('18:20'), realtime, subscriptions: { limit: 41, remaining: 0, precisionState: 'FROZEN' },
    collection: collection({
      indexDaily: { rows: 2, errors: 0, lastAt: '2026-10-02T07:05:01Z' },
      lateObservation: { refilledBars: 19, lastEvaluatedAt: '2026-10-02T09:01:00Z' },
    }),
    health: [{ tradeDate: '2026-10-02', verdict: 'COMPLETE', reasons: [], runId: 70, runCompletedAt: null,
      paperEntryStatuses: null, observationStatuses: null, stage: 'FINAL' }],
  })
  expect(state(steps, 'index-daily')).toBe('done')
  expect(state(steps, 'late-observation')).toBe('done')
  expect(steps.find(step => step.id === 'late-observation')?.detail).toContain('다시 채운 5분봉 19')
  expect(state(steps, 'health-final')).toBe('done')
  expect(steps.map(step => step.time)).toEqual([...steps.map(step => step.time)].sort())
})

// 참고 지표 문구: 주의할 점은 앞에 붙이고, 값이 없으면 '-'예요.
test('formats daily health reference indicators', () => {
  expect(indicatorLine(null)).toBe('-')
  expect(indicatorLine({ precisionReplacements: 0, dailyDataFlagged: 2, coverageGaps: 41, coverageStillMissing: 0,
    notes: ['REPLACEMENTS_NONE', 'DAILY_DATA_FLAGGED'] }))
    .toBe('⚠ 정밀 교체 0건, 일봉 결함 후보 있음 · 교체 0 · 일봉 결함 2 · 재연결 빈칸 41(남음 0)')
})

// 18:15가 지났는데 최종 기록이 없으면 확인 필요, 18:00 전에는 재보완이 예정이에요.
test('flags a missing final health record', () => {
  const evening = buildTimeline({
    clock: clockAt('18:25'), realtime, collection: collection({ indexDaily: { rows: 2, errors: 0, lastAt: null },
      lateObservation: { refilledBars: 0, lastEvaluatedAt: null } }),
    health: [{ tradeDate: '2026-10-02', verdict: 'COMPLETE', reasons: [], runId: 70, runCompletedAt: null,
      paperEntryStatuses: null, observationStatuses: null, stage: 'PRELIMINARY' }],
  })
  expect(state(evening, 'health-final')).toBe('warn')
  expect(state(evening, 'late-observation')).toBe('warn')
  const afternoon = buildTimeline({ clock: clockAt('17:00'), realtime,
    collection: collection({ lateObservation: { refilledBars: 0, lastEvaluatedAt: null } }), health: [] })
  expect(state(afternoon, 'late-observation')).toBe('waiting')
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

// 수능일처럼 장이 10:00~16:30으로 한 시간 늦춰진 날은 장에 묶인 작업 시각을 옮기고, DART 같은 고정 작업은 그대로 둬요.
test('shifts session-bound jobs on a delayed session day', () => {
  const steps = buildTimeline({
    clock: clockAt('15:30'), realtime, collection: collection({ sessionOpen: '10:00', sessionClose: '16:30' }),
    subscriptions: { limit: 41, remaining: 0, precisionState: 'FROZEN' }, health: [],
  })
  const time = (id: string) => steps.find(step => step.id === id)?.time
  expect(time('realtime')).toBe('09:59')
  expect(time('evaluate')).toBe('16:15')
  expect(time('health-final')).toBe('19:15')
  expect(time('dart')).toBe('18:10')
  // 15:30은 그날 평가(16:15) 전이라 문제 대신 예정이에요.
  expect(state(steps, 'evaluate')).toBe('waiting')
  expect(steps.map(step => step.time)).toEqual([...steps.map(step => step.time)].sort())
})

// 장 마감 뒤 재시작하면 연결 시각과 스캔 횟수가 0이 되지만, 그날 저장된 실시간 5분봉·스캔 기록이 있으면 정상으로 봐요.
test('uses stored evidence after a backend restart', () => {
  const restarted: TodayInputs['realtime'] = { realtime: {
    connected: false, connectedAt: null, lastTickAt: null, receivedTicks: 0, parseErrors: 0, processingErrors: 0, processingDropped: 0,
  } }
  const steps = buildTimeline({
    clock: clockAt('17:20'), realtime: restarted,
    wide: { completedRuns: 0, failedRuns: 0, lastCompletedAt: null, lastError: null },
    collection: collection({
      scanRuns: { rows: 100, errors: 0, lastAt: '2026-10-02T06:18:11Z' },
      realtimeBars: { rows: 2956, stocks: 53, lastAt: '2026-10-02T06:31:04Z' },
    }),
    health: [],
  })
  expect(state(steps, 'realtime')).toBe('done')
  expect(state(steps, 'scan')).toBe('done')
  // 저장 기록이 없으면(예전 백엔드) 지금처럼 문제로 남아요.
  const old = buildTimeline({ clock: clockAt('17:20'), realtime: restarted,
    wide: { completedRuns: 0, failedRuns: 0, lastCompletedAt: null, lastError: null }, collection: collection(), health: [] })
  expect(state(old, 'realtime')).toBe('problem')
  expect(state(old, 'scan')).toBe('problem')
})

// 15:40 보완 뒤 빠진 게 15:25 종가 봉뿐이면 18:00 재보완 전까지 '확인 필요' 대신 진행 중으로 보여 줘요.
test('waits for the 18:00 refill when only the closing bar is missing', () => {
  const preliminary = { tradeDate: '2026-10-02', verdict: 'DEGRADED', reasons: ['OBSERVATION_INCOMPLETE'], runId: 11,
    runCompletedAt: null, paperEntryStatuses: null, observationStatuses: null, stage: 'PRELIMINARY' as const }
  const waiting = (time: string, closingBarOnly: number) => buildTimeline({
    clock: clockAt(time), realtime, subscriptions: { limit: 41, remaining: 0, precisionState: 'FROZEN' },
    collection: collection({ observation: { statuses: { COMPLETED: 23, DATA_INCOMPLETE: 2 }, lastEvaluatedAt: '2026-10-02T06:41:00Z',
      backfilledBars: 1400, closingBarOnly } }),
    health: [preliminary],
  })
  const before = waiting('16:00', 2)
  expect(state(before, 'observation')).toBe('running')
  expect(before.find(step => step.id === 'observation')?.detail).toContain('18:00 재보완 대기')
  expect(state(before, 'health')).toBe('running')
  // 장중 봉도 빠졌으면 지금처럼 확인 필요예요.
  const realGap = waiting('16:00', 1)
  expect(state(realGap, 'observation')).toBe('warn')
  expect(state(realGap, 'health')).toBe('warn')
  // 18:00 재보완 뒤에도 남아 있으면 확인 필요로 돌아가요.
  expect(state(waiting('18:20', 2), 'observation')).toBe('warn')
})
