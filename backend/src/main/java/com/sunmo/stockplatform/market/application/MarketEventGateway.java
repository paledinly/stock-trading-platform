/*
 * 서버에서 생긴 실시간 이벤트(시세, 봉, 탐지 등)를 접속한 모든 브라우저에 SSE로 나눠 보내는 방송국이에요.
 * 발행자는 이벤트를 클라이언트별 큐에 넣기만 하고, 실제 전송은 클라이언트마다 가상 스레드가 따로 해요.
 * MarketDataService를 비롯한 여러 서비스가 publish를 부르고, MarketStreamController가 connect로 브라우저를 연결해요.
 */
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
 *
 * SSE(서버가 브라우저로 이벤트를 계속 밀어 주는 한 방향 스트림) 방송을 맡는 클래스예요.
 * 신문 배달소처럼 새 신문(이벤트)이 나오면 구독자마다 정해진 크기의 우편함(큐)에 넣어 두고, 배달원(가상 스레드)이 각자 가져다줘요.
 * 우편함이 넘치는 구독자는 연결을 끊고, 브라우저가 Last-Event-ID로 다시 붙으면 최근 보관분(replay 버퍼)에서 이어 보내요.
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

    /**
     * 하는 일: 재전송용 보관 개수(replay 버퍼 크기)를 정해요. 너무 작지 않게 최소 10개는 보관해요.
     * 받는 것: 실시간 설정값(replaySize).
     */
    public MarketEventGateway(com.sunmo.stockplatform.market.config.RealtimeMarketProperties properties) {
        this.replaySize = Math.max(10, properties.replaySize());
    }

    /**
     * 하는 일: 시간 제한 없는 새 SSE 연결을 만들어 등록해요.
     * 받는 것: 브라우저가 마지막으로 받은 이벤트 id(없으면 null).
     * 돌려주는 것: 등록된 SseEmitter.
     */
    public SseEmitter connect(String lastId) {
        return register(new SseEmitter(0L), lastId);
    }

    /**
     * 하는 일: 주어진 SseEmitter를 구독자로 등록하고, 놓친 이벤트를 큐에 채운 뒤 전송용 가상 스레드를 시작해요.
     * 받는 것: SseEmitter, 마지막으로 받은 이벤트 id(없으면 null).
     * 돌려주는 것: 같은 SseEmitter.
     */
    public SseEmitter register(SseEmitter emitter, String lastId) {
        // 준비: 구독자를 만들고, 연결이 끝나거나 오류가 나면 목록에서 빠지게 해요.
        String key = UUID.randomUUID().toString();
        Client client = new Client(key, emitter);
        emitter.onCompletion(() -> drop(client, false));
        emitter.onTimeout(() -> drop(client, false));
        emitter.onError(error -> drop(client, false));
        synchronized (replay) {
            // Replay and registration share the publish lock, so no event is skipped or duplicated in between.
            // 재전송과 등록을 발행과 같은 잠금 안에서 해서, 그 사이 이벤트가 빠지거나 두 번 가지 않게 해요.
            // lastId가 보관 범위 밖이면 dropWhile이 전부 건너뛰어 재전송 없이 새 이벤트부터 받아요.
            if (lastId != null)
                replay.stream().dropWhile(e -> !e.id().equals(lastId)).skip(1).forEach(client.queue::offer);
            clients.put(key, client);
        }
        Thread.ofVirtual().name("sse-client-" + key.substring(0, 8)).start(client::drain);
        return emitter;
    }

    /**
     * 하는 일: 새 이벤트에 순번을 붙여 replay 버퍼에 보관하고, 모든 구독자 큐에 넣어요.
     * 큐가 가득 찬 느린 구독자는 기다리지 않고 바로 끊어요(틱 처리가 막히지 않게).
     * 받는 것: 이벤트 종류(예: quote.updated), 내용 Map.
     * 돌려주는 것: 만들어진 MarketEvent.
     */
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

    /** 진단용 숫자예요. slowClientDisconnects는 큐 넘침으로 끊은 누적 횟수, clientCount는 지금 접속한 브라우저 수예요. */
    public long slowClientDisconnects() { return slowClientDisconnects.get(); }

    public int clientCount() { return clients.size(); }

    /**
     * 하는 일: 구독자를 목록에서 빼고 전송 스레드가 멈추게 표시해요. 이미 빠졌으면 아무것도 안 해요.
     * 받는 것: 구독자, 느려서 끊는 것인지(slow). slow면 연결 종료(complete)를 우리가 직접 하도록 표시하고 카운터를 올려요.
     */
    private void drop(Client client, boolean slow) {
        if (!clients.remove(client.key, client)) return;
        client.closed = true;
        if (slow) {
            client.complete = true;
            slowClientDisconnects.incrementAndGet();
            log.warn("SSE client too slow; disconnected after {} queued events", CLIENT_QUEUE_CAPACITY);
        }
    }

    /**
     * 브라우저 연결 하나와 그 전용 우편함(최대 4,096건 큐)이에요.
     * closed는 "더 보내지 말 것", complete는 "느려서 끊었으니 우리가 연결을 닫을 것"이라는 표시예요.
     */
    private final class Client {
        final String key;
        final SseEmitter emitter;
        final BlockingQueue<MarketEvent> queue = new ArrayBlockingQueue<>(CLIENT_QUEUE_CAPACITY);
        volatile boolean closed;
        volatile boolean complete;

        /** 하는 일: 구독자 식별 키와 SseEmitter를 받아 구독자를 만들어요. */
        Client(String key, SseEmitter emitter) { this.key = key; this.emitter = emitter; }

        /**
         * 하는 일: 가상 스레드에서 돌며 큐의 이벤트를 하나씩 브라우저로 보내요.
         * closed가 될 때까지 1초 간격으로 큐를 확인해요. 받는 것·돌려주는 것: 없음.
         */
        void drain() {
            try {
                while (!closed) {
                    MarketEvent event = queue.poll(1, TimeUnit.SECONDS);
                    if (event == null || closed) continue;
                    emitter.send(SseEmitter.event().id(event.id()).name(event.type()).data(event));
                }
            } catch (IOException | IllegalStateException failed) {
                // The servlet container owns completion after a failed write; do not complete it again.
                // 쓰기가 실패하면 연결 마무리는 서블릿 컨테이너 몫이라, 여기서는 목록에서 빼기만 해요.
                drop(this, false);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // Only an overflow is ours to end; the browser then reconnects with Last-Event-ID.
            // 큐 넘침으로 끊은 경우에만 우리가 연결을 닫아요. 그러면 브라우저가 Last-Event-ID로 다시 붙어요.
            if (complete) {
                try { emitter.complete(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
