/*
 * 차트용 봉 목록을 DB에서 읽어 주는 조회 서비스예요.
 * 오늘 5분봉을 장중(09:05 이후)에 조회하면, 빈 칸을 찾아 KIS 과거 분봉으로 보충을 시도한 뒤 결과를 돌려줘요.
 * CandleController(GET /api/v1/stocks/{종목코드}/candles)가 사용해요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.stock.application.StockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 하는 일: 종목 조회, 봉 레포지토리, 분봉 보충 서비스, 진단 기록부를 주입받아요(의존성 주입). */
    public CandleQueryService(StockService stocks, StockCandleRepository candles, CandleBackfillService backfill,
            RealtimeDiagnostics diagnostics) {
        this.stocks = stocks;
        this.candles = candles;
        this.backfill = backfill;
        this.diagnostics = diagnostics;
    }

    /**
     * 하는 일: 종목의 봉을 [from, to] 구간으로 조회해요. 오늘 5분봉이 포함되면 먼저 빈 칸 보충을 시도해요.
     * 받는 것: 종목코드, 봉 단위(예: "5M", "1D"), 시작·끝 시각.
     * 돌려주는 것: 시작시각 순 봉 목록. 트랜잭션(여러 DB 작업을 한 묶음으로 처리하는 단위) 안에서 실행돼요.
     * ⚠️ 확인 필요: 거래일인지 확인하지 않아 휴장일·주말에도 09:05 이후 오늘 구간을 조회하면 빈 칸 보충(KIS 호출)을 시도해요.
     * 또 KIS 호출이 DB 트랜잭션 안에서 일어나 응답이 느리면 트랜잭션이 길어질 수 있어요.
     */
    @Transactional
    public List<StockCandle> get(String stockCode, String timeframe, Instant from, Instant to) {
        var stock = stocks.getByCode(stockCode);
        // 5분봉이 아니면(일봉 등) 보충 없이 그대로 읽어요.
        if (!"5M".equals(timeframe))
            return candles.findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(stock.getId(), timeframe,
                    from, to);
        LocalDate today = LocalDate.now(SEOUL);
        Instant now = Instant.now();
        // 조회 구간이 오늘까지 걸치고 첫 칸(09:00~09:05)이 끝난 뒤라면, 오늘 빈 칸을 찾아 보충을 시도해요.
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
        // 보충 결과까지 반영된 목록을 다시 읽어 돌려줘요.
        return candles.findByStockIdAndTimeframeAndStartTimeBetweenOrderByStartTimeAsc(stock.getId(), timeframe, from,
                to);
    }
}
