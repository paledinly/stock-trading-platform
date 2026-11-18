/*
 * AI 후보 평가 응답(ai-candidate-v1) 검사기예요. 규칙은 docs/AI_CANDIDATE_EVALUATION.md 3절과 같아요.
 * 명단 40종목을 빠짐없이 한 번씩 평가했는지, A등급이 5개 이하인지, 값 범위가 맞는지, 미래 시각이 없는지 봐요.
 * 오류가 하나라도 있으면 저장하지 않아요. 경고는 저장하되 기록에 남겨요.
 */
package com.sunmo.stockplatform.aiplan.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 답안지 형식 검사관이에요. 문항(명단 종목)을 하나도 빼먹지 않았는지, 정해진 칸에 정해진 값만 썼는지 봐요. */
public class AiCandidateValidator {
    public static final String SCHEMA_VERSION = "ai-candidate-v1";
    static final int MAX_A = 5;
    static final Set<String> GRADES = Set.of("A", "B", "C", "AVOID");
    static final Set<String> OUTLOOKS = Set.of("BULLISH", "NEUTRAL", "BEARISH");
    static final Set<String> ENTRIES = Set.of("OPEN", "BREAKOUT_15M", "VWAP_RECLAIM", "NONE");
    static final Set<String> TIME_EXITS = Set.of("CLOSE", "NEXT_OPEN");
    static final Set<String> FLAGS = Set.of("DILUTION", "CB_BW", "AUDIT", "MANAGEMENT_CHANGE", "OVERHEATED",
            "THIN_LIQUIDITY", "LITIGATION", "OTHER");
    static final List<String> RUBRIC = List.of("catalyst", "flow", "trend", "overheat", "disclosureRisk");
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /** 종목 하나의 평가예요. rubric 순서는 RUBRIC과 같아요. */
    public record Grade(String stockCode, long stockId, String grade, int[] rubric, List<String> riskFlags, int confidence,
            String entryType, BigDecimal stopPct, BigDecimal targetPct, String timeExit, boolean usedExternalInfo) { }

    /** 검사를 통과한 응답이에요. */
    public record Parsed(LocalDate targetDate, String poolId, Instant analyzedAt, String modelName, String modelVersion,
            String promptVersion, String outlook, List<Grade> grades) { }

    /** 검사 결과예요. parsed는 오류가 없을 때만 있어요. */
    public record Result(List<String> errors, List<String> warnings, Parsed parsed) {
        public boolean valid() { return errors.isEmpty(); }
    }

    private final ObjectMapper json;

    public AiCandidateValidator(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 하는 일: 응답 JSON을 검사해요.
     * 받는 것: 원문, 저장된 명단(없으면 null: 아직 고정 전), 명단 종목(코드 → id), 지금 시각.
     */
    public Result validate(String raw, AiCandidatePoolService.Pool pool, Instant now) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        JsonNode root;
        try {
            root = json.readTree(strip(raw));
        } catch (Exception error) {
            return new Result(List.of("JSON을 읽지 못했어요: " + error.getMessage()), List.of(), null);
        }
        if (root == null || !root.isObject())
            return new Result(List.of("JSON 객체 하나여야 해요."), List.of(), null);
        if (!SCHEMA_VERSION.equals(text(root, "schemaVersion")))
            errors.add("schemaVersion은 " + SCHEMA_VERSION + "이어야 해요.");
        if (pool == null) {
            errors.add("저장된 후보 명단이 없어요. 카드를 대상 거래일 07:55 ~ 장 시작 사이에 연 뒤 다시 넣어 주세요.");
            return new Result(errors, warnings, null);
        }
        LocalDate targetDate = null;
        try {
            targetDate = LocalDate.parse(text(root, "targetDate"));
        } catch (Exception error) {
            errors.add("targetDate가 날짜(YYYY-MM-DD)가 아니에요.");
        }
        if (targetDate != null && !targetDate.equals(pool.targetDate()))
            errors.add("targetDate가 카드(" + pool.targetDate() + ")와 달라요.");
        String poolId = text(root, "poolId");
        if (!pool.poolId().equals(poolId))
            errors.add("poolId가 카드(" + pool.poolId() + ")와 달라요.");
        Instant analyzedAt = null;
        String analyzed = text(root, "analyzedAt");
        if (analyzed == null) {
            warnings.add("analyzedAt이 없어요. 저장 시각으로만 장 시작 전 여부를 판단해요.");
        } else {
            try {
                analyzedAt = OffsetDateTime.parse(analyzed).toInstant();
                if (analyzedAt.isAfter(now.plus(FUTURE_TOLERANCE)))
                    errors.add("analyzedAt이 지금보다 미래예요.");
            } catch (Exception error) {
                errors.add("analyzedAt은 시간대를 포함한 시각이어야 해요(예: 2026-11-19T08:30:00+09:00).");
            }
        }
        JsonNode model = root.path("model");
        String modelName = text(model, "name"), modelVersion = text(model, "version"), promptVersion = text(model, "promptVersion");
        if (modelName == null) errors.add("model.name이 없어요.");
        if (promptVersion == null) errors.add("model.promptVersion이 없어요.");
        if (modelVersion == null) warnings.add("model.version이 없어요.");
        String outlook = text(root.path("market"), "outlook");
        if (outlook == null || !OUTLOOKS.contains(outlook))
            errors.add("market.outlook은 BULLISH / NEUTRAL / BEARISH 중 하나여야 해요.");

        Map<String, Long> poolCodes = new LinkedHashMap<>();
        pool.stocks().forEach(stock -> poolCodes.put(stock.stockCode(), stock.stockId()));
        JsonNode evaluations = root.path("evaluations");
        List<Grade> grades = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (!evaluations.isArray()) {
            errors.add("evaluations 배열이 없어요.");
        } else {
            for (JsonNode node : evaluations) {
                String code = text(node, "stockCode");
                String where = "종목 " + code + ": ";
                if (code == null || !poolCodes.containsKey(code)) {
                    errors.add("명단에 없는 종목이에요: " + code);
                    continue;
                }
                if (!seen.add(code)) {
                    errors.add(where + "두 번 나왔어요.");
                    continue;
                }
                Grade grade = grade(node, code, poolCodes.get(code), where, errors, warnings, now);
                if (grade != null)
                    grades.add(grade);
            }
            List<String> missing = poolCodes.keySet().stream().filter(code -> !seen.contains(code)).toList();
            if (!missing.isEmpty())
                errors.add("평가하지 않은 명단 종목이 " + missing.size() + "개 있어요: " + String.join(", ", missing));
            long a = grades.stream().filter(g -> "A".equals(g.grade())).count();
            if (a > MAX_A)
                errors.add("A등급은 " + MAX_A + "개까지예요(지금 " + a + "개).");
        }
        Parsed parsed = errors.isEmpty() ? new Parsed(targetDate, poolId, analyzedAt, modelName, modelVersion, promptVersion,
                outlook, List.copyOf(grades)) : null;
        return new Result(List.copyOf(errors), List.copyOf(warnings), parsed);
    }

    private Grade grade(JsonNode node, String code, long stockId, String where, List<String> errors, List<String> warnings,
            Instant now) {
        int before = errors.size();
        String grade = text(node, "grade");
        if (grade == null || !GRADES.contains(grade))
            errors.add(where + "grade는 A / B / C / AVOID 중 하나여야 해요.");
        int[] rubric = new int[RUBRIC.size()];
        for (int i = 0; i < RUBRIC.size(); i++) {
            JsonNode value = node.path("rubric").path(RUBRIC.get(i));
            if (!value.isInt() || value.asInt() < 0 || value.asInt() > 2)
                errors.add(where + "rubric." + RUBRIC.get(i) + "는 0~2 정수여야 해요.");
            else
                rubric[i] = value.asInt();
        }
        List<String> flags = new ArrayList<>();
        for (JsonNode flag : node.path("riskFlags")) {
            if (FLAGS.contains(flag.asText()))
                flags.add(flag.asText());
            else
                warnings.add(where + "모르는 riskFlags 값은 OTHER로 저장해요: " + flag.asText());
        }
        if (flags.size() != node.path("riskFlags").size() && !flags.contains("OTHER"))
            flags.add("OTHER");
        JsonNode reasons = node.path("reasons");
        if (!reasons.isArray() || reasons.isEmpty() || reasons.size() > 3)
            errors.add(where + "reasons는 1~3개 문장이어야 해요.");
        JsonNode confidence = node.path("confidence");
        if (!confidence.isInt() || confidence.asInt() < 0 || confidence.asInt() > 100)
            errors.add(where + "confidence는 0~100 정수여야 해요.");
        boolean tradable = "A".equals(grade) || "B".equals(grade);
        String entry = text(node.path("entry"), "type");
        if (entry != null && !ENTRIES.contains(entry))
            errors.add(where + "entry.type은 OPEN / BREAKOUT_15M / VWAP_RECLAIM / NONE 중 하나여야 해요.");
        if (tradable && entry == null)
            errors.add(where + "A·B 등급은 entry.type이 있어야 해요.");
        BigDecimal stop = null, target = null;
        String timeExit = null;
        JsonNode exit = node.path("exit");
        if (tradable) {
            if (!exit.isObject()) {
                errors.add(where + "A·B 등급은 exit가 있어야 해요.");
            } else {
                stop = number(exit.path("stopPct"));
                if (stop == null || stop.compareTo(new BigDecimal("0.8")) < 0 || stop.compareTo(new BigDecimal("8")) > 0)
                    errors.add(where + "exit.stopPct는 0.8~8이어야 해요.");
                JsonNode targetNode = exit.path("targetPct");
                if (!targetNode.isMissingNode() && !targetNode.isNull()) {
                    target = number(targetNode);
                    if (target == null || target.compareTo(BigDecimal.ONE) < 0 || target.compareTo(new BigDecimal("20")) > 0)
                        errors.add(where + "exit.targetPct는 1~20 또는 null이어야 해요.");
                }
                timeExit = text(exit, "timeExit");
                if (timeExit == null || !TIME_EXITS.contains(timeExit))
                    errors.add(where + "exit.timeExit는 CLOSE / NEXT_OPEN 중 하나여야 해요.");
            }
        } else if (entry != null && !"NONE".equals(entry)) {
            warnings.add(where + grade + " 등급인데 entry.type이 " + entry + "예요. 기록만 해요.");
        }
        boolean external = node.path("usedExternalInfo").asBoolean(false);
        for (JsonNode source : node.path("sources")) {
            external = true;
            String published = text(source, "publishedAt");
            if (published == null) {
                warnings.add(where + "출처에 publishedAt이 없어요.");
                continue;
            }
            try {
                if (OffsetDateTime.parse(published).toInstant().isAfter(now.plus(FUTURE_TOLERANCE)))
                    errors.add(where + "출처 시각이 지금보다 미래예요.");
            } catch (Exception error) {
                warnings.add(where + "출처 publishedAt을 읽지 못했어요: " + published);
            }
        }
        if (errors.size() > before)
            return null;
        return new Grade(code, stockId, grade, rubric, List.copyOf(flags), confidence.asInt(), entry, stop, target, timeExit,
                external);
    }

    /** ChatGPT가 ```json … ``` 으로 감싸 보내도 벗겨요. */
    static String strip(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first > 0 && last > first)
                text = text.substring(first + 1, last).strip();
        }
        return text;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().strip() : null;
    }

    private static BigDecimal number(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }
}
