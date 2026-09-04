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

class OvernightPositionDecisionServiceTest {
    private final ClosingRecommendationRepository recommendations = mock(ClosingRecommendationRepository.class);
    private final OvernightPositionDecisionRepository decisions = mock(OvernightPositionDecisionRepository.class);
    private final QuoteProvider quotes = mock(QuoteProvider.class);
    private final MarketFeatureEngine features = mock(MarketFeatureEngine.class);
    private final IntradayMovingAverageService intradayMa = mock(IntradayMovingAverageService.class);
    private final OvernightPositionDecisionService service = new OvernightPositionDecisionService(
            recommendations, decisions, quotes, features, intradayMa, new ObjectMapper().findAndRegisterModules());

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

    private StockQuote quote(String current, String high) {
        return new StockQuote("005930", "삼성전자", "KOSPI", bd(current), bd("5"), bd("5"),
                bd("101"), bd(high), bd("100"), 1000, bd("100000000"), Instant.parse("2026-09-04T01:00:00Z"));
    }

    private MarketFeatureSnapshot feature(String vwapDistance, String tradeStrength) {
        return new MarketFeatureSnapshot("005930", LocalDate.of(2026, 9, 4),
                Instant.parse("2026-09-04T01:00:00Z"), bd("105"), 1000, bd("100000000"), bd("101"),
                bd("106"), bd("100"), bd("103"), bd(vwapDistance), BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ONE, bd(tradeStrength), 0, 0, bd("0.5"), BigDecimal.ZERO, false, bd("100"),
                MarketFeatureSnapshot.VERSION);
    }

    private IntradayMovingAverageFeature ma(boolean broken) {
        return new IntradayMovingAverageFeature(true, 60, bd("105"), bd("104"), bd("102"), bd("100"),
                bd("0.9"), bd("2.9"), bd("5"), true, false, false, broken);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
