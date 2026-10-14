package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CandidateContextServiceTest {
    final ClosingRecommendationRunRepository runs = mock(ClosingRecommendationRunRepository.class);
    final ObjectMapper json = new ObjectMapper();
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table closing_recommendation_run(id bigint primary key)");
        jdbc.execute("create table closing_paper_entry(run_id bigint, stock_code varchar(20))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V35__create_candidate_context_and_dart_filing.sql"))
                .execute(db);
        jdbc.update("insert into closing_recommendation_run values (7)");
        jdbc.update("insert into closing_paper_entry values (7, '005930'), (7, '000660')");
        ClosingRecommendationRun run = mock(ClosingRecommendationRun.class);
        when(run.getId()).thenReturn(7L);
        when(run.getCompletedAt()).thenReturn(Instant.parse("2026-10-02T06:15:19Z"));
        when(runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(LocalDate.of(2026, 10, 2), "FORWARD"))
                .thenReturn(Optional.of(run));
    }

    @AfterEach void close() { db.shutdown(); }

    CandidateContextService service(CandidateContextService.QuoteSource quotes) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-02T06:16:45Z"), ZoneOffset.UTC));
        KisProperties kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        return new CandidateContextService(true, kis, calendar, runs, jdbc, quotes);
    }

    @Test
    void recordsWarningStateOfEveryCandidateAndRetriesOnlyFailures() throws Exception {
        var warned = json.readTree("""
                {"stck_prpr":"71,000","prdy_ctrt":"3.25","mrkt_warn_cls_code":"02","invt_caful_yn":"Y",
                 "vi_cls_code":"N","short_over_yn":"Y","sltr_yn":"N","mang_issu_cls_code":"N","temp_stop_yn":"N",
                 "iscd_stat_cls_code":"55"}""");
        AtomicBoolean down = new AtomicBoolean(true);
        var first = service(code -> {
            if (code.equals("000660") && down.get()) throw new IllegalStateException("EGW00201");
            return warned;
        }).record();

        assertThat(first.candidates()).isEqualTo(2);
        assertThat(first.recorded()).isEqualTo(1);
        assertThat(first.failures()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select * from closing_candidate_context where stock_code = '005930'");
        assertThat(row.get("MARKET_WARNING_CODE")).isEqualTo("02");
        assertThat(row.get("SHORT_TERM_OVERHEAT")).isEqualTo("Y");
        assertThat(row.get("CURRENT_PRICE").toString()).startsWith("71000");
        assertThat(jdbc.queryForObject("select error from closing_candidate_context where stock_code = '000660'",
                String.class)).contains("EGW00201");

        down.set(false);
        var retry = service(code -> warned).record();
        assertThat(retry.candidates()).as("only the failed code is read again").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from closing_candidate_context where error is null",
                Integer.class)).isEqualTo(2);
    }
}
