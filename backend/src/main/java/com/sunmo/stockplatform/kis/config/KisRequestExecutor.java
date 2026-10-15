/*
 * 앱 키 하나로 나가는 KIS REST 호출 간격을 프로세스 전체에서 한 줄로 조절하는 파일이에요.
 * 호출 한도 초과(KIS 오류 EGW00201 또는 HTTP 429)를 만나면 점점 길게 쉬었다가 정해진 횟수만큼 재시도해요.
 * 휴장일, 분봉, 일봉, 현재가, 순위 등 KIS REST 클라이언트가 요청을 이 실행기에 맡기고, 진단 화면이 집계 수치를 읽어 가요.
 */
package com.sunmo.stockplatform.kis.config;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/** One process-wide budget for ranking, price and minute endpoints using the configured app key. */
/**
 * 순위, 현재가, 분봉 등 KIS 호출 전체가 함께 쓰는 하나의 호출 예산(속도 조절기)이에요.
 * 놀이공원 입구 직원이 손님을 일정 간격으로만 들여보내고, 안에서 "너무 붐벼요" 신호가 오면 잠시 입장을 멈추는 것과 같아요.
 * 현재가(quote) 호출은 일반 간격에 더해 별도의 더 긴 간격도 지켜요.
 */
@Component
public class KisRequestExecutor {
    private final KisRequestProperties properties;
    // 아래 세 값은 System.nanoTime() 기준의 "다음에 보내도 되는 시각"이에요. synchronized 메서드 안에서만 바뀌어요.
    private long nextRequest;
    private long nextQuote;
    private long blockedUntil;
    // 진단용 누적 카운터예요(총 요청 수, 재시도 수, 한도 초과 횟수).
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong throttled = new AtomicLong();
    // 최종 실패를 KIS 오류 코드(msg_cd, 없으면 "HTTP 500" 같은 상태) 별로 센 값이에요. 서버 시작 이후 누적이에요.
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KisRequestExecutor.class);

    /** 받는 것: 호출 간격, 쉬는 시간, 재시도 횟수 설정. 돌려주는 것: 없음(생성자). */
    public KisRequestExecutor(KisRequestProperties properties) { this.properties = properties; }

    /**
     * 간격을 지켜 KIS 요청을 실행하고, 호출 한도에 걸리면 쉬었다가 재시도해요.
     * 받는 것: 현재가 호출인지 여부, 실제 요청을 담은 함수. 돌려주는 것: 요청 결과.
     * 재시도 횟수를 다 쓰면 KisRateLimitException을 던지고, 429가 아닌 HTTP 오류는 바로 그대로 던져요.
     */
    public <T> T execute(boolean quote, Supplier<T> request) {
        for (int attempt = 0; ; attempt++) {
            // 준비: 내 차례가 될 때까지 기다려요.
            pace(quote);
            requests.incrementAndGet();
            // 처리: 요청을 보내고, 한도 초과만 골라 쉬는 시간을 늘린 뒤 다시 돌아요.
            // 재시도 규칙은 그대로이고, 밖으로 나가는 최종 실패만 failed()로 오류 코드별 수와 로그를 남겨요.
            try { return request.get(); }
            catch (KisRateLimitException error) {
                backoff(attempt);
                if (attempt >= properties.maxRetries()) throw failed(error);
            } catch (RestClientResponseException error) {
                if (error.getStatusCode().value() != 429) throw failed(error);
                backoff(attempt);
                if (attempt >= properties.maxRetries()) throw failed(new KisRateLimitException("KIS HTTP 429"));
            } catch (RuntimeException error) {
                throw failed(error);
            }
            retries.incrementAndGet();
        }
    }

    /**
     * 다음 요청을 보내도 되는 시각까지 기다린 뒤, 그다음 요청 가능 시각을 예약해요.
     * synchronized라서 여러 스레드가 동시에 와도 한 명씩 차례로 통과해요.
     * 받는 것: 현재가 호출 여부. 돌려주는 것: 없음, 기다리는 중 인터럽트되면 서비스 불가 오류를 던져요.
     */
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
        // 결과: 실제 보낸 시각 기준으로 다음 허용 시각을 미뤄 둬요.
        now = System.nanoTime();
        nextRequest = now + properties.minimumInterval().toNanos();
        if (quote) nextQuote = now + properties.quoteInterval().toNanos();
    }

    /**
     * 한도 초과를 기록하고, 모든 요청을 잠시 막을 시각을 정해요.
     * 쉬는 시간은 기본값 × 2^attempt로 시도할수록 두 배씩 늘어나요.
     * 받는 것: 몇 번째 시도인지(0부터). 돌려주는 것: 없음.
     */
    private synchronized void backoff(int attempt) {
        throttled.incrementAndGet();
        blockedUntil = Math.max(blockedUntil, System.nanoTime()
                + properties.rateLimitBackoff().multipliedBy(1L << attempt).toNanos());
    }

    /**
     * 진단용 누적 수치를 돌려줘요.
     * 받는 것: 없음. 돌려주는 것: requests(총 요청), rateLimitRetries(재시도), rateLimitErrors(한도 초과),
     * failure.<오류 코드>(재시도 후에도 실패해 밖으로 나간 요청 수, 예: failure.EGW00201, failure.HTTP 500) 맵.
     */
    public java.util.Map<String, Long> diagnostics() {
        var values = new java.util.LinkedHashMap<String, Long>();
        values.put("requests", requests.get());
        values.put("rateLimitRetries", retries.get());
        values.put("rateLimitErrors", throttled.get());
        // 최종 실패 수를 "failure.EGW00201", "failure.HTTP 500" 같은 이름으로 덧붙여요.
        new java.util.TreeMap<>(failures).forEach((key, count) -> values.put("failure." + key, count.get()));
        return values;
    }

    /**
     * 하는 일: 밖으로 나가는 최종 실패 하나를 오류 코드별로 세고, 상태·코드·짧은 설명을 경고 로그로 남겨요.
     * 응답 본문의 msg_cd·msg1만 남기고 요청 헤더(토큰·앱키)나 주소는 남기지 않아요. 받는 것: 예외. 돌려주는 것: 같은 예외(다시 던지려고).
     */
    private RuntimeException failed(RuntimeException error) {
        KisErrorDetail detail = KisErrorDetail.of(error);
        failures.computeIfAbsent(detail.key(), ignored -> new AtomicLong()).incrementAndGet();
        log.warn("KIS request failed {} type={}", detail.describe(), error.getClass().getSimpleName());
        return error;
    }
}
