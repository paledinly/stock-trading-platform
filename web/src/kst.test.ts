/**
 * 한국 장 날짜 도우미(kst.ts) 테스트예요. UTC로는 전날인 한국시간 아침에도 한국 날짜가 나오는지 봐요.
 */
import { kstDayEnd, kstDayStart, marketDate } from './kst'

test('uses the Korean date even before 09:00 KST', () => {
  expect(marketDate(new Date('2026-10-14T23:30:00Z'))).toBe('2026-10-15')
  expect(marketDate(new Date('2026-10-15T14:59:00Z'))).toBe('2026-10-15')
  expect(kstDayStart('2026-10-15')).toBe('2026-10-14T15:00:00.000Z')
  expect(kstDayEnd('2026-10-15')).toBe('2026-10-15T14:59:59.000Z')
})
