/*
 * 마감 추천 평가가 끝난 뒤 실제로 관측한 체결가로 가상 진입(paper entry, 실제 주문 없이 장부에만 산 것으로 기록)을 잡아요.
 * 평가 완료 이후 발생·수신된 신선한 체결(원천 발생→진입 10초 이내)만 쓰고, 진입 마감(기본 15:20) 전에 못 잡으면 실패로 남겨요.
 * ClosingRecommendationService, ClosingCandidateObservationService, OvernightPerformanceService가 써요.
 */
package com.sunmo.stockplatform.closing.application;

import com.sunmo.stockplatform.closing.config.ClosingRecommendationProperties;
import com.sunmo.stockplatform.closing.domain.ClosingRecommendationRun;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observed paper prices only: restart cannot manufacture a fill during the outage.
 * 가상 진입 대기 행을 만들고, 실시간 체결을 받아 시한 안에 진입가를 확정하는 서비스예요.
 * 대기 주문표(PENDING)를 들고 있다가, 평가가 끝난 뒤 막 들어온 따끈한 가격표를 본 경우에만
 * "이 가격에 샀다고 치자"라고 적는 장부 담당자예요. 서버가 꺼져 있던 동안의 가격을 나중에 지어내지 않아요.
 */
@Service
public class ClosingPaperEntryService {
    /** 가상 진입 결과예요. 진입 시각, 진입 가격, 상태(ENTERED, PENDING, 실패 사유 등)를 담아요. */
    public record Entry(Instant at, BigDecimal price, String status) {}
    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final ClosingRecommendationProperties policy;
    // 종목별로 가장 늦게 수신된 체결 1건만 메모리에 들고 있어요(입력 마감~진입 마감 구간에 받은 것만).
    private final Map<String, ObservedMarketTick> latest = new ConcurrentHashMap<>();
    /**
     * DB 접근, 거래일 달력, 마감 정책(입력 마감·진입 마감 시각)을 받아요.
     */
    public ClosingPaperEntryService(JdbcTemplate jdbc, ClosingTradingCalendar calendar, ClosingRecommendationProperties policy) {
        this.jdbc = jdbc; this.calendar = calendar; this.policy = policy;
    }
    /**
     * 이 run이 "평가 완료 후 관측 체결로 진입하는" v9 계열 진입 모델을 쓰는지 알려 줘요.
     * 받는 것: 추천 run. 돌려주는 것: v9 또는 v9.1 전략이면 true.
     */
    public static boolean usesObservedEntry(ClosingRecommendationRun run) {
        return "closing-recommend-v9-1515".equals(run.getStrategyVersion())
                || "closing-recommend-v9_1-1515".equals(run.getStrategyVersion());
    }
    /**
     * 추천 run의 종목들에 대해 가상 진입 대기 행을 만들어요.
     * 받는 것: 완료된 추천 run, 진입을 추적할 종목 코드들.
     * 돌려주는 것: 없음. 평가가 진입 마감 전에 끝났으면 PENDING, 이미 늦었으면 EVALUATION_DEADLINE_MISSED로 저장해요.
     */
    public void prepare(ClosingRecommendationRun run, Collection<String> symbols) {
        Instant deadline = MarketSessionHours.at(run.getRecommendationDate(), policy.entryDeadline());
        String status = run.getCompletedAt().isBefore(deadline) ? "PENDING" : "EVALUATION_DEADLINE_MISSED";
        for (String symbol : new TreeSet<>(symbols)) jdbc.update(
                "insert into closing_paper_entry(run_id,stock_code,completed_at,deadline,status) values (?,?,?,?,?)",
                run.getId(), symbol, Timestamp.from(run.getCompletedAt()), Timestamp.from(deadline), status);
    }
    /**
     * 시장 체결 이벤트를 받아 종목별 최신 체결로 기억해요. 트랜잭션(여러 DB 작업을 한 묶음으로 처리하는 단위)이 끝난 뒤 호출돼요.
     * 받는 것: 수신 시각이 붙은 관측 체결. 돌려주는 것: 없음.
     * 입력 마감(15:15) 전이나 진입 마감(15:20) 이후에 수신된 체결은 무시해요.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void receive(ObservedMarketTick event) {
        var local = MarketSessionHours.regularTime(event.receivedAt());
        if (local.isBefore(policy.featureFreezeAt()) || !local.isBefore(policy.entryDeadline())) return;
        latest.compute(event.tick().stockCode(), (code, old) -> old == null
                || event.receivedAt().isAfter(old.receivedAt()) ? event : old);
    }
    /**
     * 체결 1건을 가상 진입 가격으로 쓸 수 있는지 판정해요.
     * 받는 것: 관측 체결, 평가 완료 시각, 현재 시각, 진입 마감 시각.
     * 돌려주는 것: 데이터 손실 없음, 마감 전, 양수 가격, 거래정지 아님, 평가 완료 뒤 발생, 발생≤수신≤현재,
     * 원천 발생 후 10초 이내라는 조건을 모두 만족하면 true.
     */
    static boolean executable(ObservedMarketTick event, Instant completed, Instant now, Instant deadline) {
        if (event == null || event.dataLoss() || !now.isBefore(deadline)) return false;
        var tick = event.tick();
        return tick.price() != null && tick.price().signum() > 0 && !tick.tradingHalted()
                && tick.occurredAt().isAfter(completed) && !tick.occurredAt().isAfter(event.receivedAt())
                && !event.receivedAt().isAfter(now) && Duration.between(tick.occurredAt(), now).compareTo(Duration.ofSeconds(10)) <= 0;
    }
    /**
     * 스케줄러(정해진 간격으로 자동 실행하는 장치)가 기본 5초마다 불러 대기 중인 가상 진입을 처리해요.
     * 받는 것: 없음. 돌려주는 것: 없음.
     * 시한 지난 대기는 NO_FRESH_TICK_BEFORE_DEADLINE으로 닫고, 쓸 수 있는 체결이 있으면 ENTERED로 바꿔요.
     */
    @Scheduled(fixedDelayString = "${closing.automation.entry-poll-interval:5s}", scheduler = "closingScheduler")
    public void capture() {
        Instant now = calendar.now(); var local = now.atZone(ClosingTradingCalendar.ZONE);
        LocalTime regular = MarketSessionHours.regularTime(now);
        // No all-day polling/egress. On the next active window, old pending entries are expired, never backfilled.
        if (!calendar.isTradingDay(local.toLocalDate()) || regular.isBefore(policy.featureFreezeAt())
                || regular.isAfter(policy.entryDeadline().plusMinutes(1))) return;
        // 처리 1: 마감이 지난 대기 건은 진입 실패로 닫아요(나중에 채워 넣지 않아요).
        jdbc.update("update closing_paper_entry set status='NO_FRESH_TICK_BEFORE_DEADLINE' where status='PENDING' and deadline<=?", Timestamp.from(now));
        // 처리 2: 아직 시한이 남은 대기 건마다 최신 체결이 진입 조건을 만족하는지 보고 진입가를 기록해요.
        jdbc.query("select run_id,stock_code,completed_at,deadline from closing_paper_entry where status='PENDING' and deadline>?",
                rs -> {
                    String symbol = rs.getString("stock_code"); var event = latest.get(symbol);
                    Instant deadline = rs.getTimestamp("deadline").toInstant();
                    Instant executed = calendar.now();
                    if (executable(event, rs.getTimestamp("completed_at").toInstant(), executed, deadline))
                        jdbc.update("update closing_paper_entry set status='ENTERED',source_at=?,received_at=?,entered_at=?,price=? where run_id=? and stock_code=? and status='PENDING'",
                                Timestamp.from(event.tick().occurredAt()), Timestamp.from(event.receivedAt()), Timestamp.from(executed),
                                event.tick().price(), rs.getLong("run_id"), symbol);
                }, Timestamp.from(now));
        // 정리: 진입 마감 시각이 지나면 메모리의 최신 체결을 비워 다음 날로 넘어가지 않게 해요.
        if (!regular.isBefore(policy.entryDeadline())) latest.clear();
    }
    /**
     * 특정 run·종목의 가상 진입 결과를 조회해요.
     * 받는 것: run ID, 종목 코드. 돌려주는 것: 저장된 진입 결과, 행이 없으면 ENTRY_NOT_CAPTURED 상태.
     */
    public Entry find(long run, String symbol) {
        return jdbc.query("select entered_at,price,status from closing_paper_entry where run_id=? and stock_code=?",
                (rs, index) -> new Entry(rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), rs.getBigDecimal(2), rs.getString(3)),
                run, symbol).stream().findFirst().orElse(new Entry(null, null, "ENTRY_NOT_CAPTURED"));
    }
}
