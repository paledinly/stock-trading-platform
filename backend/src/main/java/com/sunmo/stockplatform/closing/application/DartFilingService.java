package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Record-only external context: the DART disclosure list of KOSPI/KOSDAQ companies by receipt date, stored in
 * dart_filing for later point-in-time joins with closing candidates (DART has no receipt time, only the date).
 * Disabled unless DART_API_KEY is set. Scoring and selection never read it.
 */
@Service
public class DartFilingService {
    private static final Logger log = LoggerFactory.getLogger(DartFilingService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    static final int PAGE_COUNT = 100;
    static final int MAX_PAGES = 50;
    /** Re-read a few days so weekends, holidays and late corrections are covered; existing rows are kept. */
    static final int DAYS_BACK = 3;
    private static final Set<String> LISTED = Set.of("Y", "K");

    public record Result(LocalDate from, LocalDate to, int pages, int saved, String status) { }

    /** One list.json page; separate so tests can supply responses directly. */
    public interface PageSource { JsonNode fetch(LocalDate date, int page); }

    private final String apiKey;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final PageSource pages;

    @Autowired
    public DartFilingService(@Value("${dart.api-key:}") String apiKey, ClosingTradingCalendar calendar, JdbcTemplate jdbc) {
        this(apiKey, calendar, jdbc, (date, page) -> RestClient.create("https://opendart.fss.or.kr").get()
                .uri(b -> b.path("/api/list.json").queryParam("crtfc_key", apiKey)
                        .queryParam("bgn_de", date.format(DATE)).queryParam("end_de", date.format(DATE))
                        .queryParam("page_no", page).queryParam("page_count", PAGE_COUNT).build())
                .retrieve().body(JsonNode.class));
    }

    DartFilingService(String apiKey, ClosingTradingCalendar calendar, JdbcTemplate jdbc, PageSource pages) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.pages = pages;
    }

    /** Evening for the day's filings, morning for filings received after the evening run. */
    @Scheduled(cron = "0 10 18 * * *", zone = "Asia/Seoul")
    public void evening() { scheduled(); }

    @Scheduled(cron = "0 40 7 * * MON-FRI", zone = "Asia/Seoul")
    public void morning() { scheduled(); }

    private void scheduled() {
        if (apiKey.isEmpty())
            return;
        try {
            log.info("DART filings: {}", collect());
        } catch (RuntimeException error) {
            // The key is part of the request URL; never log the exception message, which may contain it.
            log.warn("DART filings failed: {}", error.getClass().getSimpleName());
        }
    }

    Result collect() {
        LocalDate to = calendar.today();
        LocalDate from = to.minusDays(DAYS_BACK);
        int pagesRead = 0, saved = 0;
        String status = "OK";
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode body = pages.fetch(date, page);
                pagesRead++;
                String code = body == null ? "EMPTY" : body.path("status").asText();
                if ("013".equals(code))
                    break; // no data for this date
                if (!"000".equals(code)) {
                    status = "DART_STATUS_" + code;
                    return new Result(from, to, pagesRead, saved, status);
                }
                for (JsonNode row : body.path("list")) {
                    if (!LISTED.contains(row.path("corp_cls").asText()) || row.path("stock_code").asText().isBlank())
                        continue;
                    saved += jdbc.update("""
                            insert into dart_filing(rcept_no, rcept_dt, corp_cls, corp_code, corp_name, stock_code,
                                report_nm, flr_nm, rm, fetched_at) values (?,?,?,?,?,?,?,?,?,?)
                            on conflict (rcept_no) do nothing""",
                            row.path("rcept_no").asText(), Date.valueOf(LocalDate.parse(row.path("rcept_dt").asText(), DATE)),
                            row.path("corp_cls").asText(), row.path("corp_code").asText(), row.path("corp_name").asText(),
                            row.path("stock_code").asText().trim(), row.path("report_nm").asText().trim(),
                            row.path("flr_nm").asText(), row.path("rm").asText(), Timestamp.from(calendar.now()));
                }
                if (page >= body.path("total_page").asInt(1))
                    break;
            }
        }
        return new Result(from, to, pagesRead, saved, status);
    }
}
