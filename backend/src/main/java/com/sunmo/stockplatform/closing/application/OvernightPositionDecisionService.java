/*
 * 추천 다음날 장중에 현재가·VWAP(거래량 가중 평균가)·체결강도·5분봉 이동평균을 보고 "계속 들고 갈지, 팔지"를 참고 판단으로 남기는 서비스예요.
 * 손절(손실 한도에서 팔기)/익절(목표 수익에서 팔기)/매도 경고/보유 연장 같은 판단을 저장하지만 실제 주문은 하지 않아요.
 * 추천 API(ClosingRecommendationController)가 판단 실행과 조회에 사용해요.
 */
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

/**
 * 다음날 보유 종목의 상태를 진단해 판단 기록을 남기는 클래스예요.
 * 등산 중 일정 높이(목표)에 닿았는지, 하산 경계선(손절)을 넘었는지, 날씨(추세 지표)가 나빠지는지를 보고 "더 오를지, 내려갈지"를 메모하는 안내인과 같아요.
 */
@Service
public class OvernightPositionDecisionService {
    private final ClosingRecommendationRepository recommendations;
    private final OvernightPositionDecisionRepository decisions;
    private final QuoteProvider quotes;
    private final MarketFeatureEngine features;
    private final IntradayMovingAverageService intradayMa;
    private final ObjectMapper objectMapper;
    // 현재 시각, 오늘 날짜, 직전·다음 거래일은 모두 ClosingTradingCalendar 정책을 따라요.
    private final ClosingTradingCalendar calendar;

    /**
     * 하는 일: 의존 객체를 받아 서비스를 만들어요.
     * 받는 것: 추천 저장소, 판단 저장소, 현재가 제공자, 시장 Feature 엔진, 5분봉 이동평균 서비스, JSON 변환기, 거래일 달력.
     * 돌려주는 것: 없음(생성자).
     */
    public OvernightPositionDecisionService(ClosingRecommendationRepository recommendations,
            OvernightPositionDecisionRepository decisions, QuoteProvider quotes, MarketFeatureEngine features,
            IntradayMovingAverageService intradayMa, ObjectMapper objectMapper, ClosingTradingCalendar calendar) {
        this.recommendations = recommendations;
        this.decisions = decisions;
        this.quotes = quotes;
        this.features = features;
        this.intradayMa = intradayMa;
        this.objectMapper = objectMapper;
        this.calendar = calendar;
    }

    /**
     * 하는 일: 실행 ID 없이 추천일 기준으로 판단을 실행해요.
     * 받는 것: 추천일, 목표 수익률(%), 손절 수익률(%). 돌려주는 것: 판단 실행 결과 응답.
     */
    @Transactional
    public DecisionEvaluationResponse evaluate(LocalDate date, BigDecimal targetRate, BigDecimal stopRate) {
        return evaluate(date, targetRate, stopRate, null);
    }

    /**
     * 하는 일: 추천일(또는 실행 ID)의 추천 종목마다 지금 시점 판단을 만들어 저장해요.
     * 받는 것: 추천일(없으면 오늘 직전 거래일), 목표 수익률(없으면 +3%), 손절 수익률(없으면 -2%), 실행 ID.
     * 돌려주는 것: 판단 종류별 건수와 종목별 판단을 담은 응답.
     */
    @Transactional
    public DecisionEvaluationResponse evaluate(LocalDate date, BigDecimal targetRate, BigDecimal stopRate, Long runId) {
        // 현재 시각과 오늘 날짜는 ClosingTradingCalendar에서 받아요. 날짜를 안 주면 오늘의 직전 거래일(월요일이면 금요일)을 써요.
        LocalDate targetDate = date == null ? calendar.previousTradingDay(calendar.today()) : date;
        BigDecimal target = targetRate == null ? bd("3") : targetRate;
        BigDecimal stop = stopRate == null ? bd("-2") : stopRate;
        Instant evaluatedAt = calendar.now();
        // 처리: 추천마다 판단을 만들고 바로 저장해요(같은 추천에 판단이 여러 번 쌓이고, 조회는 최신 것을 보여 줘요).
        List<OvernightPositionDecision> saved = (runId == null
                ? recommendations.findByRecommendationDateOrderByRankAsc(targetDate)
                : recommendations.findByRunIdOrderByRankAsc(runId))
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

    /**
     * 하는 일: 추천일 기준으로 최신 판단을 조회해요(읽기 전용).
     * 받는 것: 추천일. 돌려주는 것: 종목별 최신 판단 목록.
     */
    @Transactional(readOnly = true)
    public List<OvernightPositionDecisionResponse> list(LocalDate date) {
        return list(date, null);
    }

    /**
     * 하는 일: 추천일 또는 실행 ID 기준으로 최신 판단을 조회해요(읽기 전용).
     * 받는 것: 추천일(없으면 오늘 직전 거래일), 실행 ID. 돌려주는 것: 종목별 최신 판단 목록.
     */
    @Transactional(readOnly = true)
    public List<OvernightPositionDecisionResponse> list(LocalDate date, Long runId) {
        LocalDate targetDate = date == null ? calendar.previousTradingDay(calendar.today()) : date;
        return (runId == null ? decisions.findLatestByRecommendationDate(targetDate)
                : decisions.findLatestByRunId(runId)).stream()
                .map(OvernightPositionDecisionResponse::from)
                .toList();
    }

    /**
     * 하는 일: 추천 한 건의 현재 판단을 만들어요.
     * 받는 것: 추천, 판단 시각, 목표·손절 수익률(%).
     * 돌려주는 것: 판단 엔티티. 아직 다음 거래일이 아니거나, 자료를 못 받거나, 매수 기준가·현재가가 없으면 DATA_PENDING(대기) 판단.
     */
    private OvernightPositionDecision evaluate(ClosingRecommendation recommendation, Instant evaluatedAt,
            BigDecimal targetRate, BigDecimal stopRate) {
        // 준비: 오늘이 추천일의 다음 거래일(ClosingTradingCalendar 기준)보다 앞이면 판단을 미뤄요.
        // 그래서 추천일 다음날이 주말·휴장일이면 그날에는 지난 시세로 판단을 만들지 않아요.
        LocalDate today = evaluatedAt.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        if (today.isBefore(calendar.nextTradingDay(recommendation.getRecommendationDate()))) {
            return pending(recommendation, evaluatedAt, "추천일 다음 거래일 전이라 판단을 대기합니다.");
        }
        try {
            // 처리: 현재가, 최신 시장 Feature, 현재가 시세 시각 기준 5분봉 이동평균을 모아 판단 재료를 만들고 판단해요.
            StockQuote quote = quotes.getQuote(recommendation.getStock());
            MarketFeatureSnapshot feature = features.latest(recommendation.getStock().getStockCode()).orElse(null);
            IntradayMovingAverageFeature ma = intradayMa.calculate(recommendation.getStock().getId(), quote.quotedAt());
            // 매수 기준가나 현재가가 없거나 0이면 수익률을 만들 수 없으니 0%로 두지 않고 대기 판단으로 남겨요.
            BigDecimal returnRate = pct(quote.currentPrice(), recommendation.getBuyReferencePrice());
            if (returnRate == null)
                return pending(recommendation, evaluatedAt, "매수 기준가 또는 현재가가 없어 수익률을 계산할 수 없습니다.");
            DecisionContext context = context(returnRate, quote, feature, ma, targetRate, stopRate);
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
        // 어떤 재료든 못 가져오면 오류 원인 메시지와 함께 대기 판단으로 남겨요.
        } catch (RuntimeException error) {
            return pending(recommendation, evaluatedAt, "현재가 또는 실시간 feature를 가져오지 못했습니다: " + rootMessage(error));
        }
    }

    /**
     * 하는 일: 판단에 필요한 수익률과 약세 신호들을 계산해 한 묶음으로 만들어요.
     * 받는 것: 수익률(추천 매수 기준가 대비 현재가 변화율 %, null 아님), 현재가 시세, 시장 Feature(없을 수 있음),
     * 5분봉 이동평균, 목표·손절 수익률(%).
     * 돌려주는 것: 판단 재료 묶음(DecisionContext).
     */
    private DecisionContext context(BigDecimal returnRate, StockQuote quote, MarketFeatureSnapshot feature,
            IntradayMovingAverageFeature ma, BigDecimal targetRate, BigDecimal stopRate) {
        BigDecimal vwap = feature == null ? null : feature.vwap();
        BigDecimal vwapDistanceRate = feature == null ? null : feature.vwapDistanceRate();
        BigDecimal tradeStrength = feature == null ? null : feature.tradeStrength();
        // 당일 고가 대비 현재가 하락률(%)이에요. 고가가 없으면 계산하지 않아요.
        BigDecimal dayHighPullback = quote.highPrice() == null || quote.highPrice().signum() == 0 ? null
                : pct(quote.currentPrice(), quote.highPrice());
        return new DecisionContext(
                returnRate,
                vwap,
                vwapDistanceRate,
                tradeStrength,
                dayHighPullback,
                // 약세 신호: VWAP 아래(거리율<0), 체결강도(매수 체결량/매도 체결량×100) 95 미만, 5분봉 MA20 이탈, 고가 대비 -1.5% 이하 하락.
                returnRate.compareTo(targetRate) >= 0,
                returnRate.compareTo(stopRate) <= 0,
                vwapDistanceRate != null && vwapDistanceRate.signum() < 0,
                tradeStrength != null && tradeStrength.compareTo(bd("95")) < 0,
                ma.ready() && ma.ma20Broken(),
                dayHighPullback != null && dayHighPullback.compareTo(bd("-1.5")) <= 0);
    }

    /**
     * 하는 일: 판단 재료로 판단 종류를 정해요. 순서: 손절 → (목표 도달+추세 양호) 보유 연장 → 목표 도달 익절 → 약세 신호 매도 경고 → 보유.
     * 받는 것: 판단 재료 묶음. 돌려주는 것: 판단 종류.
     */
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

    /**
     * 하는 일: 약세 신호가 하나도 없는지 확인해요.
     * 받는 것: 판단 재료 묶음. 돌려주는 것: VWAP·MA20·체결강도·고가 대비 하락 신호가 모두 없으면 true.
     */
    private boolean trendHealthy(DecisionContext context) {
        return !context.vwapBroken()
                && !context.ma20Broken()
                && !context.weakTradeStrength()
                && !context.pulledBackFromHigh();
    }

    /**
     * 하는 일: 숫자 값을 비운 대기(DATA_PENDING) 판단을 만들어요.
     * 받는 것: 추천, 판단 시각, 대기 이유 메시지. 돌려주는 것: 대기 판단 엔티티.
     */
    private OvernightPositionDecision pending(ClosingRecommendation recommendation, Instant evaluatedAt, String message) {
        return new OvernightPositionDecision(recommendation, evaluatedAt, null, null, null, null, null, null, null,
                null, false, false, OvernightPositionDecisionType.DATA_PENDING, reason(message));
    }

    /**
     * 하는 일: 특정 판단 종류가 몇 건인지 세요.
     * 받는 것: 판단 목록, 판단 종류. 돌려주는 것: 건수.
     */
    private int count(List<OvernightPositionDecision> decisions, OvernightPositionDecisionType type) {
        return (int) decisions.stream().filter(decision -> decision.getDecision() == type).count();
    }

    /**
     * 하는 일: 판단 근거(버전, 판단, 수익률, 각 신호와 지표 값, 5분봉 이동평균)를 JSON 문자열로 만들어요.
     * 받는 것: 판단 종류, 판단 재료, 5분봉 이동평균. 돌려주는 것: 근거 JSON.
     */
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

    /**
     * 하는 일: 대기 판단의 근거(버전, DATA_PENDING, 메시지)를 JSON 문자열로 만들어요.
     * 받는 것: 메시지. 돌려주는 것: 근거 JSON.
     */
    private String reason(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", OvernightPositionDecision.VERSION);
        payload.put("decision", OvernightPositionDecisionType.DATA_PENDING.name());
        payload.put("message", message);
        return json(payload);
    }

    /**
     * 하는 일: Map을 JSON 문자열로 바꿔요.
     * 받는 것: 키-값 Map. 돌려주는 것: JSON 문자열(실패하면 IllegalStateException).
     */
    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize overnight position decision reason", e);
        }
    }

    /**
     * 하는 일: 기준가 대비 가격 변화율(%)을 구해요.
     * 받는 것: 가격, 기준가. 돌려주는 것: 소수 6자리 변화율, 값이 없거나 기준가가 0 이하이면 null(자료 없음, 0%가 아니에요).
     */
    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() <= 0)
            return null;
        return price.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(bd("100"))
                .setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 하는 일: 숫자를 지수 표기 없는 문자열로 바꿔요.
     * 받는 것: 숫자(없을 수 있음). 돌려주는 것: 숫자 문자열, null이면 빈 문자열.
     */
    private String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /**
     * 하는 일: 예외의 가장 안쪽 원인을 찾아 메시지를 꺼내요.
     * 받는 것: 예외. 돌려주는 것: 원인 메시지, 없으면 예외 클래스 이름.
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * 하는 일: 문자열로 BigDecimal을 만들어요.
     * 받는 것: 숫자 문자열. 돌려주는 것: BigDecimal.
     */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * 판단 재료 묶음이에요: 수익률, VWAP와 거리율, 체결강도, 고가 대비 하락률, 목표·손절 도달 여부, 네 가지 약세 신호.
     */
    private record DecisionContext(BigDecimal returnRate, BigDecimal vwap, BigDecimal vwapDistanceRate,
            BigDecimal tradeStrength, BigDecimal dayHighPullbackRate, boolean targetHit, boolean stopHit,
            boolean vwapBroken, boolean weakTradeStrength, boolean ma20Broken, boolean pulledBackFromHigh) {
    }
}
