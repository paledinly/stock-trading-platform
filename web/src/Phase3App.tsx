/**
 * 대시보드 화면(종목 검색, 관심종목, 현재가·5분봉, 실시간 구독 표)을 그리는 파일이에요.
 * React Query(서버 데이터를 가져오고 캐시하는 라이브러리)로 백엔드 API를 부르고, 관심종목 추가·삭제·실시간 고정을 서버에 요청해요.
 * Phase9App(전체 화면 껍데기)이 대시보드 탭에서 이 화면을 보여 줘요.
 */
import { type FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CandleChart } from './CandleChart'
import type { Candle, Quote } from './marketData'
import './phase3.css'

/** 종목 기본 정보(코드, 이름, 시장)의 타입(데이터 모양 정의)이에요. */
type Stock = { stockCode: string; stockName: string; market: string }
/** 관심종목 그룹 안의 한 종목이에요. realtimePinned는 실시간 고정 여부, version은 동시 수정 충돌을 막는 번호예요. */
type Item = Stock & { id: number; groupId: number; realtimePinned: boolean; version: number }
/** 관심종목 그룹 하나와 그 안의 종목 목록이에요. */
type Group = { id: number; name: string; version: number; items: Item[] }
/** 실시간 구독 중인 종목 한 줄이에요. sources는 왜 구독했는지(정밀, 다음날 관측 등) 목록이에요. */
type Subscription = {
  stockCode: string; stockName: string | null; market: string | null; sources: string[]
  state: string | null; stateMessage: string | null; stateUpdatedAt: string | null
  precisionScore: number | null; precisionAddedAt: string | null
}
/** 실시간 구독 전체 현황이에요. limit은 구독 한도, remaining은 남은 자리, precisionState는 정밀 구독 교체 가능 여부예요. */
type Subscriptions = {
  limit: number; remaining: number; items?: Subscription[]
  precisionState?: string | null; precisionCapacity?: number | null
}

// 서버 코드값을 화면용 한국어 이름으로 바꾸는 표예요.
const SOURCE_LABEL: Record<string, string> = {
  PRECISION: '정밀', OVERNIGHT: '다음날 관측', WATCHLIST: '관심 고정', MANUAL: '수동', QUOTE: '화면 조회', CORE: '핵심',
}
const STATE_LABEL: Record<string, string> = {
  SUBSCRIBED: '수신 중', REQUESTED: '요청 중', QUEUED: '연결 대기', FAILED: '실패',
}
const PRECISION_STATE_LABEL: Record<string, string> = { ACTIVE: '교체 가능', FROZEN: '고정됨', DISABLED: '꺼짐' }

/**
 * ISO 시각 문자열을 한국 시간 "시:분"으로 바꿔요.
 * 받는 것: value(시각 문자열 또는 null). 돌려주는 것: "09:10" 같은 글자, 값이 없으면 "-".
 */
function time(value: string | null) {
  return value ? new Date(value).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }) : '-'
}

/**
 * 실시간 구독 종목 표를 보여 주는 React 컴포넌트(화면 조각)예요. 비유하면 "지금 수신 중인 채널을 적어 둔 게시판"이에요.
 * 받는 것(props, 부모가 넘겨주는 값): select(종목을 누르면 상세 영역에 띄우는 함수).
 * 돌려주는 것: 사용량 요약과 구독 종목 표 화면.
 */
function SubscriptionPanel({ select }: { select: (stock: Stock) => void }) {
  // 구독 현황은 30초마다 다시 가져와요.
  const data = useQuery({
    queryKey: ['market-subscriptions'],
    queryFn: () => api<Subscriptions>('/api/v1/market/subscriptions'),
    refetchInterval: 30_000,
  })
  const items = data.data?.items ?? []
  // 특정 출처(예: PRECISION)로 구독된 종목 수를 세요.
  const count = (source: string) => items.filter(item => item.sources.includes(source)).length
  // 사용 중인 구독 수 = 한도 - 남은 자리예요.
  const used = data.data ? data.data.limit - data.data.remaining : 0
  return <section className="panel subscriptions" aria-label="실시간 구독 종목">
    <div className="title"><span><small>KIS 실시간 체결</small><h2>실시간 구독</h2></span>
      <b>{used}</b></div>
    {data.isLoading && <p>불러오는 중…</p>}
    {data.error && <div className="alert">구독 목록을 불러오지 못했습니다.</div>}
    {data.data && <>
      <div className="subscriptionSummary">
        <span><small>사용 / 한도</small><b>{used} / {data.data.limit}</b></span>
        <span><small>정밀</small><b>{count('PRECISION')}{data.data.precisionCapacity != null ? ` / ${data.data.precisionCapacity}` : ''}</b>
          {data.data.precisionState && <em>{PRECISION_STATE_LABEL[data.data.precisionState] ?? data.data.precisionState}</em>}</span>
        <span><small>다음날 관측</small><b>{count('OVERNIGHT')}</b></span>
        <span><small>관심 고정 · 수동</small><b>{count('WATCHLIST')} · {count('MANUAL')}</b></span>
      </div>
      {items.length === 0 ? <div className="empty"><b>구독 중인 종목이 없습니다</b>
        <p>장 시작 후 시장 전체 스캔이 정밀 구독을 채웁니다.</p></div> :
        <table>
          <thead><tr><th>종목</th><th>출처</th><th>상태</th><th>정밀 점수</th><th>정밀 편입</th></tr></thead>
          <tbody>{items.map(item => <tr key={item.stockCode}>
            <td><button onClick={() => select({ stockCode: item.stockCode, stockName: item.stockName ?? item.stockCode,
              market: item.market ?? '' })}><b>{item.stockName ?? item.stockCode}</b><small>{item.stockCode}</small></button></td>
            <td>{item.sources.map(source => <i key={source} className={`source ${source.toLowerCase()}`}>
              {SOURCE_LABEL[source] ?? source}</i>)}</td>
            <td className={`state ${(item.state ?? '').toLowerCase()}`} title={item.stateMessage ?? ''}>
              {item.state ? STATE_LABEL[item.state] ?? item.state : '-'}</td>
            <td>{item.precisionScore != null ? Number(item.precisionScore).toFixed(1) : '-'}</td>
            <td>{time(item.precisionAddedAt)}</td>
          </tr>)}</tbody>
        </table>}
    </>}
  </section>
}

/**
 * fetch(브라우저 내장 HTTP 요청 함수)로 JSON API를 부르는 비동기(async, 기다렸다가 결과를 받는) 도우미예요.
 * 받는 것: url, init(요청 옵션). 돌려주는 것: 응답 JSON, 204(내용 없음)이면 undefined.
 * 실패하면 서버가 준 detail 메시지로 오류를 던져요.
 */
async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { headers: { 'Content-Type': 'application/json' }, ...init })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? '요청을 처리하지 못했습니다.')
  }
  return response.status === 204 ? undefined as T : response.json()
}

/**
 * 상단 종목 검색창이에요. 비유하면 "전화번호부 찾기 창"이에요.
 * 받는 것(props): select(검색 결과를 골랐을 때 부르는 함수). 돌려주는 것: 검색 폼과 결과 목록 화면.
 */
function Search({ select }: { select: (stock: Stock) => void }) {
  // text는 입력 중인 글자, query는 실제로 검색을 보낸 글자예요. 제출할 때만 검색해요.
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const results = useQuery({
    queryKey: ['search', query],
    queryFn: () => api<Stock[]>(`/api/v1/stocks/search?q=${encodeURIComponent(query)}&limit=8`),
    enabled: !!query,
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setQuery(text.trim())
  }
  return <div className="searchBox">
    <form onSubmit={submit}>
      <span>⌕</span>
      <input aria-label="종목 검색" value={text} onChange={event => setText(event.target.value)}
        placeholder="종목명 또는 코드 검색" />
      <button>검색</button>
    </form>
    {query && <div className="searchResults">
      {results.isLoading && <p>검색 중…</p>}
      {results.error && <p>{results.error.message}</p>}
      {results.data?.length === 0 && <p>검색 결과가 없습니다. 종목 마스터 동기화 상태를 확인하세요.</p>}
      {results.data?.map(stock => <button key={stock.stockCode} onClick={() => {
        select(stock)
        setQuery('')
      }}><b>{stock.stockName}</b><small>{stock.stockCode} · {stock.market}</small></button>)}
    </div>}
  </div>
}

/**
 * 오늘 0시부터 지금까지의 5분봉 조회 주소를 만들어요.
 * 받는 것: stockCode(종목코드). 돌려주는 것: 캔들 API 주소 문자열.
 */
function candleUrl(stockCode: string) {
  const to = new Date()
  const from = new Date(to)
  // 브라우저 시간대 기준으로 오늘 0시를 시작점으로 잡아요(한국 시간 PC를 전제로 하는 것으로 보여요).
  from.setHours(0, 0, 0, 0)
  return `/api/v1/stocks/${stockCode}/candles?timeFrame=5M&from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`
}

/**
 * 5분봉 차트 영역(제목, 로딩·오류·빈 상태, 차트, 시작·끝 시각)을 보여 주는 컴포넌트예요. 비유하면 "그래프 액자와 설명표"예요.
 * 받는 것(props): candles(5분봉 목록), loading(불러오는 중 여부), error(오류).
 * 돌려주는 것: 차트 카드 화면.
 */
function MarketChart({ candles, loading, error }: {
  candles: Candle[]
  loading: boolean
  error: Error | null
}) {
  return <div className="chart">
    <div className="chartTitle">
      <span><b>오늘 5분봉</b><small>실제 체결 기반 OHLC</small></span>
      <em>5M · KST</em>
    </div>
    {loading && <div className="chartState">5분봉을 불러오는 중…</div>}
    {error && <div className="chartState">5분봉을 불러오지 못했습니다.</div>}
    {!loading && !error && candles.length === 0 &&
      <div className="chartState">아직 저장된 5분봉이 없습니다.</div>}
    {!loading && !error && candles.length > 0 && <CandleChart candles={candles} />}
    {candles.length > 0 && <p>
      <span>{new Date(candles[0].startTime).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</span>
      <span>{candles.length}개 봉</span>
      <span>{new Date(candles.at(-1)!.startTime).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</span>
    </p>}
  </div>
}

/**
 * 대시보드 전체 화면 컴포넌트예요. 비유하면 "종목 관찰 책상"으로, 관심종목 수첩과 시세 화면, 구독 게시판을 한 책상에 펼쳐 둬요.
 * 받는 것: 없음(props 없음). 돌려주는 것: 헤더, 관심종목, 종목 상세, 실시간 구독 표가 있는 화면.
 */
export function Phase3App() {
  const cache = useQueryClient()
  // 상태(state, 컴포넌트가 기억하는 값): 선택한 종목과 새 그룹 이름이에요.
  const [selected, setSelected] = useState<Stock | null>(null)
  const [name, setName] = useState('')
  const lists = useQuery({ queryKey: ['watchlists'], queryFn: () => api<{ groups: Group[] }>('/api/v1/watchlists') })
  // 현재가와 5분봉은 종목을 고른 뒤에만 조회해요. 같은 캐시 키를 RealtimeBridge가 실시간으로 고쳐 써요.
  const quote = useQuery({
    queryKey: ['quote', selected?.stockCode],
    queryFn: () => api<Quote>(`/api/v1/stocks/${selected!.stockCode}/quote`),
    enabled: !!selected,
  })
  const candles = useQuery({
    queryKey: ['candles', selected?.stockCode],
    queryFn: () => api<Candle[]>(candleUrl(selected!.stockCode)),
    enabled: !!selected,
  })
  // 관심종목이 바뀌면 목록 캐시를 무효화해서 다시 불러오게 해요.
  const refresh = () => cache.invalidateQueries({ queryKey: ['watchlists'] })
  // useMutation(서버 데이터를 바꾸는 요청 훅): 그룹 만들기, 종목 추가·삭제, 실시간 고정 켜기/끄기예요.
  const create = useMutation({
    mutationFn: (value: string) => api('/api/v1/watchlist-groups', { method: 'POST', body: JSON.stringify({ name: value }) }),
    onSuccess: () => { setName(''); refresh() },
  })
  const add = useMutation({
    mutationFn: (groupId: number) => api('/api/v1/watchlists', {
      method: 'POST', body: JSON.stringify({ groupId, stockCode: selected!.stockCode }),
    }),
    onSuccess: refresh,
  })
  const remove = useMutation({
    mutationFn: (id: number) => api(`/api/v1/watchlists/${id}`, { method: 'DELETE' }),
    onSuccess: refresh,
  })
  const realtime = useMutation({
    mutationFn: (item: Item) => api(`/api/v1/watchlists/${item.id}/realtime`, {
      method: 'PATCH', body: JSON.stringify({ enabled: !item.realtimePinned, version: item.version }),
    }),
    onSuccess: refresh,
  })
  const items = lists.data?.groups.flatMap(group => group.items) ?? []
  // 여러 요청 중 먼저 발견된 오류 하나만 화면 위쪽에 보여 줘요.
  const error = create.error ?? add.error ?? remove.error ?? realtime.error

  return <div className="phase3">
    <header>
      <a href="/"><i>주</i><span><b>주식 모니터</b><small>국내주식 모니터링</small></span></a>
      <Search select={setSelected} />
      <em>● KRX 데이터</em>
    </header>
    <main>
      <section className="hero">
        <div><p>3단계 · 종목 모니터링</p><h1>오늘의 시장을<br /><span>한눈에.</span></h1>
          <small>관심 종목을 모으고 현재 흐름을 빠르게 확인하세요.</small></div>
        <aside><small>관심 종목</small><b>{items.length}</b><span>{lists.data?.groups.length ?? 0}개 그룹</span></aside>
      </section>
      {error && <div className="alert">{error.message}</div>}
      <div className="columns">
        <section className="panel watch">
          <section className="menuGuide"><h2>사용 안내</h2><p>관심종목은 목록 저장용입니다. 시세를 계속 받을 종목만 안테나 버튼으로 최대 10개까지 실시간 고정하세요.</p></section>
          <div className="title"><span><small>내 관심종목</small><h2>관심종목</h2></span><b>{items.length}</b></div>
          <form onSubmit={event => {
            event.preventDefault()
            if (name.trim()) create.mutate(name)
          }}><input aria-label="새 그룹 이름" value={name} onChange={event => setName(event.target.value)}
              placeholder="새 그룹 이름" /><button>그룹 추가</button></form>
          {lists.isLoading && <p>불러오는 중…</p>}
          {lists.data?.groups.length === 0 && <div className="empty"><b>첫 관심종목 그룹을 만들어보세요</b>
            <p>검색한 종목을 그룹에 추가할 수 있습니다.</p></div>}
          {lists.data?.groups.map(group => <div className="group" key={group.id}>
            <h3>{group.name}<small>{group.items.length}</small></h3>
            {group.items.map(item => <div className="item" key={item.id}>
              <button onClick={() => setSelected(item)}><i>{item.stockName[0]}</i><span><b>{item.stockName}</b>
                <small>{item.stockCode} · {item.market}</small></span></button>
              <button className={item.realtimePinned ? 'realtimePin active' : 'realtimePin'}
                aria-label={`${item.stockName} 실시간 고정 ${item.realtimePinned ? '해제' : '설정'}`}
                aria-pressed={item.realtimePinned} title={item.realtimePinned ? '실시간 고정 해제' : '실시간 고정'}
                onClick={() => realtime.mutate(item)}>⌁</button>
              <button aria-label={`${item.stockName} 삭제`} onClick={() => remove.mutate(item.id)}>×</button>
            </div>)}
          </div>)}
        </section>
        <section className="panel detail">
          {!selected ? <div className="empty large"><b>종목을 검색해 선택하세요</b>
            <p>상단 검색창에서 종목명이나 코드를 입력할 수 있습니다.</p></div> : <>
            <div className="stockTitle"><span><small>{selected.market} · {selected.stockCode}</small>
              <h2>{selected.stockName}</h2></span>
              {lists.data?.groups.length ? <select aria-label="관심종목 그룹에 추가" defaultValue="" onChange={event => {
                if (event.target.value) add.mutate(Number(event.target.value))
                event.target.value = ''
              }}><option value="">+ 관심종목</option>{lists.data.groups.map(group =>
                <option key={group.id} value={group.id}>{group.name}에 추가</option>)}</select> : null}
            </div>
            {quote.isLoading && <div className="empty">현재가를 불러오는 중…</div>}
            {quote.error && <div className="empty"><b>현재가를 표시할 수 없습니다</b><p>KIS 연동 설정을 확인하세요.</p></div>}
            {quote.data && <>
              <div className="price"><b>{quote.data.currentPrice.toLocaleString()}<small>원</small></b>
                <span className={quote.data.change >= 0 ? 'rise' : 'fall'}>
                  {quote.data.change >= 0 ? '▲' : '▼'} {Math.abs(quote.data.change).toLocaleString()}
                  {' '}({Math.abs(quote.data.changeRate).toFixed(2)}%)
                </span></div>
              <MarketChart candles={candles.data ?? []} loading={candles.isLoading}
                error={candles.error instanceof Error ? candles.error : null} />
              <div className="stats">
                <span><small>시가</small><b>{quote.data.openPrice.toLocaleString()}</b></span>
                <span><small>고가 / 저가</small><b>{quote.data.highPrice.toLocaleString()} / {quote.data.lowPrice.toLocaleString()}</b></span>
                <span><small>거래량</small><b>{quote.data.accumulatedVolume.toLocaleString()}</b></span>
                <span><small>거래대금</small><b>{Math.round(quote.data.accumulatedTradingValue / 1_000_000).toLocaleString()}백만</b></span>
              </div>
            </>}
          </>}
        </section>
      </div>
      <SubscriptionPanel select={setSelected} />
    </main>
    <footer>데이터는 투자 참고용이며 실제 주문 기능을 제공하지 않습니다.</footer>
  </div>
}
