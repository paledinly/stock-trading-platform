/*
 * AI 장전 분석 JSON(규격 ai-premarket-v1)을 읽어 형식·종목·거래일·시각을 검사하는 파일이에요.
 * 저장을 막는 문제는 errors, 저장은 하되 알려 줄 문제는 warnings로 나눠요.
 * AiPlanService가 저장 전에, 검사만 하는 API가 미리보기로 불러요. 규격 설명은 docs/AI_PREMARKET.md에 있어요.
 */
package com.sunmo.stockplatform.aiplan.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * AI 장전 분석 JSON 검사기예요. 시험 답안지 채점 전에 "이름·날짜·필수 칸이 다 채워졌나"를 먼저 보는 감독관이라고 생각하면 돼요.
 * AI가 적은 가격·조건 값은 맞는지 판단하지 않고 그대로 원본에만 남겨요. 여기서는 저장해도 되는 답안지인지만 봐요.
 */
public class AiPlanValidator {
    public static final String SCHEMA_VERSION = "ai-premarket-v1";
    public static final int MAX_PICKS = 20;
    public static final int MAX_AVOID = 20;
    // 서버와 사용자 PC 시계 차이를 감안한 여유예요. 분석 시각이 이보다 미래면 거부해요.
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    static final Set<String> OUTLOOKS = Set.of("BULLISH", "NEUTRAL", "BEARISH");
    static final Set<String> DECISIONS = Set.of("TRADE", "NO_TRADE");

    /** 종목 마스터에서 찾은 종목이에요. */
    public record StockRef(long id, String code, String name) { }

    /** 계획에 나온 종목 하나예요. kind는 PICK(매수 후보) 또는 AVOID(매수 금지)예요. */
    public record ParsedPick(String kind, StockRef stock, Integer priority, String reason) { }

    /** 검사를 통과한 계획의 저장용 값이에요. 원본 JSON은 따로 그대로 저장해요. */
    public record ParsedPlan(LocalDate targetDate, Instant analyzedAt, Instant validUntil, boolean onTime,
            String tradeDecision, String outlook, String modelName, String modelVersion, String promptVersion,
            List<ParsedPick> picks) { }

    /** 검사 결과예요. errors가 비어 있을 때만 plan이 있어요. */
    public record Result(List<String> errors, List<String> warnings, ParsedPlan plan) {
        public boolean valid() { return errors.isEmpty(); }
    }

    private final ObjectMapper json;
    private final ClosingTradingCalendar calendar;
    private final Function<String, Optional<StockRef>> stocks;

    /** 받는 것: JSON 해석기, 거래일 달력(현재 시각 포함), 종목 코드로 종목을 찾는 함수. */
    public AiPlanValidator(ObjectMapper json, ClosingTradingCalendar calendar, Function<String, Optional<StockRef>> stocks) {
        this.json = json;
        this.calendar = calendar;
        this.stocks = stocks;
    }

    /** 하는 일: 원본 JSON 글자를 검사해요. 받는 것: 붙여넣은 글자. 돌려주는 것: 오류·경고·저장용 값. */
    public Result validate(String raw) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode root;
        try {
            root = json.readTree(raw == null ? "" : raw);
        } catch (JsonProcessingException error) {
            return new Result(List.of("JSON 형식이 아니에요: " + error.getOriginalMessage()), List.of(), null);
        }
        if (root == null || !root.isObject())
            return new Result(List.of("JSON 최상위는 객체({ ... })여야 해요."), List.of(), null);

        String schema = text(root, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schema))
            errors.add("schemaVersion은 \"" + SCHEMA_VERSION + "\"이어야 해요(받은 값: " + schema + ").");

        Instant now = calendar.now();
        LocalDate today = calendar.today();
        LocalDate targetDate = date(root, "targetDate", errors);
        Instant analyzedAt = instant(root, "analyzedAt", true, errors);
        Instant validUntil = instant(root, "validUntil", false, errors);
        boolean onTime = false;
        if (targetDate != null) {
            if (!calendar.isTradingDay(targetDate))
                errors.add("targetDate " + targetDate + "는 거래일이 아니에요.");
            if (targetDate.isBefore(today))
                errors.add("지난 날짜(" + targetDate + ")의 계획은 입력할 수 없어요. 결과를 안 뒤의 입력이 될 수 있어요.");
            onTime = now.isBefore(calendar.open(targetDate));
            if (!targetDate.isBefore(today) && !onTime)
                warnings.add("장 시작 뒤에 입력했어요. 저장은 하지만 성과 집계에서는 빠져요.");
            if (validUntil == null)
                validUntil = calendar.close(targetDate);
        }
        if (analyzedAt != null) {
            if (analyzedAt.isAfter(now.plus(CLOCK_SKEW)))
                errors.add("analyzedAt이 지금보다 미래예요. 시각과 시간대(+09:00)를 확인해 주세요.");
            if (targetDate != null && calendar.isTradingDay(targetDate)
                    && analyzedAt.isBefore(calendar.close(calendar.previousTradingDay(targetDate))))
                warnings.add("전 거래일 장 마감 전에 한 분석이에요. 그 뒤 소식이 빠졌을 수 있어요.");
        }
        if (analyzedAt != null && validUntil != null && !validUntil.isAfter(analyzedAt))
            errors.add("validUntil은 analyzedAt보다 뒤여야 해요.");

        JsonNode model = root.path("model");
        String modelName = text(model, "name");
        String promptVersion = text(model, "promptVersion");
        if (blank(modelName)) errors.add("model.name이 필요해요(예: ChatGPT).");
        if (blank(promptVersion)) errors.add("model.promptVersion이 필요해요(예: premarket-v1).");
        if (blank(text(model, "version")))
            warnings.add("model.version이 없어요. 모델이 바뀌면 결과를 나눠 볼 수 없어요.");

        String outlook = text(root.path("market"), "outlook");
        if (!OUTLOOKS.contains(outlook)) errors.add("market.outlook은 BULLISH, NEUTRAL, BEARISH 중 하나여야 해요.");
        String decision = text(root, "tradeDecision");
        if (!DECISIONS.contains(decision)) errors.add("tradeDecision은 TRADE 또는 NO_TRADE여야 해요.");

        checkSources(root.path("market").path("news"), "market.news", now, errors, warnings);

        List<ParsedPick> picks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonNode pickNodes = root.path("picks");
        if (!pickNodes.isMissingNode() && !pickNodes.isArray()) errors.add("picks는 배열이어야 해요.");
        if (pickNodes.size() > MAX_PICKS) errors.add("picks는 최대 " + MAX_PICKS + "개예요.");
        if ("TRADE".equals(decision) && pickNodes.size() == 0)
            errors.add("tradeDecision이 TRADE면 picks가 하나 이상 있어야 해요.");
        if ("NO_TRADE".equals(decision) && pickNodes.size() > 0)
            warnings.add("tradeDecision이 NO_TRADE인데 picks가 있어요. 종목은 기록하지만 그날은 '매매 안 함'으로 집계해요.");
        Set<Integer> priorities = new HashSet<>();
        for (int i = 0; i < pickNodes.size(); i++) {
            JsonNode node = pickNodes.get(i);
            String where = "picks[" + i + "]";
            ParsedPick pick = pick(node, "PICK", where, seen, errors, warnings);
            if (node.path("priority").isInt()) {
                if (!priorities.add(node.path("priority").asInt()))
                    warnings.add(where + ".priority가 다른 종목과 같아요.");
            } else {
                errors.add(where + ".priority(1부터 시작하는 정수)가 필요해요.");
            }
            if (blank(text(node, "reason"))) errors.add(where + ".reason(선정 이유)이 필요해요.");
            if (!node.path("sources").isArray() || node.path("sources").isEmpty())
                warnings.add(where + "에 sources(출처)가 없어요.");
            checkSources(node.path("sources"), where + ".sources", now, errors, warnings);
            if (blank(text(node, "noBuyCondition")) && blank(text(node, "invalidation")))
                warnings.add(where + "에 매수 금지·무효화 조건이 없어요.");
            if (pick != null) picks.add(pick);
        }
        JsonNode avoidNodes = root.path("avoid");
        if (!avoidNodes.isMissingNode() && !avoidNodes.isArray()) errors.add("avoid는 배열이어야 해요.");
        if (avoidNodes.size() > MAX_AVOID) errors.add("avoid는 최대 " + MAX_AVOID + "개예요.");
        for (int i = 0; i < avoidNodes.size(); i++) {
            ParsedPick pick = pick(avoidNodes.get(i), "AVOID", "avoid[" + i + "]", seen, errors, warnings);
            if (pick != null) picks.add(pick);
        }

        if (!errors.isEmpty())
            return new Result(List.copyOf(errors), List.copyOf(warnings), null);
        return new Result(List.of(), List.copyOf(warnings), new ParsedPlan(targetDate, analyzedAt, validUntil, onTime,
                decision, outlook, modelName, text(model, "version"), promptVersion, List.copyOf(picks)));
    }

    /** 종목 한 줄을 읽어요. 종목 코드가 없거나, 마스터에 없거나, 이미 나온 종목이면 오류예요. */
    private ParsedPick pick(JsonNode node, String kind, String where, Set<String> seen, List<String> errors,
            List<String> warnings) {
        String code = text(node, "stockCode");
        if (blank(code) || !code.matches("[0-9A-Z]{6}")) {
            errors.add(where + ".stockCode는 6자리 종목 코드여야 해요(받은 값: " + code + ").");
            return null;
        }
        if (!seen.add(code)) {
            errors.add(where + ": " + code + " 종목이 picks/avoid에 두 번 이상 나와요.");
            return null;
        }
        Optional<StockRef> stock = stocks.apply(code);
        if (stock.isEmpty()) {
            errors.add(where + ": 종목 마스터에 없는 코드예요(" + code + ").");
            return null;
        }
        String name = text(node, "stockName");
        if (!blank(name) && !name.replace(" ", "").equals(stock.get().name().replace(" ", "")))
            warnings.add(where + ": 종목명 \"" + name + "\"이 마스터 이름 \"" + stock.get().name() + "\"과 달라요. 코드 기준으로 저장해요.");
        Integer priority = node.path("priority").isInt() ? node.path("priority").asInt() : null;
        return new ParsedPick(kind, stock.get(), priority, text(node, "reason"));
    }

    /** 출처 목록의 발행 시각이 지금보다 미래면(미래 정보) 오류예요. 시각이 없으면 경고만 해요. */
    private void checkSources(JsonNode sources, String where, Instant now, List<String> errors, List<String> warnings) {
        if (!sources.isArray()) return;
        for (int i = 0; i < sources.size(); i++) {
            JsonNode source = sources.get(i);
            String at = text(source, "publishedAt");
            if (blank(at)) continue;
            try {
                if (OffsetDateTime.parse(at).toInstant().isAfter(now.plus(CLOCK_SKEW)))
                    errors.add(where + "[" + i + "].publishedAt이 지금보다 미래예요(미래 정보).");
            } catch (DateTimeParseException error) {
                warnings.add(where + "[" + i + "].publishedAt을 읽지 못했어요(예: 2026-11-11T07:30:00+09:00).");
            }
        }
    }

    private static LocalDate date(JsonNode root, String field, List<String> errors) {
        String value = text(root, field);
        try {
            return LocalDate.parse(value == null ? "" : value);
        } catch (DateTimeParseException error) {
            errors.add(field + "는 YYYY-MM-DD 형식이어야 해요(받은 값: " + value + ").");
            return null;
        }
    }

    private static Instant instant(JsonNode root, String field, boolean required, List<String> errors) {
        String value = text(root, field);
        if (blank(value)) {
            if (required) errors.add(field + "가 필요해요(예: 2026-11-11T07:50:00+09:00).");
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException error) {
            errors.add(field + "는 시간대가 있는 시각이어야 해요(예: 2026-11-11T07:50:00+09:00, 받은 값: " + value + ").");
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : null;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
