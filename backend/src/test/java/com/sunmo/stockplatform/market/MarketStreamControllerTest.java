package com.sunmo.stockplatform.market;

import com.sunmo.stockplatform.market.api.MarketStreamController;
import com.sunmo.stockplatform.market.application.MarketEventGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MarketStreamControllerTest {
    @Test void containerRedispatchedWriteFailureIsResolvedWithoutWritingAnErrorBody() throws Exception {
        var gateway = mock(MarketEventGateway.class);
        var emitter = new SseEmitter(0L);
        when(gateway.connect(null)).thenReturn(emitter);
        var mvc = MockMvcBuilders.standaloneSetup(new MarketStreamController(gateway)).build();
        var request = mvc.perform(get("/api/v1/stream")).andExpect(request().asyncStarted()).andReturn();
        // Simulate the servlet container error notification, not application cleanup after send().
        var failure = new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어의 의해 중단되었습니다");
        emitter.completeWithError(failure);
        var resolved = mvc.perform(asyncDispatch(request)).andExpect(content().string("")).andReturn();
        assertThat(resolved.getResolvedException()).isSameAs(failure);
    }
}
