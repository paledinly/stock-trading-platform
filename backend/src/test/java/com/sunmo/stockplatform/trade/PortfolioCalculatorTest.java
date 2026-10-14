/*
 * PortfolioCalculator가 수동으로 기록한 매매(실주문 아님)로 가중평균 단가, 실현손익, 보유일수를 계산하는지 확인해요.
 * 손익 계산이 틀리면 매매 기록 화면의 성과가 모두 잘못 보이므로 중요해요.
 */
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

/** 수동 매매 기록의 포트폴리오 계산을 점검하는 테스트예요. */
class PortfolioCalculatorTest {
    private final Stock stock = new Stock("005930", null, "삼성전자", Market.KOSPI, MarketType.STOCK, false, false, Instant.parse("2026-08-01T00:00:00Z"));
    private final PortfolioCalculator calculator = new PortfolioCalculator();

    /**
     * 가중평균 단가로 실현손익과 보유일수를 계산하는지 확인해요.
     * 준비: 100원 10주, 110원 10주 매수 뒤 120원에 4주 매도해요.
     * 기대: 평균단가 105원 기준 실현손익 60원, 첫 매수부터 매도까지 보유일수 2일이에요.
     */
    @Test void calculatesWeightedAverageRealizedPnlAndHoldingDays() {
        Trade first = trade(1, TradeType.BUY, "2026-08-10T00:00:00Z", "100", 10);
        Trade second = trade(2, TradeType.BUY, "2026-08-11T00:00:00Z", "110", 10);
        Trade sell = trade(3, TradeType.SELL, "2026-08-12T00:00:00Z", "120", 4);
        var metrics = calculator.calculate(List.of(first, second, sell), Instant.parse("2026-08-13T00:00:00Z"));
        assertThat(metrics.get(3L).realizedPnl()).isEqualByComparingTo("60.0000");
        assertThat(metrics.get(3L).holdingDays()).isEqualTo(2);
    }

    /**
     * 기록된 보유 수량보다 많이 팔면 거부하는지 확인해요.
     * 준비: 2주만 매수한 뒤 3주 매도를 기록해요.
     * 기대: "exceeds"가 담긴 IllegalArgumentException이 나요.
     */
    @Test void rejectsSellBeyondRecordedPosition() {
        Trade buy = trade(1, TradeType.BUY, "2026-08-10T00:00:00Z", "100", 2);
        Trade sell = trade(2, TradeType.SELL, "2026-08-11T00:00:00Z", "120", 3);
        assertThatThrownBy(() -> calculator.calculate(List.of(buy, sell), Instant.parse("2026-08-12T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds");
    }

    /** id를 강제로 넣은 테스트용 매매 기록을 만드는 도우미예요(ReflectionTestUtils로 private 필드 설정). */
    private Trade trade(long id, TradeType type, String at, String price, long quantity) {
        Trade trade = new Trade(1L, stock, type, Instant.parse(at), new BigDecimal(price), quantity, null);
        ReflectionTestUtils.setField(trade, "id", id);
        return trade;
    }
}
