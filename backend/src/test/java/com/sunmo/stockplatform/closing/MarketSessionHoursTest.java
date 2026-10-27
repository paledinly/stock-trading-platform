/*
 * 수능일처럼 거래소가 정규장 시간을 바꾼 날(MarketSessionHours)에 수집·5분봉·평가 시각이 함께 옮겨지는지 확인해요.
 * 시간이 어긋나면 마감 75분 전에 평가하거나 16:30 종가 체결을 버리고, 다음날 관측이 불완전으로 남아요.
 */
package com.sunmo.stockplatform.closing;

import com.sunmo.stockplatform.candle.application.CandleBackfillService;
import com.sunmo.stockplatform.candle.application.CandleGapDetector;
import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.closing.application.MarketSessionHours;
import com.sunmo.stockplatform.kis.candle.MinuteCandle;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import com.sunmo.stockplatform.marketwide.application.MarketSessionPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 특별 개장일 장 시간 변환을 점검하는 테스트예요. */
class MarketSessionHoursTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate SUNEUNG = LocalDate.of(2026, 11, 19);
    private static final LocalDate NORMAL = LocalDate.of(2026, 11, 18);

    @BeforeEach
    void configure() {
        MarketSessionHours.configure(MarketSessionHours.parse("2026-11-19@10:00-16:30"));
    }

    @AfterEach
    void reset() {
        MarketSessionHours.configure(Map.of());
    }

    /**
     * 평소 시각과 그날 실제 시각을 오가는 변환을 확인해요.
     * 기대: 수능일 15:15 → 16:15, 16:15 → 15:15. 평소 날은 그대로예요.
     */
    @Test
    void convertsBetweenRegularAndActualTimes() {
        assertThat(MarketSessionHours.open(SUNEUNG)).isEqualTo(LocalTime.of(10, 0));
        assertThat(MarketSessionHours.shift(SUNEUNG)).isEqualTo(Duration.ofHours(1));
        assertThat(MarketSessionHours.regularOpen(SUNEUNG)).isEqualTo(LocalTime.of(9, 0));
        assertThat(MarketSessionHours.at(SUNEUNG, LocalTime.of(15, 15))).isEqualTo(at("2026-11-19T16:15:00"));
        assertThat(MarketSessionHours.regularTime(at("2026-11-19T16:15:00"))).isEqualTo(LocalTime.of(15, 15));
        assertThat(MarketSessionHours.at(NORMAL, LocalTime.of(15, 15))).isEqualTo(at("2026-11-18T15:15:00"));
        assertThat(MarketSessionHours.regularTime(at("2026-11-18T15:15:00"))).isEqualTo(LocalTime.of(15, 15));
        // 새벽 00:30은 전날 23:30이 아니라 00:00으로 봐요(18:00 이후 작업이 새벽에 돌지 않게).
        assertThat(MarketSessionHours.regularTime(at("2026-11-19T00:30:00"))).isEqualTo(LocalTime.MIDNIGHT);
    }

    /**
     * cron이 평소 시각과 한 시간 뒤에 두 번 깨어날 때 맞는 쪽만 통과하는지 확인해요.
     * 기대: 수능일은 16:16:15만, 평소 날은 15:16:15만 통과해요.
     */
    @Test
    void onlyTheMatchingCronFireRuns() {
        LocalTime regular = LocalTime.of(15, 16, 15);
        assertThat(MarketSessionHours.firesAt(at("2026-11-19T15:16:15"), regular)).isFalse();
        assertThat(MarketSessionHours.firesAt(at("2026-11-19T16:16:15"), regular)).isTrue();
        assertThat(MarketSessionHours.firesAt(at("2026-11-18T15:16:15"), regular)).isTrue();
        assertThat(MarketSessionHours.firesAt(at("2026-11-18T16:16:15"), regular)).isFalse();
    }

    /**
     * 지원하지 않는 설정은 시작할 때 막는지 확인해요.
     * 기대: 마감 30분 이동, 형식 오류, 개장이 마감보다 늦은 경우 모두 예외예요.
     */
    @Test
    void rejectsUnsupportedSessions() {
        assertThatThrownBy(() -> MarketSessionHours.configure(MarketSessionHours.parse("2026-11-19@10:00-16:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketSessionHours.parse("2026-11-19 10:00-16:30"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketSessionHours.configure(MarketSessionHours.parse("2026-11-19@16:40-16:30")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MarketSessionHours.parse(" 2026-11-19@10:00-16:30 , 2027-01-04@10:00-15:30 ")).hasSize(2);
    }

    /**
     * 달력의 개장·마감이 특별 개장일을 따르는지 확인해요(실시간 연결·다음날 관측 창이 이 값을 써요).
     */
    @Test
    void calendarUsesSpecialHours() {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null, null,
                null, null, List.of()));
        assertThat(calendar.open(SUNEUNG)).isEqualTo(at("2026-11-19T10:00:00"));
        assertThat(calendar.close(SUNEUNG)).isEqualTo(at("2026-11-19T16:30:00"));
        assertThat(calendar.close(NORMAL)).isEqualTo(at("2026-11-18T15:30:00"));
    }

    /**
     * 실시간 5분봉 칸이 그날 시간을 따르는지 확인해요.
     * 기대: 10:00:01 → 10:00 칸, 16:30:20 종가 체결 → 16:25 칸, 09:30과 16:31:30은 장 밖이에요.
     */
    @Test
    void realtimeBucketsFollowSpecialHours() {
        assertThat(FiveMinuteCandleAggregator.bucketStart(at("2026-11-19T10:00:01"))).isEqualTo(at("2026-11-19T10:00:00"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(at("2026-11-19T15:31:00"))).isEqualTo(at("2026-11-19T15:30:00"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(at("2026-11-19T16:30:20"))).isEqualTo(at("2026-11-19T16:25:00"));
        assertThatThrownBy(() -> FiveMinuteCandleAggregator.bucketStart(at("2026-11-19T09:30:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FiveMinuteCandleAggregator.bucketStart(at("2026-11-19T16:31:30")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FiveMinuteCandleAggregator.bucketStart(at("2026-11-18T15:30:20"))).isEqualTo(at("2026-11-18T15:25:00"));
    }

    /**
     * KIS 분봉 보충이 16:30 분봉을 16:25 칸에 넣고 16:20 동시호가 무체결 분봉을 버리는지 확인해요.
     */
    @Test
    void minuteBackfillUsesShiftedClosingBar() {
        var result = CandleBackfillService.aggregate(List.of(
                minute("2026-11-19T16:24:00", 10), minute("2026-11-19T16:21:00", 0),
                minute("2026-11-19T16:30:00", 50)));
        assertThat(result).extracting(row -> row.startTime())
                .containsExactly(at("2026-11-19T16:20:00"), at("2026-11-19T16:25:00"));
        // 16:21 무체결 분봉은 버리고, 16:24 체결분만 16:20 칸에 남아요.
        assertThat(result.getFirst().volume()).isEqualTo(10);
        assertThat(result.getLast().volume()).isEqualTo(50);
    }

    /**
     * 빈 칸 찾기가 10:00부터 시작하고 16:20 동시호가 칸을 빼는지 확인해요.
     */
    @Test
    void gapDetectionUsesSpecialHours() {
        List<java.time.Instant> gaps = new CandleGapDetector().gaps(SUNEUNG, at("2026-11-19T18:00:00"), List.of());
        assertThat(gaps.getFirst()).isEqualTo(at("2026-11-19T10:00:00"));
        assertThat(gaps.getLast()).isEqualTo(at("2026-11-19T16:25:00"));
        assertThat(gaps).doesNotContain(at("2026-11-19T16:20:00"));
        assertThat(gaps).hasSize(77);
    }

    /**
     * 시장 스캔 시간(평소 09:00~15:20)이 수능일엔 10:00~16:20으로 옮겨지는지 확인해요.
     */
    @Test
    void marketScanWindowShifts() {
        var policy = new MarketSessionPolicy(new MarketWideScheduleProperties(true, 120, 30, false,
                LocalTime.of(9, 0), LocalTime.of(15, 20), LocalTime.of(14, 0),
                Duration.ofMinutes(5), Duration.ofMinutes(2), List.of()));
        assertThat(policy.evaluate(at("2026-11-19T09:30:00")).eligible()).isFalse();
        assertThat(policy.evaluate(at("2026-11-19T10:00:00")).eligible()).isTrue();
        assertThat(policy.evaluate(at("2026-11-19T16:10:00")).eligible()).isTrue();
        assertThat(policy.evaluate(at("2026-11-19T16:25:00")).eligible()).isFalse();
    }

    private static MinuteCandle minute(String time, long volume) {
        BigDecimal price = BigDecimal.valueOf(100);
        return new MinuteCandle(at(time), price, price, price, price, volume, null);
    }

    private static java.time.Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL).toInstant();
    }
}
