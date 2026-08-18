package com.sunmo.stockplatform.kis.master;

import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.assertj.core.api.Assertions.assertThat;

class KisMasterParserTest {
    @Test
    void parsesKospiIdentityAndStatusFields() {
        int[] widths = {2,1,4,4,4,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,9,5,5,1,1,1};
        String suffix = " ".repeat(228);
        suffix = replaceField(suffix, widths, 0, "EF");
        suffix = replaceField(suffix, widths, 12, "2");
        suffix = replaceField(suffix, widths, 34, "Y");
        suffix = replaceField(suffix, widths, 36, "Y");
        String line = fixed("069500", 9) + fixed("KR7069500007", 12) + "KODEX 200" + suffix;

        var parsed = new KisMasterParser().parse(line.getBytes(Charset.forName("MS949")), Market.KOSPI);

        assertThat(parsed).singleElement().satisfies(stock -> {
            assertThat(stock.stockCode()).isEqualTo("069500");
            assertThat(stock.stockName()).isEqualTo("KODEX 200");
            assertThat(stock.marketType()).isEqualTo(MarketType.ETF);
            assertThat(stock.managed()).isTrue();
            assertThat(stock.tradingHalted()).isTrue();
        });
    }

    private String replaceField(String source, int[] widths, int index, String value) {
        int start = 0;
        for (int i = 0; i < index; i++) start += widths[i];
        return source.substring(0, start) + fixed(value, widths[index]) + source.substring(start + widths[index]);
    }

    private String fixed(String value, int width) {
        return value + " ".repeat(width - value.length());
    }
}

