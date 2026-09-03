import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Phase3App } from "./Phase3App";
import { RealtimeBridge } from "./RealtimeBridge";
import "./phase4.css";

type Journal = {
  memo: string | null;
  targetPrice: number | null;
  stopLossPrice: number | null;
  reasons: { code: string; customReason: string | null }[];
  version: number;
};
type Trade = {
  id: number;
  stockCode: string;
  stockName: string;
  market: string;
  tradeType: "BUY" | "SELL";
  tradedAt: string;
  price: number;
  quantity: number;
  amount: number;
  realizedPnl: number;
  holdingDays: number;
  version: number;
  journal: Journal | null;
};
async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.detail ?? "요청을 처리하지 못했습니다.");
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
function localDateTime() {
  const date = new Date(Date.now() - new Date().getTimezoneOffset() * 60000);
  return date.toISOString().slice(0, 16);
}

function reasonLabel(code: string) {
  const labels: Record<string, string> = {
    MOMENTUM: "추세",
    BREAKOUT: "돌파",
    VOLUME: "거래량",
    FUNDAMENTAL: "기본",
    NEWS: "뉴스",
    CUSTOM: "직접입력",
  };
  return labels[code] ?? code;
}

function TradeEditor({ done }: { done: () => void }) {
  const [stockCode, setStockCode] = useState(""),
    [type, setType] = useState<"BUY" | "SELL">("BUY"),
    [at, setAt] = useState(localDateTime()),
    [price, setPrice] = useState(""),
    [quantity, setQuantity] = useState("");
  const create = useMutation({
    mutationFn: () =>
      api<Trade>("/api/v1/trades", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({
          stockCode: stockCode.trim().toUpperCase(),
          tradeType: type,
          tradedAt: new Date(at).toISOString(),
          price: Number(price),
          quantity: Number(quantity),
        }),
      }),
    onSuccess: () => {
      setPrice("");
      setQuantity("");
      done();
    },
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate();
  }
  return (
    <form className="tradeEditor" onSubmit={submit}>
      <div className="sideSwitch">
        <button
          type="button"
          className={type === "BUY" ? "selected buy" : ""}
          onClick={() => setType("BUY")}
        >
          매수
        </button>
        <button
          type="button"
          className={type === "SELL" ? "selected sell" : ""}
          onClick={() => setType("SELL")}
        >
          매도
        </button>
      </div>
      <label>
        종목코드
        <input
          required
          pattern="[A-Z0-9]{6,12}"
          value={stockCode}
          onChange={(e) => setStockCode(e.target.value)}
          placeholder="005930"
        />
      </label>
      <label>
        거래 일시
        <input
          required
          type="datetime-local"
          value={at}
          onChange={(e) => setAt(e.target.value)}
        />
      </label>
      <div className="formGrid">
        <label>
          체결 가격
          <input
            required
            min="0.0001"
            step="0.0001"
            type="number"
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            placeholder="70,000"
          />
        </label>
        <label>
          수량
          <input
            required
            min="1"
            step="1"
            type="number"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            placeholder="10"
          />
        </label>
      </div>
      <div className="amount">
        <span>거래 금액</span>
        <b>
          {((Number(price) || 0) * (Number(quantity) || 0)).toLocaleString()}원
        </b>
      </div>
      {create.error && <p className="formError">{create.error.message}</p>}
      <button className="primary" disabled={create.isPending}>
        {create.isPending ? "저장 중…" : "거래 기록 저장"}
      </button>
    </form>
  );
}

function JournalEditor({ trade, done }: { trade: Trade; done: () => void }) {
  const old = trade.journal,
    [memo, setMemo] = useState(old?.memo ?? ""),
    [target, setTarget] = useState(old?.targetPrice?.toString() ?? ""),
    [stop, setStop] = useState(old?.stopLossPrice?.toString() ?? ""),
    [reasons, setReasons] = useState<string[]>(
      old?.reasons.map((r) => r.code) ?? [],
    ),
    [custom, setCustom] = useState(
      old?.reasons.find((r) => r.code === "CUSTOM")?.customReason ?? "",
    );
  const choices = [
    ["MOMENTUM", "추세"],
    ["BREAKOUT", "돌파"],
    ["VOLUME", "거래량"],
    ["FUNDAMENTAL", "기본"],
    ["NEWS", "뉴스"],
    ["CUSTOM", "직접입력"],
  ] as const;
  const save = useMutation({
    mutationFn: () =>
      api(`/api/v1/trades/${trade.id}/journal`, {
        method: "PUT",
        body: JSON.stringify({
          memo,
          targetPrice: target ? Number(target) : null,
          stopLossPrice: stop ? Number(stop) : null,
          version: old?.version ?? 0,
          reasons: reasons.map((code) => ({
            code,
            customReason: code === "CUSTOM" ? custom : null,
          })),
        }),
      }),
    onSuccess: done,
  });
  return (
    <div className="journalEditor">
      <textarea
        aria-label="투자 메모"
        value={memo}
        onChange={(e) => setMemo(e.target.value)}
        placeholder="진입 근거와 복기할 내용을 기록하세요."
      />
      <div className="formGrid">
        <label>
          목표가
          <input
            type="number"
            min="0"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
          />
        </label>
        <label>
          손절가
          <input
            type="number"
            min="0"
            value={stop}
            onChange={(e) => setStop(e.target.value)}
          />
        </label>
      </div>
      <div className="reasonTags">
        {choices.map(([code, label]) => (
          <button
            key={code}
            className={reasons.includes(code) ? "on" : ""}
            onClick={() =>
              setReasons((v) =>
                v.includes(code) ? v.filter((x) => x !== code) : [...v, code],
              )
            }
          >
            {label}
          </button>
        ))}
      </div>
      {reasons.includes("CUSTOM") && (
        <input
          aria-label="직접 입력 이유"
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          placeholder="직접 입력 이유"
        />
      )}
      {save.error && <p className="formError">{save.error.message}</p>}
      <button className="primary" onClick={() => save.mutate()}>
        투자 노트 저장
      </button>
    </div>
  );
}

export function JournalPage() {
  const cache = useQueryClient(),
    [editing, setEditing] = useState<number | null>(null);
  const trades = useQuery({
    queryKey: ["trades"],
    queryFn: () => api<Trade[]>("/api/v1/trades?limit=100"),
  });
  const refresh = () => cache.invalidateQueries({ queryKey: ["trades"] });
  const remove = useMutation({
    mutationFn: (id: number) =>
      api(`/api/v1/trades/${id}`, { method: "DELETE" }),
    onSuccess: refresh,
  });
  const realized = trades.data?.reduce((sum, t) => sum + t.realizedPnl, 0) ?? 0;
  return (
    <div className="journalPage">
      <header>
        <div>
          <p>4단계 · 투자 기록</p>
          <h1>투자기록</h1>
          <span>결과보다 과정을 기록하세요.</span>
        </div>
        <aside>
          <small>누적 실현손익</small>
          <b className={realized >= 0 ? "positive" : "negative"}>
            {realized >= 0 ? "+" : ""}
            {realized.toLocaleString()}원
          </b>
          <span>{trades.data?.length ?? 0}건의 거래</span>
        </aside>
      </header>
      <main>
        <section className="menuGuide">
          <h2>사용 안내</h2>
          <p>
            새 거래 기록에서 매수·매도 내역을 입력하고, 거래 내역의 노트
            편집으로 진입 근거와 목표가·손절가를 남깁니다.
          </p>
        </section>
        <section className="entryPanel">
          <h2>새 거래 기록</h2>
          <TradeEditor done={refresh} />
        </section>
        <section className="historyPanel">
          <div className="historyTitle">
            <span>
              <small>거래 기록</small>
              <h2>거래 내역</h2>
            </span>
            <b>{trades.data?.length ?? 0}</b>
          </div>
          {trades.isLoading && <p>불러오는 중…</p>}
          {trades.error && <p className="formError">{trades.error.message}</p>}
          {trades.data?.length === 0 && (
            <div className="journalEmpty">첫 매매 기록을 남겨보세요.</div>
          )}
          {trades.data?.map((trade) => (
            <article key={trade.id}>
              <div className="tradeHead">
                <i
                  className={
                    trade.tradeType === "BUY" ? "buyBadge" : "sellBadge"
                  }
                >
                  {trade.tradeType === "BUY" ? "매수" : "매도"}
                </i>
                <span>
                  <b>{trade.stockName}</b>
                  <small>
                    {trade.stockCode} ·{" "}
                    {new Date(trade.tradedAt).toLocaleString("ko-KR")}
                  </small>
                </span>
                <strong>{trade.amount.toLocaleString()}원</strong>
              </div>
              <div className="tradeFacts">
                <span>
                  단가 <b>{trade.price.toLocaleString()}원</b>
                </span>
                <span>
                  수량 <b>{trade.quantity.toLocaleString()}주</b>
                </span>
                <span>
                  보유기간 <b>{trade.holdingDays}일</b>
                </span>
                {trade.tradeType === "SELL" && (
                  <span>
                    실현손익{" "}
                    <b
                      className={
                        trade.realizedPnl >= 0 ? "positive" : "negative"
                      }
                    >
                      {trade.realizedPnl.toLocaleString()}원
                    </b>
                  </span>
                )}
              </div>
              {trade.journal && (
                <div className="journalPreview">
                  <p>{trade.journal.memo || "메모 없음"}</p>
                  <div>
                    {trade.journal.reasons.map((r) => (
                      <small key={r.code}>
                        #{r.customReason || reasonLabel(r.code)}
                      </small>
                    ))}
                  </div>
                </div>
              )}
              <div className="actions">
                <button
                  onClick={() =>
                    setEditing(editing === trade.id ? null : trade.id)
                  }
                >
                  {editing === trade.id ? "닫기" : "노트 편집"}
                </button>
                <button onClick={() => remove.mutate(trade.id)}>삭제</button>
              </div>
              {editing === trade.id && (
                <JournalEditor
                  trade={trade}
                  done={() => {
                    setEditing(null);
                    refresh();
                  }}
                />
              )}
            </article>
          ))}
        </section>
      </main>
    </div>
  );
}

export function Phase4App() {
  const [page, setPage] = useState<"dashboard" | "journal">("dashboard");
  return (
    <>
      <RealtimeBridge />
      <nav className="phaseNav">
        <button
          className={page === "dashboard" ? "active" : ""}
          onClick={() => setPage("dashboard")}
        >
          대시보드
        </button>
        <button
          className={page === "journal" ? "active" : ""}
          onClick={() => setPage("journal")}
        >
          투자기록
        </button>
      </nav>
      {page === "dashboard" ? <Phase3App /> : <JournalPage />}
    </>
  );
}
