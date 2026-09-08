package com.sunmo.stockplatform.marketwide.infrastructure;

import com.sunmo.stockplatform.marketwide.domain.MarketWideScanRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.time.LocalDate;
import java.util.List;

public interface MarketWideScanRunRepository extends JpaRepository<MarketWideScanRun, Long> {
    Optional<MarketWideScanRun> findByScheduledFor(Instant scheduledFor);
    List<MarketWideScanRun> findBySessionDateOrderByScheduledForAsc(LocalDate sessionDate);
}
