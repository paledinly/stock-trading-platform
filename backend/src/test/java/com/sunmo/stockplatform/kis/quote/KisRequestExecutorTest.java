/*
 * KisRequestExecutor가 KIS 요청의 재시도와 호출 간격(요청 예산)을 지키는지 확인해요.
 * KIS는 초당 호출 한도가 있어서 무한 재시도나 너무 빠른 연속 호출을 막아야 해요.
 */
package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.kis.config.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** KIS 요청 실행기의 재시도·간격 규칙을 점검하는 테스트예요. */
class KisRequestExecutorTest {
    /** 대기 시간은 0, 재시도 횟수만 지정한 실행기를 만드는 도우미예요. */
    private KisRequestExecutor executor(int retries) {
        return new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, retries));
    }
    /**
     * 호출 한도 초과(EGW00201)는 한 번 재시도하고 횟수를 기록하는지 확인해요.
     * 준비: 첫 호출만 한도 초과 예외를 던지게 해요.
     * 기대: 결과는 "ok", 요청 2회·한도 재시도 1회로 기록돼요.
     */
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
    /**
     * 계속 한도 초과가 나면 정해진 횟수에서 멈추는지 확인해요.
     * 준비: 재시도 1회 설정에서 매번 한도 초과 예외를 던지게 해요.
     * 기대: 예외가 그대로 나가고 요청은 2회만 일어나요.
     */
    @Test
    void repeatedRateLimitStopsAtBoundedRetryCount() {
        var executor = executor(1);
        assertThatThrownBy(() -> executor.execute(true, () -> { throw new KisRateLimitException("EGW00201"); }))
                .isInstanceOf(KisRateLimitException.class);
        assertThat(executor.diagnostics()).containsEntry("requests", 2L);
    }
    /**
     * 입력 오류 응답은 재시도하지 않는지 확인해요.
     * 준비: 입력 필드 누락 오류를 내는 호출을 실행해요.
     * 기대: KisRequestRejectedException이 나고 요청 1회, 재시도 0회예요.
     */
    @Test
    void inputErrorsAreNotRetried() {
        var executor = executor(2);
        assertThatThrownBy(() -> executor.execute(false, () -> {
            KisResponseErrors.failure("ranking", "ERR", "ERROR INPUT FIELD NOT FOUND [FID_INPUT_CNT_1]");
            return "never";
        })).isInstanceOf(KisRequestRejectedException.class);
        assertThat(executor.diagnostics()).containsEntry("requests", 1L).containsEntry("rateLimitRetries", 0L);
    }
    /**
     * 시세·순위 호출이 공통 예산을 공유해 간격을 두는지 확인해요.
     * 준비: 시세 20ms, 순위 40ms 간격 설정으로 시세→순위→시세를 연달아 호출해요.
     * 기대: 전체 걸린 시간이 최소 40ms 이상이에요(실제 시간을 재는 테스트예요).
     */
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
