/*
 * 장마감 Trajectory 스냅샷마다 "다음 거래일 오전에 실제로 어떻게 됐는지"(시가 갭, 오전 최고·최저, 목표/손절 도달)를 계산해 붙이는 서비스예요.
 * 확정된 5분봉만 쓰고, 봉 안의 가격 순서를 알 수 없으면 null로 남겨요. 비용 없는 연구용 라벨이고 실제 체결 손익이 아니에요.
 * 10분마다 스케줄로 돌고, TrajectoryController의 /outcomes/track에서도 직접 호출돼요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.candle.domain.StockCandle;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.closing.application.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.*;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryFeatures.*;

/**
 * 스냅샷에 다음 날 오전 결과표(outcome)를 채워 넣는 서비스예요.
 * 일기 예보를 적어 둔 뒤 다음 날 실제 날씨를 옆 칸에 적어 넣는 것처럼, 스냅샷 시점 정보와 다음 날 결과를 나란히 남겨요.
 */
@Service
public class MorningOutcomeService {
    private final TrajectoryStore store;
    private final StockRepository stocks;
    private final StockCandleRepository candles;
    private final ClosingTradingCalendar calendar;
    private final TrajectoryProperties policy;
    /** 하는 일: 저장소·레포지토리·달력·설정을 받아 보관해요. 받는 것: 각 의존 객체. 돌려주는 것: 서비스 객체예요. */
    public MorningOutcomeService(TrajectoryStore store, StockRepository stocks, StockCandleRepository candles,
            ClosingTradingCalendar calendar, TrajectoryProperties policy) {
        this.store = store; this.stocks = stocks; this.candles = candles; this.calendar = calendar; this.policy = policy;
    }
    /**
     * 하는 일: (기본 10분마다) 수집이 켜져 있고 09:00이 지났으면 직전 거래일 스냅샷의 오전 결과를 다시 계산해요.
     * 받는 것: 없어요. 돌려주는 것: 없어요. 휴장일에도 돌며 같은 결과를 덮어써요.
     */
    @Scheduled(fixedDelayString = "${closing.trajectory.outcome-interval:10m}", scheduler = "trajectoryContextScheduler")
    public void scheduled() {
        if (policy.enabled() && MarketSessionHours.regularTime(calendar.now()).isAfter(LocalTime.of(9, 0)))
            track(calendar.previousTradingDay(calendar.today()));
    }
    /**
     * 하는 일: 그날 모든 스냅샷에 대해 다음 거래일 오전 결과를 계산해 저장해요.
     * 기준 가격은 그날 마지막 5분봉(15:25 시작) 종가, 가상 진입가는 스냅샷 계산 완료 뒤 첫 5분봉 시가(15:20 전만)예요.
     * 받는 것: 스냅샷 날짜예요. 돌려주는 것: 그날 스냅샷 개수예요(종목을 못 찾아 건너뛴 것도 포함돼요).
     */
    public synchronized int track(LocalDate date) {
        List<Snapshot> snapshots = store.snapshots(date.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant(),
                date.plusDays(1).atStartOfDay(ClosingTradingCalendar.ZONE).toInstant());
        for (Snapshot snapshot : snapshots) {
            var stock = stocks.findByStockCode(snapshot.symbol()).orElse(null);
            if (stock == null) continue;
            LocalDate next = calendar.nextTradingDay(date);
            Instant open = calendar.open(next), end = MarketSessionHours.at(next, snapshot.policy().morningEnd());
            var series = candles.findByStockIdAndTimeframeAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTimeAsc(
                    stock.getId(), "5M", calendar.open(date), calendar.close(next));
            var valid = series.stream().filter(c -> valid(c, calendar.now())).sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
            BigDecimal close = valid.stream().filter(c -> c.getStartTime().equals(calendar.close(date).minusSeconds(300)))
                    .map(StockCandle::getClose).findFirst().orElse(null);
            // First whole candle after actual evaluation completion; 15:20 and later cannot be an entry.
            Instant earliest = Instant.ofEpochSecond(Math.floorDiv(snapshot.evaluatedAt().getEpochSecond(), 300) * 300 + 300);
            StockCandle entry = valid.stream().filter(c -> !c.getStartTime().isBefore(earliest)
                    && c.getStartTime().isBefore(MarketSessionHours.at(date, LocalTime.of(15, 20))))
                    .findFirst().orElse(null);
            var morning = valid.stream().filter(c -> !c.getStartTime().isBefore(open) && c.getStartTime().isBefore(calendar.close(next))).toList();
            var outcome = new LinkedHashMap<>(calculate(morning, open, end, close,
                    entry == null ? null : entry.getOpen(), snapshot.policy().targetPercent(), snapshot.policy().stopPercent(), calendar.now()));
            outcome.put("entryAt", entry == null ? null : entry.getStartTime());
            outcome.put("recommendationDate", date); outcome.put("evaluationTime", snapshot.timestamp());
            outcome.put("evaluatedAt", snapshot.evaluatedAt()); outcome.put("entryModel", "NEXT_OBSERVED_FINAL_5M_OPEN_BEFORE_1520");
            // 비용 미반영 연구 라벨이며 실행 손익이 아니라는 표시를 함께 저장해요.
            outcome.put("mode", "SHADOW_GROSS_LABELS_NOT_EXECUTION_PNL"); store.outcome(snapshot, outcome);
        }
        return snapshots.size();
    }
    /**
     * 하는 일: 결과 계산에 써도 되는 5분봉인지 검사해요. 확정봉이고, now 이전에 끝나고 생성·수정됐으며, 가격 모양(고가≥시가·종가≥저가>0)이 정상이어야 해요.
     * 받는 것: 5분봉, 현재 시각. 돌려주는 것: 써도 되면 true예요.
     */
    static boolean valid(StockCandle c, Instant now) {
        return c.isFinalCandle() && c.getTimeframe().equals("5M") && !c.getStartTime().plusSeconds(300).isAfter(now)
                && c.getCreatedAt() != null && c.getUpdatedAt() != null && !c.getCreatedAt().isAfter(now) && !c.getUpdatedAt().isAfter(now)
                && c.getLow().signum() > 0 && c.getHigh().compareTo(c.getOpen().max(c.getClose())) >= 0
                && c.getLow().compareTo(c.getOpen().min(c.getClose())) <= 0;
    }
    /**
     * 하는 일: 다음 날 오전(시가~morningEnd) 5분봉으로 결과 라벨을 계산해요. 오전 봉이 하나라도 빠지면 최고·최저 기반 값은 null이에요.
     * 받는 것: 5분봉 목록, 장 시작·오전 끝 시각, 기준 종가, 가상 진입가, 목표·손절(퍼센트), 현재 시각이에요.
     * 돌려주는 것: 상태(COMPLETED/PENDING/DATA_INCOMPLETE), 시가 갭, 오전 최대 상승·하락, 목표·손절 도달, 10:00/10:30/11:00까지 고저 등이 담긴 Map이에요.
     */
    public static Map<String, Object> calculate(List<StockCandle> source, Instant open, Instant end,
            BigDecimal close, BigDecimal entry, BigDecimal target, BigDecimal stop, Instant now) {
        var rows = source.stream().filter(c -> valid(c, now) && !c.getStartTime().isBefore(open) && c.getStartTime().isBefore(end))
                .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
        boolean complete = complete(rows, open, end) && !now.isBefore(end);
        BigDecimal first = rows.isEmpty() || !rows.getFirst().getStartTime().equals(open) ? null : rows.getFirst().getOpen();
        BigDecimal high = complete ? rows.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null) : null;
        BigDecimal low = complete ? rows.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null) : null;
        Map<String, Object> result = new LinkedHashMap<>(fields("status", complete && close != null ? "COMPLETED" : now.isBefore(end) ? "PENDING" : "DATA_INCOMPLETE",
                "closePrice", close, "entryPrice", entry, "nextOpenPrice", first, "nextOpenReturn", pct(first, close),
                "entryToNextOpenReturn", pct(first, entry), "nextMorningMaxReturn", pct(high, close),
                "nextMorningMaxDrawdown", pct(low, close), "returnBasis", "PRIOR_SESSION_CLOSE", "returnUnit", "PERCENT",
                "morningEnd", end, "targetPercent", target, "stopPercent", stop,
                "targetHit", high == null || close == null ? null : pct(high, close).compareTo(target) >= 0,
                "stopHitBeforeTarget", complete ? stopBeforeTarget(rows, close, target, stop) : null,
                "pathResolution", "FIVE_MINUTE_OHLC_NULL_IF_INTRABAR_ORDER_AMBIGUOUS"));
        // 기준 종가 대비 오전 고가가 1/2/3/5% 이상 올랐는지 표시해요.
        for (int n : List.of(1, 2, 3, 5)) result.put("hit" + n + "Pct", high == null || close == null ? null : pct(high, close).compareTo(BigDecimal.valueOf(n)) >= 0);
        for (int minutes : List.of(60, 90, 120)) {
            Instant checkpoint = open.plusSeconds(minutes * 60L);
            var part = source.stream().filter(c -> valid(c, now) && !c.getStartTime().isBefore(open) && c.getStartTime().isBefore(checkpoint))
                    .sorted(Comparator.comparing(StockCandle::getStartTime)).toList();
            String suffix = minutes == 60 ? "1000" : minutes == 90 ? "1030" : "1100";
            result.put("nextHighUntil" + suffix, complete(part, open, checkpoint) ? part.stream().map(StockCandle::getHigh).max(BigDecimal::compareTo).orElse(null) : null);
            result.put("nextLowUntil" + suffix, complete(part, open, checkpoint) ? part.stream().map(StockCandle::getLow).min(BigDecimal::compareTo).orElse(null) : null);
        }
        return result;
    }
    /** 하는 일: open~end 사이 5분봉이 하나도 빠짐없이 순서대로 있는지 확인해요. 받는 것: 봉 목록, 시작, 끝. 돌려주는 것: 완전하면 true예요. */
    static boolean complete(List<StockCandle> rows, Instant open, Instant end) {
        if (rows.size() != Duration.between(open, end).toMinutes() / 5) return false;
        for (int i = 0; i < rows.size(); i++) if (!rows.get(i).getStartTime().equals(open.plusSeconds(i * 300L))) return false;
        return true;
    }
    /**
     * 하는 일: 기준가 대비 손절선이 목표선보다 먼저 닿았는지 5분봉을 순서대로 보며 판단해요. 시가가 이미 넘었으면 시가로 판정해요.
     * 받는 것: 시간순 5분봉, 기준가, 목표·손절(퍼센트)이에요.
     * 돌려주는 것: 손절 먼저면 true, 목표 먼저이거나 둘 다 안 닿으면 false, 한 봉에서 둘 다 닿아 순서를 알 수 없으면 null이에요.
     */
    public static Boolean stopBeforeTarget(List<StockCandle> rows, BigDecimal base, BigDecimal target, BigDecimal stop) {
        if (base == null || base.signum() <= 0) return null;
        BigDecimal tp = base.multiply(BigDecimal.ONE.add(target.movePointLeft(2))), sl = base.multiply(BigDecimal.ONE.add(stop.movePointLeft(2)));
        for (StockCandle r : rows) {
            if (r.getOpen().compareTo(sl) <= 0) return true;
            if (r.getOpen().compareTo(tp) >= 0) return false;
            boolean hit = r.getHigh().compareTo(tp) >= 0, stopped = r.getLow().compareTo(sl) <= 0;
            if (hit && stopped) return null; // Intrabar order cannot be recovered from OHLC.
            if (stopped) return true;
            if (hit) return false;
        }
        return false;
    }
}
