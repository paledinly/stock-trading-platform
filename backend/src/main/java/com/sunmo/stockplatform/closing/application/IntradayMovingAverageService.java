/*
 * 저장된 확정 5분봉(5분마다 하나씩 생기는 가격 막대)으로 장중 이동평균(MA5/MA20/MA60: 최근 N개 봉 종가의 평균)과 단기 추세 신호를 계산하는 서비스예요.
 * 기준 시각까지 끝나고, 그 시각까지 실제로 저장돼 있던 봉만 써서 늦게 들어온 자료가 섞이지 않게 해요.
 * 정밀 후보 평가(ClosingPrecisionEvaluator), 백테스트, 다음날 보유 판단(OvernightPositionDecisionService)이 사용해요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.scanner.domain.ScannerDetection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 5분봉 기준 단기 추세(정배열, 골든크로스, MA20 지지·이탈)를 계산하는 클래스예요.
 * 최근 몇 바퀴의 랩타임 평균과 방금 바퀴 기록을 비교해 "속도가 붙었는지, 처졌는지"를 보는 코치와 같아요.
 */
@Service
public class IntradayMovingAverageService {
    private static final String TIMEFRAME = "5M";

    private final StockCandleRepository candles;

    /**
     * 하는 일: 5분봉을 읽을 저장소를 받아 서비스를 만들어요.
     * 받는 것: 봉 저장소(StockCandleRepository).
     * 돌려주는 것: 없음(생성자).
     */
    public IntradayMovingAverageService(StockCandleRepository candles) {
        this.candles = candles;
    }

    /**
     * 하는 일: 탐지 시각을 기준 시각이자 수신 마감 시각으로 삼아 계산해요.
     * 받는 것: Scanner 탐지 한 건.
     * 돌려주는 것: 5분봉 이동평균 결과.
     */
    public IntradayMovingAverageFeature calculate(ScannerDetection detection) {
        return calculate(detection.getStock().getId(), detection.getDetectedAt(), detection.getDetectedAt());
    }

    /**
     * 하는 일: 종목 ID와 기준 시각으로 계산해요(기준 시각 = 수신 마감 시각).
     * 받는 것: 종목 ID, 기준 시각(예: 현재가의 시세 시각).
     * 돌려주는 것: 5분봉 이동평균 결과.
     */
    public IntradayMovingAverageFeature calculate(Long stockId, java.time.Instant at) {
        return calculate(stockId, at, at);
    }

    /**
     * 하는 일: 탐지 시각까지 끝난 봉 중 availableBy 이전에 저장·수정된 봉만으로 계산해요.
     * 받는 것: Scanner 탐지 한 건, 이 시각까지 받은 자료만 쓰라는 기준 시각(availableBy).
     * 돌려주는 것: 5분봉 이동평균 결과.
     */
    public IntradayMovingAverageFeature calculate(ScannerDetection detection, java.time.Instant availableBy) {
        return calculate(detection.getStock().getId(), detection.getDetectedAt(), availableBy);
    }

    /**
     * 하는 일: 실제 조회·필터를 맡는 내부 메서드예요.
     * 받는 것: 종목 ID, 봉이 끝나 있어야 하는 시각(at), 자료 수신 마감 시각(availableBy).
     * 돌려주는 것: 5분봉 이동평균 결과.
     */
    private IntradayMovingAverageFeature calculate(Long stockId, java.time.Instant at,
            java.time.Instant availableBy) {
        // 처리: at-5분 이전에 시작한 확정 5분봉 최대 61개를 가져와, 봉 끝(시작+5분)이 at을 넘는 봉과
        // 생성·수정 시각이 availableBy 이후인 봉(나중에 받은 자료)을 버리고 오래된 순으로 정렬해요.
        List<StockCandle> series = candles
                .findTop61ByStockIdAndTimeframeAndStartTimeLessThanEqualAndFinalCandleTrueOrderByStartTimeDesc(
                        stockId, TIMEFRAME, at.minus(java.time.Duration.ofMinutes(5)))
                .stream()
                .filter(row -> row.isFinalCandle() && !row.getStartTime().plus(java.time.Duration.ofMinutes(5)).isAfter(at))
                .filter(row -> row.getCreatedAt() != null && row.getUpdatedAt() != null)
                .filter(row -> !row.getCreatedAt().isAfter(availableBy) && !row.getUpdatedAt().isAfter(availableBy))
                .sorted(Comparator.comparing(StockCandle::getStartTime))
                .toList();
        return calculate(series);
    }

    /**
     * 하는 일: 오래된 순 5분봉 목록으로 MA5/MA20/MA60, 거리율, 정배열·골든크로스·MA20 지지/이탈을 계산해요.
     * 받는 것: 시간 오름차순 5분봉 목록.
     * 돌려주는 것: 계산 결과. 20개 미만이면 빈 결과, 60개 미만이면 MA60 관련 값은 null.
     */
    public IntradayMovingAverageFeature calculate(List<StockCandle> candles) {
        if (candles.size() < 20) {
            return IntradayMovingAverageFeature.empty(candles.size());
        }
        StockCandle latest = candles.getLast();
        BigDecimal lastClose = latest.getClose();
        BigDecimal ma5 = average(candles, 5, 0);
        BigDecimal ma20 = average(candles, 20, 0);
        BigDecimal ma60 = candles.size() >= 60 ? average(candles, 60, 0) : null;
        // 직전 봉 기준 MA5/MA20(골든크로스 판단용)은 21개 이상일 때만 계산해요.
        BigDecimal previousMa5 = candles.size() >= 21 ? average(candles, 5, 1) : null;
        BigDecimal previousMa20 = candles.size() >= 21 ? average(candles, 20, 1) : null;
        boolean bullishAlignment = ma60 == null
                ? ma5.compareTo(ma20) > 0
                : ma5.compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0;
        // 골든크로스: 직전 봉에선 MA5<=MA20이었는데 이번 봉에서 MA5>MA20으로 올라선 경우예요.
        boolean goldenCross = previousMa5 != null && previousMa20 != null
                && previousMa5.compareTo(previousMa20) <= 0 && ma5.compareTo(ma20) > 0;
        // MA20 지지: 마지막 봉 저가가 MA20을 건드렸지만 종가는 MA20 이상으로 버틴 경우. 이탈: 종가가 MA20 아래인 경우예요.
        boolean ma20Support = latest.getLow().compareTo(ma20) <= 0 && latest.getClose().compareTo(ma20) >= 0;
        boolean ma20Broken = latest.getClose().compareTo(ma20) < 0;
        return new IntradayMovingAverageFeature(
                true,
                candles.size(),
                lastClose,
                ma5,
                ma20,
                ma60,
                distance(lastClose, ma5),
                distance(lastClose, ma20),
                distance(lastClose, ma60),
                bullishAlignment,
                goldenCross,
                ma20Support,
                ma20Broken);
    }

    /**
     * 하는 일: 목록 끝에서 offsetFromEnd개를 건너뛴 위치부터 거꾸로 period개 종가의 평균을 내요.
     * 받는 것: 5분봉 목록, 평균 낼 개수, 끝에서 건너뛸 개수(0=최신 포함, 1=한 봉 전 기준).
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
