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

    /** v2 계획이에요. analyzedAt은 null, 판단은 WATCH_ONLY, 최우선 감시 종목은 삼성전자예요. */
    private static String v2(String mode, String top, String picks) {
        return """
                {"schemaVersion":"ai-premarket-v2","analysisId":"20261111-PREMARKET","targetDate":"2026-11-11",
                 "analysisMode":"%s","analyzedAt":null,"analysisValidUntil":"2026-11-11T09:00:00+09:00",
                 "model":{"name":"ChatGPT","version":null,"promptVersion":"premarket-v2","temperature":null},
                 "market":{"outlook":"NEUTRAL","regime":"VOLATILE_MIXED","riskLevel":"HIGH","news":[]},
                 "tradeDecision":"WATCH_ONLY","topPriorityStockCodes":[%s],"picks":[%s],
                 "avoid":[{"stockCode":"000660","stockName":"SK하이닉스","reason":"과열","riskType":"OVEREXTENDED"}]}"""
                .formatted(mode, top, picks);
    }

    private static final String SAMSUNG_V2 = """
            {"stockCode":"005930","stockName":"삼성전자","priority":1,"status":"WATCH","reason":"테스트","confidence":"MEDIUM",
             "sources":[{"title":"t","url":"https://x","publishedAt":"2026-11-11T06:00:00+09:00"}],
             "noBuyConditions":["갭 3% 이상"],"invalidationConditions":[]}""";

    /**
     * 기대: v2 계획은 analyzedAt이 null이어도 저장할 수 있고(경고), WATCH_ONLY와 최우선 종목 표시가 들어가요.
     * ChatGPT가 코드 블록으로 감싸 보내도 읽어요.
     */
    @Test
    void acceptsVersionTwoPlansWrappedInACodeBlock() {
        String raw = "```json\n" + v2("PREMARKET", "\"005930\"", SAMSUNG_V2) + "\n```";
        var result = beforeOpen().validate(raw);

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("analyzedAt이 없어요")).anyMatch(w -> w.contains("model.version"));
        var plan = result.plan();
        assertThat(plan.schemaVersion()).isEqualTo("ai-premarket-v2");
        assertThat(plan.analysisId()).isEqualTo("20261111-PREMARKET");
        assertThat(plan.tradeDecision()).isEqualTo("WATCH_ONLY");
        assertThat(plan.riskLevel()).isEqualTo("HIGH");
        assertThat(plan.onTime()).isTrue();
        assertThat(plan.validUntil()).isEqualTo(Instant.parse("2026-11-11T00:00:00Z"));
        assertThat(plan.picks()).filteredOn(p -> p.kind().equals("PICK")).singleElement()
                .satisfies(p -> assertThat(p.topPriority()).isTrue());
        assertThat(plan.picks()).filteredOn(p -> p.kind().equals("AVOID")).singleElement()
                .satisfies(p -> assertThat(p.topPriority()).isFalse());
    }

    /** 기대: 최우선 감시 종목이 picks에 없거나 3개를 넘으면 거부해요. */
    @Test
    void checksTopPriorityCodes() {
        assertThat(beforeOpen().validate(v2("PREMARKET", "\"000660\"", SAMSUNG_V2)).errors())
                .anyMatch(e -> e.contains("topPriorityStockCodes의 000660"));
        assertThat(beforeOpen().validate(v2("PREMARKET", "\"005930\",\"1\",\"2\",\"3\"", SAMSUNG_V2)).errors())
                .anyMatch(e -> e.contains("최대 3개"));
    }

    /** 기대: analysisMode가 INTRADAY면 장 시작 전에 넣어도 집계에서 빠져요. */
    @Test
    void excludesIntradayAnalysis() {
        var result = beforeOpen().validate(v2("INTRADAY", "", SAMSUNG_V2));

        assertThat(result.errors()).isEmpty();
        assertThat(result.plan().onTime()).isFalse();
        assertThat(result.warnings()).anyMatch(w -> w.contains("INTRADAY"));
    }

    /**
     * 기대: 프로그램이 계산할 수 없는 진입 조건(지어낸 기준 이름, 시장 허용 같은 지표)이 있으면 저장은 하되 규칙 A가 기록되지 않는다고 경고해요.
     * 지원 목록 안의 조건만 쓴 종목은 경고하지 않아요.
     */
    @Test
    void warnsWhenEntryConditionsCannotBeComputed() {
        String supported = SAMSUNG_V2.replace("\"invalidationConditions\":[]", """
                "invalidationConditions":[],"entryStrategy":{"conditions":[
                  {"metric":"RVOL_5M","operator":">=","value":null,"reference":null,"unit":"RATIO"},
                  {"metric":"CLOSE_5M","operator":">","value":null,"reference":"OPENING_RANGE_15M_HIGH","unit":"KRW"}]}""");
        String unsupported = SAMSUNG_V2.replace("005930", "000660").replace("삼성전자", "SK하이닉스").replace("\"priority\":1", "\"priority\":2")
                .replace("\"invalidationConditions\":[]", """
                "invalidationConditions":[],"entryStrategy":{"conditions":[
                  {"metric":"RVOL_5M","operator":">=","value":null,"reference":"VALIDATED_RVOL_THRESHOLD","unit":"RATIO"},
                  {"metric":"MARKET_TRADE_PERMISSION","operator":"=","value":1,"reference":null,"unit":"FLAG"}]}""");
        // 하이닉스를 picks에 넣으므로 예시의 avoid(하이닉스)는 비워요.
        String plan = v2("PREMARKET", "", supported + "," + unsupported).replaceAll("\"avoid\":\\[\\{[^\\]]*\\]", "\"avoid\":[]");

        var result = beforeOpen().validate(plan);

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).filteredOn(w -> w.contains("entryStrategy.conditions")).singleElement().satisfies(w -> {
            assertThat(w).startsWith("picks[1]");
            assertThat(w).contains("RVOL_5M 기준 VALIDATED_RVOL_THRESHOLD").contains("지표 MARKET_TRADE_PERMISSION");
        });
    }

    /**
     * 기대: 화면이 내려주는 v4 예시(ai-premarket-v4.example.json)에 선정 이유·매수 금지 사유·출처만 채우면 저장할 수 있어요.
     * 예시의 확인형 진입 조건(CLOSE_5M > 15분 고가, RVOL_5M >= 프로그램 기준)은 규칙 A가 계산할 수 있어 경고가 없어요.
     */
    @Test
    void acceptsTheVersionFourExample() throws Exception {
        var mapper = new ObjectMapper();
        var root = versionFourExample(mapper);

        var result = beforeOpen().validate(mapper.writeValueAsString(root));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).noneMatch(w -> w.contains("규칙 A")).noneMatch(w -> w.contains("매수 금지·무효화 조건이 없어요"));
        assertThat(result.plan().schemaVersion()).isEqualTo("ai-premarket-v4");
        assertThat(result.plan().promptVersion()).isEqualTo("premarket-v4");
        assertThat(result.plan().picks()).filteredOn(p -> p.kind().equals("PICK")).singleElement()
                .satisfies(p -> assertThat(p.topPriority()).isTrue());
        var parsed = com.sunmo.stockplatform.aiplan.realtime.AiCondition.parse(root.withArray("picks").get(0));
        assertThat(parsed.supported()).isTrue();
        assertThat(parsed.conditions()).extracting(c -> c.metric() + " " + c.operator() + " " + c.reference() + " " + c.value())
                .containsExactly("CLOSE_5M > OPENING_RANGE_15M_HIGH null", "RVOL_5M >= null null");
    }

    /**
     * 기대: v4 원문은 모델 정보를 지어내지 말라고 해서 model 칸이 빠질 수 있어요. 오류 없이 UNKNOWN으로 저장하고 경고만 해요.
     * 확인형 진입 전략이 없거나(조기 진입만) 계산 못 하는 지표가 있으면 규칙 A 경고를 내요. PRICE > VWAP은 PRICE_VS_VWAP으로 읽어요.
     */
    @Test
    void acceptsVersionFourWithoutModelAndWarnsWhenOnlyEarlyMomentum() throws Exception {
        var mapper = new ObjectMapper();
        var root = versionFourExample(mapper);
        root.remove("model");
        var strategies = ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("picks").get(0)).withArray("strategies");
        strategies.remove(1);

        var result = beforeOpen().validate(mapper.writeValueAsString(root));

        assertThat(result.errors()).isEmpty();
        assertThat(result.plan().modelName()).isEqualTo("UNKNOWN");
        assertThat(result.plan().promptVersion()).isEqualTo("premarket-v4");
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("picks[0].strategies") && w.contains("CONFIRMED_ENTRY"));

        var confirmed = (com.fasterxml.jackson.databind.node.ObjectNode) strategies.get(0).deepCopy();
        confirmed.put("strategyType", "CONFIRMED_ENTRY");
        var conditions = (com.fasterxml.jackson.databind.node.ArrayNode) confirmed.get("entryRules").get("conditions");
        conditions.addObject().put("metric", "ORDERBOOK_SPREAD").put("operator", "<=").put("required", false);
        var parsed = com.sunmo.stockplatform.aiplan.realtime.AiCondition.parse(mapper.createObjectNode()
                .set("strategies", mapper.createArrayNode().add(confirmed)));
        assertThat(parsed.supported()).isTrue();
        assertThat(parsed.conditions()).extracting(c -> c.metric()).containsExactly("RVOL_5M", "PRICE_VS_VWAP");
        conditions.addObject().put("metric", "VOLUME_1M").put("operator", ">=").put("required", true);
        parsed = com.sunmo.stockplatform.aiplan.realtime.AiCondition.parse(mapper.createObjectNode()
                .set("strategies", mapper.createArrayNode().add(confirmed)));
        assertThat(parsed.supported()).isFalse();
        assertThat(parsed.unsupported()).containsExactly("지표 VOLUME_1M");
    }

    /**
     * 기대: 11-12 실제 v4 답처럼 AI가 기준을 PROGRAM_CONFIG.CONFIRMED_ENTRY.RVOL_5M_MIN으로 적어도 프로그램 기준으로 읽어요.
     * 계산식이 없는 눌림목 회복선(PULLBACK_RECOVERY_LEVEL)은 계속 지원하지 않아요.
     */
    @Test
    void readsNamedProgramConfigReferencesInVersionFour() throws Exception {
        var mapper = new ObjectMapper();
        String strategy = """
                {"strategies":[{"strategyType":"CONFIRMED_ENTRY","entryRules":{"operator":"AND","conditions":[
                  {"metric":"CLOSE_5M","operator":">","value":null,"reference":"OPENING_RANGE_15M_HIGH","required":true},
                  {"metric":"RVOL_5M","operator":">=","value":null,"reference":"PROGRAM_CONFIG.CONFIRMED_ENTRY.RVOL_5M_MIN","required":true},
                  {"metric":"TURNOVER_5M","operator":">=","value":null,"reference":"PROGRAM_CONFIG.CONFIRMED_ENTRY.TURNOVER_5M_MIN","required":true}
                ]}}]}""";

        var parsed = com.sunmo.stockplatform.aiplan.realtime.AiCondition.parse(mapper.readTree(strategy));
        var pullback = com.sunmo.stockplatform.aiplan.realtime.AiCondition.parse(mapper.readTree(
                strategy.replace("OPENING_RANGE_15M_HIGH", "PULLBACK_RECOVERY_LEVEL")));

        assertThat(parsed.supported()).isTrue();
        assertThat(parsed.conditions()).extracting(c -> c.metric() + " " + c.reference())
                .containsExactly("CLOSE_5M OPENING_RANGE_15M_HIGH", "RVOL_5M null", "TURNOVER_5M null");
        assertThat(pullback.supported()).isFalse();
        assertThat(pullback.unsupported()).containsExactly("CLOSE_5M 기준 PULLBACK_RECOVERY_LEVEL");
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode versionFourExample(ObjectMapper mapper) throws Exception {
        String example = new org.springframework.core.io.ClassPathResource("ai-premarket/ai-premarket-v4.example.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replace("2026-11-12", "2026-11-11").replace("20261112", "20261111");
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(example);
        var pick = (com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("picks").get(0);
        pick.put("reason", "테스트");
        pick.withArray("noBuyReasons").add("갭 3% 이상");
        pick.withArray("sources").addObject().put("title", "t").put("url", "https://x").put("publishedAt", "2026-11-11T06:00:00+09:00");
        root.withArray("topPriorityStockCodes").add("005930");
        return root;
    }
}
