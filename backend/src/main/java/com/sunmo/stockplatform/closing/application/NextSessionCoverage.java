package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.candle.domain.StockCandle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Completeness of a next-session 5M series under the KRX closing call auction.
 *
 * <p>Continuous trading runs 09:00-15:20; 15:20-15:30 is a single-price call with no executions, and the
 * closing price is one execution stamped 15:30:00..15:30:30 (random end) that is bucketed into the 15:25 bar.
 * So the 15:20 bar is structurally empty and is never required, and the 15:25 bar normally holds only the
 * closing print. Complete means:
 * <ul>
 *   <li>every regular bucket 09:00..15:15 has exactly one final, price-valid bar, and</li>
 *   <li>the 15:25 closing-print bar exists exactly once with traded volume &gt; 0 (a zero-volume padded bar,
 *       e.g. from the KIS minute-chart backfill, carries the last continuous price, not the close).</li>
 * </ul>
 * A missing closing print is reported as the 15:25 start in the missing list. The daily (1D) candle is never
 * substituted for it.
 */
public final class NextSessionCoverage {
    public static final String RULE = "regular-0900-1515+closing-print-1525";
    /** The closing print may be stamped up to the auction window; allow one more minute for bar finalization. */
    public static final Duration SETTLE_AFTER_CLOSE = FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW
            .plus(Duration.ofMinutes(1));
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final Duration CLOSING_CALL = Duration.ofMinutes(10);

    private NextSessionCoverage() {
    }

    /** Before this instant the session's final bar may still be forming: report IN_PROGRESS, not incomplete. */
    public static Instant settledAt(Instant close) {
        return close.plus(SETTLE_AFTER_CLOSE);
    }

    public static Instant closingPrintStart(Instant close) {
        return close.minus(BAR);
    }

    /**
     * Required bar starts that are missing at {@code now}. {@code series} must already be filtered to final,
     * 5-minute-aligned, price-valid bars of the session.
     */
    public static List<Instant> missing(Instant open, Instant close, Instant now, Collection<StockCandle> series) {
        List<Instant> missing = new ArrayList<>();
        Instant callStart = close.minus(CLOSING_CALL);
        for (Instant start = open; start.isBefore(callStart) && !start.plus(BAR).isAfter(now); start = start.plus(BAR)) {
            Instant at = start;
            if (series.stream().filter(c -> c.getStartTime().equals(at)).count() != 1)
                missing.add(start);
        }
        if (!now.isBefore(settledAt(close))) {
            Instant print = closingPrintStart(close);
            List<StockCandle> bars = series.stream().filter(c -> c.getStartTime().equals(print)).toList();
            if (bars.size() != 1 || bars.getFirst().getVolume() <= 0)
                missing.add(print);
        }
        return missing;
    }
}
