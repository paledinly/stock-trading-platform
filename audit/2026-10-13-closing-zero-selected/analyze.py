"""Why closing-recommend-v9-1515 selects nothing: gate decomposition of stored run snapshots.

Inputs (data/, gitignored, produced by ExportQuery.java):
  runs_inventory.tsv, snapshots.tsv, selected.tsv, detections.tsv, detections_daily.tsv, candles.tsv,
  boundary_bar.tsv
Output: results.json (small, committed) and a text report on stdout.

Usage: python -I analyze.py [dataDir] [results.json]
Standard library only. Thresholds below are read from the stored run criteria where available and are
used only to re-derive each candidate's gate outcomes; nothing here proposes new thresholds.
"""
import collections
import csv
import json
import sys
from datetime import datetime, timedelta, timezone

csv.field_size_limit(10 ** 9)
DATA = sys.argv[1] if len(sys.argv) > 1 else "data"
OUT = sys.argv[2] if len(sys.argv) > 2 else "results.json"
KST = timezone(timedelta(hours=9))
CAP = 5  # LIMITED_MODE_MAX_CANDIDATES for v9 (v8 used 1 before 2026-10-01)

# Strategy gates (pre-registered semantics) vs data/measurement readiness gates.
STRATEGY = ["NOT_TRADABLE", "OPPORTUNITY_LOW", "RISK_HIGH", "DAILY_TREND_WEAK", "INTRADAY_REVERSAL",
            "OVEREXTENDED", "LOW_LIQUIDITY", "LOW_FINAL_SCORE"]
SIGNAL_AGE = "STALE_FEATURE"  # 30-minute signal age rule; reported separately
DATA_GATES = ["LATE_BAR_REVISION", "RECEIPT_AFTER_DEADLINE", "CANDLE_GAP", "LATEST_CANDLE_STALE",
              "DAILY_DATA_MISSING", "DAILY_DATA_STALE", "TRADABILITY_UNVERIFIED", "FEATURE_MISSING",
              "CANDLE_TIMESTAMP_MISSING"]


def read(name):
    with open(f"{DATA}/{name}", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def ts(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")) if text else None


def kst(text):
    t = ts(text)
    return t.astimezone(KST).strftime("%H:%M:%S") if t else None


def gates(e, crit):
    """All failing conditions of one PRECISION evaluation, not only the first one the app reports."""
    d = e.get("dataReadiness") or {}
    miss = e.get("missingFeatures") or []
    min_opp = float(crit.get("minOpportunity", 35))
    max_risk = float(crit.get("maxRisk", 65))
    min_score = float(crit.get("minimumFinalScore", 55))
    min_cov = int(crit.get("minimumCoverageMinutes", 20))
    fails = []
    if e["decisionReason"] == "NOT_TRADABLE":
        fails.append("NOT_TRADABLE")
    opp, risk = e.get("opportunityScore"), e.get("riskScore")
    if opp is None or opp < min_opp:
        fails.append("OPPORTUNITY_LOW")
    if risk is None or risk > max_risk:
        fails.append("RISK_HIGH")
    if SIGNAL_AGE in miss:
        fails.append(SIGNAL_AGE)
    late = bool(d.get("lateCandleRevision"))
    if late:
        fails.append("LATE_BAR_REVISION")
    received, deadline = ts(d.get("receivedAt")), ts(d.get("inputReceiptDeadline"))
    if received and deadline and received > deadline:
        fails.append("RECEIPT_AFTER_DEADLINE")
    if "RECEIVED_AFTER_EVALUATION" in miss and not late and not (received and deadline and received > deadline):
        fails.append("RECEIPT_AFTER_DEADLINE")
    if "CANDLE_GAP" in miss or e.get("finalCandles", 0) * 5 < min_cov:
        fails.append("CANDLE_GAP")
    for m in ("DAILY_DATA_MISSING", "DAILY_DATA_STALE", "TRADABILITY_UNVERIFIED", "CANDLE_TIMESTAMP_MISSING"):
        if m in miss:
            fails.append(m)
    if any(m in miss for m in ("opportunityScore", "riskScore", "volumeRatio", "vwapDistanceRate",
                               "dayHighDistanceRate", "tradeStrength", "featureSnapshot", "RECEIVED_AT_MISSING")):
        fails.append("FEATURE_MISSING")
    if d.get("dailyTrend") == "FAIL":
        fails.append("DAILY_TREND_WEAK")
    if d.get("latestFiveMinute") == "FAIL":
        fails.append("LATEST_CANDLE_STALE")
    if d.get("signalRetained") == "FAIL":
        fails.append("INTRADAY_REVERSAL")
    if d.get("overextension") == "FAIL":
        fails.append("OVEREXTENDED")
    if d.get("liquidity") == "FAIL":
        fails.append("LOW_LIQUIDITY")
    if (e.get("finalScore") or 0) < min_score:
        fails.append("LOW_FINAL_SCORE")
    return list(dict.fromkeys(fails))


def passes(fails, ignore):
    return all(f in ignore for f in fails)


def main():
    inventory = {r["id"]: r for r in read("runs_inventory.tsv")}
    snaps = read("snapshots.tsv")
    selected_rows = collections.defaultdict(list)
    for r in read("selected.tsv"):
        selected_rows[r["run_id"]].append(r)

    runs_out = []
    pooled = collections.defaultdict(lambda: collections.Counter())
    for r in snaps:
        rid = r["id"]
        s = json.loads(r["response_snapshot"])
        crit = s.get("criteria") or {}
        evs = s.get("evaluations") or []
        prec = [e for e in evs if e["candidateSource"] == "PRECISION"]
        broad = [e for e in evs if e["candidateSource"] == "BROAD"]
        has_readiness = bool(prec) and bool(prec[0].get("dataReadiness"))
        run = {
            "runId": int(rid), "date": r["recommendation_date"], "version": r["strategy_version"],
            "mode": r["execution_mode"], "featureFreezeAt": crit.get("featureFreezeAt"),
            "graceSeconds": crit.get("candleFinalizationGraceSeconds"),
            "limitedModeMaxCandidates": crit.get("limitedModeMaxCandidates"),
            "sourceDetections": s.get("sourceDetections"), "broadSnapshots": s.get("sourceBroadSnapshots"),
            "precisionCandidates": len(prec), "broadCandidates": len(broad),
            "selectedStored": len(selected_rows.get(rid, [])),
            "dispositions": dict(collections.Counter(f"{e['candidateSource']}:{e['disposition']}" for e in evs)),
            "precisionReasons": dict(collections.Counter(e["decisionReason"] for e in prec).most_common()),
            "broadReasons": dict(collections.Counter(e["decisionReason"] for e in broad).most_common()),
            "precisionScoreAtLeast55": sum(1 for e in prec if (e.get("finalScore") or 0) >= 55),
            "precisionPassOppRisk": sum(1 for e in prec if (e.get("opportunityScore") or 0) >= float(crit.get("minOpportunity", 35))
                                        and (e.get("riskScore") if e.get("riskScore") is not None else 999) <= float(crit.get("maxRisk", 65))),
        }
        if has_readiness:
            rows = []
            for e in prec:
                f = gates(e, crit)
                rows.append((e, f))
            strat = set(STRATEGY)
            data = set(DATA_GATES)
            # Counterfactual counts (uncapped; capped at CAP below). Read-only re-derivation of stored gates.
            cf = {
                "actualQualified": sum(1 for e, f in rows if not f),
                "ignoreLateBarRevision": sum(1 for e, f in rows if passes(f, {"LATE_BAR_REVISION"})),
                "ignoreAllDataGates": sum(1 for e, f in rows if passes(f, data)),
                "ignoreDataAndSignalAge": sum(1 for e, f in rows if passes(f, data | {SIGNAL_AGE})),
                "ignoreStrategyGates": sum(1 for e, f in rows if passes(f, strat)),
            }
            run["counterfactualUncapped"] = cf
            run["counterfactualCapped"] = {k: min(v, CAP) for k, v in cf.items()}
            run["anyFail"] = dict(collections.Counter(x for _, f in rows for x in f).most_common())
            # Gate that alone blocks a candidate once data gates and signal age are set aside.
            sole = collections.Counter()
            for e, f in rows:
                core = [x for x in f if x in strat]
                if len(core) == 1 and passes(f, data | {SIGNAL_AGE} | set(core)):
                    sole[core[0]] += 1
            run["soleStrategyBlocker"] = dict(sole.most_common())
            run["strategyFailCountHistogram"] = dict(sorted(collections.Counter(
                sum(1 for x in f if x in strat) for _, f in rows).items()))
            for _, f in rows:
                for x in f:
                    pooled["v9" if "v9" in run["version"] else "v8" if "v8" in run["version"] and run["mode"] == "FORWARD" else "other"][x] += 1
            top = sorted(rows, key=lambda t: -(t[0].get("finalScore") or 0))[:10]
            run["top10"] = [{
                "stock": e["stockCode"], "scanner": e.get("scannerType"), "finalScore": e.get("finalScore"),
                "opportunity": e.get("opportunityScore"), "risk": e.get("riskScore"),
                "disposition": e["disposition"], "reportedReason": e["decisionReason"],
                "detectedAtKst": kst((e.get("dataReadiness") or {}).get("detectedAt")),
                "receivedAtKst": kst((e.get("dataReadiness") or {}).get("receivedAt")),
                "lastFinalCandleKst": kst((e.get("dataReadiness") or {}).get("lastFinalCandleAt")),
                "finalCandles": e.get("finalCandles"), "allFailing": f,
            } for e, f in top]
        runs_out.append(run)

    # Detection supply in the v8 and v9 windows (latest detection per stock, as representatives() does).
    det = read("detections.tsv")
    by_day = collections.defaultdict(list)
    for d in det:
        by_day[d["market_session_date"]].append(d)
    supply = []
    for day in sorted(by_day):
        rows = by_day[day]
        out = {"date": day}
        for label, start, end in (("v8_1430_1500", "14:30", "15:00"), ("v9_1430_1515", "14:30", "15:15")):
            latest = {}
            for d in rows:
                t = ts(d["detected_at"]).astimezone(KST).strftime("%H:%M:%S")
                if start + ":00" <= t <= end + ":00":
                    if d["stock_code"] not in latest or ts(d["detected_at"]) > ts(latest[d["stock_code"]]["detected_at"]):
                        latest[d["stock_code"]] = d
            end_dt = None
            n_fresh = n_pass = n_fresh_pass = 0
            for d in latest.values():
                dt = ts(d["detected_at"]).astimezone(KST)
                limit = dt.replace(hour=int(end[:2]), minute=int(end[3:]), second=0, microsecond=0)
                fresh = (limit - dt) <= timedelta(minutes=30)
                ok = d["opportunity_score"] and float(d["opportunity_score"]) >= 35 and d["risk_score"] \
                    and float(d["risk_score"]) <= 65 and d["is_etf"] not in ("t", "true")
                n_fresh += fresh
                n_pass += bool(ok)
                n_fresh_pass += bool(ok and fresh)
            out[label] = {"stocks": len(latest), "fresh30m": n_fresh, "passOppRiskNonEtf": n_pass,
                          "freshAndPass": n_fresh_pass}
        lag = [(ts(d["received_at"]) - ts(d["detected_at"])).total_seconds() for d in rows if d["received_at"]]
        lag.sort()
        # v9 boundary detections: representative detected exactly at 15:15:00 (close of the 15:10 bar).
        latest = {}
        for d in rows:
            t = ts(d["detected_at"]).astimezone(KST).strftime("%H:%M:%S")
            if "14:30:00" <= t <= "15:15:00" and (d["stock_code"] not in latest
                                                  or ts(d["detected_at"]) > ts(latest[d["stock_code"]]["detected_at"])):
                latest[d["stock_code"]] = d
        at_freeze = [d for d in latest.values() if ts(d["detected_at"]).astimezone(KST).strftime("%H:%M:%S") == "15:15:00"]
        cutoff = ts(at_freeze[0]["detected_at"]) if at_freeze else None
        out["v9RepresentativeAt1515"] = len(at_freeze)
        out["v9RepresentativeAt1515ReceivedAfterCutoff"] = sum(
            1 for d in at_freeze if d["received_at"] and ts(d["received_at"]) > cutoff)
        out["receiptLagSecondsP50"] = lag[len(lag) // 2] if lag else None
        out["receiptLagSecondsMax"] = lag[-1] if lag else None
        out["lastDetectionKst"] = max(ts(d["detected_at"]) for d in rows).astimezone(KST).strftime("%H:%M:%S")
        supply.append(out)

    daily = read("detections_daily.tsv")
    candles = read("candles.tsv")
    bar_avail = collections.defaultdict(dict)
    for c in candles:
        bar_avail[c["d"]][c["bar"]] = {
            "bars": int(c["bars"]), "updatedBy1515": int(c["updated_by_1515"]),
            "updatedBy150010": int(c["updated_by_150010"]),
            "updatedLagP50s": round(float(c["updated_lag_p50"]), 1)}

    boundary = []
    for b in read("boundary_bar.tsv"):
        v9_day = b["d"] >= "2026-10-08"
        relevant = (b["bar"] == "15:10") if v9_day else True
        if not relevant:
            continue
        boundary.append({"date": b["d"], "bar": b["bar"], "bars": int(b["bars"]),
                         "availableAtCutoff": int(b["available_at_cutoff"]),
                         "v9FlaggedIfRunAt151515": int(b["v9_flag_if_run_151515"]),
                         "flaggedAtActualOfficialRun": int(b["flagged_at_actual_run"]),
                         "officialRunAt": b["official_run_at"]})

    result = {
        "boundaryBar": boundary,
        "note": "Read-only re-derivation of stored closing run snapshots; no thresholds proposed.",
        "runs": runs_out,
        "pooledFailingConditions": {k: dict(v.most_common()) for k, v in pooled.items()},
        "detectionSupply": supply,
        "detectionsPerSessionDate": [{k: r[k] for k in ("market_session_date", "n", "stocks", "passing_opp_risk")}
                                     for r in daily],
        "barAvailability": {d: {b: v for b, v in bars.items() if b in ("14:50", "14:55", "15:00", "15:05", "15:10")}
                            for d, bars in sorted(bar_avail.items())},
        "officialRunInventory": [{"id": int(i), "date": v["recommendation_date"], "version": v["strategy_version"],
                                  "mode": v["execution_mode"], "selected": int(v["selected_rows"])}
                                 for i, v in inventory.items()],
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    report(result)


def report(res):
    print("== runs")
    for r in res["runs"]:
        print(f"run {r['runId']:>2} {r['date']} {r['version'][-14:]:>14} {r['mode']:<7} freeze={r['featureFreezeAt']} "
              f"grace={r['graceSeconds']} det={r['sourceDetections']} prec={r['precisionCandidates']} "
              f"broad={r['broadCandidates']} sel={r['selectedStored']} sc>=55={r['precisionScoreAtLeast55']} "
              f"oppRiskPass={r['precisionPassOppRisk']}")
        print("     reasons", r["precisionReasons"])
        if "counterfactualUncapped" in r:
            print("     cf", r["counterfactualUncapped"])
            print("     anyFail", r["anyFail"])
            print("     soleStrategyBlocker", r["soleStrategyBlocker"], "hist", r["strategyFailCountHistogram"])
    print("== pooled", json.dumps(res["pooledFailingConditions"], indent=1))
    print("== supply")
    for s in res["detectionSupply"]:
        print(s)


if __name__ == "__main__":
    main()
