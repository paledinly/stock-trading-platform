/*
 * CandidateContextService(후보 종목의 시장 경고 상태 기록)를 검사하는 테스트예요.
 * 공식 FORWARD run의 가상 진입 후보마다 투자주의·단기과열 같은 경고 상태와 현재가를 저장하고, 실패한 종목만 다시 조회하는지 확인해요.
 * 나중에 경고 종목이 성과에 미치는 영향을 가설로 검증하려면 당시 상태가 빠짐없이 남아야 해서 중요해요.
 */
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

/**
 * 후보마다 경고 상태가 한 번씩 기록되고, 실패만 다시 시도되는지 확인하는 테스트예요.
 */
class CandidateContextServiceTest {
    // run 저장소는 목(mock, 가짜 객체)이고, 기록 테이블은 H2(테스트용 메모리 DB)에 만들어요.
    final ClosingRecommendationRunRepository runs = mock(ClosingRecommendationRunRepository.class);
    final ObjectMapper json = new ObjectMapper();
    EmbeddedDatabase db;
    JdbcTemplate jdbc;

    /**
     * 테스트마다 새 H2를 만들고 V35 마이그레이션을 적용해요. run 7에는 가상 진입 종목 005930, 000660이 있고,
     * 2026-10-02 FORWARD run은 15:15:19 KST에 완료된 것으로 둬요.
     */
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

    /** 테스트가 끝나면 메모리 DB를 닫아요. */
    @AfterEach void close() { db.shutdown(); }

    /** 15:16:45 KST에 멈춘 고정 시계 Clock과 지정한 시세 공급원으로 서비스를 만들어요. */
    CandidateContextService service(CandidateContextService.QuoteSource quotes) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse("2026-10-02T06:16:45Z"), ZoneOffset.UTC));
        KisProperties kis = mock(KisProperties.class);
        when(kis.enabled()).thenReturn(true);
        return new CandidateContextService(true, kis, calendar, runs, jdbc, quotes);
    }

    /**
     * 모든 후보의 경고 상태를 기록하고, 다음 실행에서는 실패했던 종목만 다시 조회하는지 확인해요.
     * 준비: 경고 코드 02, 단기과열 Y, 현재가 "71,000"인 KIS 응답. 첫 실행에서는 000660 조회가 EGW00201(호출 제한)로 실패해요.
     * 기대: 첫 실행은 후보 2, 기록 1, 실패 1이고 005930 행에 경고 코드·과열·현재가 71000이, 000660 행에 오류가 남아요.
     * 두 번째 실행은 실패한 1종목만 다시 읽고, 오류 없는 행이 2개가 돼요.
     */
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

        // 실행(재시도): 장애가 풀린 뒤 다시 기록해요.
        down.set(false);
        var retry = service(code -> warned).record();
        assertThat(retry.candidates()).as("only the failed code is read again").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from closing_candidate_context where error is null",
                Integer.class)).isEqualTo(2);
    }
}
