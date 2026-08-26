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

export function CandleChart({ candles }: { candles: Candle[] }) {
  const container = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const series = useRef<ISeriesApi<'Candlestick'> | null>(null)

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
    series.current = instance.addSeries(CandlestickSeries, {
      upColor: '#ff6778', downColor: '#529fff', borderVisible: false,
      wickUpColor: '#ff6778', wickDownColor: '#529fff',
    })
    chart.current = instance
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
