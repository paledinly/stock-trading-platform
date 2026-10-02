package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryFeaturesTest.START;

class MicrostructureTest {
    @Test void persistenceFailureRetainsAggregateUntilCommit() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:microretry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V29__create_closing_microstructure_minute.sql")).execute(ds);
        var store=org.mockito.Mockito.spy(new MicrostructureStore(new JdbcTemplate(ds),new ObjectMapper().findAndRegisterModules()));
        org.mockito.Mockito.doThrow(new IllegalStateException("test failure")).doCallRealMethod().when(store).save(org.mockito.ArgumentMatchers.any());
        var calendar=org.mockito.Mockito.mock(com.sunmo.stockplatform.closing.application.ClosingTradingCalendar.class);
        org.mockito.Mockito.when(calendar.now()).thenReturn(START.plusSeconds(62));
        org.mockito.Mockito.when(calendar.isTradingDay(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var trajectory=org.mockito.Mockito.mock(TrajectoryProperties.class);org.mockito.Mockito.when(trajectory.enabled()).thenReturn(true);
        var service=new MicrostructureService(policy(),trajectory,store,calendar,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        for(int i=0;i<60;i+=5)service.book(book(i,20,10));
        service.flush();assertThat(service.status()).containsEntry("error","MICROSTRUCTURE_PERSIST_FAILED");
        service.flush();assertThat(service.status().get("error")).isNull();
        assertThat(store.minutes("005930",START,START.plusSeconds(60))).hasSize(1);
    }
    static MicrostructureProperties policy(){return new MicrostructureProperties(true,true,Duration.ofSeconds(5),2,List.of(),new BigDecimal("1000"),Duration.ofSeconds(15),Duration.ofSeconds(2));}
    static Book book(int seconds,int buy,int sell){
        return new Book("005930",START.plusSeconds(seconds),START.plusSeconds(seconds),
                java.util.stream.IntStream.range(0,10).mapToObj(i->new Level(BigDecimal.valueOf(100-i),BigDecimal.valueOf(buy))).toList(),
                java.util.stream.IntStream.range(0,10).mapToObj(i->new Level(BigDecimal.valueOf(101+i),BigDecimal.valueOf(sell))).toList());
    }
    static ObservedMarketTick tick(int seconds,long total,long buy,long sell){
        Instant source=START.plusSeconds(seconds);
        var tick=new MarketTick("005930",source.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),source,BigDecimal.valueOf(100),10,total,
                BigDecimal.valueOf(total*100),total,null,null,null,null,sell,buy,null,false,null,null);
        return new ObservedMarketTick(tick,null,source);
    }
    @Test void tenLevelsAndAdjacentMinuteChangesAreSampledAndScoresStayUnchanged(){
        var agg=new MicrostructureAggregator(policy());
        for(int i=0;i<120;i+=5)agg.book(book(i,i<60?20:30,10));
        var rows=agg.ready(START.plusSeconds(122));
        assertThat(rows).hasSize(2).allMatch(Minute::complete);
        assertThat(rows.getFirst().metrics()).containsEntry("bidDepth5",BigDecimal.valueOf(100));
        assertThat((BigDecimal)rows.getFirst().metrics().get("spreadPct")).isEqualByComparingTo("1");
        var base=TrajectoryFeatures.calculate("005930","KOSPI",START.plusSeconds(120),START.plusSeconds(122),
                List.of(TrajectoryFeaturesTest.row(1,"105","100","120")),List.of(),List.of(),TrajectoryFeaturesTest.policy());
        var result=MicrostructureFeatures.enrich(base,rows);
        assertThat((BigDecimal)result.orderbook().get("bidDepthChange1m")).isEqualByComparingTo("100");
        assertThat((BigDecimal)result.orderbook().get("bidAskRatioChange1m")).isEqualByComparingTo("1");
        assertThat(result.subScores()).isEqualTo(base.subScores());
        Minute latest=rows.getLast();
        Minute future=new Minute(latest.kind(),latest.symbol(),latest.start(),latest.occurredAt(),START.plusSeconds(123),latest.finalizedAt(),true,latest.metrics());
        assertThat(MicrostructureFeatures.enrich(base,List.of(rows.getFirst(),future)).orderbook()).containsEntry("status","UNAVAILABLE");
        assertThat(MicrostructureFeatures.enrich(base,List.of(latest)).orderbook().get("bidDepthChange1m")).isNull();
        agg.acknowledge(rows); assertThat(agg.ready(START.plusSeconds(123))).isEmpty();
        agg.book(book(119,99,10)); assertThat(agg.rejected()).isEqualTo(1);
    }
    @Test void missingDepthIsRejectedZeroAskRatioIsUnknownAndRepeatedSourceDoesNotAddSamples() throws Exception {
        var mapper=new ObjectMapper();var json=mapper.createObjectNode();json.put("aspr_acpt_hour","143000");
        for(int i=1;i<=10;i++){json.put("bidp"+i,"100");json.put("askp"+i,"101");json.put("bidp_rsqn"+i,"20");json.put("askp_rsqn"+i,"0");}
        Book parsed=OrderbookCollector.parse("005930",json,START);
        var agg=new MicrostructureAggregator(policy());agg.book(parsed);agg.book(parsed);
        var row=agg.ready(START.plusSeconds(62)).getFirst();
        assertThat(row.metrics().get("bidAskDepthRatio")).isNull();
        assertThat(row.metrics().get("sampleCount")).isEqualTo(1L);assertThat(row.complete()).isFalse();
        json.remove("askp10");assertThatThrownBy(()->OrderbookCollector.parse("005930",json,START)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void largeExecutionsKeepBuySellUnknownAndRejectDuplicateLateAuctionTicks(){
        var agg=new MicrostructureAggregator(policy());
        agg.tick(tick(-1,100,50,50));
        agg.tick(tick(1,110,60,50));agg.tick(tick(10,120,60,60));agg.tick(tick(20,150,90,60));
        agg.tick(tick(20,150,90,60));
        var rows=agg.ready(START.plusSeconds(62));
        var current=rows.stream().filter(r->r.start().equals(START)).findFirst().orElseThrow();
        assertThat(current.metrics()).containsEntry("largeBuyExecutionCount1m",1L).containsEntry("largeSellExecutionCount1m",1L)
                .containsEntry("largeUnknownExecutionCount1m",1L).containsEntry("largeBuyExecutionAmount1m",BigDecimal.valueOf(1000));
        assertThat(current.complete()).isFalse();
        agg.tick(tick(30,160,100,60));
        agg.tick(tick(3000,170,110,60)); // 15:20 auction boundary
        assertThat(agg.rejected()).isEqualTo(3);
        assertThat(agg.ready(START.plusSeconds(4000))).hasSize(2);
    }
    @Test void databaseRoundTripIsImmutableAndLateFinalizationCannotEnterSnapshot(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:microstructure;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V29__create_closing_microstructure_minute.sql")).execute(ds);
        var store=new MicrostructureStore(new JdbcTemplate(ds),new ObjectMapper().findAndRegisterModules());
        var agg=new MicrostructureAggregator(policy());for(int i=0;i<60;i+=5)agg.book(book(i,20,10));
        var row=agg.ready(START.plusSeconds(62)).getFirst();store.save(row);
        store.save(new Minute(row.kind(),row.symbol(),row.start(),row.occurredAt(),row.receivedAt(),row.finalizedAt(),false,Map.of()));
        var stored=store.minutes("005930",START,START.plusSeconds(60));assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().complete()).isTrue();assertThat(stored.getFirst().metrics()).containsKey("bids");
        var snapshot=TrajectoryFeatures.calculate("005930","KOSPI",START.plusSeconds(60),START.plusSeconds(62),
                List.of(TrajectoryFeaturesTest.row(0,"105","100","120")),List.of(),List.of(),TrajectoryFeaturesTest.policy());
        var late=new Minute(row.kind(),row.symbol(),row.start(),row.occurredAt(),row.receivedAt(),START.plusSeconds(63),true,row.metrics());
        assertThat(MicrostructureFeatures.enrich(snapshot,List.of(late)).orderbook()).containsEntry("status","UNAVAILABLE");
    }
}
