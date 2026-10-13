"""ADR-008 judge for closing-recommend-v9_1-1515 (BASELINE vs NEXT_OPEN).

Reads the TSV files written by ExportClosingV9.java and applies the pre-registered
decision rules of docs/decisions/ADR-008-CLOSING-V9-DECISION-CRITERIA.md.

    python -I judge.py --data data [--json out.json] [--details] [--save-dir DIR]
    python -I judge.py --selftest

No database access. Standard library only.
"""
import argparse
import csv
import json
import os
import random
import sys
from collections import defaultdict
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP

KST = timezone(timedelta(hours=9))
SESSION_OPEN = time(9, 0)
SESSION_CLOSE = time(15, 30)
# KRX closing call: 15:20-15:30 has no executions; the closing print (15:30:00..15:30:30, random end) is
# bucketed into the 15:25 bar. Mirrors NextSessionCoverage / FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW.
CLOSING_CALL_START = time(15, 20)
CLOSING_PRINT_BAR = time(15, 25)
SETTLE_AFTER_CLOSE = timedelta(minutes=2)
STRATEGY_VERSION = "closing-recommend-v9_1-1515"

# ADR-008 constants. Changing any of these requires a new ADR.
TARGET_RATE = Decimal("3")
STOP_RATE = Decimal("-2")
CHECKPOINTS = [(60, 15, "1차"), (120, 30, "연장")]
INTERIM_MIN_ENTRIES = 30
MAX_MISSING_RATIO = 0.20
BOOTSTRAP_RESAMPLES = 10_000
BOOTSTRAP_SEED = 20261013

# Cost scenarios in percent. BASE = .env CLOSING_* values recorded in ADR-008 (buy 0.095 / sell 0.295).
# The BASE split assumed here: buy fee 0.015 + other 0.08; sell fee 0.015 + tax 0.20 + other 0.08.
# HIGH replaces the per-side non-fee/non-tax part (slippage incl. spread) with 0.15.
COSTS = {
    "BASE": {"buy": Decimal("0.095"), "sell": Decimal("0.295")},
    "HIGH": {"buy": Decimal("0.165"), "sell": Decimal("0.365")},
}

# Static KRX closures used only when trading_calendar_day has no row for a date.
# (Mirrors ClosingTradingCalendar: weekends + verified closures + configured holidays.)
STATIC_HOLIDAYS = {
    date(2026, 9, 24), date(2026, 9, 25),  # VERIFIED_SPECIAL_CLOSURES (Chuseok)
    date(2026, 10, 5),                     # substitute holiday
    date(2026, 10, 9),                     # Hangul Day
    date(2026, 12, 25), date(2026, 12, 31),
}

HUNDRED = Decimal(100)


# ---------------------------------------------------------------------------
# Re-implementation of OvernightExecutionSimulator (overnight-execution-v1)
# ---------------------------------------------------------------------------
def _pct(v):
    return (v / HUNDRED).quantize(Decimal("1e-10"), rounding=ROUND_HALF_UP)


def _rate(price, base):
    r = ((price - base) / base).quantize(Decimal("1e-10"), rounding=ROUND_HALF_UP) * HUNDRED
    return r.quantize(Decimal("1e-6"), rounding=ROUND_HALF_UP)


def net_return(entry, exit_price, buy_cost, sell_cost):
    """Same formula as OvernightExecutionSimulator.exit: rate(sellCash, buyCash)."""
    buy_cash = entry * (Decimal(1) + _pct(buy_cost))
    sell_cash = exit_price * (Decimal(1) - _pct(sell_cost))
    return _rate(sell_cash, buy_cash)


def target_or_stop(entry, bars, target_rate=TARGET_RATE, stop_rate=STOP_RATE):
    """Returns (exit_time, exit_price, reason, ambiguous) or None. bars: list of dicts sorted by start."""
    if entry is None or entry <= 0 or not bars:
        return None
    target = entry * (Decimal(1) + _pct(target_rate))
    stop = entry * (Decimal(1) + _pct(stop_rate))
    for b in bars:
        if b["open"] <= stop:
            return b["start"], b["open"], "GAP_STOP", False
        if b["open"] >= target:
            return b["start"], b["open"], "GAP_TARGET", False
        t_hit = b["high"] >= target
        s_hit = b["low"] <= stop
        if t_hit and s_hit:
            return b["start"], stop, "AMBIGUOUS_STOP", True
        if s_hit:
            return b["start"], stop, "STOP", False
        if t_hit:
            return b["start"], target, "TARGET", False
    last = bars[-1]
    return last["start"], last["close"], "TIME_EXIT", False


def next_open(bars, session_open):
    """NEXT_OPEN arm: first 5-minute bar open of the next session (must be the 09:00 bar)."""
    if not bars or bars[0]["start"] != session_open:
        return None
    return bars[0]["start"], bars[0]["open"], "NEXT_OPEN", False


# ---------------------------------------------------------------------------
# Calendar and session coverage (mirrors OvernightPerformanceService.evaluate / NextSessionCoverage)
# ---------------------------------------------------------------------------
class Calendar:
    def __init__(self, official=None, holidays=STATIC_HOLIDAYS):
        self.official = official or {}
        self.holidays = set(holidays)

    def is_trading_day(self, d):
        if d in self.official:
            return self.official[d]
        return d.weekday() < 5 and d not in self.holidays

    def next_trading_day(self, d):
        n = d + timedelta(days=1)
        while not self.is_trading_day(n):
            n += timedelta(days=1)
        return n


def kst_instant(d, t):
    return datetime.combine(d, t, KST).astimezone(timezone.utc)


def valid_prices(b):
    return (b["low"] > 0 and b["high"] >= max(b["open"], b["close"]) and b["low"] <= min(b["open"], b["close"]))


def session_series(bars, session_date, now):
    """Returns (status, series, missing_starts).

    Complete = every 09:00..15:15 bar exactly once + the 15:25 closing-print bar exactly once with volume > 0.
    The 15:20 closing-call bar is optional (structurally empty). Before close + 2 min -> IN_PROGRESS.
    """
    open_, close = kst_instant(session_date, SESSION_OPEN), kst_instant(session_date, SESSION_CLOSE)
    call_start, print_start = kst_instant(session_date, CLOSING_CALL_START), kst_instant(session_date, CLOSING_PRINT_BAR)
    settled = close + SETTLE_AFTER_CLOSE

    def end(start):
        e = start + timedelta(minutes=5)
        return close if e > close else e

    series = [b for b in bars if b["final"] and open_ <= b["start"] <= close
              and int((b["start"] - open_).total_seconds()) % 300 == 0
              and not end(b["start"]) > now and valid_prices(b)]
    series.sort(key=lambda b: b["start"])
    counts = defaultdict(int)
    for b in series:
        counts[b["start"]] += 1
    missing = []
    s = open_
    while s < call_start and not s + timedelta(minutes=5) > now:
        if counts.get(s, 0) != 1:
            missing.append(s)
        s += timedelta(minutes=5)
    if now >= settled:
        prints = [b for b in series if b["start"] == print_start]
        if len(prints) != 1 or not (prints[0].get("volume") or 0) > 0:
            missing.append(print_start)
    if now < open_:
        status = "PENDING"
    elif now < settled:
        status = "IN_PROGRESS"
    elif not series:
        status = "DATA_MISSING"
    elif missing:
        status = "DATA_INCOMPLETE"
    else:
        status = "COMPLETED"
    return status, series, missing


# ---------------------------------------------------------------------------
# Statistics and verdict
# ---------------------------------------------------------------------------
def mean(xs):
    return sum(xs) / len(xs) if xs else None


def profit_factor(xs):
    gain = sum(x for x in xs if x > 0)
    loss = -sum(x for x in xs if x < 0)
    if loss == 0:
        return float("inf") if gain > 0 else None
    return gain / loss


def day_block_bootstrap(by_day, resamples=BOOTSTRAP_RESAMPLES, seed=BOOTSTRAP_SEED):
    """Resample trading days with replacement; statistic = pooled per-entry mean Net."""
    days = sorted(by_day)
    if not days:
        return None, None
    rng = random.Random(seed)
    sums = [sum(by_day[d]) for d in days]
    counts = [len(by_day[d]) for d in days]
    k = len(days)
    stats = []
    for _ in range(resamples):
        s = n = 0
        for _ in range(k):
            i = rng.randrange(k)
            s += sums[i]
            n += counts[i]
        stats.append(s / n)
    stats.sort()
    lo = stats[int(0.025 * resamples)]
    hi = stats[min(resamples - 1, int(0.975 * resamples) - 1)]
    return lo, hi


def mean_without_top_days(by_day, top=3):
    days = sorted(by_day, key=lambda d: sum(by_day[d]), reverse=True)
    keep = [x for d in days[top:] for x in by_day[d]]
    return mean(keep)


def arm_stats(trades, resamples=BOOTSTRAP_RESAMPLES):
    """trades: list of dicts with keys day, BASE, HIGH."""
    out = {"completed": len(trades), "trading_days": len({t["day"] for t in trades})}
    for sc in COSTS:
        xs = [t[sc] for t in trades]
        out[sc] = {"mean_net": mean(xs), "net_pf": profit_factor(xs),
                   "win_rate": (sum(1 for x in xs if x > 0) / len(xs)) if xs else None}
    by_day = defaultdict(list)
    for t in trades:
        by_day[t["day"]].append(t["BASE"])
    lo, hi = day_block_bootstrap(by_day, resamples)
    out["BASE"]["ci95"] = [lo, hi]
    out["BASE"]["mean_without_top3_days"] = mean_without_top_days(by_day)
    out["day_means_BASE"] = {str(d): mean(v) for d, v in sorted(by_day.items())}
    return out


def checkpoint_stage(n, days):
    stage = None
    for need_n, need_d, label in CHECKPOINTS:
        if n >= need_n and days >= need_d:
            stage = label
    return stage


def verdict(stats, missing_ratio):
    n, days = stats["completed"], stats["trading_days"]
    b, h = stats["BASE"], stats["HIGH"]
    lo, hi = b["ci95"]
    stage = checkpoint_stage(n, days)
    if stage is None:
        interim_stop = n >= INTERIM_MIN_ENTRIES and hi is not None and hi < 0
        return {"checkpoint": None, "label": "체크포인트 미도달",
                "interim_stop": interim_stop,
                "interim": ("중단 (중간 확인: %d건 >= %d, 95%% 상한 < 0)" % (n, INTERIM_MIN_ENTRIES)) if interim_stop
                else "중단 조건 미해당 (%d건, 기준 >= %d건이고 95%% 상한 < 0)" % (n, INTERIM_MIN_ENTRIES)}
    if missing_ratio is not None and missing_ratio > MAX_MISSING_RATIO:
        return {"checkpoint": stage, "label": "판정 보류 (결측 %.1f%% > 20%%, 다음 체크포인트로 연기)" % (missing_ratio * 100)}
    m, pf = b["mean_net"], b["net_pf"]
    if m <= 0 or (pf is not None and pf < 1.0):
        label = "중단"
    elif (lo > 0 and pf is not None and pf >= 1.2 and h["mean_net"] > 0
          and b["mean_without_top3_days"] is not None and b["mean_without_top3_days"] > 0):
        label = "실거래 검토"
    else:
        label = "계속" if stage == "1차" else "중단 (연장 후에도 계속 조건)"
    return {"checkpoint": stage, "label": label}


# ---------------------------------------------------------------------------
# Data loading and evaluation
# ---------------------------------------------------------------------------
def read_tsv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def parse_ts(s):
    if not s:
        return None
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def dec(s):
    return Decimal(s) if s not in (None, "") else None


def load(data_dir):
    meta = {r["key"]: r["value"] for r in read_tsv(os.path.join(data_dir, "meta.tsv"))}
    entries = read_tsv(os.path.join(data_dir, "entries.tsv"))
    bars = defaultdict(list)
    for r in read_tsv(os.path.join(data_dir, "bars.tsv")):
        bars[r["stock_code"]].append({
            "start": parse_ts(r["start_time"]), "open": dec(r["open"]), "high": dec(r["high"]),
            "low": dec(r["low"]), "close": dec(r["close"]), "volume": int(r.get("volume") or 0),
            "final": r["is_final"] in ("t", "true")})
    official = {date.fromisoformat(r["trade_date"]): r["market_open"] in ("t", "true")
                for r in read_tsv(os.path.join(data_dir, "calendar.tsv"))}
    return meta, entries, bars, official


def evaluate(entries, bars, cal, now):
    rows = []
    for e in entries:
        rec_date = date.fromisoformat(e["recommendation_date"])
        expected = e.get("expected_session_date") or ""
        session = date.fromisoformat(expected) if expected else cal.next_trading_day(rec_date)
        close = kst_instant(session, SESSION_CLOSE)
        disposition = e.get("disposition") or ("SELECTED" if e.get("rec_id") else "")
        if e.get("rec_id"):
            disposition = "SELECTED"
        row = {"run_id": e["run_id"], "day": rec_date, "session": session, "stock_code": e["stock_code"],
               "disposition": disposition, "entry_status": e["entry_status"],
               "is_etf": e.get("is_etf") in ("t", "true"), "session_done": now >= close + SETTLE_AFTER_CLOSE}
        entry_price = dec(e.get("entry_price"))
        row["entry_price"] = entry_price
        if e["entry_status"] != "ENTERED" or entry_price is None:
            row["state"] = "NO_ENTRY"
        else:
            status, series, missing = session_series(bars.get(e["stock_code"], []), session, now)
            row["state"] = status
            row["missing_bars"] = len(missing)
            # Diagnostics only: incomplete solely because the 15:25 closing print is missing, and whether the
            # 09:00 bar needed by NEXT_OPEN exists.
            miss_kst = {m.astimezone(KST).time() for m in missing}
            row["closing_print_only_gap"] = bool(missing) and miss_kst <= {CLOSING_PRINT_BAR}
            row["has_open_bar"] = bool(series) and series[0]["start"] == kst_instant(session, SESSION_OPEN)
            if status == "COMPLETED":
                for arm, res in (("BASELINE", target_or_stop(entry_price, series)),
                                 ("NEXT_OPEN", next_open(series, kst_instant(session, SESSION_OPEN)))):
                    if res is None:
                        row[arm] = None
                        continue
                    at, px, reason, amb = res
                    row[arm] = {"exit_at": at.isoformat(), "exit_price": px, "reason": reason, "ambiguous": amb,
                                **{sc: net_return(entry_price, px, c["buy"], c["sell"]) for sc, c in COSTS.items()}}
        # Official stored outcome (only exists for closing_recommendation rows).
        if e.get("perf_id"):
            row["official"] = {k: e.get(k) for k in ("perf_status", "execution_exit_price", "net_return_rate",
                                                     "execution_exit_reason", "virtual_entry_price",
                                                     "cost_assumption", "execution_model_version")}
        rows.append(row)
    return rows


def cross_check(rows):
    """Compare BASELINE recomputation against stored official executions (official costs)."""
    mismatches, checked = [], 0
    for r in rows:
        o = r.get("official")
        if not o or o.get("perf_status") != "COMPLETED" or not o.get("net_return_rate"):
            continue
        b = r.get("BASELINE")
        checked += 1
        if not b:
            mismatches.append({"stock_code": r["stock_code"], "day": str(r["day"]), "issue": "official COMPLETED, judge has no BASELINE result"})
            continue
        try:
            ca = json.loads(o.get("cost_assumption") or "{}")
            buy = Decimal(ca.get("buyFeePercent", "0")) + Decimal(ca.get("buySlippagePercent", "0"))
            sell = (Decimal(ca.get("sellFeePercent", "0")) + Decimal(ca.get("sellTaxPercent", "0"))
                    + Decimal(ca.get("sellSlippagePercent", "0")))
        except (ValueError, ArithmeticError):
            buy, sell = COSTS["BASE"]["buy"], COSTS["BASE"]["sell"]
        mine = net_return(r["entry_price"], b["exit_price"], buy, sell)
        issues = []
        if o.get("virtual_entry_price") and Decimal(o["virtual_entry_price"]) != r["entry_price"]:
            issues.append("entry %s != %s" % (o["virtual_entry_price"], r["entry_price"]))
        if o.get("execution_exit_reason") and o["execution_exit_reason"] != b["reason"]:
            issues.append("reason %s != %s" % (o["execution_exit_reason"], b["reason"]))
        if o.get("execution_exit_price") and abs(Decimal(o["execution_exit_price"]) - b["exit_price"]) > Decimal("0.01"):
            issues.append("exit %s != %s" % (o["execution_exit_price"], b["exit_price"]))
        if abs(Decimal(o["net_return_rate"]) - mine) > Decimal("0.0001"):
            issues.append("net %s != %s" % (o["net_return_rate"], mine))
        if issues:
            mismatches.append({"stock_code": r["stock_code"], "day": str(r["day"]), "issue": "; ".join(issues)})
    return checked, mismatches


def summarize(rows, label, decide=True, resamples=BOOTSTRAP_RESAMPLES):
    done = [r for r in rows if r["session_done"]]
    pending = [r for r in rows if not r["session_done"]]
    completed = [r for r in done if r.get("state") == "COMPLETED"]
    missing = [r for r in done if r.get("state") != "COMPLETED"]
    by_state = defaultdict(int)
    for r in done:
        by_state[r["state"]] += 1
    out = {"sample": label, "rows": len(rows), "session_pending": len(pending),
           "session_done": len(done), "state_counts": dict(by_state),
           "diag_closing_print_only_gap": sum(1 for r in done if r.get("closing_print_only_gap")),
           "diag_entered_with_0900_bar": sum(1 for r in done if r.get("has_open_bar")),
           "missing_ratio": (len(missing) / len(done)) if done else None, "arms": {}}
    for arm in ("BASELINE", "NEXT_OPEN"):
        trades = [{"day": r["day"], **{sc: float(r[arm][sc]) for sc in COSTS}}
                  for r in completed if r.get(arm)]
        unc = sum(1 for r in completed if r.get(arm) is None or r[arm]["ambiguous"])
        st = arm_stats(trades, resamples)
        st["uncertain"] = unc
        st["uncertain_ratio"] = (unc / len(completed)) if completed else None
        if decide:
            st["verdict"] = verdict(st, out["missing_ratio"])
        out["arms"][arm] = st
    return out


def fmt(x, pct=True):
    if x is None:
        return "-"
    if x == float("inf"):
        return "inf"
    return ("%+.3f%%" % x) if pct else ("%.3f" % x)


def render(s, decide=True):
    lines = ["== %s ==" % s["sample"],
             "대상 %d건 (다음 세션 미종료 %d건 제외), 세션 종료 %d건, 상태 %s" % (
                 s["rows"], s["session_pending"], s["session_done"], json.dumps(s["state_counts"], ensure_ascii=False)),
             "결측/판정불가 비율: %s" % ("-" if s["missing_ratio"] is None else "%.1f%%" % (s["missing_ratio"] * 100)),
             "진단: 15:25 종가 체결 봉만 빠져 미완료 %d건, 09:00 봉 보유 진입 %d건" % (
                 s["diag_closing_print_only_gap"], s["diag_entered_with_0900_bar"])]
    for arm, st in s["arms"].items():
        b, h = st["BASE"], st["HIGH"]
        lines.append("[%s] 완료 %d건 / %d거래일, 불확실(ambiguous/계산불가) %d건" % (
            arm, st["completed"], st["trading_days"], st["uncertain"]))
        lines.append("  평균 Net BASE %s (승률 %s, PF %s) | HIGH %s (PF %s)" % (
            fmt(b["mean_net"]), "-" if b["win_rate"] is None else "%.1f%%" % (b["win_rate"] * 100),
            fmt(b["net_pf"], False), fmt(h["mean_net"]), fmt(h["net_pf"], False)))
        lines.append("  day-block bootstrap 95%% CI (BASE, %d회, seed %d): [%s, %s] | 상위 3거래일 제외 평균 %s" % (
            BOOTSTRAP_RESAMPLES, BOOTSTRAP_SEED, fmt(b["ci95"][0]), fmt(b["ci95"][1]), fmt(b["mean_without_top3_days"])))
        if decide:
            v = st["verdict"]
            if v["checkpoint"] is None:
                lines.append("  판정: 체크포인트 미도달 (필요: 60건 AND 15거래일). 중간 확인: %s" % v["interim"])
            else:
                lines.append("  판정 (%s 체크포인트): %s" % (v["checkpoint"], v["label"]))
        else:
            lines.append("  (참고용, 판정에 합산하지 않음)")
    return "\n".join(lines)


def to_jsonable(o):
    if isinstance(o, dict):
        return {str(k): to_jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [to_jsonable(v) for v in o]
    if isinstance(o, Decimal):
        return str(o)
    if isinstance(o, (date, datetime)):
        return o.isoformat()
    if isinstance(o, float) and o == float("inf"):
        return "inf"
    return o


def run(args):
    meta, entries, bars, official = load(args.data)
    if meta.get("strategy_version") != STRATEGY_VERSION:
        print("WARNING: export strategy_version=%s" % meta.get("strategy_version"))
    now = parse_ts(args.now) if args.now else parse_ts(meta["exported_at"])
    cal = Calendar(official)
    rows = evaluate(entries, bars, cal, now)
    primary = [r for r in rows if r["disposition"] == "SELECTED"]
    secondary = [r for r in rows if r["disposition"] != "SELECTED"]
    checked, mismatches = cross_check(rows)
    p = summarize(primary, "1차 표본: v9 FORWARD SELECTED 추천의 paper entry (ADR-008 판정 대상)")
    s = summarize(secondary, "참고: v9 FORWARD 관측 후보(WATCH/EXCLUDED)의 paper entry (2차 표본, 판정 비합산)", decide=False)
    text = ["ADR-008 closing v9 judge — export as-of %s (평가 기준 시각 %s)" % (meta.get("exported_at"), now.isoformat()),
            "비용 BASE 매수 %s%% / 매도 %s%%, HIGH 매수 %s%% / 매도 %s%%" % (
                COSTS["BASE"]["buy"], COSTS["BASE"]["sell"], COSTS["HIGH"]["buy"], COSTS["HIGH"]["sell"]),
            "runs: %s" % ", ".join(sorted({"%s(run %s)" % (r["day"], r["run_id"]) for r in rows})),
            "", render(p), "", render(s, decide=False), "",
            "공식 저장 성과 대조: %d건 확인, 불일치 %d건" % (checked, len(mismatches))]
    for m in mismatches:
        text.append("  MISMATCH %s" % json.dumps(m, ensure_ascii=False))
    if args.details:
        text.append("")
        text.append("day\tsession\tstock\tdisposition\tentry_status\tstate\tentry\tBASELINE(reason,exit,BASE)\tNEXT_OPEN(exit,BASE)")
        for r in rows:
            b, n = r.get("BASELINE"), r.get("NEXT_OPEN")
            text.append("\t".join(str(x) for x in (
                r["day"], r["session"], r["stock_code"], r["disposition"], r["entry_status"], r.get("state"),
                r["entry_price"], "%s,%s,%s" % (b["reason"], b["exit_price"], b["BASE"]) if b else "-",
                "%s,%s" % (n["exit_price"], n["BASE"]) if n else "-")))
    output = "\n".join(text)
    print(output)
    summary = {"exported_at": meta.get("exported_at"), "evaluated_as_of": now, "strategy_version": meta.get("strategy_version"),
               "costs": COSTS, "primary": p, "secondary": s,
               "official_cross_check": {"checked": checked, "mismatches": mismatches}}
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(to_jsonable(summary), f, ensure_ascii=False, indent=2)
    if args.save_dir:
        os.makedirs(args.save_dir, exist_ok=True)
        with open(os.path.join(args.save_dir, "judge_output.txt"), "w", encoding="utf-8") as f:
            f.write(output + "\n")
        with open(os.path.join(args.save_dir, "judge_summary.json"), "w", encoding="utf-8") as f:
            json.dump(to_jsonable(summary), f, ensure_ascii=False, indent=2)


# ---------------------------------------------------------------------------
# Self test (synthetic data, no DB)
# ---------------------------------------------------------------------------
def selftest():
    D = Decimal
    ok = True

    def check(name, cond):
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + name)
        ok = ok and cond

    t0 = datetime(2026, 1, 5, 0, 0, tzinfo=timezone.utc)

    def bar(i, o, h, l, c):
        return {"start": t0 + timedelta(minutes=5 * i), "open": D(o), "high": D(h), "low": D(l), "close": D(c), "final": True}

    e = D(10000)
    check("gap stop", target_or_stop(e, [bar(0, 9790, 9900, 9700, 9800)])[2:] == ("GAP_STOP", False))
    check("gap target", target_or_stop(e, [bar(0, 10300, 10400, 10250, 10350)])[1:3] == (D(10300), "GAP_TARGET"))
    r = target_or_stop(e, [bar(0, 10000, 10300, 9800, 10000)])
    check("ambiguous -> stop price", r[2] == "AMBIGUOUS_STOP" and r[1] == D("9800.0000000000") and r[3])
    check("target", target_or_stop(e, [bar(0, 10000, 10100, 9950, 10050), bar(1, 10050, 10310, 10000, 10200)])[1:3]
          == (D("10300.0000000000"), "TARGET"))
    check("time exit", target_or_stop(e, [bar(0, 10000, 10100, 9950, 10050), bar(1, 10050, 10100, 9990, 10020)])[1:3]
          == (D(10020), "TIME_EXIT"))
    # Net formula: buy 10000*1.00095, sell 10100*(1-0.00295) -> (10070.205-10009.5)/10009.5*100 = 0.606474
    check("net formula", net_return(D(10000), D(10100), D("0.095"), D("0.295")) == D("0.606474"))
    check("next open requires 09:00 bar", next_open([bar(1, 1, 1, 1, 1)], t0) is None)

    # Session coverage: a full 78-bar session is COMPLETED, one gap -> DATA_INCOMPLETE, before close -> IN_PROGRESS.
    sd = date(2026, 1, 5)
    full = [{"start": kst_instant(sd, SESSION_OPEN) + timedelta(minutes=5 * i), "open": D(100), "high": D(101),
             "low": D(99), "close": D(100), "volume": 10, "final": True} for i in range(78)]
    after = kst_instant(sd, SESSION_CLOSE) + timedelta(minutes=2)
    check("coverage complete", session_series(full, sd, after)[0] == "COMPLETED")
    check("coverage gap", session_series(full[:40] + full[41:], sd, after)[0] == "DATA_INCOMPLETE")
    check("coverage 15:20 call bar optional", session_series(full[:76] + full[77:], sd, after)[0] == "COMPLETED")
    no_print = session_series(full[:77], sd, after)
    check("coverage requires 15:25 closing print", no_print[0] == "DATA_INCOMPLETE"
          and no_print[2] == [kst_instant(sd, CLOSING_PRINT_BAR)])
    padded = full[:77] + [dict(full[77], volume=0)]
    check("coverage zero-volume 15:25 is not a print", session_series(padded, sd, after)[0] == "DATA_INCOMPLETE")
    check("coverage settles 2 min after close",
          session_series(full[:76], sd, kst_instant(sd, SESSION_CLOSE) + timedelta(seconds=90))[0] == "IN_PROGRESS")
    check("coverage in progress", session_series(full, sd, kst_instant(sd, time(12, 0)))[0] == "IN_PROGRESS")
    cal = Calendar({}, {date(2026, 10, 9)})
    check("calendar skips holiday+weekend", cal.next_trading_day(date(2026, 10, 8)) == date(2026, 10, 12))

    def synth(days, per_day, mu, sd_, seed):
        rng = random.Random(seed)
        out = []
        for d in range(days):
            day_shock = rng.gauss(0, sd_ / 2)
            for _ in range(per_day):
                x = mu + day_shock + rng.gauss(0, sd_)
                out.append({"day": date(2026, 1, 1) + timedelta(days=d), "BASE": x, "HIGH": x - 0.14})
        return out

    good = arm_stats(synth(20, 4, 0.8, 0.6, 1), 2000)
    check("CI contains mean", good["BASE"]["ci95"][0] < good["BASE"]["mean_net"] < good["BASE"]["ci95"][1])
    check("verdict 실거래 검토", verdict(good, 0.05)["label"] == "실거래 검토")
    check("verdict 보류 on missing > 20%", verdict(good, 0.25)["label"].startswith("판정 보류"))
    bad = arm_stats(synth(20, 4, -0.5, 0.6, 2), 2000)
    check("verdict 중단", verdict(bad, 0.0)["label"] == "중단")
    weak = arm_stats(synth(20, 4, 0.1, 1.5, 3), 2000)
    check("verdict 계속 or 중단 for weak", verdict(weak, 0.0)["label"] in ("계속", "중단"))
    small_bad = arm_stats(synth(8, 4, -1.0, 0.3, 4), 2000)
    v = verdict(small_bad, 0.0)
    check("interim stop (32 entries, CI upper < 0)", v["checkpoint"] is None and v["interim_stop"])
    small = arm_stats(synth(5, 4, -1.0, 0.3, 5), 2000)
    v = verdict(small, 0.0)
    check("checkpoint not reached, no interim stop below 30", v["checkpoint"] is None and not v["interim_stop"])
    ext = arm_stats(synth(30, 4, 0.1, 1.5, 6), 2000)
    check("extension stage detected", verdict(ext, 0.0)["checkpoint"] == "연장")
    # Top-3 concentration: profits only on 3 days -> mean without top3 <= 0 blocks 실거래 검토.
    conc = [{"day": date(2026, 1, 1) + timedelta(days=d), "BASE": (6.0 if d < 3 else -0.05), "HIGH": (5.9 if d < 3 else -0.2)}
            for d in range(16) for _ in range(4)]
    cs = arm_stats(conc, 2000)
    check("top-3 concentration blocks 실거래 검토", verdict(cs, 0.0)["label"] != "실거래 검토"
          and cs["BASE"]["mean_without_top3_days"] < 0)
    # Bootstrap is deterministic for a fixed seed.
    byd = defaultdict(list)
    for t in synth(10, 3, 0.2, 0.5, 7):
        byd[t["day"]].append(t["BASE"])
    check("bootstrap deterministic", day_block_bootstrap(byd, 500) == day_block_bootstrap(byd, 500))
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
    ap.add_argument("--now", help="override evaluation instant (ISO-8601); default = export time")
    ap.add_argument("--json", help="write summary JSON")
    ap.add_argument("--save-dir", help="write judge_output.txt and judge_summary.json (checkpoint record)")
    ap.add_argument("--details", action="store_true", help="print per-entry rows")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if args.selftest:
        sys.exit(selftest())
    run(args)


if __name__ == "__main__":
    main()
