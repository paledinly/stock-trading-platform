/*
 * KisErrorDetail(KIS 실패 요약)과 KisRequestExecutor의 오류 코드별 실패 집계 테스트예요.
 * HTTP 500 본문의 msg_cd·msg1, KisResponseErrors가 만든 "[코드]" 메시지, 코드 없는 실패를 각각 구분하는지 확인해요.
 */
package com.sunmo.stockplatform.kis.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KisErrorDetailTest {
    static HttpServerErrorException serverError(String body) {
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void readsKisCodeAndMessageFromAnHttp500Body() {
        var detail = KisErrorDetail.of(serverError("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}"));

        assertThat(detail).isEqualTo(new KisErrorDetail(500, "EGW00201", "초당 거래건수를 초과하였습니다."));
        assertThat(detail.key()).isEqualTo("EGW00201");
        assertThat(detail.describe()).isEqualTo("status=500 msg_cd=EGW00201 msg=초당 거래건수를 초과하였습니다.");
    }

    @Test
    void fallsBackToStatusWhenTheBodyHasNoKisCode() {
        var detail = KisErrorDetail.of(new RuntimeException("wrapped", serverError("<html>gateway down</html>")));

        assertThat(detail.httpStatus()).isEqualTo(500);
        assertThat(detail.code()).isNull();
        assertThat(detail.key()).isEqualTo("HTTP 500");
    }

    @Test
    void readsTheBracketCodeFromKisResponseErrors() {
        assertThatThrownBy(() -> KisResponseErrors.failure("KIS flow failed", "EGW00123", "기간이 만료된 token 입니다."))
                .satisfies(error -> {
                    var detail = KisErrorDetail.of(error);
                    assertThat(detail.code()).isEqualTo("EGW00123");
                    assertThat(detail.message()).isEqualTo("기간이 만료된 token 입니다.");
                    assertThat(detail.httpStatus()).isNull();
                });
        assertThat(KisErrorDetail.of(new IllegalStateException()).key()).isEqualTo("OTHER");
    }

    /** HTTP 500 본문이 EGW00201(초당 한도 초과)이면 429처럼 한 번 쉬었다가 다시 시도해요. 만료 토큰(EGW00123)은 쉬지 않고 한 번만 다시 보낸 뒤 실패해요. */
    @Test
    void retriesHttp500RateLimitButNotOtherServerErrors() {
        var executor = new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1));
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        String result = executor.execute(false, () -> {
            if (calls.incrementAndGet() == 1)
                throw serverError("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(executor.diagnostics()).containsEntry("rateLimitRetries", 1L).containsEntry("rateLimitErrors", 1L);

        calls.set(0);
        assertThatThrownBy(() -> executor.execute(false, () -> {
            calls.incrementAndGet();
            throw serverError("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}");
        })).isInstanceOf(HttpServerErrorException.class);
        // 만료 토큰은 한도 초과처럼 쉬며 반복하지 않아요. 토큰을 비우고 딱 한 번만 다시 보내요(2026-10-30 변경).
        assertThat(calls).as("expired token is retried exactly once, without rate-limit backoff").hasValue(2);
        assertThat(executor.diagnostics()).containsEntry("rateLimitErrors", 1L);

        calls.set(0);
        assertThatThrownBy(() -> executor.execute(false, () -> {
            calls.incrementAndGet();
            throw serverError("{\"msg_cd\":\"EGW00201\"}");
        })).isInstanceOf(HttpServerErrorException.class);
        assertThat(calls).as("retries stop at maxRetries=1").hasValue(2);
        assertThat(executor.diagnostics()).containsEntry("failure.EGW00201", 1L);
    }

    @Test
    void countsFinalFailuresByCode() {
        var executor = new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1));

        // HTTP 500(본문 EGW00201)은 한 번 재시도한 뒤에도 실패하면 오류 코드별로 집계돼요.
        assertThatThrownBy(() -> executor.execute(false, () -> {
            throw serverError("{\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");
        })).isInstanceOf(HttpServerErrorException.class);
        assertThatThrownBy(() -> executor.execute(false, () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.execute(false, () -> "ok")).isEqualTo("ok");

        assertThat(executor.diagnostics())
                .containsEntry("requests", 4L)
                .containsEntry("rateLimitRetries", 1L)
                .containsEntry("failure.EGW00201", 1L)
                .containsEntry("failure.OTHER", 1L);
    }
}
