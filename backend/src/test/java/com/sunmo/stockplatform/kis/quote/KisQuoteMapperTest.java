package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

class KisQuoteMapperTest {
    @Test
    void mapsKisFieldsWithoutFloatingPoint() {
        Instant instant = Instant.parse("2026-08-17T01:00:00Z");
        var stock = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, MarketType.STOCK,
                false, false, instant);
        var output = new KisQuoteResponse.Output("70000", "2", "1200", "1.744", "69000", "70500", "68800",
                "12345678", "864197520000");

        var quote = new KisQuoteMapper(Clock.fixed(instant, ZoneOffset.UTC)).map(stock, output);

        assertThat(quote.currentPrice()).isEqualByComparingTo(new BigDecimal("70000"));
        assertThat(quote.changeRate()).isEqualByComparingTo(new BigDecimal("1.744"));
        assertThat(quote.accumulatedVolume()).isEqualTo(12_345_678L);
        assertThat(quote.quotedAt()).isEqualTo(instant);
    }

    @Test
    void appliesKisDownSignToChangeAndRate() {
        Instant instant = Instant.parse("2026-08-17T01:00:00Z");
        var stock = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, MarketType.STOCK,
                false, false, instant);
        var output = new KisQuoteResponse.Output("68000", "5", "800", "1.16", "69000", "69500", "67500",
                "100", "6800000");

        var quote = new KisQuoteMapper(Clock.fixed(instant, ZoneOffset.UTC)).map(stock, output);

        assertThat(quote.change()).isEqualByComparingTo("-800");
        assertThat(quote.changeRate()).isEqualByComparingTo("-1.16");
    }
}
