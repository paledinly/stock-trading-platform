/*
 * BroadSnapshotService가 전시장 스캔 결과를 시간 구간별 스냅샷으로 저장하는지 확인해요.
 * 시세를 못 받은 후보에 값을 지어내지 않고, 늦게 보강한 값은 원래 구간을 덮지 않아야 해요.
 */
package com.sunmo.stockplatform.marketwide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.kis.ranking.RankingType;
import com.sunmo.stockplatform.marketwide.application.BroadSnapshotService;
import com.sunmo.stockplatform.marketwide.domain.*;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketBroadSnapshotRepository;
import com.sunmo.stockplatform.quote.domain.StockQuote;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.MarketType;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 전시장 스냅샷 저장 규칙을 점검하는 테스트예요. */
class BroadSnapshotServiceTest {
    // 저장소는 목(mock, 가짜 객체)으로 둬요.
    private final MarketBroadSnapshotRepository repository = mock(MarketBroadSnapshotRepository.class);
    private final BroadSnapshotService service = new BroadSnapshotService(repository,
            new ObjectMapper().findAndRegisterModules());

    /**
     * 수집 성공과 실패 후보를 구간 시각으로 저장하고, 실패한 쪽에 시세를 지어내지 않는지 확인해요.
     * 준비: 05:31:59에 시세가 있는 삼성전자와 "quote timeout"으로 실패한 SK하이닉스를 저장해요.
     * 기대: 구간 시각 05:30, 성공 쪽은 BROAD_C·COLLECTED·현재가 70000, 실패 쪽은 INSUFFICIENT·QUOTE_FAILED·현재가 null이에요.
     */
    @Test
    void bucketsAndStoresCollectedAndFailedCandidatesWithoutInventingQuoteValues() {
        // 준비
        Stock samsung = stock("005930", "삼성전자");
        Stock hynix = stock("000660", "SK하이닉스");
        BroadCandidate first = new BroadCandidate(samsung, new BigDecimal("500"),
                Map.of(RankingType.TURNOVER, 1), new BigDecimal("145.2"));
        BroadCandidate second = new BroadCandidate(hynix, new BigDecimal("300"),
                Map.of(RankingType.VOLUME, 2), null);
        StockQuote quote = new StockQuote("005930", "삼성전자", "KOSPI", new BigDecimal("70000"),
                new BigDecimal("1000"), new BigDecimal("1.45"), new BigDecimal("69000"),
                new BigDecimal("70500"), new BigDecimal("68800"), 123456L,
                new BigDecimal("8641975200"), Instant.parse("2026-09-07T05:31:20Z"));
        when(repository.findBySessionDateAndCapturedAtAndStockId(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 실행
        var saved = service.save(Instant.parse("2026-09-07T05:31:59Z"), List.of(
                new BroadSnapshotService.Capture(first, quote, new BigDecimal("81.2"), null),
                new BroadSnapshotService.Capture(second, null, new BigDecimal("30"), "quote timeout")));

        // 확인
        MarketBroadSnapshot collected = saved.get("005930");
        MarketBroadSnapshot failed = saved.get("000660");
        assertThat(collected.getCapturedAt()).isEqualTo(Instant.parse("2026-09-07T05:30:00Z"));
        assertThat(collected.getDataQuality()).isEqualTo(BroadSnapshotQuality.BROAD_C);
        assertThat(collected.getCollectionStatus()).isEqualTo(BroadSnapshotStatus.COLLECTED);
        assertThat(collected.getCurrentPrice()).isEqualByComparingTo("70000");
        assertThat(collected.getRankingSources()).contains("TURNOVER").contains(MarketBroadSnapshot.SOURCE_VERSION);
        assertThat(failed.getDataQuality()).isEqualTo(BroadSnapshotQuality.INSUFFICIENT);
        assertThat(failed.getCollectionStatus()).isEqualTo(BroadSnapshotStatus.QUOTE_FAILED);
        assertThat(failed.getCurrentPrice()).isNull();
        assertThat(failed.getExclusionReason()).isEqualTo("quote timeout");
    }

    /**
     * 보강(enrichment) 결과는 실제 완료 시각으로 새로 추가하고 원래 구간 행은 건드리지 않는지 확인해요.
     * 준비: 05:31:21에 완료된 REST 보강 시세를 저장해요.
     * 기대: 수집 시각은 완료 시각, 시세 시각은 관측 시각, 출처는 ENRICHED_REST이고 기존 구간 행 조회는 없어요.
     */
    @Test
    void enrichmentAppendsAtActualTimeWithoutUpdatingOriginalBucket() {
        var candidate = new BroadCandidate(stock("005930", "삼성전자"), BigDecimal.TEN, Map.of(), null);
        Instant completed = Instant.parse("2026-09-14T05:31:21Z");
        var data = new BroadQuoteData(BigDecimal.TEN, BigDecimal.ONE, 100L, BigDecimal.valueOf(1000),
                null, null, null, null, completed.minusSeconds(1), "REST");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var saved = service.saveEnriched(completed, candidate, data);
        assertThat(saved.getCapturedAt()).isEqualTo(completed);
        assertThat(saved.getQuotedAt()).isEqualTo(data.observedAt());
        assertThat(saved.getQuoteSource()).isEqualTo("ENRICHED_REST");
        verify(repository, never()).findBySessionDateAndCapturedAtAndStockId(any(), any(), any());
    }

    /** 코스피 일반 종목을 만드는 도우미예요. */
    private Stock stock(String code, String name) {
        return new Stock(code, "KR7" + code + "003", name, Market.KOSPI, MarketType.STOCK,
                false, false, Instant.now());
    }
}
