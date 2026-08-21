package com.sunmo.stockplatform.kis.websocket;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KisRealtimeTickParserBatchTest {
    private final KisRealtimeTickParser parser = new KisRealtimeTickParser();

    @Test
    void parsesEveryTradeInCombinedFrame() {
        String[] first = trade("005930", "091501", "72000", "15", "123456", "8888832000");
        String[] second = trade("000660", "091502", "210000", "7", "654321", "137407410000");
        String payload = String.join("^", first) + "^" + String.join("^", second);

        var ticks = parser.parseMany(payload, 2);

        assertThat(ticks).hasSize(2);
        assertThat(ticks.get(0).stockCode()).isEqualTo("005930");
        assertThat(ticks.get(1).stockCode()).isEqualTo("000660");
        assertThat(ticks.get(1).price()).isEqualByComparingTo("210000");
        assertThat(ticks.get(1).tradeVolume()).isEqualTo(7);
    }

    @Test
    void rejectsFrameShorterThanDeclaredTradeCount() {
        String payload = String.join("^", trade("005930", "091501", "72000", "15", "123456", "8888832000"));

        assertThatThrownBy(() -> parser.parseMany(payload, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected at least 92");
    }

    private String[] trade(String code, String time, String price, String volume,
                           String cumulativeVolume, String cumulativeValue) {
        String[] fields = new String[KisRealtimeTickParser.FIELDS_PER_TRADE];
        Arrays.fill(fields, "0");
        fields[0] = code;
        fields[1] = time;
        fields[2] = price;
        fields[12] = volume;
        fields[13] = cumulativeVolume;
        fields[14] = cumulativeValue;
        return fields;
    }
}
