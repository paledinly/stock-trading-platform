/*
 * 공시(DART: 금융감독원 전자공시시스템에 회사가 올리는 신고 문서) 목록을 날짜별로 받아 dart_filing 테이블에 쌓는 연구용 수집기예요.
 * DART는 접수 "날짜"만 주고 접수 "시각"은 주지 않아서, 나중에 후보와 날짜 단위로만 맞춰 볼 수 있어요.
 * 스케줄러(정해진 시각에 자동 실행하는 장치)가 저녁·아침에 부르고, 추천 점수나 선정 로직은 이 데이터를 읽지 않아요.
 */
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
 * 상장사 공시 목록을 기록만 해 두는 클래스예요(점수에는 쓰지 않아요).
 * 매일 저녁과 아침에 우편함을 열어 지난 며칠치 편지를 다시 훑고, 이미 꽂아 둔 편지는 그대로 두고 새 편지만 꽂는 우편 정리 담당이라고 생각하면 돼요.
 *
 * Record-only external context: the DART disclosure list of KOSPI/KOSDAQ companies by receipt date, stored in
 * dart_filing for later point-in-time joins with closing candidates (DART has no receipt time, only the date).
 * Disabled unless DART_API_KEY is set. Scoring and selection never read it.
 */
@Service
public class DartFilingService {
    private static final Logger log = LoggerFactory.getLogger(DartFilingService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    // 한 번에 받는 공시 개수(page_count)와, 하루치 조회에서 최대로 넘길 페이지 수예요.
    static final int PAGE_COUNT = 100;
    static final int MAX_PAGES = 50;
    /**
     * 주말·휴장일(시장이 쉬는 날)과 늦은 정정 공시까지 덮으려고 며칠 전부터 다시 읽어요. 이미 저장된 행은 그대로 둬요.
     * Re-read a few days so weekends, holidays and late corrections are covered; existing rows are kept.
     */
    static final int DAYS_BACK = 3;
    // corp_cls 값 중 Y=유가증권시장(KOSPI), K=코스닥(KOSDAQ) 상장사만 저장해요.
    private static final Set<String> LISTED = Set.of("Y", "K");

    /**
     * 한 번의 수집 결과 요약이에요: 조회 기간(from~to), 읽은 페이지 수, 새로 저장한 행 수, 상태(OK 또는 DART_STATUS_코드).
     */
    public record Result(LocalDate from, LocalDate to, int pages, int saved, String status) { }

    /**
     * list.json 한 페이지를 가져오는 통로예요. 테스트가 응답을 직접 넣을 수 있게 HTTP 호출과 분리했어요.
     * 받는 것: 조회 날짜, 페이지 번호. 돌려주는 것: DART 응답 JSON.
     * One list.json page; separate so tests can supply responses directly.
     */
    public interface PageSource { JsonNode fetch(LocalDate date, int page); }

    private final String apiKey;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final PageSource pages;

    /**
     * 하는 일: 운영용 생성자예요. 실제 DART list.json을 부르는 PageSource를 만들어 넣어요.
     * 받는 것: DART 인증키(dart.api-key, 비어 있으면 수집 꺼짐), 거래일 달력, DB 접근 도구(JdbcTemplate).
     * 돌려주는 것: 없음(생성자).
     */
    @Autowired
    public DartFilingService(@Value("${dart.api-key:}") String apiKey, ClosingTradingCalendar calendar, JdbcTemplate jdbc) {
        // 인증키가 요청 주소(쿼리)에 들어가므로 이 주소나 예외 메시지를 로그로 남기면 안 돼요.
        this(apiKey, calendar, jdbc, (date, page) -> RestClient.create("https://opendart.fss.or.kr").get()
                .uri(b -> b.path("/api/list.json").queryParam("crtfc_key", apiKey)
                        .queryParam("bgn_de", date.format(DATE)).queryParam("end_de", date.format(DATE))
                        .queryParam("page_no", page).queryParam("page_count", PAGE_COUNT).build())
                .retrieve().body(JsonNode.class));
    }

    /**
     * 하는 일: 테스트에서도 쓰는 생성자예요. 인증키 앞뒤 공백을 지우고 의존 객체를 보관해요.
     * 받는 것: 인증키, 거래일 달력, JdbcTemplate, 페이지 공급자(PageSource).
     * 돌려주는 것: 없음(생성자).
     */
    DartFilingService(String apiKey, ClosingTradingCalendar calendar, JdbcTemplate jdbc, PageSource pages) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.pages = pages;
    }

    /**
     * 하는 일: 매일 18:10(한국시간)에 그날 공시를 모아요. 저녁 실행 뒤 늦게 접수된 공시는 다음 아침 실행이 채워요.
     * 받는 것: 없음(스케줄러가 호출). 돌려주는 것: 없음.
     * Evening for the day's filings, morning for filings received after the evening run.
     */
    @Scheduled(cron = "0 10 18 * * *", zone = "Asia/Seoul")
    public void evening() { scheduled(); }

    /**
     * 하는 일: 평일 07:40(한국시간)에 한 번 더 모아요(전날 저녁 이후 접수분 보충).
     * 받는 것: 없음(스케줄러가 호출). 돌려주는 것: 없음.
     */
    @Scheduled(cron = "0 40 7 * * MON-FRI", zone = "Asia/Seoul")
    public void morning() { scheduled(); }

    /**
     * 하는 일: 인증키가 있을 때만 수집을 실행하고 결과나 실패를 로그로 남겨요.
     * 받는 것: 없음. 돌려주는 것: 없음(실패해도 예외를 밖으로 던지지 않아요).
     */
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

    /**
     * 하는 일: 오늘부터 DAYS_BACK일 전까지 날짜별로 공시 목록을 페이지 단위로 읽어 상장사 공시만 저장해요.
     * 받는 것: 없음(오늘 날짜는 ClosingTradingCalendar에서 가져와요).
     * 돌려주는 것: Result 요약. DART가 오류 상태를 주면 그 자리에서 멈추고 그때까지의 결과를 돌려줘요.
     */
    Result collect() {
        // 준비: 조회 기간은 달력 날짜 기준 [오늘-3일, 오늘]이에요(휴장일도 그냥 조회해 보고 013이면 넘어가요).
        LocalDate to = calendar.today();
        LocalDate from = to.minusDays(DAYS_BACK);
        int pagesRead = 0, saved = 0;
        String status = "OK";
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (int page = 1; page <= MAX_PAGES; page++) {
                // 처리: 날짜·페이지별로 DART 응답을 받아 상태 코드를 먼저 확인해요(000=정상, 013=해당 날짜 자료 없음).
                JsonNode body = pages.fetch(date, page);
                pagesRead++;
                String code = body == null ? "EMPTY" : body.path("status").asText();
                if ("013".equals(code))
                    break; // no data for this date
                if (!"000".equals(code)) {
                    status = "DART_STATUS_" + code;
                    return new Result(from, to, pagesRead, saved, status);
                }
                // 비상장사(corp_cls가 Y/K가 아님)나 종목코드가 없는 공시는 건너뛰어요.
                for (JsonNode row : body.path("list")) {
                    if (!LISTED.contains(row.path("corp_cls").asText()) || row.path("stock_code").asText().isBlank())
                        continue;
                    // 접수번호(rcept_no)가 이미 있으면 덮어쓰지 않아요(on conflict do nothing). fetched_at은 우리가 받은 시각이에요.
                    saved += jdbc.update("""
                            insert into dart_filing(rcept_no, rcept_dt, corp_cls, corp_code, corp_name, stock_code,
                                report_nm, flr_nm, rm, fetched_at) values (?,?,?,?,?,?,?,?,?,?)
                            on conflict (rcept_no) do nothing""",
                            row.path("rcept_no").asText(), Date.valueOf(LocalDate.parse(row.path("rcept_dt").asText(), DATE)),
                            row.path("corp_cls").asText(), row.path("corp_code").asText(), row.path("corp_name").asText(),
                            row.path("stock_code").asText().trim(), row.path("report_nm").asText().trim(),
                            row.path("flr_nm").asText(), row.path("rm").asText(), Timestamp.from(calendar.now()));
                }
                // 결과: 마지막 페이지(total_page)까지 읽었으면 다음 날짜로 넘어가요.
                if (page >= body.path("total_page").asInt(1))
                    break;
            }
        }
        return new Result(from, to, pagesRead, saved, status);
    }
}
