/*
 * AI 장전 계획 JSON 검사기가 형식·종목·거래일·시각(미래 정보, 장 시작 뒤 입력)을 제대로 거르는지 확인해요.
 */
package com.sunmo.stockplatform.aiplan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.aiplan.application.AiPlanValidator.StockRef;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.config.MarketWideScheduleProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 장전 계획 검사기 테스트예요. 기준 시각은 2026-11-11(수) 07:55 한국 시간이에요. */
class AiPlanValidatorTest {
    private static final Map<String, StockRef> STOCKS = Map.of(
            "005930", new StockRef(1, "005930", "삼성전자"), "000660", new StockRef(2, "000660", "SK하이닉스"));

    private static AiPlanValidator validator(String nowUtc) {
        var calendar = new ClosingTradingCalendar(new MarketWideScheduleProperties(false, 120, 30, false, null, null,
                null, null, null, List.of()), Clock.fixed(Instant.parse(nowUtc), ZoneOffset.UTC));
        return new AiPlanValidator(new ObjectMapper(), calendar, code -> Optional.ofNullable(STOCKS.get(code)));
    }

    private static AiPlanValidator beforeOpen() { return validator("2026-11-10T22:55:00Z"); }

    /** 필수 칸만 채운 계획이에요. picks와 그 밖의 칸은 테스트마다 바꿔 끼워요. */
    private static String plan(String targetDate, String analyzedAt, String decision, String picks, String extra) {
        return """
                {"schemaVersion":"ai-premarket-v1","targetDate":"%s","analyzedAt":"%s",
                 "model":{"name":"ChatGPT","version":"test-model","promptVersion":"premarket-v1"},
                 "market":{"outlook":"NEUTRAL"%s},"tradeDecision":"%s","picks":[%s]}"""
                .formatted(targetDate, analyzedAt, extra, decision, picks);
    }

    private static final String SAMSUNG = """
            {"stockCode":"005930","stockName":"삼성전자","priority":1,"reason":"테스트",
             "sources":[{"title":"t","url":"https://x","publishedAt":"2026-11-11T06:00:00+09:00"}],
             "noBuyCondition":"갭 3% 이상"}""";

    /** 기대: 장 시작 전에 들어온 정상 계획은 오류·경고 없이 통과하고, 집계 대상(onTime)이에요. 유효시간은 그날 15:30이에요. */
    @Test
    void acceptsCompletePlanBeforeOpen() {
        var result = beforeOpen().validate(plan("2026-11-11", "2026-11-11T07:50:00+09:00", "TRADE", SAMSUNG, ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.plan().onTime()).isTrue();
        assertThat(result.plan().validUntil()).isEqualTo(Instant.parse("2026-11-11T06:30:00Z"));
        assertThat(result.plan().picks()).extracting(p -> p.stock().id()).containsExactly(1L);
    }

    /** 기대: 지난 날짜, 휴장일(토요일), 미래 분석 시각은 모두 거부해요. */
    @Test
    void rejectsPastDatesClosedDaysAndFutureAnalysis() {
        assertThat(beforeOpen().validate(plan("2026-11-10", "2026-11-10T07:50:00+09:00", "TRADE", SAMSUNG, "")).errors())
                .anyMatch(e -> e.contains("지난 날짜"));
        assertThat(beforeOpen().validate(plan("2026-11-14", "2026-11-11T07:50:00+09:00", "TRADE", SAMSUNG, "")).errors())
                .anyMatch(e -> e.contains("거래일이 아니에요"));
        assertThat(beforeOpen().validate(plan("2026-11-11", "2026-11-11T08:30:00+09:00", "TRADE", SAMSUNG, "")).errors())
                .anyMatch(e -> e.contains("미래"));
    }

    /** 기대: 출처 발행 시각이 지금보다 미래면(미래 정보) 거부해요. */
    @Test
    void rejectsSourcesPublishedInTheFuture() {
        String news = ",\"news\":[{\"title\":\"n\",\"publishedAt\":\"2026-11-11T10:00:00+09:00\"}]";
        assertThat(beforeOpen().validate(plan("2026-11-11", "2026-11-11T07:50:00+09:00", "TRADE", SAMSUNG, news)).errors())
                .anyMatch(e -> e.contains("market.news[0].publishedAt"));
    }

    /** 기대: 같은 종목이 두 번 나오거나 마스터에 없는 코드면 거부하고, 이름이 다르면 경고만 해요. */
    @Test
    void checksStockCodes() {
        String picks = SAMSUNG + "," + SAMSUNG.replace("\"priority\":1", "\"priority\":2")
                + ",{\"stockCode\":\"999999\",\"priority\":3,\"reason\":\"r\"}";
        var result = beforeOpen().validate(plan("2026-11-11", "2026-11-11T07:50:00+09:00", "TRADE", picks, ""));
        assertThat(result.errors()).anyMatch(e -> e.contains("두 번 이상")).anyMatch(e -> e.contains("999999"));

        String renamed = SAMSUNG.replace("\"stockName\":\"삼성전자\"", "\"stockName\":\"삼전\"");
        var warned = beforeOpen().validate(plan("2026-11-11", "2026-11-11T07:50:00+09:00", "TRADE", renamed, ""));
        assertThat(warned.errors()).isEmpty();
        assertThat(warned.warnings()).anyMatch(w -> w.contains("마스터 이름"));
    }

    /** 기대: 장 시작(09:00) 뒤 입력은 저장은 되지만 onTime=false이고 경고가 붙어요. */
    @Test
    void flagsSubmissionsAfterTheOpen() {
        var result = validator("2026-11-11T00:10:00Z")
                .validate(plan("2026-11-11", "2026-11-11T07:50:00+09:00", "TRADE", SAMSUNG, ""));

        assertThat(result.errors()).isEmpty();
        assertThat(result.plan().onTime()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("장 시작 뒤"));
    }

    /** 기대: NO_TRADE는 종목 없이도 통과하고, TRADE는 종목이 없으면 거부해요. 형식이 깨진 JSON도 거부해요. */
    @Test
    void handlesDecisionsAndBrokenJson() {
        assertThat(beforeOpen().validate(plan("2026-11-11", "2026-11-11T07:50:00+09:00", "NO_TRADE", "", "")).valid()).isTrue();
        assertThat(beforeOpen().validate(plan("2026-11-11", "2026-11-11T07:50:00+09:00", "TRADE", "", "")).errors())
                .anyMatch(e -> e.contains("picks가 하나 이상"));
        assertThat(beforeOpen().validate("{ not json").errors()).anyMatch(e -> e.contains("JSON 형식"));
    }
}
