/*
 * MarketDataService가 실시간 틱을 받을 때 원천 시각과 수신 시각, 손실 여부를 관찰 이벤트에 그대로 담는지 확인해요.
 * 원천 발생시각과 수신시각을 섞으면 지연 분석이 틀어지므로 중요해요.
 */
package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.market.domain.MarketTick;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.scanner.application.ScannerEngine;
import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** 틱 수신 기록(receipt) 보존을 점검하는 테스트예요. */
class MarketReceiptTest {
    /**
     * 틱 처리 시 수신 시각과 손실 표시가 발행 이벤트에 보존되는지 확인해요.
     * 준비: 목(mock, 가짜 객체) 의존성으로 서비스를 만들고, 원천보다 300ms 늦게 받은 틱을 넣어요.
     * 기대: ObservedMarketTick 이벤트가 발행되고, 트랜잭션·스캐너는 호출되지 않으며, 처리 시간 측정 항목이 남아요.
     */
    @Test void preservesCallbackReceiptAndLossInPublishedObservation() {
        // 준비
        var publisher=mock(ApplicationEventPublisher.class);
        var policy=mock(RealtimeMarketProperties.class);when(policy.candleWatermark()).thenReturn(Duration.ofSeconds(2));
        var diagnostics=new RealtimeDiagnostics();
        var transactions=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var scanner=mock(ScannerEngine.class);
        var service=new MarketDataService(mock(QuoteStateStore.class),mock(StockRepository.class),mock(StockCandleRepository.class),
                mock(MarketEventGateway.class),policy,scanner,mock(DetectionPerformanceTracker.class),
                diagnostics,mock(MarketFeatureEngine.class),publisher,transactions);
        var source=Instant.parse("2026-10-06T00:00:01Z"); var receipt=source.plusMillis(300);
        var tick=new MarketTick("005930",LocalDate.of(2026,10,6),source,BigDecimal.TEN,1,1,BigDecimal.TEN,1);
        // 실행
        service.onTick(tick,receipt,true);
        // 확인
        verify(publisher).publishEvent(new ObservedMarketTick(tick,null,receipt,true));
        verifyNoInteractions(transactions);
        verify(scanner,never()).evaluate(any());
        assertThat(diagnostics.snapshot().timings()).containsKeys("redisWrite","quoteSse","tickBody");
    }
}
