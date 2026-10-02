package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OrderbookCollectorTest {
    @Test void sharedLimiterAndBoundedSymbolsAndTradingSessionGate() throws Exception {
        var builder=RestClient.builder().baseUrl("https://example.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var trajectory=mock(TrajectoryProperties.class);when(trajectory.enabled()).thenReturn(true);
        when(trajectory.contextMaxAge()).thenReturn(Duration.ofMinutes(6));
        var kis=mock(KisProperties.class);when(kis.enabled()).thenReturn(true);
        when(kis.appKey()).thenReturn("test");when(kis.appSecret()).thenReturn("test");
        var token=mock(KisTokenManager.class);when(token.getAccessToken()).thenReturn("test");
        var requests=mock(KisRequestExecutor.class);
        when(requests.execute(eq(false),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());
        var calendar=mock(ClosingTradingCalendar.class);when(calendar.now()).thenReturn(TrajectoryFeaturesTest.START);
        when(calendar.isTradingDay(any())).thenReturn(true);
        var store=mock(TrajectoryStore.class);when(store.recentSymbols(any(),any(),eq(2))).thenReturn(List.of("005930"));
        var service=mock(MicrostructureService.class);
        var collector=new OrderbookCollector(MicrostructureTest.policy(),trajectory,store,service,builder.build(),kis,token,requests,calendar);
        var mapper=new ObjectMapper();var body=mapper.createObjectNode();body.put("rt_cd","0");var output=body.putObject("output1");output.put("aspr_acpt_hour","143000");
        for(int i=1;i<=10;i++){output.put("bidp"+i,"100");output.put("askp"+i,"101");output.put("bidp_rsqn"+i,"20");output.put("askp_rsqn"+i,"10");}
        server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-asking-price-exp-ccn")))
                .andExpect(header("tr_id","FHKST01010200")).andExpect(queryParam("FID_COND_MRKT_DIV_CODE","J"))
                .andExpect(queryParam("FID_INPUT_ISCD","005930")).andRespond(withSuccess(body.toString(),MediaType.APPLICATION_JSON));
        collector.collect();server.verify();verify(requests).execute(eq(false),any());verify(service).book(any());
        when(calendar.now()).thenReturn(TrajectoryFeaturesTest.START.plusSeconds(3000));
        collector.collect();verifyNoMoreInteractions(requests);
        when(calendar.now()).thenReturn(TrajectoryFeaturesTest.START);when(calendar.isTradingDay(any())).thenReturn(false);
        collector.collect();verifyNoMoreInteractions(requests);
    }
}
