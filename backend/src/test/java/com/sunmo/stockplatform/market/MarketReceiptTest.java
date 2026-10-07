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

class MarketReceiptTest {
    @Test void preservesCallbackReceiptAndLossInPublishedObservation() {
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
        service.onTick(tick,receipt,true);
        verify(publisher).publishEvent(new ObservedMarketTick(tick,null,receipt,true));
        verifyNoInteractions(transactions);
        verify(scanner,never()).evaluate(any());
        assertThat(diagnostics.snapshot().timings()).containsKeys("redisWrite","quoteSse","tickBody");
    }
}
