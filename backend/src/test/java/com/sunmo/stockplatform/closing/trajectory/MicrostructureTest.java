/*
 * 미시구조(Microstructure, 10단계 호가와 대량 체결을 1분 단위로 요약한 데이터) 수집·집계·저장·Feature 보강을 검사하는 테스트예요.
 * 저장 실패 시 재시도, 결측 호가 거부, 중복·늦은 체결 거부, 늦게 확정된 행의 평가 제외를 확인해요.
 * 이 데이터는 관찰용이라 추천 점수를 바꾸면 안 되고, 평가 시각 이후 정보가 섞이면 안 되기 때문에 중요해요.
 */
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

/**
 * 호가·체결 1분 요약이 정확히 한 번, 제때 받은 데이터로만 만들어지고 저장되는지 확인하는 테스트 모음이에요.
 */
class MicrostructureTest {
    /**
     * 첫 저장이 실패해도 집계 결과를 버리지 않고 다음 flush에서 다시 저장하는지 확인해요.
     * 준비: H2(테스트용 메모리 DB)에 V29 마이그레이션을 적용하고, 저장소를 스파이(spy, 진짜 객체를 감싸 일부만 바꾼 객체)로 감싸
     * 첫 save만 예외를 던지게 해요. 달력 목(mock, 가짜 객체)은 START+62초를 현재 시각으로 돌려줘요.
     * 기대: 첫 flush 뒤 상태에 MICROSTRUCTURE_PERSIST_FAILED가 남고, 두 번째 flush 뒤 오류가 사라지며 1분 행 1개가 저장돼요.
     */
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
    /** 5초 표본, 대량 체결 기준 1000 등 테스트용 미시구조 설정을 만들어요. */
    static MicrostructureProperties policy(){return new MicrostructureProperties(true,true,Duration.ofSeconds(5),2,List.of(),new BigDecimal("1000"),Duration.ofSeconds(15),Duration.ofSeconds(2));}
    /** START+seconds 시각의 10단계 호가를 만들어요. 매수 100~91, 매도 101~110에 단계별 잔량 buy/sell이 있어요. */
    static Book book(int seconds,int buy,int sell){
        return new Book("005930",START.plusSeconds(seconds),START.plusSeconds(seconds),
                java.util.stream.IntStream.range(0,10).mapToObj(i->new Level(BigDecimal.valueOf(100-i),BigDecimal.valueOf(buy))).toList(),
                java.util.stream.IntStream.range(0,10).mapToObj(i->new Level(BigDecimal.valueOf(101+i),BigDecimal.valueOf(sell))).toList());
    }
    /** START+seconds에 발생·수신한 가격 100 체결 틱을 만들어요. total은 누적 거래량, buy/sell은 누적 매수·매도 체결량이에요. */
    static ObservedMarketTick tick(int seconds,long total,long buy,long sell){
        Instant source=START.plusSeconds(seconds);
        var tick=new MarketTick("005930",source.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),source,BigDecimal.valueOf(100),10,total,
                BigDecimal.valueOf(total*100),total,null,null,null,null,sell,buy,null,false,null,null);
        return new ObservedMarketTick(tick,null,source);
    }
    /**
     * 10단계 호가와 인접 1분 간 변화를 표본으로 만들되, 기존 궤적 점수(subScores)는 바꾸지 않는지 확인해요.
     * 준비: 2분 동안 5초마다 호가를 넣고, 둘째 분에 매수 잔량을 20에서 30으로 늘려요.
     * 기대: 완결 1분 행 2개, 첫 행 상위 5단계 매수 잔량 100과 스프레드 1%, 1분 매수 잔량 변화 +100과 매수/매도 비율 변화 +1,
     * 점수는 그대로예요. 평가 이후 수신 행이 섞이면 UNAVAILABLE, 직전 분이 없으면 변화값 null,
     * 이미 내보낸 분에 늦게 들어온 호가는 거부(rejected 1)돼요.
     */
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
    /**
     * 매도 잔량이 0이면 매수/매도 비율을 알 수 없음(null)으로 두고, 같은 원천 호가를 두 번 넣어도 표본이 늘지 않으며,
     * 호가 단계가 빠진 응답은 파싱에서 거부하는지 확인해요.
     * 입력: 14:30:00 접수, 매도 잔량이 모두 0인 KIS 호가 JSON을 두 번 넣고, 이후 askp10을 지운 JSON을 파싱해요.
     * 기대: 비율 null, 표본 1개라 미완결, 결측 JSON은 IllegalArgumentException이에요.
     */
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
    /**
     * 대량 체결을 매수·매도·방향 불명으로 나눠 세고, 중복 틱과 15:20 단일가 경계 틱을 거부하는지 확인해요.
     * 입력: 기준 틱(START-1초) 뒤 누적 체결이 늘어나는 틱 3개, 같은 틱 한 번 더, 이후 START+30초와 START+3000초(15:20) 틱.
     * 기대: 첫 분에 대량 매수 1, 매도 1, 방향 불명 1, 대량 매수 금액 1000이고 미완결이에요.
     * 거부 수는 3이고(START 이전 틱, 중복 틱, 15:20 틱으로 보여요), 충분히 지난 뒤 준비된 행은 2개예요.
     */
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
    /**
     * DB에 저장한 1분 행은 같은 키로 다시 저장해도 덮어쓰이지 않고, 평가 이후 확정된 행은 스냅샷에 들어가지 않는지 확인해요.
     * 준비: H2에 완결 행을 저장한 뒤 같은 키의 미완결·빈 행을 다시 저장하고, 확정시각만 START+63초로 늦춘 행을 만들어요.
     * 기대: 저장 행은 1개로 완결·bids 정보가 유지되고, 늦게 확정된 행으로 보강하면 UNAVAILABLE이에요.
     */
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
