package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.stock.application.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

@Service
public class CandleQueryService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final StockService stocks;
    private final StockCandleRepository candles;
    private final CandleBackfillService backfill;
    private final CandleGapDetector gaps = new CandleGapDetector();
    private final RealtimeDiagnostics diagnostics;

    public CandleQueryService(StockService stocks, StockCandleRepository candles, CandleBackfillService backfill,
            RealtimeDiagnostics diagnostics) {
        this.stocks = stocks;
        this.candles = candles;
        this.backfill = backfill;
        this.diagnostics = diagnostics;
    }

    @Transactional
    public List<StockCandle> get(String stockCode, String timeframe, Instant from, Instant to) {
        var stock = stocks.getByCode(stockCode);
        if (!"5M".equals(timeframe))
            return candles.findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(stock.getId(), timeframe,
                    from, to);
        LocalDate today = LocalDate.now(SEOUL);
        Instant now = Instant.now();
        if (to.isAfter(today.atStartOfDay(SEOUL).toInstant())
                && now.atZone(SEOUL).toLocalTime().isAfter(LocalTime.of(9, 5))) {
            Instant open = today.atTime(9, 0).atZone(SEOUL).toInstant();
            Instant close = today.atTime(15, 30).atZone(SEOUL).toInstant();
            List<StockCandle> existing = candles
                    .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                            stock.getId(), "5M", open, close);
            List<Instant> missing = gaps.gaps(today, now, existing.stream().map(StockCandle::getStartTime).toList());
            diagnostics.candleGaps(stockCode, missing.size());
            backfill.backfillToday(stock, missing, now);
        }
        return candles.findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(stock.getId(), timeframe, from,
                to);
    }
}
