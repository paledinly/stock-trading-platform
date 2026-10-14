/*
 * 브라우저가 실시간 시세·봉 이벤트를 받아 가는 SSE(서버가 브라우저로 이벤트를 계속 밀어 주는 한 방향 스트림) 입구예요.
 * 연결 요청을 MarketEventGateway에 넘기고, 끊긴 연결에서 나는 쓰기 오류는 조용히 기록만 해요.
 * 웹 화면(web)의 실시간 시세·차트가 /api/v1/stream으로 이 컨트롤러에 연결해요.
 */
package com.sunmo.stockplatform.market.api;

import com.sunmo.stockplatform.market.application.MarketEventGateway;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 브라우저의 실시간 이벤트 구독 요청을 받아 주는 REST(HTTP 주소로 기능을 제공하는 방식) 컨트롤러예요.
 * 라디오 방송국 안내 데스크처럼, 청취를 원하는 사람을 방송 채널(MarketEventGateway)에 연결해 주기만 해요.
 */
@RestController
@RequestMapping("/api/v1")
public class MarketStreamController {
    private final MarketEventGateway gateway;

    /**
     * 하는 일: 이벤트 게이트웨이를 주입받아요(의존성 주입: 필요한 객체를 스프링이 대신 만들어 넣어 주는 것).
     * 받는 것: SSE 연결을 관리하는 MarketEventGateway.
     */
    public MarketStreamController(MarketEventGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 하는 일: 브라우저 한 개의 SSE 연결을 열어요.
     * 받는 것: 재연결이면 마지막으로 받은 이벤트 번호(Last-Event-ID 헤더, 없으면 null).
     * 돌려주는 것: 이후 이벤트를 흘려보낼 SseEmitter. 재연결이면 놓친 이벤트부터 다시 보내요.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = "Last-Event-ID", required = false) String lastId) {
        return gateway.connect(lastId);
    }

    /**
     * Failed SSE writes are redispatched by the container after the client connection has gone away.
     * 하는 일: 브라우저가 이미 떠난 연결에 쓰다 실패한 오류를 debug 로그로만 남기고 삼켜요.
     * 받는 것: 쓰기 실패 IOException. 돌려주는 것: 없음(응답을 더 쓰지 않아요).
     */
    @ExceptionHandler(java.io.IOException.class)
    public void disconnected(java.io.IOException exception) {
        org.slf4j.LoggerFactory.getLogger(getClass()).debug("SSE response write failed; connection closed", exception);
    }
}
