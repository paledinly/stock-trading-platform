package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.BacktestIntegrityIssue;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.BacktestIntegrityReport;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightBacktestRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Component
public class BacktestIntegrityService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime RECOMMENDATION_START = LocalTime.of(14, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final Duration CANDLE_INTERVAL = Duration.ofMinutes(5);
    private static final int MIN_SAMPLE_SIZE = 20;

    public BacktestIntegrityReport validate(List<BacktestEvaluation> evaluations, BigDecimal targetRate,
            BigDecimal stopRate) {
        IntegrityBuilder builder = new IntegrityBuilder();
        checkSampleSize(evaluations, builder);
        Set<String> stocks = new HashSet<>();
        Map<LocalDate, Integer> byDate = new HashMap<>();
        for (BacktestEvaluation evaluation : evaluations) {
            OvernightBacktestRow row = evaluation.row();
            stocks.add(row.stockCode());
            byDate.merge(row.recommendationDate(), 1, Integer::sum);
            checkRecommendationTime(row, builder);
            if (!"COMPLETED".equals(row.status())) {
                builder.warning("DATA_COVERAGE", row, "다음 거래일 데이터 없음",
                        "추천 이후 8일 이내의 확정 5분봉을 찾지 못했습니다.");
                continue;
            }
            checkNextTradingDate(row, builder);
            checkCandleCoverage(row, evaluation.candles(), builder);
            checkExecutionAmbiguity(row, evaluation.candles(), targetRate, stopRate, builder);
        }
        checkBias(evaluations, stocks.size(), byDate, builder);
        return builder.build();
    }

    private void checkSampleSize(List<BacktestEvaluation> evaluations, IntegrityBuilder builder) {
        if (evaluations.size() >= MIN_SAMPLE_SIZE) {
            builder.pass();
            return;
        }
        builder.warning("SAMPLE_BIAS", null, "백테스트 샘플 부족",
                "추천 후보가 " + evaluations.size() + "건으로 " + MIN_SAMPLE_SIZE + "건 미만입니다.");
    }

    private void checkRecommendationTime(OvernightBacktestRow row, IntegrityBuilder builder) {
        LocalDate detectedDate = row.detectedAt().atZone(MARKET_ZONE).toLocalDate();
        LocalTime detectedTime = row.detectedAt().atZone(MARKET_ZONE).toLocalTime();
        if (!detectedDate.equals(row.recommendationDate())) {
            builder.error("LOOKAHEAD", row, "추천일과 탐지일 불일치",
                    "탐지일 " + detectedDate + "이 추천일 " + row.recommendationDate() + "와 다릅니다.");
            return;
        }
        if (detectedTime.isBefore(RECOMMENDATION_START)) {
            builder.error("LOOKAHEAD", row, "장마감 추천 시간 이전 탐지 사용",
                    "탐지 시각 " + detectedTime + "이 14:30 이전입니다.");
            return;
        }
        if (detectedTime.isAfter(MARKET_CLOSE)) {
            builder.error("LOOKAHEAD", row, "정규장 종료 이후 탐지 사용",
                    "탐지 시각 " + detectedTime + "이 15:30 이후입니다.");
            return;
        }
        builder.pass();
    }

    private void checkNextTradingDate(OvernightBacktestRow row, IntegrityBuilder builder) {
        if (row.nextTradingDate() == null) {
            builder.error("DATA_COVERAGE", row, "다음 거래일 누락", "완료 결과인데 다음 거래일이 비어 있습니다.");
            return;
        }
        if (!row.nextTradingDate().isAfter(row.recommendationDate())) {
            builder.error("LOOKAHEAD", row, "성과 계산일 오류",
                    "다음 거래일 " + row.nextTradingDate() + "이 추천일 이후가 아닙니다.");
            return;
        }
        builder.pass();
    }

    private void checkCandleCoverage(OvernightBacktestRow row, List<StockCandle> candles, IntegrityBuilder builder) {
        if (candles.isEmpty()) {
            builder.error("DATA_COVERAGE", row, "완료 결과의 캔들 없음", "완료 상태지만 검증할 5분봉이 없습니다.");
            return;
        }
        boolean failed = false;
        Set<Instant> seen = new HashSet<>();
        Instant previous = null;
        for (StockCandle candle : candles) {
            if (!seen.add(candle.getStartTime())) {
                builder.error("DATA_COVERAGE", row, "중복 5분봉", candle.getStartTime() + " 캔들이 중복되었습니다.");
                failed = true;
            }
            if (previous != null && !previous.plus(CANDLE_INTERVAL).equals(candle.getStartTime())) {
                builder.warning("DATA_COVERAGE", row, "5분봉 구간 누락",
                        previous + " 다음 캔들이 " + candle.getStartTime() + "입니다.");
            }
            if (!validOhlc(candle)) {
                builder.error("DATA_COVERAGE", row, "비정상 OHLC",
                        candle.getStartTime() + " 캔들의 고가/저가/시가/종가 관계가 맞지 않습니다.");
                failed = true;
            }
            if (candle.getVolume() < 0 || candle.getTradingValue().signum() < 0) {
                builder.error("DATA_COVERAGE", row, "비정상 거래량/거래대금",
                        candle.getStartTime() + " 캔들의 거래량 또는 거래대금이 음수입니다.");
                failed = true;
            }
            previous = candle.getStartTime();
        }
        LocalTime firstTime = candles.getFirst().getStartTime().atZone(MARKET_ZONE).toLocalTime();
        if (firstTime.isAfter(MARKET_OPEN)) {
            builder.warning("DATA_COVERAGE", row, "장 시작 캔들 누락",
                    "첫 확정 5분봉이 " + firstTime + "입니다. 시가 수익률이 실제 장 시작과 다를 수 있습니다.");
        }
        if (!failed) {
            builder.pass();
        }
    }

    private void checkExecutionAmbiguity(OvernightBacktestRow row, List<StockCandle> candles, BigDecimal targetRate,
            BigDecimal stopRate, IntegrityBuilder builder) {
        BigDecimal targetPrice = threshold(row.buyReferencePrice(), targetRate);
        BigDecimal stopPrice = threshold(row.buyReferencePrice(), stopRate);
        for (StockCandle candle : candles) {
            boolean targetHit = candle.getHigh().compareTo(targetPrice) >= 0;
            boolean stopHit = candle.getLow().compareTo(stopPrice) <= 0;
            if (targetHit && stopHit) {
                builder.warning("EXECUTION", row, "목표/손절 동시 도달",
                        candle.getStartTime() + " 5분봉 안에서 목표가와 손절가가 모두 도달했습니다. 실제 선후관계는 알 수 없습니다.");
                return;
            }
        }
        builder.pass();
    }

    private void checkBias(List<BacktestEvaluation> evaluations, int uniqueStocks, Map<LocalDate, Integer> byDate,
            IntegrityBuilder builder) {
        if (evaluations.isEmpty()) {
            builder.pass();
            return;
        }
        int maxPerDate = byDate.values().stream().max(Integer::compareTo).orElse(0);
        BigDecimal concentration = BigDecimal.valueOf(maxPerDate)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(evaluations.size()), 2, RoundingMode.HALF_UP);
        if (uniqueStocks <= 2 && evaluations.size() >= 5) {
            builder.warning("SAMPLE_BIAS", null, "종목 편중",
                    "전체 " + evaluations.size() + "건 중 고유 종목이 " + uniqueStocks + "개뿐입니다.");
            return;
        }
        if (concentration.compareTo(BigDecimal.valueOf(60)) > 0 && evaluations.size() >= 5) {
            builder.warning("SAMPLE_BIAS", null, "날짜 편중",
                    "특정 날짜에 결과가 " + concentration + "% 집중되어 있습니다.");
            return;
        }
        builder.pass();
    }

    private boolean validOhlc(StockCandle candle) {
        BigDecimal maxBody = candle.getOpen().max(candle.getClose());
        BigDecimal minBody = candle.getOpen().min(candle.getClose());
        return candle.getHigh().compareTo(maxBody) >= 0
                && candle.getLow().compareTo(minBody) <= 0
                && candle.getHigh().compareTo(candle.getLow()) >= 0;
    }

    private BigDecimal threshold(BigDecimal base, BigDecimal rate) {
        return base.multiply(BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
    }

    public record BacktestEvaluation(OvernightBacktestRow row, List<StockCandle> candles) {
    }

    private static class IntegrityBuilder {
        private int total;
        private int passed;
        private int warnings;
        private int failures;
        private final List<BacktestIntegrityIssue> issues = new ArrayList<>();

        void pass() {
            total++;
            passed++;
        }

        void warning(String category, OvernightBacktestRow row, String message, String detail) {
            total++;
            warnings++;
            issues.add(issue("WARNING", category, row, message, detail));
        }

        void error(String category, OvernightBacktestRow row, String message, String detail) {
            total++;
            failures++;
            issues.add(issue("ERROR", category, row, message, detail));
        }

        BacktestIntegrityReport build() {
            String status = failures > 0 ? "FAIL" : warnings > 0 ? "WARNING" : "PASS";
            return new BacktestIntegrityReport(status, total, passed, warnings, failures, issues);
        }

        private BacktestIntegrityIssue issue(String severity, String category, OvernightBacktestRow row,
                String message, String detail) {
            return new BacktestIntegrityIssue(
                    severity,
                    category,
                    row == null ? null : row.recommendationDate(),
                    row == null ? null : row.stockCode(),
                    row == null ? null : row.stockName(),
                    message,
                    detail);
        }
    }
}
