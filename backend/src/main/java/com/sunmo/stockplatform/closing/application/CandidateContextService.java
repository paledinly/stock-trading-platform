/*
 * 15:15 공식 평가가 끝난 직후, 후보 종목마다 KIS 현재가 응답에서 시장경고·투자주의·VI·단기과열·정리매매·관리종목·거래정지 상태를 읽어 기록해요.
 * 기록만 하고 점수 계산이나 선정에는 쓰지 않아요(ADR-010 판단 기간 동안 나중 가설 검증용).
 * 스프링 스케줄러가 평일 15:16:45와 15:19:00에 자동으로 불러요.
 */
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
 * 시장경고(거래소가 붙이는 투자주의·경고·위험 표시), VI(변동성 완화장치, 급등락 시 잠깐 단일가로 바꾸는 장치)를 포함해요.
 * 시험이 끝난 뒤 수험생들의 건강 상태를 따로 적어 두는 보건 기록부 같아요. 채점에는 전혀 반영하지 않아요.
 */
@Service
public class CandidateContextService {
    private static final Logger log = LoggerFactory.getLogger(CandidateContextService.class);

    /** 한 번 기록한 결과 요약이에요. run ID, 대상 후보 수, 성공 기록 수, 조회 실패 수를 담아요. */
    public record Result(Long runId, int candidates, int recorded, int failures) { }

    /**
     * One KIS current-price response (output object); separate so tests can supply it directly.
     * 받는 것: 종목 코드. 돌려주는 것: KIS 현재가 응답의 output 객체.
     */
    public interface QuoteSource { JsonNode fetch(String stockCode); }

    private final boolean enabled;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationRunRepository runs;
    private final JdbcTemplate jdbc;
    private final QuoteSource quotes;

    /**
     * 스프링이 쓰는 생성자예요. 실제 KIS 현재가 API를 부르는 QuoteSource를 만들어 연결해요.
     * 받는 것: 기능 사용 여부(기본 꺼짐), KIS 설정, 거래일 달력, run 저장소, DB 접근, KIS HTTP 클라이언트, 토큰 관리자, 요청 실행기.
     */
    @Autowired
    public CandidateContextService(@Value("${closing.candidate-context.enabled:false}") boolean enabled,
            KisProperties kis, ClosingTradingCalendar calendar, ClosingRecommendationRunRepository runs, JdbcTemplate jdbc,
            RestClient kisRestClient, KisTokenManager tokens, KisRequestExecutor requests) {
        // KIS 국내주식 현재가(FHKST01010100)를 호출하고, 실패 응답이면 예외로 바꿔요. 인증정보는 헤더로만 보내요.
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

    /**
     * 테스트에서도 쓰는 내부 생성자예요. 시세 조회기(QuoteSource)를 직접 넣을 수 있어요.
     * 받는 것: 사용 여부, KIS 설정, 달력, run 저장소, DB 접근, 시세 조회기.
     */
    CandidateContextService(boolean enabled, KisProperties kis, ClosingTradingCalendar calendar,
            ClosingRecommendationRunRepository runs, JdbcTemplate jdbc, QuoteSource quotes) {
        this.enabled = enabled;
        this.kis = kis;
        this.calendar = calendar;
        this.runs = runs;
        this.jdbc = jdbc;
        this.quotes = quotes;
    }

    /**
     * After the 15:15 run completes; the second pass only fills codes the first one missed.
     * 평일 15:16:45에 1차 기록을 해요. 받는 것·돌려주는 것: 없음.
     */
    @Scheduled(cron = "45 16 15 * * MON-FRI", zone = "Asia/Seoul", scheduler = "closingScheduler")
    public void first() { scheduled(); }

    /** 평일 15:19:00에 2차 기록을 해요. 1차에서 빠지거나 실패한 종목만 다시 채워요. 받는 것·돌려주는 것: 없음. */
    @Scheduled(cron = "0 19 15 * * MON-FRI", zone = "Asia/Seoul", scheduler = "closingScheduler")
    public void retry() { scheduled(); }

    /**
     * 기능과 KIS가 켜져 있을 때만 기록을 실행하고, 결과나 실패를 로그로 남겨요.
     * 받는 것·돌려주는 것: 없음. 실패해도 예외를 밖으로 던지지 않아요.
     */
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

    /**
     * 오늘 FORWARD(실제 장중에 실시간으로 돈 공식 평가) run의 후보 상태를 기록해요.
     * 받는 것: 없음. 돌려주는 것: 기록 결과 요약, 거래일이 아니거나 완료된 run이 없으면 null.
     */
    Result record() {
        // 준비: 오늘이 거래일이고, 완료된 최신 FORWARD run이 있어야 해요.
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today))
            return null;
        ClosingRecommendationRun run = runs.findFirstByRecommendationDateAndExecutionModeOrderByIdDesc(today, "FORWARD")
                .orElse(null);
        if (run == null || run.getCompletedAt() == null)
            return null;
        // 대상은 가상 진입 행이 있는 후보 중 아직 오류 없이 기록되지 않은 종목이에요(2차 실행은 빠진 것만 채워요).
        List<String> codes = jdbc.queryForList("""
                select p.stock_code from closing_paper_entry p
                 where p.run_id = ? and not exists (select 1 from closing_candidate_context c
                                                     where c.run_id = p.run_id and c.stock_code = p.stock_code
                                                       and c.error is null)
                 order by p.stock_code""", String.class, run.getId());
        int recorded = 0, failures = 0;
        // 처리: 종목마다 현재가를 조회하고, 실패하면 오류 문구를 담아 그대로 기록해요.
        for (String code : codes) {
            JsonNode output = null;
            String error = null;
            try {
                output = quotes.fetch(code);
            } catch (RuntimeException failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                failures++;
            }
            // 이전 기록(실패 행 포함)을 지우고 이번 결과로 새로 넣어요. received_at은 실제 수신 시각이에요.
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
        // 결과: run ID와 대상·성공·실패 건수를 돌려줘요.
        return new Result(run.getId(), codes.size(), recorded, failures);
    }

    /**
     * 응답에서 문자열 필드를 꺼내요.
     * 받는 것: 응답 객체(없을 수 있음), 필드 이름. 돌려주는 것: 앞뒤 공백을 뺀 값, 비었으면 null.
     */
    private static String text(JsonNode output, String field) {
        if (output == null) return null;
        String value = output.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 응답에서 숫자 필드를 꺼내요. 천 단위 쉼표는 제거해요.
     * 받는 것: 응답 객체, 필드 이름. 돌려주는 것: 숫자 값, 없거나 숫자가 아니면 null.
     */
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
