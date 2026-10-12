package com.sunmo.stockplatform.kis.calendar;

import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisRequestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KisHolidayClientTest {
    @Test
    void readsMarketOpenFlagsAndSkipsUnusableRows() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("token");
        KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key", "secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
        server.expect(requestTo("https://example.test/uapi/domestic-stock/v1/quotations/chk-holiday"
                        + "?BASS_DT=20261002&CTX_AREA_NK=&CTX_AREA_FK="))
                .andExpect(method(HttpMethod.GET)).andExpect(header("tr_id", "CTCA0903R"))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","output":[
                          {"bass_dt":"20261002","opnd_yn":"Y"},
                          {"bass_dt":"20261005","opnd_yn":"N"},
                          {"bass_dt":"20261006","opnd_yn":""},
                          {"bass_dt":"bad","opnd_yn":"Y"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var client = new KisHolidayClient(builder.build(), properties, tokens,
                new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0)));

        var days = client.fetch(LocalDate.of(2026, 10, 2));

        assertThat(days).containsExactly(
                java.util.Map.entry(LocalDate.of(2026, 10, 2), true),
                java.util.Map.entry(LocalDate.of(2026, 10, 5), false));
        server.verify();
    }
}
