import { type FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CandleChart } from './CandleChart'
import type { Candle, Quote } from './marketData'
import './phase3.css'

type Stock = { stockCode: string; stockName: string; market: string }
type Item = Stock & { id: number; groupId: number; version: number }
type Group = { id: number; name: string; version: number; items: Item[] }

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { headers: { 'Content-Type': 'application/json' }, ...init })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? '요청을 처리하지 못했습니다.')
  }
  return response.status === 204 ? undefined as T : response.json()
}

function Search({ select }: { select: (stock: Stock) => void }) {
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

function candleUrl(stockCode: string) {
  const to = new Date()
  const from = new Date(to)
  from.setHours(0, 0, 0, 0)
  return `/api/v1/stocks/${stockCode}/candles?timeFrame=5M&from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`
}

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

export function Phase3App() {
  const cache = useQueryClient()
  const [selected, setSelected] = useState<Stock | null>(null)
  const [name, setName] = useState('')
  const lists = useQuery({ queryKey: ['watchlists'], queryFn: () => api<{ groups: Group[] }>('/api/v1/watchlists') })
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
  const refresh = () => cache.invalidateQueries({ queryKey: ['watchlists'] })
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
  const items = lists.data?.groups.flatMap(group => group.items) ?? []
  const error = create.error ?? add.error ?? remove.error

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
          <section className="menuGuide"><h2>사용 안내</h2><p>상단 검색창에서 종목명이나 코드를 검색하고, 관심종목 그룹에 추가합니다. 종목을 선택하면 현재가, 오늘 5분봉, 거래량과 거래대금을 확인할 수 있습니다.</p></section>
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
    </main>
    <footer>데이터는 투자 참고용이며 실제 주문 기능을 제공하지 않습니다.</footer>
  </div>
}
