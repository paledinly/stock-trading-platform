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
};
type Scan = {
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
  rankingSources: Array<{ type: string; success: boolean; candidateCount: number; error?: string }>;
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
    queryKey: ["market-wide-coverage"],
    queryFn: () => api<Coverage>("/api/v1/market-wide/coverage"),
    refetchInterval: 60000,
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
              <div><span><b>{item.source === "PRECISION" ? "정밀 추천" : "Broad 추천"}</b><small>{item.completed}건 완료 · {item.dataMissing}건 누락</small></span><strong>{pct(item.closeWinRate)}</strong></div>
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
            <dl>{Object.entries(coverage.data.exclusionReasons ?? {}).map(([reason, count]) => <span key={reason}><dt>{reason}</dt><dd>{count}</dd></span>)}</dl>
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
