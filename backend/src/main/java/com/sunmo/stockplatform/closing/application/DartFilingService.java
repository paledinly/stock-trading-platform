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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 받는 것: 조회 날짜, 법인 구분(corp_cls, null이면 전체), 페이지 번호. 돌려주는 것: DART 응답 JSON.
     * One list.json page; separate so tests can supply responses directly.
     */
    public interface PageSource { JsonNode fetch(LocalDate date, String corpCls, int page); }

    /**
     * 과거 공시 수집 진행 상태예요. lastDate는 마지막으로 끝낸 날짜(최신 날짜부터 거꾸로 받아요),
     * stoppedReason은 중간에 멈춘 이유(DART 상태 코드 등, 끝까지 받았으면 null)예요.
     */
    public record BackfillStatus(boolean running, LocalDate from, LocalDate to, LocalDate lastDate, int days, int calls,
            int saved, String stoppedReason, Instant startedAt, Instant finishedAt) {
        static BackfillStatus idle() { return new BackfillStatus(false, null, null, null, 0, 0, 0, null, null, null); }
    }

    // 과거 수집은 상장 시장별로 조회해요(비상장·기타법인 페이지를 받지 않아 호출 수가 줄어요).
    private static final List<String> BACKFILL_CLASSES = List.of("Y", "K");
    // DART 서버에 몰아서 부르지 않도록 과거 수집의 호출 사이 간격이에요.
    static final Duration BACKFILL_PAUSE = Duration.ofMillis(100);
    private final AtomicBoolean backfilling = new AtomicBoolean();
    private volatile BackfillStatus backfillStatus = BackfillStatus.idle();
    // 운영은 BACKFILL_PAUSE(100ms), 테스트는 0이에요.
    long pauseMillis = BACKFILL_PAUSE.toMillis();

    private final String apiKey;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final PageSource pages;
    // 응답이 멈춰도 기본 스케줄러 스레드를 붙잡지 않도록 연결 5초, 응답 20초 제한을 둬요.
    private static final RestClient DART = RestClient.builder().baseUrl("https://opendart.fss.or.kr")
            .requestFactory(org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect().build(
                    org.springframework.boot.http.client.ClientHttpRequestFactorySettings.defaults()
                            .withConnectTimeout(java.time.Duration.ofSeconds(5))
                            .withReadTimeout(java.time.Duration.ofSeconds(20))))
            .build();

    /**
     * 하는 일: 운영용 생성자예요. 실제 DART list.json을 부르는 PageSource를 만들어 넣어요.
     * 받는 것: DART 인증키(dart.api-key, 비어 있으면 수집 꺼짐), 거래일 달력, DB 접근 도구(JdbcTemplate).
     * 돌려주는 것: 없음(생성자).
     */
    @Autowired
    public DartFilingService(@Value("${dart.api-key:}") String apiKey, ClosingTradingCalendar calendar, JdbcTemplate jdbc) {
        // 인증키가 요청 주소(쿼리)에 들어가므로 이 주소나 예외 메시지를 로그로 남기면 안 돼요.
        this(apiKey, calendar, jdbc, (date, corpCls, page) -> DART.get()
                .uri(b -> b.path("/api/list.json").queryParam("crtfc_key", apiKey)
                        .queryParam("bgn_de", date.format(DATE)).queryParam("end_de", date.format(DATE))
                        .queryParamIfPresent("corp_cls", java.util.Optional.ofNullable(corpCls))
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
                JsonNode body = pages.fetch(date, null, page);
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

    /**
     * 하는 일: 과거 공시 수집을 백그라운드에서 시작해요(연구용, 수동 실행).
     * 받는 것: 시작일, 끝일(포함). 돌려주는 것: 시작했으면 true. 인증키가 없거나, 기간이 잘못됐거나, 이미 실행 중이면 false.
     * 최신 날짜부터 거꾸로 받아요. 중간에 멈추면 상태의 lastDate 전날을 끝일로 다시 시작하면 이어져요(이미 있는 공시는 건너뛰어요).
     */
    public boolean startBackfill(LocalDate from, LocalDate to) {
        if (apiKey.isEmpty() || from == null || to == null || from.isAfter(to) || !backfilling.compareAndSet(false, true))
            return false;
        backfillStatus = new BackfillStatus(true, from, to, null, 0, 0, 0, null, calendar.now(), null);
        Thread.ofVirtual().name("dart-backfill").start(() -> {
            try {
                log.info("DART backfill: {}", backfill(from, to));
            } catch (RuntimeException error) {
                // The key is part of the request URL; never log the exception message, which may contain it.
                BackfillStatus s = backfillStatus;
                backfillStatus = new BackfillStatus(false, s.from(), s.to(), s.lastDate(), s.days(), s.calls(), s.saved(),
                        "ERROR_" + error.getClass().getSimpleName(), s.startedAt(), calendar.now());
                log.warn("DART backfill failed: {}", error.getClass().getSimpleName());
            } finally {
                backfilling.set(false);
            }
        });
        return true;
    }

    /** 하는 일: 과거 공시 수집의 현재(또는 마지막) 상태를 돌려줘요. */
    public BackfillStatus backfillStatus() { return backfillStatus; }

    /**
     * 하는 일: [from, to] 날짜를 최신부터 거꾸로, 상장 시장(Y·K)별로 페이지를 넘기며 받아 묶음으로 저장해요.
     * 받는 것: 시작일, 끝일. 돌려주는 것: 최종 상태. DART가 오류 상태(예: 020 호출 한도 초과)를 주면 그 자리에서 멈춰요.
     */
    BackfillStatus backfill(LocalDate from, LocalDate to) {
        Instant startedAt = calendar.now();
        int days = 0, calls = 0, saved = 0;
        LocalDate last = null;
        for (LocalDate date = to; !date.isBefore(from); date = date.minusDays(1)) {
            for (String corpCls : BACKFILL_CLASSES) {
                for (int page = 1; page <= MAX_PAGES; page++) {
                    JsonNode body = pages.fetch(date, corpCls, page);
                    calls++;
                    pause();
                    String code = body == null ? "EMPTY" : body.path("status").asText();
                    if ("013".equals(code))
                        break;
                    if (!"000".equals(code)) {
                        backfillStatus = new BackfillStatus(false, from, to, last, days, calls, saved,
                                "DART_STATUS_" + code, startedAt, calendar.now());
                        return backfillStatus;
                    }
                    saved += saveBatch(body);
                    if (page >= body.path("total_page").asInt(1))
                        break;
                }
            }
            days++;
            last = date;
            backfillStatus = new BackfillStatus(true, from, to, last, days, calls, saved, null, startedAt, null);
        }
        backfillStatus = new BackfillStatus(false, from, to, last, days, calls, saved, null, startedAt, calendar.now());
        return backfillStatus;
    }

    /** 하는 일: 한 페이지의 상장사 공시를 한 번에 저장해요(이미 있는 접수번호는 건너뛰어요). 돌려주는 것: 새로 저장한 수. */
    private int saveBatch(JsonNode body) {
        List<Object[]> rows = new ArrayList<>();
        Timestamp now = Timestamp.from(calendar.now());
        for (JsonNode row : body.path("list")) {
            if (!LISTED.contains(row.path("corp_cls").asText()) || row.path("stock_code").asText().isBlank())
                continue;
            rows.add(new Object[] { row.path("rcept_no").asText(),
                    Date.valueOf(LocalDate.parse(row.path("rcept_dt").asText(), DATE)), row.path("corp_cls").asText(),
                    row.path("corp_code").asText(), row.path("corp_name").asText(), row.path("stock_code").asText().trim(),
                    row.path("report_nm").asText().trim(), row.path("flr_nm").asText(), row.path("rm").asText(), now });
        }
        if (rows.isEmpty())
            return 0;
        int saved = 0;
        for (int count : jdbc.batchUpdate("""
                insert into dart_filing(rcept_no, rcept_dt, corp_cls, corp_code, corp_name, stock_code,
                    report_nm, flr_nm, rm, fetched_at) values (?,?,?,?,?,?,?,?,?,?)
                on conflict (rcept_no) do nothing""", rows))
            saved += Math.max(count, 0);
        return saved;
    }

    /** 하는 일: 과거 수집 호출 사이를 잠깐 쉬어요. 테스트에서는 간격을 0으로 둬요. */
    private void pause() {
        if (pauseMillis <= 0)
            return;
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
