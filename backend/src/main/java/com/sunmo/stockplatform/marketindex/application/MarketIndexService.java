/*
 * KOSPI·KOSDAQ 지수 일봉을 market_index_daily에 모으고(외부 요인 연구 기록), 화면에 보여 줄 최신 지수를 만들어요.
 * 일봉은 평일 16:05에 최근 20일을 다시 받아 채우고, 과거 구간은 backfill API로 한 번에 받아요. 점수·선정에 쓰지 않아요.
 * 최신 지수는 장중 5분마다 쌓이는 지수 관측(closing_context_observation kind=INDEX)을 쓰고, 없으면 마지막 일봉 종가를 써요.
 * MarketIndexController가 호출해요.
 */
package com.sunmo.stockplatform.marketindex.application;

import com.sunmo.stockplatform.closing.application.MarketSessionHours;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.index.KisIndexDailyClient;
import com.sunmo.stockplatform.kis.index.KisIndexDailyClient.IndexDailyBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지수 일봉 수집과 최신 지수 조회를 맡아요. 시각은 모두 호스트 수신 시각이며 KIS 원천 시각이 아니에요.
 */
@Service
public class MarketIndexService {
    private static final Logger log = LoggerFactory.getLogger(MarketIndexService.class);
    /** KIS 업종 코드와 화면 이름이에요. 장중 지수 관측의 symbol도 이 이름(KOSPI, KOSDAQ)을 써요. */
    static final Map<String, String> INDICES = indices();
    /** 한 지수를 거꾸로 나눠 받을 때의 최대 요청 수예요(50행 × 80 = 약 16년). */
    static final int MAX_PAGES = 80;
    /** 장 마감 뒤 이만큼 지나야 오늘 일봉을 확정값으로 저장해요. */
    static final Duration CLOSE_SETTLE = Duration.ofMinutes(10);

    private static Map<String, String> indices() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("0001", "KOSPI");
        map.put("1001", "KOSDAQ");
        return map;
    }

    private final KisIndexDailyClient client;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** 지수 하나의 화면 표시값이에요. source는 INTRADAY(장중 관측) 또는 DAILY_CLOSE(마지막 일봉 종가)예요. */
    public record IndexView(String name, BigDecimal value, BigDecimal changePct, Instant observedAt, String source,
            LocalDate previousDate, BigDecimal previousClose) { }

    /** 지수 하나의 수집 결과예요. */
    public record BackfillResult(String name, int requests, int saved, LocalDate oldest, LocalDate newest) { }

    public MarketIndexService(KisIndexDailyClient client, KisProperties kis, ClosingTradingCalendar calendar,
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.client = client;
        this.kis = kis;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 하는 일: 거래일 16:05(KST)에 최근 20일 지수 일봉을 다시 받아 채워요. 실패해도 로그만 남겨요. */
    @Scheduled(cron = "${market.index-daily.cron:0 5 16 * * MON-FRI}", zone = "Asia/Seoul")
    public void refreshRecent() {
        // 마감이 늦춰진 날(수능일 등)은 아래 17:05 실행이 맡아요.
        if (MarketSessionHours.shift(calendar.today()).isZero())
            refresh();
    }

    /** 하는 일: 마감이 한 시간 늦춰진 날에만 17:05에 같은 갱신을 해요. */
    @Scheduled(cron = "0 5 17 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshRecentDelayed() {
        if (!MarketSessionHours.shift(calendar.today()).isZero())
            refresh();
    }

    private void refresh() {
        if (!kis.enabled() || !calendar.isTradingDay(calendar.today()))
            return;
        try {
            LocalDate today = calendar.today();
            log.info("Index daily refresh: {}", backfill(today.minusDays(20), today));
        } catch (RuntimeException error) {
            log.warn("Index daily refresh failed: {}", error.getMessage());
        }
    }

    /**
     * 하는 일: from~to 지수 일봉을 KIS에서 받아 저장해요(있으면 갱신). 장 마감 10분 전에는 오늘 일봉을 저장하지 않아요.
     * 받는 것: 시작일, 끝일. 돌려주는 것: 지수별 요청 수·저장 수·받은 기간.
     */
    public synchronized List<BackfillResult> backfill(LocalDate from, LocalDate to) {
        LocalDate today = calendar.today();
        boolean todaySettled = !calendar.now().isBefore(calendar.close(today).plus(CLOSE_SETTLE));
        LocalDate last = to.isAfter(today) ? today : to;
        List<BackfillResult> results = new ArrayList<>();
        for (var index : INDICES.entrySet()) {
            int requests = 0;
            int saved = 0;
            LocalDate oldest = null;
            LocalDate newest = null;
            LocalDate through = last;
            while (!through.isBefore(from) && requests < MAX_PAGES) {
                List<IndexDailyBar> bars = client.fetch(index.getKey(), from, through);
                requests++;
                if (bars.isEmpty())
                    break;
                Instant fetchedAt = calendar.now();
                for (IndexDailyBar bar : bars) {
                    if (bar.tradeDate().equals(today) && !todaySettled)
                        continue;
                    save(index.getKey(), index.getValue(), bar, fetchedAt);
                    saved++;
                    newest = newest == null || bar.tradeDate().isAfter(newest) ? bar.tradeDate() : newest;
                }
                LocalDate pageOldest = bars.getFirst().tradeDate();
                oldest = pageOldest;
                if (!pageOldest.isAfter(from))
                    break;
                through = pageOldest.minusDays(1);
            }
            results.add(new BackfillResult(index.getValue(), requests, saved, oldest, newest));
        }
        return results;
    }

    /** 하는 일: 일봉 한 행을 저장해요. 같은 지수·날짜가 있으면 값을 갱신해요(H2·Postgres 모두 되는 갱신 후 삽입 방식). */
    private void save(String code, String name, IndexDailyBar bar, Instant fetchedAt) {
        Object[] values = {bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(), bar.tradingValue(),
                Timestamp.from(fetchedAt), code, Date.valueOf(bar.tradeDate())};
        int updated = jdbc.update("update market_index_daily set open = ?, high = ?, low = ?, close = ?, volume = ?, "
                + "trading_value = ?, fetched_at = ? where index_code = ? and trade_date = ?", values);
        if (updated == 0)
            jdbc.update("insert into market_index_daily(index_code, index_name, trade_date, open, high, low, close, volume, "
                    + "trading_value, fetched_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", code, name,
                    Date.valueOf(bar.tradeDate()), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                    bar.tradingValue(), Timestamp.from(fetchedAt));
    }

    /**
     * 하는 일: 지수마다 화면에 보여 줄 최신 값을 만들어요. 오늘 장중 관측이 있으면 그 값(전일 대비 등락률은 KIS 값)을,
     * 없으면 마지막 일봉 종가와 그 전 일봉 대비 등락률을 써요. 전일 종가는 오늘보다 앞선 마지막 일봉이에요.
     */
    public List<IndexView> latest() {
        LocalDate today = calendar.today();
        Instant dayStart = today.atStartOfDay(ClosingTradingCalendar.ZONE).toInstant();
        List<IndexView> views = new ArrayList<>();
        for (String name : INDICES.values()) {
            List<Object[]> previous = jdbc.query("select trade_date, close from market_index_daily where index_name = ? "
                    + "and trade_date < ? order by trade_date desc limit 2",
                    (rs, i) -> new Object[]{rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)}, name, Date.valueOf(today));
            LocalDate previousDate = previous.isEmpty() ? null : (LocalDate) previous.get(0)[0];
            BigDecimal previousClose = previous.isEmpty() ? null : (BigDecimal) previous.get(0)[1];
            List<IndexView> intraday = jdbc.query("select payload, received_at from closing_context_observation "
                    + "where kind = 'INDEX' and symbol = ? and received_at >= ? order by received_at desc limit 1",
                    (rs, i) -> intradayView(name, rs.getString(1), rs.getTimestamp(2).toInstant(), previousDate, previousClose),
                    name, Timestamp.from(dayStart));
            if (!intraday.isEmpty() && intraday.get(0) != null) {
                views.add(intraday.get(0));
                continue;
            }
            List<Object[]> lastBars = jdbc.query("select trade_date, close, fetched_at from market_index_daily where index_name = ? "
                    + "order by trade_date desc limit 2", (rs, i) -> new Object[]{rs.getDate(1).toLocalDate(),
                    rs.getBigDecimal(2), rs.getTimestamp(3).toInstant()}, name);
            if (lastBars.isEmpty()) {
                views.add(new IndexView(name, null, null, null, "NONE", previousDate, previousClose));
                continue;
            }
            BigDecimal close = (BigDecimal) lastBars.get(0)[1];
            BigDecimal before = lastBars.size() > 1 ? (BigDecimal) lastBars.get(1)[1] : null;
            views.add(new IndexView(name, close, changePct(close, before), (Instant) lastBars.get(0)[2], "DAILY_CLOSE",
                    lastBars.size() > 1 ? (LocalDate) lastBars.get(1)[0] : null, before));
        }
        return views;
    }

    /** 장중 지수 관측(JSON)에서 값과 전일 대비 등락률을 꺼내요. 형식이 틀리면 null이에요. */
    private IndexView intradayView(String name, String payload, Instant receivedAt, LocalDate previousDate,
            BigDecimal previousClose) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.path("value").isNumber())
                return null;
            BigDecimal change = node.path("returnPct").isNumber() ? node.path("returnPct").decimalValue() : null;
            return new IndexView(name, node.path("value").decimalValue(), change, receivedAt, "INTRADAY",
                    previousDate, previousClose);
        } catch (Exception malformed) {
            return null;
        }
    }

    private static BigDecimal changePct(BigDecimal close, BigDecimal before) {
        if (close == null || before == null || before.signum() == 0)
            return null;
        return close.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 2, RoundingMode.HALF_UP);
    }
}
