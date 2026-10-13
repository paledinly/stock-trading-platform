package com.sunmo.stockplatform.market.application;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishers only enqueue: a socket write to a slow browser must never stall the tick worker.
 * Each client drains its own bounded queue on a virtual thread; overflow disconnects that client,
 * which reconnects with Last-Event-ID and resumes from the replay buffer.
 */
@Component
public class MarketEventGateway {
    public static final int CLIENT_QUEUE_CAPACITY = 4096;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketEventGateway.class);
    private final ConcurrentMap<String, Client> clients = new ConcurrentHashMap<>();
    private final Deque<MarketEvent> replay = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong slowClientDisconnects = new AtomicLong();
    private final int replaySize;

    public MarketEventGateway(com.sunmo.stockplatform.market.config.RealtimeMarketProperties properties) {
        this.replaySize = Math.max(10, properties.replaySize());
    }

    public SseEmitter connect(String lastId) {
        return register(new SseEmitter(0L), lastId);
    }

    public SseEmitter register(SseEmitter emitter, String lastId) {
        String key = UUID.randomUUID().toString();
        Client client = new Client(key, emitter);
        emitter.onCompletion(() -> drop(client, false));
        emitter.onTimeout(() -> drop(client, false));
        emitter.onError(error -> drop(client, false));
        synchronized (replay) {
            // Replay and registration share the publish lock, so no event is skipped or duplicated in between.
            if (lastId != null)
                replay.stream().dropWhile(e -> !e.id().equals(lastId)).skip(1).forEach(client.queue::offer);
            clients.put(key, client);
        }
        Thread.ofVirtual().name("sse-client-" + key.substring(0, 8)).start(client::drain);
        return emitter;
    }

    public MarketEvent publish(String type, Map<String, Object> payload) {
        synchronized (replay) {
            String id = Long.toString(sequence.incrementAndGet());
            MarketEvent event = new MarketEvent(id, type, Instant.now(), 1, UUID.randomUUID().toString(), payload);
            replay.addLast(event);
            while (replay.size() > replaySize)
                replay.pollFirst();
            for (Client client : clients.values())
                if (!client.queue.offer(event)) drop(client, true);
            return event;
        }
    }

    public long slowClientDisconnects() { return slowClientDisconnects.get(); }

    public int clientCount() { return clients.size(); }

    private void drop(Client client, boolean slow) {
        if (!clients.remove(client.key, client)) return;
        client.closed = true;
        if (slow) {
            client.complete = true;
            slowClientDisconnects.incrementAndGet();
            log.warn("SSE client too slow; disconnected after {} queued events", CLIENT_QUEUE_CAPACITY);
        }
    }

    private final class Client {
        final String key;
        final SseEmitter emitter;
        final BlockingQueue<MarketEvent> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE_CAPACITY);
        volatile boolean closed;
        volatile boolean complete;

        Client(String key, SseEmitter emitter) { this.key = key; this.emitter = emitter; }

        void drain() {
            try {
                while (!closed) {
                    MarketEvent event = queue.poll(1, TimeUnit.SECONDS);
                    if (event == null || closed) continue;
                    emitter.send(SseEmitter.event().id(event.id()).name(event.type()).data(event));
                }
            } catch (IOException | IllegalStateException failed) {
                // The servlet container owns completion after a failed write; do not complete it again.
                drop(this, false);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // Only an overflow is ours to end; the browser then reconnects with Last-Event-ID.
            if (complete) {
                try { emitter.complete(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
