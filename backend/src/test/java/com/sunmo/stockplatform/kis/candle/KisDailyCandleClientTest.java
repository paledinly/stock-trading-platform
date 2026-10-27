/*
 * KisDailyCandleClient가 KIS 일봉 조회 API로 원본(수정주가 미반영)·수정주가 일봉을 받아오는지 확인해요.
 * 요청 범위를 벗어난 날짜나 가격 없는 행이 섞이면 백테스트 기준 데이터가 오염되므로 중요해요.
 */
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

/** KIS 일봉 조회 클라이언트를 점검하는 테스트예요. */
class KisDailyCandleClientTest {
    /**
     * 원본 일봉을 받고 요청 범위 밖 날짜는 빼는지 확인해요.
     * 준비: MockRestServiceServer(가짜 외부 서버)가 범위 밖 9/17 행과 범위 안 9/16 행을 돌려주게 해요. 토큰 관리자는 목(mock, 가짜 객체)이에요.
     * 기대: 9/16 한 건만 남고 거래대금 5050이며, 요청은 FID_ORG_ADJ_PRC=1(원본)로 나가요.
     */
    @Test
    void fetchesRawDailyCandlesAndExcludesDatesOutsideRequest() {
        // 준비
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

        // 실행
        var result = client.fetch("005930", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 16));

        // 확인
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(result.getFirst().tradingValue()).isEqualByComparingTo("5050");
        server.verify();
    }

    /**
     * 수정주가 요청은 FID_ORG_ADJ_PRC=0으로 보내고, 가격이 없는(시가 0) 행은 건너뛰는지 확인해요.
     * 준비: 정상 9/16 행과 시가·고가·저가가 0인 9/15 행을 돌려주게 해요.
     * 기대: 9/16 한 건만 남아요.
     */
    @Test
    void adjustedRequestUsesAdjustedPricesAndSkipsRowsWithoutAPrice() {
        // 준비
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
                + "&FID_INPUT_DATE_2=20260916&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=0"))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","output2":[
                          {"stck_bsop_date":"20260916","stck_oprc":"100","stck_hgpr":"103","stck_lwpr":"99","stck_clpr":"102","acml_vol":"50","acml_tr_pbmn":"5050"},
                          {"stck_bsop_date":"20260915","stck_oprc":"0","stck_hgpr":"0","stck_lwpr":"0","stck_clpr":"98","acml_vol":"0","acml_tr_pbmn":"0"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var client = new KisDailyCandleClient(builder.build(), properties, tokens,
                new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0)));

        // 실행
        var result = client.fetchAdjusted("005930", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 16));

        // 확인
        assertThat(result).extracting(DailyCandle::date).containsExactly(LocalDate.of(2026, 9, 16));
        server.verify();
    }

    /**
     * 원주가 요청에서 가격이 0인 행(거래정지일)이나 거래량이 빈 행이 있어도 종목 전체가 실패하지 않는지 확인해요.
     * 준비: 정상 9/16 행, 가격이 0인 9/15 행, 거래량이 빈 9/14 행을 돌려주게 해요.
     * 기대: 9/16과 9/14 두 건이 남고, 9/14의 거래량은 0이에요.
     */
    @Test
    void rawRequestSkipsZeroPriceRowsInsteadOfFailing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("token");
        KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key", "secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("FID_ORG_ADJ_PRC=1")))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","output2":[
                          {"stck_bsop_date":"20260916","stck_oprc":"100","stck_hgpr":"103","stck_lwpr":"99","stck_clpr":"102","acml_vol":"50","acml_tr_pbmn":"5050"},
                          {"stck_bsop_date":"20260915","stck_oprc":"0","stck_hgpr":"0","stck_lwpr":"0","stck_clpr":"98","acml_vol":"0","acml_tr_pbmn":"0"},
                          {"stck_bsop_date":"20260914","stck_oprc":"97","stck_hgpr":"99","stck_lwpr":"96","stck_clpr":"98","acml_vol":"","acml_tr_pbmn":""}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var client = new KisDailyCandleClient(builder.build(), properties, tokens,
                new KisRequestExecutor(new KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 0)));

        var result = client.fetch("005930", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 16));

        assertThat(result).extracting(DailyCandle::date)
                .containsExactly(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 14));
        assertThat(result.get(1).volume()).isZero();
        server.verify();
    }
}
