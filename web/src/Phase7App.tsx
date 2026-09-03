import { FormEvent, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Phase6App } from "./Phase6App";
import "./phase7.css";

type Setting = { id: number; name: string };
type TargetStop = {
  sampleSize: number;
  targetFirst: number;
  stopFirst: number;
  neither: number;
  targetFirstRate: number | null;
  stopFirstRate: number | null;
  expectancy: number | null;
};
type TimeBucket = {
  bucket: string;
  sampleSize: number;
  winRate: number | null;
  averageReturn: number | null;
  averageMaxReturn: number | null;
  averageMaxDrawdown: number | null;
};
type SignalCombination = {
  scannerType: string;
  opportunityBand: string;
  riskBand: string;
  sampleSize: number;
  winRate: number | null;
  averageReturn: number | null;
  averageMaxReturn: number | null;
  averageMaxDrawdown: number | null;
  confidence: "LOW" | "MEDIUM" | "HIGH" | string;
};
type HistoricalEdge = {
  sampleSize: number;
  winRate: number | null;
  averageReturn: number | null;
  expectancy: number | null;
  averageMfe: number | null;
  averageMae: number | null;
  confidence: string;
  enoughSamples: boolean;
};
type Analytics = {
  total: number;
  completed: number;
  dataMissing: number;
  winRate5m: number | null;
  winRateClose: number | null;
  averageReturn5m: number | null;
  averageReturn10m: number | null;
  averageReturn30m: number | null;
  averageReturn60m: number | null;
  averageReturnClose: number | null;
  calculationVersion: string;
  targetRate: number;
  stopRate: number;
  targetStop?: TargetStop;
  timeBuckets?: TimeBucket[];
  signalCombinations?: SignalCombination[];
  historicalEdge?: HistoricalEdge;
  minimumSampleSize?: number;
};

async function get<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error("성과 분석 데이터를 불러오지 못했습니다.");
  return response.json();
}

function dateValue(date: Date) {
  return date.toISOString().slice(0, 10);
}

function pct(value: number | null | undefined) {
  return value == null ? "--" : `${value >= 0 ? "+" : ""}${value.toFixed(2)}%`;
}

function rate(value: number | null | undefined) {
  return value == null ? "--" : `${value.toFixed(1)}%`;
}

function confidenceLabel(value?: string) {
  if (value === "HIGH") return "충분";
  if (value === "MEDIUM") return "관찰";
  return "부족";
}

function bandLabel(value?: string) {
  if (value === "HIGH") return "높음";
  if (value === "MID") return "중간";
  if (value === "LOW") return "낮음";
  if (value === "UNKNOWN") return "미확인";
  return value ?? "--";
}

function scannerTypeLabel(value: string) {
  const labels: Record<string, string> = {
    VOLUME: "거래량 급증",
    PRICE_RISE: "5분 급등",
    MOMENTUM: "복합 모멘텀",
    VOLUME_BREAKOUT: "거래량 돌파",
    TURNOVER_BREAKOUT: "회전율 돌파",
    HIGH_BREAKOUT: "고가 돌파",
    VWAP_BREAKOUT: "평균가 돌파",
    VWAP_RECLAIM: "평균가 회복",
    PULLBACK_REBREAK: "눌림 후 재돌파",
  };
  return labels[value] ?? value;
}

function settingNameLabel(name: string) {
  const labels: Record<string, string> = {
    Momentum: "복합 모멘텀",
    "Volume Breakout": "거래량 돌파",
    "Turnover Breakout": "회전율 돌파",
    "High Breakout": "고가 돌파",
    "VWAP Breakout": "평균가 돌파",
    "VWAP Reclaim": "평균가 회복",
    "Pullback Rebreak": "눌림 후 재돌파",
  };
  return labels[name] ?? name;
}

export function AnalyticsPage({ back }: { back: () => void }) {
  const today = new Date();
  const week = new Date(Date.now() - 7 * 86400000);
  const [from, setFrom] = useState(dateValue(week));
  const [to, setTo] = useState(dateValue(today));
  const [setting, setSetting] = useState("");
  const [targetRate, setTargetRate] = useState(3);
  const [stopRate, setStopRate] = useState(-2);
  const [minimumSampleSize, setMinimumSampleSize] = useState(20);
  const [params, setParams] = useState({
    from: week.toISOString(),
    to: new Date(today.getTime() + 86400000).toISOString(),
    setting: "",
    targetRate,
    stopRate,
    minimumSampleSize,
  });
  const settings = useQuery({
    queryKey: ["scanner-settings"],
    queryFn: () => get<Setting[]>("/api/v1/scanner-settings"),
  });
  const analytics = useQuery({
    queryKey: ["analytics", params],
    queryFn: () =>
      get<Analytics>(
        `/api/v1/scanner-analytics?from=${encodeURIComponent(params.from)}&to=${encodeURIComponent(params.to)}` +
          `${params.setting ? `&settingId=${params.setting}` : ""}` +
          `&targetRate=${params.targetRate}&stopRate=${params.stopRate}&minimumSampleSize=${params.minimumSampleSize}`,
      ),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setParams({
      from: new Date(from + "T00:00:00+09:00").toISOString(),
      to: new Date(to + "T23:59:59+09:00").toISOString(),
      setting,
      targetRate,
      stopRate,
      minimumSampleSize,
    });
  }

  const data = analytics.data;
  const milestones = [
    ["5분", data?.averageReturn5m],
    ["10분", data?.averageReturn10m],
    ["30분", data?.averageReturn30m],
    ["60분", data?.averageReturn60m],
    ["종가", data?.averageReturnClose],
  ] as const;

  return (
    <div className="analyticsPage">
      <header>
        <button onClick={back}>← 대시보드</button>
        <div>
          <p>6단계 · 고급 성과 분석</p>
          <h1>신호 성과 분석</h1>
          <span>
            탐지 이후 수익률을 시간, 조건 조합, 목표·손절 기준으로 비교합니다.
          </span>
        </div>
        <aside>
          <small>신뢰도</small>
          <b>{confidenceLabel(data?.historicalEdge?.confidence)}</b>
          <span>
            {data?.historicalEdge?.sampleSize ?? 0}/
            {data?.minimumSampleSize ?? minimumSampleSize}개 샘플
          </span>
        </aside>
      </header>
      <main>
        <form onSubmit={submit}>
          <label>
            시작일
            <input
              type="date"
              value={from}
              onChange={(event) => setFrom(event.target.value)}
            />
          </label>
          <label>
            종료일
            <input
              type="date"
              value={to}
              onChange={(event) => setTo(event.target.value)}
            />
          </label>
          <label>
            탐지 설정
            <select
              value={setting}
              onChange={(event) => setSetting(event.target.value)}
            >
              <option value="">전체 설정</option>
              {settings.data?.map((item) => (
                <option key={item.id} value={item.id}>
                  {settingNameLabel(item.name)}
                </option>
              ))}
            </select>
          </label>
          <label>
            목표 수익률 %
            <input
              type="number"
              step="0.1"
              value={targetRate}
              onChange={(event) => setTargetRate(Number(event.target.value))}
            />
          </label>
          <label>
            손절 기준 %
            <input
              type="number"
              step="0.1"
              value={stopRate}
              onChange={(event) => setStopRate(Number(event.target.value))}
            />
          </label>
          <label>
            최소 샘플
            <input
              type="number"
              min="1"
              value={minimumSampleSize}
              onChange={(event) =>
                setMinimumSampleSize(Number(event.target.value))
              }
            />
          </label>
          <button>분석</button>
        </form>
        {analytics.error && (
          <div className="analyticsEmpty">{analytics.error.message}</div>
        )}
        <section className="summaryCards">
          <article>
            <small>5분 승률</small>
            <b>{rate(data?.winRate5m)}</b>
          </article>
          <article>
            <small>종가 승률</small>
            <b>{rate(data?.winRateClose)}</b>
          </article>
          <article>
            <small>목표 먼저 도달</small>
            <b>{rate(data?.targetStop?.targetFirstRate)}</b>
          </article>
          <article>
            <small>손절 먼저 도달</small>
            <b>{rate(data?.targetStop?.stopFirstRate)}</b>
          </article>
        </section>
        <section className="menuGuide">
          <h2>사용 안내</h2>
          <p>
            분석 기간과 탐지 설정을 고른 뒤 목표 수익률·손절 기준을 입력합니다.
            시간대별 성과와 조건 조합별 평균 수익률을 비교해 실제로 우위가 있는
            신호만 골라냅니다.
          </p>
        </section>
        <section className="edgeGrid">
          <ReturnPanel milestones={milestones} total={data?.total ?? 0} />
          <TargetStopPanel
            summary={data?.targetStop}
            target={data?.targetRate ?? targetRate}
            stop={data?.stopRate ?? stopRate}
          />
        </section>
        <section className="analyticsGrid">
          <TimeBucketPanel rows={data?.timeBuckets ?? []} />
          <SignalCombinationPanel rows={data?.signalCombinations ?? []} />
          <HistoricalEdgePanel edge={data?.historicalEdge} />
        </section>
      </main>
    </div>
  );
}

function ReturnPanel({
  milestones,
  total,
}: {
  milestones: readonly (readonly [string, number | null | undefined])[];
  total: number;
}) {
  return (
    <section className="returnPanel">
      <div>
        <small>평균 수익률</small>
        <h2>포착 후 평균 수익률</h2>
      </div>
      {total === 0 ? (
        <div className="analyticsEmpty">
          <b>분석할 탐지 성과가 없습니다</b>
          <p>실시간 탐지 후 마일스톤 데이터가 쌓이면 표시됩니다.</p>
        </div>
      ) : (
        <div className="milestones">
          {milestones.map(([label, value]) => (
            <article key={label}>
              <span>{label}</span>
              <b className={(value ?? 0) >= 0 ? "gain" : "loss"}>
                {pct(value)}
              </b>
              <i
                style={{
                  height: `${Math.min(100, Math.abs(value ?? 0) * 8)}%`,
                }}
              />
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function TargetStopPanel({
  summary,
  target,
  stop,
}: {
  summary?: TargetStop;
  target: number;
  stop: number;
}) {
  const total = Math.max(1, summary?.sampleSize ?? 0);
  return (
    <section className="targetPanel">
      <div>
        <small>목표 / 손절 선도달</small>
        <h2>
          {target}% / {stop}% 도달 비교
        </h2>
      </div>
      <div className="targetBars">
        <span>
          <b
            style={{ width: `${((summary?.targetFirst ?? 0) / total) * 100}%` }}
          />
          목표 {summary?.targetFirst ?? 0}
        </span>
        <span>
          <b
            style={{ width: `${((summary?.stopFirst ?? 0) / total) * 100}%` }}
          />
          손절 {summary?.stopFirst ?? 0}
        </span>
        <span>
          <b style={{ width: `${((summary?.neither ?? 0) / total) * 100}%` }} />
          미도달 {summary?.neither ?? 0}
        </span>
      </div>
      <strong>기대 수익률 {pct(summary?.expectancy)}</strong>
    </section>
  );
}

function TimeBucketPanel({ rows }: { rows: TimeBucket[] }) {
  return (
    <section>
      <h2>시간대별 성과</h2>
      {rows.length === 0 ? (
        <p className="muted">시간대 데이터 대기</p>
      ) : (
        rows.map((row) => (
          <article className="analyticsRow" key={row.bucket}>
            <span>
              <b>{row.bucket}</b>
              <small>{row.sampleSize}개 샘플</small>
            </span>
            <span>{rate(row.winRate)}</span>
            <span className={(row.averageReturn ?? 0) >= 0 ? "gain" : "loss"}>
              {pct(row.averageReturn)}
            </span>
          </article>
        ))
      )}
    </section>
  );
}

function SignalCombinationPanel({ rows }: { rows: SignalCombination[] }) {
  return (
    <section>
      <h2>신호 조합 성과</h2>
      {rows.length === 0 ? (
        <p className="muted">조합 데이터 대기</p>
      ) : (
        rows.slice(0, 8).map((row) => (
          <article
            className="analyticsRow"
            key={`${row.scannerType}-${row.opportunityBand}-${row.riskBand}`}
          >
            <span>
              <b>{scannerTypeLabel(row.scannerType)}</b>
              <small>
                기회:{bandLabel(row.opportunityBand)} · 위험:
                {bandLabel(row.riskBand)}
              </small>
            </span>
            <span>{confidenceLabel(row.confidence)}</span>
            <span className={(row.averageReturn ?? 0) >= 0 ? "gain" : "loss"}>
              {pct(row.averageReturn)}
            </span>
          </article>
        ))
      )}
    </section>
  );
}

function HistoricalEdgePanel({ edge }: { edge?: HistoricalEdge }) {
  return (
    <section>
      <h2>과거 우위</h2>
      <div className="edgeCard">
        <small>{confidenceLabel(edge?.confidence)}</small>
        <b>{pct(edge?.averageReturn)}</b>
        <span>승률 {rate(edge?.winRate)}</span>
        <span>최대 유리 수익 {pct(edge?.averageMfe)}</span>
        <span>최대 불리 낙폭 {pct(edge?.averageMae)}</span>
      </div>
    </section>
  );
}

export function Phase7App() {
  const [analytics, setAnalytics] = useState(false);
  return analytics ? (
    <AnalyticsPage back={() => setAnalytics(false)} />
  ) : (
    <>
      <button className="analyticsLaunch" onClick={() => setAnalytics(true)}>
        성과 분석
      </button>
      <Phase6App />
    </>
  );
}
