/*
 * BroadCandidateCollector가 KIS 순위 API 여러 개를 합쳐 전시장 스캔 후보를 모으는지 확인해요.
 * 일부 순위 조회가 실패해도 계속 진행하고, 관리종목·구조화 상품(ETN 등)은 상세 조회 전에 걸러야 해요.
 */
package com.sunmo.stockplatform.marketwide;

import com.sunmo.stockplatform.kis.ranking.KisRankingEntry;
import com.sunmo.stockplatform.kis.ranking.MarketRankingProvider;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.marketwide.application.BroadCandidateCollector;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 전시장 후보 수집 규칙을 점검하는 테스트예요. */
class BroadCandidateCollectorTest {
    // 순위 조회와 종목 저장소는 목(mock, 가짜 객체)이고, 수집기만 실제 객체예요.
    private final MarketRankingProvider rankings = mock(MarketRankingProvider.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final BroadCandidateCollector collector = new BroadCandidateCollector(rankings, stocks,
            new com.sunmo.stockplatform.market.config.BroadCollectionProperties(100, 30, null, null, true));

    /**
     * 순위 출처를 합치고, 거래 부적합 종목을 거르며, 일부 실패 뒤에도 계속하는지 확인해요.
     * 준비: 거래대금 순위(삼성전자 2위, 관리종목 1위), 거래량 순위(삼성전자 5위), 체결강도 순위는 실패하게 해요.
     * 기대: 대체 모드가 아니고 후보는 삼성전자만, 순위 기록 2개, 실패 출처는 TRADE_STRENGTH 하나예요.
     */
    @Test
    void mergesSourcesFiltersTradabilityAndContinuesAfterPartialFailure() {
        // 준비
        Stock samsung = stock("005930", "삼성전자", false, false);
        Stock managed = stock("123456", "관리종목", true, false);
        when(rankings.fetch(any(), any(), anyInt())).thenReturn(List.of());
        when(rankings.fetch(eq(RankingType.TURNOVER), eq(Market.KOSPI), anyInt()))
                .thenReturn(List.of(entry("005930", 2), entry("123456", 1)));
        when(rankings.fetch(eq(RankingType.VOLUME), eq(Market.KOSPI), anyInt()))
                .thenReturn(List.of(entry("005930", 5)));
        when(rankings.fetch(eq(RankingType.TRADE_STRENGTH), eq(Market.KOSPI), anyInt()))
                .thenThrow(new IllegalStateException("temporary failure"));
        when(stocks.findByStockCodeIn(any())).thenReturn(List.of(samsung, managed));

        // 실행
        var result = collector.collect(Market.KOSPI, 40, false);

        // 확인
        assertThat(result.fallback()).isFalse();
        assertThat(result.candidates()).extracting(item -> item.stock().getStockCode())
                .containsExactly("005930");
        assertThat(result.candidates().getFirst().ranks())
                .containsEntry(RankingType.TURNOVER, 2)
                .containsEntry(RankingType.VOLUME, 5);
        assertThat(result.candidates().getFirst().observations()).hasSize(2);
        assertThat(result.sources()).filteredOn(source -> !source.success())
                .extracting(source -> source.type()).containsExactly(RankingType.TRADE_STRENGTH);
    }

    /**
     * 모든 순위가 실패하거나 비었을 때만 기존 종목군(universe)으로 대신하는지 확인해요.
     * 준비: 모든 순위 조회가 "offline" 예외를 던지게 해요.
     * 기대: fallback이 true이고 후보 1개이며 모든 출처가 실패로 표시돼요.
     */
    @Test
    void fallsBackToExistingUniverseOnlyWhenEveryRankingIsEmptyOrFailed() {
        Stock samsung = stock("005930", "삼성전자", false, false);
        when(rankings.fetch(any(), any(), anyInt())).thenThrow(new IllegalStateException("offline"));
        when(stocks.broadScanUniverse(eq(null), eq(false), any())).thenReturn(List.of(samsung));

        var result = collector.collect(null, 40, false);

        assertThat(result.fallback()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.sources()).allMatch(source -> !source.success());
    }

    /** 종목코드와 순위만 채운 순위 항목을 만드는 도우미예요. */
    private KisRankingEntry entry(String code, int rank) {
        return new KisRankingEntry(code, code, rank, null, null, 0, null, null);
    }

    /**
     * 마스터 플래그가 없어도 이름으로 구조화 상품(인버스·ETN 등)을 상세 조회 전에 빼는지 확인해요.
     * 준비: 순위에 삼성전자와 "미래에셋 인버스 2X ETN"이 들어오게 해요.
     * 기대: 세 번째 인자가 false면 1개, true면 2개예요(세 번째 인자 includeEtf는 ETF·ETN 같은 상품 포함 여부예요).
     */
    @Test
    void excludesStructuredNamesBeforeDetailLookupEvenWhenMasterFlagsAreMissing() {
        when(rankings.fetch(any(), any(), anyInt())).thenReturn(List.of(entry("005930", 1), entry("123456", 2)));
        when(stocks.findByStockCodeIn(any())).thenReturn(List.of(stock("005930", "삼성전자", false, false),
                stock("123456", "미래에셋 인버스 2X ETN", false, false)));
        assertThat(collector.collect(null, 40, false).candidates()).hasSize(1);
        assertThat(collector.collect(null, 40, true).candidates()).hasSize(2);
    }

    /** 관리종목·거래정지 여부를 지정한 코스피 종목을 만드는 도우미예요. */
    private Stock stock(String code, String name, boolean managed, boolean halted) {
        return new Stock(code, "KR7" + code + "003", name, Market.KOSPI, MarketType.STOCK,
                managed, halted, Instant.now());
    }
}
