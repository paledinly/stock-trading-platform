package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "market.wide.schedule", name = "enabled", havingValue = "true")
public class MarketWideScanScheduler {
    private final MarketSessionPolicy session;
    private final MarketWideScanCoordinator scans;
    private final MarketWideScheduleProperties properties;
    private final MarketWideDiagnostics diagnostics;

    public MarketWideScanScheduler(MarketSessionPolicy session, MarketWideScanCoordinator scans,
            MarketWideScheduleProperties properties, MarketWideDiagnostics diagnostics) {
        this.session = session;
        this.scans = scans;
        this.properties = properties;
        this.diagnostics = diagnostics;
    }

    @Scheduled(fixedDelayString = "${market.wide.schedule.poll-interval:30s}")
    public void poll() {
        MarketSessionPolicy.Decision decision = session.evaluate(Instant.now());
        if (!decision.eligible()) {
            diagnostics.skipped(decision.reason(), null);
            return;
        }
        scans.scheduled(decision.bucket(), properties.scanLimit(), properties.candidateLimit(),
                properties.includeEtf());
    }
}
