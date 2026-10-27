/*
 * KisQuoteMapper가 KIS 시세 응답 문자열을 시세 객체로 바꾸는지 확인해요.
 * 금액을 부동소수점(double)으로 다루면 오차가 생기고, 하락 부호를 놓치면 등락이 반대로 보여서 중요해요.
 */
package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** KIS 시세 응답 매핑을 점검하는 테스트예요. */
class KisQuoteMapperTest {
    /**
     * 부동소수점 없이 정확한 숫자로 매핑하는지 확인해요.
     * 준비: 현재가 70000, 등락률 1.744, 누적거래량 12345678 응답과 고정 시계 Clock(항상 같은 시각)를 써요.
     * 기대: 값이 그대로 들어가고 시세 시각이 고정 시각과 같아요.
     */
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

    /**
     * KIS 하락 부호 코드("5")를 대비와 등락률에 음수로 반영하는지 확인해요.
     * 준비: 부호 5, 대비 800, 등락률 1.16 응답을 만들어요.
     * 기대: 대비 -800, 등락률 -1.16이에요.
     */
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

    /**
     * 현재가가 비었거나 0이면 0원 시세를 만들지 않고 거절 예외를 던지는지 확인해요.
     * 기대: 두 경우 모두 KisRequestRejectedException이에요(회로 차단기가 세지 않는 예외).
     */
    @Test
    void rejectsQuoteWithoutCurrentPrice() {
        Instant instant = Instant.parse("2026-08-17T01:00:00Z");
        var stock = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, MarketType.STOCK,
                false, false, instant);
        var mapper = new KisQuoteMapper(Clock.fixed(instant, ZoneOffset.UTC));

        assertThatThrownBy(() -> mapper.map(stock, new KisQuoteResponse.Output("", "3", "0", "0", "", "", "", "", "")))
                .isInstanceOf(com.sunmo.stockplatform.kis.config.KisRequestRejectedException.class);
        assertThatThrownBy(() -> mapper.map(stock, new KisQuoteResponse.Output("0", "3", "0", "0", "0", "0", "0", "0", "0")))
                .isInstanceOf(com.sunmo.stockplatform.kis.config.KisRequestRejectedException.class);
    }
}
