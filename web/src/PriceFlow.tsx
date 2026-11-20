/**
 * 종목 하나의 하루 가격 흐름(1분봉)을 그려요. AI 장전 기록·채점과 후보 평가 표에서 종목을 누르면 그 줄 바로 밑에 펼쳐져요.
 * /api/v1/stocks/{code}/intraday-flow?date=로 받아요. 실시간 구독과 상관없는 KIS 분봉 조회라 구독 안 된 종목도 보여요.
 * 장중이면 1분마다 새로 받아요. 선은 1분 종가, 점선은 첫 분봉 시가(채점 기준 시가→종가와 같은 기준)예요.
 */
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'

export type FlowBar = { time: string; open: number; high: number; low: number; close: number; volume: number }
export type Flow = { stockCode: string; date: string; status: string; message: string | null; live: boolean; fetchedAt: string;
  open: number | null; high: number | null; low: number | null; last: number | null; changeFromOpenPct: number | null; volume: number; bars: FlowBar[] }

const W = 720, H = 220, VOL_H = 44, PAD_L = 8, PAD_R = 64, PAD_T = 10

const won = (value: number | null | undefined) => value == null ? '--' : Number(value).toLocaleString('ko-KR')
const signed = (value: number | null | undefined) => value == null ? '--' : `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
const toneOf = (value: number | null | undefined) => value == null || value === 0 ? '' : value > 0 ? 'aiUp' : 'aiDown'

async function load(code: string, date: string): Promise<Flow> {
  const response = await fetch(`/api/v1/stocks/${code}/intraday-flow?date=${date}`)
  if (!response.ok) throw new Error('가격 흐름을 불러오지 못했어요.')
  return response.json()
}

/** 종목코드·날짜의 1분봉 흐름 패널이에요. */
export function PriceFlow({ code, date, name }: { code: string; date: string; name?: string }) {
  const flow = useQuery({
    queryKey: ['intraday-flow', code, date], queryFn: () => load(code, date),
    refetchInterval: query => query.state.data?.live ? 60_000 : false,
  })
  const f = flow.data
  return (
    <div className="aiFlow" aria-label={`${name ?? code} 가격 흐름`}>
      {flow.isLoading && <p className="aiEmpty">{date} 1분봉을 받는 중이에요(구독 안 된 종목은 몇 초 걸려요).</p>}
      {flow.isError && <p className="aiEmpty">가격 흐름을 불러오지 못했어요.</p>}
      {f && f.status !== 'OK' && <p className="aiEmpty">{f.message ?? '보여 줄 분봉이 없어요.'}</p>}
      {f && f.status === 'OK' && (
        <>
          <div className="aiFlowStats">
            <span><small>{date} 시가</small><b>{won(f.open)}</b></span>
            <span><small>{f.live ? '현재' : '종가'}</small><b>{won(f.last)}</b></span>
            <span><small>시가 대비</small><b className={toneOf(f.changeFromOpenPct)}>{signed(f.changeFromOpenPct)}</b></span>
            <span><small>고가</small><b>{won(f.high)} <em className={toneOf(rel(f.high, f.open))}>{signed(rel(f.high, f.open))}</em></b></span>
            <span><small>저가</small><b>{won(f.low)} <em className={toneOf(rel(f.low, f.open))}>{signed(rel(f.low, f.open))}</em></b></span>
            <span><small>거래량</small><b>{won(f.volume)}</b></span>
          </div>
          <FlowChart flow={f} />
          <p className="aiNote">1분봉 {f.bars.length}개 · {f.live ? '장중이라 1분마다 새로 받아요' : '장 마감 기준'} · KIS 분봉 조회(실시간 구독과 별개, 저장 안 함)</p>
        </>
      )}
    </div>
  )
}

function rel(value: number | null, base: number | null) {
  return value == null || base == null || base === 0 ? null : (value - base) / base * 100
}

/** 1분 종가 선, 시가 점선, 거래량 막대를 그려요. 마우스를 올리면 그 분의 값이 보여요. */
function FlowChart({ flow }: { flow: Flow }) {
  const [hover, setHover] = useState<number | null>(null)
  const geo = useMemo(() => {
    const bars = flow.bars
    const lo = Math.min(...bars.map(b => b.low)), hi = Math.max(...bars.map(b => b.high))
    const span = hi - lo || hi * 0.01 || 1
    const plotW = W - PAD_L - PAD_R, plotH = H - PAD_T - VOL_H - 16
    const x = (i: number) => PAD_L + (bars.length === 1 ? plotW / 2 : i * plotW / (bars.length - 1))
    const y = (v: number) => PAD_T + (hi - v) / span * plotH
    const maxVol = Math.max(1, ...bars.map(b => b.volume))
    const line = bars.map((b, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(b.close).toFixed(1)}`).join('')
    const ticks = bars.map((b, i) => ({ b, i })).filter(({ b }) => b.time.endsWith(':00'))
    return { bars, lo, hi, x, y, plotW, plotH, maxVol, line, ticks }
  }, [flow])
  const { bars, x, y, maxVol, line, ticks, lo, hi } = geo
  const up = (flow.changeFromOpenPct ?? 0) >= 0
  const h = hover == null ? null : bars[hover]
  const volTop = H - VOL_H - 4
  return (
    <div className="aiFlowChart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="1분봉 가격 흐름"
        onMouseLeave={() => setHover(null)}
        onMouseMove={event => {
          const box = event.currentTarget.getBoundingClientRect()
          const px = (event.clientX - box.left) / box.width * W
          const i = Math.round((px - PAD_L) / (W - PAD_L - PAD_R) * (bars.length - 1))
          setHover(Math.max(0, Math.min(bars.length - 1, i)))
        }}>
        {flow.open != null && <line x1={PAD_L} x2={W - PAD_R} y1={y(flow.open)} y2={y(flow.open)} className="aiFlowOpen" />}
        {bars.map((b, i) => (
          <rect key={b.time} x={x(i) - 0.6} width={1.2} y={volTop + VOL_H - b.volume / maxVol * VOL_H} height={b.volume / maxVol * VOL_H} className="aiFlowVol" />
        ))}
        <path d={line} className={up ? 'aiFlowLine up' : 'aiFlowLine down'} />
        <text x={W - PAD_R + 6} y={y(hi) + 4} className="aiFlowAxis">{won(hi)}</text>
        <text x={W - PAD_R + 6} y={y(lo)} className="aiFlowAxis">{won(lo)}</text>
        {flow.open != null && <text x={W - PAD_R + 6} y={y(flow.open) + 4} className="aiFlowAxis">시가</text>}
        {ticks.map(({ b, i }) => <text key={b.time} x={x(i)} y={H - 2} textAnchor="middle" className="aiFlowAxis">{b.time}</text>)}
        {h && hover != null && (
          <>
            <line x1={x(hover)} x2={x(hover)} y1={PAD_T} y2={H - 14} className="aiFlowCursor" />
            <circle cx={x(hover)} cy={y(h.close)} r={3} className={up ? 'aiFlowDot up' : 'aiFlowDot down'} />
          </>
        )}
      </svg>
      <div className="aiFlowHover" aria-live="polite">
        {h ? <>{h.time} · 종가 {won(h.close)} <span className={toneOf(rel(h.close, flow.open))}>({signed(rel(h.close, flow.open))})</span> · 고 {won(h.high)} · 저 {won(h.low)} · 거래량 {won(h.volume)}</>
          : '차트에 마우스를 올리면 그 분의 값이 보여요.'}
      </div>
    </div>
  )
}
