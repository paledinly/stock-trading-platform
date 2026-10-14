/*
 * KIS에서 받은 일봉(1D)을 stock_candle 테이블에 저장하고, 추천 계산에 필요한 일봉이 다 모였는지 확인해요.
 * 이미 있는 날짜는 건드리지 않고 빠진 날짜만 BACKFILL 출처로 추가해요.
 * DailyCandleBackfillService가 KIS 일봉을 받은 뒤 이 클래스로 저장·준비 여부를 판단해요.
 */
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

/**
 * 일봉 보관 담당이에요. 달력 앨범처럼 날짜 칸이 비어 있는 곳에만 새 사진(일봉)을 끼우고, 이미 있는 사진은 바꾸지 않아요.
 * 일봉 시작시각은 그날 서울 자정(00:00)으로 저장해요.
 */
@Service
public class DailyCandlePersistence {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final StockCandleRepository candles;

    /** 하는 일: 봉 레포지토리(DB 창구)를 주입받아요. */
    public DailyCandlePersistence(StockCandleRepository candles) { this.candles = candles; }

    /**
     * 하는 일: 받은 일봉 중 [from, through] 기간 안이면서 DB에 없는 날짜만 새로 저장해요.
     * 받는 것: 종목, 시작일, 마지막일, KIS에서 받은 일봉 목록.
     * 돌려주는 것: 새로 저장한 일봉 개수.
     */
    @Transactional
    public int saveMissing(Stock stock, LocalDate from, LocalDate through, List<DailyCandle> received) {
        // 준비: 기간 안에 이미 저장된 일봉 날짜를 모아요.
        Instant start = from.atStartOfDay(SEOUL).toInstant();
        Instant end = through.plusDays(1).atStartOfDay(SEOUL).toInstant();
        Set<Instant> existing = new HashSet<>();
        candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                stock.getId(), "1D", start, end).forEach(row -> existing.add(row.getStartTime()));
        // 처리: 기간 밖이거나 이미 있는(또는 받은 목록 안에서 중복된) 날짜는 건너뛰고 나머지만 확정 BACKFILL 일봉으로 만들어요.
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

    /**
     * 하는 일: 필요한 마지막 날짜까지 일봉이 준비됐는지 확인해요.
     * 받는 것: 종목, 반드시 있어야 하는 마지막 날짜(보통 평가일 직전 거래일).
     * 돌려주는 것: 그 날짜 이하 확정 일봉이 61개 이상이고 가장 최근 일봉이 바로 그 날짜면 true.
     */
    @Transactional(readOnly = true)
    public boolean ready(Stock stock, LocalDate latestRequiredDate) {
        Instant through = latestRequiredDate.atStartOfDay(SEOUL).toInstant();
        List<StockCandle> rows = candles
                .findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                        stock.getId(), "1D", through);
        return rows.size() >= 61 && rows.getFirst().getStartTime().equals(through);
    }
}
