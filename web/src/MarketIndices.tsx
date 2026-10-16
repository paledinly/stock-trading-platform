/**
 * 모든 화면에 고정으로 보이는 KOSPI·KOSDAQ 지수 칸이에요. 왼쪽 메뉴 아래(좁은 화면은 윗줄)에 있어요.
 * 장중에는 5분마다 쌓이는 지수 관측값을, 없으면 마지막 일봉 종가를 보여 줘요(/api/v1/market-data/indices).
 * 숫자만 보여 주고 강세·약세 같은 국면 이름은 붙이지 않아요. 국면 규칙은 아직 사전 등록·검증 전이에요.
 */
import { useQuery } from '@tanstack/react-query'

/** 서버가 주는 지수 하나예요. source: INTRADAY(장중 관측), DAILY_CLOSE(마지막 일봉 종가), NONE(자료 없음). */
export type MarketIndexView = {
  name: string
  value: number | null
  changePct: number | null
  observedAt: string | null
  source: 'INTRADAY' | 'DAILY_CLOSE' | 'NONE'
  previousDate: string | null
  previousClose: number | null
}

const number = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const hourMinute = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const monthDay = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' })

/** 언제 값인지 짧게 적어요. 장중 관측은 수신 시각, 일봉 종가는 날짜예요. */
function basis(view: MarketIndexView) {
  if (!view.observedAt) return ''
  const at = new Date(view.observedAt)
  return view.source === 'INTRADAY' ? `${hourMinute.format(at)} 수신` : `${monthDay.format(at)} 수집 종가`
}

/** 지수 칸 컴포넌트예요. 받는 것: 없음. 돌려주는 것: 지수별 값·등락률 줄(자료가 없으면 아무것도 안 그려요). */
export function MarketIndices() {
  const indices = useQuery({
    queryKey: ['sidebar', 'indices'],
    queryFn: async () => {
      const response = await fetch('/api/v1/market-data/indices')
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return response.json() as Promise<MarketIndexView[]>
    },
    refetchInterval: 60_000,
    retry: false,
  })
  const rows = Array.isArray(indices.data) ? indices.data.filter(row => row.value != null) : []
  if (!rows.length) return null
  return (
    <div className="marketIndices" aria-label="시장 지수">
      {rows.map(row => {
        const change = row.changePct
        const tone = change == null || change === 0 ? 'flat' : change > 0 ? 'up' : 'down'
        const title = row.previousClose != null && row.previousDate
          ? `전일(${row.previousDate}) 종가 ${number.format(row.previousClose)} · ${basis(row)}`
          : basis(row)
        return (
          <p key={row.name} className={`marketIndex ${tone}`} title={title}>
            <b>{row.name}</b>
            <span>{number.format(row.value!)}</span>
            <em>{change == null ? '—' : `${change > 0 ? '+' : ''}${number.format(change)}%`}</em>
          </p>
        )
      })}
      <small className="marketIndicesBasis">{basis(rows[0])}</small>
    </div>
  )
}
