/*
 * KIS 실시간 체결 구독 목록을 관리하는 장부예요. 종목마다 "누가 왜 구독을 원하는지(출처)"를 함께 적어 둬요.
 * 출처가 하나라도 남아 있으면 구독을 유지하고, 마지막 출처가 빠지면 구독 해제를 알려요. 전체 개수는 KIS 한도(기본 41)를 넘지 않아요.
 * 관심종목·수동 구독 API·정밀 후보 배정기·전일 추천(OVERNIGHT)이 add/remove를 부르고, KIS WebSocket 클라이언트가 변경 알림을 받아 실제 구독 요청을 보내요.
 */
package com.sunmo.stockplatform.market.application;

import org.springframework.stereotype.Component;
import com.sunmo.stockplatform.market.config.RealtimeMarketProperties;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * 실시간 구독 자리 예약 장부예요.
 * 공유 회의실 예약표처럼 한 방(종목)에 여러 팀(출처)이 이름을 올릴 수 있고, 마지막 팀이 빠져야 방이 비어요.
 * 방 개수(구독 한도)가 다 차면 새 종목은 예약할 수 없어요.
 */
@Component
public class RealtimeSubscriptionRegistry {
    /**
     * 구독을 원하는 이유(출처)예요.
     * WATCHLIST는 실시간 고정한 관심종목, MANUAL은 수동 구독, PRECISION은 정밀 후보, OVERNIGHT는 전일 추천 다음 날 추적이에요.
     * QUOTE는 출처 없이 add(code)를 부를 때의 기본값이고, CORE는 현재 운영 코드에서 쓰는 곳이 없는 것으로 보여요.
     */
    public enum Source {
        QUOTE, WATCHLIST, MANUAL, CORE, PRECISION, OVERNIGHT
    }

    /** KIS가 구독/해제 요청에 응답한 결과예요. subscribing은 구독 요청이었는지(false면 해제), success는 성공 여부예요. */
    public record Acknowledgement(String stockCode, boolean subscribing, boolean success, String message) {
    }

    private final int limit;
    private final Map<String, Set<Source>> entries = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Boolean> listener = (code, subscribe) -> {
    };
    private final List<java.util.function.Consumer<Acknowledgement>> acknowledgementListeners = new CopyOnWriteArrayList<>();

    /**
     * 하는 일: 구독 한도를 설정값에서 읽어 둬요.
     * 받는 것: 실시간 설정(subscriptionLimit).
     */
    public RealtimeSubscriptionRegistry(RealtimeMarketProperties properties) {
        this.limit = properties.subscriptionLimit();
    }

    /**
     * 하는 일: QUOTE 출처로 구독을 추가해요.
     * 받는 것: 종목코드. 돌려주는 것: 출처 목록이 바뀌었으면 true.
     */
    public boolean add(String code) {
        return add(code, Source.QUOTE);
    }

    /**
     * 하는 일: 종목에 출처를 추가해요. 처음 구독하는 종목이면 변경 리스너에 "구독 시작"을 알려요.
     * 받는 것: 종목코드, 출처.
     * 돌려주는 것: 실제로 추가됐으면 true, 이미 같은 출처가 있었으면 false.
     * 한도가 꽉 찬 상태에서 새 종목을 넣으려 하면 IllegalStateException을 던져요.
     */
    public synchronized boolean add(String code, Source source) {
        Set<Source> existing = entries.get(code);
        if (existing != null)
            return existing.add(source);
        if (entries.size() >= limit)
            throw new IllegalStateException("KIS realtime subscription limit reached: " + limit);
        entries.put(code, ConcurrentHashMap.newKeySet());
        entries.get(code).add(source);
        listener.accept(code, true);
        return true;
    }

    /**
     * 하는 일: 종목에서 출처 하나를 빼요. 남은 출처가 없으면 종목을 지우고 변경 리스너에 "구독 해제"를 알려요.
     * 받는 것: 종목코드, 출처. 돌려주는 것: 실제로 빠졌으면 true.
     */
    public synchronized boolean remove(String code, Source source) {
        Set<Source> sources = entries.get(code);
        if (sources == null || !sources.remove(source))
            return false;
        if (sources.isEmpty()) {
            entries.remove(code);
            listener.accept(code, false);
        }
        return true;
    }

    /** 하는 일: 지금 구독 중인 종목코드 전체를 복사해서 돌려줘요. */
    public Set<String> all() {
        return Set.copyOf(entries.keySet());
    }

    /** 하는 일: 종목코드별 출처 목록을 바꿀 수 없는 복사본으로 돌려줘요. */
    public Map<String, Set<Source>> entries() {
        Map<String, Set<Source>> copy = new TreeMap<>();
        entries.forEach((code, sources) -> copy.put(code, Set.copyOf(sources)));
        return Map.copyOf(copy);
    }

    /** 구독 한도와 남은 자리 수예요. */
    public int limit() {
        return limit;
    }

    public int remaining() {
        return Math.max(0, limit - entries.size());
    }

    /**
     * 하는 일: 종목 구독 시작/해제 때 불릴 리스너를 등록해요. 하나만 둘 수 있어서 새로 등록하면 이전 것을 대체해요.
     * 받는 것: (종목코드, 구독 시작이면 true) 를 받는 함수.
     */
    public void onChanged(BiConsumer<String, Boolean> listener) {
        this.listener = listener;
    }

    /**
     * 하는 일: KIS 구독 응답을 받을 리스너를 추가해요(여러 개 가능).
     * 받는 것: Acknowledgement를 받는 함수.
     */
    public void onAcknowledged(java.util.function.Consumer<Acknowledgement> listener) {
        acknowledgementListeners.add(listener);
    }

    /**
     * 하는 일: KIS 구독 응답을 등록된 모든 리스너에 전달해요.
     * 받는 것: 종목코드, 구독 요청이었는지, 성공 여부, KIS 메시지.
     */
    public void acknowledge(String stockCode, boolean subscribing, boolean success, String message) {
        Acknowledgement acknowledgement = new Acknowledgement(stockCode, subscribing, success, message);
        acknowledgementListeners.forEach(listener -> listener.accept(acknowledgement));
    }
}
