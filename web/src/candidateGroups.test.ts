/** 마감 평가 후보 화면 묶음(candidateGroups.ts) 테스트예요. */
import { candidateGroup, tradingValueLabel } from './candidateGroups'

// 데이터가 부족해 관찰로 간 정밀 후보는 '판단 보류'로, 조건을 통과한 관찰은 그대로 '관찰'로 나눠요.
test('separates data holds from real watch candidates', () => {
  expect(candidateGroup({ disposition: 'WATCH', decisionReason: 'STALE_FEATURE' })).toBe('HOLD')
  expect(candidateGroup({ disposition: 'WATCH', decisionReason: 'BROAD_WATCH_ONLY' })).toBe('WATCH')
  expect(candidateGroup({ disposition: 'WATCH', decisionReason: 'LIMITED_MODE_WATCH' })).toBe('WATCH')
  expect(candidateGroup({ disposition: 'SELECTED', decisionReason: 'QUALIFIED' })).toBe('SELECTED')
  expect(candidateGroup({ disposition: 'EXCLUDED', decisionReason: 'LOW_LIQUIDITY' })).toBe('EXCLUDED')
})

test('formats trading value in 억', () => {
  expect(tradingValueLabel('7752265058.0000')).toBe('77.5억')
  expect(tradingValueLabel(30_000_000)).toBe('0.3억')
  expect(tradingValueLabel(null)).toBeNull()
})
