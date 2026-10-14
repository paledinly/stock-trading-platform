package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.closing.infrastructure.ClosingRecommendationRunRepository;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Record-only external context (ADR-010 judgement period): right after the 15:15 official evaluation, reads every
 * candidate's market-warning, caution, VI, short-term-overheat, liquidation, managed and halt state from the KIS
 * current-price response into closing_candidate_context. Scoring and selection never read it.
 */
@Service
public class CandidateContextService {
    private static final Logger log = LoggerFactory.getLogger(CandidateContextService.class);

    public record Result(Long runId, int candidates, int recorded, int failures) { }

    /** One KIS current-price response (output object); separate so tests can supply it directly. */
    public interface QuoteSource { JsonNode fetch(String stockCode); }

    private final boolean enabled;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationRunRepository runs;
    private final JdbcTemplate jdbc;
    private final QuoteSource quotes;

    @Autowired
    public CandidateContextService(@Value("${closing.candidate-context.enabled:false}") boolean enabled,
            KisProperties kis, ClosingTradingCalendar calendar, ClosingRecommendationRunRepository runs, JdbcTemplate jdbc,
            RestClient kisRestClient, KisTokenManager tokens, KisRequestExecutor requests) {
        this(enabled, kis, calendar, runs, jdbc, code -> requests.execute(true, () -> {
            JsonNode response = kisRestClient.get().uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J").queryParam("FID_INPUT_ISCD", code).build())
                    .header("authorization", "Bearer " + tokens.getAccessToken()).header("appkey", kis.appKey())
                    .header("appsecret", kis.appSecret()).header("tr_id", "FHKST01010100").header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText()))
                KisResponseErrors.failure("KIS quote failed", response == null ? "EMPTY" : response.path("msg_cd").asText(),
                        "Quote unavailable");
            return response.path("output");
        }));
    }

    CandidateContextService(boolean enabled, KisProperties kis, ClosingTradingCalendar calendar,
            ClosingRecommendationRunRepository runs, JdbcTemplate jdbc, QuoteSource quotes) {
        this.enabled = enabled;
        this.kis = kis;
        this.calendar = calendar;
        this.runs = runs;
        this.jdbc = jdbc;
        this.quotes = quotes;
    }

    /** After the 15:15 run completes; the second pass only fills codes the first one missed. */
    @Scheduled(cron = "45 16 15 * * MON-FRI", zone = "Asia/Seoul")
    public void first() { scheduled(); }

    @Scheduled(cron = "0 19 15 * * MON-FRI", zone = "Asia/Seoul")
    public void retry() { scheduled(); }

    private void scheduled() {
        if (!enabled || !kis.enabled())
            return;
        try {
            Result result = record();
            if (result != null)
                log.info("Candidate context: {}", result);
        } catch (RuntimeException error) {
            log.warn("Candidate context failed: {}", error.getMessage());
        }
    }

    Result record() {
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today))
            return null;
        ClosingRecommendationRun run = runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(today, "FORWARD")
                .orElse(null);
        if (run == null || run.getCompletedAt() == null)
            return null;
        List<String> codes = jdbc.queryForList("""
                select p.stock_code from closing_paper_entry p
                 where p.run_id = ? and not exists (select 1 from closing_candidate_context c
                                                     where c.run_id = p.run_id and c.stock_code = p.stock_code
                                                       and c.error is null)
                 order by p.stock_code""", String.class, run.getId());
        int recorded = 0, failures = 0;
        for (String code : codes) {
            JsonNode output = null;
            String error = null;
            try {
                output = quotes.fetch(code);
            } catch (RuntimeException failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                failures++;
            }
            Instant received = calendar.now();
            jdbc.update("delete from closing_candidate_context where run_id = ? and stock_code = ?", run.getId(), code);
            jdbc.update("""
                    insert into closing_candidate_context(run_id, stock_code, received_at, current_price, change_rate,
                        market_warning_code, investment_caution, vi_code, short_term_overheat, liquidation_trading,
                        managed_code, temporary_halt, status_code, error) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    run.getId(), code, Timestamp.from(received), number(output, "stck_prpr"),
                    number(output, "prdy_ctrt"), text(output, "mrkt_warn_cls_code"), text(output, "invt_caful_yn"),
                    text(output, "vi_cls_code"), text(output, "short_over_yn"), text(output, "sltr_yn"),
                    text(output, "mang_issu_cls_code"), text(output, "temp_stop_yn"),
                    text(output, "iscd_stat_cls_code"), error);
            if (output != null) recorded++;
        }
        return new Result(run.getId(), codes.size(), recorded, failures);
    }

    private static String text(JsonNode output, String field) {
        if (output == null) return null;
        String value = output.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal number(JsonNode output, String field) {
        String value = text(output, field);
        if (value == null) return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
