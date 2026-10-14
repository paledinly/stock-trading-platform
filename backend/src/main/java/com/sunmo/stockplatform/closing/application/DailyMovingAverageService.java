/*
 * 저장된 확정 일봉(하루 한 개의 가격 막대)으로 MA5/MA20/MA60 같은 이동평균(최근 N일 종가의 평균)을 계산하는 서비스예요.
 * 탐지 당일 봉은 쓰지 않고, 평가 시각까지 실제로 저장돼 있던 과거 일봉만 써서 미래 정보가 섞이지 않게 해요.
 * 정밀 후보 평가(ClosingPrecisionEvaluator)와 백테스트(OvernightBacktestService)가 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Comparator;
import java.util.List;

/**
 * 탐지된 종목의 일봉 추세(평균선 위/아래, 정배열, 과열 여부)를 계산하는 클래스예요.
 * 오늘 시험 전에 "지난 성적표"만 꺼내 평균을 내 보는 담임 선생님처럼, 그 시각에 이미 손에 있던 과거 기록만 봐요.
 */
@Service
public class DailyMovingAverageService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final String TIMEFRAME = "1D";

    private final StockCandleRepository candles;

    /**
     * 하는 일: 일봉을 읽을 저장소를 받아 서비스를 만들어요.
     * 받는 것: 봉 저장소(StockCandleRepository).
     * 돌려주는 것: 없음(생성자).
     */
    public DailyMovingAverageService(StockCandleRepository candles) {
        this.candles = candles;
    }

    /**
     * 하는 일: 탐지 시각을 "수신 가능 마감 시각"으로 삼아 일봉 이동평균을 계산해요.
     * 받는 것: Scanner 탐지 한 건.
     * 돌려주는 것: 일봉 이동평균 결과(일봉 20개 미만이면 빈 결과).
     */
    public DailyMovingAverageFeature calculate(ScannerDetection detection) {
        return calculate(detection, detection.getDetectedAt());
    }

    /**
     * 하는 일: 탐지일 전날까지의 확정 일봉 중 availableBy 이전에 저장·수정된 것만 골라 이동평균을 계산해요.
     * 받는 것: Scanner 탐지 한 건, 이 시각까지 받은 자료만 쓰라는 기준 시각(availableBy).
     * 돌려주는 것: 일봉 이동평균 결과.
     */
    public DailyMovingAverageFeature calculate(ScannerDetection detection, Instant availableBy) {
        // 준비: 탐지일 전날 00:00(한국시간)까지 시작한 일봉만 대상으로 해서 당일 일봉(아직 미확정)을 빼요.
        // 달력상 전날 기준이라 주말·휴장일(시장이 쉬는 날)이 끼어도 "그 이전 가장 최근 거래일 일봉"까지 자연히 포함돼요.
        Instant previousSessionDate = detection.getDetectedAt().atZone(MARKET_ZONE).toLocalDate()
                .minusDays(1)
                .atStartOfDay(MARKET_ZONE)
                .toInstant();
        // 처리: 최근 확정 일봉 최대 61개를 가져와, 생성·수정 시각이 기준 시각 이후인 행(나중에 받은 자료)은 버리고 오래된 순으로 정렬해요.
        List<StockCandle> series = candles
                .findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                        detection.getStock().getId(), TIMEFRAME, previousSessionDate)
                .stream()
                .filter(row -> row.getCreatedAt() != null && row.getUpdatedAt() != null)
                .filter(row -> !row.getCreatedAt().isAfter(availableBy) && !row.getUpdatedAt().isAfter(availableBy))
                .sorted(Comparator.comparing(StockCandle::getStartTime))
                .toList();
        return calculate(series);
    }

    /**
     * 하는 일: 오래된 순으로 정렬된 일봉 목록으로 MA5/MA20/MA60, 거리율, MA20 기울기, 정배열·과열 여부를 계산해요.
     * 받는 것: 시간 오름차순 일봉 목록.
     * 돌려주는 것: 계산 결과. 20개 미만이면 빈 결과, 60개 미만이면 MA60 관련 값은 null.
     */
    public DailyMovingAverageFeature calculate(List<StockCandle> candles) {
        if (candles.size() < 20) {
            return DailyMovingAverageFeature.empty(candles.size());
        }
        StockCandle latest = candles.getLast();
        BigDecimal lastClose = latest.getClose();
        BigDecimal ma5 = average(candles, 5, 0);
        BigDecimal ma20 = average(candles, 20, 0);
        // MA60은 일봉 60개 이상, 전일 MA20(기울기 계산용)은 21개 이상일 때만 계산해요.
        BigDecimal ma60 = candles.size() >= 60 ? average(candles, 60, 0) : null;
        BigDecimal previousMa20 = candles.size() >= 21 ? average(candles, 20, 1) : null;
        BigDecimal ma20Slope = previousMa20 == null ? null : distance(ma20, previousMa20);
        boolean ma5AboveMa20 = ma5.compareTo(ma20) > 0;
        boolean closeAboveMa20 = lastClose.compareTo(ma20) > 0;
        boolean ma20Rising = ma20Slope != null && ma20Slope.signum() > 0;
        // 정배열: MA60이 없으면 "종가>MA20, MA5>MA20", 있으면 여기에 "MA20>MA60"까지 만족해야 해요.
        boolean bullishAlignment = ma60 == null
                ? ma5AboveMa20 && closeAboveMa20
                : closeAboveMa20 && ma5.compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0;
        BigDecimal ma20Distance = distance(lastClose, ma20);
        return new DailyMovingAverageFeature(
                true,
                candles.size(),
                latest.getStartTime().atZone(MARKET_ZONE).toLocalDate(),
                lastClose,
                ma5,
                ma20,
                ma60,
                distance(lastClose, ma5),
                ma20Distance,
                distance(lastClose, ma60),
                ma20Slope,
                closeAboveMa20,
                ma5AboveMa20,
                ma20Rising,
                bullishAlignment,
                // 과열: 종가가 MA20보다 12% 넘게 높으면 overextendedFromMa20=true예요.
                ma20Distance != null && ma20Distance.compareTo(BigDecimal.valueOf(12)) > 0);
    }

    /**
     * 하는 일: 목록 끝에서 offsetFromEnd개를 건너뛴 위치부터 거꾸로 period개 종가의 평균을 내요.
     * 받는 것: 일봉 목록, 평균 낼 개수(period), 끝에서 건너뛸 개수(0이면 최신 포함, 1이면 한 봉 전 기준).
     * 돌려주는 것: 소수 6자리로 반올림한 평균값.
     */
    private BigDecimal average(List<StockCandle> candles, int period, int offsetFromEnd) {
        int endExclusive = candles.size() - offsetFromEnd;
        int start = endExclusive - period;
        return candles.subList(start, endExclusive).stream()
                .map(StockCandle::getClose)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 가격이 평균에서 몇 % 떨어져 있는지 계산해요.
     * 받는 것: 가격, 평균값.
     * 돌려주는 것: (가격-평균)/평균×100(소수 6자리). 값이 없거나 평균이 0이면 null.
     */
    private BigDecimal distance(BigDecimal price, BigDecimal average) {
        if (price == null || average == null || average.signum() == 0) {
            return null;
        }
        return price.subtract(average)
                .divide(average, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(6, RoundingMode.HALF_UP);
    }
}
