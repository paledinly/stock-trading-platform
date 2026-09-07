package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.MarketWideScanRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;

public interface MarketWideScanRunRepository extends JpaRepository<MarketWideScanRun, Long> {
    Optional<MarketWideScanRun> findByScheduledFor(Instant scheduledFor);
}
