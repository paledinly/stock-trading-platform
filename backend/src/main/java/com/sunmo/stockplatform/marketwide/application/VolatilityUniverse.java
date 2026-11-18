/*
 * 장 시작 전에 정할 수 있는 시장 전체 스캔 명단(최근 20거래일 변동성 상위 300종목)을 만드는 파일이에요.
 * 순위표는 오늘 움직인 종목만 보여 줘서, 다음 날 시가에 크게 오를 종목의 대부분을 놓쳤어요.
 * 2018~2026 일봉 비교에서 "최근 변동성이 큰 종목"이 그런 종목을 가장 많이 담았어요(audit/2026-11-17-subscription-criteria).
 * BroadCandidateCollector가 하루 한 번 계산해 둔 명단을 받아 가요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.VolatilityUniverseProperties;
import com.sunmo.stockplatform.marketwide.domain.StructuredProductFilter;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장 전 변동성 명단이에요. 같은 날에는 한 번 계산한 결과를 다시 써요.
 * 계산이 실패하거나 결과가 비면 빈 목록을 돌려주고, 수집기는 기존 순위표 방식으로 돌아가요(5분 뒤 다시 시도).
 */
@Component
public class VolatilityUniverse {
    private static final Logger log = LoggerFactory.getLogger(VolatilityUniverse.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    // 날짜 d 이전 lookbackDays개 거래일의 종가 등락 표준편차와 평균 거래대금이에요. 행 수가 부족한 종목은 뺀다.
    // 창은 달력 기준 넉넉히 잡고(거래일 × 2 + 10일), 종목마다 최근 lookbackDays개 등락만 써요.
    private static final String SQL = """
            with w as (
              select stock_id, trade_date, close_price, trading_value,
                     lag(close_price) over (partition by stock_id order by trade_date) prev_close,
                     row_number() over (partition by stock_id order by trade_date desc) rn
              from daily_adjusted_candle
              where trade_date < ? and trade_date >= ?
            )
            select stock_id, stddev_samp(close_price / prev_close - 1) vol, avg(trading_value) avg_value
            from w
            where rn <= ? and prev_close > 0
            group by stock_id
            having count(*) >= ? and avg(trading_value) >= ?
            order by vol desc
            limit ?""";

    private final JdbcTemplate jdbc;
    private final StockRepository stocks;
    private final VolatilityUniverseProperties properties;
    private final Clock clock;
    private LocalDate cachedDate;
    private List<Stock> cached = List.of();
    private Instant failedAt;

    /** 하는 일: DB 조회 도구, 종목 레포지토리, 설정을 받아 둬요. */
    @org.springframework.beans.factory.annotation.Autowired
    public VolatilityUniverse(JdbcTemplate jdbc, StockRepository stocks, VolatilityUniverseProperties properties) {
        this(jdbc, stocks, properties, Clock.systemUTC());
    }

    VolatilityUniverse(JdbcTemplate jdbc, StockRepository stocks, VolatilityUniverseProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.stocks = stocks;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 하는 일: 오늘의 장 전 명단을 돌려줘요. 꺼져 있으면 빈 목록이에요.
     * 돌려주는 것: 변동성 높은 순서의 거래 가능한 보통주(최대 size개).
     */
    public synchronized List<Stock> today() {
        if (!properties.enabled())
            return List.of();
        Instant now = clock.instant();
        LocalDate date = now.atZone(SEOUL).toLocalDate();
        if (date.equals(cachedDate) && !cached.isEmpty())
            return cached;
        if (failedAt != null && date.equals(failedAt.atZone(SEOUL).toLocalDate())
                && failedAt.plus(RETRY_AFTER).isAfter(now))
            return List.of();
        try {
            List<Stock> computed = compute(date);
            if (computed.isEmpty()) {
                failedAt = now;
                log.warn("Volatility universe is empty for {}; the scan keeps the ranking candidates", date);
                return List.of();
            }
            cachedDate = date;
            cached = computed;
            failedAt = null;
            log.info("Volatility universe for {}: {} stocks", date, computed.size());
            return cached;
        } catch (RuntimeException error) {
            failedAt = now;
            log.warn("Volatility universe failed for {}: {}", date, error.getMessage());
            return List.of();
        }
    }

    /** 명단의 한 종목과 그 20일 변동성(일간 종가 등락 표준편차, 0.05는 5%)이에요. */
    public record Ranked(Stock stock, double volatility) { }

    /**
     * 하는 일: 거래일 date의 명단을 변동성 순서로 계산해요(캐시 없음, 꺼져 있어도 계산). date 이전 일봉만 써요.
     * AI 후보 평가(ADR-014)가 대상 거래일의 명단을 만들 때 써요.
     */
    public List<Ranked> ranked(LocalDate date) {
        int days = properties.lookbackDays();
        LocalDate from = date.minusDays(days * 2L + 10);
        // 거르면서 줄어들 몫을 감안해 세 배까지 받아요.
        List<Map.Entry<Long, Double>> rows = jdbc.query(SQL,
                (rs, i) -> Map.entry(rs.getLong("stock_id"), rs.getDouble("vol")), Date.valueOf(date), Date.valueOf(from),
                days, Math.max(5, days * 3 / 4), properties.minAverageTradingValue(), properties.size() * 3);
        Map<Long, Stock> byId = stocks.findAllById(rows.stream().map(Map.Entry::getKey).toList()).stream()
                .collect(Collectors.toMap(Stock::getId, Function.identity()));
        List<Ranked> result = new ArrayList<>();
        for (Map.Entry<Long, Double> row : rows) {
            Stock stock = byId.get(row.getKey());
            if (stock != null && eligible(stock))
                result.add(new Ranked(stock, row.getValue()));
            if (result.size() >= properties.size())
                break;
        }
        return List.copyOf(result);
    }

    /** 하는 일: 일봉으로 변동성 순위를 계산하고, 거래 가능한 보통주만 남겨 size개로 잘라요. */
    private List<Stock> compute(LocalDate date) {
        return ranked(date).stream().map(Ranked::stock).toList();
    }

    /**
     * 하는 일: 명단에 넣을 수 있는 종목인지 봐요. 활성, 관리·거래정지 아님, ETF·ETN 아님,
     * 구조화 상품·스팩 이름 아님, 보통주(6자리 숫자 코드, 끝자리 0)여야 해요.
     */
    public static boolean eligible(Stock stock) {
        String code = stock.getStockCode();
        String name = stock.getStockName();
        return stock.isActive() && !stock.isManaged() && !stock.isTradingHalted()
                && !stock.isEtf() && !stock.isEtn()
                && code != null && code.matches("\\d{5}0")
                && (name == null || (!name.contains("스팩") && !StructuredProductFilter.matches(name)));
    }
}
