/*
 * 차트용 봉 목록을 DB에서 읽어 주는 조회 서비스예요.
 * 오늘 5분봉을 장중(09:05 이후)에 조회하면, 빈 칸을 찾아 KIS 과거 분봉으로 보충을 시도한 뒤 결과를 돌려줘요.
 * CandleController(GET /api/v1/stocks/{종목코드}/candles)가 사용해요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.stock.application.StockService;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;

/**
 * 봉 조회 창구예요. 사진첩을 보여 주기 전에 오늘 페이지에 빠진 사진이 있으면 먼저 다른 곳(KIS)에서 구해 끼워 넣으려는 사서와 비슷해요.
 * 보충한 봉은 출처가 BACKFILL로 남아 실시간 봉과 구분돼요.
 */
@Service
public class CandleQueryService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final StockService stocks;
    private final StockCandleRepository candles;
    private final CandleBackfillService backfill;
    private final CandleGapDetector gaps = new CandleGapDetector();
    private final RealtimeDiagnostics diagnostics;
    private final ClosingTradingCalendar calendar;

    /** 하는 일: 종목 조회, 봉 레포지토리, 분봉 보충 서비스, 진단 기록부, 거래일 달력을 주입받아요(의존성 주입). */
    public CandleQueryService(StockService stocks, StockCandleRepository candles, CandleBackfillService backfill,
            RealtimeDiagnostics diagnostics, ClosingTradingCalendar calendar) {
        this.stocks = stocks;
        this.candles = candles;
        this.backfill = backfill;
        this.diagnostics = diagnostics;
        this.calendar = calendar;
    }

    /**
     * 하는 일: 종목의 봉을 [from, to] 구간으로 조회해요. 오늘 5분봉이 포함되면 먼저 빈 칸 보충을 시도해요.
     * 받는 것: 종목코드, 봉 단위(예: "5M", "1D"), 시작·끝 시각.
     * 돌려주는 것: 시작시각 순 봉 목록.
     * 오늘이 ClosingTradingCalendar 기준 거래일이 아니면(주말·휴장일) 빈 칸 보충(KIS 호출)을 하지 않아요.
     * 이 메서드는 트랜잭션을 열지 않아요. 각 조회는 레포지토리 단위로 실행되고, 보충 서비스는 KIS 호출을
     * 트랜잭션 밖에서 한 뒤 저장만 짧은 트랜잭션으로 묶어요(느린 KIS 응답이 DB 연결을 붙잡지 않게 해요).
     */
    public List<StockCandle> get(String stockCode, String timeframe, Instant from, Instant to) {
        var stock = stocks.getByCode(stockCode);
        // 5분봉이 아니면(일봉 등) 보충 없이 그대로 읽어요.
        if (!"5M".equals(timeframe))
            return candles.findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(stock.getId(), timeframe,
                    from, to);
        Instant now = calendar.now();
        LocalDate today = now.atZone(SEOUL).toLocalDate();
        // 오늘이 거래일이고, 조회 구간이 오늘까지 걸치며, 첫 칸(09:00~09:05)이 끝난 뒤라면 오늘 빈 칸을 찾아 보충을 시도해요.
        if (calendar.isTradingDay(today) && to.isAfter(today.atStartOfDay(SEOUL).toInstant())
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
        // 보충 결과까지 반영된 목록을 다시 읽어 돌려줘요.
        return candles.findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(stock.getId(), timeframe, from,
                to);
    }
}
