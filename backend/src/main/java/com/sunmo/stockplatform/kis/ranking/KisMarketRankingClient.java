package com.sunmo.stockplatform.kis.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.stock.domain.Market;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class KisMarketRankingClient implements MarketRankingProvider {
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;

    public KisMarketRankingClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
    }

    @Override
    @RateLimiter(name = "kisRanking")
    @CircuitBreaker(name = "kisRanking")
    public List<KisRankingEntry> fetch(RankingType type, Market market, int limit) {
        try {
            properties.requireCredentials();
            Endpoint endpoint = Endpoint.forType(type);
            JsonNode response = client.get()
                    .uri(builder -> endpoint.apply(builder.path(endpoint.path()), market).build())
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", endpoint.trId())
                    .header("custtype", "P")
                    .retrieve()
                    .body(JsonNode.class);
            validate(response, type);
            List<KisRankingEntry> entries = new ArrayList<>();
            int rank = 1;
            for (JsonNode row : response.path("output")) {
                String code = text(row, "mksc_shrn_iscd", "stck_shrn_iscd");
                if (code == null || code.isBlank())
                    continue;
                entries.add(new KisRankingEntry(code, text(row, "hts_kor_isnm", "stck_kor_isnm"), rank++,
                        decimal(row, "stck_prpr"), decimal(row, "prdy_ctrt"), number(row, "acml_vol"),
                        decimal(row, "acml_tr_pbmn"), decimal(row, "tday_rltv", "cntg_strth")));
                if (entries.size() >= Math.max(1, limit))
                    break;
            }
            return List.copyOf(entries);
        } catch (IllegalStateException exception) {
            throw new ApplicationException(ErrorCode.KIS_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
                    "KIS integration is not configured", exception);
        } catch (RestClientException exception) {
            throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY,
                    "KIS market ranking request failed: " + type, exception);
        }
    }

    private void validate(JsonNode response, RankingType type) {
        if (response == null || !"0".equals(response.path("rt_cd").asText()) || !response.path("output").isArray()) {
            String code = response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText("UNKNOWN");
            String message = response == null ? "KIS returned an empty response" : response.path("msg1").asText();
            throw new ApplicationException(ErrorCode.KIS_API_ERROR, HttpStatus.BAD_GATEWAY,
                    "KIS market ranking failed %s [%s]: %s".formatted(type, code, message));
        }
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText();
            if (!value.isBlank())
                return value.trim();
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String... fields) {
        String value = text(node, fields);
        if (value == null)
            return null;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long number(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        return value == null ? 0 : value.longValue();
    }

    private record Endpoint(String path, String trId, RankingType type) {
        private static Endpoint forType(RankingType type) {
            return switch (type) {
                case TURNOVER, VOLUME -> new Endpoint("/uapi/domestic-stock/v1/quotations/volume-rank",
                        "FHPST01710000", type);
                case PRICE_RISE -> new Endpoint("/uapi/domestic-stock/v1/ranking/fluctuation",
                        "FHPST01700000", type);
                case TRADE_STRENGTH -> new Endpoint("/uapi/domestic-stock/v1/ranking/volume-power",
                        "FHPST01680000", type);
                case HIGH_PROXIMITY -> new Endpoint("/uapi/domestic-stock/v1/ranking/near-new-highlow",
                        "FHPST01870000", type);
            };
        }

        private UriBuilder apply(UriBuilder builder, Market market) {
            String marketCode = market == Market.KOSPI ? "0001" : market == Market.KOSDAQ ? "1001" : "0000";
            builder.queryParam("FID_COND_MRKT_DIV_CODE", "J")
                    .queryParam("FID_INPUT_ISCD", marketCode)
                    .queryParam("FID_TRGT_CLS_CODE", "0")
                    .queryParam("FID_TRGT_EXLS_CLS_CODE", "0")
                    .queryParam("FID_DIV_CLS_CODE", "0")
                    .queryParam("FID_BLNG_CLS_CODE", "0")
                    .queryParam("FID_INPUT_PRICE_1", "")
                    .queryParam("FID_INPUT_PRICE_2", "")
                    .queryParam("FID_VOL_CNT", "")
                    .queryParam("FID_INPUT_DATE_1", "")
                    .queryParam("FID_INPUT_OPTION_1", "0")
                    .queryParam("FID_INPUT_OPTION_2", "0")
                    .queryParam("FID_RANK_SORT_CLS_CODE", sortCode())
                    .queryParam("FID_COND_SCR_DIV_CODE", screenCode());
            if (type == RankingType.HIGH_PROXIMITY) {
                builder.queryParam("FID_APLY_RANG_VOL", "0")
                        .queryParam("FID_INPUT_CNT_1", "0")
                        .queryParam("FID_INPUT_CNT_2", "100")
                        .queryParam("FID_PRC_CLS_CODE", "0")
                        .queryParam("FID_APLY_RANG_PRC_1", "")
                        .queryParam("FID_APLY_RANG_PRC_2", "");
            }
            return builder;
        }

        private String sortCode() {
            return type == RankingType.TURNOVER ? "3" : "0";
        }

        private String screenCode() {
            return switch (type) {
                case TURNOVER, VOLUME -> "20171";
                case PRICE_RISE -> "20170";
                case TRADE_STRENGTH -> "20168";
                case HIGH_PROXIMITY -> "20187";
            };
        }
    }
}
