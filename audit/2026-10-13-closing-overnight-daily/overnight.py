"""Daily-bar overnight decomposition (hypothesis screening only).

    python -I overnight.py --data data/daily.tsv --out-json results.json --out-md results.md

Definitions are pre-registered in README.md. Standard library only.
"""
import argparse
import csv
import json
import random
import sys
from collections import defaultdict

BUY = 0.095 / 100
SELL = 0.295 / 100
SPLIT = 0.60
RESAMPLES = 10_000
SEED = 20261013
LIMIT_UP = 0.29
# Amendment A1 (post-hoc data-quality rule, see README): KRX daily price limit is +/-30% of the base price.
PRICE_LIMIT = 0.305


def net(entry, exit_):
    b = entry * (1 + BUY)
    return (exit_ * (1 - SELL) - b) / b


def load(path):
    rows = defaultdict(dict)
    etf = {}
    dates = set()
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            o, h, l, c = (float(r[k]) for k in ("open", "high", "low", "close"))
            if o <= 0 or c <= 0:
                continue
            d = r["trade_date"]
            rows[r["stock_code"]][d] = {"o": o, "h": h, "l": l, "c": c, "tv": float(r["trading_value"] or 0)}
            etf[r["stock_code"]] = r["is_etf"] in ("t", "true")
            dates.add(d)
    return rows, etf, sorted(dates)


def build(rows, etf, dates, clean=False):
    """clean=True applies amendment A1: drop stock-days whose d or d' bar has zero trading value or whose
    close->next open / close->next close move exceeds the +/-30% price limit (corporate action or
    placeholder bar). r_d is set to None when d-1 has zero trading value or |r_d| exceeds the limit."""
    idx = {d: i for i, d in enumerate(dates)}
    out = []
    for code, series in rows.items():
        for d, x in series.items():
            i = idx[d]
            if i + 1 >= len(dates):
                continue
            n = series.get(dates[i + 1])
            if n is None:
                continue
            p = series.get(dates[i - 1]) if i > 0 else None
            if clean:
                if x["tv"] <= 0 or n["tv"] <= 0:
                    continue
                if abs(n["o"] / x["c"] - 1) > PRICE_LIMIT or abs(n["c"] / x["c"] - 1) > PRICE_LIMIT:
                    continue
                if p is not None and (p["tv"] <= 0 or abs(x["c"] / p["c"] - 1) > PRICE_LIMIT):
                    p = None
            out.append({
                "code": code, "etf": etf[code], "d": d,
                "rd": (x["c"] / p["c"] - 1) if p else None,
                "near_high": x["c"] >= 0.99 * x["h"], "tv": x["tv"],
                "g_on": n["o"] / x["c"] - 1, "g_id": n["c"] / n["o"] - 1, "g_cc": n["c"] / x["c"] - 1,
                "on": net(x["c"], n["o"]), "id": net(n["o"], n["c"]), "cc": net(x["c"], n["c"]),
            })
    return out


def tag_cross_section(obs):
    """Per day and per group (ETF/non-ETF): r_d deciles and trading-value terciles."""
    by = defaultdict(list)
    for o in obs:
        by[(o["d"], o["etf"])].append(o)
    for grp in by.values():
        with_rd = sorted((o for o in grp if o["rd"] is not None), key=lambda o: o["rd"])
        k = len(with_rd)
        for rank, o in enumerate(with_rd):
            q = rank / (k - 1) if k > 1 else 0.5
            o["top10"] = q >= 0.9
            o["bottom10"] = q <= 0.1
        for o in grp:
            o.setdefault("top10", False)
            o.setdefault("bottom10", False)
        by_tv = sorted(grp, key=lambda o: o["tv"])
        k = len(by_tv)
        for rank, o in enumerate(by_tv):
            o["liq"] = ("LOW", "MID", "HIGH")[min(2, rank * 3 // k)]


SUBSETS = {
    "ALL": lambda o: True,
    "DAYRET_TOP10": lambda o: o["top10"],
    "DAYRET_BOTTOM10": lambda o: o["bottom10"],
    "NEAR_HIGH": lambda o: o["near_high"],
    "TOP10_NEAR_HIGH": lambda o: o["top10"] and o["near_high"],
    "LIQ_LOW": lambda o: o["liq"] == "LOW",
    "LIQ_MID": lambda o: o["liq"] == "MID",
    "LIQ_HIGH": lambda o: o["liq"] == "HIGH",
    # Amendment A2 (post-hoc, descriptive only): split out limit-up-like closes (r_d >= 29%),
    # which usually cannot be bought at the close.
    "A2_ALL_EX_LIMITUP": lambda o: o["rd"] is None or o["rd"] < LIMIT_UP,
    "A2_TOP10_EX_LIMITUP": lambda o: o["top10"] and o["rd"] < LIMIT_UP,
    "A2_TOP10_NEAR_HIGH_EX_LIMITUP": lambda o: o["top10"] and o["near_high"] and o["rd"] < LIMIT_UP,
    "A2_LIMITUP_ONLY": lambda o: o["rd"] is not None and o["rd"] >= LIMIT_UP,
}
UNIVERSES = {"NON_ETF": lambda o: not o["etf"], "ETF": lambda o: o["etf"], "ALL_INCL_ETF": lambda o: True}


def mean(xs):
    return sum(xs) / len(xs) if xs else None


def median(xs):
    if not xs:
        return None
    s = sorted(xs)
    m = len(s) // 2
    return s[m] if len(s) % 2 else (s[m - 1] + s[m]) / 2


def block_ci(sel, key):
    by = defaultdict(list)
    for o in sel:
        by[o["d"]].append(o[key])
    days = sorted(by)
    if len(days) < 2:
        return None
    sums = [sum(by[d]) for d in days]
    cnts = [len(by[d]) for d in days]
    rng = random.Random(SEED)
    k = len(days)
    stats = []
    for _ in range(RESAMPLES):
        s = n = 0
        for _ in range(k):
            i = rng.randrange(k)
            s += sums[i]
            n += cnts[i]
        stats.append(s / n)
    stats.sort()
    return [stats[int(0.025 * RESAMPLES)], stats[int(0.975 * RESAMPLES) - 1]]


def stats(sel):
    if not sel:
        return {"n": 0}
    out = {"n": len(sel), "days": len({o["d"] for o in sel}),
           "limit_up_like": sum(1 for o in sel if o["rd"] is not None and o["rd"] >= LIMIT_UP)}
    for k in ("on", "id", "cc"):
        xs = [o[k] for o in sel]
        out[k] = {"gross_mean": mean([o["g_" + k] for o in sel]), "net_mean": mean(xs), "net_median": median(xs),
                  "net_win": sum(1 for x in xs if x > 0) / len(xs), "net_ci95_dayblock": block_ci(sel, k)}
    return out


def by_day(obs):
    by = defaultdict(list)
    for o in obs:
        by[o["d"]].append(o)
    rows = []
    for d in sorted(by):
        g = by[d]
        rds = [o["rd"] for o in g if o["rd"] is not None]
        rows.append({"d": d, "n": len(g), "mkt_rd": mean(rds), "on": mean([o["on"] for o in g]),
                     "id": mean([o["id"] for o in g]), "cc": mean([o["cc"] for o in g])})
    return rows


def regime(obs, days):
    mkt = {r["d"]: r["mkt_rd"] for r in days}
    out = {}
    for name, f in (("MKT_UP", lambda v: v is not None and v > 0), ("MKT_DOWN_OR_FLAT", lambda v: v is not None and v <= 0)):
        sel = [o for o in obs if f(mkt.get(o["d"]))]
        st = stats(sel)
        out[name] = {"n": st["n"], "days": st.get("days"),
                     **({k: {"net_mean": st[k]["net_mean"], "net_ci95_dayblock": st[k]["net_ci95_dayblock"]}
                         for k in ("on", "id", "cc")} if st["n"] else {})}
    return out


def pct(x):
    return "-" if x is None else "%+.3f%%" % (x * 100)


def ci(x):
    return "-" if not x else "[%s, %s]" % (pct(x[0]), pct(x[1]))


def analyze(rows, etf, dates, clean, variant, split_days):
    obs = build(rows, etf, dates, clean)
    tag_cross_section(obs)
    valid_days = sorted({o["d"] for o in obs})
    explo = set(split_days[0])
    periods = {"EXPLORATION": {d for d in valid_days if d in explo},
               "HOLDOUT": {d for d in valid_days if d not in explo}, "FULL": set(valid_days)}
    ex, ho = sorted(periods["EXPLORATION"]), sorted(periods["HOLDOUT"])
    res = {"variant": variant,
           "input": {"stocks": len(rows), "dates": len(dates), "first_date": dates[0], "last_date": dates[-1],
                     "stock_days": len(obs), "valid_d": len(valid_days),
                     "exploration": [ex[0], ex[-1], len(ex)], "holdout": [ho[0], ho[-1], len(ho)]},
           "costs_percent": {"buy": BUY * 100, "sell": SELL * 100}, "bootstrap": {"resamples": RESAMPLES, "seed": SEED},
           "results": {}, "regime": {}, "by_day_non_etf": []}
    for pname, pdays in periods.items():
        res["results"][pname] = {}
        res["regime"][pname] = {}
        for uname, uf in UNIVERSES.items():
            sel_u = [o for o in obs if o["d"] in pdays and uf(o)]
            res["results"][pname][uname] = {s: stats([o for o in sel_u if sf(o)]) for s, sf in SUBSETS.items()}
            if uname == "NON_ETF":
                res["regime"][pname][uname] = regime(sel_u, by_day(sel_u))
    res["by_day_non_etf"] = by_day([o for o in obs if not o["etf"]])
    for r in res["by_day_non_etf"]:
        r["period"] = "EXPLORATION" if r["d"] in periods["EXPLORATION"] else "HOLDOUT"

    md = ["# %s — 일봉 오버나이트 분해 (자동 생성, overnight.py)" % variant, "",
          "입력: %(stocks)d종목, %(dates)d거래일 (%(first_date)s~%(last_date)s), 종목-일 %(stock_days)d건, 유효 d %(valid_d)d일" % res["input"],
          "탐색 %s~%s (%d일), 보류 %s~%s (%d일). Net = BASE 비용(매수 0.095%%, 매도 0.295%%) 왕복. 95%% 구간 = 날짜 블록 부트스트랩 %d회." % (
              *res["input"]["exploration"], *res["input"]["holdout"], RESAMPLES), ""]
    for uname in UNIVERSES:
        md.append("## %s / %s" % (variant, uname))
        md.append("")
        md.append("| 구간 | 집합 | n | 일수 | ON Gross | ON Net | ON Net 95% | ON 승률 | ON 중앙값 | ID Net | ID Net 95% | CC Net | CC Net 95% |")
        md.append("| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | --- | ---: | --- |")
        for pname in ("EXPLORATION", "HOLDOUT", "FULL"):
            for s in SUBSETS:
                st = res["results"][pname][uname][s]
                if not st["n"]:
                    md.append("| %s | %s | 0 | | | | | | | | | | |" % (pname, s))
                    continue
                md.append("| %s | %s | %d | %d | %s | %s | %s | %.1f%% | %s | %s | %s | %s | %s |" % (
                    pname, s, st["n"], st["days"], pct(st["on"]["gross_mean"]), pct(st["on"]["net_mean"]),
                    ci(st["on"]["net_ci95_dayblock"]), st["on"]["net_win"] * 100, pct(st["on"]["net_median"]),
                    pct(st["id"]["net_mean"]), ci(st["id"]["net_ci95_dayblock"]),
                    pct(st["cc"]["net_mean"]), ci(st["cc"]["net_ci95_dayblock"])))
        md.append("")
    md.append("## %s / 시장 대리 지표별 (ETF 제외, 동일가중 r_d 부호)" % variant)
    md.append("")
    md.append("| 구간 | 국면 | n | 일수 | ON Net | ON 95% | ID Net | ID 95% |")
    md.append("| --- | --- | ---: | ---: | ---: | --- | ---: | --- |")
    for pname in ("EXPLORATION", "HOLDOUT", "FULL"):
        for g, st in res["regime"][pname]["NON_ETF"].items():
            if not st["n"]:
                continue
            md.append("| %s | %s | %d | %d | %s | %s | %s | %s |" % (pname, g, st["n"], st["days"], pct(st["on"]["net_mean"]),
                      ci(st["on"]["net_ci95_dayblock"]), pct(st["id"]["net_mean"]), ci(st["id"]["net_ci95_dayblock"])))
    md.append("")
    md.append("## %s / 날짜별 (ETF 제외 동일가중 평균)" % variant)
    md.append("")
    md.append("| d | 구간 | n | 시장 r_d | ON Net | ID Net | CC Net |")
    md.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
    for r in res["by_day_non_etf"]:
        md.append("| %s | %s | %d | %s | %s | %s | %s |" % (r["d"], r["period"][:4], r["n"], pct(r["mkt_rd"]),
                                                          pct(r["on"]), pct(r["id"]), pct(r["cc"])))
    md.append("")
    for pname in ("EXPLORATION", "HOLDOUT", "FULL"):
        for s in SUBSETS:
            st = res["results"][pname]["NON_ETF"][s]
            if st["n"]:
                print("%-5s %-11s %-16s n=%5d days=%3d ON %s %s med %s | ID %s %s | CC %s" % (
                    variant, pname, s, st["n"], st["days"], pct(st["on"]["net_mean"]), ci(st["on"]["net_ci95_dayblock"]),
                    pct(st["on"]["net_median"]), pct(st["id"]["net_mean"]), ci(st["id"]["net_ci95_dayblock"]),
                    pct(st["cc"]["net_mean"])))
    return res, md


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/daily.tsv")
    ap.add_argument("--out-json", default="results.json")
    ap.add_argument("--out-md", default="results.md")
    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    rows, etf, dates = load(a.data)
    # The chronological split is fixed on the RAW (pre-registered) set of valid days and reused for CLEAN.
    raw_days = sorted({o["d"] for o in build(rows, etf, dates)})
    cut = int(len(raw_days) * SPLIT)
    split = (raw_days[:cut], raw_days[cut:])
    out, md = {}, []
    for variant in ("RAW", "CLEAN"):
        res, lines = analyze(rows, etf, dates, variant == "CLEAN", variant, split)
        out[variant] = res
        md += lines
    with open(a.out_json, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    with open(a.out_md, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md) + "\n")


if __name__ == "__main__":
    main()
