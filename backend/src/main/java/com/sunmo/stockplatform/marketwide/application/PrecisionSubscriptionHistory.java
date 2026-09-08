package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import com.sunmo.stockplatform.marketwide.infrastructure.PrecisionSubscriptionSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class PrecisionSubscriptionHistory {
    private final PrecisionSubscriptionSessionRepository sessions;
    public PrecisionSubscriptionHistory(PrecisionSubscriptionSessionRepository sessions) { this.sessions = sessions; }

    @Transactional
    public void requested(String code, Instant at, boolean alreadySubscribed) {
        sessions.save(new PrecisionSubscriptionSession(code, at, alreadySubscribed));
    }
    @Transactional
    public void activated(String code, Instant at) {
        sessions.findFirstByStockCodeAndStatusOrderByRequestedAtDesc(code, PrecisionSubscriptionSession.Status.REQUESTED)
                .ifPresent(value -> value.activate(at));
    }
    @Transactional
    public void rejected(String code, Instant at, String reason) {
        sessions.findFirstByStockCodeAndStatusOrderByRequestedAtDesc(code, PrecisionSubscriptionSession.Status.REQUESTED)
                .ifPresent(value -> value.reject(at, reason));
    }
    @Transactional
    public void ended(String code, Instant at, String reason) {
        sessions.findFirstByStockCodeAndStatusOrderByRequestedAtDesc(code, PrecisionSubscriptionSession.Status.ACTIVE)
                .ifPresent(value -> value.end(at, reason));
    }
}
