/*
 * KIS 종목별 투자자(외국인·기관·개인) 일별 매매 기록이 얼마나 과거까지 조회되는지 확인하는 진단용 클라이언트예요(연구용).
 * 기관·외국인 연속 순매수 종목의 이후 성과를 검증하려면 과거 일별 기록이 필요해서 만들었어요. 운영 수집이나 점수에는 쓰지 않아요.
 */
package com.sunmo.stockplatform.kis.investor;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunmo.stockplatform.kis.auth.KisTokenManager;
import com.sunmo.stockplatform.kis.config.KisProperties;
import com.sunmo.stockplatform.kis.config.KisRequestExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 투자자 일별 기록 조회 진단 클라이언트예요. 호출 한 번에 응답의 모양(행 수, 날짜 범위, 필드 이름)만 요약해요. */
@Component
public class KisInvestorHistoryProbeClient {
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 시험할 KIS 조회 종류예요.
     * DAILY: 종목별 투자자매매동향(일별), 기준일을 지정해요. RECENT: 주식현재가 투자자, 최근 기록만 줘요.
     */
    public enum Kind {
        DAILY("/uapi/domestic-stock/v1/quotations/investor-trade-by-stock-daily", "FHPTJ04160001"),
        RECENT("/uapi/domestic-stock/v1/quotations/inquire-investor", "FHKST01010900");

        final String path;
        final String trId;

        Kind(String path, String trId) { this.path = path; this.trId = trId; }
    }

    private final RestClient client;
    private final KisProperties properties;
    private final KisTokenManager tokens;
    private final KisRequestExecutor requests;

    /** 하는 일: KIS 공용 RestClient, 설정, 토큰 관리자, 공용 속도 조절기를 받아 둬요. */
    public KisInvestorHistoryProbeClient(RestClient kisRestClient, KisProperties properties, KisTokenManager tokens,
            KisRequestExecutor requests) {
        this.client = kisRestClient;
        this.properties = properties;
        this.tokens = tokens;
        this.requests = requests;
    }

    /**
     * 하는 일: 한 종목의 투자자 기록을 KIS에 한 번 조회해 응답을 요약해요.
     * 받는 것: 조회 종류, 종목코드, 기준일(DAILY에서만 써요).
     * 돌려주는 것: 응답 코드·메시지와 출력 묶음(output, output1, output2)별 행 수·날짜 범위·필드 이름·첫 행·마지막 행.
     */
    public Probe probe(Kind kind, String stockCode, LocalDate date) {
        properties.requireCredentials();
        JsonNode body = requests.execute(false, () -> client.get().uri(builder -> {
                    builder.path(kind.path)
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", stockCode);
                    if (kind == Kind.DAILY) {
                        builder.queryParam("FID_INPUT_DATE_1", date.format(DATE))
                                .queryParam("FID_ORG_ADJ_PRC", "")
                                .queryParam("FID_ETC_CLS_CODE", "");
                    }
                    return builder.build();
                })
                .header("authorization", "Bearer " + tokens.getAccessToken())
                .header("appkey", properties.appKey())
                .header("appsecret", properties.appSecret())
                .header("tr_id", kind.trId)
                .header("custtype", "P")
                .retrieve().body(JsonNode.class));
        if (body == null)
            return new Probe(kind.name(), stockCode, date, null, "EMPTY_RESPONSE", "empty response", List.of());
        List<Block> blocks = new ArrayList<>();
        for (String name : List.of("output", "output1", "output2")) {
            JsonNode node = body.path(name);
            if (node.isMissingNode() || node.isNull())
                continue;
            List<JsonNode> rows = new ArrayList<>();
            if (node.isArray()) node.forEach(rows::add); else rows.add(node);
            TreeSet<String> dates = new TreeSet<>();
            for (JsonNode row : rows) {
                String day = row.path("stck_bsop_date").asText("").trim();
                if (day.length() == 8)
                    dates.add(day);
            }
            List<String> fields = new ArrayList<>();
            if (!rows.isEmpty())
                rows.getFirst().fieldNames().forEachRemaining(fields::add);
            blocks.add(new Block(name, rows.size(), dates.isEmpty() ? null : dates.first(),
                    dates.isEmpty() ? null : dates.last(), fields, rows.isEmpty() ? null : flat(rows.getFirst()),
                    rows.isEmpty() ? null : flat(rows.getLast())));
        }
        return new Probe(kind.name(), stockCode, date, body.path("rt_cd").asText(), body.path("msg_cd").asText(),
                body.path("msg1").asText(), blocks);
    }

    /** 하는 일: 한 행의 필드를 이름 → 문자열 값 맵으로 바꿔요(시세·수량 숫자만 있고 인증정보는 없어요). */
    private static Map<String, String> flat(JsonNode row) {
        Map<String, String> out = new LinkedHashMap<>();
        row.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
        return out;
    }

    /** 응답 요약이에요. blocks는 출력 묶음별 요약이에요. */
    public record Probe(String kind, String stockCode, LocalDate requestedDate, String rtCd, String msgCd, String message,
            List<Block> blocks) { }

    /** 출력 묶음 하나의 요약이에요(행 수, 날짜 범위, 필드 이름, 첫 행, 마지막 행). */
    public record Block(String name, int rows, String firstDate, String lastDate, List<String> fields,
            Map<String, String> firstRow, Map<String, String> lastRow) { }
}
