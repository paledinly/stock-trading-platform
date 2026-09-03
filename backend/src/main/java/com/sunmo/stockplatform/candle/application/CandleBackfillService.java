package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.config.CandleBackfillProperties;
import com.sunmo.stockplatform.candle.domain.*;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.kis.candle.*;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CandleBackfillService {
    private static final Logger log = LoggerFactory.getLogger(CandleBackfillService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final KisMinuteCandleClient client;
    private final StockCandleRepository repository;
    private final CandleBackfillProperties properties;
    private final RealtimeDiagnostics diagnostics;
    private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();

    public CandleBackfillService(KisMinuteCandleClient client, StockCandleRepository repository,
            CandleBackfillProperties properties, RealtimeDiagnostics diagnostics) {
        this.client = client;
        this.repository = repository;
        this.properties = properties;
        this.diagnostics = diagnostics;
    }

    @Transactional
    public int backfillToday(Stock stock, List<Instant> gaps, Instant now) {
        if (!properties.enabled() || gaps.isEmpty() || throttled(stock.getStockCode(), now))
            return 0;
        lastAttempt.put(stock.getStockCode(), now);
        diagnostics.backfillStarted(stock.getStockCode(), gaps.size());
        try {
            Map<Instant, MinuteCandle> minutes = fetchPages(stock.getStockCode(), gaps);
            int saved = 0;
            for (FiveMinuteBackfill candle : aggregate(minutes.values())) {
                if (!gaps.contains(candle.startTime()))
                    continue;
                StockCandle entity = repository
                        .findByStockIdAndTimeframeAndStartTime(stock.getId(), "5M", candle.startTime())
                        .orElseGet(() -> new StockCandle(stock, candle.startTime(), candle.open(), candle.high(),
                                candle.low(), candle.close(), candle.volume(), candle.tradingValue(), true, 0,
                                CandleSource.BACKFILL));
                if (entity.getSource() == CandleSource.REALTIME && entity.isFinalCandle())
                    continue;
                entity.revise(candle.open(), candle.high(), candle.low(), candle.close(), candle.volume(),
                        candle.tradingValue(), true, 0, CandleSource.BACKFILL);
                repository.save(entity);
                saved++;
            }
            diagnostics.backfillSucceeded(stock.getStockCode(), gaps.size(), saved);
            return saved;
        } catch (RuntimeException error) {
            diagnostics.backfillFailed(stock.getStockCode(), gaps.size(), rootMessage(error));
            log.warn("Candle backfill failed for {}: {}", stock.getStockCode(), rootMessage(error));
            return 0;
        }
    }

    private boolean throttled(String code, Instant now) {
        Instant previous = lastAttempt.get(code);
        return previous != null && now.isBefore(previous.plus(properties.refreshInterval()));
    }

    private Map<Instant, MinuteCandle> fetchPages(String code, List<Instant> gaps) {
        Instant earliest = Collections.min(gaps).minus(Duration.ofMinutes(1));
        LocalTime cursor = Collections.max(gaps).plus(Duration.ofMinutes(5)).atZone(SEOUL).toLocalTime();
        Map<Instant, MinuteCandle> result = new TreeMap<>();
        for (int request = 0; request < properties.maxRequestsPerQuery(); request++) {
            List<MinuteCandle> page = client.fetch(code, cursor);
            if (page.isEmpty())
                break;
            page.forEach(candle -> result.put(candle.startTime(), candle));
            Instant oldest = page.stream().map(MinuteCandle::startTime).min(Comparator.naturalOrder()).orElseThrow();
            if (!oldest.isAfter(earliest))
                break;
            cursor = oldest.atZone(SEOUL).toLocalTime().minusMinutes(1);
        }
        return result;
    }

    public static List<FiveMinuteBackfill> aggregate(Collection<MinuteCandle> input) {
        List<MinuteCandle> sorted = input.stream().sorted(Comparator.comparing(MinuteCandle::startTime)).toList();
        Map<Instant, List<MinuteCandle>> buckets = new LinkedHashMap<>();
        for (MinuteCandle minute : sorted) {
            Instant bucket = CandleGapDetector.floorFiveMinutes(minute.startTime());
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(minute);
        }
        List<FiveMinuteBackfill> result = new ArrayList<>();
        BigDecimal priorCumulative = null;
        for (var entry : buckets.entrySet()) {
            List<MinuteCandle> rows = entry.getValue();
            BigDecimal open = rows.getFirst().open();
            BigDecimal close = rows.getLast().close();
            BigDecimal high = rows.stream().map(MinuteCandle::high).max(Comparator.naturalOrder()).orElseThrow();
            BigDecimal low = rows.stream().map(MinuteCandle::low).min(Comparator.naturalOrder()).orElseThrow();
            long volume = rows.stream().mapToLong(MinuteCandle::volume).sum();
            BigDecimal lastCumulative = rows.getLast().cumulativeTradingValue();
            boolean openingBucket = entry.getKey().atZone(SEOUL).toLocalTime().equals(LocalTime.of(9, 0));
            BigDecimal value = lastCumulative != null && priorCumulative != null
                    && lastCumulative.compareTo(priorCumulative) >= 0
                            ? lastCumulative.subtract(priorCumulative)
                            : openingBucket && lastCumulative != null ? lastCumulative
                                    : rows.stream().map(row -> row.close().multiply(BigDecimal.valueOf(row.volume())))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new FiveMinuteBackfill(entry.getKey(), open, high, low, close, volume, value));
            if (lastCumulative != null)
                priorCumulative = lastCumulative;
        }
        return result;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record FiveMinuteBackfill(Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, long volume, BigDecimal tradingValue) {
    }
}
