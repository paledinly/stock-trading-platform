import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Phase4App } from './Phase4App'
import './phase6.css'

type ScannerType = 'VOLUME' | 'PRICE_RISE' | 'MOMENTUM' | 'VOLUME_BREAKOUT' | 'TURNOVER_BREAKOUT' | 'HIGH_BREAKOUT' | 'VWAP_BREAKOUT' | 'VWAP_RECLAIM' | 'PULLBACK_REBREAK'
type Detection = { id: number; eventId: string; scannerType: ScannerType; stockCode: string; stockName: string; market: string; detectedAt: string; detectedPrice: number; fiveMinuteChangeRate: number; volumeRatio: number; currentFiveMinuteVolume: number; currentFiveMinuteTradingValue: number; dailyTradingValue: number; momentumScore: number; opportunityScore?: number; riskScore?: number; scoreVersion?: string; scoreBreakdown?: string; settingName: string; settingSnapshot: string; featureVersion?: string; featureSnapshot?: string; detectionReason?: string }
type Setting = { id: number; name: string; type: ScannerType; minChangeRate: number; minVolumeRatio: number; minFiveMinuteTradingValue: number; minDailyTradingValue: number; minPrice: number; includeEtf: boolean; cooldownSeconds: number; active: boolean; version: number }
type Status = { realtime?: { connected: boolean; lastTickAt?: string; lastDetectionAt?: string; receivedTicks?: number; parseErrors?: number; featureSnapshots?: number; featureTrackedStocks?: number }; redisStatus?: string; subscriptionCount?: number; subscriptionLimit?: number; subscriptionRemaining?: number }
type Candle = { startTime: string; open: number; high: number; low: number; close: number; volume: number; tradingValue: number; finalCandle: boolean; revision: number }
type Trade = { id: number; stockCode: string; tradeType: 'BUY' | 'SELL'; tradedAt: string; price: number }
type Performance = { status: string; return5m?: number; return10m?: number; return30m?: number; return60m?: number; returnClose?: number; maxReturn?: number; maxDrawdown?: number }
type Reason = { state?: string; vwapDistanceRate?: string; tradeStrength?: string; dayHighDistanceRate?: string }
type Breakdown = { opportunityFactors?: Record<string, number>; riskFactors?: Record<string, number> }

const tabs = [
  ['MOMENTUM', '복합 모멘텀'],
  ['VOLUME', '거래량 급증'],
  ['PRICE_RISE', '5분 급등'],
  ['VOLUME_BREAKOUT', '거래량 돌파'],
  ['TURNOVER_BREAKOUT', '회전율 돌파'],
  ['HIGH_BREAKOUT', '고가 돌파'],
  ['VWAP_BREAKOUT', '평균가 돌파'],
  ['VWAP_RECLAIM', '평균가 회복'],
  ['PULLBACK_REBREAK', '눌림 후 재돌파'],
] as const

const emptySetting: Setting = {
  id: 0,
  name: '',
  type: 'MOMENTUM',
  minChangeRate: 0,
  minVolumeRatio: 1,
  minFiveMinuteTradingValue: 0,
  minDailyTradingValue: 0,
  minPrice: 0,
  includeEtf: false,
  cooldownSeconds: 300,
  active: true,
  version: 0,
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!response.ok) throw new Error('실시간 레이더 데이터를 처리하지 못했습니다.')
  return response.status === 204 ? undefined as T : response.json()
}

function settingPayload(setting: Setting) {
  return {
    name: setting.name,
    type: setting.type,
    minChangeRate: setting.minChangeRate,
    minVolumeRatio: setting.minVolumeRatio,
    minFiveMinuteTradingValue: setting.minFiveMinuteTradingValue,
    minDailyTradingValue: setting.minDailyTradingValue,
    minPrice: setting.minPrice,
    includeEtf: setting.includeEtf,
    cooldownSeconds: setting.cooldownSeconds,
    active: setting.active,
    version: setting.version,
  }
}

function num(value: number | undefined, digits = 1) {
  return value == null ? '--' : value.toLocaleString('ko-KR', { maximumFractionDigits: digits, minimumFractionDigits: digits })
}

function money(value: number | undefined) {
  return value == null ? '--' : Math.round(value).toLocaleString('ko-KR')
}

function pct(value: number | undefined) {
  return value == null ? '--' : `${value >= 0 ? '+' : ''}${num(value, 2)}%`
}

function parseJson<T>(value?: string): T | undefined {
  if (!value) return undefined
  try {
    return JSON.parse(value) as T
  } catch {
    return undefined
  }
}

function scannerTypeLabel(type: ScannerType | string) {
  return tabs.find(([key]) => key === type)?.[1] ?? type
}

function settingNameLabel(name: string, type?: ScannerType | string) {
  const labels: Record<string, string> = {
    Momentum: '복합 모멘텀',
    'Volume Breakout': '거래량 돌파',
    'Turnover Breakout': '회전율 돌파',
    'High Breakout': '고가 돌파',
    'VWAP Breakout': '평균가 돌파',
    'VWAP Reclaim': '평균가 회복',
    'Pullback Rebreak': '눌림 후 재돌파',
  }
  return labels[name] ?? (type ? scannerTypeLabel(type) : name)
}

function stateLabel(value?: string) {
  if (value === 'MATCHED') return '조건 충족'
  if (value === 'CONDITION_NOT_MET') return '조건 미충족'
  if (value === 'INSUFFICIENT_HISTORY') return '이력 부족'
  if (value === 'BELOW_MIN_PRICE') return '최소 가격 미달'
  if (value === 'BELOW_MIN_5M_VALUE') return '5분 거래대금 미달'
  if (value === 'BELOW_MIN_DAILY_VALUE') return '당일 거래대금 미달'
  return value ?? '--'
}

function factorLabel(value: string) {
  const labels: Record<string, string> = {
    priceMomentum: '가격 모멘텀',
    volumeExpansion: '거래량 확장',
    vwapLeadership: '평균가 우위',
    tradeStrength: '체결 강도',
    dayHighProximity: '당일 고점 근접',
    vwapOverextension: '평균가 과열',
    weakTradeStrength: '체결 약세',
    negativeMomentum: '하락 모멘텀',
    farFromDayHigh: '고점 이탈',
    sellPressure: '매도 압력',
  }
  return labels[value] ?? value
}

function candleWindow(detection?: Detection) {
  const at = detection ? new Date(detection.detectedAt).getTime() : Date.now()
  return {
    from: new Date(at - 90 * 60000).toISOString(),
    to: new Date(at + 90 * 60000).toISOString(),
  }
}

function detectionTimeLabel(value: string, includeHistory: boolean) {
  const date = new Date(value)
  return includeHistory
    ? date.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

export function ScannerPage({ back }: { back: () => void }) {
  const cache = useQueryClient()
  const [tab, setTab] = useState<ScannerType>('MOMENTUM')
  const [search, setSearch] = useState('')
  const [includeHistory, setIncludeHistory] = useState(false)
  const [minOpportunity, setMinOpportunity] = useState(0)
  const [maxRisk, setMaxRisk] = useState(100)
  const [selectedId, setSelectedId] = useState<number>()
  const [draft, setDraft] = useState<Setting>(emptySetting)
  const detections = useQuery({
    queryKey: ['detections', tab, includeHistory],
    queryFn: () => request<Detection[]>(`/api/v1/scanner-detections?type=${tab}&limit=100&todayOnly=${!includeHistory}`),
    refetchInterval: 8000,
  })
  const selected = useQuery({
    queryKey: ['detection', selectedId],
    queryFn: () => request<Detection>(`/api/v1/scanner-detections/${selectedId}`),
    enabled: selectedId != null,
  })
  const detail = selected.data ?? detections.data?.find(item => item.id === selectedId)
  const settings = useQuery({ queryKey: ['scanner-settings'], queryFn: () => request<Setting[]>('/api/v1/scanner-settings') })
  const status = useQuery({ queryKey: ['realtime-status'], queryFn: () => request<Status>('/api/v1/market/realtime/status'), refetchInterval: 5000 })
  const trades = useQuery({ queryKey: ['trades'], queryFn: () => request<Trade[]>('/api/v1/trades?limit=100') })
  const performance = useQuery({
    queryKey: ['performance', selectedId],
    queryFn: () => request<Performance>(`/api/v1/scanner-detections/${selectedId}/performance`),
    enabled: selectedId != null,
    retry: false,
  })
  const window = candleWindow(detail)
  const candles = useQuery({
    queryKey: ['radar-candles', detail?.stockCode, window.from, window.to],
    queryFn: () => request<Candle[]>(`/api/v1/stocks/${detail?.stockCode}/candles?timeFrame=5M&from=${encodeURIComponent(window.from)}&to=${encodeURIComponent(window.to)}`),
    enabled: !!detail?.stockCode,
    retry: false,
  })
  const saveSetting = useMutation({
    mutationFn: (setting: Setting) => request<Setting>(`/api/v1/scanner-settings/${setting.id}`, { method: 'PATCH', body: JSON.stringify(settingPayload(setting)) }),
    onSuccess: () => cache.invalidateQueries({ queryKey: ['scanner-settings'] }),
  })
  const createSetting = useMutation({
    mutationFn: (setting: Setting) => request<Setting>('/api/v1/scanner-settings', { method: 'POST', body: JSON.stringify(settingPayload(setting)) }),
    onSuccess: () => {
      setDraft(emptySetting)
      cache.invalidateQueries({ queryKey: ['scanner-settings'] })
    },
  })
  const deleteSetting = useMutation({
    mutationFn: (id: number) => request<void>(`/api/v1/scanner-settings/${id}`, { method: 'DELETE' }),
    onSuccess: () => cache.invalidateQueries({ queryKey: ['scanner-settings'] }),
  })
  const filtered = useMemo(() => detections.data?.filter(item => {
    const text = `${item.stockName} ${item.stockCode}`.toLowerCase()
    return text.includes(search.toLowerCase()) && (item.opportunityScore ?? 0) >= minOpportunity && (item.riskScore ?? 0) <= maxRisk
  }).sort((left, right) => (right.opportunityScore ?? 0) - (left.opportunityScore ?? 0) || (left.riskScore ?? 0) - (right.riskScore ?? 0)) ?? [], [detections.data, search, minOpportunity, maxRisk])

  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const source = new EventSource('/api/v1/stream')
    const refresh = () => {
      cache.invalidateQueries({ queryKey: ['detections'] })
      cache.invalidateQueries({ queryKey: ['realtime-status'] })
    }
    [
      'scanner.volume.detected',
      'scanner.price.detected',
      'scanner.momentum.detected',
      'scanner.volume-breakout.detected',
      'scanner.turnover-breakout.detected',
      'scanner.high-breakout.detected',
      'scanner.vwap-breakout.detected',
      'scanner.vwap-reclaim.detected',
      'scanner.pullback-rebreak.detected',
      'scanner.performance.updated',
    ].forEach(name => source.addEventListener(name, refresh))
    return () => source.close()
  }, [cache])

  useEffect(() => {
    if (selectedId == null && filtered[0]) setSelectedId(filtered[0].id)
  }, [filtered, selectedId])

  return (
    <div className="scannerPage">
      <header>
        <button onClick={back}>← 대시보드</button>
        <div>
          <p>5단계 · 실시간 레이더</p>
          <h1>실시간 레이더</h1>
          <span>실시간 탐지 후보를 점수, 이유, 차트 흐름으로 함께 확인합니다.</span>
        </div>
        <StatusCard status={status.data} />
      </header>
      <main>
        <nav>{tabs.map(([key, label]) => <button className={tab === key ? 'active' : ''} onClick={() => { setTab(key); setSelectedId(undefined) }} key={key}>{label}</button>)}</nav>
        <section className="radarFilters">
          <input value={search} onChange={event => setSearch(event.target.value)} placeholder="종목명 또는 코드 검색" />
          <label>기회점수 ≥ <input type="number" value={minOpportunity} onChange={event => setMinOpportunity(Number(event.target.value))} /></label>
          <label>위험점수 ≤ <input type="number" value={maxRisk} onChange={event => setMaxRisk(Number(event.target.value))} /></label>
          <label className="radarHistoryToggle">이전 탐지 포함 <input type="checkbox" checked={includeHistory} onChange={event => { setIncludeHistory(event.target.checked); setSelectedId(undefined) }} /></label>
        </section>
        <section className="menuGuide">
          <h2>사용 안내</h2>
          <p>상단 탭에서 탐지 조건을 선택하고, 검색과 점수 필터로 오늘 포착된 후보를 좁힙니다. 이전 탐지 포함을 켜면 과거에 저장된 탐지 이력까지 함께 확인할 수 있습니다.</p>
        </section>
        <section className="radarGrid">
          <DetectionList items={filtered} loading={detections.isLoading} error={detections.error as Error | undefined} selectedId={selectedId} select={setSelectedId} includeHistory={includeHistory} />
          <DetectionDetail detection={detail} candles={candles.data ?? []} trades={(trades.data ?? []).filter(trade => trade.stockCode === detail?.stockCode)} performance={performance.data} />
          <SettingPanel settings={settings.data ?? []} draft={draft} setDraft={setDraft} save={setting => saveSetting.mutate(setting)} create={setting => createSetting.mutate(setting)} remove={id => deleteSetting.mutate(id)} />
        </section>
      </main>
    </div>
  )
}

function StatusCard({ status }: { status?: Status }) {
  const realtime = status?.realtime
  return (
    <aside className="opsCard">
      <small>운영 상태</small>
      <b className={realtime?.connected ? 'hot' : 'cold'}>{realtime?.connected ? '연결중' : '대기'}</b>
      <span>구독 {status?.subscriptionCount ?? 0}/{status?.subscriptionLimit ?? 0}</span>
      <span>실시간 캐시 {status?.redisStatus ?? '--'}</span>
      <span>수신 체결 {(realtime?.receivedTicks ?? 0).toLocaleString('ko-KR')}</span>
    </aside>
  )
}

function DetectionList({ items, loading, error, selectedId, select, includeHistory }: { items: Detection[]; loading: boolean; error?: Error; selectedId?: number; select: (id: number) => void; includeHistory: boolean }) {
  return (
    <div className="detectionList">
      <div className="listTitle"><span><small>{includeHistory ? '최근 탐지 이력' : '오늘 실시간 후보'}</small><h2>{includeHistory ? '탐지 이력' : '실시간 후보'}</h2></span><b>{items.length}</b></div>
      {loading && <div className="scannerEmpty">불러오는 중...</div>}
      {error && <div className="scannerEmpty">{error.message}</div>}
      {!loading && !error && items.length === 0 && <div className="scannerEmpty"><b>포착된 후보가 없습니다</b><p>필터를 낮추거나 장중 체결 유입 상태를 확인하세요.</p></div>}
      {items.map(item => (
        <button className={selectedId === item.id ? 'selected' : ''} onClick={() => select(item.id)} key={item.eventId}>
          <div><i>{item.stockName[0]}</i><span><b>{item.stockName}</b><small>{item.stockCode} · {item.market} · {detectionTimeLabel(item.detectedAt, includeHistory)}</small></span><strong>{money(item.detectedPrice)}원</strong></div>
          <dl><span><dt>5분 등락</dt><dd className={item.fiveMinuteChangeRate >= 0 ? 'hot' : 'cold'}>{pct(item.fiveMinuteChangeRate)}</dd></span><span><dt>거래량</dt><dd>{num(item.volumeRatio, 2)}배</dd></span><span><dt>기회점수</dt><dd>{num(item.opportunityScore)}</dd></span><span><dt>위험점수</dt><dd>{num(item.riskScore)}</dd></span></dl>
        </button>
      ))}
    </div>
  )
}

function DetectionDetail({ detection, candles, trades, performance }: { detection?: Detection; candles: Candle[]; trades: Trade[]; performance?: Performance }) {
  const reason = parseJson<Reason>(detection?.detectionReason)
  const breakdown = parseJson<Breakdown>(detection?.scoreBreakdown)
  const feature = parseJson<{ vwap?: number }>(detection?.featureSnapshot)
  if (!detection) return <section className="detailPanel"><div className="scannerEmpty"><b>후보를 선택하세요</b><p>탐지 이유와 차트 마커를 이곳에서 확인합니다.</p></div></section>
  return (
    <section className="detailPanel">
      <div className="detailHead"><span><small>{scannerTypeLabel(detection.scannerType)}</small><h2>{detection.stockName}</h2><p>{detection.stockCode} · {settingNameLabel(detection.settingName, detection.scannerType)}</p></span><strong>{money(detection.detectedPrice)}원</strong></div>
      <RadarChart detection={detection} candles={candles} trades={trades} vwap={feature?.vwap} />
      <div className="scoreStrip"><ScoreGauge label="기회점수" value={detection.opportunityScore} tone="hot" /><ScoreGauge label="위험점수" value={detection.riskScore} tone="cold" /><ScoreGauge label="모멘텀" value={detection.momentumScore} tone="neutral" /></div>
      <section className="reasonGrid"><Fact title="탐지 상태" value={stateLabel(reason?.state)} /><Fact title="평균가 거리" value={reason?.vwapDistanceRate ? `${reason.vwapDistanceRate}%` : '--'} /><Fact title="체결강도" value={reason?.tradeStrength ?? '--'} /><Fact title="고점 거리" value={reason?.dayHighDistanceRate ? `${reason.dayHighDistanceRate}%` : '--'} /><Fact title="5분 성과" value={performance ? pct(performance.return5m) : '집계 대기'} /><Fact title="최대 낙폭" value={performance?.maxDrawdown == null ? '--' : pct(performance.maxDrawdown)} /></section>
      <FactorList title="기회점수 근거" factors={breakdown?.opportunityFactors} />
      <FactorList title="위험점수 근거" factors={breakdown?.riskFactors} />
    </section>
  )
}

function RadarChart({ detection, candles, trades, vwap }: { detection: Detection; candles: Candle[]; trades: Trade[]; vwap?: number }) {
  const data = candles.length ? candles : [{ startTime: detection.detectedAt, open: detection.detectedPrice, high: detection.detectedPrice, low: detection.detectedPrice, close: detection.detectedPrice, volume: 0, tradingValue: 0, finalCandle: false, revision: 0 }]
  const firstTime = new Date(data[0].startTime).getTime()
  const lastTime = new Date(data[data.length - 1].startTime).getTime()
  const visibleTrades = trades.filter(trade => {
    const tradedAt = new Date(trade.tradedAt).getTime()
    return tradedAt >= firstTime && tradedAt <= lastTime
  })
  const prices = data.flatMap(candle => [candle.high, candle.low, vwap ?? candle.close, detection.detectedPrice, ...visibleTrades.map(trade => trade.price)])
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  const range = Math.max(1, max - min)
  const x = (time: string) => {
    const at = new Date(time).getTime()
    return 32 + (lastTime === firstTime ? 0.5 : (at - firstTime) / (lastTime - firstTime)) * 596
  }
  const y = (price: number) => 176 - (price - min) / range * 126
  return (
    <div className="radarChart">
      <svg viewBox="0 0 660 210" role="img" aria-label="탐지 상세 차트">
        <line x1="32" x2="628" y1={y(detection.detectedPrice)} y2={y(detection.detectedPrice)} className="detectLine" />
        {vwap && <line x1="32" x2="628" y1={y(vwap)} y2={y(vwap)} className="vwapLine" />}
        {data.map(candle => {
          const cx = x(candle.startTime)
          const up = candle.close >= candle.open
          const top = y(Math.max(candle.open, candle.close))
          const height = Math.max(3, Math.abs(y(candle.open) - y(candle.close)))
          return <g key={candle.startTime}><line x1={cx} x2={cx} y1={y(candle.high)} y2={y(candle.low)} className={up ? 'up' : 'down'} /><rect x={cx - 7} y={top} width="14" height={height} className={up ? 'upFill' : 'downFill'} /></g>
        })}
        <circle cx={x(detection.detectedAt)} cy={y(detection.detectedPrice)} r="6" className="detectDot" />
        {visibleTrades.map(trade => <g key={trade.id}><circle cx={x(trade.tradedAt)} cy={y(trade.price)} r="5" className={trade.tradeType === 'BUY' ? 'buyDot' : 'sellDot'} /><text x={x(trade.tradedAt) + 7} y={y(trade.price) - 7}>{trade.tradeType === 'BUY' ? '매수' : '매도'}</text></g>)}
      </svg>
      <div><span>탐지 위치</span><span>거래량 가중 평균가</span><span>매수/매도</span></div>
    </div>
  )
}

function ScoreGauge({ label, value, tone }: { label: string; value?: number; tone: 'hot' | 'cold' | 'neutral' }) {
  const width = Math.max(0, Math.min(100, value ?? 0))
  return <article><span>{label}</span><b className={tone}>{num(value)}</b><i><em style={{ width: `${width}%` }} /></i></article>
}

function Fact({ title, value }: { title: string; value: string }) {
  return <article><small>{title}</small><b>{value}</b></article>
}

function FactorList({ title, factors }: { title: string; factors?: Record<string, number> }) {
  const rows = Object.entries(factors ?? {})
  return <div className="factorList"><h3>{title}</h3>{rows.length === 0 ? <p>근거 데이터 대기</p> : rows.map(([key, value]) => <span key={key}><small>{factorLabel(key)}</small><b>{num(value)}</b></span>)}</div>
}

function SettingPanel({ settings, draft, setDraft, save, create, remove }: { settings: Setting[]; draft: Setting; setDraft: (setting: Setting) => void; save: (setting: Setting) => void; create: (setting: Setting) => void; remove: (id: number) => void }) {
  function submit(event: FormEvent) {
    event.preventDefault()
    create(draft)
  }
  return (
    <aside className="settingPanel">
      <h2>탐지 설정</h2>
      <form onSubmit={submit} className="settingForm"><input value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} placeholder="새 설정 이름" /><select value={draft.type} onChange={event => setDraft({ ...draft, type: event.target.value as ScannerType })}>{tabs.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select><button disabled={!draft.name.trim()}>추가</button></form>
      {settings.map(setting => <SettingEditor key={setting.id} setting={setting} save={save} remove={remove} />)}
    </aside>
  )
}

function SettingEditor({ setting, save, remove }: { setting: Setting; save: (setting: Setting) => void; remove: (id: number) => void }) {
  const [local, setLocal] = useState(setting)
  useEffect(() => setLocal(setting), [setting])
  return (
    <div className={!local.active ? 'disabled' : ''}>
      <span><b>{settingNameLabel(local.name, local.type)}</b><small>{scannerTypeLabel(local.type)}</small></span>
      <label>활성 <input type="checkbox" checked={local.active} onChange={event => setLocal({ ...local, active: event.target.checked })} /></label>
      <label>등락 <input type="number" step="0.1" value={local.minChangeRate} onChange={event => setLocal({ ...local, minChangeRate: Number(event.target.value) })} /></label>
      <label>거래량 <input type="number" step="0.1" value={local.minVolumeRatio} onChange={event => setLocal({ ...local, minVolumeRatio: Number(event.target.value) })} /></label>
      <div className="settingActions"><button onClick={() => save(local)}>저장</button><button onClick={() => remove(local.id)}>삭제</button></div>
    </div>
  )
}

export function Phase6App() {
  const [scanner, setScanner] = useState(false)
  return scanner ? <ScannerPage back={() => setScanner(false)} /> : <><button className="scannerLaunch" onClick={() => setScanner(true)}>실시간 레이더</button><Phase4App /></>
}
