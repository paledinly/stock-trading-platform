/*
 * SSE(서버가 브라우저로 이벤트를 계속 밀어 주는 한 방향 스트림)로 보내는 이벤트 한 건의 모양을 정의해요.
 * MarketEventGateway가 만들어 replay 버퍼와 클라이언트별 큐에 넣고 전송해요.
 */
package com.sunmo.stockplatform.market.application;

import java.time.Instant;
import java.util.Map;

/**
 * 브라우저로 보내는 실시간 이벤트 한 건이에요. 택배 송장처럼 번호(id), 종류(type), 만든 시각, 내용물(payload)이 적혀 있어요.
 * id는 1부터 늘어나는 순번이라 브라우저가 Last-Event-ID로 "여기까지 받았다"고 알려 줄 수 있어요.
 * occurredAt은 서버가 이벤트를 만든 시각이고, 원천 체결시각과는 달라요.
 */
public record MarketEvent(String id, String type, Instant occurredAt, int version, String correlationId,
        Map<String, Object> payload) {
}
