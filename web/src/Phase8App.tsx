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
            <small>상승 비율</small>
            <b>{pct(data?.regime.advanceRate)}</b>
          </article>
          <article>
            <small>평균 등락</small>
            <b>{pct(data?.regime.averageChangeRate)}</b>
          </article>
        </section>
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
