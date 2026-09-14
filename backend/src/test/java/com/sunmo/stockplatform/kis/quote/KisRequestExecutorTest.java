package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.kis.config.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class KisRequestExecutorTest {
    private KisRequestExecutor executor(int retries) {
        return new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, retries));
    }
    @Test
    void rateLimitRetriesOnceAndRecordsCounts() {
        var executor = executor(1);
        AtomicInteger calls = new AtomicInteger();
        assertThat(executor.execute(true, () -> {
            if (calls.getAndIncrement() == 0) throw new KisRateLimitException("EGW00201");
            return "ok";
        })).isEqualTo("ok");
        assertThat(executor.diagnostics()).containsEntry("requests", 2L).containsEntry("rateLimitRetries", 1L);
    }
    @Test
    void repeatedRateLimitStopsAtBoundedRetryCount() {
        var executor = executor(1);
        assertThatThrownBy(() -> executor.execute(true, () -> { throw new KisRateLimitException("EGW00201"); }))
                .isInstanceOf(KisRateLimitException.class);
        assertThat(executor.diagnostics()).containsEntry("requests", 2L);
    }
    @Test
    void inputErrorsAreNotRetried() {
        var executor = executor(2);
        assertThatThrownBy(() -> executor.execute(false, () -> {
            KisResponseErrors.failure("ranking", "ERR", "ERROR INPUT FIELD NOT FOUND [FID_INPUT_CNT_1]");
            return "never";
        })).isInstanceOf(KisRequestRejectedException.class);
        assertThat(executor.diagnostics()).containsEntry("requests", 1L).containsEntry("rateLimitRetries", 0L);
    }
    @Test
    void commonBudgetSpacesRankingAndQuoteCalls() {
        var executor = new KisRequestExecutor(new KisRequestProperties(Duration.ofMillis(20), Duration.ofMillis(40), Duration.ZERO, 0));
        long started = System.nanoTime();
        executor.execute(true, () -> "quote");
        executor.execute(false, () -> "ranking");
        executor.execute(true, () -> "quote");
        assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(Duration.ofMillis(40).toNanos());
    }
}
