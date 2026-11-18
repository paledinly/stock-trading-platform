/*
 * AI 후보 평가(ADR-014)의 후보 명단과 평가 카드를 만드는 파일이에요.
 * 명단 = 대상 거래일의 장 전 변동성 명단(VolatilityUniverse) 상위 40종목. 카드 = 시장 공통 정보 + 종목별 한 장(전일까지 확정값만).
 * 대상 거래일 07:55 ~ 장 시작 사이에 처음 부르면 명단과 카드 글을 그대로 저장(고정)하고, 그 뒤에는 저장본을 돌려줘요.
 * 그 밖의 시각에는 저장하지 않는 미리보기만 줘요. AI 응답은 저장된 명단(poolId)에만 받아요.
 */
package com.sunmo.stockplatform.aiplan.candidate;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.marketwide.application.VolatilityUniverse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 후보 명단과 평가 카드 담당이에요. 시험지를 미리 인쇄해 봉투에 봉인해 두는 시험 감독이에요. */
@Service
public class AiCandidatePoolService {
    /** 명단 크기예요(설계 문서 1절). */
    public static final int POOL_SIZE = 40;
    /** 이 시각(대상 거래일, 한국 시간) 전에는 카드를 고정하지 않아요. 해외 지수(07:50 수집)가 들어온 뒤예요. */
    static final LocalTime FREEZE_FROM = LocalTime.of(7, 55);
    // 그날 일봉이 이보다 적으면 아직 덜 들어온 것으로 봐요.
    static final int MIN_DAILY_ROWS = 100;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String[]> OVERSEAS = List.of(new String[] { "SPX", "S&P500" },
            new String[] { "COMP", "나스닥" }, new String[] { "DJI", "다우" }, new String[] { "SOX", "필라델피아 반도체" },
            new String[] { "VIX", "VIX" }, new String[] { "USDKRW", "원/달러" }, new String[] { "EWY", "EWY(한국 ETF)" });

    /** 명단 한 종목이에요. volatility는 0.05가 5%, valueRatio20은 전일 거래대금 ÷ 그 전 20행 평균이에요. */
    public record PoolStock(int rank, long stockId, String stockCode, String stockName, BigDecimal volatility,
            BigDecimal valueRatio20) { }

    /** 명단과 카드예요. stored가 false면 미리보기(저장 안 됨)라 AI 응답을 받을 수 없어요. */
    public record Pool(LocalDate targetDate, String poolId, LocalDate dataDate, Instant createdAt, boolean stored,
            String message, List<PoolStock> stocks, String cardText) { }

    private final JdbcTemplate jdbc;
    private final ClosingTradingCalendar calendar;
    private final VolatilityUniverse universe;

    /** 받는 것: DB 접근 도구, 거래일 달력, 장 전 변동성 명단 계산기. */
    public AiCandidatePoolService(JdbcTemplate jdbc, ClosingTradingCalendar calendar, VolatilityUniverse universe) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.universe = universe;
    }

    /** 하는 일: 다음에 평가할 거래일이에요. 오늘이 거래일이고 장 시작 전이면 오늘, 아니면 다음 거래일이에요. */
    public LocalDate targetDate() {
        LocalDate today = calendar.today();
        return calendar.isTradingDay(today) && calendar.now().isBefore(calendar.open(today))
                ? today : calendar.nextTradingDay(today);
    }

    /**
     * 하는 일: 대상 거래일의 명단과 카드를 돌려줘요. 저장본이 있으면 그대로, 고정 시간대면 만들어 저장, 아니면 미리보기예요.
     * 전일 일봉이 아직 없으면 409예요.
     */
    @Transactional
    public Pool current() {
        LocalDate target = targetDate();
        Pool saved = stored(target);
        if (saved != null)
            return saved;
        Instant now = calendar.now();
        boolean freeze = calendar.today().equals(target) && !now.atZone(SEOUL).toLocalTime().isBefore(FREEZE_FROM)
                && now.isBefore(calendar.open(target));
        Pool built = build(target, now, freeze);
        if (!freeze)
            return built;
        jdbc.update("insert into ai_candidate_pool(target_date, pool_id, data_date, created_at, card_text) values (?, ?, ?, ?, ?)",
                Date.valueOf(target), built.poolId(), Date.valueOf(built.dataDate()), Timestamp.from(now), built.cardText());
        for (PoolStock stock : built.stocks())
            jdbc.update("""
                    insert into ai_candidate_pool_stock(target_date, pool_rank, stock_id, stock_code, stock_name, volatility,
                      value_ratio20) values (?, ?, ?, ?, ?, ?, ?)""", Date.valueOf(target), stock.rank(), stock.stockId(),
                    stock.stockCode(), stock.stockName(), stock.volatility(), stock.valueRatio20());
        return stored(target);
    }

    /** 하는 일: 저장된 명단을 돌려줘요. 없으면 null이에요. */
    public Pool stored(LocalDate target) {
        List<Pool> rows = jdbc.query("select pool_id, data_date, created_at, card_text from ai_candidate_pool where target_date = ?",
                (rs, i) -> new Pool(target, rs.getString(1), rs.getDate(2).toLocalDate(), rs.getTimestamp(3).toInstant(), true,
                        null, null, rs.getString(4)), Date.valueOf(target));
        if (rows.isEmpty())
            return null;
        Pool row = rows.get(0);
        return new Pool(target, row.poolId(), row.dataDate(), row.createdAt(), true, null, stocks(target), row.cardText());
    }

    /** 하는 일: 저장된 명단의 종목을 순위 순서로 돌려줘요. */
    public List<PoolStock> stocks(LocalDate target) {
        return jdbc.query("""
                select pool_rank, stock_id, stock_code, stock_name, volatility, value_ratio20 from ai_candidate_pool_stock
                where target_date = ? order by pool_rank""", (rs, i) -> new PoolStock(rs.getInt(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6)), Date.valueOf(target));
    }

    /** 명단과 카드 글을 만들어요. 저장하지 않아요. */
    Pool build(LocalDate target, Instant now, boolean freeze) {
        LocalDate dataDate = calendar.previousTradingDay(target);
        Integer rows = jdbc.queryForObject("select count(*) from daily_adjusted_candle where trade_date = ?", Integer.class,
                Date.valueOf(dataDate));
        if (rows == null || rows < MIN_DAILY_ROWS)
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                    dataDate + " 일봉이 아직 없어요(매일 18:30 갱신). 그 뒤에 다시 열어 주세요.");
        List<VolatilityUniverse.Ranked> ranked = universe.ranked(target).stream().limit(POOL_SIZE).toList();
        if (ranked.isEmpty())
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT, "변동성 명단을 만들지 못했어요.");
        List<Long> ids = ranked.stream().map(r -> r.stock().getId()).toList();
        Map<Long, List<Bar>> bars = bars(ids, target);
        List<PoolStock> stocks = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            var stock = ranked.get(i).stock();
            List<Bar> list = bars.getOrDefault(stock.getId(), List.of());
            stocks.add(new PoolStock(i + 1, stock.getId(), stock.getStockCode(), stock.getStockName(),
                    BigDecimal.valueOf(ranked.get(i).volatility()).setScale(6, RoundingMode.HALF_UP), valueRatio(list, 20)));
        }
        String poolId = poolId(stocks);
        String card = card(target, dataDate, poolId, now, stocks, bars);
        String message = freeze ? null : "미리보기예요. 대상 거래일 " + FREEZE_FROM + " ~ 장 시작 사이에 열면 명단이 고정되고 응답을 받을 수 있어요.";
        return new Pool(target, poolId, dataDate, now, false, message, List.copyOf(stocks), card);
    }

    /** 명단 식별값: 종목코드를 순서대로 이어 SHA-256을 구한 앞 12자리예요. */
    static String poolId(List<PoolStock> stocks) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(stocks.stream().map(PoolStock::stockCode)
                    .collect(Collectors.joining(",")).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal value) { }

    /** 종목별 일봉(대상일 전 약 130일, 날짜 오름차순)이에요. */
    private Map<Long, List<Bar>> bars(List<Long> ids, LocalDate target) {
        Map<Long, List<Bar>> result = new LinkedHashMap<>();
        jdbc.query("select stock_id, trade_date, open_price, high_price, low_price, close_price, trading_value "
                + "from daily_adjusted_candle where stock_id in (" + placeholders(ids.size()) + ") and trade_date >= ? and trade_date < ? "
                + "order by stock_id, trade_date", rs -> {
                    result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Bar(rs.getDate(2).toLocalDate(),
                            rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)));
                }, args(ids, Date.valueOf(target.minusDays(130)), Date.valueOf(target)));
        return result;
    }

    // ---- 카드 글 ----

    private String card(LocalDate target, LocalDate dataDate, String poolId, Instant now, List<PoolStock> stocks,
            Map<Long, List<Bar>> bars) {
        StringBuilder out = new StringBuilder();
        out.append("# 후보 평가 카드\n");
        out.append("targetDate: ").append(target).append("\npoolId: ").append(poolId).append("\n데이터 기준일: ").append(dataDate)
                .append(" (이날까지의 확정값만 있어요)\n카드 생성: ")
                .append(now.atZone(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(" (한국 시간)\n\n");
        out.append("## 시장 공통\n");
        for (String[] symbol : OVERSEAS)
            out.append(overseas(symbol[0], symbol[1], target));
        out.append(index("0001", "코스피", target)).append(index("1001", "코스닥", target));
        out.append(breadth(dataDate)).append('\n');
        out.append("## 종목 (변동성 순위 순, ").append(stocks.size()).append("종목)\n");
        List<Long> ids = stocks.stream().map(PoolStock::stockId).toList();
        Map<Long, String[]> info = info(ids);
        Map<Long, List<long[]>> flows = flows(ids, target);
        Map<String, List<String>> filings = filings(stocks.stream().map(PoolStock::stockCode).toList(), target);
        for (PoolStock stock : stocks)
            out.append(stockCard(stock, info.get(stock.stockId()), bars.getOrDefault(stock.stockId(), List.of()),
                    flows.getOrDefault(stock.stockId(), List.of()), filings.getOrDefault(stock.stockCode(), List.of())));
        return out.toString();
    }

    private String overseas(String symbol, String label, LocalDate target) {
        List<Map<String, Object>> rows = jdbc.queryForList("select trade_date, close from overseas_daily where symbol = ? "
                + "and trade_date < ? order by trade_date desc limit 2", symbol, Date.valueOf(target));
        if (rows.size() < 2)
            return "- " + label + ": 자료 없음\n";
        return "- " + label + " (" + rows.get(0).get("trade_date") + "): " + signed(change(decimal(rows.get(0).get("close")),
                decimal(rows.get(1).get("close")))) + "\n";
    }

    private String index(String code, String label, LocalDate target) {
        List<BigDecimal> closes = jdbc.query("select close from market_index_daily where index_code = ? and trade_date < ? "
                + "order by trade_date desc limit 21", (rs, i) -> rs.getBigDecimal(1), code, Date.valueOf(target));
        if (closes.size() < 2)
            return "- " + label + ": 자료 없음\n";
        return "- " + label + ": 전일 " + signed(change(closes.get(0), closes.get(1))) + ", 5일 "
                + signed(closes.size() > 5 ? change(closes.get(0), closes.get(5)) : null) + ", 20일 "
                + signed(closes.size() > 20 ? change(closes.get(0), closes.get(20)) : null) + "\n";
    }

    private String breadth(LocalDate dataDate) {
        Map<String, Object> row = jdbc.queryForMap("""
                select sum(case when close_price > prev_close then 1 else 0 end) up,
                       sum(case when close_price < prev_close then 1 else 0 end) down
                from (select d.trade_date, d.close_price, d.trading_value,
                        lag(d.close_price) over (partition by d.stock_id order by d.trade_date) prev_close
                      from daily_adjusted_candle d join stock s on s.id = d.stock_id
                      where not s.is_etf and not s.is_etn and d.trade_date between ? and ?) x
                where trade_date = ? and prev_close > 0 and trading_value >= 1000000000""",
                Date.valueOf(dataDate.minusDays(10)), Date.valueOf(dataDate), Date.valueOf(dataDate));
        return "- 전일 거래대금 10억 이상 종목(ETF·ETN 제외): 상승 " + row.get("up") + ", 하락 " + row.get("down") + "\n";
    }

    /** 종목코드 → [이름, 시장, 업종 중분류 이름]이에요. */
    private Map<Long, String[]> info(List<Long> ids) {
        Map<Long, String[]> result = new LinkedHashMap<>();
        jdbc.query("select s.id, s.stock_name, s.market, c.sector_name from stock s left join sector_code c "
                + "on c.code = s.sector_medium_code where s.id in (" + placeholders(ids.size()) + ")",
                rs -> { result.put(rs.getLong(1), new String[] { rs.getString(2), rs.getString(3), rs.getString(4) }); },
                ids.toArray());
        return result;
    }

    /** 종목별 최근 수급(날짜 내림차순, 최대 5행) [날짜 epochDay, 외국인, 기관, 개인] 백만 원이에요. */
    private Map<Long, List<long[]>> flows(List<Long> ids, LocalDate target) {
        Map<Long, List<long[]>> result = new LinkedHashMap<>();
        jdbc.query("select stock_id, trade_date, foreign_amount, institution_amount, individual_amount from investor_daily_flow "
                + "where stock_id in (" + placeholders(ids.size()) + ") and trade_date >= ? and trade_date < ? "
                + "order by stock_id, trade_date desc", rs -> {
                    List<long[]> list = result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>());
                    if (list.size() < 5)
                        list.add(new long[] { rs.getDate(2).toLocalDate().toEpochDay(), rs.getLong(3), rs.getLong(4), rs.getLong(5) });
                }, args(ids, Date.valueOf(target.minusDays(15)), Date.valueOf(target)));
        return result;
    }

    /** 종목별 최근 5거래일 공시 제목(최신 순, 최대 5건)이에요. 대상일 당일 접수분은 빼요(접수 시각이 없어요). */
    private Map<String, List<String>> filings(List<String> codes, LocalDate target) {
        LocalDate from = target;
        for (int i = 0; i < 5; i++)
            from = calendar.previousTradingDay(from);
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbc.query("select stock_code, rcept_dt, report_nm from dart_filing where stock_code in (" + placeholders(codes.size())
                + ") and rcept_dt >= ? and rcept_dt < ? order by stock_code, rcept_dt desc, rcept_no desc", rs -> {
                    List<String> list = result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>());
                    if (list.size() < 5)
                        list.add(rs.getDate(2).toLocalDate().toString().substring(5) + " " + rs.getString(3).strip());
                }, args(codes, Date.valueOf(from), Date.valueOf(target)));
        return result;
    }

    private String stockCard(PoolStock stock, String[] info, List<Bar> bars, List<long[]> flows, List<String> filings) {
        StringBuilder out = new StringBuilder("\n[").append(stock.stockCode()).append(' ').append(stock.stockName());
        if (info != null)
            out.append(" | ").append(info[1]).append(info[2] == null ? "" : " | " + info[2]);
        out.append("]\n- 명단: 변동성 ").append(stock.rank()).append("위, 20일 일간 등락 표준편차 ")
                .append(stock.volatility().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)).append("%\n");
        int n = bars.size();
        if (n < 2) {
            out.append("- 일봉 자료 부족\n");
        } else {
            Bar last = bars.get(n - 1);
            out.append("- 등락: 전일 ").append(signed(ret(bars, 1))).append(" / 5일 ").append(signed(ret(bars, 5)))
                    .append(" / 20일 ").append(signed(ret(bars, 20))).append(" / 60일 ").append(signed(ret(bars, 60))).append('\n');
            List<Bar> last60 = bars.subList(Math.max(0, n - 60), n);
            BigDecimal high60 = last60.stream().map(Bar::high).max(BigDecimal::compareTo).orElse(null);
            BigDecimal low60 = last60.stream().map(Bar::low).min(BigDecimal::compareTo).orElse(null);
            out.append("- 위치: 60일 고점 대비 ").append(signed(change(last.close(), high60))).append(", 60일 저점 대비 ")
                    .append(signed(change(last.close(), low60))).append(", ").append(streak(bars)).append('\n');
            int up10 = 0, down10 = 0;
            for (int i = Math.max(1, n - 20); i < n; i++) {
                BigDecimal r = change(bars.get(i).close(), bars.get(i - 1).close());
                if (r != null && r.compareTo(BigDecimal.TEN) >= 0) up10++;
                if (r != null && r.compareTo(BigDecimal.TEN.negate()) <= 0) down10++;
            }
            out.append("- 최근 20일: +10% 이상 ").append(up10).append("일, -10% 이하 ").append(down10).append("일\n");
            out.append("- 거래대금: 전일 ").append(eok(last.value())).append(" (20일 평균의 ").append(times(valueRatio(bars, 20)))
                    .append(", 5일 평균의 ").append(times(valueRatio(bars, 5))).append(")\n");
            BigDecimal range = last.high().subtract(last.low());
            String position = range.signum() <= 0 ? "--" : last.close().subtract(last.low()).divide(range, 2, RoundingMode.HALF_UP).toPlainString();
            BigDecimal dayRet = ret(bars, 1);
            out.append("- 전일 모양: 시가→종가 ").append(signed(change(last.close(), last.open()))).append(", 종가 위치 ").append(position)
                    .append(" (1이 고가)").append(dayRet != null && dayRet.compareTo(new BigDecimal("29.5")) >= 0 ? ", 상한가 마감" : "").append('\n');
        }
        if (flows.isEmpty()) {
            out.append("- 수급: 자료 없음\n");
        } else {
            long[] latest = flows.get(0);
            long f5 = 0, i5 = 0, p5 = 0;
            for (long[] row : flows) { f5 += row[1]; i5 += row[2]; p5 += row[3]; }
            out.append("- 수급(").append(LocalDate.ofEpochDay(latest[0]).toString().substring(5)).append(" / 최근 ")
                    .append(flows.size()).append("일 합, 억 원): 외국인 ").append(eokMillion(latest[1])).append(" / ")
                    .append(eokMillion(f5)).append(", 기관 ").append(eokMillion(latest[2])).append(" / ").append(eokMillion(i5))
                    .append(", 개인 ").append(eokMillion(latest[3])).append(" / ").append(eokMillion(p5)).append('\n');
        }
        out.append("- 공시(최근 5거래일): ").append(filings.isEmpty() ? "없음" : String.join("; ", filings)).append('\n');
        return out.toString();
    }

    /** k거래일 전 종가 대비 마지막 종가 등락(%)이에요. 자료가 모자라면 null이에요. */
    private static BigDecimal ret(List<Bar> bars, int k) {
        int n = bars.size();
        return n > k ? change(bars.get(n - 1).close(), bars.get(n - 1 - k).close()) : null;
    }

    /** 마지막 날 거래대금 ÷ 그 전 k행 평균이에요. 행이 모자라면 null이에요. */
    private static BigDecimal valueRatio(List<Bar> bars, int k) {
        int n = bars.size();
        if (n < k + 1)
            return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = n - 1 - k; i < n - 1; i++)
            sum = sum.add(bars.get(i).value());
        if (sum.signum() <= 0)
            return null;
        return bars.get(n - 1).value().multiply(BigDecimal.valueOf(k)).divide(sum, 6, RoundingMode.HALF_UP);
    }

    private static String streak(List<Bar> bars) {
        int up = 0, down = 0;
        for (int i = bars.size() - 1; i > 0; i--) {
            int sign = bars.get(i).close().compareTo(bars.get(i - 1).close());
            if (sign > 0 && down == 0) up++;
            else if (sign < 0 && up == 0) down++;
            else break;
        }
        return up > 0 ? up + "일 연속 상승" : down > 0 ? down + "일 연속 하락" : "보합";
    }

    private static BigDecimal change(BigDecimal value, BigDecimal base) {
        if (value == null || base == null || base.signum() <= 0)
            return null;
        return value.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 6, RoundingMode.HALF_UP);
    }

    private static String signed(BigDecimal pct) {
        if (pct == null)
            return "--";
        BigDecimal rounded = pct.setScale(1, RoundingMode.HALF_UP);
        return (rounded.signum() >= 0 ? "+" : "") + rounded.toPlainString() + "%";
    }

    private static String times(BigDecimal ratio) {
        return ratio == null ? "--" : ratio.setScale(1, RoundingMode.HALF_UP).toPlainString() + "배";
    }

    /** 원 → "412억"이에요. */
    private static String eok(BigDecimal won) {
        return won == null ? "--" : won.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP).toPlainString() + "억";
    }

    /** 백만 원 → 부호 있는 억 원("+38", "-5")이에요. */
    private static String eokMillion(long million) {
        BigDecimal eok = BigDecimal.valueOf(million).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        return (eok.signum() > 0 ? "+" : "") + eok.toPlainString();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : value instanceof BigDecimal big ? big : new BigDecimal(value.toString());
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(1, n), "?"));
    }

    private static Object[] args(List<?> values, Object... tail) {
        List<Object> all = new ArrayList<>(values);
        if (values.isEmpty())
            all.add(null);
        all.addAll(List.of(tail));
        return all.toArray();
    }
}
