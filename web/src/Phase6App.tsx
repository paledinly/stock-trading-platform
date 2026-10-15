/**
 * 실시간 레이더(장중 탐지 후보) 화면 파일이에요. 스캐너가 포착한 종목을 점수·이유·차트로 보여 주고, 탐지 설정을 고칠 수 있어요.
 * 서버의 탐지 이벤트를 SSE(서버가 브라우저로 계속 밀어 주는 이벤트)로 듣고, 오면 목록을 다시 불러와요.
 * Phase9App(전체 화면 껍데기)이 고급 모드의 "실시간 레이더" 메뉴에서 ScannerPage를 보여 줘요.
 */
import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import './phase6.css'

// 아래 타입(데이터 모양 정의)들은 레이더 API 응답 모양이에요.
/** 스캐너 종류 코드예요. */
type ScannerType = 'VOLUME' | 'PRICE_RISE' | 'MOMENTUM' | 'VOLUME_BREAKOUT' | 'TURNOVER_BREAKOUT' | 'HIGH_BREAKOUT' | 'VWAP_BREAKOUT' | 'VWAP_RECLAIM' | 'PULLBACK_REBREAK'
/** 탐지 한 건이에요. 점수 근거(scoreBreakdown)와 탐지 이유(detectionReason)는 JSON 글자로 와요. */
type Detection = { id: number; eventId: string; scannerType: ScannerType; stockCode: string; stockName: string; market: string; detectedAt: string; detectedPrice: number; fiveMinuteChangeRate: number; volumeRatio: number; currentFiveMinuteVolume: number; currentFiveMinuteTradingValue: number; dailyTradingValue: number; momentumScore: number; opportunityScore?: number; riskScore?: number; scoreVersion?: string; scoreBreakdown?: string; settingName: string; settingSnapshot: string; featureVersion?: string; featureSnapshot?: string; detectionReason?: string }
/** 탐지 설정 하나(최소 등락률, 거래량 배수, 거래대금, 재탐지 대기시간 등)예요. */
type Setting = { id: number; name: string; type: ScannerType; minChangeRate: number; minVolumeRatio: number; minFiveMinuteTradingValue: number; minDailyTradingValue: number; minPrice: number; includeEtf: boolean; cooldownSeconds: number; active: boolean; version: number }
/** 실시간 수신 운영 상태(연결 여부, 수신 체결 수, 오류 수, 구독 수)예요. */
type Status = { realtime?: { connected: boolean; lastTickAt?: string; lastDetectionAt?: string; receivedTicks?: number; parseErrors?: number; processingErrors?: number; featureSnapshots?: number; featureTrackedStocks?: number }; redisStatus?: string; subscriptionCount?: number; subscriptionLimit?: number; subscriptionRemaining?: number }
/** 5분봉 한 개예요. */
type Candle = { startTime: string; open: number; high: number; low: number; close: number; volume: number; tradingValue: number; finalCandle: boolean; revision: number }
/** 사용자가 직접 기록한 거래(투자기록)예요. 차트에 매수·매도 점으로 표시해요. */
type Trade = { id: number; stockCode: string; tradeType: 'BUY' | 'SELL'; tradedAt: string; price: number }
/** 탐지 뒤 5·10·30·60분, 종가까지의 사후 성과예요. */
type Performance = { status: string; return5m?: number; return10m?: number; return30m?: number; return60m?: number; returnClose?: number; maxReturn?: number; maxDrawdown?: number }
/** 탐지 이유 JSON을 풀었을 때의 모양이에요. */
type Reason = { state?: string; vwapDistanceRate?: string; tradeStrength?: string; dayHighDistanceRate?: string }
/** 점수 근거 JSON을 풀었을 때의 모양(기회 요인, 위험 요인)이에요. */
type Breakdown = { opportunityFactors?: Record<string, number>; riskFactors?: Record<string, number> }

// 상단 탭 목록: [스캐너 코드, 화면 이름]이에요.
const tabs = [
  ['MOMENTUM', '복합 모멘텀'],
  ['VOLUME', '거래량 급증'],
  ['PRICE_RISE', '5분 급등'],
  ['VOLUME_BREAKOUT', '거래량 돌파'],
  ['TURNOVER_BREAKOUT', '회전율 돌파'],
  ['HIGH_BREAKOUT', '고가 돌파'],
  ['VWAP_BREAKOUT', '평균가 돌파'],
  ['VWAP_RECLAIM', 'VWAP 상단·상승 동시 충족'],
  ['PULLBACK_REBREAK', '동일 봉 VWAP 접촉·고점 상회'],
] as const

// 새 설정 입력 폼의 기본값이에요.
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

/**
 * fetch(브라우저 내장 HTTP 요청 함수)로 JSON API를 부르는 비동기(async) 도우미예요.
 * 받는 것: url, init(요청 옵션). 돌려주는 것: 응답 JSON, 204(내용 없음)이면 undefined.
 * ⚠️ 확인 필요: ...init을 headers 뒤에 펼쳐서, init에 headers를 넘기면 Content-Type 합친 값이 덮어써지는 것으로 보여요(지금 호출부는 headers를 안 넘겨요).
 */
async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  if (!response.ok) throw new Error('실시간 레이더 데이터를 처리하지 못했습니다.')
  return response.status === 204 ? undefined as T : response.json()
}

/** 설정 객체에서 서버로 보낼 필드만 골라 요청 본문을 만들어요. 받는 것: setting. 돌려주는 것: 요청용 객체. */
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

/** 숫자를 한국식 글자(소수 자리수 고정)로 바꿔요. 받는 것: value, digits(소수 자리). 값이 없으면 "--". */
function num(value: number | undefined, digits = 1) {
  return value == null ? '--' : value.toLocaleString('ko-KR', { maximumFractionDigits: digits, minimumFractionDigits: digits })
}

/** 금액을 반올림해 천 단위 구분 글자로 바꿔요. 값이 없으면 "--". */
function money(value: number | undefined) {
  return value == null ? '--' : Math.round(value).toLocaleString('ko-KR')
}

/** 숫자를 "+1.23%" 같은 퍼센트 글자로 바꿔요. 값이 없으면 "--". */
function pct(value: number | undefined) {
  return value == null ? '--' : `${value >= 0 ? '+' : ''}${num(value, 2)}%`
}

/** JSON 글자를 객체로 풀어요. 받는 것: value. 돌려주는 것: 객체, 비었거나 깨진 글자면 undefined. */
function parseJson<T>(value?: string): T | undefined {
  if (!value) return undefined
  try {
    return JSON.parse(value) as T
  } catch {
    return undefined
  }
}

/** 스캐너 코드를 탭 이름(한국어)으로 바꿔요. 모르는 코드면 그대로 돌려줘요. */
function scannerTypeLabel(type: ScannerType | string) {
  return tabs.find(([key]) => key === type)?.[1] ?? type
}

/** 설정의 영어 이름을 한국어로 바꿔요. 받는 것: name, type(대신 쓸 스캐너 종류). 돌려주는 것: 한국어 이름. */
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

/** 탐지 상태 코드(예: MATCHED)를 한국어로 바꿔요. 값이 없으면 "--". */
function stateLabel(value?: string) {
  if (value === 'MATCHED') return '조건 충족'
  if (value === 'CONDITION_NOT_MET') return '조건 미충족'
  if (value === 'INSUFFICIENT_HISTORY') return '이력 부족'
  if (value === 'BELOW_MIN_PRICE') return '최소 가격 미달'
  if (value === 'BELOW_MIN_5M_VALUE') return '5분 거래대금 미달'
  if (value === 'BELOW_MIN_DAILY_VALUE') return '당일 거래대금 미달'
  return value ?? '--'
}

/** 점수 근거 요인 이름(예: priceMomentum)을 한국어로 바꿔요. */
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

/**
 * 차트 조회 구간을 만들어요. 탐지 시각(없으면 지금) 앞뒤 90분이에요.
 * 받는 것: detection(선택한 탐지). 돌려주는 것: { from, to } ISO 시각 글자.
 */
function candleWindow(detection?: Detection) {
  const at = detection ? new Date(detection.detectedAt).getTime() : Date.now()
  return {
    from: new Date(at - 90 * 60000).toISOString(),
    to: new Date(at + 90 * 60000).toISOString(),
  }
}

/** 탐지 시각 글자를 만들어요. 이력 포함이면 "월/일 시:분", 아니면 "시:분"이에요. */
function detectionTimeLabel(value: string, includeHistory: boolean) {
  const date = new Date(value)
  return includeHistory
    ? date.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

/**
 * 실시간 레이더 화면 React 컴포넌트(화면 조각)예요. 비유하면 "관제실 레이더 화면"으로, 포착된 후보를 목록·상세·설정 세 칸에 보여 줘요.
 * 받는 것(props): 없음. 화면 이동은 왼쪽 메뉴(AppShell)가 맡아요.
 * 돌려주는 것: 탭, 필터, 후보 목록, 상세, 탐지 설정 화면.
 */
export function ScannerPage() {
  const cache = useQueryClient()
  // 상태(state, 컴포넌트가 기억하는 값): 탭, 검색어, 이력 포함 여부, 점수 필터, 선택한 탐지, 새 설정 초안이에요.
  const [tab, setTab] = useState<ScannerType>('MOMENTUM')
  const [search, setSearch] = useState('')
  const [includeHistory, setIncludeHistory] = useState(false)
  const [minOpportunity, setMinOpportunity] = useState(0)
  const [maxRisk, setMaxRisk] = useState(100)
  const [selectedId, setSelectedId] = useState<number>()
  const [draft, setDraft] = useState<Setting>(emptySetting)
  // 탐지 목록: 오늘 것만 볼 때는 30초마다 다시 가져오고, 이력 포함이면 자동 갱신을 꺼요.
  const detections = useQuery({
    queryKey: ['detections', tab, includeHistory],
    queryFn: () => request<Detection[]>(`/api/v1/scanner-detections?type=${tab}&limit=100&todayOnly=${!includeHistory}`),
    staleTime: 30000,
    refetchOnWindowFocus: false,
    refetchInterval: includeHistory ? false : 30000,
  })
  const selected = useQuery({
    queryKey: ['detection', selectedId],
    queryFn: () => request<Detection>(`/api/v1/scanner-detections/${selectedId}`),
    enabled: selectedId != null,
  })
  // 상세 응답이 오기 전에는 목록에 있던 값으로 먼저 보여 줘요.
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
  // 'window'라는 이름이 브라우저 전역 window를 이 함수 안에서 가려요(동작에는 문제 없어 보여요).
  const window = candleWindow(detail)
  const candles = useQuery({
    queryKey: ['radar-candles', detail?.stockCode, window.from, window.to],
    queryFn: () => request<Candle[]>(`/api/v1/stocks/${detail?.stockCode}/candles?timeFrame=5M&from=${encodeURIComponent(window.from)}&to=${encodeURIComponent(window.to)}`),
    enabled: !!detail?.stockCode,
    retry: false,
  })
  // useMutation(서버 데이터를 바꾸는 요청 훅(hook)): 설정 저장·생성·삭제 뒤 설정 목록 캐시를 무효화해요.
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
  // 검색어·점수 필터로 거르고, 기회점수 높은 순 → 위험점수 낮은 순으로 정렬해요. useMemo로 값이 바뀔 때만 다시 계산해요.
  const filtered = useMemo(() => detections.data?.filter(item => {
    const text = `${item.stockName} ${item.stockCode}`.toLowerCase()
    return text.includes(search.toLowerCase()) && (item.opportunityScore ?? 0) >= minOpportunity && (item.riskScore ?? 0) <= maxRisk
  }).sort((left, right) => (right.opportunityScore ?? 0) - (left.opportunityScore ?? 0) || (left.riskScore ?? 0) - (right.riskScore ?? 0)) ?? [], [detections.data, search, minOpportunity, maxRisk])

  // EventSource(SSE 연결 객체)로 탐지 이벤트를 듣고, 오면 탐지 목록과 운영 상태를 다시 불러오게 해요.
  // ⚠️ 확인 필요: main.tsx의 RealtimeBridge도 같은 /api/v1/stream을 열고 있어서, 이 화면에 있는 동안 SSE 연결이 2개가 되는 것으로 보여요.
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

  // 아직 아무것도 고르지 않았으면 첫 번째 후보를 자동으로 선택해요.
  useEffect(() => {
    if (selectedId == null && filtered[0]) setSelectedId(filtered[0].id)
  }, [filtered, selectedId])

  return (
    <div className="scannerPage">
      <header>
        <div>
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

/**
 * 실시간 수신 운영 상태 카드예요. 비유하면 "관제실 상태 표시등"이에요.
 * 받는 것(props): status(운영 상태). 돌려주는 것: 연결 여부, 구독 수, 수신·오류 수 카드.
 */
function StatusCard({ status }: { status?: Status }) {
  const realtime = status?.realtime
  return (
    <aside className="opsCard">
      <small>운영 상태</small>
      <b className={realtime?.connected ? 'statusOk' : 'statusIdle'}>{realtime?.connected ? '연결됨' : '대기'}</b>
      <span>구독 {status?.subscriptionCount ?? 0}/{status?.subscriptionLimit ?? 0}</span>
      <span>실시간 캐시 {status?.redisStatus ?? '--'}</span>
      <span>수신 체결 {(realtime?.receivedTicks ?? 0).toLocaleString('ko-KR')}</span>
      <span>파싱 오류 {(realtime?.parseErrors ?? 0).toLocaleString('ko-KR')} · 처리 오류 {(realtime?.processingErrors ?? 0).toLocaleString('ko-KR')}</span>
    </aside>
  )
}

/**
 * 탐지 후보 목록이에요. 비유하면 "포착된 대상 명단"이에요.
 * 받는 것(props): items(후보), loading, error, selectedId(선택된 id), select(선택 함수), includeHistory(이력 포함 여부).
 * 돌려주는 것: 누를 수 있는 후보 버튼 목록 화면.
 */
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

/**
 * 선택한 탐지의 상세 영역이에요. 비유하면 "대상 하나를 확대해 보는 돋보기 창"이에요.
 * 받는 것(props): detection, candles(주변 5분봉), trades(내 거래 기록), performance(사후 성과).
 * 돌려주는 것: 차트, 점수 게이지, 탐지 이유, 점수 근거 화면.
 */
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

/**
 * 탐지 주변 5분봉을 SVG(벡터 그림)로 직접 그리는 작은 차트예요. 비유하면 "현장 약도"로, 탐지 위치·평균가선·내 매매 점을 찍어요.
 * 받는 것(props): detection, candles, trades, vwap(거래량 가중 평균가). 돌려주는 것: SVG 차트 화면.
 */
function RadarChart({ detection, candles, trades, vwap }: { detection: Detection; candles: Candle[]; trades: Trade[]; vwap?: number }) {
  // 봉 데이터가 없으면 탐지가격 하나로 임시 봉을 만들어 빈 차트를 피해요.
  const data = candles.length ? candles : [{ startTime: detection.detectedAt, open: detection.detectedPrice, high: detection.detectedPrice, low: detection.detectedPrice, close: detection.detectedPrice, volume: 0, tradingValue: 0, finalCandle: false, revision: 0 }]
  const firstTime = new Date(data[0].startTime).getTime()
  const lastTime = new Date(data[data.length - 1].startTime).getTime()
  const visibleTrades = trades.filter(trade => {
    const tradedAt = new Date(trade.tradedAt).getTime()
    return tradedAt >= firstTime && tradedAt <= lastTime
  })
  // 화면에 그릴 모든 가격을 모아 세로 축의 최솟값·최댓값을 정해요.
  const prices = data.flatMap(candle => [candle.high, candle.low, vwap ?? candle.close, detection.detectedPrice, ...visibleTrades.map(trade => trade.price)])
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  const range = Math.max(1, max - min)
  // x, y는 시각·가격을 SVG 좌표로 바꾸는 함수예요.
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

/** 0~100 점수를 막대로 보여 주는 게이지예요. 받는 것: label, value, tone(색 계열). 돌려주는 것: 점수 막대 화면. */
function ScoreGauge({ label, value, tone }: { label: string; value?: number; tone: 'hot' | 'cold' | 'neutral' }) {
  const width = Math.max(0, Math.min(100, value ?? 0))
  return <article><span>{label}</span><b className={tone}>{num(value)}</b><i><em style={{ width: `${width}%` }} /></i></article>
}

/** 제목과 값 한 쌍을 보여 주는 작은 칸이에요. 받는 것: title, value. */
function Fact({ title, value }: { title: string; value: string }) {
  return <article><small>{title}</small><b>{value}</b></article>
}

/** 점수 근거 요인 목록이에요. 받는 것: title, factors(요인 이름 → 점수). 돌려주는 것: 요인별 점수 목록 화면. */
function FactorList({ title, factors }: { title: string; factors?: Record<string, number> }) {
  const rows = Object.entries(factors ?? {})
  return <div className="factorList"><h3>{title}</h3>{rows.length === 0 ? <p>근거 데이터 대기</p> : rows.map(([key, value]) => <span key={key}><small>{factorLabel(key)}</small><b>{num(value)}</b></span>)}</div>
}

/**
 * 탐지 설정 패널이에요. 비유하면 "레이더 감도 조절판"으로, 새 설정을 만들고 기존 설정을 고쳐요.
 * 받는 것(props): settings, draft(새 설정 초안), setDraft, save, create, remove.
 * 돌려주는 것: 새 설정 폼과 설정 편집기 목록 화면.
 */
function SettingPanel({ settings, draft, setDraft, save, create, remove }: { settings: Setting[]; draft: Setting; setDraft: (setting: Setting) => void; save: (setting: Setting) => void; create: (setting: Setting) => void; remove: (id: number) => void }) {
  /** 새 설정 폼 제출 시 생성 요청을 보내요. 받는 것: event. 돌려주는 것: 없음. */
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

/**
 * 기존 설정 하나의 편집 줄이에요. 저장을 누르기 전까지는 local(화면 안 사본)만 바뀌어요.
 * 받는 것(props): setting, save, remove. 돌려주는 것: 활성·등락·거래량 입력과 저장·삭제 버튼 화면.
 */
function SettingEditor({ setting, save, remove }: { setting: Setting; save: (setting: Setting) => void; remove: (id: number) => void }) {
  const [local, setLocal] = useState(setting)
  // 서버에서 새 설정이 오면 화면 사본을 다시 맞춰요(저장 안 한 입력은 사라져요).
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
