/*
 * 15:15 평가가 끝난 뒤 15:20 가상 진입 마감 전, 상한가(하루 최대 상승폭 +30%) 근처 종목의 호가(사고팔겠다고 내놓은 가격)를 기록하는 연구용 수집기예요.
 * "상한가 근처 종목을 정말 살 수 있었는지"를 나중에 따져 보려는 자료이고, 추천 전략은 이 데이터를 읽지 않아요.
 * 스케줄러가 평일 15:16:15와 15:18:30에 부르며, closing.limit-up-watch.enabled=true일 때만 동작해요. 실제 주문은 하지 않아요.
 */
package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.Book;
import com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.Level;
import com.sunmo.stockplatform.closing.trajectory.OrderbookCollector;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;
import com.sunmo.stockplatform.kis.ranking.MarketRankingProvider;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상한가 근처 종목의 호가창을 사진 찍듯 저장해 두는 연구 기록 담당 클래스예요.
 * 마감 직전 인기 맛집 앞에 줄이 얼마나 서 있는지(남은 자리=매도 잔량)를 정해진 두 시각에 찍어 두는 관찰자라고 생각하면 돼요.
 *
 * Research collector (audit/2026-10-14-limit-up-entry): after the 15:15 evaluation and before the 15:20 entry
 * deadline, records every stock up 20%+ in the KIS fluctuation ranking with its 10-level order book, so a later
 * study can tell whether a near-limit stock could actually be bought. Read-only toward the strategy.
 */
@Service
public class LimitUpWatchService {
    private static final Logger log = LoggerFactory.getLogger(LimitUpWatchService.class);
    // 등락률 20% 이상만 후보로 봐요(상한가 +30%에 다가가는 종목).
    static final BigDecimal MIN_CHANGE_RATE = new BigDecimal("20");
    // 시장별 상승률 순위에서 가져올 최대 종목 수예요.
    static final int RANKING_LIMIT = 100;
    // 한 번의 샘플에서 호가를 조회할 최대 종목 수예요(등락률 높은 순).
    static final int MAX_STOCKS = 40;
    // 샘플을 찍을 수 있는 시간 창: 15:15 이상 ~ 15:20 미만(평가 후, 가상 진입 마감 전).
    static final LocalTime WINDOW_START = LocalTime.of(15, 15);
    static final LocalTime WINDOW_END = LocalTime.of(15, 20);

    /**
     * 한 번의 샘플 요약이에요: 세션 날짜, 샘플 이름(15:16/15:18), 후보 수, 호가를 받은 종목 수, 호가 조회 실패 수.
     */
    public record Sample(LocalDate sessionDate, String label, int candidates, int books, int failures) { }

    /**
     * 10단계 호가(매도·매수 각 10개 가격과 잔량=남은 주문 수량)를 가져오는 통로예요. 테스트가 호가를 직접 넣을 수 있게 HTTP 호출과 분리했어요.
     * 받는 것: 종목코드. 돌려주는 것: 호가 묶음(Book).
     * Fetches a 10-level order book; separate from the HTTP call so tests can supply books directly.
     */
    public interface BookSource { Book fetch(String stockCode); }

    private final boolean enabled;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final MarketRankingProvider rankings;
    private final StockRepository stocks;
    private final JdbcTemplate jdbc;
    private final BookSource books;

    /**
     * 하는 일: 운영용 생성자예요. KIS 호가 조회(읽기 전용 시세 API, 주문 아님)를 부르는 BookSource를 만들어 넣어요.
     * 받는 것: 기능 켜짐 여부, KIS 설정, 거래일 달력, 순위 제공자, 종목 저장소, JdbcTemplate, KIS HTTP 클라이언트, 토큰 관리자, 요청 실행기.
     * 돌려주는 것: 없음(생성자).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LimitUpWatchService(@Value("${closing.limit-up-watch.enabled:false}") boolean enabled, KisProperties kis,
            ClosingTradingCalendar calendar, MarketRankingProvider rankings, StockRepository stocks, JdbcTemplate jdbc,
            RestClient kisRestClient, KisTokenManager tokens, KisRequestExecutor requests) {
        this(enabled, kis, calendar, rankings, stocks, jdbc, code -> requests.execute(false, () -> {
            JsonNode response = kisRestClient.get().uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J").queryParam("FID_INPUT_ISCD", code).build())
                    .header("authorization", "Bearer " + tokens.getAccessToken()).header("appkey", kis.appKey())
                    .header("appsecret", kis.appSecret()).header("tr_id", "FHKST01010200").header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            // 응답이 없거나 rt_cd가 0(성공)이 아니면 KisResponseErrors.failure가 예외를 던져 이 종목을 실패로 처리해요.
            if (response == null || !"0".equals(response.path("rt_cd").asText()))
                KisResponseErrors.failure("KIS orderbook failed", response == null ? "EMPTY" : response.path("msg_cd").asText(),
                        "Orderbook unavailable");
            return OrderbookCollector.parse(code, response.path("output1"), calendar.now());
        }));
    }

    /**
     * 하는 일: 테스트에서도 쓰는 생성자예요. 의존 객체를 그대로 보관해요.
     * 받는 것: 기능 켜짐 여부, KIS 설정, 거래일 달력, 순위 제공자, 종목 저장소, JdbcTemplate, 호가 공급자.
     * 돌려주는 것: 없음(생성자).
     */
    LimitUpWatchService(boolean enabled, KisProperties kis, ClosingTradingCalendar calendar,
            MarketRankingProvider rankings, StockRepository stocks, JdbcTemplate jdbc, BookSource books) {
        this.enabled = enabled;
        this.kis = kis;
        this.calendar = calendar;
        this.rankings = rankings;
        this.stocks = stocks;
        this.jdbc = jdbc;
        this.books = books;
    }

    /**
     * 하는 일: 평일 15:16:15에 첫 번째 샘플(15:16)을 찍어요.
     * 받는 것: 없음(스케줄러가 호출). 돌려주는 것: 없음.
     */
    @Scheduled(cron = "15 16 15 * * MON-FRI", zone = "Asia/Seoul")
    public void first() { scheduled("15:16"); }

    /**
     * 하는 일: 평일 15:18:30에 두 번째 샘플(15:18)을 찍어요.
     * 받는 것: 없음(스케줄러가 호출). 돌려주는 것: 없음.
     */
    @Scheduled(cron = "30 18 15 * * MON-FRI", zone = "Asia/Seoul")
    public void second() { scheduled("15:18"); }

    /**
     * 하는 일: 기능과 KIS가 모두 켜져 있을 때만 샘플을 찍고 결과나 실패를 로그로 남겨요.
     * 받는 것: 샘플 이름. 돌려주는 것: 없음(실패해도 예외를 밖으로 던지지 않아요).
     */
    private void scheduled(String label) {
        if (!enabled || !kis.enabled())
            return;
        try {
            log.info("Limit-up watch: {}", sample(label));
        } catch (RuntimeException error) {
            log.warn("Limit-up watch {} failed: {}", label, error.getMessage());
        }
    }

    /**
     * 하는 일: 후보(등락률 20% 이상)를 모으고, 등락률 높은 순으로 최대 40종목의 호가를 조회해 limit_up_watch에 저장해요.
     * 받는 것: 샘플 이름(15:16 또는 15:18).
     * 돌려주는 것: Sample 요약. 거래일이 아니거나 시간 창 밖이면 0으로 채운 요약.
     */
    Sample sample(String label) {
        // 준비: 지금 시각과 오늘 날짜를 달력에서 받아, 거래일이고 15:15~15:20 창 안인지 확인해요.
        Instant now = calendar.now();
        LocalDate today = calendar.today();
        LocalTime time = now.atZone(ClosingTradingCalendar.ZONE).toLocalTime();
        if (!calendar.isTradingDay(today) || time.isBefore(WINDOW_START) || !time.isBefore(WINDOW_END))
            return new Sample(today, label, 0, 0, 0);
        // 후보 종목, 후보 정보를 받은 시각, 후보 출처(RANKING 또는 BROAD_SNAPSHOT)를 종목코드별로 모아요.
        Map<String, KisRankingEntry> candidates = new LinkedHashMap<>();
        Map<String, Instant> receivedAt = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        // 1차 출처: 시장별 상승률 순위. 한 시장이 실패해도 "시장=ERR"로 남기고 다른 시장은 계속 봐요.
        for (Market market : Market.values()) {
            List<KisRankingEntry> entries;
            try {
                entries = rankings.fetchDailyGainers(market, RANKING_LIMIT);
            } catch (RuntimeException failure) {
                rows.add(market + "=ERR");
                continue;
            }
            rows.add(market + "=" + entries.size());
            // 순위를 받은 직후 시각을 이 시장 후보들의 수신 시각으로 기록해요.
            Instant received = calendar.now();
            for (KisRankingEntry entry : entries) {
                if (entry.changeRate() == null || entry.changeRate().compareTo(MIN_CHANGE_RATE) < 0)
                    continue;
                candidates.putIfAbsent(entry.stockCode(), entry);
                receivedAt.putIfAbsent(entry.stockCode(), received);
                sources.putIfAbsent(entry.stockCode(), "RANKING");
            }
        }
        // The ranking alone missed a +29.93% stock on 2026-10-14; the market-wide snapshots of the last 10 minutes are a
        // second source. Their quote time stands in for the ranking receipt time.
        jdbc.query("""
                select distinct on (s.stock_code) s.stock_code, s.stock_name, b.current_price, b.change_rate, b.day_high,
                       b.quoted_at
                  from market_broad_snapshot b join stock s on s.id = b.stock_id
                 where b.session_date = ? and b.quoted_at >= ? and b.quoted_at <= ? and b.change_rate >= ?
                 order by s.stock_code, b.quoted_at desc""", rs -> {
                    String code = rs.getString(1);
                    if (candidates.containsKey(code)) return;
                    candidates.put(code, new KisRankingEntry(code, rs.getString(2), 0, rs.getBigDecimal(3),
                            rs.getBigDecimal(4), null, null, null, null, rs.getBigDecimal(5), null));
                    receivedAt.put(code, rs.getTimestamp(6).toInstant());
                    sources.put(code, "BROAD_SNAPSHOT");
                }, java.sql.Date.valueOf(today), Timestamp.from(now.minus(java.time.Duration.ofMinutes(10))),
                Timestamp.from(now), MIN_CHANGE_RATE);
        // 처리: 순위 조회 결과 요약(예: KOSPI=100,KOSDAQ=ERR)을 모든 행에 함께 남겨요.
        String rankingRows = String.join(",", rows);
        // 등락률 높은 순으로 정렬해요.
        List<KisRankingEntry> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator.comparing(KisRankingEntry::changeRate).reversed());
        int recorded = 0, failures = 0;
        // 15:20이 되면 남은 종목은 조회하지 않고 멈춰요(가상 진입 마감 이후 자료는 의미가 달라서).
        for (KisRankingEntry entry : ordered.subList(0, Math.min(MAX_STOCKS, ordered.size()))) {
            if (!calendar.now().atZone(ClosingTradingCalendar.ZONE).toLocalTime().isBefore(WINDOW_END))
                break;
            Book book = null;
            String error = null;
            try {
                book = books.fetch(entry.stockCode());
            } catch (RuntimeException failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                failures++;
            }
            // 결과: 호가 조회가 실패해도 오류 내용과 함께 행을 남겨, 어떤 종목을 못 봤는지 알 수 있게 해요.
            insert(today, label, entry, receivedAt.get(entry.stockCode()), book, error, sources.get(entry.stockCode()),
                    rankingRows);
            if (book != null) recorded++;
        }
        return new Sample(today, label, ordered.size(), recorded, failures);
    }

    /**
     * 하는 일: 한 종목의 샘플 한 줄을 limit_up_watch에 저장해요. 같은 날·같은 샘플·같은 종목이 이미 있으면 건너뛰어요.
     * 받는 것: 날짜, 샘플 이름, 후보 정보, 후보 수신 시각, 호가(없을 수 있음), 오류 내용, 후보 출처, 순위 조회 요약.
     * 돌려주는 것: 없음.
     */
    private void insert(LocalDate day, String label, KisRankingEntry entry, Instant rankingAt, Book book, String error,
            String source, String rankingRows) {
        Long stockId = stocks.findByStockCode(entry.stockCode()).map(stock -> stock.getId()).orElse(null);
        // 매도1호가(가장 싼 매도 주문)와 매수1호가(가장 비싼 매수 주문)를 꺼내요. 호가 원천 시각과 우리가 받은 시각을 따로 저장해요.
        Level ask = book == null ? null : book.asks().getFirst();
        Level bid = book == null ? null : book.bids().getFirst();
        jdbc.update("""
                insert into limit_up_watch(session_date, sample_label, stock_code, stock_id, ranking_received_at,
                    current_price, change_rate, day_high, book_source_at, book_received_at, best_ask, best_ask_qty,
                    best_bid, best_bid_qty, total_ask_qty, total_bid_qty, error, candidate_source, ranking_rows)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (session_date, sample_label, stock_code) do nothing""",
                Date.valueOf(day), label, entry.stockCode(), stockId, Timestamp.from(rankingAt), entry.currentPrice(),
                entry.changeRate(), entry.highPrice(),
                book == null ? null : Timestamp.from(book.sourceAt()), book == null ? null : Timestamp.from(book.receivedAt()),
                ask == null ? null : ask.price(), ask == null ? null : ask.quantity(),
                bid == null ? null : bid.price(), bid == null ? null : bid.quantity(),
                book == null ? null : total(book.asks()), book == null ? null : total(book.bids()), error, source,
                rankingRows);
    }

    /**
     * 하는 일: 호가 단계들의 잔량(남은 주문 수량)을 모두 더해요.
     * 받는 것: 호가 단계 목록. 돌려주는 것: 잔량 합계.
     */
    private static BigDecimal total(List<Level> levels) {
        return levels.stream().map(Level::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
