/*
 * 미국 지수(S&P500·나스닥 종합·나스닥100·다우·VIX·필라델피아 반도체)·원/달러 환율·EWY ETF 일봉을 overseas_daily에 모아요.
 * 외부 요인 연구(H-D 등)용 기록이에요. 점수·선정에 쓰지 않아요. 매일(월~토) 07:50에 최근 15일을 다시 받아 채우고,
 * 과거 구간은 backfill API로 받아요. 오늘(한국 날짜) 이후 날짜의 일봉은 아직 끝나지 않았을 수 있어 저장하지 않아요.
 */
package com.sunmo.stockplatform.marketindex.application;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.overseas.KisOverseasDailyClient;
import com.sunmo.stockplatform.kis.overseas.KisOverseasDailyClient.OverseasBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 해외 일봉 수집 담당이에요. 시각은 모두 호스트 수신 시각이고, 일봉 날짜는 거래소 현지 거래일이에요. */
@Service
public class OverseasDailyService {
    private static final Logger log = LoggerFactory.getLogger(OverseasDailyService.class);
    /** 한 종목을 거꾸로 나눠 받을 때의 최대 요청 수예요. */
    static final int MAX_PAGES = 80;

    /** 수집 대상이에요. kind INDEX는 FHKST03030100(marketDiv N 지수, X 환율), ETF는 HHDFS76240000(exchange)이에요. */
    public record Target(String symbol, String kind, String marketDiv, String code, String exchange) { }

    static final List<Target> TARGETS = List.of(
            new Target("SPX", "INDEX", "N", "SPX", null),
            new Target("COMP", "INDEX", "N", "COMP", null),
            new Target("NDX", "INDEX", "N", "NDX", null),
            new Target("DJI", "INDEX", "N", ".DJI", null),
            new Target("VIX", "INDEX", "N", "VIX", null),
            new Target("SOX", "INDEX", "N", "SOX", null),
            new Target("USDKRW", "INDEX", "X", "FX@KRW", null),
            new Target("EWY", "ETF", null, "EWY", "AMS"));

    /** 종목 하나의 수집 결과예요. */
    public record BackfillResult(String symbol, int requests, int saved, LocalDate oldest, LocalDate newest, String error) { }

    private final KisOverseasDailyClient client;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;

    public OverseasDailyService(KisOverseasDailyClient client, KisProperties kis, ClosingTradingCalendar calendar,
            JdbcTemplate jdbc) {
        this.client = client;
        this.kis = kis;
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    /** 하는 일: 월~토 07:50(KST, 미국 장 마감 뒤)에 최근 15일 해외 일봉을 다시 받아 채워요. 실패해도 로그만 남겨요. */
    @Scheduled(cron = "${market.overseas-daily.cron:0 50 7 * * MON-SAT}", zone = "Asia/Seoul")
    public void refreshRecent() {
        if (!kis.enabled())
            return;
        LocalDate today = calendar.today();
        log.info("Overseas daily refresh: {}", backfill(today.minusDays(15), today));
    }

    /**
     * 하는 일: from~to 해외 일봉을 받아 저장해요(있으면 갱신). 한 종목이 실패해도 다음 종목으로 넘어가요.
     * 받는 것: 시작일, 끝일. 돌려주는 것: 종목별 요청 수·저장 수·받은 기간·오류.
     */
    public synchronized List<BackfillResult> backfill(LocalDate from, LocalDate to) {
        LocalDate today = calendar.today();
        // 오늘(한국 날짜) 날짜의 일봉은 미국 장이 아직 열리지 않았거나 원/달러가 거래 중이라 끝나지 않은 값이에요.
        LocalDate last = to.isBefore(today) ? to : today.minusDays(1);
        List<BackfillResult> results = new ArrayList<>();
        for (Target target : TARGETS) {
            int requests = 0;
            int saved = 0;
            LocalDate oldest = null;
            LocalDate newest = null;
            String error = null;
            LocalDate through = last;
            try {
                while (!through.isBefore(from) && requests < MAX_PAGES) {
                    List<OverseasBar> bars = "ETF".equals(target.kind())
                            ? client.fetchEtf(target.exchange(), target.code(), from, through)
                            : client.fetchIndex(target.marketDiv(), target.code(), from, through);
                    requests++;
                    if (bars.isEmpty())
                        break;
                    Instant fetchedAt = calendar.now();
                    for (OverseasBar bar : bars) {
                        save(target, bar, fetchedAt);
                        saved++;
                        newest = newest == null || bar.tradeDate().isAfter(newest) ? bar.tradeDate() : newest;
                    }
                    oldest = bars.getFirst().tradeDate();
                    if (!oldest.isAfter(from))
                        break;
                    through = oldest.minusDays(1);
                }
            } catch (RuntimeException failure) {
                error = failure.getMessage();
                log.warn("Overseas daily {} failed: {}", target.symbol(), failure.getMessage());
            }
            results.add(new BackfillResult(target.symbol(), requests, saved, oldest, newest, error));
        }
        return results;
    }

    /** 일봉 한 행을 저장해요. 같은 종목·날짜가 있으면 갱신해요(H2·Postgres 모두 되는 갱신 후 삽입 방식). */
    private void save(Target target, OverseasBar bar, Instant fetchedAt) {
        int updated = jdbc.update("update overseas_daily set source_code = ?, open = ?, high = ?, low = ?, close = ?, "
                + "volume = ?, fetched_at = ? where symbol = ? and trade_date = ?", sourceCode(target), bar.open(),
                bar.high(), bar.low(), bar.close(), bar.volume(), Timestamp.from(fetchedAt), target.symbol(),
                Date.valueOf(bar.tradeDate()));
        if (updated == 0)
            jdbc.update("insert into overseas_daily(symbol, source_code, trade_date, open, high, low, close, volume, "
                    + "fetched_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", target.symbol(), sourceCode(target),
                    Date.valueOf(bar.tradeDate()), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                    Timestamp.from(fetchedAt));
    }

    private static String sourceCode(Target target) {
        return "ETF".equals(target.kind()) ? target.exchange() + ":" + target.code() : target.marketDiv() + ":" + target.code();
    }
}
