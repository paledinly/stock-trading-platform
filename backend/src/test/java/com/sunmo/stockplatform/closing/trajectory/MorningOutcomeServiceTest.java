package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MorningOutcomeServiceTest {
    private static final Instant OPEN = Instant.parse("2026-09-30T00:00:00Z");
    private StockCandle candle(int minute, int open, int high, int low, int close) {
        return new StockCandle(null, OPEN.plusSeconds(minute * 60L), BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 100, BigDecimal.valueOf(10000), true, 1);
    }
    @Test void closeBasedTargetsDifferFromEntryReturnsAndMissingMinutesRemainUnknown() {
        var rows = List.of(candle(0, 102, 104, 99, 103), candle(5, 103, 105, 100, 104));
        var result = MorningOutcomeService.calculate(rows, OPEN, OPEN.plusSeconds(600), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(3), BigDecimal.valueOf(-2), Instant.now().plus(Duration.ofDays(1)));
        assertThat((BigDecimal) result.get("nextOpenReturn")).isEqualByComparingTo("2");
        assertThat((BigDecimal) result.get("entryToNextOpenReturn")).isNotEqualByComparingTo("2");
        assertThat(result.get("hit5Pct")).isEqualTo(true);
        assertThat(result.get("stopHitBeforeTarget")).isEqualTo(false);
        var gap = MorningOutcomeService.calculate(List.of(rows.getLast()), OPEN, OPEN.plusSeconds(600), BigDecimal.valueOf(100),
                null, BigDecimal.valueOf(3), BigDecimal.valueOf(-2), Instant.now().plus(Duration.ofDays(1)));
        assertThat(gap.get("nextOpenReturn")).isNull();
        assertThat(gap.get("nextMorningMaxReturn")).isNull();
        assertThat(gap.get("hit1Pct")).isNull();
    }
    @Test void sameCandleTargetAndStopAreAmbiguousButOpeningGapHasKnownOrder() {
        assertThat(MorningOutcomeService.stopBeforeTarget(List.of(candle(0, 100, 104, 97, 101)), BigDecimal.valueOf(100),
                BigDecimal.valueOf(3), BigDecimal.valueOf(-2))).isNull();
        assertThat(MorningOutcomeService.stopBeforeTarget(List.of(candle(0, 97, 104, 97, 101)), BigDecimal.valueOf(100),
                BigDecimal.valueOf(3), BigDecimal.valueOf(-2))).isTrue();
    }
}
