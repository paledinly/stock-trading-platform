/*
 * CandleGapDetector가 하루 5분봉 중 빠진 구간(갭)을 찾는지 확인해요.
 * 아직 끝나지 않은 봉을 갭으로 잘못 잡으면 불필요한 보충 조회가 생기므로 중요해요.
 */
package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.CandleGapDetector;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 5분봉 누락 구간 탐지 규칙을 점검하는 테스트예요. */
class CandleGapDetectorTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 이미 마감된 구간 중 빠진 봉만 갭으로 찾는지 확인해요.
     * 준비: 기준 시각 09:17, 보유한 봉은 09:00과 09:10이에요.
     * 기대: 09:05 한 개만 갭이고, 진행 중인 09:15 봉은 포함되지 않아요.
     */
    @Test
    void findsOnlyClosedMissingBuckets() {
        // 준비
        LocalDate date = LocalDate.of(2026, 9, 1);
        Instant nine = date.atTime(9, 0).atZone(SEOUL).toInstant();
        var gaps = new CandleGapDetector().gaps(date, date.atTime(9, 17).atZone(SEOUL).toInstant(),
                List.of(nine, nine.plusSeconds(600)));
        // 확인
        assertThat(gaps).containsExactly(nine.plusSeconds(300));
    }
}
