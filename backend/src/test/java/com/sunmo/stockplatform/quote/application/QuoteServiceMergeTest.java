package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteServiceMergeTest {
    @Test
    void mergesRealtimeTickWithoutLosingDailyQuoteFields() {
        Instant baselineAt = Instant.parse("2026-08-26T01:00:00Z");
        StockQuote baseline = new StockQuote("005930", "삼성전자", "KOSPI",
                decimal("70000"), decimal("1000"), decimal("1.449275"), decimal("69000"),
                decimal("70500"), decimal("68800"), 1000, decimal("70000000"), baselineAt);
        MarketTick tick = new MarketTick("005930", LocalDate.parse("2026-08-26"),
                Instant.parse("2026-08-26T01:00:01Z"), decimal("71000"), 10, 1200,
                decimal("85000000"), 1);

        StockQuote merged = QuoteService.merge(baseline, tick);

        assertThat(merged.currentPrice()).isEqualByComparingTo("71000");
        assertThat(merged.openPrice()).isEqualByComparingTo("69000");
        assertThat(merged.highPrice()).isEqualByComparingTo("71000");
        assertThat(merged.lowPrice()).isEqualByComparingTo("68800");
        assertThat(merged.change()).isEqualByComparingTo("2000");
        assertThat(merged.changeRate()).isEqualByComparingTo("2.898551");
        assertThat(merged.accumulatedVolume()).isEqualTo(1200);
        assertThat(merged.accumulatedTradingValue()).isEqualByComparingTo("85000000");
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
