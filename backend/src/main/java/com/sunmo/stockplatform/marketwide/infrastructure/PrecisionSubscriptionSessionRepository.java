package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.PrecisionSubscriptionSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.*;

public interface PrecisionSubscriptionSessionRepository extends JpaRepository<PrecisionSubscriptionSession, Long> {
    Optional<PrecisionSubscriptionSession> findFirstByStockCodeAndStatusOrderByRequestedAtDesc(
            String stockCode, PrecisionSubscriptionSession.Status status);
    List<PrecisionSubscriptionSession> findBySessionDateOrderByRequestedAtAsc(LocalDate date);
}
