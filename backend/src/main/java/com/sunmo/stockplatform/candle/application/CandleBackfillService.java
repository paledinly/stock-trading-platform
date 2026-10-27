/*
 * 오늘 빠진 5분봉을 KIS 과거 1분봉으로 받아 5분봉으로 묶어 채우는(backfill) 서비스예요.
 * 실시간 확정봉은 절대 덮어쓰지 않고, 15:20~15:29 무체결 분봉은 버리며, 15:30 종가 체결 분봉은 15:25 봉에 합쳐요.
 * 보충 봉은 실시간 수신을 증명하지 못하므로 출처 BACKFILL로 남아요. CandleQueryService가 오늘 차트 조회 때 불러요.
 */
package com.sunmo.stockplatform.candle.application;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;

import com.sunmo.stockplatform.candle.config.CandleBackfillProperties;
import com.sunmo.stockplatform.candle.domain.*;
import com.sunmo.stockplatform.candle.infrastructure.StockCandleRepository;
import com.sunmo.stockplatform.kis.candle.*;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.stock.domain.Stock;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 빠진 5분봉 메우기 담당이에요.
 * 찢어진 앨범 페이지를 다른 사람이 찍어 둔 사진(KIS 분봉)으로 채우는 것과 같아서, 원본(실시간 확정봉)이 있는 칸은 건드리지 않고 빈 칸만 채워요.
 * 같은 종목은 refreshInterval(기본 1분)에 한 번만 시도해 KIS 호출이 몰리지 않게 해요.
 */
@Service
public class CandleBackfillService {
    private static final Logger log = LoggerFactory.getLogger(CandleBackfillService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final KisMinuteCandleClient client;
    private final StockCandleRepository repository;
    private final CandleBackfillProperties properties;
    private final RealtimeDiagnostics diagnostics;
    private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();
    private final TransactionTemplate transaction;

    /** 하는 일: KIS 분봉 클라이언트, 봉 레포지토리, 보충 설정, 진단 기록부, 트랜잭션 관리자를 주입받아요(의존성 주입). */
    public CandleBackfillService(KisMinuteCandleClient client, StockCandleRepository repository,
            CandleBackfillProperties properties, RealtimeDiagnostics diagnostics,
            PlatformTransactionManager transactions) {
        this.client = client;
        this.repository = repository;
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * 하는 일: 종목의 오늘 빈 5분봉 칸들을 KIS 분봉으로 채워요.
     * 받는 것: 종목, 빈 칸 시작시각 목록, 현재 시각.
     * 돌려주는 것: 실제로 저장한 봉 개수. 꺼져 있거나 빈 칸이 없거나 재시도 간격 안이면 0이고, 실패해도 예외 대신 0을 돌려줘요.
     * KIS 호출은 DB 트랜잭션 밖에서 하고, 받은 봉 저장만 짧은 트랜잭션으로 묶어요(호출한 쪽에 트랜잭션이 있으면 거기에 참여해요).
     */
    public int backfillToday(Stock stock, List<Instant> gaps, Instant now) {
        if (!properties.enabled() || gaps.isEmpty() || throttled(stock.getStockCode(), now))
            return 0;
        lastAttempt.put(stock.getStockCode(), now);
        diagnostics.backfillStarted(stock.getStockCode(), gaps.size());
        try {
            // 1) KIS에서 빈 칸 구간을 덮는 1분봉을 받아 5분봉으로 묶어요(트랜잭션 밖).
            Map<Instant, MinuteCandle> minutes = fetchPages(stock.getStockCode(), gaps);
            List<FiveMinuteBackfill> aggregated = aggregate(minutes.values());
            // 2) 빈 칸에 해당하는 봉만 한 트랜잭션으로 저장해요.
            Integer result = transaction.execute(status -> save(stock, gaps, aggregated));
            int saved = result == null ? 0 : result;
            diagnostics.backfillSucceeded(stock.getStockCode(), gaps.size(), saved);
            return saved;
        } catch (RuntimeException error) {
            diagnostics.backfillFailed(stock.getStockCode(), gaps.size(), rootMessage(error));
            log.warn("Candle backfill failed for {}: {}", stock.getStockCode(), rootMessage(error));
            return 0;
        }
    }

    /**
     * 하는 일: 묶은 5분봉 중 빈 칸에 해당하는 것만 저장해요. 실시간 확정봉이 이미 있으면 건너뛰어요.
     * 받는 것: 종목, 빈 칸 목록, 5분봉 목록. 돌려주는 것: 저장한 봉 개수.
     */
    private int save(Stock stock, List<Instant> gaps, List<FiveMinuteBackfill> aggregated) {
        int saved = 0;
        for (FiveMinuteBackfill candle : aggregated) {
            if (!gaps.contains(candle.startTime()))
                continue;
            StockCandle entity = repository
                    .findByStockIdAndTimeframeAndStartTime(stock.getId(), "5M", candle.startTime())
                    .orElseGet(() -> new StockCandle(stock, candle.startTime(), candle.open(), candle.high(),
                            candle.low(), candle.close(), candle.volume(), candle.tradingValue(), true, 0,
                            CandleSource.BACKFILL));
            if (entity.getSource() == CandleSource.REALTIME && entity.isFinalCandle())
                continue;
            entity.revise(candle.open(), candle.high(), candle.low(), candle.close(), candle.volume(),
                    candle.tradingValue(), true, 0, CandleSource.BACKFILL);
            repository.save(entity);
            saved++;
        }
        return saved;
    }

    /**
     * 하는 일: 이 종목을 최근에 시도했는지 확인해요.
     * 받는 것: 종목코드, 현재 시각. 돌려주는 것: 마지막 시도 후 refreshInterval이 안 지났으면 true.
     */
    private boolean throttled(String code, Instant now) {
        Instant previous = lastAttempt.get(code);
        return previous != null && now.isBefore(previous.plus(properties.refreshInterval()));
    }

    /**
     * 하는 일: 가장 늦은 빈 칸 끝에서부터 과거 방향으로 KIS 분봉 페이지를 넘기며 받아요.
     * 가장 이른 빈 칸까지 닿거나, 빈 페이지가 오거나, 요청 한도(maxRequestsPerQuery)에 닿으면 멈춰요.
     * 받는 것: 종목코드, 빈 칸 목록(비어 있으면 안 돼요). 돌려주는 것: 분봉 시작시각 순 Map.
     */
    private Map<Instant, MinuteCandle> fetchPages(String code, List<Instant> gaps) {
        Instant earliest = Collections.min(gaps).minus(Duration.ofMinutes(1));
        LocalTime cursor = Collections.max(gaps).plus(Duration.ofMinutes(5)).atZone(SEOUL).toLocalTime();
        Map<Instant, MinuteCandle> result = new TreeMap<>();
        for (int request = 0; request < properties.maxRequestsPerQuery(); request++) {
            List<MinuteCandle> page = client.fetch(code, cursor);
            if (page.isEmpty())
                break;
            page.forEach(candle -> result.put(candle.startTime(), candle));
            Instant oldest = page.stream().map(MinuteCandle::startTime).min(Comparator.naturalOrder()).orElseThrow();
            if (!oldest.isAfter(earliest))
                break;
            cursor = oldest.atZone(SEOUL).toLocalTime().minusMinutes(1);
        }
        return result;
    }

    /**
     * 하는 일: 1분봉들을 5분봉으로 묶어요. 실시간 집계와 같은 규칙을 따라요.
     * 받는 것: 1분봉 모음(순서 무관). 돌려주는 것: 시작시각 순 5분봉 목록.
     * 거래대금은 누적 거래대금 차이를 우선 쓰고, 09:00 첫 칸은 누적값 자체, 그 밖에는 종가 × 거래량 합으로 추정해요.
     */
    public static List<FiveMinuteBackfill> aggregate(Collection<MinuteCandle> input) {
        // 1) 분봉을 시간순으로 정렬해 5분 칸별로 나눠요.
        List<MinuteCandle> sorted = input.stream().sorted(Comparator.comparing(MinuteCandle::startTime)).toList();
        Map<Instant, List<MinuteCandle>> buckets = new LinkedHashMap<>();
        for (MinuteCandle minute : sorted) {
            Instant bucket = CandleGapDetector.floorFiveMinutes(minute.startTime());
            // The random-end closing auction print (15:30 minute) is the session's final 5M bar, as in realtime.
            // 15:30 분봉(랜덤엔드 종가 체결)은 실시간과 똑같이 마지막 15:25 봉에 넣어요.
            // 수능일처럼 마감이 늦춰진 날은 평소 시각으로 바꿔 비교해요(16:30 분봉 → 15:30).
            LocalTime minuteTime = MarketSessionHours.regularTime(minute.startTime());
            // Closing call minutes without trades are placeholders, not prices anyone traded at.
            // 15:20~15:29 동시호가 시간의 무체결 분봉은 실제 거래 가격이 아닌 자리 채움이라 버려요.
            if (!minuteTime.isBefore(LocalTime.of(15, 20)) && minuteTime.isBefore(LocalTime.of(15, 30))
                    && minute.volume() <= 0)
                continue;
            if (!minuteTime.isBefore(LocalTime.of(15, 30)))
                bucket = MarketSessionHours.at(minute.startTime().atZone(SEOUL).toLocalDate(), LocalTime.of(15, 25));
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(minute);
        }
        // 2) 칸마다 시가(첫 분봉)·종가(마지막 분봉)·고가·저가·거래량·거래대금을 계산해요.
        List<FiveMinuteBackfill> result = new ArrayList<>();
        BigDecimal priorCumulative = null;
        for (var entry : buckets.entrySet()) {
            List<MinuteCandle> rows = entry.getValue();
            BigDecimal open = rows.getFirst().open();
            BigDecimal close = rows.getLast().close();
            BigDecimal high = rows.stream().map(MinuteCandle::high).max(Comparator.naturalOrder()).orElseThrow();
            BigDecimal low = rows.stream().map(MinuteCandle::low).min(Comparator.naturalOrder()).orElseThrow();
            long volume = rows.stream().mapToLong(MinuteCandle::volume).sum();
            BigDecimal lastCumulative = rows.getLast().cumulativeTradingValue();
            var bucketTime = entry.getKey().atZone(SEOUL);
            boolean openingBucket = bucketTime.toLocalTime().equals(MarketSessionHours.open(bucketTime.toLocalDate()));
            BigDecimal value = lastCumulative != null && priorCumulative != null
                    && lastCumulative.compareTo(priorCumulative) >= 0
                            ? lastCumulative.subtract(priorCumulative)
                            : openingBucket && lastCumulative != null ? lastCumulative
                                    : rows.stream().map(row -> row.close().multiply(BigDecimal.valueOf(row.volume())))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new FiveMinuteBackfill(entry.getKey(), open, high, low, close, volume, value));
            if (lastCumulative != null)
                priorCumulative = lastCumulative;
        }
        return result;
    }

    /** 하는 일: 예외의 가장 안쪽 원인 메시지를 꺼내요. 메시지가 없으면 예외 클래스 이름을 돌려줘요. */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** 분봉을 묶어 만든 보충용 5분봉 한 개예요. */
    public record FiveMinuteBackfill(Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, long volume, BigDecimal tradingValue) {
    }
}
