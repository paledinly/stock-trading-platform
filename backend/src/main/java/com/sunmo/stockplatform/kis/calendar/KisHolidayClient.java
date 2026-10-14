/*
 * KIS 국내휴장일조회 API로 기준일부터 날짜별 개장 여부(opnd_yn)를 받아 오는 파일이에요.
 * 형식이 이상한 행은 추측하지 않고 건너뛰어요.
 * 거래일 달력(closing 패키지의 ClosingTradingCalendar 쪽)이 휴장일과 다음 거래일을 정할 때 쓰는 것으로 보여요.
 */
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
/**
 * KIS 국내휴장일조회 API(프로그램끼리 정보를 주고받는 창구)를 부르는 클라이언트예요.
 * KIS는 하루 1회 정도만 호출하길 권하고, 실전 계좌 키에서만 동작해요.
 * 증권사에 전화해 "이 날부터 장 열리는 날이 언제예요?"라고 묻고 달력에 표시해 오는 직원과 같아요.
 */
@Component
public class KisHolidayClient {
    private static final String ENDPOINT = "/uapi/domestic-stock/v1/quotations/chk-holiday";
    private static final String TR_ID = "CTCA0903R";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    /**
     * 휴장일 조회에 필요한 부품을 받아요.
     * 받는 것: KIS용 RestClient, KIS 설정, 토큰(API 호출용 임시 출입증) 관리자, 호출 속도 조절기. 돌려주는 것: 없음(생성자).
     */
    public KisHolidayClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /** Market-open flag (opnd_yn) per date from the base date onward, in response order. */
    /**
     * 기준일부터 날짜별 개장 여부를 조회해요.
     * 받는 것: 기준일. 돌려주는 것: 날짜 → 개장 여부(true=개장) 맵, KIS 응답 순서 그대로.
     */
    public Map<LocalDate, Boolean> fetch(LocalDate baseDate) {
        // 준비: 인증정보 확인 후, 공용 호출 속도 조절기를 거쳐 KIS를 불러요.
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
            // rt_cd가 "0"이 아니면 KIS가 실패를 알린 것이라 알맞은 예외로 바꿔 던져요.
            if (response == null || !"0".equals(response.path("rt_cd").asText())) {
                KisResponseErrors.failure("KIS holiday request failed",
                        response == null ? "EMPTY_RESPONSE" : response.path("msg_cd").asText(),
                        response == null ? "empty response" : response.path("msg1").asText());
            }
            return response;
        });
        // 처리: Y/N이 분명한 행만 담고, 나머지는 모르는 값으로 남겨 둬요.
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
