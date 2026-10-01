package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.intraday.IntradayModel.*;
import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.intraday.IntradayFeatures.pct;

/** Ordered, disjoint windows. No RSI proxy and no closing-strategy score. */
public final class IntradaySetups {
    private IntradaySetups() {}
    public record Segment(double open, double high, double low, double close, double volume) {}
    public record Detection(String setup, double support, double resistance, double range, List<String> reasons) {}

    public static Detection detect(List<Input> history, Input now, Features f) {
        Instant at = now.observation().tick().occurredAt();
        Segment base = segment(history, at.minusSeconds(1800), at.minusSeconds(900));
        Segment impulse = segment(history, at.minusSeconds(900), at.minusSeconds(600));
        Segment pullback = segment(history, at.minusSeconds(600), at.minusSeconds(300));
        Segment recovery = segment(history, at.minusSeconds(300), at.plusNanos(1));
        if (base == null || impulse == null || pullback == null || recovery == null) return null;
        return detect(base, impulse, pullback, recovery, f.value("vwap"), now.policy());
    }

    public static Detection detect(Segment base, Segment impulse, Segment pullback, Segment recovery,
                                   double vwap, IntradayProperties p) {
        double range = ((impulse.high - impulse.low) + (pullback.high - pullback.low)
                + (recovery.high - recovery.low)) / 3;
        if (range <= 0 || !Double.isFinite(vwap)) return null;
        boolean sequence = impulse.close > impulse.open && impulse.high > base.high
                && impulse.volume > base.volume / 3 * p.minVolumeRatio()
                && pullback.close < impulse.close && pullback.low > impulse.open
                && pullback.low >= vwap - range * p.stopBufferFraction()
                && pullback.volume < impulse.volume * p.pullbackVolumeFraction()
                && recovery.volume > pullback.volume * p.minVolumeRatio()
                && recovery.low >= pullback.low && recovery.close > recovery.open;
        if (sequence && recovery.close > impulse.high)
            return new Detection("RE_BREAKOUT", pullback.low, impulse.high, range,
                    List.of("1차 고점 형성 → 거래량 감소 조정 → 저점 유지 → 거래량 동반 재돌파"));
        if (sequence && recovery.close > pullback.high && recovery.close < impulse.high)
            return new Detection("PULLBACK", pullback.low, impulse.high, range,
                    List.of("거래량 동반 상승 → 저거래량 눌림 → VWAP 지지 → 거래량 재증가"));
        double resistance = Math.max(base.high, Math.max(impulse.high, pullback.high));
        if (recovery.open <= resistance && recovery.close > resistance
                && recovery.close > vwap && recovery.volume > pullback.volume * p.minVolumeRatio())
            return new Detection("BREAKOUT", resistance, resistance, range,
                    List.of("선행 25분 고점 상향 돌파·돌파선 위 현재 체결 확인"));
        return null;
    }

    public static Plan plan(Detection d, double price, IntradayProperties p) {
        double from = Math.max(d.support, price - d.range * p.entryRangeFraction());
        double to = price + d.range * p.entryRangeFraction();
        double stop = d.support - d.range * p.stopBufferFraction();
        double risk = to - stop;
        double target1 = switch (p.targetMode()) {
            case "FIXED_1" -> to * 1.01;
            case "FIXED_2" -> to * 1.02;
            case "FIXED_3" -> to * 1.03;
            case "PRIOR_HIGH" -> d.resistance;
            case "RANGE" -> to + d.range * p.targetRiskMultiple();
            default -> d.setup.equals("PULLBACK") ? d.resistance : to + risk * p.targetRiskMultiple();
        };
        double target2 = Math.max(target1 + d.range, to + risk * (p.targetRiskMultiple() + 1));
        if (stop <= 0 || risk <= 0 || from > to || target1 <= to) return null;
        double rr = (target1 - to) / risk;
        if (rr < p.minRiskReward()) return null;
        // Cost-aware R:R at the most expensive allowed entry; spread and slippage are explicit assumptions.
        if (p.costs().configured()) {
            double netRisk = -p.costs().net(p.costs().buy(to), p.costs().sell(stop));
            double netReward = p.costs().net(p.costs().buy(to), p.costs().sell(target1));
            if (netRisk <= 0 || netReward / netRisk < p.minRiskReward()) return null;
            rr = netReward / netRisk;
        }
        return new Plan(from, to, to + d.range * p.entryRangeFraction(), stop, target1, target2,
                rr, "구조적 지지/돌파선 하단 " + stop + " 이탈; 진입 전 추격 제한 초과 시 무효");
    }

    static Segment segment(List<Input> history, Instant from, Instant to) {
        List<Input> rows = history.stream().filter(x -> !x.observation().tick().occurredAt().isBefore(from)
                && x.observation().tick().occurredAt().isBefore(to)).toList();
        if (rows.size() < 2) return null;
        double high = rows.stream().mapToDouble(x -> x.observation().tick().price().doubleValue()).max().orElseThrow();
        double low = rows.stream().mapToDouble(x -> x.observation().tick().price().doubleValue()).min().orElseThrow();
        var first = rows.getFirst().observation().tick();
        var last = rows.getLast().observation().tick();
        return new Segment(first.price().doubleValue(), high, low, last.price().doubleValue(),
                last.cumulativeVolume() - first.cumulativeVolume());
    }
}
