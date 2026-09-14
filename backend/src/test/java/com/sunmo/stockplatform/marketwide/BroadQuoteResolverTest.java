package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.kis.ranking.*;
import com.sunmo.stockplatform.market.config.BroadCollectionProperties;
import com.sunmo.stockplatform.market.application.QuoteStateStore;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.marketwide.application.BroadQuoteResolver;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.quote.application.*;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BroadQuoteResolverTest {
    private final QuoteProvider provider = mock(QuoteProvider.class);
    private final QuoteStateStore ticks = mock(QuoteStateStore.class);
    private final QuoteSnapshotCache cache = new QuoteSnapshotCache();
    private final BroadQuoteResolver resolver = new BroadQuoteResolver(provider, ticks, cache,
            new BroadCollectionProperties(100, 30, null, null, true));
    private final Stock stock = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, MarketType.STOCK,
            false, false, Instant.now());

    @Test
    void rankingCoreFieldsAvoidRestAndDoNotInventOhlc() {
        var result = resolver.resolve(candidate(true), true);
        assertThat(result.restAttempted()).isFalse();
        assertThat(result.data().complete()).isTrue();
        assertThat(result.data().source()).isEqualTo("RANKING:TURNOVER");
        assertThat(result.data().open()).isNull();
        assertThat(result.data().high()).isNull();
        verifyNoInteractions(provider);
    }

    @Test
    void partialRankingSurvivesFailedRest() {
        when(provider.getQuote(stock)).thenThrow(new IllegalStateException("EGW00201"));
        var result = resolver.resolve(candidate(false), true);
        assertThat(result.restAttempted()).isTrue();
        assertThat(result.data().price()).isEqualByComparingTo("1000");
        assertThat(result.data().tradingValue()).isNull();
        assertThat(result.data().complete()).isFalse();
        assertThat(result.error()).contains("EGW00201");
    }

    @Test
    void exhaustedBudgetPreservesPartialRankingWithoutRest() {
        var result = resolver.resolve(candidate(false), false);
        assertThat(result.restAttempted()).isFalse();
        assertThat(result.error()).isEqualTo("DETAIL_QUOTE_BUDGET_EXHAUSTED");
        assertThat(result.data().price()).isEqualByComparingTo("1000");
        verifyNoInteractions(provider);
    }

    @Test
    void freshTickUsesRealBaselineWithoutRestOrSubscriptionChanges() {
        cache.remember(quote(Instant.now().minusSeconds(120)));
        MarketTick tick = new MarketTick("005930", LocalDate.now(ZoneId.of("Asia/Seoul")), Instant.now().minusSeconds(1),
                bd("1100"), 1, 200, bd("210000"), 2);
        when(ticks.get("005930")).thenReturn(Optional.of(tick));
        var result = resolver.resolve(candidate(false), true);
        assertThat(result.data().source()).isEqualTo("REALTIME_CACHE");
        assertThat(result.data().price()).isEqualByComparingTo("1100");
        assertThat(result.data().changeRate()).isGreaterThan(bd("10"));
        assertThat(result.restAttempted()).isFalse();
        verifyNoInteractions(provider);
    }

    @Test
    void freshCacheAvoidsDuplicateRestAndStaleTickIsIgnored() {
        when(provider.getQuote(stock)).thenReturn(quote(Instant.now()));
        var noRanking = new BroadCandidate(stock, BigDecimal.ZERO, Map.of(), null);
        when(ticks.get("005930")).thenReturn(Optional.of(new MarketTick("005930",
                LocalDate.now(ZoneId.of("Asia/Seoul")), Instant.now().minusSeconds(300), bd("900"), 1, 100, bd("90000"), 1)));
        assertThat(resolver.resolve(noRanking, true).data().source()).isEqualTo("REST");
        assertThat(resolver.resolve(noRanking, true).data().source()).isEqualTo("CACHE");
        verify(provider, times(1)).getQuote(stock);
    }

    @Test
    void changedTickPriceDoesNotReuseRankingChangeRate() {
        when(ticks.get("005930")).thenReturn(Optional.of(new MarketTick("005930",
                LocalDate.now(ZoneId.of("Asia/Seoul")), Instant.now().minusSeconds(1), bd("1100"), 1, 100, bd("110000"), 1)));
        var result = resolver.resolve(new BroadCandidate(stock, BigDecimal.ZERO, Map.of(), null), false);
        assertThat(result.data().source()).isEqualTo("REALTIME_PARTIAL");
        assertThat(result.data().changeRate()).isNull();
        assertThat(result.data().open()).isNull();
    }

    private BroadCandidate candidate(boolean complete) {
        var entry = new KisRankingEntry("005930", "삼성전자", 1, bd("1000"), bd("10"), 100,
                complete ? bd("100000") : null, null);
        return new BroadCandidate(stock, bd("500"), Map.of(RankingType.TURNOVER, 1), null,
                List.of(new BroadCandidate.RankingObservation(RankingType.TURNOVER, entry, Instant.now().minusSeconds(2))));
    }
    private StockQuote quote(Instant at) {
        return new StockQuote("005930", "삼성전자", "KOSPI", bd("1000"), bd("100"), bd("11.11"),
                bd("950"), bd("1050"), bd("940"), 100, bd("100000"), at);
    }
    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
