/**
 * 백테스트(전략 재생) 화면(BacktestPage)이에요. 저장된 5분봉으로 탐지 설정을 다시 평가하고 가상 탐지 성과를 보여 줘요.
 * 화면 이동과 메뉴는 AppShell.tsx가 맡아요.
 */
import { FormEvent, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import './backtest.css'

// 아래 타입(데이터 모양 정의)들은 백테스트 API 응답 모양이에요.
/** 탐지 설정(스캐너 설정) 하나의 id와 이름이에요. */
type Setting = { id: number; name: string }
/** 설정별 백테스트 통계(탐지 수, 5·30·60분 승률과 평균 수익률, 최대 상승·낙폭)예요. */
type Summary = { settingId: number; settingName: string; scannerType: string; detections: number; winRate5m: number | null; winRate30m: number | null; winRate60m: number | null; averageReturn5m: number | null; averageReturn30m: number | null; averageReturn60m: number | null; averageMaxReturn: number | null; averageMaxDrawdown: number | null }
/** 백테스트 중 다시 재생해서 나온 가상 탐지 한 건과 그 뒤 성과예요. 실제 주문이 아니에요. */
type VirtualDetection = { settingId: number; settingName: string; scannerType: string; detectedAt: string; detectedPrice: number; changeRate: number | null; volumeRatio: number | null; score: number | null; reason: string; performance: { return5m: number | null; return30m: number | null; return60m: number | null; maxReturn: number | null; maxDrawdown: number | null; status: string } }
/** 백테스트 실행 결과 전체(종목, 기간, 평가한 봉 수, 통계, 탐지 목록)예요. */
type Backtest = { stockCode: string; stockName: string; from: string; to: string; evaluatedCandles: number; virtualDetections: number; summaries: Summary[]; detections: VirtualDetection[] }
/** 저장된 5분봉이 있어서 백테스트할 수 있는 종목 정보예요. */
type BacktestableStock = { stockCode: string; stockName: string; market: string; candleCount: number; firstCandleAt: string; lastCandleAt: string }

/**
 * fetch(브라우저 내장 HTTP 요청 함수)로 GET 요청을 보내고 JSON을 돌려주는 비동기(async) 도우미예요.
 * 받는 것: url. 돌려주는 것: 응답 JSON. 실패하면 백테스트용 오류 메시지를 던져요.
 */
async function get<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) throw new Error('백테스트 데이터를 불러오지 못했습니다.')
  return response.json()
}

/**
 * 날짜를 "YYYY-MM-DD" 글자로 바꿔요. 받는 것: date. 돌려주는 것: 날짜 글자.
 * ⚠️ 확인 필요: toISOString은 UTC 기준이라 한국 시간 오전 9시 전에는 하루 전 날짜가 나오는 것으로 보여요.
 */
function dateValue(date: Date) {
  return date.toISOString().slice(0, 10)
}

/** 숫자를 "+1.23%" 같은 퍼센트 글자로 바꿔요. 값이 없으면 "--"를 돌려줘요. */
function pct(value?: number | null) {
  return value == null ? '--' : `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`
}

/** 숫자를 한국식 천 단위 구분 글자(소수 둘째 자리까지)로 바꿔요. 값이 없으면 "--"를 돌려줘요. */
function num(value?: number | null) {
  return value == null ? '--' : value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
}

/**
 * 스캐너 종류 코드(예: VOLUME)를 한국어 이름으로 바꿔요.
 * 받는 것: value(코드). 돌려주는 것: 한국어 이름, 모르는 코드면 그대로.
 */
function scannerTypeLabel(value: string) {
  const labels: Record<string, string> = {
    VOLUME: '거래량 급증',
    PRICE_RISE: '5분 급등',
    MOMENTUM: '복합 모멘텀',
    VOLUME_BREAKOUT: '거래량 돌파',
    TURNOVER_BREAKOUT: '회전율 돌파',
    HIGH_BREAKOUT: '고가 돌파',
    VWAP_BREAKOUT: '평균가 돌파',
    VWAP_RECLAIM: 'VWAP 상단·상승 동시 충족',
    PULLBACK_REBREAK: '동일 봉 VWAP 접촉·고점 상회',
  }
  return labels[value] ?? value
}

/**
 * 탐지 설정의 영어 이름을 한국어 이름으로 바꿔요.
 * 받는 것: name(설정 이름), scannerType(없을 때 대신 쓸 스캐너 종류). 돌려주는 것: 한국어 이름.
 */
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

/**
 * 백테스트 화면 React 컴포넌트(화면 조각)예요. 비유하면 "지난 경기 녹화 다시보기"로, 저장된 5분봉으로 탐지 조건을 다시 돌려 봐요.
 * 받는 것(props): 없음. 화면 이동은 왼쪽 메뉴(AppShell)가 맡아요.
 * 돌려주는 것: 조건 입력 폼, 종목 고르기, 설정별 통계, 가상 탐지 목록 화면.
 */
export function BacktestPage() {
  // 기본 기간은 최근 7일이에요.
  const today = new Date()
  const week = new Date(Date.now() - 7 * 86400000)
  const [stockCode, setStockCode] = useState('005930')
  const [stockSearch, setStockSearch] = useState('')
  const [from, setFrom] = useState(dateValue(week))
  const [to, setTo] = useState(dateValue(today))
  const [settingId, setSettingId] = useState('')
  // params는 "재생"을 눌렀을 때 확정된 조회 조건이에요. 입력 중인 값과 분리해서 누를 때만 다시 조회해요.
  const [params, setParams] = useState({ stockCode, from, to, settingId })
  // useQuery(서버 데이터를 가져와 캐시하는 훅(hook)): 설정 목록, 백테스트 가능 종목, 백테스트 결과예요.
  const settings = useQuery({ queryKey: ['scanner-settings'], queryFn: () => get<Setting[]>('/api/v1/scanner-settings') })
  const backtestStocks = useQuery({ queryKey: ['backtest-stocks'], queryFn: () => get<BacktestableStock[]>('/api/v1/backtests/stocks') })
  const backtest = useQuery({
    queryKey: ['backtest', params],
    // 입력 날짜를 한국 시간(+09:00) 하루 처음과 끝으로 바꿔 서버에 보내요.
    queryFn: () => get<Backtest>(`/api/v1/backtests/run?stockCode=${params.stockCode}&from=${encodeURIComponent(new Date(params.from + 'T00:00:00+09:00').toISOString())}&to=${encodeURIComponent(new Date(params.to + 'T23:59:59+09:00').toISOString())}${params.settingId ? `&settingId=${params.settingId}` : ''}&limit=80`),
    enabled: !!params.stockCode,
  })

  /** 폼 제출 시 입력값을 조회 조건으로 확정해요. 받는 것: event(폼 제출 이벤트). 돌려주는 것: 없음. */
  function submit(event: FormEvent) {
    event.preventDefault()
    setParams({ stockCode: stockCode.trim(), from, to, settingId })
  }

  const data = backtest.data
  // 검색어로 종목 코드·이름을 걸러서 최대 12개만 버튼으로 보여 줘요.
  const stockKeyword = stockSearch.trim().toLowerCase()
  const selectableStocks = (backtestStocks.data ?? [])
    .filter(item => !stockKeyword
      || item.stockCode.includes(stockKeyword)
      || item.stockName.toLowerCase().includes(stockKeyword))
    .slice(0, 12)
  return (
    <div className="backtestPage">
      <header>
        <div>
          <h1>전략 재생</h1>
          <span>저장된 5분봉으로 탐지 설정을 재생하고 가상 탐지 성과를 비교합니다.</span>
        </div>
        <aside><small>가상 탐지</small><b>{data?.virtualDetections ?? 0}</b><span>{data?.evaluatedCandles ?? 0}개 봉</span></aside>
      </header>
      <main>
        <form onSubmit={submit}>
          <label>종목코드<input list="backtestable-stocks" value={stockCode} onChange={event => setStockCode(event.target.value)} />
            <datalist id="backtestable-stocks">
              {backtestStocks.data?.map(item => <option key={item.stockCode} value={item.stockCode}>{item.stockName}</option>)}
            </datalist>
          </label>
          <label>시작일<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
          <label>종료일<input type="date" value={to} onChange={event => setTo(event.target.value)} /></label>
          <label>탐지 설정<select value={settingId} onChange={event => setSettingId(event.target.value)}><option value="">전체 설정</option>{settings.data?.map(item => <option key={item.id} value={item.id}>{settingNameLabel(item.name)}</option>)}</select></label>
          <button>재생</button>
        </form>
        <section className="backtestStockPicker">
          <div>
            <span>
              <small>조회 가능한 종목</small>
              <b>{backtestStocks.data?.length ?? 0}개</b>
            </span>
            <label>종목 검색<input value={stockSearch} onChange={event => setStockSearch(event.target.value)} placeholder="종목명 또는 코드" /></label>
          </div>
          <small>저장된 5분봉이 있는 종목만 표시됩니다. 검색어가 없으면 최근 데이터 기준 상위 12개를 보여줍니다.</small>
          {backtestStocks.isLoading && <p>종목 목록을 불러오는 중...</p>}
          {backtestStocks.error && <p>{backtestStocks.error.message}</p>}
          {!backtestStocks.isLoading && selectableStocks.length === 0 && <p>입력한 조건과 맞는 저장 종목이 없습니다.</p>}
          <div>
            {selectableStocks.map(item => (
              <button
                type="button"
                key={item.stockCode}
                className={stockCode.trim() === item.stockCode ? 'active' : undefined}
                onClick={() => setStockCode(item.stockCode)}
              >
                <b>{item.stockName}</b>
                <small>{item.stockCode} · {item.market} · {item.candleCount.toLocaleString('ko-KR')}봉</small>
              </button>
            ))}
          </div>
        </section>
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

