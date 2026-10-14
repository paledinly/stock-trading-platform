/*
 * OvernightPositionDecisionService(다음날 보유 포지션 판단)를 검사하는 테스트예요.
 * 전날 추천 종목의 현재 수익률, VWAP 거리, 체결강도, 장중 MA20 이탈 여부로 보유 연장(EXTEND_HOLD)이나 손절(STOP_LOSS)을 고르는지 확인해요.
 * 실주문이 아닌 판단 기록이지만, 판단 근거가 저장돼야 나중에 규칙을 검증할 수 있어서 중요해요.
 */
package com.sunmo.stockplatform.closing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.*;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.quote.application.QuoteProvider;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 전날 추천 종목을 다음날 계속 들고 갈지, 손절할지 판단하는 규칙을 확인하는 테스트 모음이에요.
 */
class OvernightPositionDecisionServiceTest {
    // 저장소, 시세, Feature, 이동평균 계산은 모두 목(mock, 가짜 객체)이에요.
    private final ClosingRecommendationRepository recommendations = mock(ClosingRecommendationRepository.class);
    private final OvernightPositionDecisionRepository decisions = mock(OvernightPositionDecisionRepository.class);
    private final QuoteProvider quotes = mock(QuoteProvider.class);
    private final MarketFeatureEngine features = mock(MarketFeatureEngine.class);
    private final IntradayMovingAverageService intradayMa = mock(IntradayMovingAverageService.class);
    /** 추천일(목요일)과 다음 거래일(금요일), 그리고 금요일 추천의 다음 거래일(월요일)이에요. */
    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 3);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 4);
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
    /** 기본 판단 시각이에요(한국시간 2026-09-04 금요일 10:00). */
    private static final Instant FRIDAY_MORNING = Instant.parse("2026-09-04T01:00:00Z");
    private final OvernightPositionDecisionService service = service(FRIDAY_MORNING);

    /**
     * 목표 수익에 닿았고 추세가 건강하면 보유 연장을 고르는지 확인해요.
     * 준비: 기준가 100인 전날 추천 1건, 현재가 105·고가 106, VWAP 거리 +2.0, 체결강도 120, MA20 이탈 없음.
     * 목표 +3%, 손절 -2%로 평가해요.
     * 기대: EXTEND_HOLD 1건, 수익률 5%, 판단 근거 JSON에 targetHit true와 ma20Broken false가 들어가요.
     * 추천일은 목요일, 판단 시각은 다음 거래일인 금요일 10:00으로 고정해요.
     */
    @Test
    void extendsHoldWhenTargetHitAndTrendIsHealthy() {
        ClosingRecommendation recommendation = recommendation(THURSDAY);
        when(recommendations.findByRecommendationDateOrderByRankAsc(any())).thenReturn(List.of(recommendation));
        when(quotes.getQuote(any())).thenReturn(quote("105", "106"));
        when(features.latest("005930")).thenReturn(Optional.of(feature("2.0", "120")));
        when(intradayMa.calculate(1L, Instant.parse("2026-09-04T01:00:00Z"))).thenReturn(ma(false));
        when(decisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.evaluate(THURSDAY, bd("3"), bd("-2"));

        assertThat(result.extendHold()).isEqualTo(1);
        assertThat(result.decisions().getFirst().decision()).isEqualTo("EXTEND_HOLD");
        assertThat(result.decisions().getFirst().returnRate()).isEqualByComparingTo("5.000000");
        assertThat(result.decisions().getFirst().reasonJson()).contains("\"targetHit\":true", "\"ma20Broken\":false");
    }

    /**
     * 수익률이 손절 기준 아래로 떨어지면 손절을 고르는지 확인해요.
     * 준비: 기준가 100, 현재가 97(-3%), VWAP 거리 -1.0, 체결강도 80, MA20 이탈. 손절 기준 -2%.
     * 기대: STOP_LOSS 1건이에요.
     */
    @Test
    void stopsLossWhenReturnFallsBelowStopRate() {
        ClosingRecommendation recommendation = recommendation(THURSDAY);
        when(recommendations.findByRecommendationDateOrderByRankAsc(any())).thenReturn(List.of(recommendation));
        when(quotes.getQuote(any())).thenReturn(quote("97", "101"));
        when(features.latest("005930")).thenReturn(Optional.of(feature("-1.0", "80")));
        when(intradayMa.calculate(1L, Instant.parse("2026-09-04T01:00:00Z"))).thenReturn(ma(true));
        when(decisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.evaluate(THURSDAY, bd("3"), bd("-2"));

        assertThat(result.stopLoss()).isEqualTo(1);
        assertThat(result.decisions().getFirst().decision()).isEqualTo("STOP_LOSS");
    }

    /**
     * 추천일 다음날이 주말이면, 다음 거래일 전이라 지난 시세로 판단하지 않고 대기하는지 확인해요.
     * 준비: 금요일 추천, 판단 시각은 토요일 10:00이에요.
     * 기대: DATA_PENDING 1건이고 현재가는 조회하지 않아요.
     */
    @Test
    void waitsUntilTheNextTradingDayAcrossTheWeekend() {
        ClosingRecommendation recommendation = recommendation(FRIDAY);
        when(recommendations.findByRecommendationDateOrderByRankAsc(FRIDAY)).thenReturn(List.of(recommendation));
        when(decisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service(Instant.parse("2026-09-05T01:00:00Z")).evaluate(FRIDAY, bd("3"), bd("-2"));

        assertThat(result.decisions()).singleElement()
                .satisfies(decision -> assertThat(decision.decision()).isEqualTo("DATA_PENDING"));
        verify(quotes, never()).getQuote(any());
    }

    /**
     * 날짜를 주지 않으면 달력상 어제가 아니라 직전 거래일을 쓰는지 확인해요.
     * 준비: 판단 시각은 월요일 10:00, 추천·판단 조회는 목(mock)이에요.
     * 기대: 판단 실행과 조회 모두 금요일 추천을 찾아요.
     */
    @Test
    void defaultDateIsThePreviousTradingDay() {
        when(recommendations.findByRecommendationDateOrderByRankAsc(any())).thenReturn(List.of());
        var monday = service(MONDAY.atTime(10, 0).atZone(ClosingTradingCalendar.ZONE).toInstant());

        var result = monday.evaluate(null, null, null);
        monday.list(null);

        assertThat(result.recommendationDate()).isEqualTo(FRIDAY);
        verify(recommendations).findByRecommendationDateOrderByRankAsc(FRIDAY);
        verify(decisions).findLatestByRecommendationDate(FRIDAY);
    }

    /**
     * 매수 기준가가 없거나 0이면 수익률 0%로 판단하지 않고 대기(자료 없음)로 남기는지 확인해요.
     * 준비: 기준가 null인 추천과 기준가 0인 추천, 현재가 105는 정상이에요.
     * 기대: 두 건 모두 DATA_PENDING이고 수익률은 null, 보유·매도 판단 건수는 0이에요.
     */
    @Test
    void missingBuyReferencePriceDoesNotBecomeZeroReturn() {
        ClosingRecommendation noBase = recommendation(THURSDAY);
        when(noBase.getBuyReferencePrice()).thenReturn(null);
        ClosingRecommendation zeroBase = recommendation(THURSDAY);
        when(zeroBase.getBuyReferencePrice()).thenReturn(BigDecimal.ZERO);
        when(recommendations.findByRecommendationDateOrderByRankAsc(THURSDAY)).thenReturn(List.of(noBase, zeroBase));
        when(quotes.getQuote(any())).thenReturn(quote("105", "106"));
        when(features.latest("005930")).thenReturn(Optional.of(feature("2.0", "120")));
        when(intradayMa.calculate(1L, FRIDAY_MORNING)).thenReturn(ma(false));
        when(decisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.evaluate(THURSDAY, bd("3"), bd("-2"));

        assertThat(result.decisions()).hasSize(2).allSatisfy(decision -> {
            assertThat(decision.decision()).isEqualTo("DATA_PENDING");
            assertThat(decision.returnRate()).isNull();
        });
        assertThat(result.extendHold() + result.takeProfit() + result.sellWarning() + result.stopLoss()).isZero();
    }

    /** 고정 시각 시계를 쓰는 거래일 달력으로 서비스를 만들어요. */
    private OvernightPositionDecisionService service(Instant now) {
        ClosingTradingCalendar calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30,
                false, null, null, null, null, null, List.of()), Clock.fixed(now, ZoneOffset.UTC));
        return new OvernightPositionDecisionService(recommendations, decisions, quotes, features, intradayMa,
                new ObjectMapper().findAndRegisterModules(), calendar);
    }

    /** 삼성전자(005930) 1순위, 매수 기준가 100인 추천 목을 만들어요. */
    private ClosingRecommendation recommendation(LocalDate date) {
        Stock stock = mock(Stock.class);
        when(stock.getId()).thenReturn(1L);
        when(stock.getStockCode()).thenReturn("005930");
        when(stock.getStockName()).thenReturn("삼성전자");
        when(stock.getMarket()).thenReturn(Market.KOSPI);

        ClosingRecommendation recommendation = mock(ClosingRecommendation.class);
        when(recommendation.getId()).thenReturn(11L);
        when(recommendation.getRecommendationDate()).thenReturn(date);
        when(recommendation.getStock()).thenReturn(stock);
        when(recommendation.getRank()).thenReturn(1);
        when(recommendation.getBuyReferencePrice()).thenReturn(bd("100"));
        return recommendation;
    }

    /** 현재가와 고가만 바꾼 현재 시세를 만들어요. */
    private StockQuote quote(String current, String high) {
        return new StockQuote("005930", "삼성전자", "KOSPI", bd(current), bd("5"), bd("5"),
                bd("101"), bd(high), bd("100"), 1000, bd("100000000"), Instant.parse("2026-09-04T01:00:00Z"));
    }

    /** VWAP 거리와 체결강도만 바꾼 시장 Feature 스냅샷을 만들어요. */
    private MarketFeatureSnapshot feature(String vwapDistance, String tradeStrength) {
        return new MarketFeatureSnapshot("005930", LocalDate.of(2026, 9, 4),
                Instant.parse("2026-09-04T01:00:00Z"), bd("105"), 1000, bd("100000000"), bd("101"),
                bd("106"), bd("100"), bd("103"), bd(vwapDistance), BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ONE, bd(tradeStrength), 0, 0, bd("0.5"), BigDecimal.ZERO, false, bd("100"),
                MarketFeatureSnapshot.VERSION);
    }

    /** 준비 완료된 장중 이동평균 Feature를 만들어요. broken으로 MA20 이탈 여부만 바꿔요. */
    private IntradayMovingAverageFeature ma(boolean broken) {
        return new IntradayMovingAverageFeature(true, 60, bd("105"), bd("104"), bd("102"), bd("100"),
                bd("0.9"), bd("2.9"), bd("5"), true, false, false, broken);
    }

    /** 문자열로 BigDecimal을 만드는 짧은 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
