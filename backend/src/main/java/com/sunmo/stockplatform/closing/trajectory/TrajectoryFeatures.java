/*
 * 한 종목의 1분 집계·맥락 관측(지수·거래대금 순위·수급)·과거 일봉으로 Trajectory 스냅샷(기준시각의 특징값 묶음)을 계산하는 순수 계산 클래스예요.
 * 기준시각(cutoff) 이후에 생긴 자료나 계산시각 이후에 받은 자료는 쓰지 않아요. 자료가 모자라면 0이 아니라 null로 남겨요.
 * TrajectoryService가 스냅샷을 만들 때 쓰고, ratio/pct 같은 도우미는 MorningOutcomeService와 MicrostructureAggregator도 써요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;

/** Pure point-in-time computation. Percent fields use percentage points; ratios are unscaled. */
/*
 * (특정 시점 기준의 순수 계산이에요. Pct 값은 퍼센트(2는 2%), ratio 값은 배율 그대로예요.)
 * 비유하면 "15:00 정각에 찍은 사진만 보고 쓰는 관찰 보고서"예요. 15:00 이후에 찍힌 사진은 아무리 선명해도 보고서에 넣지 않아요.
 * 하위 점수(Sub Score)는 검증되지 않은 연구 표시값이고 운영 추천 순위에 쓰지 않아요.
 */
public final class TrajectoryFeatures {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** 도구 클래스라 객체를 만들지 못하게 막아 둔 생성자예요. */
    private TrajectoryFeatures() {}
    /** 하는 일: a ÷ b를 소수 8자리로 계산해요. 받는 것: a, b. 돌려주는 것: 비율, 어느 쪽이 없거나 b가 0 이하면 null이에요. */
    public static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return a == null || b == null || b.signum() <= 0 ? null : a.divide(b, 8, RoundingMode.HALF_UP);
    }
    /** 하는 일: a가 b보다 몇 % 높은지 계산해요((a÷b-1)×100). 받는 것: a, 기준 b. 돌려주는 것: 퍼센트 또는 null이에요. */
    public static BigDecimal pct(BigDecimal a, BigDecimal b) {
        BigDecimal r = ratio(a, b); return r == null ? null : r.subtract(BigDecimal.ONE).movePointRight(2);
    }
    /** 하는 일: 가격이 저가~고가 사이 어디쯤인지 0~1 비율로 계산해요. 받는 것: 가격, 고가, 저가. 돌려주는 것: 위치 비율 또는 null이에요. */
    public static BigDecimal range(BigDecimal price, BigDecimal high, BigDecimal low) {
        return price == null || high == null || low == null ? null : ratio(price.subtract(low), high.subtract(low));
    }
    /** 하는 일: 종목 수익률 - 시장 수익률(상대강도)을 계산해요. 받는 것: 종목·시장 값. 돌려주는 것: 차이 또는 null이에요. */
    public static BigDecimal relative(BigDecimal stock, BigDecimal market) {
        return stock == null || market == null ? null : stock.subtract(market);
    }
    /**
     * 하는 일: 대금 가속도 (최근 - 2×직전 + 그 이전) ÷ 그 이전을 계산해요. 예: 10→15→25이면 0.5예요.
     * 받는 것: 최근, 직전, 그 이전 구간 값. 돌려주는 것: 가속도 비율 또는 null이에요.
     */
    public static BigDecimal acceleration(BigDecimal recent, BigDecimal previous, BigDecimal older) {
        return recent == null || previous == null || older == null ? null
                : ratio(recent.subtract(previous.multiply(BigDecimal.valueOf(2))).add(older), older);
    }
    /** 하는 일: 순위 변화(과거 순위 - 현재 순위)를 계산해요. 양수면 순위가 올라간 거예요. 받는 것: 현재, 과거 순위. 돌려주는 것: 변화량이에요. */
    public static BigDecimal rankChange(BigDecimal current, BigDecimal old) { return relative(old, current); }
    /** 작은 도우미예요. positive/negative: null이 아니고 양수/음수인지, vwap: 당일 누적대금÷누적량(거래량 가중 평균가)이에요. */
    static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }
    static boolean negative(BigDecimal n) { return n != null && n.signum() < 0; }
    static BigDecimal vwap(Minute row) { return ratio(row.dailyTurnover(), row.dailyVolume()); }

    /**
     * 하는 일: 기준시각(cutoff)까지의 자료로 한 종목의 스냅샷을 계산해요. 가격·거래량·대금·순위·체결·VWAP·장후반 패턴·지수·일봉·하위 점수·수급을 채워요.
     * 받는 것: 종목, 시장 이름, 기준시각, 계산시각, 1분 집계, 맥락 관측, 일봉, 정책이에요.
     * 돌려주는 것: Snapshot, 기준시각 직전 1분 집계가 없으면 null이에요. 기준시각이 계산시각보다 미래면 예외를 던져요.
     */
    public static Snapshot calculate(String symbol, String marketName, Instant cutoff, Instant evaluatedAt,
            List<Minute> source, List<Context> contexts, List<StockCandle> dailySource, TrajectoryProperties policy) {
        if (cutoff.isAfter(evaluatedAt)) throw new IllegalArgumentException("Future feature cutoff");
        LocalDate date = cutoff.atZone(ZONE).toLocalDate();
        // 같은 날, 기준시각 전에 끝났고, 계산시각 전에 받은·확정된 1분 집계만 써요(미래 자료 누수 방지).
        List<Minute> rows = source.stream().filter(r -> r.symbol().equals(symbol)
                && r.start().atZone(ZONE).toLocalDate().equals(date) && !r.start().plusSeconds(60).isAfter(cutoff)
                && !r.occurredAt().isAfter(cutoff) && !r.receivedAt().isAfter(evaluatedAt)
                && !r.finalizedAt().isAfter(evaluatedAt)).sorted(Comparator.comparing(Minute::start)).toList();
        if (rows.isEmpty() || !rows.getLast().start().plusSeconds(60).equals(cutoff)) return null;
        Minute last = rows.getLast();
        // 일봉은 당일 이전 확정봉 중 계산시각 전에 생성·수정된 것만 써요.
        List<StockCandle> daily = dailySource.stream().filter(r -> r.isFinalCandle() && r.getTimeframe().equals("1D")
                && r.getStartTime().atZone(ZONE).toLocalDate().isBefore(date)
                && r.getCreatedAt() != null && r.getUpdatedAt() != null
                && !r.getCreatedAt().isAfter(evaluatedAt) && !r.getUpdatedAt().isAfter(evaluatedAt))
                .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        BigDecimal prevClose = daily.isEmpty() ? null : daily.getLast().getClose();
        BigDecimal priceReturn = pct(last.close(), prevClose);
        Map<String, Object> volumes = windows(rows, cutoff, "volume", Minute::volume, policy.volumeComparisonWindows());
        Map<String, Object> money = windows(rows, cutoff, "turnover", Minute::turnover, policy.turnoverComparisonWindows());
        volumes.put("dailyVolume", last.dailyVolume()); money.put("dailyTurnover", last.dailyTurnover());
        volumes.put("volumeVsYesterday", daily.isEmpty() ? null : ratio(last.dailyVolume(), BigDecimal.valueOf(daily.getLast().getVolume())));
        for (int n : List.of(5, 20)) volumes.put("volumeVs" + n + "dAvg", ratio(last.dailyVolume(), averageDaily(daily, n, r -> BigDecimal.valueOf(r.getVolume()))));
        for (int n : List.of(5, 15)) money.put("turnoverAcceleration" + n + "m", acceleration(
                sum(rows, cutoff, n, Minute::turnover), sum(rows, cutoff.minusSeconds(n * 60L), n, Minute::turnover),
                sum(rows, cutoff.minusSeconds(n * 120L), n, Minute::turnover)));
        // 거래대금 순위는 ALL 범위만 쓰고, 5/15/30/60분 전 순위와 비교해요. 오래된(contextMaxAge 초과) 관측은 null이에요.
        Context rank = context(contexts, "TURNOVER_RANK", symbol, "ALL", cutoff, evaluatedAt, policy.contextMaxAge());
        money.put("turnoverRank", rank == null ? null : rank.value());
        money.put("rankScope", "ALL_KRX_PROVIDER_RESPONSE"); money.put("rankReceivedAt", rank == null ? null : rank.receivedAt());
        for (int n : List.of(5, 15, 30, 60)) {
            Context old = context(contexts, "TURNOVER_RANK", symbol, "ALL", cutoff.minusSeconds(n * 60L), evaluatedAt, policy.contextMaxAge());
            money.put("turnoverRank" + n + "mAgo", old == null ? null : old.value());
            money.put("turnoverRankChange" + n + "m", rank == null || old == null ? null : rankChange(rank.value(), old.value()));
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionStrength", last.executionStrength());
        execution.put("buyExecutionVolume", last.buyVolume()); execution.put("sellExecutionVolume", last.sellVolume());
        execution.put("buySellExecutionRatio", ratio(last.buyVolume(), last.sellVolume()));
        Map<String, Object> vw = new LinkedHashMap<>();
        vw.put("vwap", vwap(last)); vw.put("vwapDistancePct", pct(last.close(), vwap(last)));
        vw.put("priceAboveVwap", vwap(last) == null ? null : last.close().compareTo(vwap(last)) > 0);
        for (int n : List.of(5, 15)) {
            BigDecimal strength = sum(rows, cutoff, n, Minute::executionStrength);
            execution.put("executionStrength" + n + "mAvg", strength == null ? null : ratio(strength, BigDecimal.valueOf(n)));
            execution.put("executionStrengthSlope" + n + "m", slope(window(rows, cutoff, n), Minute::executionStrength));
            execution.put("buySellRatio" + n + "m", ratio(sum(rows, cutoff, n, Minute::buyVolume), sum(rows, cutoff, n, Minute::sellVolume)));
            vw.put("vwapDistanceSlope" + n + "m", slope(window(rows, cutoff, n), r -> pct(r.close(), vwap(r))));
        }
        // 끝에서부터 끊김 없이 완전한 1분 구간만 보고, VWAP 위로 올라선 시각·유지 분 수·돌파 실패 여부를 판단해요.
        List<Minute> run = trailingContinuous(rows);
        Instant cross = null; int above = 0; boolean failed = false;
        for (int i = 0; i < run.size(); i++) {
            Minute r = run.get(i); BigDecimal d = pct(r.close(), vwap(r));
            if (d == null) { above = 0; cross = null; continue; }
            if (d.signum() > 0) {
                above++;
                if (i > 0 && pct(run.get(i - 1).close(), vwap(run.get(i - 1))) != null
                        && pct(run.get(i - 1).close(), vwap(run.get(i - 1))).signum() <= 0) cross = r.start().plusSeconds(60);
            } else { failed = above > 0; above = 0; }
        }
        vw.put("vwapCrossTime", cross); vw.put("vwapAboveDurationMinutes", above);
        vw.put("state", vwap(last) == null ? "UNKNOWN" : above > 1 ? "VWAP_ABOVE_HOLD"
                : above == 1 && cross != null ? "VWAP_BREAKOUT" : above == 1 ? "VWAP_ABOVE_OBSERVED"
                : failed ? "VWAP_FAILED_BREAKOUT" : "VWAP_BELOW");
        // 장후반(기본 14:30~) 1분 집계가 처음부터 빠짐없이 완전할 때만 장후반 수익률·패턴을 계산해요.
        Instant lateStart = date.atTime(policy.analysisStart()).atZone(ZONE).toInstant();
        List<Minute> late = rows.stream().filter(r -> !r.start().isBefore(lateStart)).toList();
        boolean lateComplete = !late.isEmpty() && late.getFirst().start().equals(lateStart)
                && late.stream().allMatch(Minute::complete) && contiguous(late);
        Map<String, Object> patterns = patterns(lateComplete ? late : List.of(), policy.pullbackPercent());
        BigDecimal lateReturn = lateComplete ? pct(last.close(), late.getFirst().open()) : null;
        BigDecimal high = lateComplete ? late.stream().map(Minute::high).max(BigDecimal::compareTo).orElse(null) : null;
        Map<String, Object> price = fields("currentPrice", last.close(), "open", last.dayOpen(), "high", last.dayHigh(), "low", last.dayLow(),
                "prevClose", prevClose, "changePct", priceReturn, "changeFromOpenPct", pct(last.close(), last.dayOpen()),
                "dayRangePosition", range(last.close(), last.dayHigh(), last.dayLow()),
                "distanceFromHighPct", positive(last.dayHigh()) ? pct(last.close(), last.dayHigh()).negate() : null,
                "distanceFromLowPct", pct(last.close(), last.dayLow()), "lateSessionReturnPct", lateReturn,
                "highAfter1430", high, "newHighAfter1430", patterns.get("newHighAfter1430"),
                "highBreakTime", patterns.get("highBreakTime"), "pullbackDepthPct", patterns.get("pullbackDepthPct"));
        Context index = context(contexts, "INDEX", marketName, marketName, cutoff, evaluatedAt, policy.contextMaxAge());
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("name", marketName); market.put("marketReturnPct", index == null ? null : index.returnPct());
        market.put("receivedAt", index == null ? null : index.receivedAt());
        market.put("relativeStrengthMarket", index == null ? null : relative(priceReturn, index.returnPct()));
        for (int n : List.of(30, 60)) {
            Context old = context(contexts, "INDEX", marketName, marketName, cutoff.minusSeconds(n * 60L), evaluatedAt, policy.contextMaxAge());
            market.put("marketReturn" + n + "mPct", index == null || old == null ? null : pct(index.value(), old.value()));
        }
        Map<String, Object> technical = daily(daily, last.close());
        technical.put("upperWickRatio", last.dayHigh() == null || last.dayOpen() == null || last.dayLow() == null ? null
                : ratio(last.dayHigh().subtract(last.dayOpen().max(last.close())), last.dayHigh().subtract(last.dayLow())));
        BigDecimal lateBuy = lateComplete && late.stream().allMatch(r -> r.buyVolume() != null)
                ? late.stream().map(Minute::buyVolume).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        BigDecimal lateSell = lateComplete && late.stream().allMatch(r -> r.sellVolume() != null)
                ? late.stream().map(Minute::sellVolume).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        technical.put("lateSessionSellRatio", lateBuy == null || lateSell == null ? null : ratio(lateSell, lateBuy.add(lateSell)));
        // 하위 점수는 입력값을 50+50*tanh(x/10)로 바꾼 미검증 표시값이에요. 미수집 분야는 null 점수예요.
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("LateMoneyFlowScore", score((BigDecimal) money.get("turnover15mChangePct"), "turnover15mChangePct"));
        scores.put("ClosingStrengthScore", score(lateReturn, "lateSessionReturnPct"));
        scores.put("ExecutionMomentumScore", score((BigDecimal) execution.get("executionStrengthSlope15m"), "executionStrengthSlope15m"));
        scores.put("VWAPStrengthScore", score((BigDecimal) vw.get("vwapDistanceSlope15m"), "vwapDistanceSlope15m"));
        scores.put("MarketRelativeStrengthScore", score((BigDecimal) market.get("relativeStrengthMarket"), "relativeStrengthMarket"));
        for (String key : List.of("OrderbookPressureScore", "SectorStrengthScore", "ThemeBreadthScore", "CatalystQualityScore", "OvernightRiskScore")) scores.put(key, score(null, "NOT_COLLECTED"));
        scores.put("OverheatRiskScore", score((BigDecimal) technical.get("return5d"), "return5d"));
        return new Snapshot(symbol, cutoff, evaluatedAt, evaluatedAt, "closing-trajectory-v2-flow-shadow", price, volumes, money, execution, vw,
                technical, patterns, market, scores, fields("catalystKnown", false, "catalystType", "UNKNOWN", "confidence", "UNVALIDATED"),
                Map.of(), Map.of(), InvestorFlowFeatures.calculate(symbol, contexts, cutoff, evaluatedAt, policy.contextMaxAge()), Map.of(), Map.of(), List.of(), List.of(),
                fields("latestMinuteComplete", last.complete(), "lateSessionComplete", lateComplete, "dailyCount", daily.size(),
                        "investorFlow", "SEE_PER_PARTY_OBSERVATION_STATUS",
                        "unavailable", List.of("orderbook", "closingAuction", "sector", "theme", "news", "disclosures")), policy);
    }

    /**
     * 하는 일: 입력값을 0~100 범위 표시값으로 바꾸고 입력·이유·산식·상태를 함께 담아요.
     * 받는 것: 입력값(없을 수 있음), 이유 문자열. 돌려주는 것: 점수 Map이에요(입력이 없으면 value=null).
     */
    static Map<String, Object> score(BigDecimal x, String reason) {
        // Bounded signed evidence scale, not calibrated probability and never a production ranking input.
        Double value = x == null ? null : 50 + 50 * Math.tanh(x.doubleValue() / 10);
        return fields("value", value, "input", x, "reason", reason, "formula", "50+50*tanh(input/10)", "status", "SHADOW_UNVALIDATED");
    }
    /**
     * 하는 일: 지정 시각(at) 이하, 계산시각 이하에 받았고 age 이내로 신선한 같은 날 관측 중 가장 늦게 받은 것을 골라요.
     * 받는 것: 관측 목록, 종류, 종목/지수, 범위, 기준 시각, 계산시각, 최대 나이예요. 돌려주는 것: Context 또는 null이에요.
     */
    static Context context(List<Context> rows, String kind, String symbol, String scope, Instant at, Instant available, Duration age) {
        return rows.stream().filter(r -> r.kind().equals(kind) && r.symbol().equals(symbol) && r.scope().equals(scope)
                && !r.receivedAt().isAfter(at) && !r.receivedAt().isAfter(available)
                && !r.receivedAt().isBefore(at.minus(age))
                && r.receivedAt().atZone(ZONE).toLocalDate().equals(at.atZone(ZONE).toLocalDate()))
                .max(Comparator.comparing(Context::receivedAt)).orElse(null);
    }
    /**
     * 하는 일: 1분과 지정 구간(N분)의 합계, 그리고 최근 N분 대비 직전 N분 변화율(%)을 계산해요.
     * 받는 것: 1분 집계, 끝 시각, 이름 접두어, 값 꺼내기 함수, 구간 목록이에요. 돌려주는 것: "접두어Nm", "접두어NmChangePct" Map이에요.
     */
    static Map<String, Object> windows(List<Minute> rows, Instant end, String key, Function<Minute, BigDecimal> value, List<Integer> periods) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<Integer> windows = new TreeSet<>(periods); windows.add(1);
        for (int n : windows) {
            BigDecimal recent = sum(rows, end, n, value);
            result.put(key + n + "m", recent);
            if (n > 1) result.put(key + n + "mChangePct", pct(recent, sum(rows, end.minusSeconds(n * 60L), n, value)));
        }
        return result;
    }
    /** 하는 일: end 직전 N분의 1분 집계를 골라요. 정확히 N개이고 모두 완전하며 이어져 있을 때만 돌려주고, 아니면 빈 목록이에요. */
    static List<Minute> window(List<Minute> rows, Instant end, int minutes) {
        List<Minute> subset = rows.stream().filter(r -> !r.start().isBefore(end.minusSeconds(minutes * 60L)) && r.start().isBefore(end)).toList();
        return subset.size() == minutes && subset.stream().allMatch(Minute::complete) && contiguous(subset) ? subset : List.of();
    }
    /** 하는 일: end 직전 N분 구간의 값을 더해요. 받는 것: 1분 집계, 끝, N, 값 함수. 돌려주는 것: 합계, 구간이 불완전하거나 값이 하나라도 없으면 null이에요. */
    static BigDecimal sum(List<Minute> rows, Instant end, int n, Function<Minute, BigDecimal> field) {
        List<Minute> w = window(rows, end, n);
        return w.isEmpty() || w.stream().anyMatch(r -> field.apply(r) == null) ? null : w.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    /** 하는 일: 분 순서(0,1,2…)에 대한 값의 최소제곱 회귀 기울기를 계산해요. 받는 것: 1분 집계, 값 함수. 돌려주는 것: 분당 기울기, 2개 미만이거나 값이 없으면 null이에요. */
    public static BigDecimal slope(List<Minute> rows, Function<Minute, BigDecimal> field) {
        if (rows.size() < 2 || rows.stream().anyMatch(r -> field.apply(r) == null)) return null;
        double meanX = (rows.size() - 1) / 2.0, meanY = rows.stream().map(field).mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
        double numerator = 0, denominator = 0;
        for (int i = 0; i < rows.size(); i++) { numerator += (i - meanX) * (field.apply(rows.get(i)).doubleValue() - meanY); denominator += (i - meanX) * (i - meanX); }
        return BigDecimal.valueOf(numerator / denominator).setScale(8, RoundingMode.HALF_UP);
    }
    /** 하는 일: 1분 집계가 1분 간격으로 빠짐없이 이어지는지 확인해요. 돌려주는 것: 이어지면 true예요. */
    static boolean contiguous(List<Minute> rows) {
        for (int i = 1; i < rows.size(); i++) if (!rows.get(i - 1).start().plusSeconds(60).equals(rows.get(i).start())) return false;
        return true;
    }
    /** 하는 일: 목록 끝에서부터 거꾸로 보며 완전하고 이어지는 마지막 구간만 잘라 돌려줘요. 받는 것: 1분 집계. 돌려주는 것: 뒤쪽 연속 구간이에요. */
    static List<Minute> trailingContinuous(List<Minute> rows) {
        int start = rows.size();
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (!rows.get(i).complete() || (i < rows.size() - 1 && !rows.get(i).start().plusSeconds(60).equals(rows.get(i + 1).start()))) break;
            start = i;
        }
        return rows.subList(start, rows.size());
    }
    /**
     * 하는 일: 장후반 1분 집계로 패턴을 관측해요. Late Breakout(최근 5분 내 당일 고가 돌파+대금 증가+VWAP 위+가격 상승),
     * Pullback Recovery(일정 % 눌림 뒤 VWAP 지지와 거래량 증가로 고점 근처 회복), Failed Breakout(돌파 후 VWAP 아래로 이탈하며 약세),
     * Distribution(대금·매도량 증가인데 가격 하락)이에요. 자동 제외 조건으로 쓰지 않아요.
     * 받는 것: 장후반 1분 집계, 눌림 기준(%)이에요. 돌려주는 것: 패턴 Map이에요. 3분 미만이면 INSUFFICIENT_DATA, 판단 자료가 없으면 각 값은 null이에요.
     */
    public static Map<String, Object> patterns(List<Minute> rows, BigDecimal pullbackThreshold) {
        if (rows.size() < 3) return fields("state", "INSUFFICIENT_DATA", "lateBreakout", null, "pullbackRecovery", null,
                "failedBreakout", null, "distribution", null, "newHighAfter1430", null);
        BigDecimal peak = rows.getFirst().high(), deepest = BigDecimal.ZERO; Instant breakAt = null;
        boolean pulled = false, recovered = false, supported = false; int pullbackAt = -1;
        for (int i = 1; i < rows.size(); i++) {
            Minute r = rows.get(i), prev = rows.get(i - 1);
            if (prev.dayHigh() != null && r.high().compareTo(prev.dayHigh()) > 0) breakAt = r.start().plusSeconds(60);
            // Minute OHLC cannot establish intra-minute ordering: only earlier minute peaks establish pullbacks.
            BigDecimal depth = pct(r.low(), peak).negate(); deepest = deepest.max(depth);
            if (depth.compareTo(pullbackThreshold) >= 0) {
                if (!pulled) pullbackAt = i;
                pulled = true; supported |= vwap(r) != null && r.close().compareTo(vwap(r)) >= 0;
            }
            if (pulled && i > pullbackAt && supported && r.close().compareTo(peak.multiply(new BigDecimal("0.995"))) >= 0
                    && r.volume() != null && prev.volume() != null && r.volume().compareTo(prev.volume()) > 0) recovered = true;
            peak = peak.max(r.high());
        }
        Minute last = rows.getLast(); Instant end = last.start().plusSeconds(60);
        BigDecimal moneyChange = pct(sum(rows, end, 5, Minute::turnover), sum(rows, end.minusSeconds(300), 5, Minute::turnover));
        BigDecimal priceChange = rows.size() < 6 ? null : pct(last.close(), rows.get(rows.size() - 6).close());
        BigDecimal sellChange = pct(sum(rows, end, 5, Minute::sellVolume), sum(rows, end.minusSeconds(300), 5, Minute::sellVolume));
        BigDecimal distance = pct(last.close(), vwap(last));
        BigDecimal strengthSlope = slope(window(rows, end, 5), Minute::executionStrength);
        boolean recentBreak = breakAt != null && !breakAt.isBefore(end.minusSeconds(300));
        boolean crossedBelowAfterBreak = false;
        for (int i = 1; i < rows.size(); i++) {
            Minute r = rows.get(i), previous = rows.get(i - 1);
            BigDecimal before = pct(previous.close(), vwap(previous)), after = pct(r.close(), vwap(r));
            if (breakAt != null && !r.start().plusSeconds(60).isBefore(breakAt)
                    && positive(before) && after != null && after.signum() <= 0) crossedBelowAfterBreak = true;
        }
        return fields("state", "OBSERVED", "lateBreakout", moneyChange == null || distance == null || priceChange == null ? null
                        : recentBreak && positive(moneyChange) && positive(distance) && positive(priceChange),
                "pullbackOccurred", pulled, "pullbackRecovery", recovered, "recoveredFromPullback", recovered,
                "pullbackDepthPct", deepest, "highBreakTime", breakAt, "newHighAfter1430", breakAt != null,
                "failedBreakout", priceChange == null || strengthSlope == null || distance == null ? null
                        : recentBreak && crossedBelowAfterBreak && negative(priceChange) && negative(strengthSlope) && negative(distance),
                "distribution", moneyChange == null || priceChange == null || sellChange == null ? null
                        : positive(moneyChange) && negative(priceChange) && positive(sellChange));
    }
    /** 하는 일: 최근 N개 일봉 값의 평균을 구해요. 받는 것: 일봉(오래된→최근 순), N, 값 함수. 돌려주는 것: 평균, 봉이 모자라거나 값이 없으면 null이에요. */
    static BigDecimal averageDaily(List<StockCandle> rows, int n, Function<StockCandle, BigDecimal> field) {
        if (rows.size() < n) return null;
        List<BigDecimal> values = rows.subList(rows.size() - n, rows.size()).stream().map(field).toList();
        return values.contains(null) ? null : ratio(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add), BigDecimal.valueOf(n));
    }
    /**
     * 하는 일: 직전 거래일까지의 일봉으로 1/3/5/10/20일 수익률, 5/10/20일 고가·저가·이동평균·평균 거래량·대금, 10/20일 변동성(표본 표준편차, 비연율화 %)을 계산해요.
     * 받는 것: 일봉(오래된→최근 순), 현재가예요. 돌려주는 것: 값 Map이에요. 52주 고가는 미수집이라 null이에요.
     */
    static Map<String, Object> daily(List<StockCandle> rows, BigDecimal price) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int n : List.of(1, 3, 5, 10, 20)) out.put("return" + n + "d", rows.size() < n ? null : pct(price, rows.get(rows.size() - n).getClose()));
        for (int n : List.of(5, 10, 20)) {
            var w = rows.size() < n ? List.<StockCandle>of() : rows.subList(rows.size() - n, rows.size());
            out.put("high" + n + "d", w.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null));
            out.put("low" + n + "d", w.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null));
            out.put("ma" + n, averageDaily(rows, n, StockCandle::getClose));
            out.put("avgVolume" + n + "d", averageDaily(rows, n, r -> BigDecimal.valueOf(r.getVolume())));
            out.put("avgTurnover" + n + "d", averageDaily(rows, n, StockCandle::getTradingValue));
            if (n >= 10) {
                Double volatility = null;
                if (rows.size() >= n + 1) {
                    List<Double> returns = new ArrayList<>();
                    for (int i = rows.size() - n; i < rows.size(); i++) returns.add(pct(rows.get(i).getClose(), rows.get(i - 1).getClose()).doubleValue());
                    double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                    volatility = Math.sqrt(returns.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (n - 1));
                }
                out.put("volatility" + n + "d", volatility);
            }
        }
        out.put("distanceFrom20dHighPct", pct(price, (BigDecimal) out.get("high20d")));
        out.put("high52w", null); out.put("distanceFrom52wHighPct", null);
        out.put("historyBasis", "PRIOR_COMPLETED_DAILY_CANDLES");
        return out;
    }
}
