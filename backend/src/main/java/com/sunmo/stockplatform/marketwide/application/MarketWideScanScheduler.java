/*
 * 일정한 간격으로 깨어나 시장 전체 스캔을 돌릴 차례인지 확인하는 파일이에요.
 * 돌려도 되는 시간이면 스캔 조정자(MarketWideScanCoordinator)에게 회차 스캔을 맡기고, 아니면 건너뛴 이유만 남겨요.
 * market.wide.schedule.enabled=true일 때만 스프링이 자동으로 실행해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 시장 전체 스캔의 스케줄러(정해진 간격마다 자동으로 일을 시키는 장치)예요.
 * 알람 시계처럼 주기적으로 울려서 "지금 스캔할 시간이야?"를 확인하고, 맞으면 스캔을 시작시켜요.
 */
@Component
@ConditionalOnProperty(prefix = "market.wide.schedule", name = "enabled", havingValue = "true")
public class MarketWideScanScheduler {
    private final MarketSessionPolicy session;
    private final MarketWideScanCoordinator scans;
    private final MarketWideScheduleProperties properties;
    private final MarketWideDiagnostics diagnostics;

    /**
     * 하는 일: 스케줄러에 필요한 협력 객체들을 받아 둬요.
     * 받는 것: 장 시간 판단 정책, 스캔 조정자, 일정 설정, 진단 기록기.
     */
    public MarketWideScanScheduler(MarketSessionPolicy session, MarketWideScanCoordinator scans,
            MarketWideScheduleProperties properties, MarketWideDiagnostics diagnostics) {
        this.session = session;
        this.scans = scans;
        this.properties = properties;
        this.diagnostics = diagnostics;
    }

    /**
     * 하는 일: 기본 30초마다 불려서, 스캔 가능한 시간이면 이번 회차 스캔을 요청해요.
     * 받는 것: 없음(현재 시각을 직접 사용해요).
     * 돌려주는 것: 없음. 불가하면 건너뛴 사유를 진단 기록에 남겨요.
     */
    @Scheduled(fixedDelayString = "${market.wide.schedule.poll-interval:30s}")
    public void poll() {
        MarketSessionPolicy.Decision decision = session.evaluate(Instant.now());
        if (!decision.eligible()) {
            diagnostics.skipped(decision.reason(), null);
            return;
        }
        // 같은 회차(bucket)는 조정자 쪽에서 한 번만 돌도록 걸러 주는 것으로 보여요.
        scans.scheduled(decision.bucket(), properties.scanLimit(), properties.candidateLimit(),
                properties.includeEtf());
    }
}
