/*
 * MorningOutcomeService 가 다음 거래일 아침 분봉으로 시가 수익률·목표/손절 도달 여부를 계산하는 부분을 확인해요.
 * 분봉이 빠진 구간을 추정으로 채우거나, 같은 봉 안에서 목표와 손절 중 무엇이 먼저였는지 단정하면 성과가 왜곡되므로 중요해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 다음날 아침 성과 계산이 모르는 것은 모른다고(null) 남기는지 확인하는 테스트예요. */
class MorningOutcomeServiceTest {
    /** 다음 거래일 장 시작 시각이에요(UTC 00:00 = 한국시간 09:00). */
    private static final Instant OPEN = Instant.parse("2026-09-30T00:00:00Z");
    /** 장 시작 후 minute 분에 시작하는 확정 봉을 만드는 도우미예요. */
    private StockCandle candle(int minute, int open, int high, int low, int close) {
        return new StockCandle(null, OPEN.plusSeconds(minute * 60L), BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 100, BigDecimal.valueOf(10000), true, 1);
    }
    /**
     * 무엇: 기준가(종가 100) 대비 지표와 진입가(101) 대비 지표가 구분되고, 첫 봉이 없으면 결과를 비워 두는지 확인해요.
     * 입력: 09:00, 09:05 두 봉(시가 102, 최고 105)과, 09:00 봉이 빠진 목록. 목표 +3%, 손절 -2%.
     * 기대: 다음날 시가 수익률 2%, 진입 기준 수익률은 2% 가 아니고 5% 도달·손절 선행 없음. 첫 봉이 빠지면 관련 값은 null 이에요.
     */
    @Test void closeBasedTargetsDifferFromEntryReturnsAndMissingMinutesRemainUnknown() {
        var rows = List.of(candle(0, 102, 104, 99, 103), candle(5, 103, 105, 100, 104));
        var result = MorningOutcomeService.calculate(rows, OPEN, OPEN.plusSeconds(600), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(3), BigDecimal.valueOf(-2), Instant.now().plus(Duration.ofDays(1)));
        assertThat((BigDecimal) result.get("nextOpenReturn")).isEqualByComparingTo("2");
        assertThat((BigDecimal) result.get("entryToNextOpenReturn")).isNotEqualByComparingTo("2");
        assertThat(result.get("hit5Pct")).isEqualTo(true);
        assertThat(result.get("stopHitBeforeTarget")).isEqualTo(false);
        // 09:00 봉이 빠진 경우: 시가나 최대 수익률을 추정하지 않고 null 로 남겨야 해요.
        var gap = MorningOutcomeService.calculate(List.of(rows.getLast()), OPEN, OPEN.plusSeconds(600), BigDecimal.valueOf(100),
                null, BigDecimal.valueOf(3), BigDecimal.valueOf(-2), Instant.now().plus(Duration.ofDays(1)));
        assertThat(gap.get("nextOpenReturn")).isNull();
        assertThat(gap.get("nextMorningMaxReturn")).isNull();
        assertThat(gap.get("hit1Pct")).isNull();
    }
    /**
     * 무엇: 한 봉 안에서 목표(+3%)와 손절(-2%)이 모두 닿았을 때 순서를 판단하는 규칙을 확인해요.
     * 입력: 시가 100 에서 104/97 을 모두 찍은 봉, 그리고 시가부터 97 로 갭하락한 봉.
     * 기대: 앞의 경우는 순서를 알 수 없어 null, 갭하락은 시가에서 이미 손절이므로 true 예요.
     */
    @Test void sameCandleTargetAndStopAreAmbiguousButOpeningGapHasKnownOrder() {
        assertThat(MorningOutcomeService.stopBeforeTarget(List.of(candle(0, 100, 104, 97, 101)), BigDecimal.valueOf(100),
                BigDecimal.valueOf(3), BigDecimal.valueOf(-2))).isNull();
        assertThat(MorningOutcomeService.stopBeforeTarget(List.of(candle(0, 97, 104, 97, 101)), BigDecimal.valueOf(100),
                BigDecimal.valueOf(3), BigDecimal.valueOf(-2))).isTrue();
    }
}
