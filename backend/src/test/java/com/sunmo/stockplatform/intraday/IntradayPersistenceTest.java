package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.stock.domain.*;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:intraday;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "market.realtime.enabled=false",
        "intraday.enabled=true", "intraday.flush-interval=1h", "watchlist.initial.enabled=false",
        "intraday.costs.buy-fee-percent=0.001", "intraday.costs.sell-fee-percent=0.001",
        "intraday.costs.sell-tax-percent=0.001", "intraday.costs.spread-percent=0.001",
        "intraday.costs.buy-slippage-percent=0.001", "intraday.costs.sell-slippage-percent=0.001"})
class IntradayPersistenceTest {
    @Autowired IntradayService service;
    @Autowired StockRepository stocks;
    @Autowired IntradayInputRepository inputs;
    @Autowired IntradayRecommendationRepository recommendations;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ClosingTradingCalendar calendar;

    @Test void committedSharedEventsReachScannerPersistFrozenSnapshotAndReplayWithoutMixingPaper() {
        LocalDate day = LocalDate.of(2026, 9, 29);
        when(calendar.today()).thenReturn(day);
        when(calendar.previousTradingDay(day)).thenReturn(day.minusDays(1));
        when(calendar.isTradingDay(day)).thenReturn(true);
        when(calendar.now()).thenReturn(IntradayEngineTest.START);
        if (stocks.findByStockCodeAndActiveTrue("005930").isEmpty())
            stocks.save(new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, MarketType.STOCK, false, false, IntradayEngineTest.START));
        var tx = new TransactionTemplate(transactions);
        var observations = IntradayEngineTest.breakoutInputs();
        for (int i = 0; i < observations.size(); i += 60) {
            var input = observations.get(i);
            when(calendar.now()).thenReturn(input.evaluatedAt());
            tx.executeWithoutResult(ignored -> publisher.publishEvent(input.observation()));
            service.flush();
        }
        assertThat(inputs.countBySessionDate(day)).isEqualTo(31);
        var paper = service.list(day);
        assertThat(paper).hasSize(1);
        var signal = paper.getFirst().signal();
        String frozen = recommendations.findById(signal.id()).orElseThrow().getSnapshot();
        var entered = IntradayEngineTest.after(paper.getFirst(), 1, signal.price());
        when(calendar.now()).thenReturn(entered.evaluatedAt());
        tx.executeWithoutResult(ignored -> publisher.publishEvent(entered.observation())); service.flush();
        var stopped = IntradayEngineTest.after(paper.getFirst(), 2, signal.plan().stop() - 1);
        when(calendar.now()).thenReturn(stopped.evaluatedAt());
        tx.executeWithoutResult(ignored -> publisher.publishEvent(stopped.observation())); service.flush();
        assertThat(service.list(day).getFirst().outcome().status).isEqualTo("STOPPED");
        assertThat(recommendations.findById(signal.id()).orElseThrow().getSnapshot()).isEqualTo(frozen);
        Map<String, Object> replay = service.replay(day, null);
        @SuppressWarnings("unchecked") var replayed = (Collection<IntradayModel.Result>) replay.get("results");
        assertThat(replayed).hasSize(1);
        assertThat(replayed.iterator().next().outcome().netReturn).isEqualTo(service.list(day).getFirst().outcome().netReturn);
        assertThat(recommendations.count()).isEqualTo(1); // replay does not write paper/closing samples
        long count = inputs.count();
        tx.executeWithoutResult(status -> { publisher.publishEvent(stopped.observation()); status.setRollbackOnly(); });
        service.flush();
        assertThat(inputs.count()).isEqualTo(count); // rolled-back common events are not consumed
    }
}
