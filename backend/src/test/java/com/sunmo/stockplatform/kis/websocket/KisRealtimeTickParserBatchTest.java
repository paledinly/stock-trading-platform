/*
 * KisRealtimeTickParser가 한 프레임에 여러 체결이 묶여 오는 KIS 실시간 메시지를 모두 나눠 읽는지 확인해요.
 * 묶음 중 일부만 읽거나 짧은 프레임을 그대로 받아들이면 틱이 빠지거나 값이 밀리므로 중요해요.
 */
package com.sunmo.stockplatform.kis.websocket;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 묶음 체결 프레임 파싱을 점검하는 테스트예요. */
class KisRealtimeTickParserBatchTest {
    private final KisRealtimeTickParser parser = new KisRealtimeTickParser();

    /**
     * 묶음 프레임의 체결을 빠짐없이 읽는지 확인해요.
     * 준비: 005930과 000660 체결 레코드를 이어 붙이고 건수 2로 파싱해요.
     * 기대: 2건이 순서대로 나오고 두 번째 가격 210000, 체결량 7이에요.
     */
    @Test
    void parsesEveryTradeInCombinedFrame() {
        // 준비
        String[] first = trade("005930", "091501", "72000", "15", "123456", "8888832000");
        String[] second = trade("000660", "091502", "210000", "7", "654321", "137407410000");
        String payload = String.join("^", first) + "^" + String.join("^", second);

        // 실행
        var ticks = parser.parseMany(payload, 2);

        // 확인
        assertThat(ticks).hasSize(2);
        assertThat(ticks.get(0).stockCode()).isEqualTo("005930");
        assertThat(ticks.get(1).stockCode()).isEqualTo("000660");
        assertThat(ticks.get(1).price()).isEqualByComparingTo("210000");
        assertThat(ticks.get(1).tradeVolume()).isEqualTo(7);
    }

    /**
     * 선언된 건수보다 필드가 모자란 프레임은 거부하는지 확인해요.
     * 준비: 레코드 1건짜리 문자열을 건수 2로 파싱해요.
     * 기대: "expected at least 92" 메시지의 IllegalArgumentException이 나요.
     */
    @Test
    void rejectsFrameShorterThanDeclaredTradeCount() {
        String payload = String.join("^", trade("005930", "091501", "72000", "15", "123456", "8888832000"));

        assertThatThrownBy(() -> parser.parseMany(payload, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected at least 92");
    }

    /** 필요한 필드만 채운 1건 분량의 체결 필드 배열을 만드는 도우미예요. */
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
        fields[33] = "20260923";
        return fields;
    }
}
