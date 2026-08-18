package com.sunmo.stockplatform.candle.infrastructure;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
public interface StockCandleRepository extends JpaRepository<StockCandle,Long>{
 List<StockCandle> findTop6ByStockIdAndTimeframeAndStartTimeBeforeOrderByStartTimeDesc(Long stockId,String timeframe,Instant startTime);
 Optional<StockCandle> findByStockIdAndTimeframeAndStartTime(Long stockId,String timeframe,Instant startTime);
 List<StockCandle> findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(Long stockId,String timeframe,Instant from,Instant to);
}
