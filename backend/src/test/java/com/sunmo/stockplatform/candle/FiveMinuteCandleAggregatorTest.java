/*
 * FiveMinuteCandleAggregator(실시간 틱을 5분봉으로 모으는 집계기)의 핵심 규칙을 확인해요.
 * 봉 확정 시점, 중복·역순·늦은 틱 처리, 장 마감 동시호가 체결의 15:25 봉 귀속이 틀리면 모든 스캐너 판정이 흔들려요.
 */
package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

/** 실시간 5분봉 집계 규칙을 점검하는 테스트예요. */
class FiveMinuteCandleAggregatorTest {
    // 워터마크(봉 확정 전 늦은 틱을 기다리는 여유 시간)는 2초예요.
    private final FiveMinuteCandleAggregator aggregator = new FiveMinuteCandleAggregator(Duration.ofSeconds(2));

    /**
     * 시가·고가·거래량을 모으고 다음 구간 경계에서 이전 봉을 확정하는지 확인해요.
     * 준비: 00:00:01과 00:04:59 틱 뒤에 00:05:00 틱을 넣어요.
     * 기대: 진행 중 봉은 시가 100·고가 110·거래량 8이고, 경계 틱에서 이전 봉이 종가 110으로 확정되며 새 봉은 00:05에 시작해요.
     */
    @Test
    void aggregatesOhlcvAndClosesAtNextBoundary() {
        var first = tick("2026-08-18T00:00:01Z", "100", 5, 5, "500", 1);
        var second = tick("2026-08-18T00:04:59Z", "110", 3, 8, "830", 2);
        var next = tick("2026-08-18T00:05:00Z", "105", 2, 10, "1040", 3);
        aggregator.accept(first);
        var update = aggregator.accept(second).getFirst();
        assertThat(update.open()).isEqualByComparingTo("100");
        assertThat(update.high()).isEqualByComparingTo("110");
        assertThat(update.volume()).isEqualTo(8);
        var boundary = aggregator.accept(next);
        assertThat(boundary.getFirst().finalCandle()).isTrue();
        assertThat(boundary.getFirst().close()).isEqualByComparingTo("110");
        assertThat(boundary.get(1).startTime()).isEqualTo(Instant.parse("2026-08-18T00:05:00Z"));
    }

    /**
     * 같은 순번 틱은 무시하는지 확인해요.
     * 준비: 순번 7인 틱을 두 번 넣어요.
     * 기대: 두 번째 결과가 비어 있어요.
     */
    @Test
    void ignoresDuplicateSequence() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 5, 5, "500", 7));
        assertThat(aggregator.accept(tick("2026-08-18T00:00:02Z", "120", 5, 10, "1100", 7))).isEmpty();
    }

    /**
     * 누적 거래량이 초기화(감소)되면 체결량으로 거래량을 더하는지 확인해요.
     * 준비: 누적 100 뒤에 누적 2·체결량 3인 틱을 넣어요.
     * 기대: 거래량 5+3=8이에요.
     */
    @Test
    void usesTradeVolumeWhenCumulativeVolumeResets() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 5, 100, "10000", 1));
        var reset = aggregator.accept(tick("2026-08-18T00:00:02Z", "101", 3, 2, "202", 2)).getFirst();
        assertThat(reset.volume()).isEqualTo(8);
    }

    /**
     * 시간이 거꾸로 온 틱은 무시하고, 다음 틱의 누적 증가분 계산도 망가뜨리지 않는지 확인해요.
     * 준비: 00:00:02(누적 100) 뒤에 00:00:01(누적 90) 틱, 그 다음 00:00:03(누적 102) 틱을 넣어요.
     * 기대: 역순 틱은 무시되고, 다음 봉 거래량은 5+2=7, 저가는 100 그대로예요.
     */
    @Test
    void ignoresOutOfOrderTickWithoutCorruptingFollowingCumulativeDelta() {
        aggregator.accept(tick("2026-08-18T00:00:02Z", "100", 5, 100, "10000", 1));
        assertThat(aggregator.accept(tick("2026-08-18T00:00:01Z", "90", 3, 90, "9000", 2)))
                .isEmpty();

        var next = aggregator.accept(tick("2026-08-18T00:00:03Z", "101", 2, 102, "10202", 3)).getFirst();

        assertThat(next.volume()).isEqualTo(7);
        assertThat(next.low()).isEqualByComparingTo("100");
    }

    /**
     * 틱이 없어도 워터마크가 지나면 봉을 확정하는지 확인해요.
     * 준비: 00:00:01 틱 하나만 넣고 00:05:01, 00:05:02 시각으로 flush해요.
     * 기대: 00:05:01에는 아직 없음, 00:05:02(경계+2초)에는 1개 확정이에요.
     */
    @Test
    void watermarkClosesSilentBucket() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 1, 1, "100", 1));
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:01Z"))).isEmpty();
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:02Z"))).hasSize(1);
    }

    /**
     * 워터마크 뒤에 온 늦은 틱이 확정봉을 부분 값으로 바꾸지 못하는지 확인해요.
     * 준비: 거래량 600으로 확정된 뒤 00:04:55 늦은 틱을 넣어요.
     * 기대: 늦은 틱 결과는 비어 있고, 이후 flush해도 새로 확정되는 봉이 없어요.
     */
    @Test
    void lateTickAfterWatermarkCannotReplaceTheFinalCandleWithAPartialOne() {
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 100, 100, "10000", 1));
        aggregator.accept(tick("2026-08-18T00:04:50Z", "101", 500, 600, "60500", 2));
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:05:02Z")).getFirst().volume()).isEqualTo(600);

        var ignored = aggregator.accept(tick("2026-08-18T00:04:55Z", "102", 50, 650, "65600", 3));

        assertThat(ignored).isEmpty();
        assertThat(aggregator.flush(Instant.parse("2026-08-18T00:10:00Z"))).isEmpty();
    }

    /**
     * 15:30 정각의 종가 체결이 마지막 정규장 구간(15:25 봉)에 들어가는지 확인해요.
     * 준비: 15:25:01 틱 뒤에 15:30:00 틱을 넣어요.
     * 기대: 15:25 봉이 종가 101, 거래량 7로 갱신돼요.
     */
    @Test
    void closingPrintBelongsToTheFinalRegularSessionBucket() {
        aggregator.accept(tick("2026-08-18T06:25:01Z", "100", 5, 5, "500", 1));

        var update = aggregator.accept(tick("2026-08-18T06:30:00Z", "101", 2, 7, "702", 2)).getFirst();

        assertThat(update.startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(update.close()).isEqualByComparingTo("101");
        assertThat(update.volume()).isEqualTo(7);
    }

    /**
     * 동시호가(15:20~15:30) 동안 체결이 없다가 무작위 시각(15:30:17)에 나온 종가 체결이 15:25 확정봉이 되는지 확인해요.
     * 준비: 15:19:59 틱 뒤에 15:30:17 종가 체결 틱을 넣고, 15:30:30과 15:31:02에 flush해요.
     * 기대: 15:15 봉이 확정되고 15:25 봉(종가 103, 거래량 40)이 생기며, 15:31:02에 15:25 봉이 확정돼요.
     */
    @Test
    void randomEndClosingPrintAfterAnEmptyClosingCallBecomesTheFinal1525Bar() {
        // Last continuous trade 15:19:59, no executions during the 15:20-15:30 call, print stamped 15:30:17.
        aggregator.accept(tick("2026-08-18T06:19:59Z", "100", 5, 500, "50000", 1));
        var print = aggregator.accept(tick("2026-08-18T06:30:17Z", "103", 40, 540, "54120", 2));

        assertThat(print).hasSize(2);
        assertThat(print.getFirst().finalCandle()).isTrue();
        assertThat(print.getFirst().startTime()).isEqualTo(Instant.parse("2026-08-18T06:15:00Z"));
        assertThat(print.get(1).startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(print.get(1).close()).isEqualByComparingTo("103");
        assertThat(print.get(1).volume()).isEqualTo(40);
        assertThat(aggregator.flush(Instant.parse("2026-08-18T06:30:30Z"))).isEmpty();
        var closed = aggregator.flush(Instant.parse("2026-08-18T06:31:02Z"));
        assertThat(closed).singleElement().satisfies(bar -> {
            assertThat(bar.finalCandle()).isTrue();
            assertThat(bar.startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        });
    }

    /**
     * 15:25 봉은 동시호가 체결 시간 창이 끝나기 전에는 확정하지 않는지 확인해요.
     * 준비: 15:25:10 틱 뒤 15:30:05에 flush하고, 15:30:29 종가 체결 틱을 넣어요.
     * 기대: 15:30:05에는 확정 없음, 종가 체결이 15:25 봉에 반영(종가 99, 거래량 10)되고 15:31:02에 확정돼요.
     */
    @Test
    void final1525BucketIsNotFinalizedBeforeTheClosingAuctionWindowEnds() {
        aggregator.accept(tick("2026-08-18T06:25:10Z", "100", 1, 1, "100", 1));
        assertThat(aggregator.flush(Instant.parse("2026-08-18T06:30:05Z"))).isEmpty();

        var print = aggregator.accept(tick("2026-08-18T06:30:29Z", "99", 9, 10, "991", 2));

        assertThat(print).singleElement().satisfies(bar -> {
            assertThat(bar.startTime()).isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
            assertThat(bar.close()).isEqualByComparingTo("99");
            assertThat(bar.volume()).isEqualTo(10);
        });
        assertThat(aggregator.flush(Instant.parse("2026-08-18T06:31:02Z"))).hasSize(1);
    }

    /**
     * 동시호가 시간 창의 구간 계산과 장 마감 후 체결 거부를 확인해요.
     * 준비: 15:20~15:31:01 사이 여러 시각의 구간 시작을 계산하고, 15:40 시간외 틱을 넣어요.
     * 기대: 15:20은 15:20 구간, 15:29:59~15:31:00은 15:25 구간, 15:31:01과 15:40은 예외예요.
     */
    @Test
    void bucketsClosingAuctionWindowAndRejectsPostCloseTrades() {
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:20:00Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:20:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:29:59Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:30:00Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:30:30Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThat(FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:31:00Z")))
                .isEqualTo(Instant.parse("2026-08-18T06:25:00Z"));
        assertThatThrownBy(() -> FiveMinuteCandleAggregator.bucketStart(Instant.parse("2026-08-18T06:31:01Z")))
                .isInstanceOf(IllegalArgumentException.class);
        // The after-hours closing-price session (15:40) is never regular-session data.
        assertThatThrownBy(() -> aggregator.accept(tick("2026-08-18T06:40:00Z", "100", 1, 1, "100", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 장 시작 전 틱은 거부하는지 확인해요.
     * 준비: 08:59:59(서울) 틱을 넣어요.
     * 기대: IllegalArgumentException이 나요.
     */
    @Test
    void rejectsPreMarketTick() {
        assertThatThrownBy(() -> aggregator.accept(tick("2026-08-17T23:59:59Z", "100", 1, 1, "100", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 005930의 테스트용 틱을 만드는 도우미예요. */
    private MarketTick tick(String instant, String price, long volume, long cumulative, String value, long sequence) {
        Instant time = Instant.parse(instant);
        return new MarketTick("005930", time.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(), time, new BigDecimal(price),
                volume, cumulative, new BigDecimal(value), sequence);
    }
}
