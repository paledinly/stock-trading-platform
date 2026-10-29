/*
 * 정밀 종목 깔때기를 검증하기 위한 연구용 그림자 기록이에요.
 * 진입 마감(15:20) 직후 그날 Broad 후보와 정밀 구독 종목 전부의 현재가를 한 번씩 받아 funnel_shadow_quote에 남겨요.
 * 정밀이 아닌 종목은 실시간 체결이 없어서, 깔때기 단계마다 같은 진입 시각 가격으로 비교하려면 이 기록이 필요해요.
 * 점수나 선정 로직은 이 데이터를 읽지 않아요.
 */
package com.sunmo.stockplatform.closing.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 깔때기 그림자 시세 기록 담당이에요. 그날 후보 명단 전체의 사진을 같은 시각에 한 장씩 찍어 두는 일을 해요. */
@Service
public class FunnelShadowService {
    private static final Logger log = LoggerFactory.getLogger(FunnelShadowService.class);
    // 평소 시각 기준 실행 시각이에요(마감이 늦춰진 날은 그만큼 늦게).
    static final LocalTime RUN_AT = LocalTime.of(15, 20, 30);

    /** KIS 현재가 한 종목을 가져오는 통로예요. 테스트가 응답을 직접 넣을 수 있게 분리했어요. */
    public interface QuoteSource { JsonNode fetch(String stockCode); }

    /** 한 번 기록한 결과 요약이에요. */
    public record Result(LocalDate date, int targets, int saved, int failed) { }

    private final boolean enabled;
    private final KisProperties kis;
    private final ClosingTradingCalendar calendar;
    private final JdbcTemplate jdbc;
    private final QuoteSource quotes;

    /** 스프링이 쓰는 생성자예요. KIS 국내주식 현재가(FHKST01010100)를 부르는 QuoteSource를 만들어요. */
    @Autowired
    public FunnelShadowService(@Value("${closing.funnel-shadow.enabled:true}") boolean enabled, KisProperties kis,
            ClosingTradingCalendar calendar, JdbcTemplate jdbc, RestClient kisRestClient, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this(enabled, kis, calendar, jdbc, code -> requests.execute(true, () -> {
            JsonNode response = kisRestClient.get().uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-price")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J").queryParam("FID_INPUT_ISCD", code).build())
                    .header("authorization", "Bearer " + tokens.getAccessToken()).header("appkey", kis.appKey())
                    .header("appsecret", kis.appSecret()).header("tr_id", "FHKST01010100").header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText()))
                KisResponseErrors.failure("KIS quote failed", response == null ? "EMPTY" : response.path("msg_cd").asText(),
                        "Quote unavailable");
            return response.path("output");
        }));
    }

    /** 테스트에서도 쓰는 생성자예요. */
    FunnelShadowService(boolean enabled, KisProperties kis, ClosingTradingCalendar calendar, JdbcTemplate jdbc,
            QuoteSource quotes) {
        this.enabled = enabled;
        this.kis = kis;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.quotes = quotes;
    }

    /**
     * 하는 일: 평일 15:20:30(마감이 늦춰진 날은 16:20:30)에 기록해요. 두 번 깨어나 그날 장 시간에 맞는 쪽만 일해요.
     * 실패해도 로그만 남겨요.
     */
    @Scheduled(cron = "30 20 15,16 * * MON-FRI", zone = "Asia/Seoul", scheduler = "closingScheduler")
    public void scheduled() {
        if (!enabled || !kis.enabled() || !MarketSessionHours.firesAt(calendar.now(), RUN_AT))
            return;
        try {
            log.info("Funnel shadow quotes: {}", record());
        } catch (RuntimeException error) {
            log.warn("Funnel shadow quotes failed: {}", error.getMessage());
        }
    }

    /**
     * 하는 일: 오늘 Broad 후보와 정밀 구독 종목을 모아 현재가를 한 번씩 받아 저장해요(이미 있는 행은 건너뛰어요).
     * 돌려주는 것: 요약. 거래일이 아니면 대상 0이에요.
     */
    Result record() {
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today))
            return new Result(today, 0, 0, 0);
        // 대상: 오늘 Broad 스냅샷에 나온 종목(in_broad)과 오늘 정밀 구독 기록이 있는 종목(in_precision)이에요.
        Map<Long, Object[]> targets = new LinkedHashMap<>();
        jdbc.query("""
                select distinct s.id, s.stock_code from market_broad_snapshot b join stock s on s.id = b.stock_id
                 where b.session_date = ?""", rs -> {
            targets.put(rs.getLong(1), new Object[] { rs.getString(2), true, false });
        }, Date.valueOf(today));
        jdbc.query("""
                select distinct s.id, s.stock_code from precision_subscription_session p join stock s on s.stock_code = p.stock_code
                 where p.session_date = ?""", rs -> {
            Object[] row = targets.get(rs.getLong(1));
            if (row == null)
                targets.put(rs.getLong(1), new Object[] { rs.getString(2), false, true });
            else
                row[2] = true;
        }, Date.valueOf(today));
        int saved = 0, failed = 0;
        for (var entry : targets.entrySet()) {
            String code = (String) entry.getValue()[0];
            BigDecimal price = null, rate = null, value = null;
            String error = null;
            try {
                JsonNode output = quotes.fetch(code);
                price = decimal(output, "stck_prpr");
                rate = decimal(output, "prdy_ctrt");
                value = decimal(output, "acml_tr_pbmn");
                if (price == null || price.signum() <= 0)
                    error = "NO_PRICE";
            } catch (RuntimeException failure) {
                error = failure.getClass().getSimpleName();
            }
            if (error != null)
                failed++;
            // 같은 날 이미 기록한 종목은 건너뛰어요(재시작 뒤 다시 돌아도 처음 기록을 덮어쓰지 않아요).
            saved += jdbc.update("""
                    insert into funnel_shadow_quote(session_date, stock_id, stock_code, in_broad, in_precision, price,
                        change_rate, accumulated_trading_value, received_at, error)
                    select cast(? as date), cast(? as bigint), cast(? as varchar(12)), cast(? as boolean), cast(? as boolean),
                           cast(? as numeric), cast(? as numeric), cast(? as numeric), cast(? as timestamp with time zone),
                           cast(? as varchar(200)) where not exists
                        (select 1 from funnel_shadow_quote where session_date = ? and stock_id = ?)""",
                    Date.valueOf(today), entry.getKey(), code, entry.getValue()[1], entry.getValue()[2],
                    error == null ? price : null, error == null ? rate : null, error == null ? value : null,
                    Timestamp.from(calendar.now()), error, Date.valueOf(today), entry.getKey());
        }
        return new Result(today, targets.size(), saved, failed);
    }

    private static BigDecimal decimal(JsonNode output, String field) {
        String raw = output == null ? "" : output.path(field).asText("").trim();
        return raw.isEmpty() ? null : new BigDecimal(raw);
    }
}
