package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClosingPaperEntryServiceTest {
    static final Instant DONE = Instant.parse("2026-10-07T06:15:10Z");
    static final Instant DEADLINE = Instant.parse("2026-10-07T06:20:00Z");
    final ClosingTradingCalendar clock = mock(ClosingTradingCalendar.class);
    final ClosingRecommendationProperties policy = new ClosingRecommendationProperties(20,4,null,null,null,null,null);
    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    ClosingPaperEntryService service;
    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table closing_recommendation_run(id bigint primary key)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__closing_paper_entry.sql")).execute(db);
        jdbc.update("insert into closing_recommendation_run values (1)");
        service = new ClosingPaperEntryService(jdbc, clock, policy);
        when(clock.isTradingDay(any())).thenReturn(true);
        when(clock.now()).thenReturn(DONE.plusSeconds(5));
    }
    @AfterEach void close() { db.shutdown(); }
    ClosingRecommendationRun run(Instant completed) {
        var run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(1L); when(run.getCompletedAt()).thenReturn(completed);
        when(run.getRecommendationDate()).thenReturn(LocalDate.of(2026,10,7));
        return run;
    }
    ObservedMarketTick tick(Instant source, Instant received, boolean loss) {
        var tick = new MarketTick("005930", LocalDate.of(2026,10,7), source, BigDecimal.valueOf(100),
                1,100,BigDecimal.valueOf(10000),1,null,null,null,null,null,null,null,false,null,null);
        return new ObservedMarketTick(tick, null, received, loss);
    }
    @Test void actualFreshReceiptIsSavedOnceAndSurvivesRestart() {
        service.prepare(run(DONE),List.of("005930"));
        service.receive(tick(DONE.plusSeconds(1),DONE.plusSeconds(2),false)); service.capture();
        var entry = service.find(1,"005930");
        assertThat(entry.status()).isEqualTo("ENTERED"); assertThat(entry.at()).isEqualTo(DONE.plusSeconds(5));
        var restarted = new ClosingPaperEntryService(jdbc,clock,policy); restarted.capture();
        assertThat(restarted.find(1,"005930")).isEqualTo(entry);
    }
    @Test void stalePreCompletionLostAndFutureTicksCannotFillAndDeadlineExpires() {
        service.prepare(run(DONE),List.of("005930"));
        for (var event : List.of(tick(DONE,DONE.plusSeconds(1),false),
                tick(DONE.plusSeconds(1),DONE.plusSeconds(2),true),
                tick(DONE.plusSeconds(30),DONE.plusSeconds(30),false))) {
            service.receive(event); service.capture(); assertThat(service.find(1,"005930").price()).isNull();
        }
        assertThat(ClosingPaperEntryService.executable(tick(DONE.plusSeconds(1),DONE.plusSeconds(2),false),
                DONE,DONE.plusSeconds(12),DEADLINE)).isFalse();
        when(clock.now()).thenReturn(DEADLINE); service.capture();
        assertThat(service.find(1,"005930").status()).isEqualTo("NO_FRESH_TICK_BEFORE_DEADLINE");
    }
    @Test void lateEvaluationAndOfflineRestartDoNotBackfillEntry() {
        service.prepare(run(DEADLINE),List.of("005930"));
        assertThat(service.find(1,"005930").status()).isEqualTo("EVALUATION_DEADLINE_MISSED");
    }
}
