/*
 * 실시간 수집 상태를 한 번에 보여 주는 진단 API(프로그램끼리 정해진 방식으로 요청·응답하는 창구)예요.
 * 틱 처리 통계, Redis 연결 여부, 구독 수와 한도, SSE 접속 수를 묶어 돌려줘요.
 * 운영자 화면과 운영 문서의 GET /api/v1/market/realtime/status 점검 절차가 이 응답을 사용해요.
 */
package com.sunmo.stockplatform.market.api;

import com.sunmo.stockplatform.market.application.MarketEventGateway;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.market.application.RealtimeSubscriptionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실시간 파이프라인의 건강 상태를 알려 주는 REST(HTTP 주소로 기능을 제공하는 방식) 컨트롤러예요.
 * 자동차 계기판처럼 여러 센서(진단 카운터, Redis, 구독, SSE) 값을 한 화면에 모아 보여 줘요.
 */
@RestController
@RequestMapping("/api/v1/market/realtime")
public class RealtimeStatusController {
    private final RealtimeDiagnostics diagnostics;
    private final RealtimeSubscriptionRegistry subscriptions;
    private final StringRedisTemplate redis;
    private final MarketEventGateway stream;

    /**
     * 하는 일: 진단에 필요한 부품들을 주입받아요(의존성 주입: 스프링이 필요한 객체를 넣어 주는 것).
     * 받는 것: 진단 카운터, 구독 목록, Redis(메모리 기반 키-값 저장소) 템플릿 제공자, SSE 게이트웨이.
     * Redis가 설정되지 않았으면 redis 필드는 null로 남아요.
     */
    public RealtimeStatusController(RealtimeDiagnostics diagnostics, RealtimeSubscriptionRegistry subscriptions,
            ObjectProvider<StringRedisTemplate> redis, MarketEventGateway stream) {
        this.diagnostics = diagnostics;
        this.subscriptions = subscriptions;
        this.stream = stream;
        this.redis = redis.getIfAvailable();
    }

    /**
     * 하는 일: 현재 실시간 수집 상태를 모아 돌려줘요.
     * 받는 것: 없음.
     * 돌려주는 것: 진단 스냅샷, Redis 상태, 구독 수·한도·남은 자리, SSE 접속 수, 느린 클라이언트 강제 종료 수.
     */
    @GetMapping("/status")
    public Status status() {
        return new Status(diagnostics.snapshot(), redisStatus(), subscriptions.all().size(),
                subscriptions.limit(), subscriptions.remaining(), stream.clientCount(), stream.slowClientDisconnects());
    }

    /**
     * 하는 일: Redis에 PING을 보내 살아 있는지 확인해요.
     * 돌려주는 것: "UP"(응답 정상), "DOWN"(응답 이상·오류), "UNAVAILABLE"(Redis 미설정).
     */
    private String redisStatus() {
        if (redis == null)
            return "UNAVAILABLE";
        try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN";
        } catch (RuntimeException error) {
            return "DOWN";
        }
    }

    /**
     * /status 응답 모양이에요. 계기판 한 장에 적힌 숫자 묶음이라고 보면 돼요.
     * sseSlowClientDisconnects는 큐가 넘쳐 서버가 끊은 브라우저 연결 수예요.
     */
    public record Status(RealtimeDiagnostics.Snapshot realtime, String redisStatus,
            int subscriptionCount, int subscriptionLimit, int subscriptionRemaining,
            int sseClients, long sseSlowClientDisconnects) {
    }
}
