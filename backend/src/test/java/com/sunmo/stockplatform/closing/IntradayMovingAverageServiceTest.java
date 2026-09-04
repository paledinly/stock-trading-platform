package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.closing.application.IntradayMovingAverageService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IntradayMovingAverageServiceTest {
    private final IntradayMovingAverageService service = new IntradayMovingAverageService(mock(StockCandleRepository.class));

    @Test
    void calculatesIntradayMovingAverageTrendFeatures() {
        List<StockCandle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T00:00:00Z");
        for (int index = 0; index < 56; index++) {
            candles.add(candle(start.plusSeconds(index * 300L), bd("100")));
        }
        candles.add(candle(start.plusSeconds(56 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(57 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(58 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(59 * 300L), bd("92")));
        candles.add(candle(start.plusSeconds(60 * 300L), bd("140")));

        var feature = service.calculate(candles);

        assertThat(feature.ready()).isTrue();
        assertThat(feature.candleCount()).isEqualTo(61);
        assertThat(feature.ma5()).isEqualByComparingTo("101.600000");
        assertThat(feature.ma20()).isEqualByComparingTo("100.400000");
        assertThat(feature.ma60()).isEqualByComparingTo("100.133333");
        assertThat(feature.goldenCross()).isTrue();
        assertThat(feature.bullishAlignment()).isTrue();
        assertThat(feature.ma20Support()).isFalse();
        assertThat(feature.ma20Broken()).isFalse();
        assertThat(feature.ma20DistanceRate()).isEqualByComparingTo("39.442231");
    }

    @Test
    void reportsNotReadyUntilTwentyCandlesExist() {
        var feature = service.calculate(List.of(candle(Instant.parse("2026-09-04T00:00:00Z"), bd("100"))));

        assertThat(feature.ready()).isFalse();
        assertThat(feature.candleCount()).isEqualTo(1);
        assertThat(feature.ma20()).isNull();
    }

    @Test
    void detectsMa20SupportAndBreakdown() {
        List<StockCandle> support = candles(20, "100");
        support.set(19, candle(Instant.parse("2026-09-04T01:35:00Z"), "101", "102", "99", "101"));
        assertThat(service.calculate(support).ma20Support()).isTrue();

        List<StockCandle> broken = candles(20, "100");
        broken.set(19, candle(Instant.parse("2026-09-04T01:35:00Z"), "99", "100", "98", "99"));
        assertThat(service.calculate(broken).ma20Broken()).isTrue();
    }

    private List<StockCandle> candles(int count, String close) {
        List<StockCandle> values = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T00:00:00Z");
        for (int index = 0; index < count; index++) {
            values.add(candle(start.plusSeconds(index * 300L), bd(close)));
        }
        return values;
    }

    private StockCandle candle(Instant at, BigDecimal close) {
        return candle(at, close.toPlainString(), close.toPlainString(), close.toPlainString(), close.toPlainString());
    }

    private StockCandle candle(Instant at, String open, String high, String low, String close) {
        return new StockCandle(null, at, bd(open), bd(high), bd(low), bd(close), 100, bd("100000"), true, 0);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
