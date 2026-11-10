/*
 * AI 장전 분석 계획을 저장하고 조회하는 파일이에요(ADR-012, 1단계: 기록과 자동 채점).
 * 붙여넣은 JSON은 검사(AiPlanValidator)를 통과하면 원본 그대로 저장하고, 같은 날짜로 다시 넣으면 새 판(revision)으로 쌓아요.
 * 채점은 AiPlanOutcomeService가 하고, 이 파일은 조회 때 채점 결과를 함께 붙여 돌려줘요.
 * AiPlanController가 불러요. 실시간 구독, 추천 점수, 가상 진입에는 영향을 주지 않아요.
 */
package com.sunmo.stockplatform.aiplan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.aiplan.application.AiPlanValidator.ParsedPick;
import com.sunmo.stockplatform.aiplan.application.AiPlanValidator.ParsedPlan;
import com.sunmo.stockplatform.aiplan.application.AiPlanValidator.StockRef;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** AI 장전 계획 보관함이에요. 들어온 답안지를 고치지 않고 날짜·판 번호를 붙여 차곡차곡 쌓아 두는 서류철이에요. */
@Service
public class AiPlanService {
    /** 저장 결과예요. 검사에 실패하면 plan이 없고 errors가 있어요. */
    public record SubmitResult(boolean saved, List<String> errors, List<String> warnings, PlanSummary plan) { }

    /** 계획 목록의 한 줄이에요. scored는 성과 집계에 쓰이는 판(장 시작 전 마지막 판)인지예요. */
    public record PlanSummary(long id, LocalDate targetDate, int revision, Instant analyzedAt, Instant submittedAt,
            boolean onTime, boolean scored, String tradeDecision, String marketOutlook, String modelName,
            String modelVersion, String promptVersion, int pickCount, int avoidCount, List<String> warnings) { }

    /** 종목 하나와 그 종목의 채점 결과예요(아직 채점 전이면 outcome이 null). */
    public record PickView(long id, String kind, String stockCode, String stockName, Integer priority, String reason,
            Outcome outcome) { }

    /** 장 마감 뒤 실제 시장 데이터로 잰 값이에요. 퍼센트 단위(2는 2%)예요. AI가 적은 값은 들어 있지 않아요. */
    public record Outcome(LocalDate tradeDate, String status, BigDecimal prevClose, BigDecimal open, BigDecimal high,
            BigDecimal low, BigDecimal close, BigDecimal gapPct, BigDecimal openClosePct, BigDecimal highPct,
            BigDecimal lowPct, BigDecimal closeClosePct, BigDecimal netOpenClosePct, BigDecimal universeOpenClosePct,
            BigDecimal universeGapPct, Integer universeCount, BigDecimal excessOpenClosePct, Instant evaluatedAt) { }

    /** 계획 하나의 상세예요. rawJson은 받은 그대로의 원본이에요. */
    public record PlanDetail(PlanSummary plan, String rawJson, List<PickView> picks, List<PlanSummary> revisions) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ClosingTradingCalendar calendar;

    /** 받는 것: DB 접근 도구, JSON 해석기, 거래일 달력. */
    public AiPlanService(JdbcTemplate jdbc, ObjectMapper json, ClosingTradingCalendar calendar) {
        this.jdbc = jdbc;
        this.json = json;
        this.calendar = calendar;
    }

    /** 하는 일: 저장하지 않고 검사만 해요. 받는 것: 원본 JSON. 돌려주는 것: 오류·경고. */
    public SubmitResult check(String raw) {
        AiPlanValidator.Result result = validator().validate(raw);
        return new SubmitResult(false, result.errors(), result.warnings(), null);
    }

    /**
     * 하는 일: 검사를 통과하면 원본과 종목 목록을 저장해요. 같은 날짜가 이미 있으면 다음 판 번호로 저장해요.
     * 받는 것: 원본 JSON. 돌려주는 것: 저장 여부, 오류·경고, 저장된 계획 요약.
     */
    @Transactional
    public SubmitResult submit(String raw) {
        AiPlanValidator.Result result = validator().validate(raw);
        if (!result.valid())
            return new SubmitResult(false, result.errors(), result.warnings(), null);
        ParsedPlan plan = result.plan();
        int revision = jdbc.queryForObject("select coalesce(max(revision), 0) + 1 from ai_premarket_plan where target_date = ?",
                Integer.class, Date.valueOf(plan.targetDate()));
        String warnings = result.warnings().isEmpty() ? null : String.join("\n", result.warnings());
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into ai_premarket_plan(target_date, revision, schema_version, analyzed_at, valid_until, submitted_at,
                      on_time, trade_decision, market_outlook, model_name, model_version, prompt_version, raw_json, warnings)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new String[] { "id" });
            ps.setDate(1, Date.valueOf(plan.targetDate()));
            ps.setInt(2, revision);
            ps.setString(3, AiPlanValidator.SCHEMA_VERSION);
            ps.setTimestamp(4, Timestamp.from(plan.analyzedAt()));
            ps.setTimestamp(5, Timestamp.from(plan.validUntil()));
            ps.setTimestamp(6, Timestamp.from(calendar.now()));
            ps.setBoolean(7, plan.onTime());
            ps.setString(8, plan.tradeDecision());
            ps.setString(9, plan.outlook());
            ps.setString(10, plan.modelName());
            ps.setString(11, plan.modelVersion());
            ps.setString(12, plan.promptVersion());
            ps.setString(13, raw);
            ps.setString(14, warnings);
            return ps;
        }, key);
        long planId = key.getKey().longValue();
        for (ParsedPick pick : plan.picks())
            jdbc.update("""
                    insert into ai_premarket_pick(plan_id, kind, stock_id, stock_code, stock_name, priority, reason)
                    values (?, ?, ?, ?, ?, ?, ?)""", planId, pick.kind(), pick.stock().id(), pick.stock().code(),
                    pick.stock().name(), pick.priority(), pick.reason());
        return new SubmitResult(true, List.of(), result.warnings(), summary(planId));
    }

    /** 하는 일: 기간 안 날짜별 최신 판 목록을 최근 날짜부터 돌려줘요. */
    public List<PlanSummary> list(LocalDate from, LocalDate to) {
        return jdbc.query(SUMMARY_SQL + """
                where p.target_date between ? and ?
                  and p.revision = (select max(q.revision) from ai_premarket_plan q where q.target_date = p.target_date)
                order by p.target_date desc""", this::summaryRow, Date.valueOf(from), Date.valueOf(to));
    }

    /** 하는 일: 계획 하나의 원본, 종목별 채점 결과, 같은 날짜의 모든 판을 돌려줘요. 없으면 404예요. */
    public PlanDetail detail(long id) {
        PlanSummary plan = summary(id);
        String raw = jdbc.queryForObject("select raw_json from ai_premarket_plan where id = ?", String.class, id);
        List<PickView> picks = jdbc.query("""
                select k.id, k.kind, k.stock_code, k.stock_name, k.priority, k.reason, o.*
                from ai_premarket_pick k left join ai_premarket_outcome o on o.pick_id = k.id
                where k.plan_id = ? order by k.kind desc, k.priority nulls last, k.id""", (rs, i) -> new PickView(
                rs.getLong("id"), rs.getString("kind"), rs.getString("stock_code"), rs.getString("stock_name"),
                (Integer) rs.getObject("priority"), rs.getString("reason"), outcome(rs)), id);
        List<PlanSummary> revisions = jdbc.query(SUMMARY_SQL + " where p.target_date = ? order by p.revision desc",
                this::summaryRow, Date.valueOf(plan.targetDate()));
        return new PlanDetail(plan, raw, picks, revisions);
    }

    private PlanSummary summary(long id) {
        List<PlanSummary> rows = jdbc.query(SUMMARY_SQL + " where p.id = ?", this::summaryRow, id);
        if (rows.isEmpty())
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.NOT_FOUND, "AI 장전 계획이 없어요: " + id);
        return rows.get(0);
    }

    // 성과 집계에 쓰는 판: 같은 날짜에서 장 시작 전에 들어온 마지막 판이에요.
    private static final String SUMMARY_SQL = """
            select p.*,
              (select count(*) from ai_premarket_pick k where k.plan_id = p.id and k.kind = 'PICK') picks,
              (select count(*) from ai_premarket_pick k where k.plan_id = p.id and k.kind = 'AVOID') avoids,
              (p.on_time and p.revision = (select max(q.revision) from ai_premarket_plan q
                 where q.target_date = p.target_date and q.on_time)) scored
            from ai_premarket_plan p
            """;

    private PlanSummary summaryRow(ResultSet rs, int row) throws SQLException {
        String warnings = rs.getString("warnings");
        return new PlanSummary(rs.getLong("id"), rs.getDate("target_date").toLocalDate(), rs.getInt("revision"),
                rs.getTimestamp("analyzed_at").toInstant(), rs.getTimestamp("submitted_at").toInstant(),
                rs.getBoolean("on_time"), rs.getBoolean("scored"), rs.getString("trade_decision"),
                rs.getString("market_outlook"), rs.getString("model_name"), rs.getString("model_version"),
                rs.getString("prompt_version"), rs.getInt("picks"), rs.getInt("avoids"),
                warnings == null ? List.of() : List.of(warnings.split("\n")));
    }

    private static Outcome outcome(ResultSet rs) throws SQLException {
        if (rs.getString("status") == null)
            return null;
        Integer count = (Integer) rs.getObject("universe_count");
        return new Outcome(rs.getDate("trade_date").toLocalDate(), rs.getString("status"), rs.getBigDecimal("prev_close"),
                rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"), rs.getBigDecimal("low_price"),
                rs.getBigDecimal("close_price"), rs.getBigDecimal("gap_pct"), rs.getBigDecimal("open_close_pct"),
                rs.getBigDecimal("high_pct"), rs.getBigDecimal("low_pct"), rs.getBigDecimal("close_close_pct"),
                rs.getBigDecimal("net_open_close_pct"), rs.getBigDecimal("universe_open_close_pct"),
                rs.getBigDecimal("universe_gap_pct"), count, rs.getBigDecimal("excess_open_close_pct"),
                rs.getTimestamp("evaluated_at").toInstant());
    }

    /** 검사기를 만들어요. 종목은 종목 마스터(stock)에서 코드로 찾아요. */
    private AiPlanValidator validator() {
        return new AiPlanValidator(json, calendar, code -> {
            List<StockRef> rows = jdbc.query("select id, stock_code, stock_name from stock where stock_code = ?",
                    (rs, i) -> new StockRef(rs.getLong(1), rs.getString(2), rs.getString(3)), code);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        });
    }
}
