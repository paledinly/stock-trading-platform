/*
 * TrajectoryContextCollector 가 KIS 업종지수 응답(JSON)을 시장 맥락 값으로 바꾸는 부분을 확인해요.
 * 지수 값이 빠졌을 때 0 으로 채우면 "지수 변동 없음"처럼 잘못 해석되므로, 결측을 숫자로 대체하지 않는지가 중요해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** 지수 응답 파싱이 실제 필드만 읽고 결측을 숨기지 않는지 확인하는 테스트예요. */
class TrajectoryContextCollectorTest {
    /**
     * 무엇: 지수 현재값·등락률 필드를 그대로 읽고, 필드가 없으면 0 대신 예외를 내는지 확인해요.
     * 입력: KOSDAQ 지수 JSON(900.50, -1.25)과 빈 JSON {}.
     * 기대: 값과 등락률이 그대로 나오고, 빈 JSON 은 NumberFormatException 이 나요.
     */
    @Test void parsesActualIndexFieldsAndDoesNotReplaceMissingNumbersWithZero() throws Exception {
        var mapper = new ObjectMapper();
        var row = TrajectoryContextCollector.parseIndex("KOSDAQ", mapper.readTree("""
                {"bstp_nmix_prpr":"900.50","bstp_nmix_prdy_ctrt":"-1.25"}
                """), Instant.parse("2026-09-30T05:00:00Z"));
        assertThat(row.value()).isEqualByComparingTo("900.50");
        assertThat(row.returnPct()).isEqualByComparingTo("-1.25");
        assertThatThrownBy(() -> TrajectoryContextCollector.parseIndex("KOSPI", mapper.readTree("{}"), Instant.now()))
                .isInstanceOf(NumberFormatException.class);
    }
}
