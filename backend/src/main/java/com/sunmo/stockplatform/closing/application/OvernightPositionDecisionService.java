package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.DecisionEvaluationResponse;
import com.sunmo.stockplatform.closing.api.ClosingRecommendationDtos.OvernightPositionDecisionResponse;
import com.sunmo.stockplatform.closing.domain.*;
import com.sunmo.stockplatform.closing.infrastructure.*;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.market.feature.domain.MarketFeatureSnapshot;
import com.sunmo.stockplatform.quote.application.QuoteProvider;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Service
public class OvernightPositionDecisionService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    private final ClosingRecommendationRepository recommendations;
    private final OvernightPositionDecisionRepository decisions;
    private final QuoteProvider quotes;
    private final MarketFeatureEngine features;
    private final IntradayMovingAverageService intradayMa;
    private final ObjectMapper objectMapper;

    public OvernightPositionDecisionService(ClosingRecommendationRepository recommendations,
            OvernightPositionDecisionRepository decisions, QuoteProvider quotes, MarketFeatureEngine features,
            IntradayMovingAverageService intradayMa, ObjectMapper objectMapper) {
        this.recommendations = recommendations;
        this.decisions = decisions;
        this.quotes = quotes;
        this.features = features;
        this.intradayMa = intradayMa;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DecisionEvaluationResponse evaluate(LocalDate date, BigDecimal targetRate, BigDecimal stopRate) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE).minusDays(1) : date;
        BigDecimal target = targetRate == null ? bd("3") : targetRate;
        BigDecimal stop = stopRate == null ? bd("-2") : stopRate;
        Instant evaluatedAt = Instant.now();
        List<OvernightPositionDecision> saved = recommendations.findByRecommendationDateOrderByRankAsc(targetDate)
                .stream()
                .map(recommendation -> evaluate(recommendation, evaluatedAt, target, stop))
                .map(decisions::save)
                .toList();
        return new DecisionEvaluationResponse(
                targetDate,
                evaluatedAt,
                saved.size(),
                count(saved, OvernightPositionDecisionType.EXTEND_HOLD),
                count(saved, OvernightPositionDecisionType.TAKE_PROFIT),
                count(saved, OvernightPositionDecisionType.SELL_WARNING),
                count(saved, OvernightPositionDecisionType.STOP_LOSS),
                OvernightPositionDecision.VERSION,
                saved.stream().map(OvernightPositionDecisionResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public List<OvernightPositionDecisionResponse> list(LocalDate date) {
        LocalDate targetDate = date == null ? LocalDate.now(MARKET_ZONE).minusDays(1) : date;
        return decisions.findLatestByRecommendationDate(targetDate).stream()
                .map(OvernightPositionDecisionResponse::from)
                .toList();
    }

    private OvernightPositionDecision evaluate(ClosingRecommendation recommendation, Instant evaluatedAt,
            BigDecimal targetRate, BigDecimal stopRate) {
        LocalDate today = evaluatedAt.atZone(MARKET_ZONE).toLocalDate();
        if (!today.isAfter(recommendation.getRecommendationDate())) {
            return pending(recommendation, evaluatedAt, "추천일 다음 거래일 전이라 판단을 대기합니다.");
        }
        try {
            StockQuote quote = quotes.getQuote(recommendation.getStock());
            MarketFeatureSnapshot feature = features.latest(recommendation.getStock().getStockCode()).orElse(null);
            IntradayMovingAverageFeature ma = intradayMa.calculate(recommendation.getStock().getId(), quote.quotedAt());
            DecisionContext context = context(recommendation, quote, feature, ma, targetRate, stopRate);
            OvernightPositionDecisionType decision = decide(context);
            return new OvernightPositionDecision(
                    recommendation,
                    evaluatedAt,
                    quote.currentPrice(),
                    context.returnRate(),
                    context.vwap(),
                    context.vwapDistanceRate(),
                    context.tradeStrength(),
                    ma.ma5(),
                    ma.ma20(),
                    ma.ma60(),
                    context.targetHit(),
                    context.stopHit(),
                    decision,
                    reason(decision, context, ma));
        } catch (RuntimeException error) {
            return pending(recommendation, evaluatedAt, "현재가 또는 실시간 feature를 가져오지 못했습니다: " + rootMessage(error));
        }
    }

    private DecisionContext context(ClosingRecommendation recommendation, StockQuote quote, MarketFeatureSnapshot feature,
            IntradayMovingAverageFeature ma, BigDecimal targetRate, BigDecimal stopRate) {
        BigDecimal returnRate = pct(quote.currentPrice(), recommendation.getBuyReferencePrice());
        BigDecimal vwap = feature == null ? null : feature.vwap();
        BigDecimal vwapDistanceRate = feature == null ? null : feature.vwapDistanceRate();
        BigDecimal tradeStrength = feature == null ? null : feature.tradeStrength();
        BigDecimal dayHighPullback = quote.highPrice() == null || quote.highPrice().signum() == 0 ? null
                : pct(quote.currentPrice(), quote.highPrice());
        return new DecisionContext(
                returnRate,
                vwap,
                vwapDistanceRate,
                tradeStrength,
                dayHighPullback,
                returnRate.compareTo(targetRate) >= 0,
                returnRate.compareTo(stopRate) <= 0,
                vwapDistanceRate != null && vwapDistanceRate.signum() < 0,
                tradeStrength != null && tradeStrength.compareTo(bd("95")) < 0,
                ma.ready() && ma.ma20Broken(),
                dayHighPullback != null && dayHighPullback.compareTo(bd("-1.5")) <= 0);
    }

    private OvernightPositionDecisionType decide(DecisionContext context) {
        if (context.stopHit())
            return OvernightPositionDecisionType.STOP_LOSS;
        if (context.targetHit() && trendHealthy(context))
            return OvernightPositionDecisionType.EXTEND_HOLD;
        if (context.targetHit())
            return OvernightPositionDecisionType.TAKE_PROFIT;
        if (context.vwapBroken() || context.ma20Broken() || context.weakTradeStrength() || context.pulledBackFromHigh())
            return OvernightPositionDecisionType.SELL_WARNING;
        return OvernightPositionDecisionType.HOLD;
    }

    private boolean trendHealthy(DecisionContext context) {
        return !context.vwapBroken()
                && !context.ma20Broken()
                && !context.weakTradeStrength()
                && !context.pulledBackFromHigh();
    }

    private OvernightPositionDecision pending(ClosingRecommendation recommendation, Instant evaluatedAt, String message) {
        return new OvernightPositionDecision(recommendation, evaluatedAt, null, null, null, null, null, null, null,
                null, false, false, OvernightPositionDecisionType.DATA_PENDING, reason(message));
    }

    private int count(List<OvernightPositionDecision> decisions, OvernightPositionDecisionType type) {
        return (int) decisions.stream().filter(decision -> decision.getDecision() == type).count();
    }

    private String reason(OvernightPositionDecisionType decision, DecisionContext context,
            IntradayMovingAverageFeature ma) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", OvernightPositionDecision.VERSION);
        payload.put("decision", decision.name());
        payload.put("returnRate", plain(context.returnRate()));
        payload.put("targetHit", context.targetHit());
        payload.put("stopHit", context.stopHit());
        payload.put("vwapBroken", context.vwapBroken());
        payload.put("ma20Broken", context.ma20Broken());
        payload.put("weakTradeStrength", context.weakTradeStrength());
        payload.put("pulledBackFromHigh", context.pulledBackFromHigh());
        payload.put("vwap", plain(context.vwap()));
        payload.put("vwapDistanceRate", plain(context.vwapDistanceRate()));
        payload.put("tradeStrength", plain(context.tradeStrength()));
        payload.put("dayHighPullbackRate", plain(context.dayHighPullbackRate()));
        payload.put("intradayMa", ma.toMap());
        return json(payload);
    }

    private String reason(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", OvernightPositionDecision.VERSION);
        payload.put("decision", OvernightPositionDecisionType.DATA_PENDING.name());
        payload.put("message", message);
        return json(payload);
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize overnight position decision reason", e);
        }
    }

    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() == 0)
            return BigDecimal.ZERO;
        return price.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(bd("100"))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record DecisionContext(BigDecimal returnRate, BigDecimal vwap, BigDecimal vwapDistanceRate,
            BigDecimal tradeStrength, BigDecimal dayHighPullbackRate, boolean targetHit, boolean stopHit,
            boolean vwapBroken, boolean weakTradeStrength, boolean ma20Broken, boolean pulledBackFromHigh) {
    }
}
