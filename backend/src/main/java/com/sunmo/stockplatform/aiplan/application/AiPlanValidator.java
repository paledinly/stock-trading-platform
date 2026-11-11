/*
 * AI 장전 분석 JSON(규격 ai-premarket-v1, ai-premarket-v2)을 읽어 형식·종목·거래일·시각을 검사하는 파일이에요.
 * 저장을 막는 문제는 errors, 저장은 하되 알려 줄 문제는 warnings로 나눠요.
 * AiPlanService가 저장 전에, 검사만 하는 API가 미리보기로 불러요. 규격 설명은 docs/AI_PREMARKET.md에 있어요.
 */
package com.sunmo.stockplatform.aiplan.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.aiplan.realtime.AiCondition;
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
    public static final String SCHEMA_V1 = "ai-premarket-v1";
    public static final String SCHEMA_V2 = "ai-premarket-v2";
    // 새로 만드는 프롬프트가 쓰는 규격이에요. v1도 계속 받아요.
    public static final String SCHEMA_VERSION = SCHEMA_V2;
    public static final int MAX_PICKS = 20;
    public static final int MAX_TOP_PRIORITY = 3;
    public static final int MAX_AVOID = 20;
    // 서버와 사용자 PC 시계 차이를 감안한 여유예요. 분석 시각이 이보다 미래면 거부해요.
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    static final Set<String> OUTLOOKS = Set.of("BULLISH", "NEUTRAL", "BEARISH");
    static final List<String> DECISIONS_V1 = List.of("TRADE", "NO_TRADE");
    // v2: WATCH_ONLY는 감시만(신규 매수 제한), DATA_INSUFFICIENT는 자료 부족으로 매수 차단이에요.
    static final List<String> DECISIONS_V2 = List.of("TRADE", "WATCH_ONLY", "NO_TRADE", "DATA_INSUFFICIENT");
    /** 그날 종목을 채점하는 판단이에요. NO_TRADE·DATA_INSUFFICIENT는 '매매 안 함' 날이에요. */
    public static final Set<String> PICK_DECISIONS = Set.of("TRADE", "WATCH_ONLY");
    static final Set<String> MODES = Set.of("PREMARKET", "INTRADAY");
    static final List<String> REGIMES = List.of("TREND_UP", "TREND_DOWN", "SECTOR_ROTATION", "VOLATILE_MIXED", "WAIT_AND_SEE");
    static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "EXTREME");
    static final Set<String> CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW");

    /** 종목 마스터에서 찾은 종목이에요. */
    public record StockRef(long id, String code, String name) { }

    /** 계획에 나온 종목 하나예요. kind는 PICK(매수 후보) 또는 AVOID(매수 금지)예요. */
    public record ParsedPick(String kind, StockRef stock, Integer priority, String reason, boolean topPriority,
            String confidence) { }

    /** 검사를 통과한 계획의 저장용 값이에요. 원본 JSON은 따로 그대로 저장해요. */
    public record ParsedPlan(String schemaVersion, String analysisId, String analysisMode, LocalDate targetDate,
            Instant analyzedAt, Instant validUntil, boolean onTime, String tradeDecision, String outlook, String regime,
            String riskLevel, String modelName, String modelVersion, String promptVersion, List<ParsedPick> picks) { }

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
            root = json.readTree(stripFence(raw));
        } catch (JsonProcessingException error) {
            return new Result(List.of("JSON 형식이 아니에요: " + error.getOriginalMessage()), List.of(), null);
        }
        if (root == null || !root.isObject())
            return new Result(List.of("JSON 최상위는 객체({ ... })여야 해요."), List.of(), null);

        String schema = text(root, "schemaVersion");
        boolean v2 = SCHEMA_V2.equals(schema);
        if (!v2 && !SCHEMA_V1.equals(schema))
            errors.add("schemaVersion은 \"" + SCHEMA_V2 + "\" 또는 \"" + SCHEMA_V1 + "\"이어야 해요(받은 값: " + schema + ").");
        String mode = v2 ? text(root, "analysisMode") : "PREMARKET";
        if (!MODES.contains(mode)) errors.add("analysisMode는 PREMARKET 또는 INTRADAY여야 해요.");

        Instant now = calendar.now();
        LocalDate today = calendar.today();
        LocalDate targetDate = date(root, "targetDate", errors);
        // v2는 실제 시각을 확인하지 못하면 analyzedAt을 null로 적게 했어요. 그때는 저장 시각으로만 집계 여부를 판단해요.
        Instant analyzedAt = instant(root, "analyzedAt", !v2, errors);
        if (v2 && analyzedAt == null && !root.path("analyzedAt").isTextual())
            warnings.add("analyzedAt이 없어요. 분석 시각을 알 수 없어 저장 시각으로만 장 시작 전 여부를 판단해요.");
        Instant validUntil = instant(root, v2 ? "analysisValidUntil" : "validUntil", false, errors);
        boolean onTime = false;
        if (targetDate != null) {
            if (!calendar.isTradingDay(targetDate))
                errors.add("targetDate " + targetDate + "는 거래일이 아니에요.");
            if (targetDate.isBefore(today))
                errors.add("지난 날짜(" + targetDate + ")의 계획은 입력할 수 없어요. 결과를 안 뒤의 입력이 될 수 있어요.");
            boolean beforeOpen = now.isBefore(calendar.open(targetDate));
            onTime = beforeOpen && !"INTRADAY".equals(mode);
            if (!targetDate.isBefore(today) && !beforeOpen)
                warnings.add("장 시작 뒤에 입력했어요. 저장은 하지만 성과 집계에서는 빠져요.");
            else if ("INTRADAY".equals(mode))
                warnings.add("analysisMode가 INTRADAY예요. 장전 분석이 아니라서 저장만 하고 성과 집계에서는 빠져요.");
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
            errors.add((v2 ? "analysisValidUntil" : "validUntil") + "은 analyzedAt보다 뒤여야 해요.");

        JsonNode model = root.path("model");
        String modelName = text(model, "name");
        String promptVersion = text(model, "promptVersion");
        if (blank(modelName)) errors.add("model.name이 필요해요(예: ChatGPT).");
        if (blank(promptVersion)) errors.add("model.promptVersion이 필요해요(예: premarket-v2).");
        if (blank(text(model, "version")))
            warnings.add("model.version이 없어요. 모델이 바뀌면 결과를 나눠 볼 수 없어요.");

        JsonNode market = root.path("market");
        String outlook = text(market, "outlook");
        if (!OUTLOOKS.contains(outlook)) errors.add("market.outlook은 BULLISH, NEUTRAL, BEARISH 중 하나여야 해요.");
        String regime = text(market, "regime"), riskLevel = text(market, "riskLevel");
        if (v2 && !REGIMES.contains(regime))
            warnings.add("market.regime이 정해진 값(" + String.join(", ", REGIMES) + ")이 아니에요: " + regime);
        if (v2 && !RISK_LEVELS.contains(riskLevel))
            warnings.add("market.riskLevel이 정해진 값(LOW, MEDIUM, HIGH, EXTREME)이 아니에요: " + riskLevel);
        String decision = text(root, "tradeDecision");
        List<String> decisions = v2 ? DECISIONS_V2 : DECISIONS_V1;
        if (!decisions.contains(decision))
            errors.add("tradeDecision은 " + String.join(", ", decisions) + " 중 하나여야 해요.");

        checkSources(root.path("market").path("news"), "market.news", now, errors, warnings);

        List<ParsedPick> picks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonNode pickNodes = root.path("picks");
        if (!pickNodes.isMissingNode() && !pickNodes.isArray()) errors.add("picks는 배열이어야 해요.");
        if (pickNodes.size() > MAX_PICKS) errors.add("picks는 최대 " + MAX_PICKS + "개예요.");
        if ("TRADE".equals(decision) && pickNodes.size() == 0)
            errors.add("tradeDecision이 TRADE면 picks가 하나 이상 있어야 해요.");
        if (decision != null && decisions.contains(decision) && !PICK_DECISIONS.contains(decision) && pickNodes.size() > 0)
            warnings.add("tradeDecision이 " + decision + "인데 picks가 있어요. 종목은 기록하지만 그날은 '매매 안 함'으로 집계해요.");
        Set<String> top = new HashSet<>();
        JsonNode topNodes = root.path("topPriorityStockCodes");
        if (!topNodes.isMissingNode() && !topNodes.isNull() && !topNodes.isArray())
            errors.add("topPriorityStockCodes는 배열이어야 해요.");
        topNodes.forEach(node -> top.add(node.asText()));
        if (top.size() > MAX_TOP_PRIORITY) errors.add("topPriorityStockCodes는 최대 " + MAX_TOP_PRIORITY + "개예요.");
        Set<String> pickCodes = new HashSet<>();
        pickNodes.forEach(node -> pickCodes.add(text(node, "stockCode")));
        for (String code : top)
            if (!pickCodes.contains(code)) errors.add("topPriorityStockCodes의 " + code + "가 picks에 없어요.");
        Set<Integer> priorities = new HashSet<>();
        for (int i = 0; i < pickNodes.size(); i++) {
            JsonNode node = pickNodes.get(i);
            String where = "picks[" + i + "]";
            ParsedPick pick = pick(node, "PICK", where, seen, top, errors, warnings);
            String confidence = text(node, "confidence");
            if (v2 && confidence != null && !CONFIDENCE.contains(confidence))
                warnings.add(where + ".confidence는 HIGH, MEDIUM, LOW 중 하나여야 해요.");
            if (v2 && text(node, "status") != null && !"WATCH".equals(text(node, "status")))
                warnings.add(where + ".status가 WATCH가 아니에요. 장전 최초 상태는 WATCH예요.");
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
            boolean conditions = v2 ? node.path("noBuyConditions").size() > 0 || node.path("invalidationConditions").size() > 0
                    : !blank(text(node, "noBuyCondition")) || !blank(text(node, "invalidation"));
            if (!conditions)
                warnings.add(where + "에 매수 금지·무효화 조건이 없어요.");
            // v2: 실시간 감시의 규칙 A는 계산할 수 있는 조건만 받아요. 하나라도 모르면 그 종목은 규칙 A를 기록하지 않아요.
            AiCondition.Parsed entry = AiCondition.parse(node);
            if (v2 && !entry.supported())
                warnings.add(where + ".entryStrategy.conditions를 프로그램이 계산할 수 없어 이 종목의 AI 조건 감시(규칙 A)는 기록되지 않아요: "
                        + String.join(", ", entry.unsupported()));
            if (pick != null) picks.add(pick);
        }
        JsonNode avoidNodes = root.path("avoid");
        if (!avoidNodes.isMissingNode() && !avoidNodes.isArray()) errors.add("avoid는 배열이어야 해요.");
        if (avoidNodes.size() > MAX_AVOID) errors.add("avoid는 최대 " + MAX_AVOID + "개예요.");
        for (int i = 0; i < avoidNodes.size(); i++) {
            ParsedPick pick = pick(avoidNodes.get(i), "AVOID", "avoid[" + i + "]", seen, Set.of(), errors, warnings);
            if (pick != null) picks.add(pick);
        }

        if (!errors.isEmpty())
            return new Result(List.copyOf(errors), List.copyOf(warnings), null);
        return new Result(List.of(), List.copyOf(warnings), new ParsedPlan(schema, text(root, "analysisId"), mode,
                targetDate, analyzedAt, validUntil, onTime, decision, outlook, regime, riskLevel, modelName,
                text(model, "version"), promptVersion, List.copyOf(picks)));
    }

    /** 종목 한 줄을 읽어요. 종목 코드가 없거나, 마스터에 없거나, 이미 나온 종목이면 오류예요. */
    private ParsedPick pick(JsonNode node, String kind, String where, Set<String> seen, Set<String> top,
            List<String> errors, List<String> warnings) {
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
        return new ParsedPick(kind, stock.get(), priority, text(node, "reason"), top.contains(code), text(node, "confidence"));
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

    /** ChatGPT가 JSON을 코드 블록(```json ... ```)으로 감싸 보내도 읽을 수 있게 벗겨요. */
    public static String stripFence(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            text = firstLine < 0 ? "" : text.substring(firstLine + 1).strip();
            if (text.endsWith("```"))
                text = text.substring(0, text.length() - 3);
        }
        return text;
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
