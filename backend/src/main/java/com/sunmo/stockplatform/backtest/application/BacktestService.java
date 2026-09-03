package com.sunmo.stockplatform.backtest.application;

import com.sunmo.stockplatform.backtest.api.BacktestDtos.BacktestResponse;
import com.sunmo.stockplatform.backtest.api.BacktestDtos.SettingSummary;
import com.sunmo.stockplatform.backtest.api.BacktestDtos.VirtualDetection;
import com.sunmo.stockplatform.backtest.api.BacktestDtos.VirtualPerformance;
import com.sunmo.stockplatform.candle.application.CandleSnapshot;
import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.scanner.application.ScannerEvaluator;
import com.sunmo.stockplatform.scanner.domain.ScannerSetting;
import com.sunmo.stockplatform.scanner.infrastructure.ScannerSettingRepository;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
@Transactional(readOnly = true)
public class BacktestService {
    private static final long OWNER = 1L;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ScannerSettingRepository settings;
    private final ScannerEvaluator evaluator;

    public BacktestService(StockRepository stocks, StockCandleRepository candles,
            ScannerSettingRepository settings, ScannerEvaluator evaluator) {
        this.stocks = stocks;
        this.candles = candles;
        this.settings = settings;
        this.evaluator = evaluator;
    }

    public BacktestResponse run(String stockCode, Instant from, Instant to, Long settingId, int limit) {
        Stock stock = stocks.findByStockCodeAndActiveTrue(stockCode)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Stock not found: " + stockCode));
        Instant warmupFrom = from.minus(Duration.ofMinutes(60));
        Instant forwardTo = to.plus(Duration.ofMinutes(70));
        List<StockCandle> series = candles
                .findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                        stock.getId(), "5M", warmupFrom, forwardTo);
        List<ScannerSetting> selectedSettings = settings.findByOwnerIdOrderById(OWNER).stream()
                .filter(ScannerSetting::isActive)
                .filter(setting -> settingId == null || Objects.equals(setting.getId(), settingId))
                .toList();
        if (selectedSettings.isEmpty()) {
            throw error(HttpStatus.NOT_FOUND, "Scanner setting not found");
        }

        List<VirtualDetection> detections = new ArrayList<>();
        int evaluated = 0;
        for (int index = 0; index < series.size(); index++) {
            StockCandle candle = series.get(index);
            if (candle.getStartTime().isBefore(from) || !candle.getStartTime().isBefore(to)
                    || !candle.isFinalCandle()) {
                continue;
            }
            evaluated++;
            List<StockCandle> previous = previous(series, index);
            if (previous.size() < 6)
                continue;
            CandleSnapshot current = snapshot(stockCode, candle);
            ScannerEvaluator.Metrics metrics = evaluator.calculate(current, previous);
            BigDecimal daily = cumulativeTradingValue(series, index, candle.getStartTime());
            MarketFeatureSnapshot feature = feature(stockCode, series, index, metrics, daily);
            for (ScannerSetting setting : selectedSettings) {
                ScannerEvaluator.Decision decision = evaluator.evaluate(setting, metrics, current, daily, feature);
                if (!decision.matched())
                    continue;
                detections.add(new VirtualDetection(
                        setting.getId(),
                        setting.getName(),
                        setting.getType().name(),
                        candle.getStartTime().plus(Duration.ofMinutes(5)),
                        candle.getClose(),
                        metrics.changeRate(),
                        metrics.volumeRatio(),
                        decision.score(),
                        decision.reasonJson(),
                        performance(series, index)));
            }
        }

        List<VirtualDetection> limited = detections.stream()
                .sorted(Comparator.comparing(VirtualDetection::detectedAt).reversed())
                .limit(Math.min(Math.max(limit, 1), 200))
                .toList();
        return new BacktestResponse(stockCode, stock.getStockName(), from, to, evaluated, detections.size(),
                summaries(detections), limited);
    }

    private List<StockCandle> previous(List<StockCandle> series, int index) {
        List<StockCandle> previous = new ArrayList<>();
        for (int i = index - 1; i >= 0 && previous.size() < 6; i--) {
            StockCandle candle = series.get(i);
            if (candle.isFinalCandle())
                previous.add(candle);
        }
        return previous;
    }

    private CandleSnapshot snapshot(String stockCode, StockCandle candle) {
        return new CandleSnapshot(stockCode, candle.getStartTime(), candle.getOpen(), candle.getHigh(), candle.getLow(),
                candle.getClose(), candle.getVolume(), candle.getTradingValue(), true, candle.getRevision());
    }

    private MarketFeatureSnapshot feature(String stockCode, List<StockCandle> series, int index,
            ScannerEvaluator.Metrics metrics, BigDecimal daily) {
        StockCandle current = series.get(index);
        LocalDate businessDate = current.getStartTime().atZone(MARKET_ZONE).toLocalDate();
        List<StockCandle> session = series.stream()
                .filter(candle -> !candle.getStartTime().isAfter(current.getStartTime()))
                .filter(candle -> candle.getStartTime().atZone(MARKET_ZONE).toLocalDate().equals(businessDate))
                .toList();
        BigDecimal vwapNumerator = session.stream()
                .map(candle -> typical(candle).multiply(BigDecimal.valueOf(candle.getVolume())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long volume = session.stream().mapToLong(StockCandle::getVolume).sum();
        BigDecimal vwap = volume == 0 ? current.getClose()
                : vwapNumerator.divide(BigDecimal.valueOf(volume), 6, RoundingMode.HALF_UP);
        BigDecimal high = session.stream().map(StockCandle::getHigh).reduce(BigDecimal::max).orElse(current.getHigh());
        BigDecimal low = session.stream().map(StockCandle::getLow).reduce(BigDecimal::min).orElse(current.getLow());
        BigDecimal vwapDistance = current.getClose().subtract(vwap)
                .divide(vwap, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal dayHighDistance = high.signum() == 0 ? BigDecimal.ZERO
                : high.subtract(current.getClose())
                        .divide(high, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(6, RoundingMode.HALF_UP);
        BigDecimal turnover = daily == null || daily.signum() == 0 ? null
                : current.getTradingValue().divide(daily, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(6, RoundingMode.HALF_UP);
        return new MarketFeatureSnapshot(stockCode, businessDate, current.getStartTime().plus(Duration.ofMinutes(5)),
                current.getClose(), volume, daily, session.getFirst().getOpen(), high, low, vwap, vwapDistance,
                null, metrics.volumeRatio(), turnover, BigDecimal.valueOf(100), 0, 0, bd("0.5"),
                dayHighDistance, false, session.getFirst().getOpen(), MarketFeatureSnapshot.VERSION);
    }

    private VirtualPerformance performance(List<StockCandle> series, int index) {
        StockCandle base = series.get(index);
        BigDecimal price5m = closeAt(series, index, 1);
        BigDecimal price30m = closeAt(series, index, 6);
        BigDecimal price60m = closeAt(series, index, 12);
        List<StockCandle> forward = forward(series, index, 12);
        BigDecimal high = forward.stream().map(StockCandle::getHigh).reduce(BigDecimal::max).orElse(null);
        BigDecimal low = forward.stream().map(StockCandle::getLow).reduce(BigDecimal::min).orElse(null);
        String status = price60m == null ? "PARTIAL" : "COMPLETED";
        return new VirtualPerformance(pct(price5m, base.getClose()), pct(price30m, base.getClose()),
                pct(price60m, base.getClose()), pct(high, base.getClose()), pct(low, base.getClose()), status);
    }

    private List<StockCandle> forward(List<StockCandle> series, int index, int count) {
        List<StockCandle> values = new ArrayList<>();
        for (int i = index + 1; i < series.size() && values.size() < count; i++) {
            if (series.get(i).isFinalCandle())
                values.add(series.get(i));
        }
        return values;
    }

    private BigDecimal closeAt(List<StockCandle> series, int index, int offset) {
        List<StockCandle> values = forward(series, index, offset);
        return values.size() < offset ? null : values.get(offset - 1).getClose();
    }

    private BigDecimal cumulativeTradingValue(List<StockCandle> series, int index, Instant at) {
        LocalDate date = at.atZone(MARKET_ZONE).toLocalDate();
        return series.subList(0, index + 1).stream()
                .filter(candle -> candle.getStartTime().atZone(MARKET_ZONE).toLocalDate().equals(date))
                .map(StockCandle::getTradingValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<SettingSummary> summaries(List<VirtualDetection> detections) {
        Map<String, List<VirtualDetection>> grouped = new LinkedHashMap<>();
        for (VirtualDetection detection : detections) {
            grouped.computeIfAbsent(
                    detection.settingId() + "|" + detection.settingName() + "|" + detection.scannerType(),
                    ignored -> new ArrayList<>()).add(detection);
        }
        return grouped.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("\\|");
            List<VirtualDetection> rows = entry.getValue();
            return new SettingSummary(
                    Long.valueOf(parts[0]),
                    parts[1],
                    parts[2],
                    rows.size(),
                    win(rows, row -> row.performance().return5m()),
                    win(rows, row -> row.performance().return30m()),
                    win(rows, row -> row.performance().return60m()),
                    avg(rows, row -> row.performance().return5m()),
                    avg(rows, row -> row.performance().return30m()),
                    avg(rows, row -> row.performance().return60m()),
                    avg(rows, row -> row.performance().maxReturn()),
                    avg(rows, row -> row.performance().maxDrawdown()));
        }).toList();
    }

    private BigDecimal typical(StockCandle candle) {
        return candle.getHigh().add(candle.getLow()).add(candle.getClose())
                .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(BigDecimal value, BigDecimal base) {
        if (value == null || base.signum() == 0)
            return null;
        return value.subtract(base).divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal avg(List<VirtualDetection> rows, Function<VirtualDetection, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal win(List<VirtualDetection> rows, Function<VirtualDetection, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        long wins = values.stream().filter(value -> value.signum() > 0).count();
        return BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private ApplicationException error(HttpStatus status, String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, status, message);
    }
}
