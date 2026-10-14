/**
 * 투자기록(매매 일지) 화면 파일이에요. 사용자가 직접 한 매수·매도를 손으로 기록하고, 거래마다 메모·목표가·손절가·진입 이유를 남겨요.
 * 실제 주문 기능은 없고, 서버의 /api/v1/trades API에 기록만 저장해요.
 * Phase9App(전체 화면 껍데기)이 "투자기록" 메뉴에서 JournalPage를 보여 줘요.
 */
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import "./phase4.css";

/** 거래 한 건에 붙는 투자 노트예요. version은 동시 수정 충돌을 막는 번호예요. */
type Journal = {
  memo: string | null;
  targetPrice: number | null;
  stopLossPrice: number | null;
  reasons: { code: string; customReason: string | null }[];
  version: number;
};
/** 기록된 거래 한 건이에요. realizedPnl(실현손익)과 holdingDays(보유기간)는 서버가 계산해 주는 것으로 보여요. */
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
/**
 * fetch(브라우저 내장 HTTP 요청 함수)로 JSON API를 부르는 비동기(async) 도우미예요.
 * 받는 것: url, init(요청 옵션, 추가 헤더 포함). 돌려주는 것: 응답 JSON, 204(내용 없음)이면 undefined.
 * 실패하면 서버가 준 detail 메시지로 오류를 던져요.
 */
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
/**
 * 지금 시각을 datetime-local 입력칸 형식("YYYY-MM-DDTHH:mm", 내 PC 시간대 기준)으로 만들어요.
 * 받는 것: 없음. 돌려주는 것: 날짜·시각 글자.
 */
function localDateTime() {
  // 시간대 차이만큼 미리 더해 두면 toISOString(UTC 표기)이 로컬 시각처럼 보이게 돼요.
  const date = new Date(Date.now() - new Date().getTimezoneOffset() * 60000);
  return date.toISOString().slice(0, 16);
}

/** 진입 이유 코드(예: MOMENTUM)를 한국어 이름으로 바꿔요. 받는 것: code. 돌려주는 것: 이름, 모르는 코드면 그대로. */
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

/**
 * 새 거래 입력 폼 React 컴포넌트(화면 조각)예요. 비유하면 "가계부에 한 줄 적는 칸"이에요.
 * 받는 것(props, 부모가 넘겨주는 값): done(저장이 끝나면 부르는 함수).
 * 돌려주는 것: 매수/매도, 종목코드, 일시, 가격, 수량 입력 폼 화면.
 */
function TradeEditor({ done }: { done: () => void }) {
  // 상태(state, 컴포넌트가 기억하는 값): 입력칸 값들이에요.
  const [stockCode, setStockCode] = useState(""),
    [type, setType] = useState<"BUY" | "SELL">("BUY"),
    [at, setAt] = useState(localDateTime()),
    [price, setPrice] = useState(""),
    [quantity, setQuantity] = useState("");
  // useMutation(서버 데이터를 바꾸는 요청 훅(hook))으로 거래를 저장해요.
  const create = useMutation({
    mutationFn: () =>
      api<Trade>("/api/v1/trades", {
        method: "POST",
        // Idempotency-Key(같은 요청 중복 저장 방지용 키)를 요청마다 새로 만들어요.
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({
          stockCode: stockCode.trim().toUpperCase(),
          tradeType: type,
          tradedAt: new Date(at).toISOString(),
          price: Number(price),
          quantity: Number(quantity),
        }),
      }),
    // 저장 성공하면 가격·수량만 비우고(종목·일시는 유지) 부모에게 알려요.
    onSuccess: () => {
      setPrice("");
      setQuantity("");
      done();
    },
  });
  /** 폼 제출 시 저장 요청을 보내요. 받는 것: e(폼 제출 이벤트). 돌려주는 것: 없음. */
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

/**
 * 거래 한 건의 투자 노트 편집기예요. 비유하면 "가계부 줄 옆에 붙이는 포스트잇 메모"예요.
 * 받는 것(props): trade(편집할 거래), done(저장이 끝나면 부르는 함수).
 * 돌려주는 것: 메모, 목표가, 손절가, 진입 이유 태그 편집 화면.
 */
function JournalEditor({ trade, done }: { trade: Trade; done: () => void }) {
  // 기존 노트가 있으면 그 값으로 입력칸을 채워요.
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
  // PUT으로 노트를 통째로 저장해요. version으로 다른 곳에서 먼저 고쳤는지 서버가 확인하는 것으로 보여요.
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

/**
 * 투자기록 화면 전체 컴포넌트예요. 비유하면 "매매 일지 노트"로, 새 기록 칸과 지난 거래 목록이 함께 있어요.
 * 받는 것: 없음(props 없음). 돌려주는 것: 누적 실현손익, 새 거래 입력, 거래 내역 화면.
 */
export function JournalPage() {
  // editing은 지금 노트를 펼친 거래의 id예요(없으면 null).
  const cache = useQueryClient(),
    [editing, setEditing] = useState<number | null>(null);
  const trades = useQuery({
    queryKey: ["trades"],
    queryFn: () => api<Trade[]>("/api/v1/trades?limit=100"),
  });
  // 저장·삭제 뒤 거래 목록 캐시를 무효화해서 다시 불러와요.
  const refresh = () => cache.invalidateQueries({ queryKey: ["trades"] });
  // ⚠️ 확인 필요: 삭제 버튼을 누르면 확인 질문 없이 바로 지워지는 것으로 보여요.
  const remove = useMutation({
    mutationFn: (id: number) =>
      api(`/api/v1/trades/${id}`, { method: "DELETE" }),
    onSuccess: refresh,
  });
  // 불러온 거래(최대 100건)의 실현손익을 더해요. 100건을 넘으면 전체 누적과 다를 수 있어요.
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
