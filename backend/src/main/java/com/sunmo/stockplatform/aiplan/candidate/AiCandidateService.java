/*
 * AI 후보 평가 응답을 검사해 원본 그대로 저장하고 조회하는 파일이에요(ADR-014).
 * 같은 날짜로 다시 넣으면 새 판(revision)으로 쌓고, 집계는 장 시작 전에 저장된 "첫" 판만 해요(다시 물어 결과 고르기 방지).
 * 채점은 AiCandidateOutcomeService가 해요. 실시간 구독, 추천 점수, 가상 진입에는 영향을 주지 않아요.
 */
package com.sunmo.stockplatform.aiplan.candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 평가 답안지 보관함이에요. 들어온 답안지를 고치지 않고 판 번호를 붙여 쌓고, 첫 답안지만 채점 대상으로 표시해요. */
@Service
public class AiCandidateService {
    /** 저장 결과예요. 검사에 실패하면 evaluation이 없고 errors가 있어요. */
    public record SubmitResult(boolean saved, List<String> errors, List<String> warnings, EvalSummary evaluation) { }

    /** 평가 한 판의 요약이에요. counted는 집계 판(장 시작 전 첫 판)인지예요. */
    public record EvalSummary(long id, LocalDate targetDate, int revision, String poolId, Instant analyzedAt,
            Instant submittedAt, boolean onTime, boolean counted, String modelName, String modelVersion,
            String promptVersion, String marketOutlook, int gradeA, int gradeB, int gradeC, int gradeAvoid,
            List<String> warnings) { }

    /** 종목 하나의 등급과 채점 결과(채점 전이면 status null)예요. 퍼센트 단위예요. */
    public record GradeView(int poolRank, String stockCode, String stockName, String grade, int catalyst, int flow, int trend,
            int overheat, int disclosureRisk, String riskFlags, int confidence, String entryType, BigDecimal stopPct,
            BigDecimal targetPct, String timeExit, boolean usedExternalInfo, String status, BigDecimal openClosePct,
            BigDecimal netOpenClosePct) { }

    /** 평가 하나의 상세예요. rawJson은 받은 그대로의 원본이에요. */
    public record EvalDetail(EvalSummary evaluation, String rawJson, List<GradeView> grades, List<EvalSummary> revisions) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ClosingTradingCalendar calendar;
    private final AiCandidatePoolService pools;

    public AiCandidateService(JdbcTemplate jdbc, ObjectMapper json, ClosingTradingCalendar calendar,
            AiCandidatePoolService pools) {
        this.jdbc = jdbc;
        this.json = json;
        this.calendar = calendar;
        this.pools = pools;
    }

    /** 하는 일: 저장하지 않고 검사만 해요. */
    public SubmitResult check(String raw) {
        AiCandidateValidator.Result result = validate(raw);
        return new SubmitResult(false, result.errors(), result.warnings(), null);
    }

    /** 하는 일: 검사를 통과하면 원본과 종목별 등급을 저장해요. 같은 날짜가 있으면 다음 판 번호예요. */
    @Transactional
    public SubmitResult submit(String raw) {
        AiCandidateValidator.Result result = validate(raw);
        if (!result.valid())
            return new SubmitResult(false, result.errors(), result.warnings(), null);
        AiCandidateValidator.Parsed parsed = result.parsed();
        Instant now = calendar.now();
        boolean onTime = now.isBefore(calendar.open(parsed.targetDate()));
        int revision = jdbc.queryForObject("select coalesce(max(revision), 0) + 1 from ai_candidate_eval where target_date = ?",
                Integer.class, Date.valueOf(parsed.targetDate()));
        String warnings = result.warnings().isEmpty() ? null : String.join("\n", result.warnings());
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into ai_candidate_eval(target_date, revision, pool_id, schema_version, analyzed_at, submitted_at,
                      on_time, model_name, model_version, prompt_version, market_outlook, raw_json, warnings)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new String[] { "id" });
            ps.setDate(1, Date.valueOf(parsed.targetDate()));
            ps.setInt(2, revision);
            ps.setString(3, parsed.poolId());
            ps.setString(4, AiCandidateValidator.SCHEMA_VERSION);
            ps.setTimestamp(5, parsed.analyzedAt() == null ? null : Timestamp.from(parsed.analyzedAt()));
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setBoolean(7, onTime);
            ps.setString(8, parsed.modelName());
            ps.setString(9, parsed.modelVersion());
            ps.setString(10, parsed.promptVersion());
            ps.setString(11, parsed.outlook());
            ps.setString(12, raw);
            ps.setString(13, warnings);
            return ps;
        }, key);
        long id = key.getKey().longValue();
        for (AiCandidateValidator.Grade g : parsed.grades())
            jdbc.update("""
                    insert into ai_candidate_grade(eval_id, stock_code, stock_id, grade, catalyst, flow, trend, overheat,
                      disclosure_risk, risk_flags, confidence, entry_type, stop_pct, target_pct, time_exit, used_external_info)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, g.stockCode(), g.stockId(), g.grade(),
                    g.rubric()[0], g.rubric()[1], g.rubric()[2], g.rubric()[3], g.rubric()[4],
                    g.riskFlags().isEmpty() ? null : String.join(",", g.riskFlags()), g.confidence(), g.entryType(),
                    g.stopPct(), g.targetPct(), g.timeExit(), g.usedExternalInfo());
        return new SubmitResult(true, List.of(), result.warnings(), summary(id));
    }

    /** 하는 일: 기간 안의 모든 판을 최근 날짜·판 순서로 돌려줘요. */
    public List<EvalSummary> list(LocalDate from, LocalDate to) {
        return jdbc.query(SUMMARY_SQL + " where e.target_date between ? and ? order by e.target_date desc, e.revision",
                this::summaryRow, Date.valueOf(from), Date.valueOf(to));
    }

    /** 하는 일: 평가 하나의 원본, 명단 순서대로 종목별 등급·채점 결과, 같은 날짜의 모든 판이에요. 없으면 404예요. */
    public EvalDetail detail(long id) {
        EvalSummary evaluation = summary(id);
        String raw = jdbc.queryForObject("select raw_json from ai_candidate_eval where id = ?", String.class, id);
        List<GradeView> grades = jdbc.query("""
                select p.pool_rank, p.stock_code, p.stock_name, g.grade, g.catalyst, g.flow, g.trend, g.overheat,
                  g.disclosure_risk, g.risk_flags, g.confidence, g.entry_type, g.stop_pct, g.target_pct, g.time_exit,
                  g.used_external_info, o.status, o.open_close_pct, o.net_open_close_pct
                from ai_candidate_grade g
                  join ai_candidate_eval e on e.id = g.eval_id
                  join ai_candidate_pool_stock p on p.target_date = e.target_date and p.stock_code = g.stock_code
                  left join ai_candidate_outcome o on o.target_date = e.target_date and o.stock_id = g.stock_id
                where g.eval_id = ? order by p.pool_rank""", (rs, i) -> new GradeView(rs.getInt(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                rs.getString(10), rs.getInt(11), rs.getString(12), rs.getBigDecimal(13), rs.getBigDecimal(14),
                rs.getString(15), rs.getBoolean(16), rs.getString(17), rs.getBigDecimal(18), rs.getBigDecimal(19)), id);
        List<EvalSummary> revisions = jdbc.query(SUMMARY_SQL + " where e.target_date = ? order by e.revision",
                this::summaryRow, Date.valueOf(evaluation.targetDate()));
        return new EvalDetail(evaluation, raw, grades, revisions);
    }

    private EvalSummary summary(long id) {
        List<EvalSummary> rows = jdbc.query(SUMMARY_SQL + " where e.id = ?", this::summaryRow, id);
        if (rows.isEmpty())
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.NOT_FOUND, "AI 후보 평가가 없어요: " + id);
        return rows.get(0);
    }

    // 집계 판: 같은 날짜에서 장 시작 전에 저장된 첫 판이에요.
    static final String COUNTED = "e.on_time and e.revision = (select min(q.revision) from ai_candidate_eval q "
            + "where q.target_date = e.target_date and q.on_time)";

    private static final String SUMMARY_SQL = """
            select e.*,
              (select count(*) from ai_candidate_grade g where g.eval_id = e.id and g.grade = 'A') ga,
              (select count(*) from ai_candidate_grade g where g.eval_id = e.id and g.grade = 'B') gb,
              (select count(*) from ai_candidate_grade g where g.eval_id = e.id and g.grade = 'C') gc,
              (select count(*) from ai_candidate_grade g where g.eval_id = e.id and g.grade = 'AVOID') gavoid,
              (""" + COUNTED + """
            ) counted
            from ai_candidate_eval e
            """;

    private EvalSummary summaryRow(ResultSet rs, int row) throws SQLException {
        String warnings = rs.getString("warnings");
        Timestamp analyzedAt = rs.getTimestamp("analyzed_at");
        return new EvalSummary(rs.getLong("id"), rs.getDate("target_date").toLocalDate(), rs.getInt("revision"),
                rs.getString("pool_id"), analyzedAt == null ? null : analyzedAt.toInstant(),
                rs.getTimestamp("submitted_at").toInstant(), rs.getBoolean("on_time"), rs.getBoolean("counted"),
                rs.getString("model_name"), rs.getString("model_version"), rs.getString("prompt_version"),
                rs.getString("market_outlook"), rs.getInt("ga"), rs.getInt("gb"), rs.getInt("gc"), rs.getInt("gavoid"),
                warnings == null ? List.of() : List.of(warnings.split("\n")));
    }

    /** 응답이 가리키는 날짜의 저장된 명단으로 검사해요. 날짜를 못 읽으면 다음 대상 거래일 명단으로 봐요. */
    private AiCandidateValidator.Result validate(String raw) {
        LocalDate target = pools.targetDate();
        try {
            String date = json.readTree(AiCandidateValidator.strip(raw)).path("targetDate").asText(null);
            if (date != null)
                target = LocalDate.parse(date);
        } catch (Exception ignored) {
            // 형식 오류는 검사기가 알려 줘요.
        }
        return new AiCandidateValidator(json).validate(raw, pools.stored(target), calendar.now());
    }
}
