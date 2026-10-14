/*
 * KisMinuteCandleClient가 KIS 당일 분봉 조회 API를 올바른 주소·헤더로 부르고 응답을 분봉으로 바꾸는지 확인해요.
 * 요청 파라미터나 거래ID(tr_id)가 틀리면 실제 운영에서 분봉 보충이 실패하므로 중요해요.
 */
package com.sunmo.stockplatform.kis.candle;

import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** KIS 당일 분봉 클라이언트의 요청·응답 매핑을 점검하는 테스트예요. */
class KisMinuteCandleClientTest {
    /**
     * 공식 당일 분봉 엔드포인트를 호출하고 응답 행을 매핑하는지 확인해요.
     * 준비: MockRestServiceServer(가짜 외부 서버)에 기대 요청과 1행짜리 응답을 등록하고, 토큰 관리자는 목(mock, 가짜 객체)으로 둬요.
     * 기대: 결과 1건, 종가 70050이고 기대한 요청이 실제로 호출돼요.
     */
    @Test
    void requestsOfficialTodayMinuteEndpointAndMapsRows() {
        // 준비
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("token");
        KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key", "secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
        server.expect(requestTo(
                "https://example.test/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930&FID_INPUT_HOUR_1=093000&FID_PW_DATA_INCU_YN=Y&FID_ETC_CLS_CODE="))
                .andExpect(method(HttpMethod.GET)).andExpect(header("tr_id", "FHKST03010200"))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","output2":[{"stck_bsop_date":"20260901","stck_cntg_hour":"090000",
                        "stck_oprc":"70000","stck_hgpr":"70100","stck_lwpr":"69900","stck_prpr":"70050",
                        "cntg_vol":"123","acml_tr_pbmn":"8616150"}]}
                        """, MediaType.APPLICATION_JSON));
        // 실행
        var result = new KisMinuteCandleClient(builder.build(), properties, tokens,
                new com.sunmo.stockplatform.kis.config.KisRequestExecutor(
                    new com.sunmo.stockplatform.kis.config.KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1))).fetch("005930",
                LocalTime.of(9, 30));
        // 확인
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().close()).isEqualByComparingTo("70050");
        server.verify();
    }

    /**
     * 영업일자(stck_bsop_date)가 없거나 빈 행은 서버 날짜로 추정하지 않고 버리는지 확인해요.
     * 준비: 날짜 있는 행 1개, 날짜 필드가 없는 행 1개, 날짜가 빈 문자열인 행 1개를 돌려주게 해요.
     * 기대: 날짜 있는 행 1건만 그 날짜(2026-09-01 09:01 KST)로 남고, 버린 행 2개가 세어져요.
     */
    @Test
    void skipsRowsWithoutBusinessDate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens = mock(KisTokenManager.class);
        when(tokens.getAccessToken()).thenReturn("token");
        KisProperties properties = new KisProperties(true, URI.create("https://example.test"), "key", "secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
                new KisProperties.Master(false, "0 0 0 * * *", URI.create("https://example.test/kospi"),
                        URI.create("https://example.test/kosdaq")));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                "https://example.test/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice?")))
                .andRespond(withSuccess("""
                        {"rt_cd":"0","output2":[
                        {"stck_bsop_date":"20260901","stck_cntg_hour":"090100","stck_oprc":"70000","stck_hgpr":"70100",
                         "stck_lwpr":"69900","stck_prpr":"70050","cntg_vol":"123","acml_tr_pbmn":"8616150"},
                        {"stck_cntg_hour":"090000","stck_oprc":"70000","stck_hgpr":"70100",
                         "stck_lwpr":"69900","stck_prpr":"70050","cntg_vol":"1","acml_tr_pbmn":"70050"},
                        {"stck_bsop_date":"","stck_cntg_hour":"085900","stck_oprc":"70000","stck_hgpr":"70100",
                         "stck_lwpr":"69900","stck_prpr":"70050","cntg_vol":"1","acml_tr_pbmn":"70050"}]}
                        """, MediaType.APPLICATION_JSON));
        var client = new KisMinuteCandleClient(builder.build(), properties, tokens,
                new com.sunmo.stockplatform.kis.config.KisRequestExecutor(
                    new com.sunmo.stockplatform.kis.config.KisRequestProperties(Duration.ZERO, Duration.ZERO, Duration.ZERO, 1)));

        var result = client.fetch("005930", LocalTime.of(9, 30));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().startTime()).isEqualTo(Instant.parse("2026-09-01T00:01:00Z"));
        assertThat(client.rowsWithoutDate()).isEqualTo(2);
        server.verify();
    }
}
