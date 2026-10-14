/*
 * QuoteService.merge가 일간 시세(기준값)에 실시간 틱을 합칠 때 필요한 값만 갱신하는지 확인해요.
 * 시가·저가 같은 일간 필드가 사라지면 화면 시세가 틀어지므로 중요해요.
 */
package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 시세와 실시간 틱 병합 규칙을 점검하는 테스트예요. */
class QuoteServiceMergeTest {
    /**
     * 실시간 틱을 합쳐도 일간 시세 필드를 잃지 않는지 확인해요.
     * 준비: 현재가 70000(전일 대비 +1000) 시세와 71000원·누적거래량 1200 틱을 만들어요.
     * 기대: 현재가·고가는 71000으로, 대비 2000·등락률 2.898551로 다시 계산되고 시가·저가는 유지돼요.
     */
    @Test
    void mergesRealtimeTickWithoutLosingDailyQuoteFields() {
        // 준비
        Instant baselineAt = Instant.parse("2026-08-26T01:00:00Z");
        StockQuote baseline = new StockQuote("005930", "삼성전자", "KOSPI",
                decimal("70000"), decimal("1000"), decimal("1.449275"), decimal("69000"),
                decimal("70500"), decimal("68800"), 1000, decimal("70000000"), baselineAt);
        MarketTick tick = new MarketTick("005930", LocalDate.parse("2026-08-26"),
                Instant.parse("2026-08-26T01:00:01Z"), decimal("71000"), 10, 1200,
                decimal("85000000"), 1);

        // 실행
        StockQuote merged = QuoteService.merge(baseline, tick);

        // 확인
        assertThat(merged.currentPrice()).isEqualByComparingTo("71000");
        assertThat(merged.openPrice()).isEqualByComparingTo("69000");
        assertThat(merged.highPrice()).isEqualByComparingTo("71000");
        assertThat(merged.lowPrice()).isEqualByComparingTo("68800");
        assertThat(merged.change()).isEqualByComparingTo("2000");
        assertThat(merged.changeRate()).isEqualByComparingTo("2.898551");
        assertThat(merged.accumulatedVolume()).isEqualTo(1200);
        assertThat(merged.accumulatedTradingValue()).isEqualByComparingTo("85000000");
    }

    /** 문자열을 BigDecimal로 바꾸는 도우미예요. */
    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
