/*
 * KisHolidayClient가 KIS 휴장일 조회 API로 날짜별 개장 여부를 읽는지 확인해요.
 * 다음 거래일 계산이 이 값에 기대므로, 비정상 행은 걸러야 해요.
 */
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

/** KIS 휴장일 조회 클라이언트를 점검하는 테스트예요. */
class KisHolidayClientTest {
    /**
     * 개장 여부(Y/N)를 읽고 쓸 수 없는 행은 건너뛰는지 확인해요.
     * 준비: MockRestServiceServer(가짜 외부 서버)가 Y, N, 빈 값, 잘못된 날짜 4행을 돌려주게 해요.
     * 기대: 10/2=개장, 10/5=휴장 두 건만 남고 기대한 요청이 호출돼요.
     */
    @Test
    void readsMarketOpenFlagsAndSkipsUnusableRows() {
        // 준비: 토큰 관리자는 목(mock, 가짜 객체)으로 둬요.
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

        // 실행
        var days = client.fetch(LocalDate.of(2026, 10, 2));

        // 확인
        assertThat(days).containsExactly(
                java.util.Map.entry(LocalDate.of(2026, 10, 2), true),
                java.util.Map.entry(LocalDate.of(2026, 10, 5), false));
        server.verify();
    }
}
