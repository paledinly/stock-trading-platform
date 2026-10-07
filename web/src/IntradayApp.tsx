import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import './intraday.css'

type Features = { values: Record<string, number>; vwapState: string; marketRegime: string; unavailable: string[] }
type Signal = { id: string; stockCode: string; stockName: string; setup: string; session: string; sourceAt: string; receivedAt: string; recommendedAt: string; expiresAt: string; price: number; score: number; features: Features; reasons: string[]; risks: string[]; plan: { entryFrom: number; entryTo: number; chaseLimit: number; stop: number; target1: number; target2: number; riskReward: number; invalidCondition: string } }
type Outcome = { status: string; entryAt: string | null; entryPrice: number | null; exitAt: string | null; exitPrice: number | null; grossReturn: number | null; netReturn: number | null; mfe: number; mae: number; tradeMfe: number | null; tradeMae: number | null; targetBeforeStop: boolean | null; currentPrice: number | null; currentPriceAt: string | null; closeReturn: number | null; quality: string[]; failureFactors: string[]; returns: Record<string, { returnPercent: number; observedAt: string }> }
type Result = { signal: Signal; outcome: Outcome }
type Stats = { recommendations: number; trades: number; unresolved: number; winRate: number | null; averageNetReturn: number | null; medianNetReturn: number | null; profitFactor: number | null; mfe: number | null; mae: number | null; targetBeforeStopRate: number | null; equalNotionalDrawdown: number | null; consecutiveLosses: number; averageHoldingMinutes: number | null }
type Analytics = Record<string, Record<string, Stats>>
type Status = { enabled: boolean; costsConfigured: boolean; mode: string; queued: number; dropped: number; error: string | null; candidates: { stockCode: string; stockName: string; stage: string; reason: string; evaluatedAt: string }[] }
async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) throw new Error('장중 연구 데이터를 불러오지 못했습니다.')
  return response.json()
}
const num = (n?: number | null) => n == null ? '자료 없음' : n.toLocaleString('ko-KR', { maximumFractionDigits: 2 })
const time = (s?: string | null) => s ? new Date(s).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '자료 없음'
const labels: Record<string, string> = {
  BREAKOUT: '돌파', PULLBACK: '눌림목', RE_BREAKOUT: '재돌파', ENTRY_READY: '진입 조건 충족', ENTERED: '가상 진입',
  STOPPED: '손절 먼저 도달', TARGET_HIT: '목표 먼저 도달', EXPIRED: '진입 만료', INVALIDATED: '진입 무효',
  TIME_EXIT: '시간 청산', UNRESOLVED: '관측 부족·판정 불가', SCANNED: '스캔', WATCHLIST: '관찰', SETUP_FORMING: '패턴 형성 중',
  TRAILING_EXIT: '추적 손절 청산', RISK_DATA_UNAVAILABLE: '필수 위험정보 미확보', VWAP_UNAVAILABLE: '누적 VWAP 미확보',
  HISTORY_CAPACITY_WARMUP_RESET: '관측 버퍼 상한·워밍업 재시작', RECOMMENDED: '추천 생성',
  COSTS_NOT_CONFIGURED: '비용 미반영 · Net 수익률·비용 포함 R:R 미산정', WARMUP_30M_REQUIRED: '연속 30분 관측 대기', LOW_LIQUIDITY: '유동성 부족',
  FLOW_CONFIRMATION_PENDING: '거래량·대금 확인 대기', ORDERED_SETUP_PENDING: '시간순 패턴 확인 대기',
  ACTIVE_OR_COOLDOWN: '기존 추천 진행·재추천 대기', NET_RISK_REWARD_INSUFFICIENT: '비용 포함 R:R 부족',
  CHASE_OR_ORDER_FLOW_REVERSAL: '추격·체결강도 반전 위험', OUTSIDE_RECOMMENDATION_SESSION: '추천 시간 밖',
  STALE_OR_LOST_INPUT: '지연·누락 입력', UNIVERSE_EXCLUDED: '거래대상 제외', UPPER_WICK: '긴 윗꼬리',
  DAY_RETURN_PREVIOUS_CLOSE: '전일종가 대비 상승률 미확보', SAME_TIME_RVOL: '과거 동일 시간대 거래량 미확보',
  KOSPI_KOSDAQ_SECTOR_RS: '지수·업종 상대강도 미확보', MARKET_REGIME: '시장 국면 미확보', BID_ASK_SPREAD: '실측 호가 간격 미확보',
  ORDER_IMBALANCE: '호가 불균형 미확보', VI_STATE_COUNT: 'VI 상태·횟수 미확보', PRICE_LIMIT_DISTANCE: '상하한가 거리 미확보',
  INVESTMENT_WARNING: '투자위험 상태 미확보', LISTING_AGE: '상장 경과일 미확보', UNVALIDATED_RESEARCH_SCORE: '점수 수익성 미검증',
  SPREAD_IS_COST_ASSUMPTION_NOT_OBSERVED: '호가 간격은 실측이 아닌 비용 가정', ORDER_FLOW_UNAVAILABLE: '체결강도 미확보',
  OBSERVATION_GAP_OR_DELAY: '관측 공백·지연·누적값 역전', TRADING_HALTED: '거래정지', CLOSE_PRICE_MISSING: '장마감 체결 미확보',
  NO_EXECUTABLE_EXIT: '가상 청산 체결 미확보', NO_EXECUTABLE_TIME_EXIT: '보유기한 내 가상 청산 체결 미확보',
  FAILURE_FACTORS_ARE_DESCRIPTIVE_NOT_CAUSAL: '실패 태그는 관측 설명이며 원인 확정이 아님',
  FAKE_BREAKOUT: '돌파 후 손절선 이탈', SUPPORT_LOSS: '지지선 이탈', VWAP_LOSS: 'VWAP 이탈', ORDER_FLOW_REVERSAL: '체결강도 반전',
}
const label = (s: string) => labels[s] ?? s

function Statistics({ data }: { data?: Analytics }) {
  return <>{['setup', 'timeBand', 'scoreBand', 'marketRegime'].map(group => <section key={group}>
    <h2>{{ setup: 'Setup별 성과', timeBand: '시간대별 성과', scoreBand: '점수 구간별 성과', marketRegime: '시장 국면별 성과' }[group]}</h2>
    <div className="intradayTable"><table><thead><tr><th>구간</th><th>Net 표본/추천</th><th>승률 %</th><th>평균 Net %</th><th>중앙 Net %</th><th>PF</th><th>MFE / MAE %</th><th>보유 분</th><th>목표 먼저 %</th><th>낙폭 / 연패</th></tr></thead>
      <tbody>{Object.entries(data?.[group] ?? {}).map(([key, s]) => <tr key={key}><td>{label(key)}</td><td>{s.trades}/{s.recommendations}</td><td>{num(s.winRate)}</td><td>{num(s.averageNetReturn)}</td><td>{num(s.medianNetReturn)}</td><td>{num(s.profitFactor)}</td><td>{num(s.mfe)} / {num(s.mae)}</td><td>{num(s.averageHoldingMinutes)}</td><td>{num(s.targetBeforeStopRate)}</td><td>{num(s.equalNotionalDrawdown)} / {s.consecutiveLosses}</td></tr>)}</tbody></table></div>
  </section>)}</>
}

type ExitReport = { mode: string; commonCompletedCount: number; comparisons: Record<string, {
  missedBaselineWinners: number; cohorts: Record<string, number>; commonCompleted: { count: number; averageGrossReturn: number | null; targetHitRate: number | null; net: Stats }
}> }
function ExitComparison({ date }: { date: string }) {
  const [stop, setStop] = useState('1')
  const [target, setTarget] = useState('1')
  const [protect, setProtect] = useState('')
  const [floor, setFloor] = useState('0')
  const [advanced, setAdvanced] = useState(false)
  const [minimumStop, setMinimumStop] = useState('0')
  const [narrowMode, setNarrowMode] = useState('WIDEN')
  const [entryMode, setEntryMode] = useState('IMMEDIATE')
  const [holdSeconds, setHoldSeconds] = useState('10')
  const [retestTolerance, setRetestTolerance] = useState('0.1')
  const [partialAt, setPartialAt] = useState('')
  const [partialFraction, setPartialFraction] = useState('0.5')
  const [trail, setTrail] = useState('')
  const [stopLimit, setStopLimit] = useState('0')
  const [freshStructure, setFreshStructure] = useState(false)
  const [grid, setGrid] = useState('')
  function experimentOptions() {
    const tickSizes: Record<string, number> = {}
    for (const item of grid.split(',').map(x => x.trim()).filter(Boolean)) {
      const parts = item.split('=')
      if (parts.length !== 2 || !/^[A-Za-z0-9]{6}$/.test(parts[0].trim()) || !Number.isFinite(Number(parts[1])) || Number(parts[1]) <= 0)
        throw new Error('가격 단위는 종목코드=단위 형식으로 입력하세요. 예: 005930=100')
      tickSizes[parts[0].trim()] = Number(parts[1])
    }
    return { tickSizes, minimumStopPercent: Number(minimumStop), narrowStopMode: narrowMode, entryMode,
      holdSeconds: entryMode === 'HOLD' ? Number(holdSeconds) : 0,
      retestTolerancePercent: entryMode === 'RETEST' ? Number(retestTolerance) : null,
      maxStoppedPerSymbol: Number(stopLimit), requireFreshStructureAfterStop: freshStructure,
      partialAtPercent: partialAt === '' ? null : Number(partialAt), partialFraction: partialAt === '' ? null : Number(partialFraction),
      trailingDistancePercent: trail === '' ? null : Number(trail) }
  }
  const comparison = useMutation({ mutationFn: () => api<ExitReport>(`/api/v1/intraday/exit-comparison?date=${date}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ variants: [{
      name: 'EXPERIMENT', stopDistanceMultiplier: Number(stop), targetDistanceMultiplier: Number(target),
      protectAtPercent: protect === '' ? null : Number(protect), protectFloorPercent: protect === '' ? null : Number(floor),
      ...(advanced ? { options: experimentOptions() } : {}),
    }] }),
  }) })
  return <section><h2>진입·청산 비교 실험</h2>
    <p>기본은 동일 진입 청산 비교이며, 진입 확인·제외 조건을 켜면 후보를 고정한 진입 변경 실험입니다. 배수 1은 기존 거리입니다. 운영 설정과 저장된 성과는 변경하지 않습니다.</p>
    <form onSubmit={e => { e.preventDefault(); comparison.mutate() }}>
      <label>손절 거리 배수 <input required type="number" min="0.01" step="any" value={stop} onChange={e => setStop(e.target.value)} /></label>{' '}
      <label>목표 거리 배수 <input required type="number" min="0.01" step="any" value={target} onChange={e => setTarget(e.target.value)} /></label>{' '}
      <label>수익 보호 시작 % (선택) <input type="number" min="0.01" step="any" value={protect} onChange={e => setProtect(e.target.value)} /></label>{' '}
      <label>보호선 % <input required={protect !== ''} type="number" min="0" step="any" value={floor} onChange={e => setFloor(e.target.value)} /></label>{' '}
      <p><label><input type="checkbox" checked={advanced} onChange={e => setAdvanced(e.target.checked)} /> 추가 실험 사용</label></p>
      {advanced && <fieldset><legend>가격·진입·부분 청산·재진입 조건</legend>
        <p><label>최소 손절폭 % <input required type="number" min="0" max="99" step="any" value={minimumStop} onChange={e => setMinimumStop(e.target.value)} /></label>{' '}
          <label>좁은 손절 처리 <select value={narrowMode} onChange={e => setNarrowMode(e.target.value)}><option value="WIDEN">최소 폭까지 확대</option><option value="FILTER">후보 제외</option></select></label></p>
        <p><label>돌파 진입 조건 <select value={entryMode} onChange={e => setEntryMode(e.target.value)}><option value="IMMEDIATE">기존 진입</option><option value="HOLD">돌파선 위 유지</option><option value="RETEST">돌파선 재접근 후 회복</option></select></label>{' '}
          {entryMode === 'HOLD' && <label>유지 시간 (초) <input required type="number" min="1" max="3600" value={holdSeconds} onChange={e => setHoldSeconds(e.target.value)} /></label>}
          {entryMode === 'RETEST' && <label>재접근 허용 폭 % <input required type="number" min="0.001" step="any" value={retestTolerance} onChange={e => setRetestTolerance(e.target.value)} /></label>}</p>
        <p><label>부분 익절 시작 % <input type="number" min="0.001" step="any" value={partialAt} onChange={e => setPartialAt(e.target.value)} /></label>{' '}
          <label>부분 청산 비율 (0~1) <input required={partialAt !== ''} type="number" min="0.001" max="0.999" step="any" value={partialFraction} onChange={e => setPartialFraction(e.target.value)} /></label>{' '}
          <label>추적 손절 거리 % <input type="number" min="0.001" step="any" value={trail} onChange={e => setTrail(e.target.value)} /></label></p>
        <p>추적 손절은 수익 보호 시작 또는 부분 익절 후 작동합니다. 부분 익절선은 최종 목표선보다 낮아야 합니다.</p>
        <p><label>종목별 손절 횟수 제한 (0은 제한 없음) <input required type="number" min="0" max="100" value={stopLimit} onChange={e => setStopLimit(e.target.value)} /></label>{' '}
          <label><input type="checkbox" checked={freshStructure} onChange={e => setFreshStructure(e.target.checked)} /> 손절 후 더 높은 돌파선·강한 거래량과 거래대금 확인</label></p>
        <p><label>종목별 가격 단위 (선택) <input value={grid} placeholder="005930=100,000660=500" onChange={e => setGrid(e.target.value)} /></label></p>
        <p>가격 단위는 직접 확인한 연구 가정입니다. 입력하면 미등록 종목은 판정 불가로 남습니다. 빈 값이면 가격 반올림을 적용하지 않습니다.</p>
      </fieldset>}
      <button disabled={comparison.isPending}>{comparison.isPending ? '비교 중…' : '선택일 청산 비교'}</button>
    </form>
    <p>당시 비용을 사용하며 미설정이면 Net은 자료 없음입니다. 보호선은 진입 fill 기준이며 다음 관측 체결에서 청산하므로 보호 수익을 보장하지 않습니다.</p>
    {comparison.error && <p role="alert">{comparison.error.message}</p>}
    {comparison.data && <><p>{comparison.data.mode === 'FIXED_CANDIDATE_ENTRY_EXIT_COMPARISON' ? '후보 고정·진입 변경 실험' : '동일 진입 청산 비교'}</p><p>모든 비교안에서 판정 가능한 공통 완료 표본: {comparison.data.commonCompletedCount}건 · 마지막 실행 결과</p>
      <div className="intradayTable"><table><thead><tr><th>비교안</th><th>공통 표본</th><th>평균 Gross %</th><th>Net 표본</th><th>평균 Net %</th><th>잔여 물량 목표 먼저 %</th><th>판정 불가</th><th>진행 중</th><th>미진입</th><th>놓친 기존 수익 거래</th></tr></thead>
        <tbody>{Object.entries(comparison.data.comparisons).map(([name, group]) => <tr key={name}>
          <td>{name === 'BASELINE' ? '기존 청산' : '실험 청산'}</td><td>{group.commonCompleted.count}</td><td>{num(group.commonCompleted.averageGrossReturn)}</td>
          <td>{group.commonCompleted.net.trades}</td><td>{num(group.commonCompleted.net.averageNetReturn)}</td><td>{num(group.commonCompleted.targetHitRate)}</td>
          <td>{group.cohorts['quality:UNCERTAIN'] ?? 0}</td><td>{group.cohorts['quality:OPEN'] ?? 0}</td><td>{group.cohorts['quality:NO_ENTRY'] ?? 0}</td><td>{group.missedBaselineWinners ?? 0}</td></tr>)}</tbody></table></div>
      <p>공통 표본에서 제외된 판정 불가·진행 중 거래까지 함께 확인해야 합니다. 이 결과는 수익성 검증이나 계좌 수익률이 아닙니다.</p></>}
  </section>
}

export function IntradayPage({ back }: { back: () => void }) {
  const [date, setDate] = useState(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date()))
  const status = useQuery({ queryKey: ['intraday', 'status'], queryFn: () => api<Status>('/api/v1/intraday/status'), refetchInterval: 5000 })
  const results = useQuery({ queryKey: ['intraday', 'results', date], queryFn: () => api<Result[]>(`/api/v1/intraday/recommendations?date=${date}`), refetchInterval: 5000 })
  const analytics = useQuery({ queryKey: ['intraday', 'analytics', date], queryFn: () => api<Analytics>(`/api/v1/intraday/analytics?date=${date}`), refetchInterval: 30000 })
  const replay = useMutation({ mutationFn: (day: string) => api<{ inputCount: number; results: Result[]; analytics: Analytics }>(`/api/v1/intraday/replay?date=${day}`, { method: 'POST' }) })
  const sortedResults = [...(results.data ?? [])].sort((a, b) => Date.parse(b.signal.recommendedAt) - Date.parse(a.signal.recommendedAt))
  const error = status.error ?? results.error ?? analytics.error ?? replay.error
  return <div className="intradayPage"><header><button onClick={back}>← 대시보드</button><h1>실시간 장중 추천</h1><p>독립 연구·가상매매 · 점수는 확률이 아닙니다 · 수익성 미검증</p></header>
    <main><section><label>거래일 (한국시간) <input type="date" value={date} onChange={e => { setDate(e.target.value); replay.reset() }} /></label>
      <p>{status.data?.enabled ? '수집 활성' : '실험 비활성'} · {status.data?.costsConfigured ? '비용 설정됨' : '비용 미반영 · 추천 가능'} · 대기 {status.data?.queued ?? 0} · 누락 {status.data?.dropped ?? 0}</p>
      <p>기존 실시간 구독 종목만 평가합니다. 연속 30분 관측이 필요하며 09:05 직후 추천은 아직 지원하지 않습니다. 지수·호가·VI·투자위험 이력이 없어 운영 적합성은 미검증입니다.</p>
      {status.data?.error && <p role="alert">장중 처리 오류: {status.data.error}</p>}{error && <p role="alert">{error.message}</p>}
    </section>
    {results.isLoading && <p>추천을 불러오는 중…</p>}
    {!results.isLoading && !results.error && results.data?.length === 0 && <section><h2>NO TRADE</h2><p>진입 조건을 충족한 추천이 없습니다. 비활성·데이터 부족 여부와 관찰 후보 사유를 확인하세요.</p></section>}
    <section><h2>추천·가상 포지션</h2><p>추천 시각 최신순 · 5초마다 갱신</p><div className="intradayTable"><table><thead><tr><th>번호 / 종목</th><th>Setup / 추천 시각</th><th>추천가 / 최근 관측가</th><th>Entry / 추격 제한</th><th>Stop / Target 1·2</th><th>R:R / Score</th><th>상태</th></tr></thead>
      <tbody>{sortedResults.map(({ signal: s, outcome: o }, index) => <tr key={s.id}><td>{index + 1}. {s.stockName}<small>{s.stockCode}</small></td><td>{label(s.setup)}<small>{time(s.recommendedAt)}</small></td><td>{num(s.price)} / {num(o.currentPrice)}<small>{time(o.currentPriceAt)}</small></td><td>{num(s.plan.entryFrom)}~{num(s.plan.entryTo)}<small>추격 제한 {num(s.plan.chaseLimit)}</small></td><td>{num(s.plan.stop)}<small>{num(s.plan.target1)} / {num(s.plan.target2)}</small></td><td>{num(s.plan.riskReward)} / {num(s.score)}<small>{s.risks.includes('COSTS_NOT_CONFIGURED') ? '가격 기준 · 비용 포함 R:R 미산정' : '비용 포함'}</small></td><td>{label(o.status)}</td></tr>)}</tbody></table></div></section>
    {sortedResults.map(({ signal: s, outcome: o }) => <details key={s.id}><summary>{s.stockName} · 추천 근거·위험·성과</summary>
      <h3>추천 근거</h3><ul>{s.reasons.map(x => <li key={x}>{x}</li>)}</ul><h3>위험 요인</h3><ul>{s.risks.map(x => <li key={x}>{label(x)}</li>)}</ul>
      <p>유효기한 {time(s.expiresAt)} · 원천 {time(s.sourceAt)} · 수신 {time(s.receivedAt)}</p><p>{s.plan.invalidCondition}</p>
      <p>거래대금(당일/5분): {num(s.features.values.dailyValue)} / {num(s.features.values.value5m)}원 · 거래량 {num(s.features.values.volumeRatio5m)}배 · VWAP {num(s.features.values.vwap)} ({s.features.vwapState}) · 체결강도 {num(s.features.values.strength)} · RS/Regime 자료 없음</p>
      <p>추천 이후 MFE / MAE: {num(o.mfe)}% / {num(o.mae)}% · 가상 포지션 MFE / MAE: {num(o.tradeMfe)}% / {num(o.tradeMae)}%</p>
      <p>가상 진입 {num(o.entryPrice)} ({time(o.entryAt)}) → 청산 {num(o.exitPrice)} ({time(o.exitAt)}) · Gross {num(o.grossReturn)}% · Net {s.risks.includes('COSTS_NOT_CONFIGURED') ? '미산정 (비용 미설정)' : num(o.netReturn) + '%'}</p>
      <p>목표/손절 순서: {o.targetBeforeStop === true ? '목표 먼저' : o.targetBeforeStop === false ? '손절 먼저' : '미판정'}</p>
      <p>{[1, 3, 5, 10, 15, 30, 60].map(m => `${m}분: ${num(o.returns[m]?.returnPercent)}%`).join(' · ')} · 장마감: {num(o.closeReturn)}%</p>
      <p>관측 품질: {o.quality.map(label).join(', ') || '현재까지 누락 표식 없음'} · 실패 설명: {o.failureFactors.map(label).join(', ') || '없음'}</p>
    </details>)}
    <section><h2>현재 관찰 후보</h2>{status.data?.candidates.map(c => <p key={c.stockCode}>{c.stockName} · {label(c.stage)} · {label(c.reason)} · {time(c.evaluatedAt)}</p>)}</section>
    <h2>실시간 Paper 통계</h2><p>비용 미설정 추천은 Net 통계 표본에서 제외합니다. 기대값은 평균 Net입니다. 낙폭은 동일 명목금액의 청산순 수익률 누적 낙폭(%p)이며 계좌 MDD가 아닙니다. 자료 없음은 0%가 아닙니다.</p><Statistics data={analytics.data} />
    <section><h2>수신 이력 재생</h2><p>당시 기록된 입력·설정·시각으로 동일 엔진을 실행합니다. Paper 표본과 합산하지 않습니다.</p><button disabled={replay.isPending} onClick={() => replay.mutate(date)}>{replay.isPending ? '재생 중…' : '선택일 재생'}</button>
      {replay.data && <><p>저장 입력 {replay.data.inputCount}건 · 재생 추천 {replay.data.results.length}건</p>{replay.data.inputCount === 0 && <p>수신 이력이 없어 백테스트할 수 없습니다.</p>}<Statistics data={replay.data.analytics} /></>}
    </section><ExitComparison key={date} date={date} /></main></div>
}
