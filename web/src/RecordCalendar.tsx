/**
 * AI 장전 기록·채점과 후보 평가에서 기록 날짜를 고르는 달력이에요.
 * 기록이 있는 날은 색과 짧은 설명으로 표시하고, 아무 날이나 누를 수 있어요(빈 날은 빈 목록이 보여요).
 * 달 이동은 부모가 그 달 기록을 다시 불러오도록 month를 바꿔 알려 줘요.
 */

export type DayMark = { label: string; tone?: 'ok' | 'muted' | 'warn' }

const WEEK = ['일', '월', '화', '수', '목', '금', '토']

/** 오늘 날짜를 서울 기준 'YYYY-MM-DD'로 돌려줘요. */
export function seoulToday(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}

/** 'YYYY-MM'의 첫날과 마지막 날을 돌려줘요. */
export function monthRange(month: string): { from: string; to: string } {
  const [y, m] = month.split('-').map(Number)
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate()
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, '0')}` }
}

/** 'YYYY-MM'에 delta달을 더해요. */
export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 + delta, 1))
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`
}

/** 한 달 달력이에요. marks: 날짜별 기록 표시, selected: 고른 날짜. */
export function RecordCalendar({ month, onMonth, marks, selected, onSelect, label }: {
  month: string; onMonth: (month: string) => void; marks: Record<string, DayMark>
  selected: string | null; onSelect: (date: string) => void; label: string
}) {
  const [y, m] = month.split('-').map(Number)
  const firstWeekday = new Date(Date.UTC(y, m - 1, 1)).getUTCDay()
  const days = Number(monthRange(month).to.slice(8))
  const today = seoulToday()
  const cells: (string | null)[] = [...Array(firstWeekday).fill(null)]
  for (let d = 1; d <= days; d++) cells.push(`${month}-${String(d).padStart(2, '0')}`)
  while (cells.length % 7) cells.push(null)
  const count = Object.keys(marks).filter(date => date.startsWith(month)).length
  return (
    <div className="aiCalendar" aria-label={label}>
      <div className="aiCalendarHead">
        <button type="button" aria-label="이전 달" onClick={() => onMonth(shiftMonth(month, -1))}>‹</button>
        <b>{y}년 {m}월</b>
        <button type="button" aria-label="다음 달" onClick={() => onMonth(shiftMonth(month, 1))}>›</button>
        <small>기록 {count}일</small>
        {!month.startsWith(today.slice(0, 7)) && <button type="button" className="aiCalendarToday" onClick={() => { onMonth(today.slice(0, 7)); onSelect(today) }}>오늘</button>}
      </div>
      <div className="aiCalendarGrid" role="grid">
        {WEEK.map((w, i) => <span key={w} className={i === 0 ? 'aiCalendarWeek sun' : i === 6 ? 'aiCalendarWeek sat' : 'aiCalendarWeek'}>{w}</span>)}
        {cells.map((date, i) => {
          if (!date) return <span key={`blank-${i}`} className="aiCalendarBlank" />
          const mark = marks[date]
          const weekday = i % 7
          const cls = ['aiCalendarDay', mark ? `marked ${mark.tone ?? ''}` : '', date === selected ? 'selected' : '', date === today ? 'today' : '',
            weekday === 0 || weekday === 6 ? 'weekend' : ''].filter(Boolean).join(' ')
          return (
            <button type="button" role="gridcell" key={date} className={cls} aria-pressed={date === selected}
              aria-label={`${date}${mark ? ` 기록 있음: ${mark.label}` : ' 기록 없음'}`} onClick={() => onSelect(date)}>
              <b>{Number(date.slice(8))}</b>
              {mark && <small>{mark.label}</small>}
            </button>
          )
        })}
      </div>
      <p className="aiCalendarLegend"><i className="ok" />집계에 들어간 날 <i className="muted" />집계 밖(장 시작 뒤 입력 등) · 빈 날도 누를 수 있어요</p>
    </div>
  )
}
