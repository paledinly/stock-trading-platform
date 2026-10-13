"""Point-in-time all-eligible-stock overnight study (pre-registered in README.md).

Usage:
    python -I analyze.py data/adjusted.tsv results.json results.md
    python -I analyze.py --selftest

Standard library only. Every eligibility and subset rule uses information known at the close of d.
The outcome day d' is never used to select a stock-day.
"""
import json
import random
import sys
from collections import defaultdict

CALENDAR_MIN_STOCKS = 1000
HISTORY_DAYS = 20
EXTREME_MOVE = 0.305
LIMIT_UP = 0.29
MOM_TOP_SHARE = 0.10
NEAR_HIGH = 0.99
EXPLORATION_SHARE = 0.6
RESAMPLES = 10000
SEED = 20261013
COSTS = {"BASE": (0.00095, 0.00295), "HIGH": (0.00165, 0.00365)}
SETS = ["ALL", "LIMIT_UP", "MOM", "REGIME_UP", "REGIME_DOWN"]
LEGS = ["ON", "ID", "CC"]


def net(entry, exit_, cost):
    b, s = cost
    return (exit_ * (1 - s) - entry * (1 + b)) / (entry * (1 + b))


def common_share(code):
    return len(code) == 6 and code.endswith("0")


def load(path):
    series = defaultdict(dict)
    meta = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                key, _, value = line[1:].strip().partition("=")
                meta[key] = value
                continue
            if line.startswith("stock_code\t"):
                continue
            code, d, o, h, l, c, v, tv = line.rstrip("\n").split("\t")
            series[code][d] = (float(o), float(h), float(l), float(c), int(v), float(tv))
    return series, meta


def build(series):
    """Returns (observations, calendar, counters). One observation per eligible stock-day d with d' present."""
    per_date = defaultdict(int)
    for rows in series.values():
        for d in rows:
            per_date[d] += 1
    calendar = sorted(d for d, n in per_date.items() if n >= CALENDAR_MIN_STOCKS)
    pos = {d: i for i, d in enumerate(calendar)}
    counters = defaultdict(int)
    candidates = defaultdict(list)  # d -> list of dicts (eligible at d, before d' check)
    for code, rows in series.items():
        if not common_share(code):
            counters["excluded_not_common"] += 1
            continue
        for i in range(HISTORY_DAYS, len(calendar)):
            d = calendar[i]
            if d not in rows:
                continue
            window = calendar[i - HISTORY_DAYS:i]  # d-20 .. d-1
            if any(w not in rows for w in window):
                counters["excluded_history"] += 1
                continue
            closes = [rows[w][3] for w in window] + [rows[d][3]]
            rets = [closes[k] / closes[k - 1] - 1 for k in range(1, len(closes))]  # d-19..d, each vs previous
            if any(abs(r) > EXTREME_MOVE for r in rets):
                counters["excluded_extreme_move"] += 1
                continue
            if i + 1 >= len(calendar):
                continue  # no d' in the calendar: d is not evaluable
            d_next = calendar[i + 1]
            o, h, l, c, v, tv = rows[d]
            candidates[d].append({"code": code, "d": d, "d_next": d_next, "r_d": rets[-1], "close": c, "high": h,
                                  "next": rows.get(d_next)})
    observations = []
    for d in sorted(candidates):
        items = candidates[d]
        regime_mean = sum(x["r_d"] for x in items) / len(items)
        not_limit = [x for x in items if x["r_d"] < LIMIT_UP]
        ranked = sorted(not_limit, key=lambda x: x["r_d"])
        cutoff_index = int(len(ranked) * (1 - MOM_TOP_SHARE))
        top = {x["code"] for x in ranked[cutoff_index:]}
        for x in items:
            if x["next"] is None:
                counters["missing_next_day"] += 1
                continue
            no, nh, nl, nc, nv, ntv = x["next"]
            sets = ["ALL", "REGIME_UP" if regime_mean > 0 else "REGIME_DOWN"]
            if x["r_d"] >= LIMIT_UP:
                sets.append("LIMIT_UP")
            elif x["code"] in top and x["close"] >= NEAR_HIGH * x["high"]:
                sets.append("MOM")
            obs = {"code": x["code"], "d": d, "sets": sets,
                   "gross": {"ON": no / x["close"] - 1, "ID": nc / no - 1, "CC": nc / x["close"] - 1}}
            for name, cost in COSTS.items():
                obs[name] = {"ON": net(x["close"], no, cost), "ID": net(no, nc, cost), "CC": net(x["close"], nc, cost)}
            observations.append(obs)
    return observations, calendar, dict(counters)


def median(values):
    s = sorted(values)
    m = len(s) // 2
    return s[m] if len(s) % 2 else (s[m - 1] + s[m]) / 2


def block_ci(selected, value):
    by = defaultdict(list)
    for o in selected:
        by[o["d"]].append(value(o))
    days = sorted(by)
    if len(days) < 2:
        return None
    sums = [sum(by[d]) for d in days]
    counts = [len(by[d]) for d in days]
    rng = random.Random(SEED)
    k = len(days)
    stats = []
    for _ in range(RESAMPLES):
        s = n = 0
        for _ in range(k):
            i = rng.randrange(k)
            s += sums[i]
            n += counts[i]
        stats.append(s / n)
    stats.sort()
    return [stats[int(0.025 * RESAMPLES)], stats[int(0.975 * RESAMPLES) - 1]]


def summarize(selected):
    if not selected:
        return {"n": 0, "days": 0}
    days = sorted({o["d"] for o in selected})
    out = {"n": len(selected), "days": len(days)}
    for leg in LEGS:
        out["gross_" + leg] = sum(o["gross"][leg] for o in selected) / len(selected)
        for cost in COSTS:
            values = [o[cost][leg] for o in selected]
            out[f"{cost}_{leg}_mean"] = sum(values) / len(values)
            if leg == "ON":
                out[f"{cost}_{leg}_median"] = median(values)
                out[f"{cost}_{leg}_win"] = sum(1 for v in values if v > 0) / len(values)
            # Day-level equal-weight mean (one portfolio per day).
            by = defaultdict(list)
            for o in selected:
                by[o["d"]].append(o[cost][leg])
            out[f"{cost}_{leg}_daymean"] = sum(sum(v) / len(v) for v in by.values()) / len(by)
            out[f"{cost}_{leg}_ci"] = block_ci(selected, lambda o, c=cost, l=leg: o[c][l])
    return out


def analyze(observations):
    days = sorted({o["d"] for o in observations})
    split = days[int(len(days) * EXPLORATION_SHARE) - 1]
    periods = {"EXPLORATION": lambda o: o["d"] <= split, "HOLDOUT": lambda o: o["d"] > split, "FULL": lambda o: True}
    table = {}
    for period, keep in periods.items():
        for name in SETS:
            table[f"{period}/{name}"] = summarize([o for o in observations if keep(o) and name in o["sets"]])
    return table, {"first_d": days[0], "last_d": days[-1], "exploration_last_d": split,
                   "exploration_days": sum(1 for d in days if d <= split), "holdout_days": sum(1 for d in days if d > split)}


def judge(table):
    def get(period, name, key):
        return table[f"{period}/{name}"].get(key)

    out = {}
    for cost in COSTS:
        e, h = "EXPLORATION", "HOLDOUT"
        q1 = (get(e, "ALL", f"{cost}_ON_mean") > 0 and get(h, "ALL", f"{cost}_ON_mean") > 0
              and get(h, "ALL", f"{cost}_ON_ci")[0] > 0)
        q2 = (get(e, "ALL", f"{cost}_ID_mean") < 0 and get(h, "ALL", f"{cost}_ID_mean") < 0
              and get(h, "ALL", f"{cost}_ID_ci")[1] < 0)
        mom_ok = table[f"{h}/MOM"].get("n", 0) > 0 and table[f"{e}/MOM"].get("n", 0) > 0
        q3 = bool(mom_ok and get(e, "MOM", f"{cost}_ON_mean") > get(e, "ALL", f"{cost}_ON_mean")
                  and get(h, "MOM", f"{cost}_ON_mean") > get(h, "ALL", f"{cost}_ON_mean")
                  and get(h, "MOM", f"{cost}_ON_mean") > 0 and get(h, "MOM", f"{cost}_ON_ci")[0] > 0)
        q4 = (get(e, "REGIME_DOWN", f"{cost}_ON_mean") < get(e, "REGIME_UP", f"{cost}_ON_mean")
              and get(h, "REGIME_DOWN", f"{cost}_ON_mean") < get(h, "REGIME_UP", f"{cost}_ON_mean"))
        out[cost] = {"Q1_overnight_edge": "지지" if q1 else "근거 없음",
                     "Q2_intraday_loss": "재현" if q2 else "재현 안 됨",
                     "Q3_momentum_close_edge": "지지" if q3 else "근거 없음",
                     "Q4_regime_difference": "지지" if q4 else "근거 없음"}
    return out


def pct(v):
    return "" if v is None else f"{v * 100:+.3f}%"


def ci(v):
    return "-" if not v else f"[{v[0] * 100:+.3f}%, {v[1] * 100:+.3f}%]"


def markdown(meta, info, counters, table, verdict):
    lines = ["# 결과 — 자격 충족 전 종목 하룻밤 보유 (자동 생성, analyze.py)", "",
             f"- export: {meta.get('exported_at')}, d 범위 {info['first_d']}~{info['last_d']}, "
             f"탐색 {info['exploration_days']}일(~{info['exploration_last_d']}) / 보류 {info['holdout_days']}일",
             f"- 제외 집계: {json.dumps(counters, ensure_ascii=False)}", ""]
    for cost in COSTS:
        lines += [f"## {cost} 비용", "",
                  "| 구간 | 집합 | n | 일수 | ON Gross | ON Net | ON 95% | ON 일평균 | ON 중앙값 | ON 승률 | ID Net | ID 95% | CC Net | CC 95% |",
                  "| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |"]
        for key, row in table.items():
            period, name = key.split("/")
            if not row.get("n"):
                lines.append(f"| {period} | {name} | 0 | 0 | | | | | | | | | | |")
                continue
            lines.append(f"| {period} | {name} | {row['n']} | {row['days']} | {pct(row['gross_ON'])} | "
                         f"{pct(row[cost + '_ON_mean'])} | {ci(row[cost + '_ON_ci'])} | {pct(row[cost + '_ON_daymean'])} | "
                         f"{pct(row[cost + '_ON_median'])} | {row[cost + '_ON_win'] * 100:.1f}% | "
                         f"{pct(row[cost + '_ID_mean'])} | {ci(row[cost + '_ID_ci'])} | "
                         f"{pct(row[cost + '_CC_mean'])} | {ci(row[cost + '_CC_ci'])} |")
        lines += ["", "판정: " + json.dumps(verdict[cost], ensure_ascii=False), ""]
    return "\n".join(lines) + "\n"


def selftest():
    """Synthetic checks of time ordering, eligibility and subset rules."""
    dates = [f"2026-01-{i:02d}" for i in range(1, 30)]
    series = defaultdict(dict)
    for k in range(CALENDAR_MIN_STOCKS):
        code = f"{k:05d}0"
        for d in dates:
            series[code][d] = (100.0, 101.0, 99.0, 100.0, 10, 1000.0)
    # Preferred share and 7-char code are never eligible.
    series["000005"] = dict(series["000000"])
    series["Q500001"] = dict(series["000000"])
    # An extreme move inside the 20-day window excludes the stock while it stays in the window.
    jump = series["000010"]
    jump[dates[10]] = (140.0, 140.0, 140.0, 140.0, 10, 1000.0)
    # A huge move on d' must not remove the stock-day d (no outcome-based selection).
    boom = series["000020"]
    boom[dates[25]] = (500.0, 500.0, 500.0, 500.0, 10, 1000.0)
    # A limit-up close on d is LIMIT_UP, not MOM.
    lim = series["000030"]
    lim[dates[24]] = (100.0, 129.5, 100.0, 129.5, 10, 1000.0)
    obs, calendar, counters = build(series)
    codes = {o["code"] for o in obs}
    assert "000005" not in codes and "Q500001" not in codes, "non-common shares excluded"
    assert all(o["d"] >= dates[HISTORY_DAYS] for o in obs), "needs 20 prior days"
    jump_days = {o["d"] for o in obs if o["code"] == "000010"}
    assert dates[HISTORY_DAYS] not in jump_days, "move inside the window excludes"
    boom_days = {o["d"] for o in obs if o["code"] == "000020"}
    assert dates[24] in boom_days, "an outcome-day move does not exclude d"
    lim_obs = [o for o in obs if o["code"] == "000030" and o["d"] == dates[24]]
    assert lim_obs and "LIMIT_UP" in lim_obs[0]["sets"] and "MOM" not in lim_obs[0]["sets"]
    flat = [o for o in obs if o["code"] == "000040"][0]
    assert abs(flat["BASE"]["ON"] - net(100.0, 100.0, COSTS["BASE"])) < 1e-12
    assert abs(net(100.0, 100.0, COSTS["BASE"]) - ((100 * 0.99705 - 100.095) / 100.095)) < 1e-12
    print("SELFTEST OK", counters)


def main(argv):
    if argv[1:2] == ["--selftest"]:
        selftest()
        return
    series, meta = load(argv[1])
    observations, calendar, counters = build(series)
    table, info = analyze(observations)
    verdict = judge(table)
    meta.update({"stocks_in_export": len(series), "calendar_days": len(calendar)})
    result = {"meta": meta, "info": info, "counters": counters, "verdict": verdict, "table": table,
              "settings": {"history_days": HISTORY_DAYS, "extreme_move": EXTREME_MOVE, "limit_up": LIMIT_UP,
                           "mom_top_share": MOM_TOP_SHARE, "near_high": NEAR_HIGH,
                           "exploration_share": EXPLORATION_SHARE, "resamples": RESAMPLES, "seed": SEED,
                           "costs": COSTS, "calendar_min_stocks": CALENDAR_MIN_STOCKS}}
    with open(argv[2], "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    with open(argv[3], "w", encoding="utf-8") as f:
        f.write(markdown(meta, info, counters, table, verdict))
    print(json.dumps({"info": info, "verdict": verdict}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main(sys.argv)
