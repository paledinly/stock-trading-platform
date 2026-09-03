package com.sunmo.stockplatform.market.api;

import com.sunmo.stockplatform.market.application.MarketEventGateway;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class MarketStreamController {
    private final MarketEventGateway gateway;

    public MarketStreamController(MarketEventGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = "Last-Event-ID", required = false) String lastId) {
        return gateway.connect(lastId);
    }
}
