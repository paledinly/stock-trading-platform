/*
 * AI 계획 JSON의 진입 조건(picks[].entryStrategy.conditions[]) 하나를 프로그램이 계산할 수 있는 형태로 읽은 값이에요(규칙 A).
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
     * 하는 일: entryStrategy.conditions 배열을 읽어요.
     * 받는 것: picks[] 항목 하나(JSON). 돌려주는 것: 조건 목록과 지원 여부. 지원하지 않는 항목은 이유를 남겨요.
     */
    public static Parsed parse(JsonNode pick) {
        List<AiCondition> conditions = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        JsonNode nodes = pick == null ? null : pick.path("entryStrategy").path("conditions");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty())
            return new Parsed(List.of(), false, List.of("조건 없음"));
        for (JsonNode node : nodes) {
            String metric = text(node, "metric"), operator = text(node, "operator"), reference = text(node, "reference");
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
