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

class KisMinuteCandleClientTest {
    @Test void requestsOfficialTodayMinuteEndpointAndMapsRows(){
        RestClient.Builder builder=RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        KisTokenManager tokens=mock(KisTokenManager.class);when(tokens.getAccessToken()).thenReturn("token");
        KisProperties properties=new KisProperties(true,URI.create("https://example.test"),"key","secret",
                Duration.ofMinutes(5),Duration.ofSeconds(3),Duration.ofSeconds(5),
                new KisProperties.Master(false,"0 0 0 * * *",URI.create("https://example.test/kospi"),URI.create("https://example.test/kosdaq")));
        server.expect(requestTo("https://example.test/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930&FID_INPUT_HOUR_1=093000&FID_PW_DATA_INCU_YN=Y&FID_ETC_CLS_CODE="))
                .andExpect(method(HttpMethod.GET)).andExpect(header("tr_id","FHKST03010200"))
                .andRespond(withSuccess("""
                    {"rt_cd":"0","output2":[{"stck_bsop_date":"20260901","stck_cntg_hour":"090000",
                    "stck_oprc":"70000","stck_hgpr":"70100","stck_lwpr":"69900","stck_prpr":"70050",
                    "cntg_vol":"123","acml_tr_pbmn":"8616150"}]}
                    """, MediaType.APPLICATION_JSON));
        var result=new KisMinuteCandleClient(builder.build(),properties,tokens).fetch("005930",LocalTime.of(9,30));
        assertThat(result).hasSize(1);assertThat(result.getFirst().close()).isEqualByComparingTo("70050");
        server.verify();
    }
}
