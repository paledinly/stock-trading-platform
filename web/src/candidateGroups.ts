/**
 * 마감 평가 후보를 화면 묶음으로 나눠요. 백엔드는 데이터가 부족해 판단하지 못한 정밀 후보도 관찰(WATCH)로 보내므로,
 * 그런 행은 '관찰 후보'와 섞지 않고 '판단 보류'로 따로 보여 줘요(처분 자체는 바꾸지 않아요).
 */

export type CandidateGroup = 'SELECTED' | 'WATCH' | 'FADED' | 'HOLD' | 'EXCLUDED'

/**
 * 신호가 약해진 사유예요. 틱·봉은 정상인데 마지막 스캐너 탐지가 평가 시각보다 30분 넘게 앞서요
 * (오후 늦게 돌파·VWAP 회복 같은 조건을 다시 만족하지 못함). 데이터 부족과 구분해요.
 */
const FADED_REASONS = new Set(['STALE_FEATURE'])

/** 데이터가 부족해 판단을 보류한 사유예요. 조건을 통과한 관찰(LIMITED_MODE_WATCH, BROAD_WATCH_ONLY 등)과 구분해요. */
const DATA_HOLD_REASONS = new Set([
  'MISSING_REQUIRED_FEATURES', 'DAILY_DATA_MISSING', 'DAILY_DATA_STALE', 'CANDLE_GAP',
  'CANDLE_TIMESTAMP_MISSING', 'RECEIVED_AFTER_EVALUATION', 'RECEIVED_AT_MISSING', 'CALENDAR_UNVERIFIED',
  'INSUFFICIENT_INTRADAY_COVERAGE', 'LATEST_CANDLE_STALE', 'TRADABILITY_UNVERIFIED',
])

export const CANDIDATE_GROUPS: { group: CandidateGroup; title: string }[] = [
  { group: 'SELECTED', title: '최종 추천' },
  { group: 'WATCH', title: '관찰 후보' },
  { group: 'FADED', title: '신호 약해짐 (30분 넘게 재탐지 없음)' },
  { group: 'HOLD', title: '판단 보류 (데이터 부족)' },
  { group: 'EXCLUDED', title: '제외 후보' },
]

/** 후보 한 행의 화면 묶음이에요. 받는 것: 처분과 사유. */
export function candidateGroup(row: { disposition: string; decisionReason: string }): CandidateGroup {
  if (row.disposition === 'WATCH' && FADED_REASONS.has(row.decisionReason)) return 'FADED'
  if (row.disposition === 'WATCH' && DATA_HOLD_REASONS.has(row.decisionReason)) return 'HOLD'
  return row.disposition === 'SELECTED' || row.disposition === 'WATCH' ? row.disposition : 'EXCLUDED'
}

/** 거래대금(원)을 "12.3억"처럼 보여 줘요. 값이 없거나 숫자가 아니면 null이에요. */
export function tradingValueLabel(value?: number | string | null) {
  const amount = value == null ? NaN : Number(value)
  if (!Number.isFinite(amount)) return null
  return `${(amount / 100_000_000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}억`
}
