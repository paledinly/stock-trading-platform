/*
 * InvestorFlowCollector 가 KIS 외국인·기관 추정 순매수와 종목별 프로그램 매매 API 를 호출하는 흐름을 확인해요.
 * 한 요청이 실패해도 다른 수집이 계속되고, 휴장일엔 호출하지 않으며, 호출 한도를 공용 실행기로 공유하는지가 중요해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 수급 수집기가 실패를 격리하고 정해진 호출만 하는지 확인하는 테스트예요. */
class InvestorFlowCollectorTest {
    /**
     * 무엇: 추정 순매수 요청 2건 중 1건이 실패해도 프로그램 매매 요청은 진행되고, 휴장일엔 호출이 없는지 확인해요.
     * 입력: 14:59(한국시간) 시각, 목(mock, 가짜 객체) 의존성, 가짜 HTTP 서버의 성공·서버오류·성공 응답.
     * 기대: 실행기 호출 3회, 맥락 저장 3건(외국인·기관·프로그램), 실패 1건 집계, 휴장일엔 추가 호출 없음.
     */
    @Test void boundedRequestsShareLimiterAndFailureDoesNotBlockProgramOrRunOnClosedDays() {
        // 준비: 외부 의존성을 목으로 바꾸고, 실행기는 요청을 그대로 실행하게 해요.
        var builder = RestClient.builder().baseUrl("https://openapi.koreainvestment.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var trajectory = mock(TrajectoryProperties.class);
        when(trajectory.enabled()).thenReturn(true);
        when(trajectory.analysisEnd()).thenReturn(LocalTime.of(15,20));
        when(trajectory.contextMaxAge()).thenReturn(Duration.ofMinutes(6));
        var kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        when(kis.baseUrl()).thenReturn(URI.create("https://openapi.koreainvestment.com"));
        when(kis.appKey()).thenReturn("test"); when(kis.appSecret()).thenReturn("test");
        var tokens = mock(KisTokenManager.class); when(tokens.getAccessToken()).thenReturn("test-token");
        var requests = mock(KisRequestExecutor.class);
        when(requests.execute(eq(false), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        var calendar = mock(ClosingTradingCalendar.class);
        Instant now = Instant.parse("2026-10-02T05:59:00Z");
        when(calendar.now()).thenReturn(now); when(calendar.isTradingDay(any())).thenReturn(true);
        var store = mock(TrajectoryStore.class);
        when(store.recentSymbols(any(), any(), eq(20))).thenReturn(List.of("005930"));
        var service = mock(TrajectoryService.class);
        var collector = new InvestorFlowCollector(new InvestorFlowProperties(true,20,Duration.ofMinutes(5)),
                trajectory,store,service,builder.build(),kis,tokens,requests,calendar);
        // 준비: 추정 순매수 1차 성공, 2차 서버 오류(KIS가 한도 초과를 HTTP 500 본문에 담는 경우), 프로그램 매매 성공 순서로 응답해요.
        server.expect(requestTo(org.hamcrest.Matchers.containsString("foreign-institution-total")))
                .andExpect(header("tr_id","FHPTJ04400000"))
                .andRespond(withSuccess("{\"rt_cd\":\"0\",\"output\":[{\"mksc_shrn_iscd\":\"005930\",\"frgn_ntby_qty\":\"-10\",\"orgn_ntby_qty\":\"20\"}]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("foreign-institution-total")))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("program-trade-by-stock")))
                .andExpect(header("tr_id","FHPPG04650101"))
                .andExpect(queryParam("FID_COND_MRKT_DIV_CODE","J"))
                .andRespond(withSuccess("{\"rt_cd\":\"0\",\"output\":[{\"bsop_hour\":\"145800\",\"whol_smtn_ntby_qty\":\"-30\"}]}",MediaType.APPLICATION_JSON));
        // 실행/확인
        collector.collect(); server.verify();
        verify(requests,times(3)).execute(eq(false),any());
        verify(service,times(3)).context(any());
        org.assertj.core.api.Assertions.assertThat(collector.status()).containsEntry("failures",1L)
                .containsEntry("failureCodes", java.util.Map.of("ESTIMATE EGW00201", 1L));
        // 휴장일로 바꾸면 더 이상 호출하지 않아야 해요.
        when(calendar.isTradingDay(any())).thenReturn(false);
        collector.collect(); verifyNoMoreInteractions(requests);
    }
}
