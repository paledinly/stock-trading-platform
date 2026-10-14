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
    private final OvernightPositionDecisionService service = new OvernightPositionDecisionService(
            recommendations, decisions, quotes, features, intradayMa, new ObjectMapper().findAndRegisterModules());

    /**
     * 목표 수익에 닿았고 추세가 건강하면 보유 연장을 고르는지 확인해요.
     * 준비: 기준가 100인 전날 추천 1건, 현재가 105·고가 106, VWAP 거리 +2.0, 체결강도 120, MA20 이탈 없음.
     * 목표 +3%, 손절 -2%로 평가해요.
     * 기대: EXTEND_HOLD 1건, 수익률 5%, 판단 근거 JSON에 targetHit true와 ma20Broken false가 들어가요.
     * 참고: 추천일은 실제 오늘 날짜(LocalDate.now) 기준 전날이지만, 목이 any()로 받으므로 날짜에 따라 결과가 바뀌지는 않는 것으로 보여요.
     */
    @Test
    void extendsHoldWhenTargetHitAndTrendIsHealthy() {
        ClosingRecommendation recommendation = recommendation(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
        when(recommendations.findByRecommendationDateOrderByRankAsc(any())).thenReturn(List.of(recommendation));
        when(quotes.getQuote(any())).thenReturn(quote("105", "106"));
        when(features.latest("005930")).thenReturn(Optional.of(feature("2.0", "120")));
        when(intradayMa.calculate(1L, Instant.parse("2026-09-04T01:00:00Z"))).thenReturn(ma(false));
        when(decisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.evaluate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1), bd("3"), bd("-2"));

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
        ClosingRecommendation recommendation = recommendation(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
        when(recommendations.findByRecommendationDateOrderByRankAsc(any())).thenReturn(List.of(recommendation));
        when(quotes.getQuote(any())).thenReturn(quote("97", "101"));
        when(features.latest("005930")).thenReturn(Optional.of(feature("-1.0", "80")));
        when(intradayMa.calculate(1L, Instant.parse("2026-09-04T01:00:00Z"))).thenReturn(ma(true));
        when(decisions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.evaluate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1), bd("3"), bd("-2"));

        assertThat(result.stopLoss()).isEqualTo(1);
        assertThat(result.decisions().getFirst().decision()).isEqualTo("STOP_LOSS");
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
