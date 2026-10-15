/**
 * 한국 장 날짜(KST) 도우미예요. toISOString()은 UTC라서 한국 시간 오전 9시 전에는 하루 전 날짜가 나오므로,
 * 화면의 기본 날짜는 모두 여기서 만들어요.
 */

/** 주어진 시각의 한국시간 날짜를 "YYYY-MM-DD"로 돌려줘요. 브라우저 시간대와 상관없어요. */
export function marketDate(date: Date) {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date)
  const part = (type: string) => parts.find(row => row.type === type)!.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

/** 오늘(한국시간) 날짜예요. */
export function kstToday() {
  return marketDate(new Date())
}

/** 오늘(한국시간)부터 며칠 전 날짜예요. 받는 것: 며칠 전인지. */
export function kstDaysAgo(days: number) {
  return marketDate(new Date(Date.now() - days * 86400000))
}

/** 한국시간 날짜의 하루 시작·끝 시각(ISO, UTC 표기)이에요. 서버 기간 조회에 써요. */
export function kstDayStart(date: string) {
  return new Date(`${date}T00:00:00+09:00`).toISOString()
}

export function kstDayEnd(date: string) {
  return new Date(`${date}T23:59:59+09:00`).toISOString()
}
