/*
 * 마감 추천 백테스트(과거 데이터로 전략을 돌려 보는 검증) 결과가 믿을 만한지 점검하는 서비스예요.
 * 미래 정보 사용(룩어헤드), 진입 시각 오류, 다음 거래일 5분봉 결측·중복·이상값, 목표/손절 동시 도달, 표본 편중을 경고나 오류로 모아요.
 * OvernightBacktestService가 백테스트 결과를 돌려주기 전에 불러요.
 */
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

/**
 * 백테스트 행마다 점검표를 돌려 PASS/WARNING/FAIL 보고서를 만드는 검수 담당이에요.
 * 시험 답안을 채점하기 전에 "시험 시간 전에 답을 본 건 아닌지, 답안지가 찢어진 데는 없는지"를
 * 항목별로 체크해 통과·주의·실격을 표시하는 감독관이라고 생각하면 돼요.
 */
@Component
public class BacktestIntegrityService {
    // 점검 기준 시각이에요. 탐지 허용 구간(14:30~15:30), 평가 시각, 진입 시각, 5분봉 간격, 최소 표본 수를 정해요.
    // ⚠️ 확인 필요: DECISION_TIME 15:00, ENTRY_TIME 15:05는 v8(15:00 평가) 기준 하드코딩이에요.
    // 현재 v9.1은 15:15 평가·15:20 전 진입이고 ClosingRecommendationProperties의 시각과 연결돼 있지 않아요.
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime RECOMMENDATION_START = LocalTime.of(14, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
    private static final LocalTime DECISION_TIME = LocalTime.of(15, 0);
    private static final LocalTime ENTRY_TIME = LocalTime.of(15, 5);
    private static final Duration CANDLE_INTERVAL = Duration.ofMinutes(5);
    private static final int MIN_SAMPLE_SIZE = 20;

    /**
     * 백테스트 평가 목록 전체를 점검해 무결성 보고서를 만들어요.
     * 받는 것: 백테스트 행과 다음 거래일 5분봉 묶음 목록, 목표 수익률(%), 손절 수익률(%).
     * 돌려주는 것: 점검 건수와 경고·오류 목록이 담긴 BacktestIntegrityReport(상태 PASS/WARNING/FAIL).
     */
    public BacktestIntegrityReport validate(List<BacktestEvaluation> evaluations, BigDecimal targetRate,
            BigDecimal stopRate) {
        // 준비: 표본 크기를 먼저 보고, 종목 수와 날짜별 건수를 모아 편중 점검에 써요.
        IntegrityBuilder builder = new IntegrityBuilder();
        checkSampleSize(evaluations, builder);
        Set<String> stocks = new HashSet<>();
        Map<LocalDate, Integer> byDate = new HashMap<>();
        // 처리: 행마다 탐지 시각 → 진입 → 완료 여부 → 다음 거래일 → 5분봉 품질 → 목표/손절 동시 도달 순으로 봐요.
        for (BacktestEvaluation evaluation : evaluations) {
            OvernightBacktestRow row = evaluation.row();
            stocks.add(row.stockCode());
            byDate.merge(row.recommendationDate(), 1, Integer::sum);
            checkRecommendationTime(row, builder);
            if (!checkEntry(row, builder))
                continue;
            if (!"COMPLETED".equals(row.status())) {
                builder.warning("DATA_COVERAGE", row, "다음 거래일 데이터 없음",
                        "예정된 다음 거래일의 확정 5분봉을 찾지 못했습니다.");
                continue;
            }
            checkNextTradingDate(row, builder);
            checkCandleCoverage(row, evaluation.candles(), builder);
            checkExecutionAmbiguity(row, evaluation.candles(), targetRate, stopRate, builder);
        }
        // 결과: 전체 표본의 종목·날짜 편중을 마지막에 보고 보고서를 만들어요.
        checkBias(evaluations, stocks.size(), byDate, builder);
        return builder.build();
    }

    /**
     * 가상 진입 시각과 가격이 평가 완료 이후 조건을 지키는지 확인해요.
     * 받는 것: 백테스트 행, 결과 누적기.
     * 돌려주는 것: 진입이 유효하면 true. 진입이 없으면 경고, 시각·가격이 틀리면 LOOKAHEAD 오류를 남기고 false.
     */
    private boolean checkEntry(OvernightBacktestRow row, IntegrityBuilder builder) {
        if (row.entryAt() == null || row.buyReferencePrice() == null) {
            // ⚠️ 확인 필요: 안내 문구의 "15:05 확정 5분봉"은 v8 진입 모델 기준이에요. 기본 15:15 설정에서는
            // OvernightBacktestService가 5분봉으로 진입을 만들지 않아 모든 행이 이 경고로 가는 것으로 보여요.
            builder.warning("EXECUTION", row, "평가 이후 진입 데이터 없음",
                    "15:05 확정 5분봉 시가가 없어 모의 진입을 만들지 않았습니다.");
            return false;
        }
        LocalDate entryDate = row.entryAt().atZone(MARKET_ZONE).toLocalDate();
        LocalTime entryTime = MarketSessionHours.regularTime(row.entryAt());
        if (!entryDate.equals(row.recommendationDate()) || entryTime.isBefore(ENTRY_TIME)
                || !entryTime.isAfter(DECISION_TIME) || row.buyReferencePrice().signum() <= 0) {
            builder.error("LOOKAHEAD", row, "진입 시점 오류",
                    "진입 시각 " + row.entryAt() + " 또는 가격이 15:00 평가 완료 이후 조건을 충족하지 않습니다.");
            return false;
        }
        builder.pass();
        return true;
    }

    /**
     * 표본이 최소 20건 이상인지 확인해요.
     * 받는 것: 평가 목록, 결과 누적기. 돌려주는 것: 없음(부족하면 SAMPLE_BIAS 경고를 남겨요).
     */
    private void checkSampleSize(List<BacktestEvaluation> evaluations, IntegrityBuilder builder) {
        if (evaluations.size() >= MIN_SAMPLE_SIZE) {
            builder.pass();
            return;
        }
        builder.warning("SAMPLE_BIAS", null, "백테스트 샘플 부족",
                "추천 후보가 " + evaluations.size() + "건으로 " + MIN_SAMPLE_SIZE + "건 미만입니다.");
    }

    /**
     * 탐지가 추천일 당일 14:30~15:30 사이에 나온 것인지 확인해요(미래 정보 사용 방지).
     * 받는 것: 백테스트 행, 결과 누적기. 돌려주는 것: 없음(어긋나면 LOOKAHEAD 오류).
     */
    private void checkRecommendationTime(OvernightBacktestRow row, IntegrityBuilder builder) {
        LocalDate detectedDate = row.detectedAt().atZone(MARKET_ZONE).toLocalDate();
        LocalTime detectedTime = MarketSessionHours.regularTime(row.detectedAt());
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

    /**
     * 성과 측정일(다음 거래일)이 있고 추천일보다 뒤인지 확인해요.
     * 받는 것: 백테스트 행, 결과 누적기. 돌려주는 것: 없음(어긋나면 오류).
     */
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

    /**
     * 다음 거래일 5분봉의 품질을 확인해요. 중복, 간격 빈틈, OHLC(시가·고가·저가·종가) 관계, 음수 거래량, 장 시작 봉 누락을 봐요.
     * 받는 것: 백테스트 행, 5분봉 목록, 결과 누적기. 돌려주는 것: 없음.
     * 빈틈과 장 시작 누락은 경고, 중복·이상값은 오류로 남겨요.
     */
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
        // 첫 봉이 09:00보다 늦으면 시가 수익률이 실제 장 시작 가격과 다를 수 있어 경고해요.
        LocalTime firstTime = MarketSessionHours.regularTime(candles.getFirst().getStartTime());
        LocalTime open = MarketSessionHours.regularOpen(candles.getFirst().getStartTime().atZone(MARKET_ZONE).toLocalDate());
        if (firstTime.isAfter(open.isAfter(MARKET_OPEN) ? open : MARKET_OPEN)) {
            builder.warning("DATA_COVERAGE", row, "장 시작 캔들 누락",
                    "첫 확정 5분봉이 " + firstTime + "입니다. 시가 수익률이 실제 장 시작과 다를 수 있습니다.");
        }
        if (!failed) {
            builder.pass();
        }
    }

    /**
     * 한 5분봉 안에서 목표가와 손절가가 함께 닿은 적이 있는지 확인해요.
     * 5분봉만으로는 봉 안의 선후관계를 알 수 없어 결과가 모호하다고 경고해요.
     * 받는 것: 백테스트 행, 5분봉 목록, 목표·손절 수익률(%), 결과 누적기. 돌려주는 것: 없음.
     */
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

    /**
     * 표본이 특정 종목이나 날짜에 몰려 있는지 확인해요.
     * 받는 것: 평가 목록, 고유 종목 수, 날짜별 건수, 결과 누적기.
     * 돌려주는 것: 없음. 5건 이상일 때 고유 종목 2개 이하이거나 한 날짜가 60% 초과면 SAMPLE_BIAS 경고를 남겨요.
     */
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

    /**
     * 봉의 고가·저가가 시가·종가를 감싸는 정상 모양인지 확인해요.
     * 받는 것: 5분봉. 돌려주는 것: 정상이면 true.
     */
    private boolean validOhlc(StockCandle candle) {
        BigDecimal maxBody = candle.getOpen().max(candle.getClose());
        BigDecimal minBody = candle.getOpen().min(candle.getClose());
        return candle.getHigh().compareTo(maxBody) >= 0
                && candle.getLow().compareTo(minBody) <= 0
                && candle.getHigh().compareTo(candle.getLow()) >= 0;
    }

    /**
     * 기준가에 수익률(%)을 적용한 가격을 계산해요(예: 10,000원, +3% → 10,300원).
     * 받는 것: 기준가, 수익률(%). 돌려주는 것: 목표·손절 가격.
     */
    private BigDecimal threshold(BigDecimal base, BigDecimal rate) {
        return base.multiply(BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
    }

    /** 점검 대상 1건이에요. 백테스트 결과 행과 그 행의 다음 거래일 5분봉 목록을 묶어요. */
    public record BacktestEvaluation(OvernightBacktestRow row, List<StockCandle> candles) {
    }

    /**
     * 점검 결과를 모아 세는 누적기예요. 통과·경고·오류 건수와 이슈 목록을 쌓아요.
     * 체크리스트에 ○, △, × 표시를 하나씩 적어 나가는 메모장 같은 역할이에요.
     */
    private static class IntegrityBuilder {
        private int total;
        private int passed;
        private int warnings;
        private int failures;
        private final List<BacktestIntegrityIssue> issues = new ArrayList<>();

        /** 점검 1건 통과로 셉니다. 받는 것·돌려주는 것: 없음. */
        void pass() {
            total++;
            passed++;
        }

        /**
         * 경고 1건을 기록해요.
         * 받는 것: 분류, 관련 행(전체 표본 문제면 null), 요약 메시지, 상세 설명. 돌려주는 것: 없음.
         */
        void warning(String category, OvernightBacktestRow row, String message, String detail) {
            total++;
            warnings++;
            issues.add(issue("WARNING", category, row, message, detail));
        }

        /**
         * 오류 1건을 기록해요.
         * 받는 것: 분류, 관련 행(전체 표본 문제면 null), 요약 메시지, 상세 설명. 돌려주는 것: 없음.
         */
        void error(String category, OvernightBacktestRow row, String message, String detail) {
            total++;
            failures++;
            issues.add(issue("ERROR", category, row, message, detail));
        }

        /**
         * 지금까지 모은 결과로 보고서를 만들어요.
         * 돌려주는 것: 오류가 있으면 FAIL, 경고만 있으면 WARNING, 아니면 PASS 상태의 보고서.
         */
        BacktestIntegrityReport build() {
            String status = failures > 0 ? "FAIL" : warnings > 0 ? "WARNING" : "PASS";
            return new BacktestIntegrityReport(status, total, passed, warnings, failures, issues);
        }

        /**
         * 이슈 1건을 API 응답 형식으로 만들어요.
         * 받는 것: 심각도, 분류, 관련 행, 메시지, 상세. 돌려주는 것: 추천일·종목 정보가 붙은 이슈(행이 없으면 null로 채워요).
         */
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
