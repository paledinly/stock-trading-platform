package com.sunmo.stockplatform.kis.config;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/** One process-wide budget for ranking, price and minute endpoints using the configured app key. */
@Component
public class KisRequestExecutor {
    private final KisRequestProperties properties;
    private long nextRequest;
    private long nextQuote;
    private long blockedUntil;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong throttled = new AtomicLong();

    public KisRequestExecutor(KisRequestProperties properties) { this.properties = properties; }

    public <T> T execute(boolean quote, Supplier<T> request) {
        for (int attempt = 0; ; attempt++) {
            pace(quote);
            requests.incrementAndGet();
            try { return request.get(); }
            catch (KisRateLimitException error) {
                backoff(attempt);
                if (attempt >= properties.maxRetries()) throw error;
            } catch (RestClientResponseException error) {
                if (error.getStatusCode().value() != 429) throw error;
                backoff(attempt);
                if (attempt >= properties.maxRetries()) throw new KisRateLimitException("KIS HTTP 429");
            }
            retries.incrementAndGet();
        }
    }

    private synchronized void pace(boolean quote) {
        long now = System.nanoTime();
        long due = Math.max(nextRequest, blockedUntil);
        if (quote) due = Math.max(due, nextQuote);
        long wait = due - now;
        if (wait > 0) {
            try { java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new com.sunmo.stockplatform.common.error.ApplicationException(
                        com.sunmo.stockplatform.common.error.ErrorCode.KIS_API_ERROR,
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "KIS request interrupted", error);
            }
        }
        now = System.nanoTime();
        nextRequest = now + properties.minimumInterval().toNanos();
        if (quote) nextQuote = now + properties.quoteInterval().toNanos();
    }

    private synchronized void backoff(int attempt) {
        throttled.incrementAndGet();
        blockedUntil = Math.max(blockedUntil, System.nanoTime()
                + properties.rateLimitBackoff().multipliedBy(1L << attempt).toNanos());
    }

    public java.util.Map<String, Long> diagnostics() {
        return java.util.Map.of("requests", requests.get(), "rateLimitRetries", retries.get(),
                "rateLimitErrors", throttled.get());
    }
}
