/*
 * KIS가 "만료된 토큰"(EGW00123)으로 거절하면 토큰 캐시를 비우고 한 번만 다시 보내는지 확인해요.
 */
package com.sunmo.stockplatform.kis.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 만료 토큰 재시도 테스트예요. */
class KisRequestExecutorTokenTest {
    private static HttpServerErrorException expired() {
        byte[] body = "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"expired token\"}".getBytes(StandardCharsets.UTF_8);
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                new HttpHeaders(), body, StandardCharsets.UTF_8);
    }

    private KisRequestExecutor executor() {
        return new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0));
    }

    /** 기대: 첫 요청이 만료 토큰으로 거절되면 캐시를 비우고 다시 보내 성공해요. */
    @Test
    void retriesOnceAfterExpiredToken() {
        var executor = executor();
        var cleared = new AtomicInteger();
        executor.setExpiredTokenHandler(() -> { cleared.incrementAndGet(); return true; });
        var attempts = new AtomicInteger();

        String result = executor.execute(false, () -> {
            if (attempts.incrementAndGet() == 1) throw expired();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(cleared).hasValue(1);
    }

    /** 기대: 다시 보내도 만료 토큰이면 더 반복하지 않고 실패를 돌려줘요. */
    @Test
    void doesNotLoopWhenTheNewTokenIsAlsoRejected() {
        var executor = executor();
        executor.setExpiredTokenHandler(() -> true);
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(false, () -> { attempts.incrementAndGet(); throw expired(); }))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(attempts).hasValue(2);
    }
}
