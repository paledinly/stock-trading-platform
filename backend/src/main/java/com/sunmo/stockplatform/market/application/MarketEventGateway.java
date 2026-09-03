package com.sunmo.stockplatform.market.application;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MarketEventGateway {
    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final Deque<MarketEvent> replay = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int replaySize;

    public MarketEventGateway(com.sunmo.stockplatform.market.config.RealtimeMarketProperties properties) {
        this.replaySize = Math.max(10, properties.replaySize());
    }

    public SseEmitter connect(String lastId) {
        SseEmitter emitter = new SseEmitter(0L);
        String client = UUID.randomUUID().toString();
        clients.put(client, emitter);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        if (lastId != null)
            replay.stream().dropWhile(e -> !e.id().equals(lastId)).skip(1).forEach(e -> send(client, emitter, e));
        return emitter;
    }

    public MarketEvent publish(String type, Map<String, Object> payload) {
        String id = Long.toString(sequence.incrementAndGet());
        MarketEvent event = new MarketEvent(id, type, Instant.now(), 1, UUID.randomUUID().toString(), payload);
        replay.addLast(event);
        while (replay.size() > replaySize)
            replay.pollFirst();
        clients.forEach((key, emitter) -> send(key, emitter, event));
        return event;
    }

    private void send(String key, SseEmitter emitter, MarketEvent event) {
        try {
            emitter.send(SseEmitter.event().id(event.id()).name(event.type()).data(event));
        } catch (IOException | IllegalStateException exception) {
            clients.remove(key);
            emitter.complete();
        }
    }
}
