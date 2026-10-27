/*
 * 마감 평가 시간대의 실시간 연결 상태를 입력 마감 직전과 진입 마감 직후에 한 번씩 찍어 두고,
 * 장 마감 15분 뒤 그날의 완결성(그날 기록이 빠짐없이 갖춰졌는지) 점검 행을 저장하도록 ClosingDailyHealthService에 넘겨요.
 * closing.automation.enabled=true일 때만 켜지고, 스프링 스케줄러가 주기적으로 불러요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Samples realtime health just before the feature freeze and after the entry deadline, then records the
 * daily completeness row at close + 15m on trading days. Read-only with respect to the strategy.
 * 하는 일: 입력 마감(기본 15:15) 직전 1분 안의 마지막 상태와 진입 마감(기본 15:20) 이후 첫 상태를 비교해
 * 그 사이 실시간 연결이 끊기거나 데이터가 버려졌는지 알 수 있게 해요. 전략 결과에는 손대지 않아요.
 * 경기 시작 직전과 종료 직후에 경기장 전광판을 사진 찍어 두었다가, 경기 후에 "중간에 정전 있었나?"를 확인하는 기록원 같아요.
 */
@Component
@ConditionalOnProperty(prefix = "closing.automation", name = "enabled", havingValue = "true")
public class ClosingDailyHealthRecorder {
    // 로그 기록기예요.
    private static final Logger log = LoggerFactory.getLogger(ClosingDailyHealthRecorder.class);
    /**
     * The pre-freeze sample is the last poll inside this lead before the freeze time.
     * 입력 마감 전 몇 분 안을 "직전"으로 볼지 정하는 간격이에요(1분).
     */
    static final Duration SAMPLE_LEAD = Duration.ofMinutes(1);
    /**
     * The final record waits for the 18:00 late minute backfill and observation re-evaluation
     * (ClosingRecommendationScheduler.LATE_OBSERVATION_AT) to finish.
     * 18:00 분봉 재보완·관측 재평가가 끝난 뒤 최종 기록을 남기는 시각이에요.
     */
    static final LocalTime FINAL_CHECK_AT = ClosingRecommendationScheduler.LATE_OBSERVATION_AT.plusMinutes(15);

    /** 한 시점의 실시간 상태 사진이에요. 시각, 연결 여부, 누적 버려진 메시지 수, 마지막 끊김 시각을 담아요. */
    private record Sample(Instant at, boolean connected, long dropped, Instant disconnectedAt) { }

    private final ClosingDailyHealthService health;
    private final RealtimeDiagnostics diagnostics;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties policy;
    // 오늘 날짜의 두 샘플과 마지막 기록 날짜는 메모리에만 있어요. 재시작하면 사라져 그날은 UNOBSERVED가 될 수 있어요.
    private LocalDate sampleDate;
    private Sample beforeFreeze;
    private Sample afterDeadline;
    private LocalDate recordedDate;
    private ClosingDailyHealthService.Stage recordedStage;

    /**
     * 일일 점검 서비스, 실시간 진단, 거래일 달력, 마감 정책(입력 마감·진입 마감 시각)을 받아요.
     */
    public ClosingDailyHealthRecorder(ClosingDailyHealthService health, RealtimeDiagnostics diagnostics,
            ClosingTradingCalendar calendar, ClosingRecommendationProperties policy) {
        this.health = health;
        this.diagnostics = diagnostics;
        this.calendar = calendar;
        this.policy = policy;
    }

    /**
     * 기본 30초마다 불려 샘플을 찍고, 장 마감 15분 뒤에는 하루 한 번 점검 행을 기록해요.
     * 받는 것·돌려주는 것: 없음. 기록 실패는 로그만 남기고 다음 호출에서 다시 시도해요.
     */
    @Scheduled(fixedDelayString = "${closing.automation.poll-interval:30s}", scheduler = "closingScheduler")
    public synchronized void poll() {
        Instant now = calendar.now();
        LocalDate today = now.atZone(ClosingTradingCalendar.ZONE).toLocalDate();
        LocalTime time = MarketSessionHours.regularTime(now);
        if (!calendar.isTradingDay(today))
            return;
        // 준비: 날짜가 바뀌면 전날 샘플을 버려요.
        if (!today.equals(sampleDate)) {
            sampleDate = today;
            beforeFreeze = null;
            afterDeadline = null;
        }
        // 처리: 입력 마감 1분 전~마감 사이에는 "직전" 샘플을 계속 갱신하고, 진입 마감 뒤에는 "직후" 샘플을 한 번 찍어요.
        LocalTime freeze = policy.featureFreezeAt();
        if (!time.isBefore(freeze.minus(SAMPLE_LEAD)) && time.isBefore(freeze))
            beforeFreeze = sample(now);
        else if (beforeFreeze != null && afterDeadline == null && !time.isBefore(policy.entryDeadline()))
            afterDeadline = sample(now);

        // 결과: 장 마감+15분 뒤 1차(PRELIMINARY), 18:15 뒤 최종(FINAL)을 하루 한 번씩 기록해요.
        // 18:15 뒤에 처음 기록하게 되면(그 사이 재시작 등) 최종만 남겨요.
        if (now.isBefore(calendar.close(today).plus(ClosingDailyHealthService.CHECK_DELAY)))
            return;
        ClosingDailyHealthService.Stage stage = time.isBefore(FINAL_CHECK_AT)
                ? ClosingDailyHealthService.Stage.PRELIMINARY : ClosingDailyHealthService.Stage.FINAL;
        if (today.equals(recordedDate) && (recordedStage == stage || recordedStage == ClosingDailyHealthService.Stage.FINAL))
            return;
        try {
            ClosingDailyHealthService.DailyHealth row = health.recordToday(window(), stage);
            recordedDate = today;
            recordedStage = stage;
            log.info("Closing daily health {} {}: {} {}", today, stage, row.verdict(), row.reasons());
        } catch (RuntimeException error) {
            log.warn("Closing daily health record failed for {}: {}", today, error.getMessage());
        }
    }

    /**
     * Both samples are needed; a restart or a gap in between leaves the window unobserved.
     * 하는 일: 두 샘플로 평가 시간대의 실시간 상태 요약을 만들어요.
     * 받는 것: 없음(메모리의 샘플을 써요). 돌려주는 것: 계속 연결됐는지, 그 사이 버려진 메시지 수, 두 샘플 시각. 샘플이 없으면 UNOBSERVED.
     */
    ClosingDailyHealthService.RealtimeWindow window() {
        if (beforeFreeze == null || afterDeadline == null)
            return ClosingDailyHealthService.RealtimeWindow.UNOBSERVED;
        // 두 샘플 모두 연결 상태이고, 마지막 끊김이 직전 샘플 이후에 일어나지 않았어야 "계속 연결"로 봐요.
        boolean stayedConnected = beforeFreeze.connected() && afterDeadline.connected()
                && (afterDeadline.disconnectedAt() == null || !afterDeadline.disconnectedAt().isAfter(beforeFreeze.at()));
        return new ClosingDailyHealthService.RealtimeWindow(stayedConnected,
                Math.max(0, afterDeadline.dropped() - beforeFreeze.dropped()), beforeFreeze.at(), afterDeadline.at());
    }

    /**
     * 실시간 진단에서 지금 상태를 읽어 샘플 1개를 만들어요.
     * 받는 것: 현재 시각. 돌려주는 것: Sample.
     */
    private Sample sample(Instant now) {
        RealtimeDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        return new Sample(now, snapshot.connected(), snapshot.processingDropped(), snapshot.disconnectedAt());
    }
}
