package com.sunmo.stockplatform.kis.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import com.sunmo.stockplatform.kis.config.KisResponseErrors;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** KIS 국내휴장일조회. KIS recommends at most one call per day; real-account keys only. */
@Component
public class KisHolidayClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/chk-holiday";
    private static final String TR_ID = "CTCA0903R";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    public KisHolidayClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /** Market-open flag (opnd_yn) per date from the base date onward, in response order. */
    public Map<LocalDate, Boolean> fetch(LocalDate baseDate) {
        properties.requireCredentials();
        JsonNode body = requests.execute(false, () -> {
            JsonNode response = client.get().uri(builder -> builder.path(ENDPOINT)
                    .queryParam("BASS_DT", baseDate.format(DATE))
                    .queryParam("CTX_AREA_NK", "")
                    .queryParam("CTX_AREA_FK", "")
                    .build())
                    .header("authorization", "Bearer " + tokens.getAccessToken())
                    .header("appkey", properties.appKey())
                    .header("appsecret", properties.appSecret())
                    .header("tr_id", TR_ID)
                    .header("custtype", "P")
                    .retrieve().body(JsonNode.class);
            if (response == null || !"0".equals(response.path("rt_cd").asText())) {
                KisResponseErrors.failure("KIS holiday request failed",
                        response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText(),
                        response == null ? "empty response" : response.path("msg1").asText());
            }
            return response;
        });
        Map<LocalDate, Boolean> days = new LinkedHashMap<>();
        for (JsonNode row : body.path("output")) {
            String open = row.path("opnd_yn").asText();
            if (!"Y".equals(open) && !"N".equals(open)) continue;
            try { days.put(LocalDate.parse(row.path("bass_dt").asText(), DATE), "Y".equals(open)); }
            catch (DateTimeParseException ignored) { /* malformed rows are not guessed */ }
        }
        return days;
    }
}
