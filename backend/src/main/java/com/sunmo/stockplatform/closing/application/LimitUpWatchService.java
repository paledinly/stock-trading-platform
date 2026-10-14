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
 * Research collector (audit/2026-10-14-limit-up-entry): after the 15:15 evaluation and before the 15:20 entry
 * deadline, records every stock up 20%+ in the KIS fluctuation ranking with its 10-level order book, so a later
 * study can tell whether a near-limit stock could actually be bought. Read-only toward the strategy.
 */
@Service
public class LimitUpWatchService {
    private static final Logger log = LoggerFactory.getLogger(LimitUpWatchService.class);
    static final BigDecimal MIN_CHANGE_RATE = new BigDecimal("20");
    static final int RANKING_LIMIT = 100;
    static final int MAX_STOCKS = 40;
    static final LocalTime WINDOW_START = LocalTime.of(15, 15);
    static final LocalTime WINDOW_END = LocalTime.of(15, 20);

    public record Sample(LocalDate sessionDate, String label, int candidates, int books, int failures) { }

    /** Fetches a 10-level order book; separate from the HTTP call so tests can supply books directly. */
    public interface BookSource { Book fetch(String stockCode); }

    private final boolean enabled;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final MarketRankingProvider rankings;
    private final StockRepository stocks;
    private final JdbcTemplate jdbc;
    private final BookSource books;

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
            if (response == null || !"0".equals(response.path("rt_cd").asText()))
                KisResponseErrors.failure("KIS orderbook failed", response == null ? "EMPTY" : response.path("msg_cd").asText(),
                        "Orderbook unavailable");
            return OrderbookCollector.parse(code, response.path("output1"), calendar.now());
        }));
    }

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

    @Scheduled(cron = "15 16 15 * * MON-FRI", zone = "Asia/Seoul")
    public void first() { scheduled("15:16"); }

    @Scheduled(cron = "30 18 15 * * MON-FRI", zone = "Asia/Seoul")
    public void second() { scheduled("15:18"); }

    private void scheduled(String label) {
        if (!enabled || !kis.enabled())
            return;
        try {
            log.info("Limit-up watch: {}", sample(label));
        } catch (RuntimeException error) {
            log.warn("Limit-up watch {} failed: {}", label, error.getMessage());
        }
    }

    Sample sample(String label) {
        Instant now = calendar.now();
        LocalDate today = calendar.today();
        LocalTime time = now.atZone(ClosingTradingCalendar.ZONE).toLocalTime();
        if (!calendar.isTradingDay(today) || time.isBefore(WINDOW_START) || !time.isBefore(WINDOW_END))
            return new Sample(today, label, 0, 0, 0);
        Map<String, KisRankingEntry> candidates = new LinkedHashMap<>();
        Map<String, Instant> receivedAt = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        for (Market market : Market.values()) {
            List<KisRankingEntry> entries;
            try {
                entries = rankings.fetch(RankingType.PRICE_RISE, market, RANKING_LIMIT);
            } catch (RuntimeException failure) {
                rows.add(market + "=ERR");
                continue;
            }
            rows.add(market + "=" + entries.size());
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
        String rankingRows = String.join(",", rows);
        List<KisRankingEntry> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator.comparing(KisRankingEntry::changeRate).reversed());
        int recorded = 0, failures = 0;
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
            insert(today, label, entry, receivedAt.get(entry.stockCode()), book, error, sources.get(entry.stockCode()),
                    rankingRows);
            if (book != null) recorded++;
        }
        return new Sample(today, label, ordered.size(), recorded, failures);
    }

    private void insert(LocalDate day, String label, KisRankingEntry entry, Instant rankingAt, Book book, String error,
            String source, String rankingRows) {
        Long stockId = stocks.findByStockCode(entry.stockCode()).map(stock -> stock.getId()).orElse(null);
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

    private static BigDecimal total(List<Level> levels) {
        return levels.stream().map(Level::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
