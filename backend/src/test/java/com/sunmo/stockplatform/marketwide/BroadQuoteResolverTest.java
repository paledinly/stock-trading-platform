/*
 * BroadQuoteResolver가 전시장 스캔 후보의 시세를 어디서 가져올지(순위 응답, 실시간 틱, 캐시, REST 조회) 고르는지 확인해요.
 * KIS REST 호출을 아끼면서도 없는 값을 지어내지 않고, 오래된 틱을 쓰지 않아야 해요.
 */
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

/** 전시장 후보 시세 출처 선택 규칙을 점검하는 테스트예요. */
class BroadQuoteResolverTest {
    // 시세 조회와 틱 저장소는 목(mock, 가짜 객체), 시세 캐시는 실제 객체예요.
    private final QuoteProvider provider = mock(QuoteProvider.class);
    private final QuoteStateStore ticks = mock(QuoteStateStore.class);
    private final QuoteSnapshotCache cache = new QuoteSnapshotCache();
    private final BroadQuoteResolver resolver = new BroadQuoteResolver(provider, ticks, cache,
            new BroadCollectionProperties(100, 30, null, null, true));
    private final Stock stock = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, MarketType.STOCK,
            false, false, Instant.now());

    /**
     * 순위 응답에 핵심 필드가 다 있으면 REST를 부르지 않고, 시가·고가는 지어내지 않는지 확인해요.
     * 준비: 거래대금까지 들어 있는 거래대금 순위 후보를 넘겨요.
     * 기대: REST 시도 없음, 완전한 데이터, 출처 RANKING:TURNOVER, 시가·고가 null이에요.
     */
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

    /**
     * 순위 값이 일부만 있고 REST가 실패해도 가진 값은 남기는지 확인해요.
     * 준비: 거래대금 없는 후보와, 한도 초과(EGW00201) 예외를 던지는 REST를 준비해요.
     * 기대: REST 시도함, 가격 1000 유지, 거래대금 null, 불완전, 오류에 EGW00201이 담겨요.
     */
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

    /**
     * 상세 조회 예산이 바닥나면 REST 없이 순위 값만 남기는지 확인해요.
     * 준비: 거래대금 없는 후보를 예산 없음(false)으로 해석해요.
     * 기대: REST 시도 없음, 오류 DETAIL_QUOTE_BUDGET_EXHAUSTED, 가격 1000이에요.
     */
    @Test
    void exhaustedBudgetPreservesPartialRankingWithoutRest() {
        var result = resolver.resolve(candidate(false), false);
        assertThat(result.restAttempted()).isFalse();
        assertThat(result.error()).isEqualTo("DETAIL_QUOTE_BUDGET_EXHAUSTED");
        assertThat(result.data().price()).isEqualByComparingTo("1000");
        verifyNoInteractions(provider);
    }

    /**
     * 최신 실시간 틱이 있으면 캐시된 실제 기준 시세로 등락률을 계산하고 REST·구독은 건드리지 않는지 확인해요.
     * 준비: 2분 전 시세(전일 대비 100원)를 캐시에 두고 1초 전 1100원 틱을 넣어요.
     * 기대: 출처 REALTIME_CACHE, 가격 1100, 등락률 10% 초과, REST 시도 없음이에요.
     */
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

    /**
     * 신선한 캐시가 있으면 REST를 다시 부르지 않고, 5분 지난 틱은 무시하는지 확인해요.
     * 준비: 순위 정보 없는 후보, 300초 전 틱, 정상 REST 시세를 준비하고 두 번 해석해요.
     * 기대: 첫 번째 출처 REST, 두 번째 CACHE이고 REST 호출은 1번뿐이에요.
     */
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

    /**
     * 틱 가격이 바뀌었으면 순위 응답의 등락률을 재사용하지 않는지 확인해요.
     * 준비: 순위 정보 없는 후보, 1초 전 1100원 틱, 예산 없음으로 해석해요.
     * 기대: 출처 REALTIME_PARTIAL, 등락률·시가 null이에요.
     */
    @Test
    void changedTickPriceDoesNotReuseRankingChangeRate() {
        when(ticks.get("005930")).thenReturn(Optional.of(new MarketTick("005930",
                LocalDate.now(ZoneId.of("Asia/Seoul")), Instant.now().minusSeconds(1), bd("1100"), 1, 100, bd("110000"), 1)));
        var result = resolver.resolve(new BroadCandidate(stock, BigDecimal.ZERO, Map.of(), null), false);
        assertThat(result.data().source()).isEqualTo("REALTIME_PARTIAL");
        assertThat(result.data().changeRate()).isNull();
        assertThat(result.data().open()).isNull();
    }

    /** 거래대금 순위 후보를 만드는 도우미예요(complete가 false면 거래대금을 비워요). */
    private BroadCandidate candidate(boolean complete) {
        var entry = new KisRankingEntry("005930", "삼성전자", 1, bd("1000"), bd("10"), 100,
                complete ? bd("100000") : null, null);
        return new BroadCandidate(stock, bd("500"), Map.of(RankingType.TURNOVER, 1), null,
                List.of(new BroadCandidate.RankingObservation(RankingType.TURNOVER, entry, Instant.now().minusSeconds(2))));
    }
    /** 지정한 시각의 REST 시세(현재가 1000)를 만드는 도우미예요. */
    private StockQuote quote(Instant at) {
        return new StockQuote("005930", "삼성전자", "KOSPI", bd("1000"), bd("100"), bd("11.11"),
                bd("950"), bd("1050"), bd("940"), 100, bd("100000"), at);
    }
    /** 문자열을 BigDecimal로 바꾸는 도우미예요. */
    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
