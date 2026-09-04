package com.sunmo.stockplatform.candle.infrastructure;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.stock.domain.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface StockCandleRepository extends JpaRepository<StockCandle, Long> {
    List<StockCandle> findTop6ByStockIdAndTimeframeAndStartTimeBeforeOrderByStartTimeDesc(Long stockId,
            String timeframe, Instant startTime);

    Optional<StockCandle> findByStockIdAndTimeframeAndStartTime(Long stockId, String timeframe, Instant startTime);

    List<StockCandle> findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(Long stockId, String timeframe,
            Instant from, Instant to);

    List<StockCandle> findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
            Long stockId, String timeframe, Instant from, Instant to);

    List<StockCandle> findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
            Long stockId, String timeframe, Instant startTime);

    @Query("""
            select s.stockCode as stockCode,
                   s.stockName as stockName,
                   s.market as market,
                   count(c.id) as candleCount,
                   min(c.startTime) as firstCandleAt,
                   max(c.startTime) as lastCandleAt
              from StockCandle c
              join c.stock s
             where c.timeframe = :timeframe
               and c.finalCandle = true
               and s.active = true
             group by s.stockCode, s.stockName, s.market
             order by max(c.startTime) desc, count(c.id) desc, s.stockCode asc
            """)
    List<BacktestableStockRow> findBacktestableStocks(@Param("timeframe") String timeframe);

    interface BacktestableStockRow {
        String getStockCode();

        String getStockName();

        Market getMarket();

        long getCandleCount();

        Instant getFirstCandleAt();

        Instant getLastCandleAt();
    }
}
