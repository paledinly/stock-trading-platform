package com.sunmo.stockplatform.intraday;

import com.sunmo.stockplatform.market.application.*;
import com.sunmo.stockplatform.market.feature.application.MarketFeatureEngine;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.scanner.application.ScannerEngine;
import com.sunmo.stockplatform.analytics.application.DetectionPerformanceTracker;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Duration;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class IntradayMarketWiringTest {
    @Test void originalTickPathPublishesCommonFeatureAndIndependentReceiptTime() {
        var properties = mock(RealtimeMarketProperties.class); when(properties.candleWatermark()).thenReturn(Duration.ofSeconds(2));
        var features = mock(MarketFeatureEngine.class); var publisher = mock(ApplicationEventPublisher.class);
        var input = IntradayEngineTest.breakoutInputs().getFirst();
        when(features.onTick(input.observation().tick())).thenReturn(input.observation().feature());
        var service = new MarketDataService(mock(QuoteStateStore.class), mock(StockRepository.class), mock(StockCandleRepository.class),
                mock(MarketEventGateway.class), properties, mock(ScannerEngine.class), mock(DetectionPerformanceTracker.class),
                mock(RealtimeDiagnostics.class), features, publisher);
        service.onTick(input.observation().tick());
        var capture = org.mockito.ArgumentCaptor.forClass(Object.class); verify(publisher).publishEvent(capture.capture());
        var event = (ObservedMarketTick) capture.getValue();
        assertThat(event.tick()).isSameAs(input.observation().tick());
        assertThat(event.feature()).isSameAs(input.observation().feature());
        assertThat(event.receivedAt()).isNotNull();
    }
}
