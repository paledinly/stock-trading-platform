/*
 * OrderbookCollector 가 KIS 호가 API 를 호출해 호가 스냅샷을 모으는 조건을 확인해요.
 * 호출 한도를 공용 실행기(KisRequestExecutor)로 공유하고, 종목 수를 제한하며, 장 시간·거래일이 아니면 호출하지 않는지가 중요해요.
 */
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

/** 호가 수집기가 정해진 시간·종목·호출 한도 안에서만 움직이는지 확인하는 테스트예요. */
class OrderbookCollectorTest {
    /**
     * 무엇: 호출이 공용 실행기를 거치고, 최근 종목 상위 2개로 제한되며, 장 시간 밖·휴장일엔 호출하지 않는지 확인해요.
     * 입력: 목(mock, 가짜 객체)으로 만든 설정·토큰·달력·저장소와, 가짜 HTTP 서버(MockRestServiceServer)의 10단계 호가 응답.
     * 기대: START 시각엔 호가 API 1회 호출 후 book() 전달, START+50분과 휴장일엔 추가 호출이 없어요.
     */
    @Test void sharedLimiterAndBoundedSymbolsAndTradingSessionGate() throws Exception {
        // 준비: 외부 의존성을 모두 목으로 바꾸고, 실행기는 넘겨받은 요청을 그대로 실행하게 해요.
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
        // 준비: 14:30:00 접수 시각의 매수/매도 10단계 호가 응답을 만들어요.
        var mapper=new ObjectMapper();var body=mapper.createObjectNode();body.put("rt_cd","0");var output=body.putObject("output1");output.put("aspr_acpt_hour","143000");
        for(int i=1;i<=10;i++){output.put("bidp"+i,"100");output.put("askp"+i,"101");output.put("bidp_rsqn"+i,"20");output.put("askp_rsqn"+i,"10");}
        server.expect(requestTo(org.hamcrest.Matchers.containsString("inquire-asking-price-exp-ccn")))
                .andExpect(header("tr_id","FHKST01010200")).andExpect(queryParam("FID_COND_MRKT_DIV_CODE","J"))
                .andExpect(queryParam("FID_INPUT_ISCD","005930")).andRespond(withSuccess(body.toString(),MediaType.APPLICATION_JSON));
        collector.collect();server.verify();verify(requests).execute(eq(false),any());verify(service).book(any());
        // 확인: 수집 구간 밖(50분 뒤)으로 보이는 시각, 그리고 휴장일에는 더 이상 호출하지 않아야 해요.
        when(calendar.now()).thenReturn(TrajectoryFeaturesTest.START.plusSeconds(3000));
        collector.collect();verifyNoMoreInteractions(requests);
        when(calendar.now()).thenReturn(TrajectoryFeaturesTest.START);when(calendar.isTradingDay(any())).thenReturn(false);
        collector.collect();verifyNoMoreInteractions(requests);
    }
}
