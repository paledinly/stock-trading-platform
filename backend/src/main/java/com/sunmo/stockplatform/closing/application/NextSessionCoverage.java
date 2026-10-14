/*
 * 다음 거래일 5분봉 묶음이 "성과를 계산해도 될 만큼 빠짐없이 모였는지"를 판정하는 규칙 모음이에요.
 * 15:20~15:30 동시호가(주문을 모았다가 한 가격으로 한꺼번에 체결하는 방식) 때문에 생기는 빈 15:20 봉과 15:25 종가 봉을 특별히 다뤄요.
 * 다음날 성과 추적(OvernightPerformanceService)과 후보 관측(ClosingCandidateObservationService)이 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.candle.domain.StockCandle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 다음 거래일 5분봉이 완전한지 확인하는 출석부 같은 클래스예요.
 * 09:00~15:15 학생(정규 봉)은 한 명씩 꼭 출석해야 하고, 15:20 학생은 원래 결석이 정상이며, 15:25 학생(종가 체결 봉)은 거래량이 있는 진짜로 와야 출석으로 쳐 줘요.
 *
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
    // 완전성 규칙의 이름표예요(정규 09:00~15:15 + 15:25 종가 봉). 현재 main 코드에서는 직접 참조하지 않는 것으로 보여요.
    public static final String RULE = "regular-0900-1515+closing-print-1525";
    /**
     * 종가 체결은 동시호가 창(CLOSING_AUCTION_WINDOW, 60초) 안에 찍힐 수 있으니, 봉 확정을 위해 1분을 더 기다려요(마감+2분).
     * The closing print may be stamped up to the auction window; allow one more minute for bar finalization.
     */
    public static final Duration SETTLE_AFTER_CLOSE = FiveMinuteCandleAggregator.CLOSING_AUCTION_WINDOW
            .plus(Duration.ofMinutes(1));
    // 5분봉 길이와 장 마감 동시호가 길이(15:20~15:30, 10분)예요.
    private static final Duration BAR = Duration.ofMinutes(5);
    private static final Duration CLOSING_CALL = Duration.ofMinutes(10);

    /** 인스턴스를 만들지 않는 정적 유틸리티라 생성자를 막아 뒀어요. */
    private NextSessionCoverage() {
    }

    /**
     * 하는 일: 이 시각 전에는 마지막 봉이 아직 만들어지는 중일 수 있으니 "미완성"이 아니라 "진행 중(IN_PROGRESS)"으로 봐야 하는 기준 시각을 알려 줘요.
     * 받는 것: 그 세션의 마감 시각. 돌려주는 것: 마감 + SETTLE_AFTER_CLOSE(2분).
     * Before this instant the session's final bar may still be forming: report IN_PROGRESS, not incomplete.
     */
    public static Instant settledAt(Instant close) {
        return close.plus(SETTLE_AFTER_CLOSE);
    }

    /**
     * 하는 일: 종가 체결이 담기는 봉(마감 5분 전에 시작하는 봉, 보통 15:25)의 시작 시각을 알려 줘요.
     * 받는 것: 세션 마감 시각. 돌려주는 것: 마감 - 5분.
     */
    public static Instant closingPrintStart(Instant close) {
        return close.minus(BAR);
    }

    /**
     * 하는 일: now 시점까지 있어야 하는데 빠진 봉의 시작 시각 목록을 만들어요.
     * 받는 것: 세션 시작·마감 시각, 판정 시각(now), 이미 확정·5분 정렬·가격 정합으로 걸러진 봉 목록.
     * 돌려주는 것: 빠진 봉 시작 시각 목록(비어 있으면 완전). 같은 시각 봉이 2개 이상이어도 빠진 것으로 봐요.
     *
     * Required bar starts that are missing at {@code now}. {@code series} must already be filtered to final,
     * 5-minute-aligned, price-valid bars of the session.
     */
    public static List<Instant> missing(Instant open, Instant close, Instant now, Collection<StockCandle> series) {
        List<Instant> missing = new ArrayList<>();
        // 준비: 동시호가 시작 시각(마감-10분, 보통 15:20)을 구해요. 그 전까지가 정규 봉이에요.
        Instant callStart = close.minus(CLOSING_CALL);
        // 처리 1: 정규 봉(09:00~15:15 시작) 중 이미 끝났어야 할 봉마다 정확히 1개 있는지 확인해요.
        for (Instant start = open; start.isBefore(callStart) && !start.plus(BAR).isAfter(now); start = start.plus(BAR)) {
            Instant at = start;
            if (series.stream().filter(c -> c.getStartTime().equals(at)).count() != 1)
                missing.add(start);
        }
        // 처리 2: 마감+2분이 지났으면 15:25 종가 봉이 정확히 1개이고 거래량이 0보다 큰지 확인해요.
        // 거래량 0인 봉은 마지막 정규 가격을 채운 가짜 봉이라 종가로 인정하지 않아요. 일봉으로 대신 채우지도 않아요.
        if (!now.isBefore(settledAt(close))) {
            Instant print = closingPrintStart(close);
            List<StockCandle> bars = series.stream().filter(c -> c.getStartTime().equals(print)).toList();
            if (bars.size() != 1 || bars.getFirst().getVolume() <= 0)
                missing.add(print);
        }
        return missing;
    }
}
