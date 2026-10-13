package com.sunmo.stockplatform.intraday;

/**
 * KRX stock tick table in force since 2023-01-25 (same for KOSPI and KOSDAQ).
 * ETF/ETN/ELW use other grids and are outside the intraday stock universe; earlier history used other bands.
 */
public final class KrxTickSize {
    private static final double[] UPPER = {2_000, 5_000, 20_000, 50_000, 200_000, 500_000};
    private static final double[] TICK = {1, 5, 10, 50, 100, 500, 1_000};

    private KrxTickSize() { }

    /** Tick for an order at this price; a band's lower bound already uses the larger tick. */
    public static double at(double price) {
        if (!Double.isFinite(price) || price <= 0) return Double.NaN;
        for (int i = 0; i < UPPER.length; i++) if (price < UPPER[i]) return TICK[i];
        return TICK[TICK.length - 1];
    }

    /** Nearest valid price at or below (up=false) / at or above (up=true). */
    public static double round(double price, boolean up) {
        double tick = at(price);
        if (Double.isNaN(tick)) return Double.NaN;
        // Band bounds are multiples of both neighbouring ticks, so a result on a boundary is still a valid quote.
        return (up ? Math.ceil(price / tick - 1e-9) : Math.floor(price / tick + 1e-9)) * tick;
    }
}
