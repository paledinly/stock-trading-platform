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
    public OrderbookCollector(MicrostructureProperties policy,TrajectoryProperties trajectory,TrajectoryStore store,
            MicrostructureService service,RestClient kisRestClient,KisProperties kis,KisTokenManager tokens,
            KisRequestExecutor requests,ClosingTradingCalendar calendar) {
        this.policy=policy;this.trajectory=trajectory;this.store=store;this.service=service;client=kisRestClient;
        this.kis=kis;this.tokens=tokens;this.requests=requests;this.calendar=calendar;
    }
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
    private boolean active() {
        var now=calendar.now().atZone(ClosingTradingCalendar.ZONE);
        return policy.enabled()&&policy.orderbookEnabled()&&trajectory.enabled()&&kis.enabled()
                && calendar.isTradingDay(now.toLocalDate())&&!now.toLocalTime().isBefore(LocalTime.of(9,0))
                && now.toLocalTime().isBefore(LocalTime.of(15,20));
    }
    public Map<String,Object> status(){return TrajectoryModel.fields("requestFailures",failures.get(),"lastResponseAt",lastSuccess,
            "sampling","BOUNDED_REST","responseDoesNotGuaranteeFreshBook",true);}
    static Book parse(String symbol,JsonNode output,Instant received) {
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
    private static BigDecimal number(JsonNode row,String field){
        String text=row.path(field).asText("").trim().replace(",","");
        if(text.isEmpty())throw new IllegalArgumentException("Missing orderbook field: "+field);
        return new BigDecimal(text);
    }
}
