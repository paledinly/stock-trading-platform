import { FormEvent, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Phase3App } from './Phase3App'
import { JournalPage } from './Phase4App'
import { ScannerPage } from './Phase6App'
import { AnalyticsPage } from './Phase7App'
import { MarketWidePage } from './Phase8App'
import './phase9.css'

type Setting = { id: number; name: string }
type Summary = { settingId: number; settingName: string; scannerType: string; detections: number; winRate5m: number | null; winRate30m: number | null; winRate60m: number | null; averageReturn5m: number | null; averageReturn30m: number | null; averageReturn60m: number | null; averageMaxReturn: number | null; averageMaxDrawdown: number | null }
type VirtualDetection = { settingId: number; settingName: string; scannerType: string; detectedAt: string; detectedPrice: number; changeRate: number | null; volumeRatio: number | null; score: number | null; reason: string; performance: { return5m: number | null; return30m: number | null; return60m: number | null; maxReturn: number | null; maxDrawdown: number | null; status: string } }
type Backtest = { stockCode: string; stockName: string; from: string; to: string; evaluatedCandles: number; virtualDetections: number; summaries: Summary[]; detections: VirtualDetection[] }

async function get<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) throw new Error('백테스트 데이터를 불러오지 못했습니다.')
  return response.json()
}

function dateValue(date: Date) {
  return date.toISOString().slice(0, 10)
}

function pct(value?: number | null) {
  return value == null ? '--' : `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
}

function num(value?: number | null) {
  return value == null ? '--' : value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

function scannerTypeLabel(value: string) {
  const labels: Record<string, string> = {
    VOLUME: '거래량 급증',
    PRICE_RISE: '5분 급등',
    MOMENTUM: '복합 모멘텀',
    VOLUME_BREAKOUT: '거래량 돌파',
    TURNOVER_BREAKOUT: '회전율 돌파',
    HIGH_BREAKOUT: '고가 돌파',
    VWAP_BREAKOUT: '평균가 돌파',
    VWAP_RECLAIM: '평균가 회복',
    PULLBACK_REBREAK: '눌림 후 재돌파',
  }
  return labels[value] ?? value
}

function settingNameLabel(name: string, scannerType?: string) {
  const labels: Record<string, string> = {
    Momentum: '복합 모멘텀',
    'Volume Breakout': '거래량 돌파',
    'Turnover Breakout': '회전율 돌파',
    'High Breakout': '고가 돌파',
    'VWAP Breakout': '평균가 돌파',
    'VWAP Reclaim': '평균가 회복',
    'Pullback Rebreak': '눌림 후 재돌파',
  }
  return labels[name] ?? (scannerType ? scannerTypeLabel(scannerType) : name)
}

export function BacktestPage({ back }: { back: () => void }) {
  const today = new Date()
  const week = new Date(Date.now() - 7 * 86400000)
  const [stockCode, setStockCode] = useState('005930')
  const [from, setFrom] = useState(dateValue(week))
  const [to, setTo] = useState(dateValue(today))
  const [settingId, setSettingId] = useState('')
  const [params, setParams] = useState({ stockCode, from, to, settingId })
  const settings = useQuery({ queryKey: ['scanner-settings'], queryFn: () => get<Setting[]>('/api/v1/scanner-settings') })
  const backtest = useQuery({
    queryKey: ['backtest', params],
    queryFn: () => get<Backtest>(`/api/v1/backtests/run?stockCode=${params.stockCode}&from=${encodeURIComponent(new Date(params.from + 'T00:00:00+09:00').toISOString())}&to=${encodeURIComponent(new Date(params.to + 'T23:59:59+09:00').toISOString())}${params.settingId ? `&settingId=${params.settingId}` : ''}&limit=80`),
    enabled: !!params.stockCode,
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setParams({ stockCode: stockCode.trim(), from, to, settingId })
  }

  const data = backtest.data
  return (
    <div className="backtestPage">
      <header>
        <button onClick={back}>← 대시보드</button>
        <div>
          <p>8단계 · 백테스트</p>
          <h1>전략 재생</h1>
          <span>저장된 5분봉으로 탐지 설정을 재생하고 가상 탐지 성과를 비교합니다.</span>
        </div>
        <aside><small>가상 탐지</small><b>{data?.virtualDetections ?? 0}</b><span>{data?.evaluatedCandles ?? 0}개 봉</span></aside>
      </header>
      <main>
        <form onSubmit={submit}>
          <label>종목코드<input value={stockCode} onChange={event => setStockCode(event.target.value)} /></label>
          <label>시작일<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
          <label>종료일<input type="date" value={to} onChange={event => setTo(event.target.value)} /></label>
          <label>탐지 설정<select value={settingId} onChange={event => setSettingId(event.target.value)}><option value="">전체 설정</option>{settings.data?.map(item => <option key={item.id} value={item.id}>{settingNameLabel(item.name)}</option>)}</select></label>
          <button>재생</button>
        </form>
        <section className="menuGuide">
          <h2>사용 안내</h2>
          <p>종목코드와 기간을 입력하고 재생을 누르면 저장된 5분봉으로 탐지 조건을 다시 평가합니다. 설정별 전략 통계에서 승률과 평균 수익률을 보고, 가상 탐지 목록에서 개별 신호 결과를 확인합니다.</p>
        </section>
        {backtest.error && <div className="backtestEmpty">{backtest.error.message}</div>}
        <section className="backtestSummary">
          <article><small>종목</small><b>{data ? `${data.stockName} ${data.stockCode}` : '--'}</b></article>
          <article><small>평가 봉</small><b>{data?.evaluatedCandles ?? 0}</b></article>
          <article><small>가상 탐지</small><b>{data?.virtualDetections ?? 0}</b></article>
          <article><small>설정 수</small><b>{data?.summaries.length ?? 0}</b></article>
        </section>
        <section className="backtestGrid">
          <div className="summaryPanel">
            <h2>설정별 전략 통계</h2>
            {data?.summaries.length === 0 && <div className="backtestEmpty">전략 통계가 없습니다</div>}
            {data?.summaries.map(row => <article key={row.settingId}><span><b>{settingNameLabel(row.settingName, row.scannerType)}</b><small>{scannerTypeLabel(row.scannerType)} · {row.detections}건 탐지</small></span><dl><span><dt>5분 승률</dt><dd>{pct(row.winRate5m)}</dd></span><span><dt>30분 평균</dt><dd className={(row.averageReturn30m ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(row.averageReturn30m)}</dd></span><span><dt>60분 평균</dt><dd className={(row.averageReturn60m ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(row.averageReturn60m)}</dd></span><span><dt>최대낙폭</dt><dd>{pct(row.averageMaxDrawdown)}</dd></span></dl></article>)}
          </div>
          <div className="virtualPanel">
            <h2>가상 탐지 목록</h2>
            {backtest.isLoading && <div className="backtestEmpty">재생 중...</div>}
            {data?.detections.length === 0 && <div className="backtestEmpty">가상 탐지가 없습니다</div>}
            {data?.detections.map((item, index) => <article key={`${item.settingId}-${item.detectedAt}-${index}`}><div><span><b>{settingNameLabel(item.settingName, item.scannerType)}</b><small>{new Date(item.detectedAt).toLocaleString('ko-KR')} · {scannerTypeLabel(item.scannerType)}</small></span><strong>{Math.round(item.detectedPrice).toLocaleString('ko-KR')}원</strong></div><dl><span><dt>등락</dt><dd>{pct(item.changeRate)}</dd></span><span><dt>거래량</dt><dd>{num(item.volumeRatio)}배</dd></span><span><dt>30분</dt><dd className={(item.performance.return30m ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.performance.return30m)}</dd></span><span><dt>60분</dt><dd className={(item.performance.return60m ?? 0) >= 0 ? 'gain' : 'loss'}>{pct(item.performance.return60m)}</dd></span></dl></article>)}
          </div>
        </section>
      </main>
    </div>
  )
}

export function Phase9App() {
  const [page, setPage] = useState<'dashboard' | 'journal' | 'scanner' | 'analytics' | 'wide' | 'backtest'>('dashboard')
  const goDashboard = () => setPage('dashboard')
  return (
    <>
      <nav className="workspaceNav" aria-label="주요 메뉴">
        <button className={`backtestLaunch ${page === 'backtest' ? 'active' : ''}`} onClick={() => setPage('backtest')}>백테스트</button>
        <button className={`wideLaunch ${page === 'wide' ? 'active' : ''}`} onClick={() => setPage('wide')}>시장 전체</button>
        <button className={`analyticsLaunch ${page === 'analytics' ? 'active' : ''}`} onClick={() => setPage('analytics')}>성과 분석</button>
        <button className={`scannerLaunch ${page === 'scanner' ? 'active' : ''}`} onClick={() => setPage('scanner')}>실시간 레이더</button>
        <span className="phaseNav">
          <button className={page === 'dashboard' ? 'active' : ''} onClick={() => setPage('dashboard')}>대시보드</button>
          <button className={page === 'journal' ? 'active' : ''} onClick={() => setPage('journal')}>투자기록</button>
        </span>
      </nav>
      {page === 'dashboard' && <Phase3App />}
      {page === 'journal' && <JournalPage />}
      {page === 'scanner' && <ScannerPage back={goDashboard} />}
      {page === 'analytics' && <AnalyticsPage back={goDashboard} />}
      {page === 'wide' && <MarketWidePage back={goDashboard} />}
      {page === 'backtest' && <BacktestPage back={goDashboard} />}
    </>
  )
}
