/**
 * lightweight-charts 라이브러리로 5분봉 캔들 차트를 그리는 파일이에요.
 * 차트는 처음 한 번만 만들고, 봉 데이터가 바뀔 때마다 데이터만 갈아끼워요.
 * 대시보드(Phase3App)의 종목 상세 영역에서 쓰는 것으로 보여요.
 */
import { useEffect, useRef } from 'react'
import {
  CandlestickSeries,
  ColorType,
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { Candle } from './marketData'

/**
 * 5분봉 캔들 차트를 보여 주는 React 컴포넌트(화면 조각)예요. 비유하면 "주가 그래프 액자"예요.
 * 받는 것(props, 부모가 넘겨주는 값): candles(5분봉 목록).
 * 돌려주는 것: 차트가 그려질 div 화면.
 */
export function CandleChart({ candles }: { candles: Candle[] }) {
  // useRef(다시 그려도 유지되는 보관함)로 DOM 요소와 차트 객체를 들고 있어요.
  const container = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const series = useRef<ISeriesApi<'Candlestick'> | null>(null)

  // 처음 한 번: 차트를 만들고, 크기 변화를 감시하고, 사라질 때 정리해요.
  useEffect(() => {
    if (!container.current) return
    const instance = createChart(container.current, {
      width: container.current.clientWidth,
      height: 194,
      layout: { background: { type: ColorType.Solid, color: '#0b121d' }, textColor: '#758296' },
      grid: { vertLines: { color: '#182235' }, horzLines: { color: '#182235' } },
      rightPriceScale: { borderColor: '#26334a' },
      timeScale: { borderColor: '#26334a', timeVisible: true, secondsVisible: false },
      localization: { locale: 'ko-KR', priceFormatter: (price: number) => Math.round(price).toLocaleString('ko-KR') },
    })
    // 한국식 색: 상승은 빨강, 하락은 파랑이에요.
    series.current = instance.addSeries(CandlestickSeries, {
      upColor: '#ff6778', downColor: '#529fff', borderVisible: false,
      wickUpColor: '#ff6778', wickDownColor: '#529fff',
    })
    chart.current = instance
    // 부모 너비가 바뀌면 차트 너비도 맞춰요.
    const observer = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width
      if (width) instance.applyOptions({ width })
    })
    observer.observe(container.current)
    return () => {
      observer.disconnect()
      instance.remove()
      chart.current = null
      series.current = null
    }
  }, [])

  // 봉 목록이 바뀔 때마다 차트용 형식(초 단위 UTC 시각)으로 바꿔 다시 넣어요.
  useEffect(() => {
    const data = candles.map(candle => ({
      time: Math.floor(new Date(candle.startTime).getTime() / 1000) as UTCTimestamp,
      open: candle.open,
      high: candle.high,
      low: candle.low,
      close: candle.close,
    }))
    series.current?.setData(data)
    if (data.length) chart.current?.timeScale().fitContent()
  }, [candles])

  return <div className="candleCanvas" ref={container} aria-label="실제 5분봉 차트" />
}
