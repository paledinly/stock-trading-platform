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

    @Test
    void countsFinalFailuresByCodeWithoutChangingRetries() {
        var executor = new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1));

        // HTTP 500(본문 EGW00201)은 지금처럼 재시도하지 않고 바로 실패해요. 집계만 남아요.
        assertThatThrownBy(() -> executor.execute(false, () -> {
            throw serverError("{\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");
        })).isInstanceOf(HttpServerErrorException.class);
        assertThatThrownBy(() -> executor.execute(false, () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.execute(false, () -> "ok")).isEqualTo("ok");

        assertThat(executor.diagnostics())
                .containsEntry("requests", 3L)
                .containsEntry("rateLimitRetries", 0L)
                .containsEntry("failure.EGW00201", 1L)
                .containsEntry("failure.OTHER", 1L);
    }
}
