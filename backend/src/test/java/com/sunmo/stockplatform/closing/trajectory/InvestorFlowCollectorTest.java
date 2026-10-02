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

class InvestorFlowCollectorTest {
    @Test void boundedRequestsShareLimiterAndFailureDoesNotBlockProgramOrRunOnClosedDays() {
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
        server.expect(requestTo(org.hamcrest.Matchers.containsString("foreign-institution-total")))
                .andExpect(header("tr_id","FHPTJ04400000"))
                .andRespond(withSuccess("{\"rt_cd\":\"0\",\"output\":[{\"mksc_shrn_iscd\":\"005930\",\"frgn_ntby_qty\":\"-10\",\"orgn_ntby_qty\":\"20\"}]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("foreign-institution-total")))
                .andRespond(withServerError());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("program-trade-by-stock")))
                .andExpect(header("tr_id","FHPPG04650101"))
                .andExpect(queryParam("FID_COND_MRKT_DIV_CODE","J"))
                .andRespond(withSuccess("{\"rt_cd\":\"0\",\"output\":[{\"bsop_hour\":\"145800\",\"whol_smtn_ntby_qty\":\"-30\"}]}",MediaType.APPLICATION_JSON));
        collector.collect(); server.verify();
        verify(requests,times(3)).execute(eq(false),any());
        verify(service,times(3)).context(any());
        org.assertj.core.api.Assertions.assertThat(collector.status()).containsEntry("failures",1L);
        when(calendar.isTradingDay(any())).thenReturn(false);
        collector.collect(); verifyNoMoreInteractions(requests);
    }
}
