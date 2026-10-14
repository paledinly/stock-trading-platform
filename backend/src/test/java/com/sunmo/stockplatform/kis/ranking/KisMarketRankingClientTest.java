/*
 * KisMarketRankingClient가 KIS 순위 API(거래량·거래대금·등락률 순위)를 맞는 파라미터로 부르고 응답을 순위 항목으로 바꾸는지 확인해요.
 * 순위는 전시장 스캔 후보의 출발점이라, 파라미터나 값 매핑이 틀리면 후보 전체가 달라져요.
 */
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

/** KIS 순위 조회 클라이언트를 점검하는 테스트예요. */
class KisMarketRankingClientTest {
    /**
     * 거래대금 순위를 요청하고 응답 행을 매핑하는지 확인해요.
     * 준비: MockRestServiceServer(가짜 외부 서버)가 쉼표가 들어간 가격("70,000")의 행과 필드가 부족한 행을 돌려주게 하고, 요청 수는 1로 해요.
     * 기대: 1건만 남고 종목코드 005930, 현재가 70000(쉼표 제거), 거래대금이 그대로 들어가요.
     */
    @Test
    void requestsTurnoverRankingAndMapsRows() {
        // 준비: 토큰 관리자는 목(mock, 가짜 객체)으로 둬요.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("access-token");
        KisMarketRankingClient client = new KisMarketRankingClient(builder.build(), properties(), tokens,
                new com.sunmo.stockplatform.kis.config.KisRequestExecutor(
                    new com.sunmo.stockplatform.kis.config.KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1)));

        server.expect(requestTo(startsWith(
                        "https://example.test/uapi/domestic-stock/v1/quotations/volume-rank?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("FID_INPUT_ISCD", "0001"))
                .andExpect(queryParam("FID_RANK_SORT_CLS_CODE", "3"))
                .andExpect(queryParam("FID_BLNG_CLS_CODE", "3"))
                .andExpect(header("tr_id", "FHPST01710000"))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리",
                         "output":[
                           {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","stck_prpr":"70,000",
                            "prdy_ctrt":"1.25","acml_vol":"123456","acml_tr_pbmn":"8641975200"},
                           {"mksc_shrn_iscd":"000660","hts_kor_isnm":"SK하이닉스","stck_prpr":"180000"}
                         ]}
                        """, MediaType.APPLICATION_JSON));

        // 실행
        var result = client.fetch(RankingType.TURNOVER, Market.KOSPI, 1);

        // 확인
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().stockCode()).isEqualTo("005930");
        assertThat(result.getFirst().currentPrice()).isEqualByComparingTo("70000");
        assertThat(result.getFirst().accumulatedTradingValue()).isEqualByComparingTo("8641975200");
        server.verify();
    }

    /** 테스트용 KIS 설정을 만드는 도우미예요(가짜 키 값만 써요). */
    private KisProperties properties() {
        return new KisProperties(true, URI.create("https://example.test"), "app-key", "app-secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
    }

    /**
     * 등락률 순위 요청에 필수 필드를 보내고, 응답에 없는 값은 null로 두는지 확인해요.
     * 준비: 요청 수 50, 응답에는 거래량·거래대금이 없는 행 하나를 돌려주게 해요.
     * 기대: 거래량·거래대금은 null(0으로 채우지 않음), 고가는 71000이에요.
     */
    @Test
    void sendsRequiredFluctuationFieldsAndPreservesMissingValues() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("access-token");
        var client = new KisMarketRankingClient(builder.build(), properties(), tokens,
                new com.sunmo.stockplatform.kis.config.KisRequestExecutor(
                    new com.sunmo.stockplatform.kis.config.KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1)));
        server.expect(requestTo(startsWith("https://example.test/uapi/domestic-stock/v1/ranking/fluctuation?")))
                .andExpect(queryParam("FID_INPUT_CNT_1", "50"))
                .andExpect(queryParam("FID_PRC_CLS_CODE", "0"))
                .andExpect(queryParam("FID_RSFL_RATE1", ""))
                .andExpect(queryParam("FID_RSFL_RATE2", ""))
                .andExpect(header("tr_id", "FHPST01700000"))
                .andRespond(withSuccess("""
                    {"rt_cd":"0","output":[{"stck_shrn_iscd":"005930","stck_prpr":"70000","prdy_ctrt":"1.2","stck_hgpr":"71000"}]}
                    """, MediaType.APPLICATION_JSON));
        var result = client.fetch(RankingType.PRICE_RISE, null, 50);
        assertThat(result.getFirst().accumulatedVolume()).isNull();
        assertThat(result.getFirst().accumulatedTradingValue()).isNull();
        assertThat(result.getFirst().highPrice()).isEqualByComparingTo("71000");
        server.verify();
    }
}
