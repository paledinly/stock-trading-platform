package com.sunmo.stockplatform.kis.quote;

import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KisQuoteClientTest {
    @Test
    void sendsOfficialEndpointTrIdAndMapsResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = properties();
        var tokenManager = mock(KisTokenManager.class);
        when(tokenManager.getAccessToken()).thenReturn("access-token");
        var client = new KisQuoteClient(builder.build(), properties, tokenManager, new KisQuoteMapper());
        var stock = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, MarketType.STOCK,
                false, false, Instant.now());

        server.expect(requestTo("https://example.test/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("authorization", "Bearer access-token"))
                .andExpect(header("appkey", "app-key"))
                .andExpect(header("appsecret", "app-secret"))
                .andExpect(header("tr_id", "FHKST01010100"))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                         "output":{"stck_prpr":"70000","prdy_vrss_sign":"2","prdy_vrss":"1200","prdy_ctrt":"1.74",
                         "stck_oprc":"69000","stck_hgpr":"70500","stck_lwpr":"68800",
                         "acml_vol":"123456","acml_tr_pbmn":"8641975200"}}
                        """, MediaType.APPLICATION_JSON));

        var quote = client.getQuote(stock);

        assertThat(quote.stockCode()).isEqualTo("005930");
        assertThat(quote.currentPrice()).isEqualByComparingTo("70000");
        server.verify();
    }

    private KisProperties properties() {
        return new KisProperties(true, URI.create("https://example.test"), "app-key", "app-secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
    }
}
