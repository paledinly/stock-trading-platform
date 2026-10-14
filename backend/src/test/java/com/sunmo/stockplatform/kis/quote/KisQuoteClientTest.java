/*
 * KisQuoteClient가 KIS 현재가 조회 API를 공식 주소·인증 헤더·거래ID(tr_id)로 호출하고 응답을 시세로 바꾸는지 확인해요.
 * 헤더가 하나라도 틀리면 운영에서 시세 조회가 실패하므로 중요해요.
 */
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

/** KIS 현재가 조회 클라이언트를 점검하는 테스트예요. */
class KisQuoteClientTest {
    /**
     * 공식 엔드포인트와 헤더로 요청하고 응답을 매핑하는지 확인해요.
     * 준비: MockRestServiceServer(가짜 외부 서버)에 기대 요청과 정상 응답을 등록하고, 토큰 관리자는 목(mock, 가짜 객체)으로 둬요.
     * 기대: 종목코드 005930, 현재가 70000이고 기대한 요청이 호출돼요.
     */
    @Test
    void sendsOfficialEndpointTrIdAndMapsResponse() {
        // 준비
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = properties();
        var tokenManager = mock(KisTokenManager.class);
        when(tokenManager.getAccessToken()).thenReturn("access-token");
        var client = new KisQuoteClient(builder.build(), properties, tokenManager, new KisQuoteMapper(),
                new com.sunmo.stockplatform.kis.config.KisRequestExecutor(
                    new com.sunmo.stockplatform.kis.config.KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1)));
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

        // 실행
        var quote = client.getQuote(stock);

        // 확인
        assertThat(quote.stockCode()).isEqualTo("005930");
        assertThat(quote.currentPrice()).isEqualByComparingTo("70000");
        server.verify();
    }

    /** 테스트용 KIS 설정을 만드는 도우미예요(가짜 키 값만 써요). */
    private KisProperties properties() {
        return new KisProperties(true, URI.create("https://example.test"), "app-key", "app-secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
    }
}
