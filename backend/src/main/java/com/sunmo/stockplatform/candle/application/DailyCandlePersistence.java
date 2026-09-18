package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.domain.CandleSource;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.kis.candle.DailyCandle;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DailyCandlePersistence {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final StockCandleRepository candles;

    public DailyCandlePersistence(StockCandleRepository candles) { this.candles = candles; }

    @Transactional
    public int saveMissing(Stock stock, LocalDate from, LocalDate through, List<DailyCandle> received) {
        Instant start = from.atStartOfDay(SEOUL).toInstant();
        Instant end = through.plusDays(1).atStartOfDay(SEOUL).toInstant();
        Set<Instant> existing = new HashSet<>();
        candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                stock.getId(), "1D", start, end).forEach(row -> existing.add(row.getStartTime()));
        List<StockCandle> additions = new ArrayList<>();
        for (DailyCandle row : received) {
            if (row.date().isBefore(from) || row.date().isAfter(through)) continue;
            Instant bucket = row.date().atStartOfDay(SEOUL).toInstant();
            if (!existing.add(bucket)) continue;
            additions.add(new StockCandle(stock, "1D", bucket, row.open(), row.high(), row.low(), row.close(),
                    row.volume(), row.tradingValue(), true, 0, CandleSource.BACKFILL));
        }
        candles.saveAll(additions);
        return additions.size();
    }

    @Transactional(readOnly = true)
    public boolean ready(Stock stock, LocalDate latestRequiredDate) {
        Instant through = latestRequiredDate.atStartOfDay(SEOUL).toInstant();
        List<StockCandle> rows = candles
                .findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                        stock.getId(), "1D", through);
        return rows.size() >= 61 && rows.getFirst().getStartTime().equals(through);
    }
}
