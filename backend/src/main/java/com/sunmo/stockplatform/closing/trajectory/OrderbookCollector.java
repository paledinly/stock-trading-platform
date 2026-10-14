/*
 * 연구용으로 최근 관측 종목의 호가(매수·매도 대기 주문의 가격과 수량, 10단계)를 KIS REST API로 몇 초마다 받아 오는 수집기예요.
 * 받은 호가는 MicrostructureService에 넘겨 미세구조 1분 집계에 쓰여요. 기본 꺼짐이고, 실시간 호가 스트림이 아닌 제한된 샘플링이에요.
 * TrajectoryController의 /microstructure/status가 이 수집기의 상태를 보여 줘요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.*;

/**
 * 정해진 간격으로 종목들의 호가판을 사진 찍듯 받아 오는 수집기예요.
 * 시장 진열대를 몇 초마다 한 번씩 둘러보며 "지금 사려는 사람·팔려는 사람이 얼마나 줄 서 있는지" 적어 오는 조사원과 같아요.
 * 응답 호가가 최신이라는 보장은 없어서 원천 접수시각(aspr_acpt_hour)과 수신시각을 따로 기록해요.
 */
@Component
public class OrderbookCollector {
    private final MicrostructureProperties policy;
    private final TrajectoryProperties trajectory;
    private final TrajectoryStore store;
    private final MicrostructureService service;
    private final RestClient client;
    private final KisProperties kis;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;
    private final ClosingTradingCalendar calendar;
    private final AtomicLong failures=new AtomicLong();
    private volatile Instant lastSuccess;
    /** 하는 일: 설정·저장소·서비스·KIS 접속 도구·달력을 받아 보관해요. 받는 것: 각 의존 객체. 돌려주는 것: 수집기 객체예요. */
    public OrderbookCollector(MicrostructureProperties policy,TrajectoryProperties trajectory,TrajectoryStore store,
            MicrostructureService service,RestClient kisRestClient,KisProperties kis,KisTokenManager tokens,
            KisRequestExecutor requests,ClosingTradingCalendar calendar) {
        this.policy=policy;this.trajectory=trajectory;this.store=store;this.service=service;client=kisRestClient;
        this.kis=kis;this.tokens=tokens;this.requests=requests;this.calendar=calendar;
    }
    /**
     * 하는 일: (기본 5초마다) 대상 종목의 호가를 차례로 요청해 미세구조 서비스에 넘겨요.
     * 대상은 설정된 종목 목록, 없으면 최근 맥락 유효시간 안에 1분 집계가 있는 종목(최대 maxSymbols개)이에요.
     * 받는 것: 없어요. 돌려주는 것: 없어요. 실패는 개수만 세고 경고 로그를 남겨요(인증정보는 로그에 남기지 않음).
     */
    @Scheduled(fixedDelayString="${closing.trajectory.microstructure.poll-interval:5s}",scheduler="microstructureContextScheduler")
    public void collect() {
        if(!active())return;
        Instant now=calendar.now();
        List<String> symbols=policy.symbols().isEmpty()?store.recentSymbols(now.minus(trajectory.contextMaxAge()),now,policy.maxSymbols()):policy.symbols();
        for(String symbol:new LinkedHashSet<>(symbols)) {
            if(!active())return;
            try {
                kis.requireCredentials();
                Book book=requests.execute(false,()->{
                    JsonNode response=client.get().uri(b->b.path("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn")
                                    .queryParam("FID_COND_MRKT_DIV_CODE","J").queryParam("FID_INPUT_ISCD",symbol).build())
                            .header("authorization","Bearer "+tokens.getAccessToken()).header("appkey",kis.appKey())
                            .header("appsecret",kis.appSecret()).header("tr_id","FHKST01010200").header("custtype","P")
                            .retrieve().body(JsonNode.class);
                    if(response==null||!"0".equals(response.path("rt_cd").asText()))
                        KisResponseErrors.failure("KIS orderbook failed",response==null?"EMPTY":response.path("msg_cd").asText(),"Orderbook unavailable");
                    return parse(symbol,response.path("output1"),calendar.now());
                });
                service.book(book);lastSuccess=book.receivedAt();
            }catch(RuntimeException failure){
                failures.incrementAndGet();
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Orderbook request failed symbol={} type={}",symbol,failure.getClass().getSimpleName());
            }
        }
    }
    /** 하는 일: 미세구조·호가·Trajectory·KIS가 모두 켜져 있고 거래일 09:00~15:20 미만인지 확인해요. 돌려주는 것: 수집해도 되면 true예요. */
    private boolean active() {
        var now=calendar.now().atZone(ClosingTradingCalendar.ZONE);
        return policy.enabled()&&policy.orderbookEnabled()&&trajectory.enabled()&&kis.enabled()
                && calendar.isTradingDay(now.toLocalDate())&&!now.toLocalTime().isBefore(LocalTime.of(9,0))
                && now.toLocalTime().isBefore(LocalTime.of(15,20));
    }
    /** 하는 일: 실패 횟수, 마지막 성공 응답 시각과 "제한된 REST 샘플링, 최신 호가 보장 없음" 표시를 돌려줘요. */
    public Map<String,Object> status(){return TrajectoryModel.fields("requestFailures",failures.get(),"lastResponseAt",lastSuccess,
            "sampling","BOUNDED_REST","responseDoesNotGuaranteeFreshBook",true);}
    /**
     * 하는 일: KIS 호가 응답(output1)에서 원천 접수시각과 매수·매도 10단계 가격·잔량을 꺼내 Book으로 만들어요.
     * 원천시각은 수신 날짜+HHmmss로 만들고, 수신시각보다 미래면 거부해요(원천시각과 수신시각을 섞지 않음).
     * 받는 것: 종목, 응답 노드, 수신시각이에요. 돌려주는 것: Book이에요. 값이 빠지면 예외를 던져요.
     */
    public static Book parse(String symbol,JsonNode output,Instant received) {
        String time=output.path("aspr_acpt_hour").asText();
        if(!time.matches("[0-9]{6}"))throw new IllegalArgumentException("Missing orderbook source time");
        LocalTime local=LocalTime.of(Integer.parseInt(time.substring(0,2)),Integer.parseInt(time.substring(2,4)),Integer.parseInt(time.substring(4,6)));
        Instant source=received.atZone(ClosingTradingCalendar.ZONE).toLocalDate().atTime(local).atZone(ClosingTradingCalendar.ZONE).toInstant();
        if(source.isAfter(received))throw new IllegalArgumentException("Future orderbook source time");
        List<Level> bids=new ArrayList<>(),asks=new ArrayList<>();
        for(int i=1;i<=10;i++) {
            bids.add(new Level(number(output,"bidp"+i),number(output,"bidp_rsqn"+i)));
            asks.add(new Level(number(output,"askp"+i),number(output,"askp_rsqn"+i)));
        }
        return new Book(symbol,source,received,bids,asks);
    }
    /** 하는 일: 응답 필드 문자열을 쉼표를 빼고 숫자로 바꿔요. 받는 것: 노드, 필드 이름. 돌려주는 것: 숫자예요(비어 있으면 예외). */
    private static BigDecimal number(JsonNode row,String field){
        String text=row.path(field).asText("").trim().replace(",","");
        if(text.isEmpty())throw new IllegalArgumentException("Missing orderbook field: "+field);
        return new BigDecimal(text);
    }
}
