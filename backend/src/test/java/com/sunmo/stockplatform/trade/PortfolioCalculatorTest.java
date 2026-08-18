package com.sunmo.stockplatform.trade;

import com.sunmo.stockplatform.stock.domain.*;
import com.sunmo.stockplatform.trade.application.PortfolioCalculator;
import com.sunmo.stockplatform.trade.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PortfolioCalculatorTest {
    private final Stock stock = new Stock("005930", null, "삼성전자", Market.KOSPI, MarketType.STOCK, false, false, Instant.parse("2026-08-01T00:00:00Z"));
    private final PortfolioCalculator calculator = new PortfolioCalculator();

    @Test void calculatesWeightedAverageRealizedPnlAndHoldingDays() {
        Trade first = trade(1, TradeType.BUY, "2026-08-10T00:00:00Z", "100", 10);
        Trade second = trade(2, TradeType.BUY, "2026-08-11T00:00:00Z", "110", 10);
        Trade sell = trade(3, TradeType.SELL, "2026-08-12T00:00:00Z", "120", 4);
        var metrics = calculator.calculate(List.of(first, second, sell), Instant.parse("2026-08-13T00:00:00Z"));
        assertThat(metrics.get(3L).realizedPnl()).isEqualByComparingTo("60.0000");
        assertThat(metrics.get(3L).holdingDays()).isEqualTo(2);
    }

    @Test void rejectsSellBeyondRecordedPosition() {
        Trade buy = trade(1, TradeType.BUY, "2026-08-10T00:00:00Z", "100", 2);
        Trade sell = trade(2, TradeType.SELL, "2026-08-11T00:00:00Z", "120", 3);
        assertThatThrownBy(() -> calculator.calculate(List.of(buy, sell), Instant.parse("2026-08-12T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds");
    }

    private Trade trade(long id, TradeType type, String at, String price, long quantity) {
        Trade trade = new Trade(1L, stock, type, Instant.parse(at), new BigDecimal(price), quantity, null);
        ReflectionTestUtils.setField(trade, "id", id);
        return trade;
    }
}
