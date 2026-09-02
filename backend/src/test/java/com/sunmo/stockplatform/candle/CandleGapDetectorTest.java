package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.CandleGapDetector;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandleGapDetectorTest {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");

    @Test void findsOnlyClosedMissingBuckets(){
        LocalDate date=LocalDate.of(2026,9,1);Instant nine=date.atTime(9,0).atZone(SEOUL).toInstant();
        var gaps=new CandleGapDetector().gaps(date,date.atTime(9,17).atZone(SEOUL).toInstant(),List.of(nine,nine.plusSeconds(600)));
        assertThat(gaps).containsExactly(nine.plusSeconds(300));
    }
}
