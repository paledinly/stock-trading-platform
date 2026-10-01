package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class TrajectoryContextCollectorTest {
    @Test void parsesActualIndexFieldsAndDoesNotReplaceMissingNumbersWithZero() throws Exception {
        var mapper = new ObjectMapper();
        var row = TrajectoryContextCollector.parseIndex("KOSDAQ", mapper.readTree("""
                {"bstp_nmix_prpr":"900.50","bstp_nmix_prdy_ctrt":"-1.25"}
                """), Instant.parse("2026-09-30T05:00:00Z"));
        assertThat(row.value()).isEqualByComparingTo("900.50");
        assertThat(row.returnPct()).isEqualByComparingTo("-1.25");
        assertThatThrownBy(() -> TrajectoryContextCollector.parseIndex("KOSPI", mapper.readTree("{}"), Instant.now()))
                .isInstanceOf(NumberFormatException.class);
    }
}
