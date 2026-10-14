/*
 * 종목 하나의 현재가를 만들어 주는 서비스예요.
 * 오늘 받아 둔 기준 시세(캐시 또는 KIS REST)에 실시간으로 들어온 최신 체결(틱)을 합쳐 가장 최신 시세를 계산해요.
 * QuoteController(현재가 API)가 호출하고, merge()는 시장 전체 스캔(BroadQuoteResolver)에서도 써요.
 */
package com.sunmo.stockplatform.quote.application;

import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.application.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 현재가 조회를 맡은 서비스 클래스예요.
 * 아침에 받은 신문(기준 시세)에 방금 들어온 속보(실시간 틱)를 덧붙여 "지금 기준" 소식으로 정리해 주는 편집자와 같아요.
 */
@Service
public class QuoteService {
    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final StockService stockService;
    private final QuoteProvider quoteProvider;
    private final QuoteStateStore quoteStateStore;
    private final QuoteSnapshotCache snapshots;

    /**
     * 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 것)으로 종목 조회, 시세 공급자, 실시간 상태 저장소, 캐시를 받아요.
     */
    public QuoteService(StockService stockService, QuoteProvider quoteProvider, QuoteStateStore quoteStateStore,
            QuoteSnapshotCache snapshots) {
        this.stockService = stockService;
        this.quoteProvider = quoteProvider;
        this.quoteStateStore = quoteStateStore;
        this.snapshots = snapshots;
    }

    /**
     * 종목의 최신 현재가를 돌려줘요.
     * 받는 것: 종목 코드. 돌려주는 것: 기준 시세에 최신 틱을 합친 StockQuote.
     * 기준 시세 조회가 실패해도 틱이 있으면 틱만으로 만든 임시 시세를 돌려주고, 틱도 없으면 예외를 그대로 던져요.
     */
    public StockQuote getQuote(String stockCode) {
        // Viewing a quote no longer takes a realtime slot: every free slot goes to precision (ADR-010).
        // A stock that is already subscribed still merges its latest tick below.
        // (한국어) 현재가를 본다고 실시간 구독 슬롯을 쓰지 않아요. 남는 슬롯은 모두 정밀 구독용이에요(ADR-010).
        // 이미 구독 중인 종목이면 아래에서 최신 틱을 합쳐요.
        var stock = stockService.getByCode(stockCode);
        MarketTick tick = quoteStateStore.get(stockCode).orElse(null);
        StockQuote baseline = snapshots.today(stockCode, Instant.now()).orElse(null);
        boolean realBaseline = true;
        // 오늘 기준 시세가 캐시에 없을 때만 KIS REST를 불러 호출 수를 아껴요.
        if (baseline == null || !isToday(baseline.quotedAt())) {
            try {
                baseline = quoteProvider.getQuote(stock);
            } catch (RuntimeException error) {
                if (tick == null)
                    throw error;
                log.warn("Using realtime-only quote for {} because baseline quote failed: {}",
                        stockCode, error.getMessage());
                // 틱만으로 만든 시세는 전일 대비 값이 0이라 믿을 수 없어서 캐시에 넣지 않아요.
                baseline = fromTick(stockCode, stock.getStockName(), stock.getMarket().name(), tick);
                realBaseline = false;
            }
        }
        StockQuote result = tick == null ? baseline : merge(baseline, tick);
        if (realBaseline) snapshots.remember(result);
        return result;
    }

    /**
     * 이전 시세에 새 틱을 합쳐 새 시세를 만들어요.
     * 받는 것: 이전 시세, 실시간 틱. 돌려주는 것: 틱이 더 늦으면 합친 새 시세, 아니면 이전 시세 그대로.
     * 늦게 도착한 오래된 틱이 최신 값을 덮어쓰지 않도록 시각을 먼저 비교해요.
     */
    public static StockQuote merge(StockQuote previous, MarketTick tick) {
        if (!tick.occurredAt().isAfter(previous.quotedAt()))
            return previous;
        BigDecimal current = tick.price();
        // 이전 시세의 "현재가 - 전일 대비"로 전일 종가를 거꾸로 구해, 새 현재가 기준 등락을 다시 계산해요.
        BigDecimal previousClose = previous.currentPrice().subtract(previous.change());
        BigDecimal change = current.subtract(previousClose);
        BigDecimal changeRate = previousClose.signum() == 0 ? BigDecimal.ZERO
                : change.divide(previousClose, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
        // 시가/고가/저가는 0이나 빈 값을 무시하고, 고가는 더 큰 쪽·저가는 더 작은 쪽을 골라요.
        BigDecimal open = positiveOr(tick.openPrice(), positiveOr(previous.openPrice(), current));
        BigDecimal high = positiveOr(previous.highPrice(), current).max(positiveOr(tick.highPrice(), current)).max(current);
        BigDecimal low = positiveOr(previous.lowPrice(), current).min(positiveOr(tick.lowPrice(), current)).min(current);
        // 누적 거래량/거래대금은 줄어들 수 없으니 둘 중 큰 값을 써요.
        long volume = Math.max(previous.accumulatedVolume(), tick.cumulativeVolume());
        BigDecimal tradingValue = max(previous.accumulatedTradingValue(), tick.cumulativeTradingValue());
        Instant quotedAt = tick.occurredAt().isAfter(previous.quotedAt()) ? tick.occurredAt() : previous.quotedAt();
        return new StockQuote(previous.stockCode(), previous.stockName(), previous.market(), current, change,
                changeRate,
                open, high, low, volume, tradingValue, quotedAt);
    }

    /**
     * 기준 시세 없이 틱 하나만으로 임시 시세를 만들어요.
     * 받는 것: 종목 코드·이름·시장, 틱. 돌려주는 것: 시가/고가/저가를 모두 틱 가격으로 둔 StockQuote.
     * 전일 대비 금액과 등락률은 알 수 없어서 0으로 채워요.
     */
    private static StockQuote fromTick(String code, String name, String market, MarketTick tick) {
        return new StockQuote(code, name, market, tick.price(), BigDecimal.ZERO, BigDecimal.ZERO,
                tick.price(), tick.price(), tick.price(), tick.cumulativeVolume(), tick.cumulativeTradingValue(),
                tick.occurredAt());
    }

    /** 시각이 서울 기준 오늘 날짜인지 확인해요. 받는 것: 시각(null 가능). 돌려주는 것: 오늘이면 true. */
    private static boolean isToday(Instant timestamp) {
        return timestamp != null && timestamp.atZone(SEOUL).toLocalDate().equals(LocalDate.now(SEOUL));
    }

    /** 값이 0보다 크면 그 값을, 아니면(없거나 0 이하) 대신 쓸 값(fallback)을 돌려줘요. */
    private static BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }

    /** null을 허용하는 최댓값 계산이에요. 한쪽만 있으면 그 값을, 둘 다 없으면 0을 돌려줘요. */
    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null)
            return right == null ? BigDecimal.ZERO : right;
        if (right == null)
            return left;
        return left.max(right);
    }
}
