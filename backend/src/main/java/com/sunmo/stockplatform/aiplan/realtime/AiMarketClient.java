/*
 * AI 실시간 감시가 쓰는 KIS REST 조회 두 가지예요: KOSPI·KOSDAQ 지수 현재가(1분마다)와 매수 신호 순간의 호가 1회(스프레드).
 * 연구 수집기(TrajectoryContextCollector, OrderbookCollector)와 같은 KIS API를 쓰지만, 그쪽 동결 설정과 상관없이 따로 불러요.
 * 실패하면 빈 값을 돌려주고, 인증정보는 로그에 남기지 않아요. KIS 호출은 백엔드에서만 해요.
 */
package com.sunmo.stockplatform.aiplan.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** KIS 지수·호가 조회기예요. */
@Component
public class AiMarketClient {
    private static final Logger log = LoggerFactory.getLogger(AiMarketClient.class);

    /** 지수 현재가와 당일 시가예요. 시가 칸이 없으면 open은 null이에요. */
    public record IndexQuote(BigDecimal value, BigDecimal open) { }

    private final RestClient client;
    private final KisProperties kis;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    public AiMarketClient(RestClient kisRestClient, KisProperties kis, KisTokenManager tokens, KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.kis = kis;
        this.tokens = tokens;
        this.requests = requests;
    }

    public boolean enabled() { return kis.enabled(); }

    /**
     * 하는 일: 업종 지수 현재가(FHPUP02100000)를 조회해요. 받는 것: 업종 코드(0001 KOSPI, 1001 KOSDAQ).
     * 돌려주는 것: 현재가와 시가(bstp_nmix_oprc). 실패하면 빈 값.
     */
    public Optional<IndexQuote> index(String code) {
        if (!kis.enabled()) return Optional.empty();
        try {
            kis.requireCredentials();
            JsonNode output = requests.execute(false, () -> {
                JsonNode response = client.get().uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-index-price")
                                .queryParam("FID_COND_MRKT_DIV_CODE", "U").queryParam("FID_INPUT_ISCD", code).build())
                        .header("authorization", "Bearer " + tokens.getAccessToken()).header("appkey", kis.appKey())
                        .header("appsecret", kis.appSecret()).header("tr_id", "FHPUP02100000").header("custtype", "P")
                        .retrieve().body(JsonNode.class);
                if (response == null || !"0".equals(response.path("rt_cd").asText()))
                    KisResponseErrors.failure("KIS index failed", response == null ? "EMPTY" : response.path("msg_cd").asText(),
                            "Index unavailable");
                return response.path("output");
            });
            return Optional.of(parseIndex(output));
        } catch (RuntimeException failure) {
            log.warn("AI watch index unavailable code={} type={}", code, failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 하는 일: 호가(FHKST01010200)를 한 번 조회해 스프레드 절반(%)을 계산해요. (매도1호가 − 매수1호가) ÷ 중간값 ÷ 2 × 100.
     * 돌려주는 것: 스프레드 절반(%). 실패하거나 호가가 비면 빈 값.
     */
    public Optional<BigDecimal> halfSpreadPct(String stockCode) {
        if (!kis.enabled()) return Optional.empty();
        try {
            kis.requireCredentials();
            JsonNode output = requests.execute(false, () -> {
                JsonNode response = client.get().uri(b -> b.path("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn")
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J").queryParam("FID_INPUT_ISCD", stockCode).build())
                        .header("authorization", "Bearer " + tokens.getAccessToken()).header("appkey", kis.appKey())
                        .header("appsecret", kis.appSecret()).header("tr_id", "FHKST01010200").header("custtype", "P")
                        .retrieve().body(JsonNode.class);
                if (response == null || !"0".equals(response.path("rt_cd").asText()))
                    KisResponseErrors.failure("KIS orderbook failed", response == null ? "EMPTY" : response.path("msg_cd").asText(),
                            "Orderbook unavailable");
                return response.path("output1");
            });
            return parseHalfSpread(output);
        } catch (RuntimeException failure) {
            log.warn("AI watch orderbook unavailable code={} type={}", stockCode, failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static IndexQuote parseIndex(JsonNode output) {
        BigDecimal value = number(output, "bstp_nmix_prpr");
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Invalid index");
        BigDecimal open = number(output, "bstp_nmix_oprc");
        return new IndexQuote(value, open != null && open.signum() > 0 ? open : null);
    }

    static Optional<BigDecimal> parseHalfSpread(JsonNode output) {
        BigDecimal ask = number(output, "askp1"), bid = number(output, "bidp1");
        if (ask == null || bid == null || ask.signum() <= 0 || bid.signum() <= 0 || ask.compareTo(bid) < 0)
            return Optional.empty();
        BigDecimal mid = ask.add(bid).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
        return Optional.of(ask.subtract(bid).multiply(BigDecimal.valueOf(50)).divide(mid, 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal number(JsonNode node, String field) {
        String text = node == null ? "" : node.path(field).asText("").trim().replace(",", "");
        if (text.isEmpty()) return null;
        try { return new BigDecimal(text); } catch (NumberFormatException e) { return null; }
    }
}
