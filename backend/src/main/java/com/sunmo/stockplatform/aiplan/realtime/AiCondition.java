/*
 * AI 계획 JSON의 진입 조건(v2: picks[].entryStrategy.conditions[], v4: 확인형 진입 전략의 entryRules.conditions[]) 하나를
 * 프로그램이 계산할 수 있는 형태로 읽은 값이에요(규칙 A).
 * 지원하는 지표·기준만 받아들이고, 하나라도 모르는 지표·연산자가 있으면 그 종목의 규칙 A는 UNSUPPORTED가 돼요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * metric: 지표, operator: 비교, value: 숫자 기준(없으면 null), reference: 가격 기준 이름(없으면 null), unit: 단위.
 */
public record AiCondition(String metric, String operator, BigDecimal value, String reference, String unit) {
    static final Set<String> METRICS = Set.of("RVOL_5M", "PRICE_VS_VWAP", "CLOSE_5M", "TURNOVER_5M", "PRICE_CHANGE_FROM_OPEN");
    static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=");
    static final Set<String> REFERENCES = Set.of("OPENING_RANGE_5M_HIGH", "OPENING_RANGE_15M_HIGH", "PREVIOUS_HIGH",
            "PREVIOUS_CLOSE", "VWAP");

    /** 읽은 조건 목록과, 전부 지원되는지 여부예요. 조건이 하나도 없으면 지원 안 됨이에요. */
    public record Parsed(List<AiCondition> conditions, boolean supported, List<String> unsupported) { }

    /**
     * 하는 일: entryStrategy.conditions 배열(v2)이나 strategies[]의 CONFIRMED_ENTRY 조건(v4)을 읽어요.
     * 받는 것: picks[] 항목 하나(JSON). 돌려주는 것: 조건 목록과 지원 여부. 지원하지 않는 항목은 이유를 남겨요.
     */
    public static Parsed parse(JsonNode pick) {
        if (pick != null && pick.path("strategies").isArray()) return parseStrategies(pick.path("strategies"));
        JsonNode nodes = pick == null ? null : pick.path("entryStrategy").path("conditions");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
            // v3는 진입 조건 없이 전략 유형(strategy.strategyType)만 적어요. 수치 기준은 프로그램이 정해요.
            String strategy = pick == null ? null : text(pick.path("strategy"), "strategyType");
            return new Parsed(List.of(), false, List.of(strategy != null ? "진입 조건 없음(v3 전략 " + strategy + ")" : "조건 없음"));
        }
        return conditions(nodes, List.of());
    }

    /**
     * v4는 종목마다 전략이 여러 개예요. 규칙 A는 5분봉으로 계산하는 확인형 진입(CONFIRMED_ENTRY) 하나만 따라가요.
     * 조기 진입(EARLY_MOMENTUM)은 1분 지표·호가 스프레드가 필요해 아직 계산하지 않아요.
     * required가 false인 조건은 참고용이라 빼요. 기준이 PROGRAM_CONFIG면 프로그램 기준(규칙 P 값)을 써요.
     */
    private static Parsed parseStrategies(JsonNode strategies) {
        JsonNode confirmed = null;
        for (JsonNode strategy : strategies)
            if ("CONFIRMED_ENTRY".equals(text(strategy, "strategyType"))) { confirmed = strategy; break; }
        if (confirmed == null) return new Parsed(List.of(), false, List.of("확인형 진입(CONFIRMED_ENTRY) 전략 없음"));
        JsonNode rules = confirmed.path("entryRules");
        JsonNode nodes = rules.path("conditions");
        if (!nodes.isArray() || nodes.isEmpty()) return new Parsed(List.of(), false, List.of("CONFIRMED_ENTRY 조건 없음"));
        String join = text(rules, "operator");
        List<String> unsupported = join != null && !"AND".equals(join) ? List.of("조건 결합 " + join) : List.of();
        List<JsonNode> required = new ArrayList<>();
        nodes.forEach(node -> { if (!node.path("required").isBoolean() || node.path("required").asBoolean()) required.add(node); });
        if (required.isEmpty()) return new Parsed(List.of(), false, List.of("CONFIRMED_ENTRY 필수 조건 없음"));
        return conditions(required, unsupported);
    }

    private static Parsed conditions(Iterable<JsonNode> nodes, List<String> initial) {
        List<AiCondition> conditions = new ArrayList<>();
        List<String> unsupported = new ArrayList<>(initial);
        for (JsonNode node : nodes) {
            String metric = text(node, "metric"), operator = text(node, "operator"), reference = text(node, "reference");
            // v4 표기: 기준값을 프로그램이 정하면 reference가 PROGRAM_CONFIG예요(AI가 PROGRAM_CONFIG.CONFIRMED_ENTRY.RVOL_5M_MIN처럼
            // 뒤에 이름을 붙이기도 해요). 현재가 > VWAP은 PRICE_VS_VWAP과 같아요.
            if (reference != null && (reference.equals("PROGRAM_CONFIG") || reference.startsWith("PROGRAM_CONFIG."))) reference = null;
            if ("PRICE".equals(metric) && "VWAP".equals(reference) && !node.path("value").isNumber()) {
                metric = "PRICE_VS_VWAP";
                reference = null;
            }
            BigDecimal value = node.path("value").isNumber() ? node.path("value").decimalValue() : null;
            if (!METRICS.contains(metric)) { unsupported.add("지표 " + metric); continue; }
            if (!OPERATORS.contains(operator)) { unsupported.add(metric + " 연산자 " + operator); continue; }
            if (reference != null && !REFERENCES.contains(reference)) { unsupported.add(metric + " 기준 " + reference); continue; }
            if (reference != null && !"CLOSE_5M".equals(metric)) { unsupported.add(metric + " 기준 " + reference); continue; }
            conditions.add(new AiCondition(metric, operator, value, reference, text(node, "unit")));
        }
        return new Parsed(List.copyOf(conditions), unsupported.isEmpty(), List.copyOf(unsupported));
    }

    /** 하는 일: left operator right를 계산해요. 어느 쪽이든 없으면 null(판단 불가)이에요. */
    Boolean compare(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return null;
        int c = left.compareTo(right);
        return switch (operator) {
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            default -> c <= 0;
        };
    }

    /** 화면·기록용 한 줄 표현이에요. 예: "CLOSE_5M > OPENING_RANGE_15M_HIGH". */
    String label() {
        return metric + " " + operator + " " + (reference != null ? reference : value != null ? value.toPlainString() : "P기준");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
