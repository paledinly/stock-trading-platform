/*
 * BROAD 후보 한 종목의 "지금 쓸 수 있는 가장 믿을 만한 시세"를 골라 주는 파일이에요.
 * 실시간 틱, 시세 캐시, 랭킹 API 관측값을 먼저 보고, 그래도 부족하면 REST 현재가 조회까지 시도해요.
 * MarketWideScannerService와 BroadEnrichmentQueue가 후보 시세를 채울 때 사용해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.quote.application.*;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/**
 * 후보 종목 시세를 여러 출처 중에서 골라 합쳐 주는 클래스예요.
 * 냉장고(캐시)와 방금 배달 온 재료(실시간 틱)를 먼저 확인하고, 정말 없을 때만 마트(REST 조회)에 다녀오는 요리사와 같아요.
 */
@Component
public class BroadQuoteResolver {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final QuoteProvider provider;
    private final QuoteStateStore ticks;
    private final QuoteSnapshotCache cache;
    private final BroadCollectionProperties properties;
    /**
     * 하는 일: 시세를 고를 때 필요한 출처들과 설정을 받아 둬요.
     * 받는 것: REST 시세 제공자, 실시간 틱 저장소, 시세 캐시(최근 값을 잠시 보관하는 곳), 수집 설정.
     */
    public BroadQuoteResolver(QuoteProvider provider, QuoteStateStore ticks, QuoteSnapshotCache cache,
            BroadCollectionProperties properties) {
        this.provider = provider; this.ticks = ticks; this.cache = cache; this.properties = properties;
    }
    /**
     * 하는 일: 후보 한 종목의 시세를 출처 우선순위에 따라 골라요.
     * 순서: 실시간 틱+오늘 캐시 병합 → 실시간 틱 단독 → 신선한 캐시 → 완전한 랭킹 관측값 → REST 조회.
     * 받는 것: BROAD 후보, REST 조회를 해도 되는지 여부(호출 예산이 남았는지).
     * 돌려주는 것: 고른 시세 데이터, 오류 메시지(없으면 null), REST를 실제로 시도했는지를 담은 Resolution.
     */
    public Resolution resolve(BroadCandidate candidate, boolean allowRest) {
        // 준비: 랭킹 API 관측값 중 신선하고 핵심 필드가 가장 많이 채워진(같으면 더 최근) 것을 골라요.
        Instant now = Instant.now();
        String code = candidate.stock().getStockCode();
        BroadQuoteData ranking = candidate.observations().stream().map(BroadQuoteData::fromRanking)
                .filter(row -> fresh(row.observedAt(), now, properties.cacheMaxAge()))
                .max(Comparator.comparingInt(BroadQuoteData::availableCoreFields).thenComparing(BroadQuoteData::observedAt))
                .orElse(null);
        // 오늘 날짜의, 충분히 최근인 실시간 틱만 써요.
        MarketTick tick = ticks.get(code).filter(row -> row.businessDate().equals(now.atZone(SEOUL).toLocalDate())
                && fresh(row.occurredAt(), now, properties.tickMaxAge())).orElse(null);
        StockQuote baseline = cache.today(code, now).orElse(null);
        // 1순위: 오늘 캐시보다 새로운 틱이 있으면 둘을 합쳐서 쓰고, 합친 값을 캐시에 다시 넣어요.
        if (tick != null && baseline != null && tick.occurredAt().isAfter(baseline.quotedAt())) {
            StockQuote merged = QuoteService.merge(baseline, tick);
            cache.remember(merged);
            return new Resolution(BroadQuoteData.fromQuote(merged, "REALTIME_CACHE"), null, false);
        }
        // 2순위: 틱만 있으면 틱으로 시세를 만들어요.
        // 등락률은 틱에 없어서, 랭킹 가격과 틱 가격이 같을 때만 랭킹의 등락률을 빌려 와요.
        // 이때 관측 시각은 둘 중 더 이른 시각을 써서 정보가 실제보다 새것처럼 보이지 않게 해요.
        if (tick != null) {
            boolean samePrice = ranking != null && ranking.price() != null && ranking.price().compareTo(tick.price()) == 0;
            BroadQuoteData realtime = new BroadQuoteData(tick.price(), samePrice ? ranking.changeRate() : null,
                    tick.cumulativeVolume(), tick.cumulativeTradingValue(), tick.openPrice(), tick.highPrice(),
                    tick.lowPrice(), tick.tradeStrength(), samePrice && ranking.observedAt().isBefore(tick.occurredAt())
                            ? ranking.observedAt() : tick.occurredAt(), samePrice ? "REALTIME_RANKING" : "REALTIME_PARTIAL");
            if (realtime.complete()) return new Resolution(realtime, null, false);
            // 틱 시세가 불완전하면, 랭킹 값이 없을 때의 대체값으로만 남겨 둬요.
            if (ranking == null) ranking = realtime;
        }
        // 3~4순위: 신선한 캐시, 그다음 핵심 필드가 다 찬 랭킹 관측값.
        StockQuote recent = cache.fresh(code, now, properties.cacheMaxAge()).orElse(null);
        if (recent != null) return new Resolution(BroadQuoteData.fromQuote(recent, "CACHE"), null, false);
        if (ranking != null && ranking.complete()) return new Resolution(ranking, null, false);
        // REST 호출 예산이 없으면 불완전한 값이라도 사유와 함께 돌려줘요.
        if (!allowRest) return new Resolution(ranking, "DETAIL_QUOTE_BUDGET_EXHAUSTED", false);
        // 마지막: REST 현재가 조회. 실패하면 가진 값과 오류 메시지를 함께 돌려줘요.
        try {
            StockQuote quote = provider.getQuote(candidate.stock());
            cache.remember(quote);
            return new Resolution(BroadQuoteData.fromQuote(quote, "REST"), null, true);
        } catch (RuntimeException error) {
            return new Resolution(ranking, error.getMessage(), true);
        }
    }
    /**
     * 하는 일: 어떤 시각이 "지금 기준 허용 나이 안"인지 확인해요. 미래 시각도 신선하지 않다고 봐요.
     * 받는 것: 확인할 시각, 현재 시각, 허용 나이.
     * 돌려주는 것: 신선하면 true.
     */
    private boolean fresh(Instant at, Instant now, Duration age) {
        return at != null && !at.isAfter(now) && !at.isBefore(now.minus(age));
    }
    /**
     * 시세 선택 결과예요. data는 고른 시세(없으면 null), error는 오류/사유, restAttempted는 REST 조회를 시도했는지예요.
     */
    public record Resolution(BroadQuoteData data, String error, boolean restAttempted) { }
}
