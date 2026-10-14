/*
 * FiveMinuteCandleAggregator(틱을 5분봉으로 모으는 집계기)가 이미 확정된 봉을
 * 늦게 도착한 틱으로 덮어쓰지 않는지 확인해요. 확정봉이 바뀌면 과거 판정이 흔들리므로 중요해요.
 */
package com.sunmo.stockplatform.candle;

import com.sunmo.stockplatform.candle.application.FiveMinuteCandleAggregator;
import com.sunmo.stockplatform.market.domain.MarketTick;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

/** 늦은 틱이 확정봉을 고치지 않는지 점검하는 테스트예요. */
class LateTickRevisionTest {
    /**
     * 이전 구간이 확정된 뒤 들어온 늦은 틱은 무시하는지 확인해요.
     * 준비: 워터마크(봉 확정 전 기다리는 여유 시간) 2초, 00:00:01과 00:05:01 틱 뒤에 00:04:59 틱을 넣어요.
     * 기대: 늦은 틱 처리 결과가 비어 있어요(아무 봉도 갱신되지 않음).
     */
    @Test
    void ignoresLateTickAfterPreviousBucketWasFinalized() {
        var aggregator = new FiveMinuteCandleAggregator(Duration.ofSeconds(2));
        aggregator.accept(tick("2026-08-18T00:00:01Z", "100", 1, 1, 1));
        aggregator.accept(tick("2026-08-18T00:05:01Z", "110", 1, 2, 2));
        var ignored = aggregator.accept(tick("2026-08-18T00:04:59Z", "120", 2, 3, 3));
        assertThat(ignored).isEmpty();
    }

    /** 종목 005930의 테스트용 틱을 만드는 도우미예요(거래대금은 가격×누적거래량으로 단순 계산). */
    private MarketTick tick(String value, String price, long volume, long cumulative, long sequence) {
        Instant instant = Instant.parse(value);
        return new MarketTick("005930", instant.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(), instant,
                new BigDecimal(price), volume, cumulative,
                new BigDecimal(price).multiply(BigDecimal.valueOf(cumulative)), sequence);
    }
}
