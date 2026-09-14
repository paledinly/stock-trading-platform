import { FormEvent, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Phase7App } from "./Phase7App";
import "./phase8.css";

type Market = "" | "KOSPI" | "KOSDAQ";
type Candidate = {
  stockCode: string;
  stockName: string;
  market: string;
  currentPrice: number;
  changeRate: number;
  accumulatedVolume: number;
  accumulatedTradingValue: number;
  broadScore: number;
  reason: string;
  precisionEligible: boolean;
  quotedAt: string;
  quoteSource?: string;
};
type Scan = {
  collection?: CollectionSummary;
  scannedAt: string;
  market: string;
  requestedLimit: number;
  scannedCount: number;
  candidateCount: number;
  fallback: boolean;
  rankingSources: Array<{ type: string; success: boolean; candidateCount: number; error?: string }>;
  precisionAllocation: {
    state: string;
    capacity: number;
    activeCount: number;
    remainingSlots: number;
    reservedSlots: number;
    allocations: Array<{ stockCode: string; score: number; awaitingAcknowledgement: boolean }>;
  };
  universe: {
    activeStocks: number;
    tradableStocks: number;
    realtimeSubscriptionLimit: number;
    realtimeSubscriptionCount: number;
    realtimeSubscriptionRemaining: number;
  };
  regime: {
    state: string;
    averageChangeRate: number | null;
    advanceRate: number | null;
    declineRate: number | null;
    averageTradingValue: number | null;
  };
  candidates: Candidate[];
};
type MarketWideStatus = {
  enrichment?: {
    enabled: boolean; capacity: number; retainedJobs: number; states: Record<string, number>;
    accepted: number; coalesced: number; succeeded: number; exhausted: number; expired: number;
    rejected: number; lastProcessedAt: string | null; lastError: string | null;
  };
  collection?: CollectionSummary;
  running: boolean;
  lastStartedAt: string | null;
  lastCompletedAt: string | null;
  lastScheduledBucket: string | null;
  completedRuns: number;
  failedRuns: number;
  skippedRuns: number;
  lastSkipReason: string | null;
  lastError: string | null;
  lastDurationMillis: number;
  lastScannedCount: number;
  lastCandidateCount: number;
  lastFallback: boolean;
  rankingSources: Array<{ type: string; success: boolean; candidateCount: number; error?: string; attempts?: number; successes?: number }>;
};
type CollectionSummary = {
  detailQuoteBudget: number; restLookups: number; restFailures: number; insufficientCount: number;
  dataSources: Record<string, number>; maxDataAgeSeconds: number; kisRequests: Record<string, number>;
};
type SourcePerformance = {
  source: "BROAD" | "PRECISION";
  recommendations: number;
  completed: number;
  dataMissing: number;
  closeWinRate: number | null;
  averageOpenReturn: number | null;
  averageCloseReturn: number | null;
  averageMaxReturn: number | null;
  averageMaxDrawdown: number | null;
  targetHitRate: number | null;
  stopHitRate: number | null;
};
type Coverage = {
  sessionDate: string;
  activeUniverse: number;
  tradableUniverse: number;
  scheduledRuns: number;
  completedRuns: number;
  failedRuns: number;
  rankingCapturedStocks: number;
  rankingCoverageRate: number | null;
  broadCollectedStocks: number;
  broadInsufficientStocks: number;
  broadCoverageRate: number | null;
  precisionRequestedStocks: number;
  precisionActivatedStocks: number;
  averagePrecisionMinutes: number | null;
  precisionDetectionStocks: number;
  broadRecommendations: number;
  precisionRecommendations: number;
  exclusionReasons: Record<string, number>;
  sourcePerformance: SourcePerformance[];
  limitations: string[];
};

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!response.ok)
    throw new Error("시장 전체 스캐너 데이터를 처리하지 못했습니다.");
  return response.status === 204 ? (undefined as T) : response.json();
}

function pct(value?: number | null) {
  return value == null ? "--" : `${value >= 0 ? "+" : ""}${value.toFixed(2)}%`;
}

function money(value?: number | null) {
  return value == null ? "--" : Math.round(value).toLocaleString("ko-KR");
}

function regimeLabel(value?: string) {
  if (value === "RISK_ON") return "위험 선호";
  if (value === "RISK_OFF") return "위험 회피";
  if (value === "MIXED") return "혼조";
  if (value === "READY") return "준비";
  return "판단 대기";
}

function reasonLabel(value: string) {
  if (value === "PRICE_STRENGTH") return "가격 강세";
  if (value === "LIQUIDITY_ONLY") return "유동성 후보";
  return value;
}

function exclusionReasonLabel(value: string) {
  if (value === "RECOMMENDED_BROAD") return "광역 후보로 추천됨";
  if (value === "PROMOTED_TO_PRECISION") return "정밀 탐지로 승격됨";
  if (value === "QUOTE_FAILED") return "시세 조회 실패";
  if (value === "INSUFFICIENT_DATA") return "데이터 부족";
  if (value === "NOT_TRADABLE") return "거래 부적합";
  if (value === "SCORE_OR_LIMIT_FILTERED") return "점수 또는 추천 개수 조건 제외";
  return value;
}

export function MarketWidePage({ back }: { back: () => void }) {
  const [market, setMarket] = useState<Market>("");
  const [limit, setLimit] = useState(40);
  const [candidates, setCandidates] = useState(12);
  const [includeEtf, setIncludeEtf] = useState(false);
  const [params, setParams] = useState({
    market,
    limit,
    candidates,
    includeEtf,
  });
  const scan = useQuery({
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryKey: ["market-wide-scan", params],
    queryFn: () =>
      api<Scan>(
        `/api/v1/market-wide/scan?limit=${params.limit}&candidates=${params.candidates}&includeEtf=${params.includeEtf}${params.market ? `&market=${params.market}` : ""}`,
      ),
  });
  const status = useQuery({
    queryKey: ["market-wide-status"],
    queryFn: () => api<MarketWideStatus>("/api/v1/market-wide/status"),
    refetchInterval: 30000,
  });
  const coverage = useQuery({
    staleTime: 120000,
    refetchOnWindowFocus: false,
    queryKey: ["market-wide-coverage"],
    queryFn: () => api<Coverage>("/api/v1/market-wide/coverage"),
    refetchInterval: 120000,
  });
  const subscribe = useMutation({
    mutationFn: (stockCode: string) =>
      api(`/api/v1/market/subscriptions/${stockCode}`, { method: "POST" }),
    onSuccess: () => scan.refetch(),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setParams({ market, limit, candidates, includeEtf });
  }

  const data = scan.data;
  return (
    <div className="widePage">
      <header>
        <button onClick={back}>← 대시보드</button>
        <div>
          <p>7단계 · 시장 전체 스캐너</p>
          <h1>시장 전체 스캐너</h1>
          <span>
            넓은 범위의 시세 조회로 후보를 줄이고, 선별된 종목만 정밀
            구독합니다.
          </span>
        </div>
        <aside>
          <small>시장 상태</small>
          <b>{regimeLabel(data?.regime.state)}</b>
          <span>{data ? `${data.scannedCount}개 스캔` : "스캔 대기"}</span>
        </aside>
      </header>
      <main>
        <form onSubmit={submit}>
          <label>
            시장
            <select
              value={market}
              onChange={(event) => setMarket(event.target.value as Market)}
            >
              <option value="">전체</option>
              <option value="KOSPI">KOSPI</option>
              <option value="KOSDAQ">KOSDAQ</option>
            </select>
          </label>
          <label>
            스캔 수
            <input
              type="number"
              min="1"
              max="120"
              value={limit}
              onChange={(event) => setLimit(Number(event.target.value))}
            />
          </label>
          <label>
            후보 수
            <input
              type="number"
              min="1"
              max="30"
              value={candidates}
              onChange={(event) => setCandidates(Number(event.target.value))}
            />
          </label>
          <label className="check">
            ETF 포함
            <input
              type="checkbox"
              checked={includeEtf}
              onChange={(event) => setIncludeEtf(event.target.checked)}
            />
          </label>
          <button>넓은 범위 스캔</button>
        </form>
        <section className="menuGuide">
          <h2>사용 안내</h2>
          <p>
            시장과 스캔 수를 정한 뒤 넓은 범위 스캔을 실행합니다. 점수가 높은
            후보 중 구독 여유가 있는 종목은 정밀 구독을 눌러 실시간 감시
            대상으로 전환합니다.
          </p>
        </section>
        {scan.error && <div className="wideEmpty">{scan.error.message}</div>}
        <section className="wideSummary">
          <article>
            <small>거래 가능 종목</small>
            <b>
              {data?.universe.tradableStocks.toLocaleString("ko-KR") ?? "--"}
            </b>
          </article>
          <article>
            <small>구독 여유</small>
            <b>{data?.universe.realtimeSubscriptionRemaining ?? "--"}</b>
          </article>
          <article>
            <small>자동 정밀 구독</small>
            <b>{data ? `${data.precisionAllocation.activeCount}/${data.precisionAllocation.capacity}` : "--"}</b>
          </article>
          <article>
            <small>상승 비율</small>
            <b>{pct(data?.regime.advanceRate)}</b>
          </article>
          <article>
            <small>평균 등락</small>
            <b>{pct(data?.regime.averageChangeRate)}</b>
          </article>
        </section>
        <section className="wideSummary">
          <article><small>자동 스캔</small><b>{status.data?.running ? "실행 중" : "대기"}</b></article>
          <article><small>완료/실패</small><b>{status.data ? `${status.data.completedRuns}/${status.data.failedRuns}` : "--"}</b></article>
          <article><small>최근 후보</small><b>{status.data?.lastCandidateCount ?? "--"}</b></article>
          <article><small>최근 소요</small><b>{status.data ? `${status.data.lastDurationMillis}ms` : "--"}</b></article>
        </section>
        {status.data?.lastError && <div className="wideEmpty">최근 자동 스캔 오류: {status.data.lastError}</div>}
        {status.data?.enrichment && <section className="menuGuide">
          <h2>비동기 후보 보강</h2>
          <p>{status.data.enrichment.enabled ? '활성' : '비활성'} · 보관 작업 {status.data.enrichment.retainedJobs}/{status.data.enrichment.capacity}건 · 상태 {Object.entries(status.data.enrichment.states).map(([state, count]) => `${state} ${count}건`).join(' · ') || '대기 작업 없음'}</p>
          <p>접수 {status.data.enrichment.accepted} · 중복 병합 {status.data.enrichment.coalesced} · 성공 {status.data.enrichment.succeeded} · 재시도 소진 {status.data.enrichment.exhausted} · 만료 {status.data.enrichment.expired} · 접수 제외 {status.data.enrichment.rejected} (서버 시작 이후)</p>
          {status.data.enrichment.lastError && <p>최근 보강 오류: {status.data.enrichment.lastError}</p>}
          <p>장중에 1건씩 보강하며 마감 평가 동결 시각 이후에는 저장하지 않습니다. 성공 데이터는 새 시점의 스냅샷이며, 기존 추천은 자동 재생성하지 않습니다.</p>
        </section>}
        {(data?.collection ?? status.data?.collection) && <section className="menuGuide">
          <h2>KIS 수집 품질</h2>
          {(() => {
            const collected = data?.collection ?? status.data?.collection;
            if (!collected) return null;
            return <>
              <p>상세 REST 조회 {collected.restLookups}/{collected.detailQuoteBudget}종목 · REST 실패 {collected.restFailures}건 · 필수값 부족 {collected.insufficientCount}종목 · 확보 데이터 최대 경과 {collected.maxDataAgeSeconds}초</p>
              <p>출처: {Object.entries(collected.dataSources).map(([source, count]) => `${source} ${count}건`).join(' · ')}</p>
              <p>KIS 요청 {collected.kisRequests.requests ?? 0}회 · 유량 제한 {collected.kisRequests.rateLimitErrors ?? 0}회 · 재시도 {collected.kisRequests.rateLimitRetries ?? 0}회 (서버 시작 이후)</p>
            </>;
          })()}
          {(data?.rankingSources ?? status.data?.rankingSources ?? []).map(source => <p key={source.type}>
            {source.type}: {source.success ? `성공 ${source.candidateCount}종목` : `실패 ${source.error ?? ''}`}
          </p>)}
          {(status.data?.rankingSources ?? []).filter(source => source.attempts != null).map(source => <p key={`rate-${source.type}`}>
            {source.type} 누적 성공 {source.successes}/{source.attempts}회 (서버 시작 이후)
          </p>)}
          <p>랭킹 시각은 응답 수신 시각입니다. 랭킹에 없는 값은 생성하지 않으며, 이 상태는 전체 시장을 대표하는 시장지표가 아닙니다.</p>
        </section>}
        <section className="wideSummary">
          <article><small>순위 포착률</small><b>{pct(coverage.data?.rankingCoverageRate)}</b></article>
          <article><small>Broad 확보율</small><b>{pct(coverage.data?.broadCoverageRate)}</b></article>
          <article><small>Precision 승인</small><b>{coverage.data ? `${coverage.data.precisionActivatedStocks}/${coverage.data.precisionRequestedStocks}` : "--"}</b></article>
          <article><small>평균 구독 체류</small><b>{coverage.data?.averagePrecisionMinutes == null ? "--" : `${coverage.data.averagePrecisionMinutes.toFixed(1)}분`}</b></article>
          <article><small>탐지 종목</small><b>{coverage.data?.precisionDetectionStocks ?? "--"}</b></article>
        </section>
        {coverage.error && <div className="wideEmpty">Coverage를 불러오지 못했습니다: {coverage.error.message}</div>}
        {coverage.data && <section className="wideGrid">
          <div className="candidatePanel">
            <div className="wideTitle"><span><small>성과 검증</small><h2>출처별 다음날 성과</h2></span></div>
            {(coverage.data.sourcePerformance ?? []).map(item => <article key={item.source}>
              <div><span><b>{item.source === "PRECISION" ? "정밀 추천" : "광역 추천"}</b><small>{item.completed}건 완료 · {item.dataMissing}건 누락</small></span><strong>{pct(item.closeWinRate)}</strong></div>
              <dl>
                <span><dt>추천</dt><dd>{item.recommendations}</dd></span>
                <span><dt>시가 평균</dt><dd>{pct(item.averageOpenReturn)}</dd></span>
                <span><dt>종가 평균</dt><dd>{pct(item.averageCloseReturn)}</dd></span>
                <span><dt>최고 평균</dt><dd>{pct(item.averageMaxReturn)}</dd></span>
                <span><dt>최대 낙폭</dt><dd>{pct(item.averageMaxDrawdown)}</dd></span>
                <span><dt>목표 도달</dt><dd>{pct(item.targetHitRate)}</dd></span>
              </dl>
            </article>)}
          </div>
          <aside className="regimePanel">
            <h2>후보 처리 결과</h2>
            <dl>{Object.entries(coverage.data.exclusionReasons ?? {}).map(([reason, count]) => <span key={reason}><dt>{exclusionReasonLabel(reason)}</dt><dd>{count}</dd></span>)}</dl>
            <h2>통계 주의사항</h2>
            {(coverage.data.limitations ?? []).map(item => <p key={item}>{item}</p>)}
          </aside>
        </section>}
        <section className="wideGrid">
          <div className="candidatePanel">
            <div className="wideTitle">
              <span>
                <small>시장 후보</small>
                <h2>시장 후보</h2>
              </span>
              <b>{data?.candidateCount ?? 0}</b>
            </div>
            {scan.isLoading && <div className="wideEmpty">스캔 중...</div>}
            {!scan.isLoading && data?.candidates.length === 0 && (
              <div className="wideEmpty">후보가 없습니다</div>
            )}
            {data?.candidates.map((item) => (
              <article key={item.stockCode}>
                <div>
                  <i>{item.stockName[0]}</i>
                  <span>
                    <b>{item.stockName}</b>
                    <small>
                      {item.stockCode} · {item.market} ·{" "}
                      {reasonLabel(item.reason)}
                      {item.quoteSource ? ` · ${item.quoteSource}` : ''}
                    </small>
                  </span>
                  <strong>{item.broadScore.toFixed(1)}</strong>
                </div>
                <dl>
                  <span>
                    <dt>현재가</dt>
                    <dd>{money(item.currentPrice)}원</dd>
                  </span>
                  <span>
                    <dt>등락</dt>
                    <dd className={item.changeRate >= 0 ? "gain" : "loss"}>
                      {pct(item.changeRate)}
                    </dd>
                  </span>
                  <span>
                    <dt>거래대금</dt>
                    <dd>{money(item.accumulatedTradingValue / 1000000)}백만</dd>
                  </span>
                  <span>
                    <dt>거래량</dt>
                    <dd>{item.accumulatedVolume.toLocaleString("ko-KR")}</dd>
                  </span>
                </dl>
                <button
                  disabled={!item.precisionEligible || subscribe.isPending}
                  onClick={() => subscribe.mutate(item.stockCode)}
                >
                  {item.precisionEligible ? "정밀 구독" : "구독 불가"}
                </button>
              </article>
            ))}
          </div>
          <aside className="regimePanel">
            <h2>정밀 구독</h2>
            <p>{data?.precisionAllocation.state === "ACTIVE" ? "자동 할당" : data?.precisionAllocation.state === "FROZEN" ? "후보 고정" : "비활성"}</p>
            <dl>
              <span><dt>활성/한도</dt><dd>{data ? `${data.precisionAllocation.activeCount}/${data.precisionAllocation.capacity}` : "--"}</dd></span>
              <span><dt>예약 슬롯</dt><dd>{data?.precisionAllocation.reservedSlots ?? "--"}</dd></span>
              <span><dt>승인 대기</dt><dd>{data?.precisionAllocation.allocations.filter(item => item.awaitingAcknowledgement).length ?? "--"}</dd></span>
            </dl>
            <h2>시장 상태</h2>
            <p>{regimeLabel(data?.regime.state)}</p>
            <dl>
              <span>
                <dt>하락 비율</dt>
                <dd>{pct(data?.regime.declineRate)}</dd>
              </span>
              <span>
                <dt>평균 거래대금</dt>
                <dd>
                  {money((data?.regime.averageTradingValue ?? 0) / 1000000)}백만
                </dd>
              </span>
              <span>
                <dt>요청 범위</dt>
                <dd>{data?.requestedLimit ?? limit}</dd>
              </span>
              <span>
                <dt>활성 종목</dt>
                <dd>
                  {data?.universe.activeStocks.toLocaleString("ko-KR") ?? "--"}
                </dd>
              </span>
            </dl>
          </aside>
        </section>
      </main>
    </div>
  );
}

export function Phase8App() {
  const [wide, setWide] = useState(false);
  return wide ? (
    <MarketWidePage back={() => setWide(false)} />
  ) : (
    <>
      <button className="wideLaunch" onClick={() => setWide(true)}>
        시장 전체
      </button>
      <Phase7App />
    </>
  );
}
