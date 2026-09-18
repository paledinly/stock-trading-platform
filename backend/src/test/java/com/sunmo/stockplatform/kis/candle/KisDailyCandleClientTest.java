package com.sunmo.stockplatform.kis.candle;

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

class KisDailyCandleClientTest {
    @Test
    void fetchesRawDailyCandlesAndExcludesDatesOutsideRequest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("token");
        KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key", "secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
        server.expect(requestTo("https://example.test/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                + "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930&FID_INPUT_DATE_1=20260601"
                + "&FID_INPUT_DATE_2=20260916&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("tr_id", "FHKST03010100"))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","output2":[
                          {"stck_bsop_date":"20260917","stck_oprc":"101","stck_hgpr":"102","stck_lwpr":"100","stck_clpr":"101","acml_vol":"10"},
                          {"stck_bsop_date":"20260916","stck_oprc":"100","stck_hgpr":"103","stck_lwpr":"99","stck_clpr":"102","acml_vol":"50","acml_tr_pbmn":"5050"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var client = new KisDailyCandleClient(builder.build(), properties, tokens,
                new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0)));

        var result = client.fetch("005930", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 16));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(result.getFirst().tradingValue()).isEqualByComparingTo("5050");
        server.verify();
    }
}
