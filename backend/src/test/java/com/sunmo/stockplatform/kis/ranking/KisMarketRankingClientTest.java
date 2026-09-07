package com.sunmo.stockplatform.kis.ranking;

import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.stock.domain.Market;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KisMarketRankingClientTest {
    @Test
    void requestsTurnoverRankingAndMapsRows() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("access-token");
        KisMarketRankingClient client = new KisMarketRankingClient(builder.build(), properties(), tokens);

        server.expect(requestTo(startsWith(
                        "https://example.test/uapi/domestic-stock/v1/quotations/volume-rank?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("FID_INPUT_ISCD", "0001"))
                .andExpect(queryParam("FID_RANK_SORT_CLS_CODE", "3"))
                .andExpect(header("tr_id", "FHPST01710000"))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리",
                         "output":[
                           {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","stck_prpr":"70,000",
                            "prdy_ctrt":"1.25","acml_vol":"123456","acml_tr_pbmn":"8641975200"},
                           {"mksc_shrn_iscd":"000660","hts_kor_isnm":"SK하이닉스","stck_prpr":"180000"}
                         ]}
                        """, MediaType.APPLICATION_JSON));

        var result = client.fetch(RankingType.TURNOVER, Market.KOSPI, 1);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().stockCode()).isEqualTo("005930");
        assertThat(result.getFirst().currentPrice()).isEqualByComparingTo("70000");
        assertThat(result.getFirst().accumulatedTradingValue()).isEqualByComparingTo("8641975200");
        server.verify();
    }

    private KisProperties properties() {
        return new KisProperties(true, URI.create("https://example.test"), "app-key", "app-secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
    }
}
