/*
 * 저장된 확정 5분봉을 시간순으로 다시 훑으며 Scanner 설정이 어디서 탐지했을지 흉내 내는 백테스트 서비스예요.
 * 봉마다 직전 6개 봉으로 지표를 계산해 Scanner 판단을 돌리고, 탐지되면 이후 5·30·60분 가상 성과를 계산해 설정별로 요약해요.
 * BacktestController(GET /api/v1/backtests/run)가 사용해요. 결과는 가상 탐지이며 수익성 검증이 아니에요.
 */
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

/**
 * 봉 데이터 되감기 재생기예요.
 * 경기 녹화본을 한 장면(봉)씩 넘기며 "이 시점에 Scanner 규칙이 신호를 냈을까, 냈다면 이후 가격은 어떻게 움직였나"를 기록해요.
 * 실시간 Feature 일부는 봉만으로 알 수 없어 근사값이나 고정값으로 채우므로, 실시간 결과와 똑같지 않을 수 있어요.
 * 출처를 가리지 않아 BACKFILL 봉(실시간 당시 수신을 증명하지 못하는 보충 봉)도 함께 쓰여요.
 * 클래스 전체가 읽기 전용 트랜잭션(여러 DB 작업을 한 묶음으로 처리하는 단위)으로 실행돼요.
 */
@Service
@Transactional(readOnly = true)
public class BacktestService {
    private static final long OWNER = 1L;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ScannerSettingRepository settings;
    private final ScannerEvaluator evaluator;

    /** 하는 일: 종목·봉·Scanner 설정 레포지토리(DB 창구)와 Scanner 판단기를 주입받아요(의존성 주입). */
    public BacktestService(StockRepository stocks, StockCandleRepository candles,
            ScannerSettingRepository settings, ScannerEvaluator evaluator) {
        this.stocks = stocks;
        this.candles = candles;
        this.settings = settings;
        this.evaluator = evaluator;
    }

    /**
     * 하는 일: 한 종목의 [from, to) 구간 확정 5분봉마다 Scanner 설정을 평가해 가상 탐지와 성과를 만들어요.
     * 받는 것: 종목코드, 시작·끝 시각, 설정 ID(없으면 사용자 1번의 활성 설정 전체), 돌려줄 탐지 최대 수.
     * 돌려주는 것: 평가 봉 수, 전체 가상 탐지 수, 설정별 요약, 최신순 가상 탐지(최대 limit개, 1~200).
     * 종목이나 설정이 없으면 404 오류를 던져요.
     */
    public BacktestResponse run(String stockCode, Instant from, Instant to, Long settingId, int limit) {
        // 준비: 직전 봉 계산용으로 60분 앞, 성과 계산용으로 70분 뒤까지 봉을 넉넉히 읽고, 평가할 설정을 골라요.
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

        // 처리: 구간 안의 확정봉마다 직전 확정봉 6개가 있을 때만 지표를 계산하고 설정별로 판단해요.
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
                // 탐지 시각은 봉이 끝난 시각(시작 + 5분), 탐지 가격은 그 봉의 종가로 기록해요.
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

        // 결과: 최신 탐지부터 limit개만 돌려주고, 요약은 전체 탐지로 계산해요.
        List<VirtualDetection> limited = detections.stream()
                .sorted(Comparator.comparing(VirtualDetection::detectedAt).reversed())
                .limit(Math.min(Math.max(limit, 1), 200))
                .toList();
        return new BacktestResponse(stockCode, stock.getStockName(), from, to, evaluated, detections.size(),
                summaries(detections), limited);
    }

    /**
     * 하는 일: index 바로 앞의 확정봉을 최신순으로 최대 6개 모아요. 날짜가 바뀌어도 이어서 모아요.
     * 받는 것: 봉 목록, 기준 위치. 돌려주는 것: 직전 확정봉 목록(최신순).
     */
    private List<StockCandle> previous(List<StockCandle> series, int index) {
        List<StockCandle> previous = new ArrayList<>();
        for (int i = index - 1; i >= 0 && previous.size() < 6; i--) {
            StockCandle candle = series.get(i);
            if (candle.isFinalCandle())
                previous.add(candle);
        }
        return previous;
    }

    /** 하는 일: DB 봉을 Scanner가 받는 확정 CandleSnapshot 모양으로 바꿔요. */
    private CandleSnapshot snapshot(String stockCode, StockCandle candle) {
        return new CandleSnapshot(stockCode, candle.getStartTime(), candle.getOpen(), candle.getHigh(), candle.getLow(),
                candle.getClose(), candle.getVolume(), candle.getTradingValue(), true, candle.getRevision());
    }

    /**
     * 하는 일: 봉 데이터만으로 실시간 Feature 스냅샷을 비슷하게 만들어요.
     * VWAP은 당일 봉들의 대표가격((고+저+종)/3) × 거래량으로 근사하고, 고가·저가는 당일 봉에서 구해요.
     * 체결강도 100, 매수비율 0.5, 매수·매도 증분 0, 거래정지 false, VWAP 기울기 null은 봉에 없는 값이라 고정값으로 넣어요.
     * 받는 것: 종목코드, 봉 목록, 기준 위치, Scanner 지표, 당일 누적 거래대금. 돌려주는 것: 근사 Feature.
     * ⚠️ 확인 필요: turnoverRatio에 "이 봉 거래대금 ÷ 당일 누적 거래대금 × 100(%)"을 넣는데,
     * 실시간 turnoverRatio는 "5분 거래대금 ÷ 직전 6칸 평균"(배수)이라 뜻이 달라요. TURNOVER_BREAKOUT 판단이 실시간과 다르게 나올 수 있어요.
     * ⚠️ 확인 필요: viStandardPrice(VI 기준가) 자리에 당일 첫 봉 시가를 넣어요. 실제 VI 기준가가 아니에요.
     */
    private MarketFeatureSnapshot feature(String stockCode, List<StockCandle> series, int index,
            ScannerEvaluator.Metrics metrics, BigDecimal daily) {
        // 준비: 기준 봉과 같은 날짜이면서 기준 봉 이전(포함)인 봉들만 모아요(미래 봉을 쓰지 않게).
        StockCandle current = series.get(index);
        LocalDate businessDate = current.getStartTime().atZone(MARKET_ZONE).toLocalDate();
        List<StockCandle> session = series.stream()
                .filter(candle -> !candle.getStartTime().isAfter(current.getStartTime()))
                .filter(candle -> candle.getStartTime().atZone(MARKET_ZONE).toLocalDate().equals(businessDate))
                .toList();
        // 처리: 근사 VWAP, 당일 고가·저가, VWAP 거리, 고가 대비 거리, 거래대금 비중을 계산해요.
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
        // 결과: 관측 시각을 봉 끝(시작 + 5분)으로 둔 스냅샷을 만들어요.
        return new MarketFeatureSnapshot(stockCode, businessDate, current.getStartTime().plus(Duration.ofMinutes(5)),
                current.getClose(), volume, daily, session.getFirst().getOpen(), high, low, vwap, vwapDistance,
                null, metrics.volumeRatio(), turnover, BigDecimal.valueOf(100), 0, 0, bd("0.5"),
                dayHighDistance, false, session.getFirst().getOpen(), MarketFeatureSnapshot.VERSION);
    }

    /**
     * 하는 일: 탐지 봉 종가를 기준으로 이후 1·6·12번째 확정봉 종가 수익률과, 이후 12개 봉의 최고가·최저가 수익률을 계산해요.
     * 받는 것: 봉 목록, 탐지 봉 위치. 돌려주는 것: 가상 성과(12번째 봉이 없으면 PARTIAL).
     * ⚠️ 확인 필요: 시간이 아니라 "다음 확정봉 개수"로 세서, 빈 봉이 있거나 장 마감을 넘기면(다음 날 봉) 실제 30·60분과 달라질 수 있어요.
     */
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

    /** 하는 일: index 뒤의 확정봉을 시간순으로 최대 count개 모아요. */
    private List<StockCandle> forward(List<StockCandle> series, int index, int count) {
        List<StockCandle> values = new ArrayList<>();
        for (int i = index + 1; i < series.size() && values.size() < count; i++) {
            if (series.get(i).isFinalCandle())
                values.add(series.get(i));
        }
        return values;
    }

    /** 하는 일: index 뒤 offset번째 확정봉의 종가를 돌려줘요. 그만큼 봉이 없으면 null이에요. */
    private BigDecimal closeAt(List<StockCandle> series, int index, int offset) {
        List<StockCandle> values = forward(series, index, offset);
        return values.size() < offset ? null : values.get(offset - 1).getClose();
    }

    /**
     * 하는 일: 기준 봉과 같은 날짜의 봉 중 기준 봉까지(포함) 거래대금을 더해 당일 누적 거래대금을 구해요.
     * 읽어 온 구간(from - 60분)보다 앞선 당일 봉은 포함되지 않아요.
     */
    private BigDecimal cumulativeTradingValue(List<StockCandle> series, int index, Instant at) {
        LocalDate date = at.atZone(MARKET_ZONE).toLocalDate();
        return series.subList(0, index + 1).stream()
                .filter(candle -> candle.getStartTime().atZone(MARKET_ZONE).toLocalDate().equals(date))
                .map(StockCandle::getTradingValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 하는 일: 가상 탐지를 설정별로 묶어 승률과 평균 수익률을 계산해요.
     * 받는 것: 전체 가상 탐지. 돌려주는 것: 설정별 요약 목록(처음 나온 순서).
     * ⚠️ 확인 필요: "ID|이름|유형"을 '|'로 이어 붙였다가 다시 나누므로, 설정 이름에 '|'가 들어가면 요약이 깨지거나 오류가 날 수 있어요.
     */
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

    /** 하는 일: 봉의 대표가격((고가 + 저가 + 종가) ÷ 3)을 구해요. */
    private BigDecimal typical(StockCandle candle) {
        return candle.getHigh().add(candle.getLow()).add(candle.getClose())
                .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
    }

    /** 하는 일: (value - base) ÷ base × 100(%)을 구해요. value가 없거나 base가 0이면 null이에요. */
    private BigDecimal pct(BigDecimal value, BigDecimal base) {
        if (value == null || base.signum() == 0)
            return null;
        return value.subtract(base).divide(base, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(6, RoundingMode.HALF_UP);
    }

    /** 하는 일: 값이 있는 항목만 골라 평균을 내요. 하나도 없으면 null이에요. */
    private BigDecimal avg(List<VirtualDetection> rows, Function<VirtualDetection, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /** 하는 일: 값이 있는 항목 중 0보다 큰(이익) 비율을 퍼센트로 구해요. 하나도 없으면 null이에요. */
    private BigDecimal win(List<VirtualDetection> rows, Function<VirtualDetection, BigDecimal> getter) {
        var values = rows.stream().map(getter).filter(Objects::nonNull).toList();
        if (values.isEmpty())
            return null;
        long wins = values.stream().filter(value -> value.signum() > 0).count();
        return BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    /** 하는 일: 문자열을 BigDecimal 숫자로 바꿔요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 하는 일: 주어진 HTTP 상태와 메시지로 잘못된 요청(INVALID_REQUEST) 오류를 만들어요. */
    private ApplicationException error(HttpStatus status, String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, status, message);
    }
}
