/*
 * DetectionPerformance(스캐너 검출 후 가격 성과 기록)가 5분·10분·종가 수익률과 최대 이익·손실을 맞게 계산하는지 확인해요.
 * 데이터가 없을 때 0을 넣으면 성과가 왜곡되므로, 비어 있으면 비어 있다고 남기는 것이 중요해요.
 */
package com.sunmo.stockplatform.analytics;

import com.sunmo.stockplatform.analytics.domain.*;
import com.sunmo.stockplatform.scanner.domain.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 검출 성과 계산 규칙을 점검하는 테스트예요. */
class DetectionPerformanceTest {
    private final Instant detected = Instant.parse("2026-08-19T00:00:00Z");

    /**
     * 시점별 가격으로 구간 수익률과 MFE/MAE(검출 후 최대 유리·불리 변동)를 계산하는지 확인해요.
     * 준비: 검출가 100에 5분 110, 10분 90, 30분 120, 60분 80, 종가 105를 관찰시켜요.
     * 기대: 완료 상태, 5분 +10%, 10분 -10%, 종가 +5%, 최대 +20%, 최저 -20%예요.
     */
    @Test
    void fillsMilestonesAndCalculatesReturns() {
        DetectionPerformance p = new DetectionPerformance(detection());
        p.observe(bd("110"), detected.plusSeconds(300));
        p.observe(bd("90"), detected.plusSeconds(600));
        p.observe(bd("120"), detected.plusSeconds(1800));
        p.observe(bd("80"), detected.plusSeconds(3600));
        p.finalizeClose(bd("105"), detected.plusSeconds(23000));
        assertThat(p.getStatus()).isEqualTo(PerformanceStatus.COMPLETED);
        assertThat(p.getReturn5m()).isEqualByComparingTo("10.000000");
        assertThat(p.getReturn10m()).isEqualByComparingTo("-10.000000");
        assertThat(p.getReturnClose()).isEqualByComparingTo("5.000000");
        assertThat(p.getMaxReturn()).isEqualByComparingTo("20.000000");
        assertThat(p.getMaxDrawdown()).isEqualByComparingTo("-20.000000");
        assertThat(p.getMfe()).isEqualByComparingTo("20.000000");
        assertThat(p.getMae()).isEqualByComparingTo("-20.000000");
    }

    @Test
    /**
     * 빠진 시점 값은 0이 아니라 null로 두는지 확인해요.
     * 준비: 5분 가격만 관찰한 뒤 일찍 종가를 확정해요.
     * 기대: 상태는 DATA_MISSING, 10분·30분 수익률은 null이에요.
     */
    void marksMissingMilestonesInsteadOfWritingZero() {
        DetectionPerformance p = new DetectionPerformance(detection());
        p.observe(bd("102"), detected.plusSeconds(300));
        p.finalizeClose(bd("103"), detected.plusSeconds(1000));
        assertThat(p.getStatus()).isEqualTo(PerformanceStatus.DATA_MISSING);
        assertThat(p.getReturn10m()).isNull();
        assertThat(p.getReturn30m()).isNull();
    }

    @Test
    /**
     * 검출 전 가격은 무시하고, 봉 범위(고·저·종)로 놓친 구간을 복구하는지 확인해요.
     * 준비: 검출 1초 전 가격 50을 넣고, 5분 시점에 고가 115·저가 95·종가 110 범위를 넣어요.
     * 기대: 검출 전 값은 거부(false), 5분 +10%, MFE +15%, MAE -5%, 복구 사용 표시가 켜져요.
     */
    void ignoresPricesBeforeDetectionAndRecoversCandleRange() {
        DetectionPerformance p = new DetectionPerformance(detection());
        assertThat(p.observe(bd("50"), detected.minusSeconds(1))).isFalse();
        p.observeRange(bd("115"), bd("95"), bd("110"), detected.plusSeconds(300));
        assertThat(p.getReturn5m()).isEqualByComparingTo("10.000000");
        assertThat(p.getMfe()).isEqualByComparingTo("15.000000");
        assertThat(p.getMae()).isEqualByComparingTo("-5.000000");
        assertThat(p.isRecoveryUsed()).isTrue();
    }

    @Test
    /**
     * 종가 확정 사유를 기록하는지 확인해요.
     * 준비: 종가 없이 STARTUP_RECOVERY 사유로 확정해요.
     * 기대: DATA_MISSING 상태이고 사유가 STARTUP_RECOVERY예요.
     */
    void recordsFinalizationReason() {
        DetectionPerformance p = new DetectionPerformance(detection());
        p.finalizeClose(null, detected.plusSeconds(23000), "STARTUP_RECOVERY");
        assertThat(p.getStatus()).isEqualTo(PerformanceStatus.DATA_MISSING);
        assertThat(p.getFinalizationReason()).isEqualTo("STARTUP_RECOVERY");
    }

    /** 검출가 100인 테스트용 스캐너 검출 기록을 만드는 도우미예요. */
    private ScannerDetection detection() {
        ScannerSetting setting = new ScannerSetting(1L, "Momentum", ScannerType.MOMENTUM, bd("2"), bd("2"), bd("0"),
                bd("0"), bd("0"), false, 300);
        return new ScannerDetection(UUID.randomUUID(), null, setting, detected, bd("100"), bd("2"), bd("2"), 10,
                bd("1000"), bd("10000"), bd("4"), "{}", "test");
    }

    /** 문자열을 BigDecimal로 바꾸는 도우미예요. */
    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
