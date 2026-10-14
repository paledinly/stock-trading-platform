/*
 * MarketStreamController의 SSE(서버가 브라우저로 계속 보내는 이벤트 스트림) 연결 오류 처리를 확인해요.
 * 클라이언트가 끊겼을 때 오류 본문을 쓰려다 추가 예외가 나지 않아야 로그와 연결이 깔끔해요.
 */
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

/** SSE 스트림 컨트롤러의 끊김 처리를 점검하는 테스트예요. */
class MarketStreamControllerTest {
    /**
     * 서블릿 컨테이너가 쓰기 실패를 알리며 재디스패치할 때, 오류 본문 없이 처리되는지 확인해요.
     * 준비: 목(mock, 가짜 객체) 게이트웨이와 MockMvc(가짜 HTTP 요청 도구)로 스트림을 연 뒤 IOException으로 끝내요.
     * 기대: 응답 본문은 비어 있고, 해결된 예외가 같은 IOException이에요.
     */
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
