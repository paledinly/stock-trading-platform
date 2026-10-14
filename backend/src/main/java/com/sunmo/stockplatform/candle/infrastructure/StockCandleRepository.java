/*
 * stock_candle 테이블을 읽고 쓰는 레포지토리(DB 테이블을 객체처럼 조회·저장하게 해 주는 창구)예요.
 * 메서드 이름 규칙으로 Spring Data JPA가 SQL을 자동으로 만들어 주고, 백테스트 가능 종목 집계는 직접 쓴 쿼리를 써요.
 * 실시간 저장(MarketDataService), 보충, 조회(CandleQueryService), Scanner, 백테스트(BacktestService) 등이 사용해요.
 */
package com.sunmo.stockplatform.candle.infrastructure;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.stock.domain.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

/**
 * 봉 보관 창고의 출납 담당이에요. "이 종목, 이 단위, 이 기간의 봉을 꺼내 줘" 같은 요청을 받아 꺼내 주거나 새로 넣어요.
 */
public interface StockCandleRepository extends JpaRepository<StockCandle, Long> {
    /** 하는 일: 기준 시각보다 앞선 봉 중 최근 6개를 최신순으로 가져와요(ScannerEngine이 직전 봉 기준값 계산에 써요). */
    List<StockCandle> findTop6ByStockIdAndTimeframeAndStartTimeBeforeOrderByStartTimeDesc(Long stockId,
            String timeframe, Instant startTime);

    /** 하는 일: 종목·봉 단위·시작시각이 정확히 같은 봉 하나를 찾아요. 없으면 빈 Optional. */
    Optional<StockCandle> findByStockIdAndTimeframeAndStartTime(Long stockId, String timeframe, Instant startTime);

    /** 하는 일: [from, to] 양끝 포함 구간의 봉을 오래된 순으로 가져와요. */
    List<StockCandle> findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(Long stockId, String timeframe,
            Instant from, Instant to);

    /** 하는 일: [from, to) 구간(끝 제외)의 봉을 오래된 순으로 가져와요. */
    List<StockCandle> findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
            Long stockId, String timeframe, Instant from, Instant to);

    /** 하는 일: 기준 시각 이하의 확정봉 중 최근 61개를 최신순으로 가져와요(일봉 준비 완료 판단 등에 써요). */
    List<StockCandle> findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
            Long stockId, String timeframe, Instant startTime);

    /**
     * 하는 일: 확정봉이 있는 활성 종목별로 봉 개수와 첫·마지막 봉 시각을 집계해요.
     * 받는 것: 봉 단위. 돌려주는 것: 최근 봉이 있는 종목 → 봉이 많은 종목 → 종목코드 순 목록.
     * 백테스트 화면에서 고를 수 있는 종목 목록을 만드는 데 써요.
     */
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

    /** findBacktestableStocks 결과 한 줄이에요. 종목 정보와 봉 개수, 첫·마지막 봉 시각을 담아요. */
    interface BacktestableStockRow {
        String getStockCode();

        String getStockName();

        Market getMarket();

        long getCandleCount();

        Instant getFirstCandleAt();

        Instant getLastCandleAt();
    }
}
